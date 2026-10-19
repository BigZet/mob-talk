package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Physical-client bridge to the authoritative integrated server. No disk/network work here. */
public final class MobGameBridge {
    private static volatile MobGameBridge active;
    private static boolean registered;
    public final MinecraftServer server;
    public final ConversationStore memory;
    public volatile MobTalkConfig config;
    final MobActionController actions;
    final VillagerBargaining bargaining;
    final MobTrading trading;
    /** Spoken promises of villagers as one-use rows in their vanilla window. */
    final TradeBridge trades;
    /** Misdeeds without a blow, seen by the village. */
    final MischiefWatch mischief;
    /** What the player built or dug lately, for the place analysis ("he walled me in"). */
    final BlockChangeLog changes = new BlockChangeLog();
    /** Bonds between mobs: their sources in the game and the walks they cause. */
    final MobSocialController social;
    /** Occasions for a creature to speak first, found on the server and voiced by the client. */
    public final InitiativeController initiative;
    /** The villagers' social layer in the world: gifts changing hands, slaps, families, the village's stance. */
    final SocialController society;
    /** Villagers' families: weddings, births, growth, mourning. */
    final FamilyController families;
    /** The village's stance towards the player: no trade for an exile, a watchful golem, warnings. */
    final VillageController villages;
    private final Map<UUID, Deque<Heard>> heard = new HashMap<>();

    private MobGameBridge(MinecraftServer server, ConversationStore memory, MobTalkConfig config) {
        this.server = server; this.memory = memory; this.config = config.copy();
        social = new MobSocialController(this);
        society = new SocialController(this);
        families = new FamilyController(this);
        villages = new VillageController(this);
        initiative = new InitiativeController(this);
        actions = new MobActionController(this);
        bargaining = new VillagerBargaining(this);
        trading = new MobTrading(this);
        trades = new TradeBridge(this);
        mischief = new MischiefWatch(this);
    }

    public static void initialize() {
        if (registered) return;
        registered = true;
        // A favour may only ask for an item the game knows; the core checks the id shape alone.
        Trading.setItemChecker(id -> MobTrading.item(id) != null);
        MischiefWatch.initialize();
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, amount, blocked) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge == null || !(entity instanceof MobEntity mob)) return;
            if (source.getAttacker() instanceof ServerPlayerEntity player && !blocked && amount > 0) {
                bridge.memory.recordDamage(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                        player.getName().getString(), System.currentTimeMillis());
                bridge.witnessed(mob, player, "ударил", -10);
                if (mob instanceof VillagerEntity villager) bridge.bargaining.hurtBy(villager, player);
                bridge.actions.hurtBy(mob, player);
            } else if (!blocked && amount > 0 && bridge.memory.known(mob.getUuid())) {
                // Only a mob the player already knows keeps a diary: a profile for every burning zombie would bloat the world memory.
                bridge.event(mob, "Damage: " + source.getName() + "; attacker="
                        + (source.getAttacker() == null ? "environment" : type(source.getAttacker())));
                if (source.getAttacker() instanceof MobEntity attacker) bridge.social.attacked(attacker, mob);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge == null || !(entity instanceof MobEntity mob)) return;
            // Fabric fires AFTER_DAMAGE only for a survived blow, so the kill is seen here alone. A mob nobody talked to leaves no
            // memory behind; one with a conversation keeps its profile, and its death goes into it.
            UUID id = mob.getUuid();
            if (source.getAttacker() instanceof ServerPlayerEntity player) bridge.witnessed(mob, player, "убил", -25);
            bridge.social.died(mob); // Before the stranger's profile goes: the mourners keep its name.
            bridge.families.died(mob, source); // Relatives mourn wherever they are.
            if (bridge.memory.known(id) && !bridge.memory.forgetStranger(id)) bridge.event(mob, "The mob died: " + source.getName());
            // END_WORLD_TICK removes dead leases; a death callback can run inside that lease's melee goal.
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge != null && entity instanceof MobEntity mob) bridge.actions.restore(mob);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge != null && entity instanceof MobEntity mob) {
                bridge.actions.unload(mob);
                bridge.social.unload(mob);
                bridge.initiative.unload(mob);
                if (mob instanceof VillagerEntity villager) { bridge.bargaining.unload(villager); bridge.trades.unload(villager); }
                bridge.heard.remove(mob.getUuid());
                bridge.changes.forget(mob.getUuid());
            }
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            MobGameBridge bridge = world instanceof ServerWorld serverWorld ? active(serverWorld.getServer()) : null;
            if (bridge != null && player instanceof ServerPlayerEntity serverPlayer) bridge.changes.broken((ServerWorld) world, pos, serverPlayer);
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            MobGameBridge bridge = active;
            if (bridge != null && bridge.server == world.getServer()) {
                bridge.actions.tick(world);
                bridge.actions.stance(world);
                bridge.social.tick(world);
                bridge.society.tick(world);
                bridge.families.tick(world);
                bridge.villages.tick(world);
                bridge.initiative.tick(world);
                bridge.bargaining.tick(world);
                bridge.trades.tick(world);
                bridge.changes.tick(bridge, world);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MobGameBridge bridge = active(server);
            if (bridge != null) { bridge.bargaining.close(); bridge.trades.close(); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MobGameBridge bridge = active;
            if (bridge != null && bridge.server == server) active = null;
        });
    }

    /** Call on server thread after the memory file was opened by a worker. */
    public static MobGameBridge attach(MinecraftServer server, ConversationStore memory, MobTalkConfig config) {
        if (active != null && active.server == server) { active.config = config.copy(); return active; }
        MobGameBridge bridge = new MobGameBridge(server, memory, config);
        active = bridge;
        for (ServerWorld world : server.getWorlds())
            for (Entity entity : world.iterateEntities()) if (entity instanceof MobEntity mob) bridge.actions.restore(mob);
        return bridge;
    }

    public static MobGameBridge active(MinecraftServer server) {
        MobGameBridge bridge = active;
        return bridge != null && bridge.server == server ? bridge : null;
    }
    public static CompletableFuture<Void> detach(MinecraftServer server) {
        MobGameBridge bridge = active(server);
        if (bridge == null) return CompletableFuture.completedFuture(null);
        active = null;
        for (MobCard card : bridge.memory.cards(null))
            if (card.action() != null && card.action().type() != ActionType.NONE && card.action().type() != ActionType.FOLLOW)
                bridge.memory.recordAction(card.id(), ActionIntent.none());
        if (server.isStopped()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> { bridge.actions.clearRuntime(); bridge.social.clearRuntime(); bridge.society.clearRuntime(); bridge.villages.clearRuntime(); bridge.initiative.clearRuntime(); bridge.bargaining.close(); bridge.trades.close(); done.complete(null); });
        return done;
    }
    public static MobGameBridge forEntity(Entity entity) {
        return entity.getWorld() instanceof ServerWorld world ? active(world.getServer()) : null;
    }
    static MobGameBridge currentTradeBridge() {
        MobGameBridge bridge = active;
        return bridge != null && bridge.server.isOnThread() ? bridge : null;
    }
    public static String type(Entity entity) { return Registries.ENTITY_TYPE.getId(entity.getType()).toString(); }
    /** From the placement mixins: a block or a bucket of fluid the player put down. */
    public static void blockPlaced(ServerWorld world, net.minecraft.util.math.BlockPos pos, ServerPlayerEntity player) {
        MobGameBridge bridge = active(world.getServer());
        if (bridge != null) bridge.changes.placed(world, pos, player);
    }
    public void event(MobEntity mob, String text) {
        memory.recordEvent(mob.getUuid(), type(mob), mob.getName().getString(), text, System.currentTimeMillis());
    }
    /** Every mob with a profile that can see the victim takes it personally: a smaller drop than the victim's and a memory
     * "Видел, как игрок … ударил Марфу (корова)". Strangers notice nothing — a profile for each would bloat the world memory. */
    void witnessed(MobEntity victim, ServerPlayerEntity player, String seen, int delta) { witnessed(victim, player, seen, delta, WITNESS_RANGE); }
    void witnessed(MobEntity victim, ServerPlayerEntity player, String seen, int delta, double range) {
        if (!config.enabled || !(victim.getWorld() instanceof ServerWorld world)) return;
        // Killing monsters, or a creature that is attacking the player, is self-defence in everyone's eyes.
        if (SpeciesCatalog.get(type(victim)).hostile() || victim.getTarget() == player) return;
        String name = memory.displayName(victim.getUuid());
        String what = seen + " " + (name.isBlank() ? victim.getName().getString() : name) + " (" + SpeciesCatalog.get(type(victim)).name().toLowerCase(java.util.Locale.ROOT) + ")";
        long now = System.currentTimeMillis();
        for (MobEntity witness : world.getEntitiesByClass(MobEntity.class, victim.getBoundingBox().expand(range),
                other -> other != victim && other.isAlive() && !other.isRemoved() && memory.known(other.getUuid()) && other.canSee(victim)))
            memory.recordWitnessed(witness.getUuid(), player.getUuid(), player.getName().getString(), what, delta, now);
    }
    /** How far a mob sees what happens to another, and how far it hears what is said. */
    static final double WITNESS_RANGE = 16, HEARING_RANGE = 5;

    /** The mob stops wandering and keeps looking at the player for {@code ticks}: it is being spoken to, thinking or speaking. */
    /** The gift key: one item from the player's hand to this creature, on the server. */
    public CompletableFuture<String> giftFromHand(UUID mobId, UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            Entity entity = player == null ? null : ((ServerWorld) player.getWorld()).getEntity(mobId);
            if (!(entity instanceof MobEntity mob) || !mob.isAlive()) return "Моб недоступен";
            return society.giftFromHand((ServerWorld) player.getWorld(), player, mob);
        });
    }

    public CompletableFuture<Void> attend(UUID mobId, UUID playerId, int ticks) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            Entity entity = player == null ? null : ((ServerWorld) player.getWorld()).getEntity(mobId);
            if (entity instanceof MobEntity mob && mob.isAlive()) actions.attend(mob, player, ticks);
            return null;
        });
    }

    /** Bystanders that may add a line after the mob spoken to: alive, within the conversation range of the player, able to see
     * them, nearest first with mobs the player already knows ahead of strangers. Never the speaker itself. */
    public CompletableFuture<List<UUID>> witnesses(UUID speakerId, UUID playerId, String dimension, int limit) {
        return onServer(() -> {
            ServerWorld world = world(dimension);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (world == null || player == null || !player.isAlive() || player.getWorld() != world || limit <= 0) return List.of();
            double range = Math.min(HEARING_RANGE, config.maxDistance);
            Entity speaker = world.getEntity(speakerId);
            // Within earshot of either side of the conversation, and able to see the player.
            List<MobEntity> around = world.getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(range + 4),
                    mob -> !mob.getUuid().equals(speakerId) && mob.isAlive() && !mob.isRemoved() && !mob.isAiDisabled()
                            && (mob.squaredDistanceTo(player) <= range * range || speaker != null && mob.squaredDistanceTo(speaker) <= range * range)
                            && mob.squaredDistanceTo(player) <= config.maxDistance * config.maxDistance && mob.canSee(player));
            around.sort(Comparator.comparingInt((MobEntity mob) -> memory.known(mob.getUuid()) ? 0 : 1).thenComparingDouble(player::squaredDistanceTo));
            return around.stream().limit(limit).map(Entity::getUuid).toList();
        });
    }

    public static void helped(MobEntity mob, ServerPlayerEntity player, String reason) {
        MobGameBridge bridge = forEntity(mob);
        if (bridge == null) return;
        bridge.memory.recordHelp(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                player.getName().getString(), System.currentTimeMillis());
        bridge.event(mob, reason + ": " + player.getName().getString());
    }

    /** The conversation target behind the player's open merchant window: a mob's own deals (B) or a villager's profession trades. */
    public CompletableFuture<UUID> selectMerchant(UUID playerId, int syncId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("Игрок недоступен");
            MobMerchant own = MobMerchant.of(player);
            if (own != null && player.currentScreenHandler.syncId == syncId) return own.mob.getUuid();
            return VillagerBargaining.selected(player, syncId).getUuid();
        });
    }
    /** « отношение +N» for the HUD and the trade-window title; no card means a stranger. */
    public static String relationshipLabel(MobCard card) {
        int value = card == null ? 0 : card.relationship();
        boolean hostile = card != null && SpeciesCatalog.get(card.type()).hostile();
        String mark = hostile && value >= CombatRules.ALLY ? " (свой)" : hostile && value >= CombatRules.TRUCE ? " (перемирие)" : "";
        String hearts = card != null && "minecraft:villager".equals(card.type()) ? Hearts.symbols(value) : "";
        return "отношение " + (value >= 0 ? "+" : "") + value + mark + (hearts.isEmpty() ? "" : " " + hearts);
    }

    public CompletableFuture<MobSnapshot> capture(UUID mobId, UUID playerId, String dimension) {
        return onServer(() -> {
            Nearby nearby = nearby(mobId, playerId, dimension);
            if (memory.card(mobId, playerId) == null) event(nearby.mob, "The player met this mob");
            actions.attend(nearby.mob, nearby.player, 100); // Being spoken to: stand and listen while the answer is written.
            MobSnapshot snapshot = MobContextCapture.capture(this, nearby.world, nearby.mob, nearby.player);
            social.conversation(nearby.mob, snapshot); // The known creatures around get acquainted with the one spoken to.
            return snapshot;
        }).thenApplyAsync(PlaceCapture::classify); // The classifier takes tens of milliseconds: never on the server thread.
    }
    /** The living mob and player in the same world within the conversation range; server thread only. */
    private Nearby nearby(UUID mobId, UUID playerId, String dimension) {
        ServerWorld world = world(dimension);
        Entity entity = world == null ? null : world.getEntity(mobId);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (!(entity instanceof MobEntity mob) || !mob.isAlive() || mob.isRemoved() || player == null
                || !player.isAlive() || player.getWorld() != world)
            throw new IllegalStateException("Собеседник или игрок недоступен");
        if (mob.squaredDistanceTo(player) > config.maxDistance * config.maxDistance)
            throw new IllegalStateException("Подойдите к мобу ближе");
        return new Nearby(world, mob, player);
    }
    private record Nearby(ServerWorld world, MobEntity mob, ServerPlayerEntity player) { }

    /** Opens the vanilla merchant window over the mob's own deals; the vanilla handler moves the items from then on. */
    public CompletableFuture<Void> openTrade(UUID mobId, UUID playerId, String dimension) {
        return onServer(() -> {
            if (!config.enabled) throw new IllegalStateException("Mob Talk выключен");
            Nearby nearby = nearby(mobId, playerId, dimension);
            synchronized (memory) {
                if (memory.card(mobId, playerId) == null) event(nearby.mob, "The player met this mob");
                trading.open(nearby.mob, nearby.player);
            }
            return null;
        });
    }

    public CompletableFuture<String> apply(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, observed, expectedRevision, valid).thenApply(Outcome::message);
    }
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, BargainIntent.none(), observed, expectedRevision, valid);
    }
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, bargain, DealIntent.none(), "", QuestIntent.none(), observed, expectedRevision, valid);
    }
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, DealIntent deal, String unlock, QuestIntent quest, MobSnapshot observed, long expectedRevision,
            java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, bargain, deal, unlock, quest, SocialIntent.NONE, observed, expectedRevision, valid);
    }
    /** {@code social}: what the player's words were (the store scores the victim on commit); bystanders in sight take an insult
     * or a death threat to one of their own personally. */
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, DealIntent deal, String unlock, QuestIntent quest, SocialIntent social, MobSnapshot observed, long expectedRevision,
            java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, bargain, deal, unlock, quest, social, GestureIntent.none(), observed, expectedRevision, valid);
    }
    /** {@code gesture}: the social gesture of the reply with its decided outcome; its effect on the world runs here, before the commit. */
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, DealIntent deal, String unlock, QuestIntent quest, SocialIntent social, GestureIntent gesture, MobSnapshot observed,
            long expectedRevision, java.util.function.BooleanSupplier valid) {
        return onServer(() -> {
            synchronized (memory) {
                if (!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException("Разговор отменён");
                if (expectedRevision >= 0 && memory.revision(mobId) != expectedRevision)
                    throw new java.util.concurrent.CancellationException("Отношение моба изменилось: устаревший ответ отменён");
                ServerWorld world = world(dimension);
                Entity entity = world == null ? null : world.getEntity(mobId);
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (!(entity instanceof MobEntity mob) || !mob.isAlive() || player == null || !player.isAlive()
                        || player.getWorld() != world) throw new java.util.concurrent.CancellationException("Моб или игрок недоступен");
                if (memory.card(mobId, playerId) == null) event(mob, "The player met this mob");
                // First: a gift no longer in hand cancels the whole reply before anything else changes.
                String gestured = society.apply(mob, player, gesture);
                String trade = "";
                if (bargain != null && bargain.type() != BargainIntent.Type.NONE) {
                    if (!(mob instanceof VillagerEntity villager)) throw new IllegalStateException("Торг доступен только с жителями");
                    // Window shut: the bargain is a spoken promise that becomes a one-use row; open: a price in the window.
                    trade = TradeBridge.spoken(observed) ? trades.apply(villager, player, bargain, observed) : bargaining.apply(villager, player, bargain, observed);
                }
                String deals = trading.apply(mob, player, deal, unlock, quest);
                if (!deals.isBlank()) trade = trade.isBlank() ? deals : trade + "; " + deals;
                String result = actions.apply(mob, player, intent, observed);
                if (!gestured.isBlank()) result = result.isBlank() ? gestured : result + "; " + gestured;
                if (!trade.isBlank()) result = result.isBlank() ? trade : result + "; " + trade;
                if (social == SocialIntent.THREAT) witnessed(mob, player, "грозил убить", -10, HEARING_RANGE);
                else if (social == SocialIntent.INSULT) witnessed(mob, player, "оскорбил", -5, HEARING_RANGE);
                return new Outcome(result, memory.revision(mobId));
            }
        });
    }
    public record Outcome(String message, long revision) { }
    public CompletableFuture<String> stopAllFollowers() {
        return onServer(() -> { actions.stopFollowers(); return "Следование всех мобов отменено"; });
    }
    public <T> CompletableFuture<T> onServer(java.util.function.Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            if (active != this) { future.completeExceptionally(new IllegalStateException("Мир сменился")); return; }
            try { future.complete(work.get()); } catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        return future;
    }
    private ServerWorld world(String dimension) {
        for (ServerWorld world : server.getWorlds())
            if (world.getRegistryKey().getValue().toString().equals(dimension)) return world;
        return null;
    }

    /** Only sound-producing game events are perceptible to a blind warden. */
    public static void heard(WardenEntity warden, Vec3d position, Entity source, String event) {
        MobGameBridge bridge = forEntity(warden);
        if (bridge == null) return;
        Deque<Heard> events = bridge.heard.computeIfAbsent(warden.getUuid(), ignored -> new ArrayDeque<>());
        events.addLast(new Heard(warden.getWorld().getTime(), position, source == null ? null : source.getUuid(), event));
        while (events.size() > 16) events.removeFirst();
    }
    List<Heard> heard(MobEntity mob) {
        Deque<Heard> events = heard.get(mob.getUuid());
        if (events == null) return List.of();
        events.removeIf(event -> mob.getWorld().getTime() - event.tick > 100);
        return List.copyOf(events);
    }
    public static void afterBrain(ServerWorld world, LivingEntity entity) {
        MobGameBridge bridge = active(world.getServer());
        if (bridge != null && entity instanceof MobEntity mob) bridge.actions.afterBrain(mob);
    }
    public static boolean suppressesAttack(MobEntity mob) {
        MobGameBridge bridge = forEntity(mob);
        return bridge != null && bridge.actions.suppressesNativeAttack(mob);
    }
    /** A hostile mob that earned this player's trust leaves them alone; the first blow drops the relationship and ends it. */
    public static boolean truce(MobEntity mob, ServerPlayerEntity player) {
        MobGameBridge bridge = forEntity(mob);
        if (bridge == null || !bridge.config.enabled) return false;
        return CombatRules.truce(SpeciesCatalog.get(type(mob)).hostile(), bridge.memory.relationship(mob.getUuid(), player.getUuid()));
    }
    /** An exiled player gets no trade window from this village's villagers. */
    public static boolean refusesTrade(VillagerEntity villager, ServerPlayerEntity player) {
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && bridge.villages.refuseTrade(villager, player);
    }
    /** Villager families own breeding: while they are on, vanilla villagers do not breed (children come only to couples). */
    public static boolean familiesOwnBreeding(VillagerEntity villager) {
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && bridge.config.enabled && bridge.config.socialLayer && bridge.config.familyLife;
    }
    /** A creature this hostile promised to leave alone (SPARE) is never taken as a target while the promise lasts. */
    public static boolean spared(MobEntity mob, LivingEntity target) {
        MobGameBridge bridge = forEntity(mob);
        return bridge != null && bridge.config.enabled && bridge.actions.friends.spared(mob, target);
    }
    /** The STAY/GUARD/SPARE leases of this mob for /mobtalk state; safe from the client thread. */
    public List<FriendActions.Status> friendStatus(UUID mob) { return actions.friends.status(mob); }
    record Heard(long tick, Vec3d position, UUID source, String event) { }
}
