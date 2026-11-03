package io.github.mobtalk.game;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.net.CardTableActionC2S;
import io.github.mobtalk.net.CardTableOpenS2C;
import io.github.mobtalk.net.CardTableStateS2C;
import io.github.mobtalk.net.NoticeS2C;
import io.github.mobtalk.server.CardTableService;
import io.github.mobtalk.server.CommandFilter;
import io.github.mobtalk.server.ServerConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.Activity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Игра в 21 в мире Minecraft (docs/cards-21.md): предмет «Колода карт» (ПКМ по жителю — сесть за стол; крафт 3 бумаги + чернильный
 * мешок, продаёт картограф), удары и смерть жителя, команды {@code /mobtalk cards}, изумруды в инвентаре и {@link CardTableService} этого
 * сервера. Всё на потоке сервера. */
public final class CardTables {
    private CardTables() { }
    public static Item DECK;
    private static boolean registered;
    private static volatile CardTableService service;
    private static volatile MinecraftServer current;

    /** Служба стола этого сервера; null, пока мир не открыт. */
    public static CardTableService service() { return service; }

    public static void register() {
        if (registered) return;
        registered = true;
        DECK = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "card_deck"), new Item(new Item.Settings().maxCount(1)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(DECK));
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.CARTOGRAPHER, 1,
                factories -> factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 2), new ItemStack(DECK), 6, 2, 0.05f)));
        // Житель открывает торговлю раньше, чем предмет в руке узнает о клике: колоду ловим до него.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(entity instanceof VillagerEntity) || !player.getStackInHand(hand).isOf(DECK) || player.isSpectator()) return ActionResult.PASS;
            if (!world.isClient && player instanceof ServerPlayerEntity server) {
                CardTableService cards = service;
                if (cards == null) server.sendMessage(Text.literal("[Mob Talk] Мир ещё загружается"), false);
                else cards.open(server.getUuid(), entity.getId(), "");
            }
            return ActionResult.SUCCESS;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, amount, blocked) -> {
            CardTableService cards = service;
            if (cards == null || !(entity instanceof VillagerEntity) || amount <= 0) return;
            UUID attacker = source.getAttacker() instanceof ServerPlayerEntity player ? player.getUuid() : null;
            cards.villagerHurt(entity.getUuid(), attacker);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            CardTableService cards = service;
            if (cards != null && entity instanceof VillagerEntity) cards.villagerGone(entity.getUuid());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("mobtalk").then(commands())));
    }

    // ---- Жизненный цикл (из MobTalkMod). ----

    public static void started(MinecraftServer server, ConversationStore memory) {
        current = server;
        CardTableService created = new CardTableService(new World(server), new Outbox(server), memory, ServerConfig::get, System::currentTimeMillis);
        created.setTalk((villager, player, input, done) -> talk(server, player, input, done));
        created.start();
        service = created;
        io.github.mobtalk.server.DialogueService dialogue = io.github.mobtalk.MobTalkMod.dialogue();
        if (dialogue != null) dialogue.setCardOffer((player, mob) -> { CardTableService cards = service; if (cards != null) cards.open(player, mob, ""); });
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) created.join(player.getUuid());
    }
    public static void stopping(MinecraftServer server) {
        if (current != server) return;
        CardTableService old = service;
        service = null; current = null;
        if (old != null) old.close();
    }
    public static void tick(MinecraftServer server) { CardTableService cards = service; if (cards != null && server == current) cards.tick(); }
    public static void join(ServerPlayerEntity player) { CardTableService cards = service; if (cards != null) cards.join(player.getUuid()); }
    public static void leave(ServerPlayerEntity player) { CardTableService cards = service; if (cards != null) cards.leave(player.getUuid()); }
    public static void action(ServerPlayerEntity player, CardTableActionC2S packet) {
        CardTableService cards = service;
        if (cards != null) cards.action(player.getUuid(), packet);
    }

    // ---- Реплики жителя: фоновая модель, через 4 с — заготовка. ----

    private static final java.util.concurrent.ExecutorService TALK = java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mobtalk-cards"); thread.setDaemon(true); return thread;
    });
    static void talk(MinecraftServer server, UUID player, io.github.mobtalk.core.CardLines.Input input, java.util.function.Consumer<io.github.mobtalk.core.CardLines.Line> done) {
        io.github.mobtalk.core.CardLines.Line fallback = io.github.mobtalk.core.CardLines.fallback(input.event(), input.style(), System.nanoTime());
        io.github.mobtalk.server.DialogueService dialogue = io.github.mobtalk.MobTalkMod.dialogue();
        boolean allowed = dialogue != null && ServerConfig.get().enabled
                && (player == null || io.github.mobtalk.MobTalkMod.budget().allow(player, io.github.mobtalk.server.PlayerBudget.Kind.VILLAGE_TALK).allowed());
        if (!allowed) { done.accept(fallback); return; }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        io.github.mobtalk.core.CardLines.Line line = dialogue.readyEngine().cardLine(input);
                        if (player != null) io.github.mobtalk.MobTalkMod.budget().charge(player, io.github.mobtalk.server.PlayerBudget.Kind.VILLAGE_TALK, 0, 1);
                        return line;
                    } catch (Exception failure) { return fallback; }
                }, TALK)
                .completeOnTimeout(fallback, 4, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(line -> server.execute(() -> done.accept(line)));
    }

    /** Житель продал товар за изумруды: они идут и в его кошелёк для игры в 21. */
    public static void sold(VillagerEntity villager, TradeOffer offer) {
        MobGameBridge bridge = MobGameBridge.active(villager.getServer());
        if (bridge == null) return;
        int emeralds = 0;
        if (offer.getDisplayedFirstBuyItem().isOf(Items.EMERALD)) emeralds += offer.getDisplayedFirstBuyItem().getCount();
        if (offer.getDisplayedSecondBuyItem().isOf(Items.EMERALD)) emeralds += offer.getDisplayedSecondBuyItem().getCount();
        bridge.memory.cards().sold(villager.getUuid(), emeralds);
    }

    // ---- Команды. ----

    private static LiteralArgumentBuilder<ServerCommandSource> commands() {
        LiteralArgumentBuilder<ServerCommandSource> open = literal("open").executes(context -> open(context, null, ""));
        LiteralArgumentBuilder<ServerCommandSource> cards = literal("cards").executes(context -> reply(context, service == null ? "Мир ещё загружается" : service.describe()));
        for (String rules : new String[]{"ochko", "blackjack"}) open.then(literal(rules).executes(context -> open(context, null, rules)));
        open.then(argument("villager", EntityArgumentType.entity())
                .executes(context -> open(context, EntityArgumentType.getEntity(context, "villager"), ""))
                .then(literal("ochko").executes(context -> open(context, EntityArgumentType.getEntity(context, "villager"), "ochko")))
                .then(literal("blackjack").executes(context -> open(context, EntityArgumentType.getEntity(context, "villager"), "blackjack"))));
        cards.then(open);
        cards.then(literal("deck").then(argument("seed", LongArgumentType.longArg()).executes(context -> {
            if (CommandFilter.refusal(context.getSource(), "mobtalk admin") != null) return reply(context, "Колоду задаёт только администратор");
            if (service == null) return reply(context, "Мир ещё загружается");
            service.deck(LongArgumentType.getLong(context, "seed"));
            return reply(context, "Следующий новый стол тасуется сидом " + LongArgumentType.getLong(context, "seed"));
        })));
        return cards;
    }
    /** Правила в команде — только у администратора; у остальных правила сервера. */
    private static int open(CommandContext<ServerCommandSource> context, Entity target, String rules) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        CardTableService cards = service;
        if (cards == null) return reply(context, "Мир ещё загружается");
        String variant = !rules.isEmpty() && CommandFilter.refusal(context.getSource(), "mobtalk admin") == null ? rules : "";
        String refusal = target == null ? cards.openNearest(player.getUuid(), variant) : cards.open(player.getUuid(), target.getId(), variant);
        return refusal.isEmpty() ? 1 : 0;
    }
    private static int reply(CommandContext<ServerCommandSource> context, String text) {
        context.getSource().sendFeedback(() -> Text.literal("[Mob Talk] " + text), false);
        return 1;
    }

    // ---- Мир. ----

    /** {@link CardTableService.World} на сервере Minecraft. */
    static final class World implements CardTableService.World {
        private final MinecraftServer server;
        World(MinecraftServer server) { this.server = server; }

        private static CardTableService.Villager view(VillagerEntity v) {
            ServerWorld world = (ServerWorld) v.getWorld();
            MobGameBridge bridge = MobGameBridge.active(world.getServer());
            String given = bridge == null ? "" : bridge.memory.displayName(v.getUuid());
            return new CardTableService.Villager(v.getUuid(), v.getId(), given.isBlank() ? v.getName().getString() : given,
                    world.getRegistryKey().getValue().toString(), v.getX(), v.getY(), v.getZ(), !v.isBaby(), v.isSleeping(),
                    v.getBrain().hasActivity(Activity.PANIC), world.hasRaidAt(v.getBlockPos()),
                    Registries.VILLAGER_PROFESSION.getId(v.getVillagerData().getProfession()).toString(), v.getVillagerData().getLevel());
        }
        private static boolean living(Entity entity) { return entity instanceof VillagerEntity v && v.isAlive() && !v.isRemoved(); }

        @Override public CardTableService.Villager villager(UUID player, int entityId) {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(player);
            Entity entity = online == null ? null : online.getWorld().getEntityById(entityId);
            return living(entity) ? view((VillagerEntity) entity) : null;
        }
        @Override public CardTableService.Villager villager(UUID id) {
            for (ServerWorld world : server.getWorlds()) {
                Entity entity = world.getEntity(id);
                if (living(entity)) return view((VillagerEntity) entity);
            }
            return null;
        }
        @Override public CardTableService.Villager nearest(UUID player, double range) {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(player);
            if (online == null) return null;
            List<VillagerEntity> near = online.getWorld().getEntitiesByClass(VillagerEntity.class, online.getBoundingBox().expand(range),
                    v -> v.isAlive() && !v.isBaby() && v.squaredDistanceTo(online) <= range * range);
            return near.stream().min(Comparator.comparingDouble(v -> v.squaredDistanceTo(online))).map(World::view).orElse(null);
        }
        @Override public CardTableService.Person player(UUID id) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) return null;
            return new CardTableService.Person(id, p.getGameProfile().getName(), p.getWorld().getRegistryKey().getValue().toString(),
                    p.getX(), p.getY(), p.getZ(), p.isAlive() && !p.isSpectator());
        }
        @Override public int emeralds(UUID player) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            return p == null ? 0 : p.getInventory().count(Items.EMERALD);
        }
        @Override public boolean take(UUID player, int emeralds) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            if (p == null || emeralds <= 0 || p.getInventory().count(Items.EMERALD) < emeralds) return false;
            int left = emeralds;
            var inventory = p.getInventory();
            for (int slot = 0; slot < inventory.size() && left > 0; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isOf(Items.EMERALD)) continue;
                int taken = Math.min(left, stack.getCount());
                stack.decrement(taken);
                left -= taken;
            }
            inventory.markDirty();
            return true;
        }
        @Override public void give(UUID player, int emeralds) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            if (p == null || emeralds <= 0) return;
            int left = emeralds;
            while (left > 0) {
                ItemStack stack = new ItemStack(Items.EMERALD, Math.min(64, left));
                left -= stack.getCount();
                p.getInventory().insertStack(stack);
                if (!stack.isEmpty()) p.dropItem(stack, false);
            }
        }
        @Override public void hold(UUID villager, UUID player, int ticks) {
            MobGameBridge bridge = MobGameBridge.active(server);
            if (bridge != null) bridge.attend(villager, player, ticks);
        }
        @Override public long day() { return Math.floorDiv(server.getOverworld().getTimeOfDay(), 24_000L); }
        /** Кто за каким столом: игрок за столом получает подпись «♠ играет в 21» у ника (команда scoreboard {@code mobtalk_cards});
         * игрок, уже состоящий в чужой команде, не трогается. */
        private final Map<UUID, List<UUID>> seated = new java.util.HashMap<>();
        @Override public void playing(UUID villager, List<UUID> players, boolean on) {
            if (on) seated.put(villager, List.copyOf(players)); else seated.remove(villager);
            var scoreboard = server.getScoreboard();
            net.minecraft.scoreboard.Team team = scoreboard.getTeam("mobtalk_cards");
            if (team == null) {
                team = scoreboard.addTeam("mobtalk_cards");
                team.setSuffix(Text.literal(" ♠ играет в 21").formatted(net.minecraft.util.Formatting.GOLD));
            }
            java.util.Set<String> want = new java.util.HashSet<>();
            for (List<UUID> ids : seated.values()) for (UUID id : ids) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) want.add(p.getGameProfile().getName());
            }
            for (String name : List.copyOf(team.getPlayerList())) if (!want.contains(name)) scoreboard.removeScoreHolderFromTeam(name, team);
            for (String name : want) if (scoreboard.getScoreHolderTeam(name) == null) scoreboard.addScoreHolderToTeam(name, team);
        }
        @Override public CardTableService.Held held(UUID player) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            if (p == null || p.getMainHandStack().isEmpty()) return null;
            ItemStack stack = p.getMainHandStack();
            // Простой предмет: без зачарований, имени, повреждений и прочих данных сверх обычных для своего вида.
            boolean plain = stack.getComponentChanges().isEmpty();
            return new CardTableService.Held(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), plain);
        }
        @Override public boolean takeHeld(UUID player, String item, int count) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            if (p == null) return false;
            ItemStack stack = p.getMainHandStack();
            if (stack.isEmpty() || !Registries.ITEM.getId(stack.getItem()).toString().equals(item) || stack.getCount() < count) return false;
            stack.decrement(count);
            p.getInventory().markDirty();
            return true;
        }
        @Override public void giveItem(UUID player, String item, int count) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            Item type = Registries.ITEM.get(Identifier.of(item));
            if (p == null || count <= 0 || type == Items.AIR) return;
            int left = count;
            while (left > 0) {
                ItemStack stack = new ItemStack(type, Math.min(type.getMaxCount(), left));
                left -= stack.getCount();
                p.getInventory().insertStack(stack);
                if (!stack.isEmpty()) p.dropItem(stack, false);
            }
        }
        @Override public String prize(UUID villager, UUID player, int percent) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
            if (p == null) return "";
            for (ServerWorld world : server.getWorlds())
                if (world.getEntity(villager) instanceof VillagerEntity v && v.isAlive()) return TradeBridge.prize(v, p, percent);
            return "";
        }
        @Override public List<UUID> online() { return server.getPlayerManager().getPlayerList().stream().map(ServerPlayerEntity::getUuid).toList(); }
        @Override public long timeOfDay() { return server.getOverworld().getTimeOfDay(); }
        @Override public boolean busy(UUID player) {
            io.github.mobtalk.server.DialogueService dialogue = io.github.mobtalk.MobTalkMod.dialogue();
            return dialogue != null && dialogue.busyTalking(player);
        }
    }

    /** {@link CardTableService.Outbox}: пакеты игрокам; игрок без мода на клиенте получает строку в чат вместо стола. */
    static final class Outbox implements CardTableService.Outbox {
        private final MinecraftServer server;
        Outbox(MinecraftServer server) { this.server = server; }
        private boolean send(UUID player, CustomPayload payload) {
            ServerPlayerEntity entity = server.getPlayerManager().getPlayer(player);
            if (entity == null || !ServerPlayNetworking.canSend(entity, payload.getId())) return false;
            ServerPlayNetworking.send(entity, payload);
            return true;
        }
        @Override public void open(UUID player, CardTableOpenS2C packet) {
            if (!send(player, packet)) chat(player, "[Mob Talk] Для игры в 21 нужен Mob Talk на клиенте");
        }
        @Override public void state(UUID player, CardTableStateS2C packet) { send(player, packet); }
        @Override public void session(io.github.mobtalk.core.CardLedger.Session s, int purse, String style) {
            io.github.mobtalk.server.DialogueService dialogue = io.github.mobtalk.MobTalkMod.dialogue();
            if (dialogue == null) return;
            String result = s.net() > 0 ? "выиграл " + s.net() : s.net() < 0 ? "проиграл " + -s.net() : "вничью";
            dialogue.event("cards", UUID.fromString(s.player()), s.playerName(), UUID.fromString(s.villager()), s.villagerName(), "minecraft:villager",
                    s.playerName() + " и " + s.villagerName() + ": " + s.rounds() + " раздач в " + ("BLACKJACK".equals(s.variant()) ? "блэкджек" : "очко") + ", игрок " + result
                            + (s.note() == null || s.note().isBlank() ? "" : " (" + s.note() + ")"),
                    java.util.Map.of("rounds", s.rounds(), "net", s.net(), "variant", s.variant(), "purse", purse, "style", style));
        }
        @Override public void invite(UUID player, String villager, UUID villagerId, String line) {
            ServerPlayerEntity entity = server.getPlayerManager().getPlayer(player);
            if (entity == null) return;
            Text button = Text.literal("[Сесть за стол]").styled(style -> style.withColor(net.minecraft.util.Formatting.GOLD)
                    .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/mobtalk cards open " + villagerId))
                    .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("Сыграть в 21 с жителем " + villager))));
            entity.sendMessage(Text.literal(villager + ": «" + line + "» ").formatted(net.minecraft.util.Formatting.YELLOW).append(button), false);
        }
        @Override public void chat(UUID player, String line) {
            if (send(player, new NoticeS2C(line, false))) return;
            ServerPlayerEntity entity = server.getPlayerManager().getPlayer(player);
            if (entity != null) entity.sendMessage(Text.literal(line), false);
        }
    }
}
