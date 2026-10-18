package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
    private final Map<UUID, Deque<Heard>> heard = new HashMap<>();

    private MobGameBridge(MinecraftServer server, ConversationStore memory, MobTalkConfig config) {
        this.server = server; this.memory = memory; this.config = config.copy();
        actions = new MobActionController(this);
        bargaining = new VillagerBargaining(this);
        trading = new MobTrading(this);
    }

    public static void initialize() {
        if (registered) return;
        registered = true;
        // A favour may only ask for an item the game knows; the core checks the id shape alone.
        Trading.setItemChecker(id -> MobTrading.item(id) != null);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, amount, blocked) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge == null || !(entity instanceof MobEntity mob)) return;
            if (source.getAttacker() instanceof ServerPlayerEntity player && !blocked && amount > 0) {
                bridge.memory.recordDamage(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                        player.getName().getString(), System.currentTimeMillis());
                if (mob instanceof VillagerEntity villager) bridge.bargaining.hurtBy(villager, player);
                bridge.actions.hurtBy(mob, player);
            } else if (!blocked && amount > 0) {
                bridge.event(mob, "Damage: " + source.getName() + "; attacker="
                        + (source.getAttacker() == null ? "environment" : type(source.getAttacker())));
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge == null) return;
            if (source.getAttacker() instanceof ServerPlayerEntity player) {
                if (entity instanceof MobEntity mob)
                    bridge.memory.recordDamage(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                            player.getName().getString(), System.currentTimeMillis());
            }
            if (entity instanceof MobEntity mob) bridge.event(mob, "The mob died: " + source.getName());
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
                if (mob instanceof VillagerEntity villager) bridge.bargaining.unload(villager);
                bridge.heard.remove(mob.getUuid());
            }
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            MobGameBridge bridge = active;
            if (bridge != null && bridge.server == world.getServer()) {
                bridge.actions.tick(world);
                bridge.bargaining.tick(world);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MobGameBridge bridge = active(server);
            if (bridge != null) bridge.bargaining.close();
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
        server.execute(() -> { bridge.actions.clearRuntime(); bridge.bargaining.close(); done.complete(null); });
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
    public void event(MobEntity mob, String text) {
        memory.recordEvent(mob.getUuid(), type(mob), mob.getName().getString(), text, System.currentTimeMillis());
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
        return "отношение " + (value >= 0 ? "+" : "") + value;
    }

    public CompletableFuture<MobSnapshot> capture(UUID mobId, UUID playerId, String dimension) {
        return onServer(() -> {
            Nearby nearby = nearby(mobId, playerId, dimension);
            if (memory.card(mobId, playerId) == null) event(nearby.mob, "The player met this mob");
            return MobContextCapture.capture(this, nearby.world, nearby.mob, nearby.player);
        });
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
                String trade = "";
                if (bargain != null && bargain.type() != BargainIntent.Type.NONE) {
                    if (!(mob instanceof VillagerEntity villager)) throw new IllegalStateException("Торг доступен только с жителями");
                    trade = bargaining.apply(villager, player, bargain, observed);
                }
                String deals = trading.apply(mob, player, deal, unlock, quest);
                if (!deals.isBlank()) trade = trade.isBlank() ? deals : trade + "; " + deals;
                String result = actions.apply(mob, player, intent, observed);
                if (!trade.isBlank()) result = result.isBlank() ? trade : result + "; " + trade;
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
    record Heard(long tick, Vec3d position, UUID source, String event) { }
}
