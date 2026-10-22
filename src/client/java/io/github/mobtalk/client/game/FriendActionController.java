package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import io.github.mobtalk.client.game.mixin.MobEntityAccessor;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The friend requests of {@link FriendActions} in the world: one STAY or GUARD duty per mob, and the creatures each hostile spares.
 * They run beside {@link MobActionController}'s move/attack leases: a guard fights through an ordinary attack lease. Server thread
 * only, except {@link #status}, which /mobtalk state reads from the client thread. */
final class FriendActionController {
    private static final double LOOK_RANGE = 8;
    private final MobGameBridge bridge;
    private final MobActionController actions;
    private final Map<UUID, Duty> duties = new HashMap<>();
    private final Map<UUID, Map<UUID, Spare>> spares = new HashMap<>();
    private final Map<UUID, List<FriendActions.Status>> statuses = new ConcurrentHashMap<>();

    FriendActionController(MobGameBridge bridge, MobActionController actions) { this.bridge = bridge; this.actions = actions; }

    /** The friend requests this mob may grant this player now, added to its {@code base} actions. */
    List<ActionType> available(MobEntity mob, ServerPlayerEntity player, List<ActionType> base) {
        List<ActionType> types = new ArrayList<>(base);
        int relationship = bridge.memory.relationship(mob.getUuid(), player.getUuid());
        boolean owned = mob instanceof Tameable tameable && player.getUuid().equals(tameable.getOwnerUuid());
        if (base.contains(ActionType.FOLLOW) && FriendActions.mayStay(relationship, owned)) types.add(ActionType.STAY);
        if (base.contains(ActionType.FOLLOW) && base.contains(ActionType.ATTACK)
                && FriendActions.mayGuard(MobActionController.fighter(mob), relationship, owned)) types.add(ActionType.GUARD);
        if (base.contains(ActionType.ATTACK) && FriendActions.maySpare(SpeciesCatalog.get(MobGameBridge.type(mob)).hostile())) types.add(ActionType.SPARE);
        return List.copyOf(types);
    }

    String apply(MobEntity mob, ServerPlayerEntity player, ActionIntent intent, MobSnapshot snapshot) {
        ActionType type = intent.type();
        if (!actions.available(mob, player).contains(type)) return "Это действие недоступно данному мобу";
        if (mob.squaredDistanceTo(player) > bridge.config.maxDistance * bridge.config.maxDistance) return "Подойдите к мобу ближе для нового действия";
        if (type == ActionType.SPARE) return spare(mob, player, intent, snapshot);
        String target = FriendActions.target(type, intent.target());
        if (target == null) return "Цель недоступна для этого действия";
        long now = mob.getWorld().getTime();
        Duty running = duties.get(mob.getUuid());
        if (running != null && running.playerId.equals(player.getUuid()) && running.lease.same(type, target)) {
            running.lease.refresh(now); publish(mob.getUuid()); // Asked again: just keep going, nothing to announce.
            return "";
        }
        if (running != null) remove(running);
        actions.stop(mob.getUuid(), false); // A follow, flee or attack gives way to the new duty.
        Duty duty = new Duty(mob, player.getUuid(), new FriendActions.Lease(type, target, now),
                type == ActionType.STAY || FriendActions.HERE.equals(target) ? mob.getPos() : null);
        duties.put(mob.getUuid(), duty);
        ((MobEntityAccessor) mob).mobtalk$goalSelector().add(-1, duty.goal);
        mob.getNavigation().stop();
        bridge.memory.recordAction(mob.getUuid(), new ActionIntent(type, target));
        bridge.event(mob, "Action started: " + type + "; target=" + target);
        publish(mob.getUuid());
        String key = type == ActionType.STAY ? "mobtalk.friend.stay.start" : FriendActions.HERE.equals(target) ? "mobtalk.friend.guard.here" : "mobtalk.friend.guard.player";
        return Text.translatable(key, name(mob)).getString();
    }

    private String spare(MobEntity mob, ServerPlayerEntity player, ActionIntent intent, MobSnapshot snapshot) {
        boolean truce = MobGameBridge.truce(mob, player);
        boolean toPlayer = FriendActions.PLAYER.equals(intent.target()) || player.getUuid().toString().equals(intent.target());
        if (!FriendActions.maySpareTarget(truce, toPlayer))
            return toPlayer ? "Моб и так в перемирии с вами" : "Пощадить другого моб согласится только в перемирии с вами";
        LivingEntity target = player;
        if (!toPlayer) {
            MobActionController.Resolved resolved = actions.resolve(mob, intent.target(), snapshot);
            if (resolved.entity() == null) return resolved.error();
            target = resolved.entity();
        }
        long now = mob.getWorld().getTime();
        Map<UUID, Spare> mine = spares.computeIfAbsent(mob.getUuid(), ignored -> new HashMap<>());
        Spare running = mine.get(target.getUuid());
        if (running != null) { running.lease.refresh(now); publish(mob.getUuid()); return ""; }
        String targetName = toPlayer ? player.getName().getString() : target.getName().getString();
        mine.put(target.getUuid(), new Spare(mob, player.getUuid(), target.getUuid(), targetName, toPlayer,
                new FriendActions.Lease(ActionType.SPARE, target.getUuid().toString(), now)));
        calmDown(mob, target);
        bridge.event(mob, "Обещал не нападать на " + targetName);
        publish(mob.getUuid());
        return Text.translatable("mobtalk.friend.spare.start", name(mob), targetName).getString();
    }
    /** Whatever the mob was doing to the one it now spares stops at once: target, anger and an explicit attack. */
    private void calmDown(MobEntity mob, LivingEntity target) {
        if (actions.attacking(mob, target)) actions.stop(mob.getUuid(), false);
        if (mob.getTarget() == target) mob.setTarget(null);
        if (mob instanceof Angerable angry && target.getUuid().equals(angry.getAngryAt())) angry.stopAnger();
        if (MobActionController.registered(mob, MemoryModuleType.ATTACK_TARGET)
                && mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ATTACK_TARGET).filter(target::equals).isPresent())
            mob.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        if (MobActionController.registered(mob, MemoryModuleType.ANGRY_AT)
                && mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ANGRY_AT).filter(target.getUuid()::equals).isPresent())
            mob.getBrain().forget(MemoryModuleType.ANGRY_AT);
    }

    /** Another action from the player: a STAY/GUARD it replaces gives way silently, since that action announces itself. */
    void replaced(MobEntity mob, ActionIntent intent) {
        Duty running = duties.get(mob.getUuid());
        if (running != null && FriendActions.change(running.lease, intent) == FriendActions.Change.REPLACE) remove(running);
    }
    /** The player now asks for an attack on someone this mob spares: the new request wins. */
    void lift(MobEntity mob, LivingEntity target) {
        Map<UUID, Spare> mine = spares.get(mob.getUuid());
        if (mine != null && mine.remove(target.getUuid()) != null) { if (mine.isEmpty()) spares.remove(mob.getUuid()); publish(mob.getUuid()); }
    }
    /** STOP: every friend request of this mob ends; the caller announces the stop and persists it. */
    void cancel(UUID id) {
        Duty duty = duties.get(id);
        if (duty != null) { remove(duty); bridge.event(duty.mob, "Action ended: " + duty.lease.type + " (" + FriendActions.End.CANCELLED + ")"); }
        if (spares.remove(id) != null) publish(id);
    }
    /** A blow from the player ends what the mob was doing for them. Deferred to the world tick like retaliation. */
    void hurtBy(MobEntity mob, ServerPlayerEntity player) {
        Duty duty = duties.get(mob.getUuid());
        if (duty != null && duty.playerId.equals(player.getUuid())) finish(duty, FriendActions.End.HURT);
        Map<UUID, Spare> mine = spares.get(mob.getUuid());
        Spare spare = mine == null ? null : mine.get(player.getUuid());
        if (spare != null) finish(spare, FriendActions.End.HURT);
    }

    boolean holds(UUID id) { return duties.containsKey(id); }
    boolean spared(MobEntity mob, LivingEntity target) {
        Map<UUID, Spare> mine = spares.get(mob.getUuid());
        return mine != null && target != null && mine.containsKey(target.getUuid());
    }
    /** Names of those this mob spares, for its own context. */
    List<String> sparing(MobEntity mob) {
        Map<UUID, Spare> mine = spares.get(mob.getUuid());
        return mine == null ? List.of() : mine.values().stream().map(spare -> spare.name).toList();
    }
    List<FriendActions.Status> status(UUID mob) { return statuses.getOrDefault(mob, List.of()); }

    void tick(ServerWorld world) {
        long now = world.getTime();
        for (Duty duty : List.copyOf(duties.values())) {
            if (duty.mob.getWorld() != world) continue;
            if (!bridge.config.enabled || !duty.mob.isAlive() || duty.mob.isRemoved()) { finish(duty, null); continue; }
            ServerPlayerEntity player = player(duty.playerId);
            double distance = player == null || !player.isAlive() || player.getWorld() != world ? Double.NaN : duty.mob.distanceTo(player);
            FriendActions.End end = duty.lease.check(now, distance, true);
            if (end != FriendActions.End.NONE) { finish(duty, end); continue; }
            if (duty.lease.type == ActionType.GUARD) guard(duty, player, world, now);
        }
        for (Map<UUID, Spare> mine : List.copyOf(spares.values())) {
            for (Spare spare : List.copyOf(mine.values())) {
                if (spare.mob.getWorld() != world) continue;
                if (!bridge.config.enabled || !spare.mob.isAlive() || spare.mob.isRemoved()) { finish(spare, null); continue; }
                Entity target = world.getEntity(spare.targetId);
                FriendActions.End end = spare.lease.check(now, Double.NaN, target instanceof LivingEntity living && living.isAlive() && !target.isRemoved());
                ServerPlayerEntity player = player(spare.playerId);
                if (end == FriendActions.End.NONE && !spare.toPlayer && (player == null || !MobGameBridge.truce(spare.mob, player)))
                    end = FriendActions.End.TRUCE_BROKEN;
                if (end != FriendActions.End.NONE) finish(spare, end);
            }
        }
    }

    /** A guard between fights stays near what it guards; a hostile that comes close to it is attacked through an ordinary attack
     * lease, and the guard comes back once the foe is dead, out of reach or chased too far. */
    private void guard(Duty duty, ServerPlayerEntity player, ServerWorld world, long now) {
        Vec3d anchor = duty.anchor(player);
        if (anchor == null) return;
        if (duty.foe != null) {
            Entity foe = world.getEntity(duty.foe);
            boolean fighting = foe instanceof LivingEntity living && actions.attacking(duty.mob, living);
            if (fighting && foe.isAlive() && !FriendActions.breakOff(Math.sqrt(foe.squaredDistanceTo(anchor)))) return;
            if (fighting) actions.stop(duty.mob.getUuid(), false);
            duty.foe = null; duty.approaching = true;
        }
        if (now < duty.nextScan) return;
        duty.nextScan = now + 10;
        MobEntity guard = duty.mob;
        LivingEntity foe = world.getEntitiesByClass(MobEntity.class, new Box(anchor, anchor).expand(FriendActions.guardAlert()),
                        other -> other != guard && other.isAlive() && !other.isRemoved()
                                && FriendActions.threatens(Math.sqrt(other.squaredDistanceTo(anchor))) && hostileTo(other, guard, player)
                                && guard.canSee(other) && actions.canAttack(guard, other))
                .stream().min(Comparator.comparingDouble(other -> other.squaredDistanceTo(anchor))).orElse(null);
        if (foe != null && actions.guardAttack(guard, foe)) {
            duty.foe = foe.getUuid();
            bridge.event(guard, "Защищал " + (FriendActions.HERE.equals(duty.lease.target) ? "место" : "игрока") + " от " + foe.getName().getString());
        }
    }
    /** A monster that is not under a truce with the player, or anything already going for the player or the guard. A calm neutral
     * (zombified piglin, enderman) is left alone: attacking it would start the fight the guard is there to prevent. */
    private static boolean hostileTo(MobEntity other, MobEntity guard, ServerPlayerEntity player) {
        if (other instanceof Tameable tameable && player != null && player.getUuid().equals(tameable.getOwnerUuid())) return false;
        LivingEntity target = other.getTarget();
        if (target != null && (target == player || target == guard)) return true;
        if (!(other instanceof Monster) || !SpeciesCatalog.get(MobGameBridge.type(other)).hostile()) return false;
        if (other instanceof Angerable angry && angry.getAngerTime() <= 0) return false;
        return player == null || !MobGameBridge.truce(other, player);
    }

    /** Brain-driven mobs re-plan a walk every tick; after their brain ran, the duty's own spot wins. True when a duty handled it. */
    boolean afterBrain(MobEntity mob) {
        Duty duty = duties.get(mob.getUuid());
        if (duty == null || duty.foe != null) return false;
        hold(duty, true);
        return true;
    }
    /** Stand still at the spot (STAY) or within NEAR–FAR of the guarded one (GUARD), facing the player when they are close. */
    private void hold(Duty duty, boolean afterBrain) {
        MobEntity mob = duty.mob;
        ServerPlayerEntity player = player(duty.playerId);
        if (player != null && player.getWorld() == mob.getWorld() && mob.squaredDistanceTo(player) <= LOOK_RANGE * LOOK_RANGE && bridge.sees(mob, player)) {
            mob.getLookControl().lookAt(player, 30f, 30f);
            if (MobActionController.registered(mob, MemoryModuleType.LOOK_TARGET))
                mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(player, true), 20);
        }
        Vec3d anchor = duty.anchor(player);
        double distance = anchor == null ? 0 : Math.sqrt(mob.squaredDistanceTo(anchor));
        boolean walk = duty.lease.type == ActionType.STAY ? distance > FriendActions.stayDrift()
                : (duty.approaching = FriendActions.approach(duty.approaching, distance));
        if (!walk) {
            if (afterBrain) forgetWalk(mob);
            mob.getNavigation().stop();
            return;
        }
        if (afterBrain && MobActionController.registered(mob, MemoryModuleType.WALK_TARGET))
            mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(anchor, 1.1F, duty.lease.type == ActionType.STAY ? 1 : (int) FriendActions.guardNear()), 20);
        long now = mob.getWorld().getTime();
        if (now >= duty.nextPath || !mob.getNavigation().isFollowingPath()) {
            duty.nextPath = now + 10;
            mob.getNavigation().startMovingTo(anchor.x, anchor.y, anchor.z, 1.1);
        }
        if (afterBrain) mob.getNavigation().tick();
    }
    private static void forgetWalk(MobEntity mob) {
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (MobActionController.registered(mob, MemoryModuleType.PATH)) mob.getBrain().forget(MemoryModuleType.PATH);
    }

    /** The duty ends on its own: the guard stops any fight it was in, the saved action clears and the player is told why
     * ({@code end} null: silently, the mob died, unloaded or Mob Talk was switched off). */
    private void finish(Duty duty, FriendActions.End end) {
        remove(duty);
        if (duty.foe != null && world(duty.mob).getEntity(duty.foe) instanceof LivingEntity foe && actions.attacking(duty.mob, foe))
            actions.stop(duty.mob.getUuid(), false);
        ActionIntent saved = bridge.memory.action(duty.mob.getUuid());
        if (saved != null && saved.type() == duty.lease.type) bridge.memory.recordAction(duty.mob.getUuid(), ActionIntent.none());
        if (end == null) return;
        bridge.event(duty.mob, "Action ended: " + duty.lease.type + " (" + end + ")");
        tell(duty.playerId, end, name(duty.mob), Text.translatable("mobtalk.action." + duty.lease.type.name().toLowerCase(Locale.ROOT)));
    }
    private void finish(Spare spare, FriendActions.End end) {
        Map<UUID, Spare> mine = spares.get(spare.mob.getUuid());
        if (mine == null || !mine.remove(spare.targetId, spare)) return;
        if (mine.isEmpty()) spares.remove(spare.mob.getUuid());
        publish(spare.mob.getUuid());
        if (end == null) return;
        bridge.event(spare.mob, "Больше не обещает щадить " + spare.name + " (" + end + ")");
        tell(spare.playerId, end, name(spare.mob), Text.translatable("mobtalk.friend.spare.label", spare.name));
    }
    private void remove(Duty duty) {
        if (!duties.remove(duty.mob.getUuid(), duty)) return;
        ((MobEntityAccessor) duty.mob).mobtalk$goalSelector().remove(duty.goal);
        duty.mob.getNavigation().stop();
        forgetWalk(duty.mob);
        publish(duty.mob.getUuid());
    }
    private void tell(UUID playerId, FriendActions.End end, String mob, Text action) {
        ServerPlayerEntity player = player(playerId);
        if (player != null) player.sendMessage(Text.literal("[Mob Talk] ").append(
                Text.translatable("mobtalk.friend.end." + end.name().toLowerCase(Locale.ROOT), mob, action)), false);
    }

    void unload(MobEntity mob) {
        Duty duty = duties.get(mob.getUuid());
        if (duty != null) remove(duty);
        if (spares.remove(mob.getUuid()) != null) publish(mob.getUuid());
    }
    void clear() {
        for (Duty duty : List.copyOf(duties.values())) remove(duty);
        spares.clear(); statuses.clear();
    }

    private void publish(UUID id) {
        List<FriendActions.Status> list = new ArrayList<>();
        Duty duty = duties.get(id);
        if (duty != null) list.add(new FriendActions.Status(duty.lease.type, duty.lease.target, duty.lease.expires()));
        Map<UUID, Spare> mine = spares.get(id);
        if (mine != null) for (Spare spare : mine.values()) list.add(new FriendActions.Status(ActionType.SPARE, spare.name, spare.lease.expires()));
        if (list.isEmpty()) statuses.remove(id); else statuses.put(id, List.copyOf(list));
    }
    private ServerPlayerEntity player(UUID id) { return bridge.server.getPlayerManager().getPlayer(id); }
    private static ServerWorld world(MobEntity mob) { return (ServerWorld) mob.getWorld(); }
    private String name(MobEntity mob) {
        String name = bridge.memory.displayName(mob.getUuid());
        return name.isBlank() ? mob.getName().getString() : name;
    }

    private final class Duty {
        final MobEntity mob; final UUID playerId; final FriendActions.Lease lease;
        /** The spot of STAY and of GUARD "here"; null while guarding the player. */
        final Vec3d spot;
        UUID foe; boolean approaching; long nextPath, nextScan;
        final Goal goal = new Goal() {
            { setControls(EnumSet.of(Control.MOVE, Control.LOOK)); }
            public boolean canStart() { return duties.get(mob.getUuid()) == Duty.this && foe == null; }
            public boolean shouldContinue() { return canStart(); }
            public boolean shouldRunEveryTick() { return true; }
            public void tick() { hold(Duty.this, false); }
        };
        Duty(MobEntity mob, UUID playerId, FriendActions.Lease lease, Vec3d spot) {
            this.mob = mob; this.playerId = playerId; this.lease = lease; this.spot = spot;
        }
        Vec3d anchor(ServerPlayerEntity player) {
            if (spot != null) return spot;
            return player != null && player.isAlive() && player.getWorld() == mob.getWorld() ? player.getPos() : null;
        }
    }
    private record Spare(MobEntity mob, UUID playerId, UUID targetId, String name, boolean toPlayer, FriendActions.Lease lease) { }
}
