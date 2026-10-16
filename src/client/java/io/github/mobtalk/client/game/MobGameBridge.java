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
    private final Map<UUID, Deque<Heard>> heard = new HashMap<>();

    private MobGameBridge(MinecraftServer server, ConversationStore memory, MobTalkConfig config) {
        this.server = server; this.memory = memory; this.config = config.copy();
        actions = new MobActionController(this);
    }

    public static void initialize() {
        if (registered) return;
        registered = true;
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, amount, blocked) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge == null || !(entity instanceof MobEntity mob)) return;
            if (source.getAttacker() instanceof ServerPlayerEntity player && !blocked && amount > 0) {
                bridge.memory.recordDamage(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                        player.getName().getString(), System.currentTimeMillis());
                bridge.actions.hurtBy(mob, player.getUuid());
            } else if (!blocked && amount > 0) {
                bridge.event(mob, "Damage: " + source.getName() + "; attacker="
                        + (source.getAttacker() == null ? "environment" : type(source.getAttacker())));
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge == null) return;
            if (entity instanceof MobEntity mob) {
                if (source.getAttacker() instanceof ServerPlayerEntity player)
                    bridge.memory.recordDamage(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                            player.getName().getString(), System.currentTimeMillis());
                bridge.event(mob, "The mob died: " + source.getName());
                bridge.actions.stop(mob.getUuid(), true);
            }
            bridge.actions.targetDied(entity.getUuid());
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge != null && entity instanceof MobEntity mob) bridge.actions.restore(mob);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge != null && entity instanceof MobEntity mob) {
                bridge.actions.unload(mob);
                bridge.heard.remove(mob.getUuid());
            }
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            MobGameBridge bridge = active;
            if (bridge != null && bridge.server == world.getServer()) bridge.actions.tick(world);
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
        server.execute(() -> { bridge.actions.clearRuntime(); done.complete(null); });
        return done;
    }
    public static MobGameBridge forEntity(Entity entity) {
        return entity.getWorld() instanceof ServerWorld world ? active(world.getServer()) : null;
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

    public CompletableFuture<MobSnapshot> capture(UUID mobId, UUID playerId, String dimension) {
        return onServer(() -> {
            ServerWorld world = world(dimension);
            Entity entity = world == null ? null : world.getEntity(mobId);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (!(entity instanceof MobEntity mob) || !mob.isAlive() || mob.isRemoved() || player == null
                    || !player.isAlive() || player.getWorld() != world)
                throw new IllegalStateException("Собеседник или игрок недоступен");
            if (mob.squaredDistanceTo(player) > config.maxDistance * config.maxDistance)
                throw new IllegalStateException("Подойдите к мобу ближе");
            if (memory.card(mobId, playerId) == null) event(mob, "The player met this mob");
            return MobContextCapture.capture(this, world, mob, player);
        });
    }

    public CompletableFuture<String> apply(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, observed, expectedRevision, valid).thenApply(Outcome::message);
    }
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
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
                String result = actions.apply(mob, player, intent, observed);
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
        return bridge != null && bridge.actions.moving(mob);
    }
    record Heard(long tick, Vec3d position, UUID source, String event) { }
}
