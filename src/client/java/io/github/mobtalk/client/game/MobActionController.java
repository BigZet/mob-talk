package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import io.github.mobtalk.client.game.mixin.MobEntityAccessor;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.brain.*;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.*;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import java.util.*;

/** Small action leases; vanilla movement, collision, combat and gamerules remain authoritative. */
final class MobActionController {
    private static final Set<String> DIALOGUE_ONLY = Set.of("minecraft:ender_dragon", "minecraft:giant", "minecraft:bat",
            "minecraft:squid", "minecraft:glow_squid");
    private static final Set<String> ATTACK_ONLY = Set.of("minecraft:ghast", "minecraft:slime", "minecraft:magma_cube", "minecraft:wither");
    private final MobGameBridge bridge;
    private final Map<UUID, Lease> leases = new HashMap<>();
    MobActionController(MobGameBridge bridge) { this.bridge = bridge; }

    static List<ActionType> available(MobEntity mob) {
        List<ActionType> types = new ArrayList<>(List.of(ActionType.NONE, ActionType.STOP));
        String type = MobGameBridge.type(mob);
        if (DIALOGUE_ONLY.contains(type) || mob.isAiDisabled() || mob.hasVehicle()) return List.copyOf(types);
        if (!ATTACK_ONLY.contains(type) && mob instanceof PathAwareEntity
                && (mob.getNavigation() instanceof MobNavigation || mob.getNavigation() instanceof BirdNavigation
                        || mob.getNavigation() instanceof SwimNavigation)) {
            types.add(ActionType.FOLLOW); types.add(ActionType.FLEE);
        }
        boolean brain = mob.getBrain().getMemories().containsKey(MemoryModuleType.ATTACK_TARGET);
        if ((ATTACK_ONLY.contains(type) || mob instanceof net.minecraft.entity.ai.RangedAttackMob
                || mob.getAttributes().hasAttribute(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                && (!(mob instanceof net.minecraft.entity.passive.AnimalEntity) || brain || nativeAnimalCombat(type)))
                && !(mob instanceof SlimeEntity slime && !(mob instanceof MagmaCubeEntity) && slime.getSize() <= 1)
                && !(mob instanceof PiglinEntity piglin && piglin.isBaby()))
            types.add(ActionType.ATTACK);
        return List.copyOf(types);
    }
    private static boolean nativeAnimalCombat(String type) {
        return Set.of("minecraft:wolf", "minecraft:bee", "minecraft:polar_bear", "minecraft:panda", "minecraft:fox",
                "minecraft:ocelot").contains(type);
    }
    String apply(MobEntity mob, ServerPlayerEntity player, ActionIntent intent, MobSnapshot snapshot) {
        ActionType action = intent == null ? ActionType.NONE : intent.type();
        if (action == ActionType.NONE) return "";
        if (action == ActionType.STOP) { stop(mob.getUuid(), true); return "Действие отменено; моб вернулся к обычному поведению"; }
        if (!available(mob).contains(action)) return "Это действие недоступно данному мобу";
        MobCard card = bridge.memory.card(mob.getUuid(), player.getUuid());
        if (action == ActionType.FOLLOW && mob instanceof Tameable tameable && tameable.getOwnerUuid() != null
                && !tameable.getOwnerUuid().equals(player.getUuid())) return "У этого моба другой хозяин";
        if (action == ActionType.FOLLOW && (mob.getTarget() != null || card != null && card.relationship() < -30
                || mob instanceof Angerable angry && angry.getAngerTime() > 0))
            return "Моб сейчас зол или занят боем и не согласен следовать";
        if (mob.squaredDistanceTo(player) > bridge.config.maxDistance * bridge.config.maxDistance)
            return "Подойдите к мобу ближе для нового действия";
        LivingEntity target = player;
        if ((action == ActionType.ATTACK || action == ActionType.FLEE) && !"player".equals(intent.target())
                && !player.getUuid().toString().equals(intent.target())) {
            ObservedEntity observed = snapshot == null ? null : snapshot.observedEntities().stream()
                    .filter(entity -> entity.key().equals(intent.target()) || entity.id().toString().equals(intent.target())).findFirst().orElse(null);
            if (observed == null) return "Цель не наблюдалась мобом";
            Entity found = mob.getWorld() instanceof ServerWorld world ? world.getEntity(observed.id()) : null;
            if (!(found instanceof MobEntity living) || living == mob || !living.isAlive()
                    || living.squaredDistanceTo(mob) > 24 * 24 || !perceives(mob, living, observed))
                return "Цель больше не доступна мобу";
            target = living;
        }
        if (action == ActionType.ATTACK && !canAttack(mob, target)) return "Моб не может атаковать эту цель по своей природе";
        stop(mob.getUuid(), false);
        Lease lease = new Lease(mob, target.getUuid(), action, action == ActionType.FLEE ? mob.getWorld().getTime() + 400 : Long.MAX_VALUE);
        leases.put(mob.getUuid(), lease);
        MobEntityAccessor selectors = (MobEntityAccessor) mob;
        selectors.mobtalk$targetSelector().add(-1, lease.targetGoal);
        if (action != ActionType.ATTACK) selectors.mobtalk$goalSelector().add(-1, lease.moveGoal);
        if (action == ActionType.ATTACK) assignAttack(mob, target);
        bridge.memory.recordAction(mob.getUuid(), new ActionIntent(action, target.getUuid().toString()));
        bridge.event(mob, "Action started: " + action + "; target=" + target.getName().getString());
        return switch (action) {
            case FOLLOW -> "Моб следует за вами; остановка через STOP или кнопку отмены";
            case FLEE -> "Моб ищет безопасный путь подальше от " + target.getName().getString();
            case ATTACK -> "Моб атакует " + target.getName().getString() + " своим обычным способом";
            default -> "";
        };
    }
    private boolean perceives(MobEntity mob, MobEntity target, ObservedEntity observed) {
        if (mob instanceof WardenEntity)
            return Boolean.TRUE.equals(observed.details().get("audible"))
                    && bridge.heard(mob).stream().anyMatch(event -> target.getUuid().equals(event.source()));
        return observed.visible() && mob.canSee(target);
    }
    private static boolean canAttack(MobEntity mob, LivingEntity target) {
        if (!target.isAlive() || target.isSpectator() || target.isInCreativeMode() || !mob.canTarget(target)) return false;
        if (mob instanceof SlimeEntity && !(target instanceof net.minecraft.entity.player.PlayerEntity)
                && !(target instanceof net.minecraft.entity.passive.IronGolemEntity)) return false;
        if (mob instanceof WardenEntity warden && !warden.isValidTarget(target)) return false;
        if (mob instanceof net.minecraft.entity.passive.BeeEntity bee && bee.hasStung()) return false;
        if (mob instanceof net.minecraft.entity.passive.FrogEntity && !net.minecraft.entity.passive.FrogEntity.isValidFrogFood(target)) return false;
        if (mob instanceof net.minecraft.entity.passive.TameableEntity tameable && tameable.isTamed()
                && tameable.getOwner() != null && !tameable.canAttackWithOwner(target, tameable.getOwner())) return false;
        if (mob instanceof HoglinEntity && registered(mob, MemoryModuleType.PACIFIED)
                && mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.PACIFIED).isPresent()) return false;
        if (mob instanceof net.minecraft.entity.boss.WitherEntity && target.getType().isIn(net.minecraft.registry.tag.EntityTypeTags.UNDEAD)) return false;
        String type = MobGameBridge.type(mob);
        if (type.equals("minecraft:fox") || type.equals("minecraft:ocelot"))
            return target instanceof net.minecraft.entity.passive.ChickenEntity || target instanceof net.minecraft.entity.passive.RabbitEntity
                    || target instanceof net.minecraft.entity.passive.FishEntity;
        if (type.equals("minecraft:axolotl") && target instanceof net.minecraft.entity.player.PlayerEntity) return false;
        return true;
    }
    private static void assignAttack(MobEntity mob, LivingEntity target) {
        if (mob.getTarget() == target) return;
        if (mob instanceof WardenEntity warden) { warden.increaseAngerAt(target, 150, true); warden.updateAttackTarget(target); }
        else if (registered(mob, MemoryModuleType.ATTACK_TARGET)) {
            mob.getBrain().remember(MemoryModuleType.ATTACK_TARGET, target, 600);
            if (registered(mob, MemoryModuleType.ANGRY_AT)) mob.getBrain().remember(MemoryModuleType.ANGRY_AT, target.getUuid(), 600);
        } else mob.setTarget(target);
    }
    static boolean registered(MobEntity mob, MemoryModuleType<?> type) { return mob.getBrain().getMemories().containsKey(type); }
    void restore(MobEntity mob) {
        if (leases.containsKey(mob.getUuid())) return;
        if (!bridge.config.enabled || !mob.isAlive()) return;
        ActionIntent saved = bridge.memory.action(mob.getUuid());
        if (saved != null && saved.type() != ActionType.FOLLOW && saved.type() != ActionType.NONE) {
            bridge.memory.recordAction(mob.getUuid(), ActionIntent.none()); return;
        }
        if (saved == null || saved.type() != ActionType.FOLLOW || !available(mob).contains(ActionType.FOLLOW)) return;
        try {
            UUID owner = UUID.fromString(saved.target());
            Lease lease = new Lease(mob, owner, ActionType.FOLLOW, Long.MAX_VALUE);
            leases.put(mob.getUuid(), lease);
            ((MobEntityAccessor) mob).mobtalk$targetSelector().add(-1, lease.targetGoal);
            ((MobEntityAccessor) mob).mobtalk$goalSelector().add(-1, lease.moveGoal);
        } catch (IllegalArgumentException ignored) { }
    }
    void tick(ServerWorld world) {
        for (Lease lease : List.copyOf(leases.values())) {
            if (lease.mob.getWorld() != world) continue;
            if (!bridge.config.enabled || !lease.mob.isAlive() || lease.mob.isRemoved()) { stop(lease.mob.getUuid(), true); continue; }
            LivingEntity target = lease.target();
            if (lease.action == ActionType.FOLLOW && target == null) { lease.mob.getNavigation().stop(); continue; }
            if (target == null || !target.isAlive() || world.getTime() >= lease.expires
                    || lease.action == ActionType.ATTACK && (!canAttack(lease.mob, target) || lease.mob.squaredDistanceTo(target) > 64 * 64)) {
                stop(lease.mob.getUuid(), true); continue;
            }
            if (lease.action == ActionType.FLEE && lease.mob.squaredDistanceTo(target) >= 16 * 16) { stop(lease.mob.getUuid(), true); continue; }
            if (lease.action == ActionType.ATTACK) assignAttack(lease.mob, target);
        }
    }
    /** Brain tasks run after the goal navigation tick. Reapply only this lease's movement intent. */
    void afterBrain(MobEntity mob) {
        Lease lease = leases.get(mob.getUuid());
        if (lease == null || lease.action == ActionType.ATTACK) return;
        LivingEntity target = lease.target();
        if (target == null) return;
        if (registered(mob, MemoryModuleType.LOOK_TARGET)) mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(target, true), 20);
        Vec3d destination = lease.action == ActionType.FOLLOW ? target.getPos() : lease.fleePoint;
        if (destination != null && registered(mob, MemoryModuleType.WALK_TARGET))
            mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(destination, 1.1F, lease.action == ActionType.FOLLOW ? 4 : 1), 20);
        lease.move(true);
        mob.getNavigation().tick();
    }
    boolean moving(MobEntity mob) {
        Lease lease = leases.get(mob.getUuid());
        return lease != null && (lease.action == ActionType.FOLLOW || lease.action == ActionType.FLEE);
    }
    void hurtBy(MobEntity mob, UUID player) {
        Lease lease = leases.get(mob.getUuid());
        if (lease != null && lease.action == ActionType.FOLLOW && lease.targetId.equals(player)) stop(mob.getUuid(), true);
        else {
            ActionIntent saved = bridge.memory.action(mob.getUuid());
            if (saved != null && saved.type() == ActionType.FOLLOW && player.toString().equals(saved.target()))
                bridge.memory.recordAction(mob.getUuid(), ActionIntent.none());
        }
    }
    void targetDied(UUID id) {
        for (Lease lease : List.copyOf(leases.values())) if (lease.targetId.equals(id)) stop(lease.mob.getUuid(), true);
    }
    void stopFollowers() {
        for (MobCard card : bridge.memory.cards(null))
            if (card.action() != null && card.action().type() == ActionType.FOLLOW) {
                stop(card.id(), true); bridge.memory.recordAction(card.id(), ActionIntent.none());
            }
    }
    void stop(UUID id, boolean persist) {
        Lease lease = leases.remove(id);
        if (lease != null) {
            MobEntity mob = lease.mob;
            ((MobEntityAccessor) mob).mobtalk$targetSelector().remove(lease.targetGoal);
            ((MobEntityAccessor) mob).mobtalk$goalSelector().remove(lease.moveGoal);
            mob.getNavigation().stop();
            if (mob.getTarget() != null && mob.getTarget().getUuid().equals(lease.targetId)) mob.setTarget(null);
            for (MemoryModuleType<?> type : List.of(MemoryModuleType.WALK_TARGET, MemoryModuleType.LOOK_TARGET, MemoryModuleType.PATH))
                if (registered(mob, type)) mob.getBrain().forget(type);
            if (registered(mob, MemoryModuleType.ATTACK_TARGET))
                mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ATTACK_TARGET)
                        .filter(target -> target.getUuid().equals(lease.targetId)).ifPresent(target -> mob.getBrain().forget(MemoryModuleType.ATTACK_TARGET));
        }
        if (persist) bridge.memory.recordAction(id, ActionIntent.none());
    }
    void unload(MobEntity mob) { stop(mob.getUuid(), false); }
    void clearRuntime() { for (UUID id : List.copyOf(leases.keySet())) stop(id, false); }

    private final class Lease {
        final MobEntity mob; final UUID targetId; final ActionType action; final long expires;
        Vec3d fleePoint; long nextPath;
        final Goal targetGoal = new Goal() {
            { setControls(EnumSet.of(Control.TARGET)); }
            public boolean canStart() { return leases.get(mob.getUuid()) == Lease.this && target() != null; }
            public boolean shouldContinue() { return canStart(); }
            public boolean shouldRunEveryTick() { return true; }
            public void tick() { if (action == ActionType.ATTACK) { LivingEntity target = target(); if (target != null) assignAttack(mob, target); }
                else mob.setTarget(null); }
        };
        final Goal moveGoal = new Goal() {
            { setControls(EnumSet.of(Control.MOVE, Control.LOOK)); }
            public boolean canStart() { return leases.get(mob.getUuid()) == Lease.this && target() != null; }
            public boolean shouldContinue() { return canStart(); }
            public boolean shouldRunEveryTick() { return true; }
            public void tick() { move(false); }
        };
        Lease(MobEntity mob, UUID targetId, ActionType action, long expires) {
            this.mob = mob; this.targetId = targetId; this.action = action; this.expires = expires;
        }
        LivingEntity target() {
            Entity found = ((ServerWorld) mob.getWorld()).getEntity(targetId);
            return found instanceof LivingEntity living && living.isAlive() ? living : null;
        }
        void move(boolean afterBrain) {
            LivingEntity target = target();
            if (target == null) return;
            mob.getLookControl().lookAt(target, 30, 30);
            if (action == ActionType.FOLLOW && mob.squaredDistanceTo(target) <= 4 * 4) { mob.getNavigation().stop(); return; }
            if (mob.getWorld().getTime() < nextPath && (!afterBrain || mob.getNavigation().isFollowingPath())) return;
            nextPath = mob.getWorld().getTime() + 10;
            if (action == ActionType.FOLLOW) { mob.getNavigation().startMovingTo(target, 1.1); return; }
            if (!(mob instanceof PathAwareEntity pathAware)) return;
            fleePoint = mob.getNavigation() instanceof BirdNavigation || mob.getNavigation() instanceof SwimNavigation
                    ? NoPenaltyTargeting.findFrom(pathAware, 16, 7, target.getPos()) : FuzzyTargeting.findFrom(pathAware, 16, 4, target.getPos());
            if (fleePoint == null || fleePoint.squaredDistanceTo(target.getPos()) <= mob.squaredDistanceTo(target)) return;
            BlockPos point = BlockPos.ofFloored(fleePoint);
            if (!((ServerWorld) mob.getWorld()).getChunkManager().isChunkLoaded(point.getX() >> 4, point.getZ() >> 4)
                    || mob.getWorld().getBlockState(point).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                    || mob.getNavigation() instanceof SwimNavigation && !mob.getWorld().getFluidState(point).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                fleePoint = null; return;
            }
            Path path = mob.getNavigation().findPathTo(point, 1);
            if (path != null && path.reachesTarget()) {
                for (int i = 0; i < path.getLength(); i++) {
                    BlockPos node = path.getNodePos(i);
                    if (!((ServerWorld) mob.getWorld()).getChunkManager().isChunkLoaded(node.getX() >> 4, node.getZ() >> 4)) return;
                    var block = mob.getWorld().getBlockState(node);
                    if (block.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                            || block.isIn(net.minecraft.registry.tag.BlockTags.FIRE)
                            || mob.getNavigation() instanceof SwimNavigation && !block.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) return;
                }
                mob.getNavigation().startMovingAlong(path, 1.2);
            }
        }
    }
}
