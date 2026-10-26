package io.github.mobtalk.game;

import io.github.mobtalk.core.MobClock;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.mobtalk.core.CivilRegistry;
import io.github.mobtalk.core.Initiative;
import io.github.mobtalk.core.Officialdom;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.*;

/** The officials' windows for papers (track «чиновники», docs/officials.md): a player with a passport and no open passport case who
 * Shift+right-clicks an official gets the list of papers that office issues ({@link CivilRegistry#papers}) as clickable lines in the
 * chat; a click ({@code /spravka <n>}) issues the paper at once — a certificate from the book of civil acts, or a справка filled from
 * what the game knows. Without a passport there are no papers («сначала паспорт»). A village without a head or an inspector has the
 * clerk issue their papers too. A building permit keeps the inspector away for {@link CivilRegistry#PERMIT_DAYS} days. Server thread. */
final class CivilOffice {
    /** How far the player may step from the official between the list and the click; how long the list stays valid. */
    static final double REACH = 8;
    static final long MENU_TICKS = 20 * 120;
    /** How often the book of acts reads the family tree (so an act gets its real day). */
    static final long SYNC_PERIOD = 600, SYNC_PHASE = 13;

    private final MobGameBridge bridge;
    private final Map<UUID, Menu> menus = new HashMap<>();

    /** The list shown: whose window, the offers, until when; {@code withoutPassport} — only the uncollected certificates were offered,
     * the next Shift+right-click on the same clerk opens a passport case. */
    private record Menu(UUID official, Officialdom.Rank rank, List<CivilRegistry.Offer> offers, long until, boolean withoutPassport) { }
    /** The stale lines of every mob are forgotten once per server start. */
    private boolean cleaned;

    CivilOffice(MobGameBridge bridge) { this.bridge = bridge; }

    boolean enabled() { return bridge.officials.enabled() && bridge.config.officialPapers && OfficialContent.SPRAVKA != null; }

    static void registerCommand(java.util.function.Function<ServerPlayerEntity, CivilOffice> office) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(CommandManager.literal("spravka")
                .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    CivilOffice civil = player == null ? null : office.apply(player);
                    if (civil != null) civil.choose(player, IntegerArgumentType.getInteger(ctx, "n"));
                    return 1;
                }))));
    }

    void tick(ServerWorld world) {
        if (!enabled() || world != world.getServer().getOverworld() || world.getTime() % SYNC_PERIOD != SYNC_PHASE) return;
        if (!cleaned) { cleaned = true; bridge.memory.forgetStale(null, Officialdom.STALE); }
        sync(world);
    }
    /** Reads the family tree into the book; a new marriage's certificate is handed to its players at once, as at a real
     * registration (one who is not online collects it at the clerk's window, even without a passport). */
    private void sync(ServerWorld world) {
        long day = PassportOffice.day(world);
        for (CivilRegistry.Act act : bridge.memory.civilSync(day)) {
            if (act.kind() != CivilRegistry.Kind.MARRIAGE) continue;
            for (int i = 0; i < 2; i++) {
                UUID id = act.id(i);
                ServerPlayerEntity player = id == null ? null : world.getServer().getPlayerManager().getPlayer(id);
                if (player == null || bridge.memory.civilRepeated(CivilRegistry.Paper.MARRIAGE_CERTIFICATE, act.number(), id)) continue;
                CivilRegistry.Facts facts = new CivilRegistry.Facts(MobGameBridge.playerName(player), "", day, 0, List.of(), List.of(), false);
                String number = bridge.memory.civilIssue(CivilRegistry.Paper.MARRIAGE_CERTIFICATE, act.number(), id);
                player.getInventory().offerOrDrop(OfficialContent.paper(CivilRegistry.Paper.MARRIAGE_CERTIFICATE, id, number, act.number(),
                        CivilRegistry.lines(CivilRegistry.Paper.MARRIAGE_CERTIFICATE, act, facts), Text.translatable("mobtalk.civil.registry").getString(), day));
                player.sendMessage(Text.translatable("mobtalk.civil.wedding", number).formatted(Formatting.GOLD), false);
                player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1f, 1f);
            }
        }
    }

    /** The window of {@code official}: true when it showed the list (or the refusal without a passport). */
    boolean window(ServerWorld world, ServerPlayerEntity player, VillagerEntity official, Officialdom.Rank rank, Set<Officialdom.Rank> staff) {
        if (!enabled()) return false;
        Text who = bridge.officials.titled(official, rank);
        bridge.memory.forgetStale(official.getUuid(), Officialdom.STALE);
        sync(world);
        boolean passport = OfficialContent.hasPassport(player);
        boolean openOrder = openOrder(world, player, official);
        List<CivilRegistry.Offer> offers = new ArrayList<>();
        if (!passport) {
            if (rank != Officialdom.Rank.CLERK) { say(player, who, Text.translatable("mobtalk.civil.no_passport"), Formatting.GRAY); return true; }
            // Without a passport the clerk still hands over the certificates the player never collected (the act is his already);
            // the next Shift+right-click on her opens a passport case.
            Menu last = menus.get(player.getUuid());
            if (last != null && last.withoutPassport() && last.official().equals(official.getUuid()) && world.getTime() <= last.until()) { menus.remove(player.getUuid()); return false; }
            for (CivilRegistry.Offer offer : bridge.memory.civilPapers(player.getUuid(), rank, openOrder))
                if (offer.paper().certificate && offer.act() > 0 && !bridge.memory.civilRepeated(offer.paper(), offer.act(), player.getUuid())) offers.add(offer);
            if (offers.isEmpty()) return false; // the clerk opens a passport case
        } else {
            offers.addAll(bridge.memory.civilPapers(player.getUuid(), rank, openOrder));
            // The clerk signs for the offices the village lacks.
            if (rank == Officialdom.Rank.CLERK) for (Officialdom.Rank other : List.of(Officialdom.Rank.HEAD, Officialdom.Rank.INSPECTOR))
                if (staff != null && !staff.contains(other)) offers.addAll(bridge.memory.civilPapers(player.getUuid(), other, openOrder));
        }
        menus.put(player.getUuid(), new Menu(official.getUuid(), rank, offers, world.getTime() + MENU_TICKS, !passport));
        say(player, who, Text.translatable(passport ? "mobtalk.civil.window" : "mobtalk.civil.window_uncollected"), Formatting.GOLD);
        for (int i = 0; i < offers.size(); i++) {
            CivilRegistry.Offer offer = offers.get(i);
            boolean repeated = bridge.memory.civilRepeated(offer.paper(), offer.act(), player.getUuid());
            int fee = offer.paper().fee(repeated, bridge.config.passportFee);
            String label = (repeated ? "повторное " : "") + offer.paper().title + (offer.about().isBlank() ? "" : " — " + offer.about());
            MutableText line = Text.literal("  [" + (i + 1) + "] ").formatted(Formatting.DARK_GRAY).append(Text.literal(label).formatted(Formatting.AQUA));
            if (fee > 0) line.append(Text.translatable("mobtalk.civil.fee", fee).formatted(Formatting.GREEN));
            int n = i + 1;
            line.styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/spravka " + n))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("mobtalk.civil.click"))));
            player.sendMessage(line, false);
        }
        if (!passport) player.sendMessage(Text.translatable("mobtalk.civil.passport_next").formatted(Formatting.DARK_GRAY), false);
        world.playSound(null, official.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 1f, 1f);
        return true;
    }

    /** A click on the list: the paper is made out and handed over now. */
    void choose(ServerPlayerEntity player, int n) {
        Menu menu = menus.get(player.getUuid());
        ServerWorld world = player.getServerWorld();
        if (menu == null || world.getTime() > menu.until() || n < 1 || n > menu.offers().size()) {
            player.sendMessage(Text.translatable("mobtalk.civil.expired").formatted(Formatting.GRAY), false);
            return;
        }
        if (!(world.getEntity(menu.official()) instanceof VillagerEntity official) || !official.isAlive() || official.squaredDistanceTo(player) > REACH * REACH) {
            player.sendMessage(Text.translatable("mobtalk.civil.too_far").formatted(Formatting.GRAY), false);
            return;
        }
        issue(world, player, official, menu.rank(), menu.offers().get(n - 1));
    }

    /** Makes out the paper of {@code offer} to the player (the fee taken first) and gives it. True when it was issued. */
    boolean issue(ServerWorld world, ServerPlayerEntity player, VillagerEntity official, Officialdom.Rank rank, CivilRegistry.Offer offer) {
        CivilRegistry.Paper paper = offer.paper();
        Text who = bridge.officials.titled(official, rank);
        if (lunch(world, rank)) { say(player, who, Text.translatable("mobtalk.paper.lunch"), Formatting.GRAY); return false; }
        boolean repeated = bridge.memory.civilRepeated(paper, offer.act(), player.getUuid());
        int fee = paper.fee(repeated, bridge.config.passportFee);
        if (fee > 0 && PassportOffice.count(player, "minecraft:emerald") < fee) {
            say(player, who, Text.translatable("mobtalk.civil.no_fee", fee), Formatting.GRAY);
            return false;
        }
        CivilRegistry.Act act = offer.act() > 0 ? bridge.memory.civilAct(offer.act()) : null;
        if (paper.act != null && act == null) return false;
        long day = PassportOffice.day(world);
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, official.getBlockPos());
        String holder = MobGameBridge.playerName(player);
        CivilRegistry.Facts facts = new CivilRegistry.Facts(holder, zone == null ? "" : zone.title().getString(), day, reputation(world, player, official),
                bridge.memory.civilFamily(player.getUuid()), household(world, player), openOrder(world, player, official));
        List<String> lines = CivilRegistry.lines(paper, act, facts);
        if (fee > 0) take(player, fee);
        String number = bridge.memory.civilIssue(paper, offer.act(), player.getUuid());
        String issuer = bridge.officials.name(official);
        player.getInventory().offerOrDrop(OfficialContent.paper(paper, player.getUuid(), number, offer.act(), lines, issuer, day));
        if (paper == CivilRegistry.Paper.BUILD_PERMIT && zone != null) bridge.memory.officialFavour(zone.key(), player.getUuid(), day, CivilRegistry.PERMIT_DAYS);
        world.playSound(null, official.getBlockPos(), SoundEvents.BLOCK_WOOD_HIT, SoundCategory.NEUTRAL, 1f, 0.6f);
        world.playSound(null, official.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 1f, 0.9f);
        say(player, who, Text.translatable("mobtalk.civil.issued_now", (repeated ? "повторное " : "") + paper.title, number), Formatting.GREEN);
        bridge.memory.recordEvent(official.getUuid(), MobGameBridge.type(official), official.getName().getString(),
                "Ты выдал(а) игроку " + holder + " «" + paper.title + "»" + (fee > 0 ? " за госпошлину" : ""), MobClock.now());
        bridge.initiative.raise(new Initiative.Cue(official.getUuid(), issuer, Initiative.Trigger.GIFT, cue(paper, lines), List.of(), MobClock.now()));
        return true;
    }

    /** What the official says over the paper (the code decided; the model only speaks). */
    private static String cue(CivilRegistry.Paper paper, List<String> lines) {
        String text = "ты только что выдал(а) игроку «" + paper.title + "» (" + String.join("; ", lines) + "). ";
        return text + switch (paper) {
            case NO_RECORD -> "Казённо сообщи, что по документам он не рождался, и посоветуй установить факт рождения в судебном порядке.";
            case ALIVE -> "Казённо напомни, что справка действительна один день, завтра нужна новая.";
            case BUILD_PERMIT -> "Казённо предупреди, что разрешение действует семь дней и строить можно только согласно проекту.";
            case HOUSING -> "Казённо добавь, что перепланировку всё же надо согласовать.";
            case DEATH_CERTIFICATE, DEATH_NOTE -> "Скажи сухо, но с казённым соболезнованием.";
            case MARRIAGE_CERTIFICATE -> "Поздравь казённо и напомни не терять: повторное — за госпошлину.";
            default -> "Скажи казённо одну фразу: «получите, распишитесь» и что-нибудь про порядок.";
        };
    }

    private static boolean lunch(ServerWorld world, Officialdom.Rank rank) { return rank == Officialdom.Rank.CLERK && io.github.mobtalk.core.Paperwork.lunch(world.getTimeOfDay()); }

    private boolean openOrder(ServerWorld world, ServerPlayerEntity player, VillagerEntity official) {
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, official.getBlockPos());
        return zone != null && bridge.memory.officialFine(zone.key(), player.getUuid()) != null;
    }
    /** What the village thinks of the player: the mean of the villagers' relations to him around the official, −100..100. */
    private int reputation(ServerWorld world, ServerPlayerEntity player, VillagerEntity official) {
        List<VillagerEntity> near = world.getEntitiesByClass(VillagerEntity.class, official.getBoundingBox().expand(48), v -> v.isAlive() && !v.isBaby());
        if (near.isEmpty()) return 0;
        long sum = 0;
        for (VillagerEntity v : near) sum += bridge.memory.relationship(v.getUuid(), player.getUuid());
        return (int) (sum / near.size());
    }
    /** The household book: the animals around the player, by kind («корова — 2»). */
    private static List<String> household(ServerWorld world, ServerPlayerEntity player) {
        Map<String, Integer> counts = new TreeMap<>();
        for (AnimalEntity a : world.getEntitiesByClass(AnimalEntity.class, player.getBoundingBox().expand(32), a -> a.isAlive()))
            counts.merge(a.getType().getName().getString().toLowerCase(Locale.ROOT), 1, Integer::sum);
        List<String> out = new ArrayList<>();
        counts.forEach((kind, n) -> out.add(kind + " — " + n + " гол."));
        return out.size() > 6 ? out.subList(0, 6) : out;
    }
    private static void take(ServerPlayerEntity player, int emeralds) {
        for (int i = 0; i < player.getInventory().size() && emeralds > 0; i++) {
            net.minecraft.item.ItemStack s = player.getInventory().getStack(i);
            if (!s.isOf(net.minecraft.item.Items.EMERALD)) continue;
            int n = Math.min(emeralds, s.getCount()); s.decrement(n); emeralds -= n;
        }
    }
    private static void say(ServerPlayerEntity player, Text who, Text what, Formatting color) {
        player.sendMessage(Text.empty().append(who.copy().formatted(Formatting.WHITE)).append(": ").append(what.copy().formatted(color)), false);
    }
    /** The list the player was shown last (self-tests). */
    List<CivilRegistry.Offer> menu(UUID player) { Menu m = menus.get(player); return m == null ? List.of() : m.offers(); }
    void clearRuntime() { menus.clear(); }
}
