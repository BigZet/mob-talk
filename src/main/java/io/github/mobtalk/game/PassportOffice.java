package io.github.mobtalk.game;

import io.github.mobtalk.core.Initiative;
import io.github.mobtalk.core.Officialdom;
import io.github.mobtalk.core.Paperwork;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** The passport office (track «чиновники», stage 2, docs/officials.md): Shift+right-click an official is a visit to the office.
 * The clerk opens a case and {@link Paperwork} sends the player around the offices; at the clerk's window there is a queue — a few
 * villagers walk up and stand in line in front of the desk, one is served every {@code queueSeconds}, and «я только спросить»
 * (Shift+right-click again) sometimes gets the player to the window at once and otherwise angers the queue. The clerk is at lunch
 * 13:00–14:00. A taken envelope rushes the case, a sweet skips the current wait. Server thread only. */
final class PassportOffice {
    static final long PERIOD = 20, PHASE = 7;
    /** How far from the desk villagers are called to the queue, how far the player may step away before losing the place. */
    static final double CALL = 16, LEAVE = 8;
    /** At most this many real villagers stand in a line (the rest of the queue «went out for a minute»). */
    static final int LINE = 4;
    /** Small gifts that hurry a paper along with a corrupt official («шоколадка»). */
    static final Set<Item> SWEETS = Set.of(Items.COOKIE, Items.CAKE, Items.PUMPKIN_PIE, Items.HONEY_BOTTLE);

    private final MobGameBridge bridge;
    /** The player waiting at a clerk's window, by player. */
    private final Map<UUID, Ticket> tickets = new HashMap<>();
    private final Random random = new Random();

    /** A place in a queue: the clerk, her desk, how many are still ahead, the villagers standing in line, when the next is served. */
    private static final class Ticket {
        final net.minecraft.registry.RegistryKey<net.minecraft.world.World> world; final UUID clerk; final BlockPos desk;
        final List<UUID> line = new ArrayList<>(); final List<Vec3d> spots = new ArrayList<>();
        int ahead; long next; boolean asked;
        Ticket(net.minecraft.registry.RegistryKey<net.minecraft.world.World> world, UUID clerk, BlockPos desk) { this.world = world; this.clerk = clerk; this.desk = desk; }
    }

    PassportOffice(MobGameBridge bridge) { this.bridge = bridge; }

    boolean enabled() { return bridge.officials.enabled() && bridge.config.paperwork && OfficialContent.PASSPORT != null; }

    // ---- The visit ----

    /** Shift+right-click on an official. True when the office handled it (no trades open). */
    boolean visit(ServerWorld world, ServerPlayerEntity player, VillagerEntity official) {
        if (!enabled()) return false;
        Officialdom.Rank rank = OfficialContent.rank(official);
        if (rank == null) return false;
        official.getLookControl().lookAt(player, 30f, 30f);
        if (rank == Officialdom.Rank.CLERK && !Paperwork.lunch(world.getTimeOfDay())) {
            Ticket ticket = tickets.get(player.getUuid());
            if (ticket != null && ticket.clerk.equals(official.getUuid())) { justAsk(world, player, official, ticket); return true; }
            if (ticket != null) leave(world, ticket, player.getUuid());
            BlockPos desk = desk(official);
            long day = day(world);
            int waiting = desk == null ? 0 : (int) Math.min(LINE, callable(world, desk).size());
            int ahead = bridge.config.queueSeconds <= 0 ? 0 : Paperwork.queue(official.getUuid(), day, waiting);
            if (ahead > 0 && desk != null) { join(world, player, official, desk, ahead); return true; }
        }
        serve(world, player, official, rank, false);
        return true;
    }

    /** The window: what the office does for the player now. {@code opened} — a case was just opened (the application goes next). */
    private void serve(ServerWorld world, ServerPlayerEntity player, VillagerEntity official, Officialdom.Rank rank, boolean opened) {
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, official.getBlockPos());
        Map<Officialdom.Rank, List<VillagerEntity>> staff = zone == null ? Map.of(rank, List.of(official)) : bridge.officials.officials(world, zone);
        long day = day(world);
        Paperwork.Case kase = bridge.memory.paperCase(player.getUuid());
        // No open passport case: the window for papers (a clerk without the player's passport opens a case as before).
        if (kase == null && !opened && bridge.civil.window(world, player, official, rank, staff.keySet())) return;
        Paperwork.Pockets pockets = new Paperwork.Pockets() {
            @Override public int count(String item) { return PassportOffice.count(player, item); }
            @Override public int emeralds() { return PassportOffice.count(player, "minecraft:emerald"); }
        };
        Officialdom.Rank office = rank;
        // A paper whose office the village lacks (no head, no inspector): the clerk signs it herself.
        if (kase != null && kase.current() != null && kase.current().office != rank && rank == Officialdom.Rank.CLERK && !staff.containsKey(kase.current().office))
            office = kase.current().office;
        Paperwork.Visit visit = Paperwork.visit(kase, office, day, world.getTimeOfDay(), bridge.config.paperLunch, pockets, bridge.config.passportFee, bridge.config.passportDays);
        Text who = bridge.officials.titled(official, rank);
        switch (visit.result()) {
            case LUNCH -> say(player, who, Text.translatable("mobtalk.paper.lunch"), Formatting.GRAY);
            case NO_CASE -> say(player, who, Text.translatable("mobtalk.paper.no_case"), Formatting.GRAY);
            case OPENED -> {
                Set<Officialdom.Rank> offices = EnumSet.copyOf(staff.isEmpty() ? Set.of(Officialdom.Rank.CLERK) : staff.keySet());
                Paperwork.Case opened2 = bridge.memory.paperOpen(player.getUuid(), MobGameBridge.playerName(player), zone == null ? "" : zone.key(),
                        zone == null ? "" : zone.title().getString(), bridge.config.paperworkSteps, offices, day);
                List<String> titles = opened2.steps().stream().map(s -> "«" + s.title + "»").toList();
                say(player, who, Text.translatable("mobtalk.paper.opened", opened2.number(), String.join(", ", titles)), Formatting.GOLD);
                world.playSound(null, official.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 1f, 1f);
                cue(official, rank, "игрок пришёл за паспортом; ты завёл(а) дело №" + opened2.number() + ". Перечисли казённо, какие справки ему собрать: " + String.join(", ", titles) + ".");
                if (!opened) serve(world, player, official, rank, true);
            }
            case WRONG_OFFICE -> say(player, who, Text.translatable("mobtalk.paper.wrong_office", visit.step().title, Text.translatable("mobtalk.paper.to." + visit.step().office.path)), Formatting.GRAY);
            case NOT_READY -> say(player, who, visit.readyDay() - day <= 1 ? Text.translatable("mobtalk.paper.tomorrow", visit.step().title)
                    : Text.translatable("mobtalk.paper.not_ready", visit.step().title, visit.readyDay()), Formatting.GRAY);
            case MISSING -> {
                if (opened) return; // the case was just opened: the list of papers is enough for now
                List<String> need = new ArrayList<>();
                if (!visit.item().isEmpty() && count(player, visit.item()) < visit.count()) need.add(visit.count() + " × " + itemName(visit.item()));
                if (visit.fee() > 0 && count(player, "minecraft:emerald") < visit.fee()) need.add(Text.translatable("mobtalk.paper.fee", visit.fee()).getString());
                say(player, who, Text.translatable("mobtalk.paper.missing", visit.step().title, String.join(", ", need)), Formatting.GRAY);
            }
            case ACCEPTED -> {
                take(player, visit);
                bridge.memory.paperSave(kase);
                say(player, who, Text.translatable("mobtalk.paper.accepted", visit.step().title, visit.readyDay()), Formatting.YELLOW);
                world.playSound(null, official.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 1f, 0.9f);
                cue(official, rank, "ты принял(а) у игрока документы на «" + visit.step().title + "» и велел(а) прийти к " + visit.readyDay() + "-му дню. Скажи это казённо и устало: «приходите завтра».");
            }
            case DONE -> {
                take(player, visit);
                bridge.memory.paperSave(kase);
                stamp(world, official);
                Paperwork.Step next = visit.next();
                if (next == Paperwork.Step.ISSUE) say(player, who, Text.translatable("mobtalk.paper.done_last", visit.step().title, visit.readyDay()), Formatting.YELLOW);
                else say(player, who, Text.translatable("mobtalk.paper.done", visit.step().title, next.title, Text.translatable("mobtalk.paper.to." + next.office.path)), Formatting.YELLOW);
                cue(official, rank, "ты оформил(а) игроку «" + visit.step().title + "». Дальше ему нужно «" + next.title + "» — отправь его казённым языком.");
            }
            case ISSUED -> {
                take(player, visit);
                issue(world, player, official, rank, zone, day, false);
            }
        }
    }

    /** The passport is ready: the case closes, the player gets it. */
    void issue(ServerWorld world, ServerPlayerEntity player, VillagerEntity official, Officialdom.Rank rank, VillageDefenseController.Zone zone, long day, boolean rushed) {
        Paperwork.Case kase = bridge.memory.paperCase(player.getUuid());
        String place = kase != null && !kase.villageTitle().isBlank() ? kase.villageTitle() : zone == null ? "" : zone.title().getString();
        String number = bridge.memory.paperIssue(player.getUuid(), kase != null ? kase.village() : zone == null ? "" : zone.key(), day);
        String issuer = bridge.officials.name(official);
        player.getInventory().offerOrDrop(OfficialContent.passport(player.getUuid(), MobGameBridge.playerName(player), number, place, issuer, day));
        stamp(world, official);
        world.playSound(null, official.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.NEUTRAL, 0.6f, 1.2f);
        say(player, bridge.officials.titled(official, rank), Text.translatable(rushed ? "mobtalk.paper.issued_rushed" : "mobtalk.paper.issued", number), Formatting.GREEN);
        long now = System.currentTimeMillis();
        bridge.memory.recordEvent(official.getUuid(), MobGameBridge.type(official), official.getName().getString(),
                "Ты выдал(а) игроку " + MobGameBridge.playerName(player) + " паспорт " + number + (rushed ? " — без очереди и справок, за конверт" : ""), now);
        cue(official, rank, "ты только что выдал(а) игроку паспорт " + number + (rushed ? ", хотя половины справок нет: конверт решил вопрос. Намекни, чтобы не болтал." : ". Поздравь казённо, напомни не терять: восстановление через суд."));
    }

    /** A corrupt clerk took an envelope while the player has a case: everything is «found», the passport is handed over now. True when
     * there was a case to rush. */
    boolean rush(ServerWorld world, ServerPlayerEntity player, VillagerEntity clerk, VillageDefenseController.Zone zone) {
        if (!enabled()) return false;
        Paperwork.Case kase = bridge.memory.paperCase(player.getUuid());
        if (kase == null) return false;
        Paperwork.rush(kase);
        bridge.memory.paperSave(kase);
        leave(world, tickets.get(player.getUuid()), player.getUuid());
        issue(world, player, clerk, Officialdom.Rank.CLERK, zone, day(world), true);
        return true;
    }

    /** From «Подарить»: a sweet to a corrupt official while the player has a case — the current paper is ready today, the queue is
     * skipped. Null when this is no such gift (the ordinary gift goes on). */
    String sweet(ServerWorld world, ServerPlayerEntity player, VillagerEntity official, ItemStack stack) {
        if (!enabled() || stack.isEmpty() || !SWEETS.contains(stack.getItem())) return null;
        Officialdom.Rank rank = OfficialContent.rank(official);
        if (rank == null || Officialdom.honest(official.getUuid(), bridge.config.officialHonestPercent)) return null;
        Paperwork.Case kase = bridge.memory.paperCase(player.getUuid());
        Ticket ticket = tickets.get(player.getUuid());
        boolean queued = ticket != null && ticket.clerk.equals(official.getUuid());
        boolean hurried = kase != null && kase.current() != null && kase.current().office == rank && Paperwork.hurry(kase, day(world));
        if (!hurried && !queued) return null;
        stack.decrement(1);
        if (hurried) bridge.memory.paperSave(kase);
        world.playSound(null, official.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1.1f);
        bridge.memory.recordBribe(official.getUuid(), MobGameBridge.type(official), official.getName().getString(), player.getUuid(), MobGameBridge.playerName(player),
                3, "принёс тебе «шоколадку» (" + stack.getName().getString() + "), чтобы ускорить бумаги; ты взял(а)", System.currentTimeMillis());
        if (queued) { leave(world, ticket, player.getUuid()); serve(world, player, official, rank, false); }
        return Text.translatable("mobtalk.paper.sweet", bridge.officials.titled(official, rank)).getString();
    }

    // ---- The queue ----

    private void join(ServerWorld world, ServerPlayerEntity player, VillagerEntity clerk, BlockPos desk, int ahead) {
        Ticket ticket = new Ticket(world.getRegistryKey(), clerk.getUuid(), desk);
        ticket.ahead = ahead;
        ticket.next = world.getTime() + bridge.config.queueSeconds * 20L;
        Direction front = world.getBlockState(desk).contains(OfficialContent.Desk.FACING) ? world.getBlockState(desk).get(OfficialContent.Desk.FACING) : Direction.SOUTH;
        for (int i = 1; i <= LINE + 1; i++) ticket.spots.add(Vec3d.ofBottomCenter(desk.offset(front, 1 + i)));
        for (VillagerEntity v : callable(world, desk)) {
            if (ticket.line.size() >= Math.min(LINE, ahead)) break;
            ticket.line.add(v.getUuid());
        }
        tickets.put(player.getUuid(), ticket);
        march(world, ticket);
        say(player, bridge.officials.titled(clerk, Officialdom.Rank.CLERK), Text.translatable("mobtalk.paper.queue", ahead), Formatting.GRAY);
        player.sendMessage(Text.translatable("mobtalk.paper.queue.hint").formatted(Formatting.DARK_GRAY), false);
    }
    /** «Я только спросить»: sometimes straight to the window, otherwise the queue grumbles. */
    private void justAsk(ServerWorld world, ServerPlayerEntity player, VillagerEntity clerk, Ticket ticket) {
        if (!ticket.asked && random.nextDouble() < Paperwork.JUST_ASK) {
            say(player, bridge.officials.titled(clerk, Officialdom.Rank.CLERK), Text.translatable("mobtalk.paper.just_ask.ok"), Formatting.GRAY);
            leave(world, ticket, player.getUuid());
            serve(world, player, clerk, Officialdom.Rank.CLERK, false);
            return;
        }
        ticket.asked = true;
        String playerName = MobGameBridge.playerName(player);
        long now = System.currentTimeMillis();
        for (UUID id : ticket.line) if (world.getEntity(id) instanceof VillagerEntity v) {
            v.setHeadRollingTimeLeft(30);
            bridge.memory.recordWitnessed(v.getUuid(), MobGameBridge.type(v), v.getName().getString(), player.getUuid(), playerName,
                    "лез без очереди к паспортистке («я только спросить»)", -2, io.github.mobtalk.core.MobState.Event.MISCHIEF, now);
        }
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 0.8f);
        player.sendMessage(Text.translatable("mobtalk.paper.just_ask.no").formatted(Formatting.RED), false);
    }

    void tick(ServerWorld world) {
        if (tickets.isEmpty() || world.getTime() % PERIOD != PHASE) return;
        long now = world.getTime();
        for (Iterator<Map.Entry<UUID, Ticket>> it = tickets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ticket> e = it.next();
            Ticket ticket = e.getValue();
            if (ticket.world != world.getRegistryKey()) continue; // each world ticks its own windows
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(e.getKey());
            VillagerEntity clerk = world.getEntity(ticket.clerk) instanceof VillagerEntity v && v.isAlive() ? v : null;
            if (player == null || clerk == null || player.getWorld() != world) { release(world, ticket); it.remove(); continue; }
            if (player.squaredDistanceTo(Vec3d.ofCenter(ticket.desk)) > LEAVE * LEAVE) {
                player.sendMessage(Text.translatable("mobtalk.paper.queue.left").formatted(Formatting.GRAY), false);
                release(world, ticket); it.remove(); continue;
            }
            if (Paperwork.lunch(world.getTimeOfDay())) {
                if (bridge.config.paperLunch && now % 200 == PHASE) say(player, bridge.officials.titled(clerk, Officialdom.Rank.CLERK), Text.translatable("mobtalk.paper.lunch"), Formatting.GRAY);
                ticket.next = now + bridge.config.queueSeconds * 20L;
            } else if (now >= ticket.next) {
                ticket.ahead--;
                ticket.next = now + bridge.config.queueSeconds * 20L;
                if (!ticket.line.isEmpty()) {
                    UUID served = ticket.line.removeFirst();
                    if (world.getEntity(served) instanceof VillagerEntity v) v.getBrain().forget(MemoryModuleType.WALK_TARGET);
                    world.playSound(null, ticket.desk, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 0.8f, 1f);
                }
                if (ticket.ahead <= 0) {
                    release(world, ticket); it.remove();
                    player.sendMessage(Text.translatable("mobtalk.paper.queue.turn").formatted(Formatting.GOLD), false);
                    serve(world, player, clerk, Officialdom.Rank.CLERK, false);
                    continue;
                }
            }
            march(world, ticket);
            player.sendMessage(Text.translatable("mobtalk.paper.queue.bar", ticket.ahead, Math.max(0, (ticket.next - now) / 20)), true);
        }
    }
    /** The villagers in line walk to (and stay at) their spots in front of the desk. */
    private void march(ServerWorld world, Ticket ticket) {
        for (int i = 0; i < ticket.line.size(); i++) if (world.getEntity(ticket.line.get(i)) instanceof VillagerEntity v && v.isAlive()) {
            Vec3d spot = ticket.spots.get(Math.min(i, ticket.spots.size() - 1));
            if (v.squaredDistanceTo(spot) > 0.6) v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, 0.5f, 0));
            else v.getLookControl().lookAt(Vec3d.ofCenter(ticket.desk));
        }
    }
    private void release(ServerWorld world, Ticket ticket) {
        if (ticket == null) return;
        for (UUID id : ticket.line) if (world.getEntity(id) instanceof VillagerEntity v) v.getBrain().forget(MemoryModuleType.WALK_TARGET);
        ticket.line.clear();
    }
    private void leave(ServerWorld world, Ticket ticket, UUID player) {
        release(world, ticket);
        tickets.remove(player);
    }
    /** Villagers near the desk who can stand in a queue: grown, no officials, not busy with anything else. */
    private List<VillagerEntity> callable(ServerWorld world, BlockPos desk) {
        Set<UUID> queued = new HashSet<>();
        for (Ticket t : tickets.values()) queued.addAll(t.line);
        List<VillagerEntity> out = new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, new net.minecraft.util.math.Box(desk).expand(CALL, 4, CALL),
                v -> v.isAlive() && !v.isBaby() && !v.isSleeping() && OfficialContent.rank(v) == null && !DefenseContent.traveller(v) && !queued.contains(v.getUuid()) && !bridge.actions.busy(v)));
        out.sort(Comparator.comparingDouble(v -> v.squaredDistanceTo(Vec3d.ofCenter(desk))));
        return out;
    }
    /** Whether the player stands in a queue (self-tests). */
    int ahead(UUID player) { Ticket t = tickets.get(player); return t == null ? -1 : t.ahead; }
    int line(UUID player) { Ticket t = tickets.get(player); return t == null ? 0 : t.line.size(); }

    // ---- Helpers ----

    /** The case for the snapshot of a talk with an official; null for none. */
    Map<String, Object> caseFor(VillagerEntity villager, ServerPlayerEntity player) {
        if (!enabled() || OfficialContent.rank(villager) == null || !(villager.getWorld() instanceof ServerWorld world)) return null;
        Paperwork.Case kase = bridge.memory.paperCase(player.getUuid());
        if (kase == null) return null;
        Map<String, Object> out = new LinkedHashMap<>(kase.data());
        out.put("today", day(world));
        return out;
    }
    private static BlockPos desk(VillagerEntity official) {
        return official.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null);
    }
    static long day(ServerWorld world) { return Math.floorDiv(world.getServer().getOverworld().getTimeOfDay(), 24_000L); }
    static int count(ServerPlayerEntity player, String item) {
        Item wanted = Registries.ITEM.get(Identifier.of(item));
        int n = 0;
        for (int i = 0; i < player.getInventory().size(); i++) { ItemStack s = player.getInventory().getStack(i); if (s.isOf(wanted)) n += s.getCount(); }
        return n;
    }
    private static void remove(ServerPlayerEntity player, String item, int count) {
        Item wanted = Registries.ITEM.get(Identifier.of(item));
        for (int i = 0; i < player.getInventory().size() && count > 0; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isOf(wanted)) continue;
            int n = Math.min(count, s.getCount()); s.decrement(n); count -= n;
        }
    }
    private static void take(ServerPlayerEntity player, Paperwork.Visit visit) {
        if (!visit.item().isEmpty() && visit.count() > 0) remove(player, visit.item(), visit.count());
        if (visit.fee() > 0) remove(player, "minecraft:emerald", visit.fee());
    }
    private static String itemName(String item) { return Registries.ITEM.get(Identifier.of(item)).getName().getString(); }
    private static void stamp(ServerWorld world, VillagerEntity official) {
        world.playSound(null, official.getBlockPos(), SoundEvents.BLOCK_WOOD_HIT, SoundCategory.NEUTRAL, 1f, 0.6f);
    }
    private static void say(ServerPlayerEntity player, Text who, Text what, Formatting color) {
        player.sendMessage(Text.empty().append(who.copy().formatted(Formatting.WHITE)).append(": ").append(what.copy().formatted(color)), false);
    }
    private void cue(VillagerEntity official, Officialdom.Rank rank, String what) {
        bridge.initiative.raise(new Initiative.Cue(official.getUuid(), bridge.officials.name(official), Initiative.Trigger.GIFT, what, List.of(), System.currentTimeMillis()));
    }

    void clearRuntime() { tickets.clear(); }
}
