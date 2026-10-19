package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.SwimNavigation;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Where bonds between mobs come from in the game and what they do to it: creatures standing together warm up, a blow between
 * two of them makes enemies, a death seen is mourned; friends drift towards each other, enemies step away, a friend comes to
 * sit with a mourner once. Deterministic, a few checks every two seconds, only around the player, only for mobs the world
 * memory knows; vanilla AI keeps the wheel — this only hands it a short walk target now and then. Server thread only. */
final class MobSocialController {
    /** Checks every two seconds, on a tick the hatred stance does not use. */
    private static final long PERIOD = 40, PHASE = 23;
    /** The budget window for social walks (one minute) and how long one mob rests after a walk. */
    private static final long BUDGET_WINDOW = 1200, MOB_COOLDOWN = 100, RETREAT_COOLDOWN = 60;
    /** How many known mobs around the players are considered at once. */
    private static final int MAX_MOBS = 64;
    private final MobGameBridge bridge;
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Friends on their way to, or sitting with, a mourner. */
    private final Map<UUID, Visit> visits = new HashMap<>();
    private long windowStart = -1; private int walks;
    private static final class Visit {
        final MobEntity mob, mourner; final UUID dead; final long deadline; long until; boolean arrived;
        Visit(MobEntity mob, MobEntity mourner, UUID dead, long deadline) { this.mob = mob; this.mourner = mourner; this.dead = dead; this.deadline = deadline; }
    }
    MobSocialController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.socialBehavior; }
    private static String type(Entity entity) { return MobGameBridge.type(entity); }
    private static boolean hostile(Entity entity) { return SpeciesCatalog.get(type(entity)).hostile(); }
    private String name(MobEntity mob) { String given = bridge.memory.displayName(mob.getUuid()); return given.isBlank() ? mob.getName().getString() : given; }

    // ---- Sources: what the game does to the bonds. ----

    /** One creature struck another (vanilla fights: zombie and villager, wolf and sheep): enemies, when both are known. */
    void attacked(MobEntity attacker, MobEntity victim) {
        if (!bridge.config.enabled || attacker == victim || !bridge.memory.known(attacker.getUuid()) || !bridge.memory.known(victim.getUuid())) return;
        long now = System.currentTimeMillis();
        bridge.memory.updateRelations(r -> r.attacked(attacker.getUuid(), name(attacker), type(attacker), victim.getUuid(), name(victim), type(victim), now));
    }
    /** A creature died: every known mob that could see it and knew it mourns, with the name, whoever the killer was. */
    void died(MobEntity victim) {
        if (!bridge.config.enabled || !(victim.getWorld() instanceof ServerWorld world)) return;
        UUID dead = victim.getUuid();
        String deadName = name(victim), deadType = type(victim);
        boolean acquainted = bridge.memory.acquainted(dead);
        long now = System.currentTimeMillis();
        for (MobEntity witness : world.getEntitiesByClass(MobEntity.class, victim.getBoundingBox().expand(MobGameBridge.WITNESS_RANGE),
                other -> other != victim && other.isAlive() && !other.isRemoved() && bridge.memory.known(other.getUuid()) && other.canSee(victim)))
            bridge.memory.updateRelations(r -> r.mourn(witness.getUuid(), dead, deadName, deadType, acquainted, now));
    }
    /** The player talks to {@code mob}: the known creatures around the pair get acquainted with the speaker. */
    void conversation(MobEntity mob, MobSnapshot snapshot) {
        if (!bridge.config.enabled || !(mob.getWorld() instanceof ServerWorld world)) return;
        long now = System.currentTimeMillis();
        for (ObservedEntity entity : snapshot.observedEntities()) {
            if (!entity.visible() || !SocialRules.together(entity.distance()) || !bridge.memory.known(entity.id())) continue;
            if (!(world.getEntity(entity.id()) instanceof MobEntity other) || hostile(other) != hostile(mob)) continue;
            MobRelations.Kinship kinship = bridge.memory.kinship(mob.getUuid(), other.getUuid());
            bridge.memory.updateRelations(r -> r.together(mob.getUuid(), name(mob), type(mob), other.getUuid(), name(other), type(other), kinship,
                    MobRelations.MET_GAIN, MobRelations.MET_INTERVAL, now));
        }
    }

    // ---- Behaviour: what the bonds do to the game. ----

    void tick(ServerWorld world) {
        if (!enabled()) { if (!visits.isEmpty()) visits.clear(); return; }
        tickVisits(world);
        if (world.getTime() % PERIOD != PHASE) return;
        long now = System.currentTimeMillis(), time = world.getTime();
        double range = SocialRules.PLAYER_RANGE;
        Map<UUID, MobEntity> around = new LinkedHashMap<>();
        Map<UUID, Double> toPlayer = new HashMap<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(range),
                    other -> other.isAlive() && !other.isRemoved() && !other.isAiDisabled() && bridge.memory.known(other.getUuid())
                            && other.squaredDistanceTo(player) <= range * range)) {
                if (around.size() >= MAX_MOBS) break;
                around.putIfAbsent(mob.getUuid(), mob);
                toPlayer.merge(mob.getUuid(), (double) mob.distanceTo(player), Math::min);
            }
        }
        if (around.size() < 2) return;
        List<MobEntity> mobs = new ArrayList<>(around.values());
        // Standing together: every known pair within reach warms up a little, neighbours and kin from a head start — and, once per
        // interval, the village's rumours cross between two of its inhabitants.
        for (int i = 0; i < mobs.size(); i++) for (int j = i + 1; j < mobs.size(); j++) {
            MobEntity a = mobs.get(i), b = mobs.get(j);
            if (hostile(a) != hostile(b) || !SocialRules.together(a.distanceTo(b))) continue;
            MobRelations.Kinship kinship = bridge.memory.kinship(a.getUuid(), b.getUuid());
            boolean met = bridge.memory.updateRelations(r -> r.together(a.getUuid(), name(a), type(a), b.getUuid(), name(b), type(b), kinship,
                    MobRelations.TOGETHER_GAIN, MobRelations.TOGETHER_INTERVAL, now));
            if (met) bridge.memory.spreadRumors(a.getUuid(), b.getUuid(), random::nextDouble, now);
        }
        traders(world, mobs, now);
        // Grief: a friend goes to sit with the mourner, once per loss; the mourner itself may be talking to the player meanwhile.
        for (MobEntity mourner : mobs) {
            List<MobRelations.View> bonds = bridge.memory.bonds(mourner.getUuid(), now);
            MobRelations.View grief = null;
            for (MobRelations.View bond : bonds) if (bond.mourns() && !bond.consoled()) { grief = bond; break; }
            if (grief == null) continue;
            for (MobEntity friend : mobs) {
                if (friend == mourner || !free(friend) || !SocialRules.shouldVisit(bonds, friend.getUuid()) || !budget(time)) continue;
                UUID dead = grief.other();
                visits.put(friend.getUuid(), new Visit(friend, mourner, dead, time + SocialRules.VISIT_DEADLINE_TICKS));
                cooldowns.put(friend.getUuid(), time + MOB_COOLDOWN);
                walkTo(friend, mourner, 0.9F, 2);
                break;
            }
        }
        // One move per free mob: away from an enemy at hand, or, now and then, over to a friend.
        for (MobEntity mob : mobs) {
            if (!free(mob)) continue;
            Long rest = cooldowns.get(mob.getUuid());
            if (rest != null && time < rest) continue;
            List<MobRelations.View> bonds = bridge.memory.bonds(mob.getUuid(), now);
            if (bonds.isEmpty()) continue;
            SocialRules.Actor self = new SocialRules.Actor(mob.getUuid(), false, hostile(mob), toPlayer.getOrDefault(mob.getUuid(), Double.MAX_VALUE));
            SocialRules.Decision decision = SocialRules.decide(self, bonds, id -> {
                MobEntity other = around.get(id);
                return other == null || other.getWorld() != world ? null : (double) mob.distanceTo(other);
            }, random.nextDouble());
            MobEntity target = decision.target() == null ? null : around.get(decision.target());
            if (target == null) continue;
            switch (decision.move()) {
                case APPROACH -> { if (budget(time)) { walkTo(mob, target, 0.9F, 3); cooldowns.put(mob.getUuid(), time + MOB_COOLDOWN); } }
                case RETREAT -> { if (retreat(mob, target)) cooldowns.put(mob.getUuid(), time + RETREAT_COOLDOWN); }
                default -> { }
            }
        }
    }
    /** When each wandering trader last traded rumours, so a visit counts once per {@link MobRelations#TOGETHER_INTERVAL}. */
    private final Map<UUID, Long> traderVisits = new HashMap<>();
    /** A wandering trader standing with a villager picks up the village's rumours, or hands over the ones it brought from the last village. */
    private void traders(ServerWorld world, List<MobEntity> known, long now) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            for (net.minecraft.entity.passive.WanderingTraderEntity trader : world.getEntitiesByClass(net.minecraft.entity.passive.WanderingTraderEntity.class,
                    player.getBoundingBox().expand(SocialRules.PLAYER_RANGE), other -> other.isAlive() && !other.isRemoved())) {
                Long last = traderVisits.get(trader.getUuid());
                if (last != null && now - last < MobRelations.TOGETHER_INTERVAL) continue;
                for (MobEntity villager : known) {
                    if (!(villager instanceof net.minecraft.entity.passive.VillagerEntity) || !SocialRules.together(trader.distanceTo(villager))) continue;
                    traderVisits.put(trader.getUuid(), now);
                    bridge.memory.tradeRumors(trader.getUuid(), villager.getUuid(), now);
                    break;
                }
            }
        }
    }
    private void tickVisits(ServerWorld world) {
        if (visits.isEmpty()) return;
        long time = world.getTime();
        for (Visit visit : List.copyOf(visits.values())) {
            if (visit.mob.getWorld() != world) continue;
            boolean over = !visit.mob.isAlive() || visit.mob.isRemoved() || !visit.mourner.isAlive() || visit.mourner.isRemoved()
                    || visit.mourner.getWorld() != world || bridge.actions.busy(visit.mob) || visit.mob.getTarget() != null
                    || visit.mob instanceof PathAwareEntity aware && aware.isPanicking();
            if (!visit.arrived) {
                if (!over && visit.mob.squaredDistanceTo(visit.mourner) <= 3 * 3) {
                    visit.arrived = true;
                    visit.until = time + SocialRules.VISIT_MIN_TICKS + random.nextInt(SocialRules.VISIT_MAX_TICKS - SocialRules.VISIT_MIN_TICKS + 1);
                    visit.mob.getNavigation().stop();
                } else if (over || time >= visit.deadline) {
                    // Could not get there: the loss still counts as shared, so nobody keeps trying forever.
                    visits.remove(visit.mob.getUuid());
                    long now = System.currentTimeMillis();
                    bridge.memory.updateRelations(r -> r.consoled(visit.mourner.getUuid(), visit.dead, now));
                } else if (time % 20 == 0) walkTo(visit.mob, visit.mourner, 0.9F, 2);
                continue;
            }
            if (over || time >= visit.until) {
                visits.remove(visit.mob.getUuid());
                long now = System.currentTimeMillis();
                bridge.memory.updateRelations(r -> r.consoled(visit.mourner.getUuid(), visit.dead, now));
                continue;
            }
            hold(visit.mob, visit.mourner);
        }
    }
    /** Standing with the mourner: no walking, eyes on the friend; brains re-plan a walk every tick, so this runs after them too. */
    private static void hold(MobEntity mob, MobEntity other) {
        mob.getNavigation().stop();
        mob.getLookControl().lookAt(other, 30f, 30f);
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (MobActionController.registered(mob, MemoryModuleType.PATH)) mob.getBrain().forget(MemoryModuleType.PATH);
        if (MobActionController.registered(mob, MemoryModuleType.LOOK_TARGET)) mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(other, true), 20);
    }
    /** Whether this mob is sitting with a mourner right now, for the brain hook that keeps it from wandering off. */
    boolean standing(MobEntity mob) { Visit visit = visits.get(mob.getUuid()); return visit != null && visit.arrived; }
    void afterBrain(MobEntity mob) { Visit visit = visits.get(mob.getUuid()); if (visit != null && visit.arrived) hold(mob, visit.mourner); }

    /** A mob that may be moved socially: walks, not in a fight, a conversation, a panic or a lease, not sitting, riding or on a leash,
     * not hostile by nature. */
    private boolean free(MobEntity mob) {
        if (!(mob instanceof PathAwareEntity aware) || aware.isPanicking() || hostile(mob) || visits.containsKey(mob.getUuid())) return false;
        if (mob.getTarget() != null || mob.hasVehicle() || mob.isLeashed() || mob instanceof TameableEntity tameable && tameable.isSitting()) return false;
        return !bridge.actions.busy(mob);
    }
    /** The walks allowed per minute across the world, from the config. */
    private boolean budget(long time) {
        if (windowStart < 0 || time - windowStart >= BUDGET_WINDOW) { windowStart = time; walks = 0; }
        if (walks >= bridge.config.socialActionsPerMinute) return false;
        walks++;
        return true;
    }
    /** A short walk target the vanilla AI carries out: a brain memory for villagers and the like, plain navigation for the rest. */
    private static void walkTo(MobEntity mob, MobEntity target, float speed, int completionRange) {
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) {
            mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityLookTarget(target, false), speed, completionRange), 120);
            if (MobActionController.registered(mob, MemoryModuleType.LOOK_TARGET)) mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(target, true), 120);
        } else {
            mob.getNavigation().startMovingTo(target, speed);
            mob.getLookControl().lookAt(target, 30f, 30f);
        }
    }
    /** A few blocks away from an enemy standing too close; false when no safe point was found. */
    private static boolean retreat(MobEntity mob, MobEntity enemy) {
        if (!(mob instanceof PathAwareEntity aware)) return false;
        Vec3d point = mob.getNavigation() instanceof BirdNavigation || mob.getNavigation() instanceof SwimNavigation
                ? NoPenaltyTargeting.findFrom(aware, 8, 4, enemy.getPos()) : FuzzyTargeting.findFrom(aware, 8, 4, enemy.getPos());
        if (point == null || point.squaredDistanceTo(enemy.getPos()) <= mob.squaredDistanceTo(enemy)) return false;
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(point, 1.0F, 1), 80);
        else mob.getNavigation().startMovingTo(point.x, point.y, point.z, 1.0);
        return true;
    }

    void unload(MobEntity mob) {
        cooldowns.remove(mob.getUuid()); visits.remove(mob.getUuid()); traderVisits.remove(mob.getUuid());
        visits.values().removeIf(visit -> visit.mourner == mob);
    }
    void clearRuntime() { cooldowns.clear(); visits.clear(); traderVisits.clear(); windowStart = -1; walks = 0; }
}
