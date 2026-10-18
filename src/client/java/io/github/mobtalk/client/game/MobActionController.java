package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import io.github.mobtalk.client.game.mixin.MobEntityAccessor;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.brain.*;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.pathing.*;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import net.minecraft.util.Hand;
import java.util.*;

/** Small action leases; vanilla movement, collision, combat and gamerules remain authoritative. */
final class MobActionController {
    private static final Set<String> DIALOGUE_ONLY = Set.of("minecraft:ender_dragon", "minecraft:giant", "minecraft:bat",
            "minecraft:squid", "minecraft:glow_squid");
    private static final Set<String> ATTACK_ONLY = Set.of("minecraft:ghast", "minecraft:slime", "minecraft:magma_cube", "minecraft:wither");
    private final MobGameBridge bridge;
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final Map<UUID, Retaliation> pendingRetaliations = new HashMap<>();
    private record Retaliation(MobEntity mob, ServerPlayerEntity player) { }
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
        if ((canUseMeleeFallback(mob) || ATTACK_ONLY.contains(type) || mob instanceof net.minecraft.entity.ai.RangedAttackMob
                || mob.getAttributes().hasAttribute(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                && (!(mob instanceof net.minecraft.entity.passive.AnimalEntity) || brain || nativeAnimalCombat(type)
                        || isKillerRabbit(mob)))
                && !(mob instanceof SlimeEntity slime && !(mob instanceof MagmaCubeEntity) && slime.getSize() <= 1)
                && !(mob instanceof PiglinEntity piglin && piglin.isBaby()))
            types.add(ActionType.ATTACK);
        return List.copyOf(types);
    }
    private static boolean nativeAnimalCombat(String type) {
        return Set.of("minecraft:wolf", "minecraft:cat", "minecraft:bee", "minecraft:polar_bear", "minecraft:panda", "minecraft:fox",
                "minecraft:ocelot").contains(type);
    }
    private static boolean canUseMeleeFallback(MobEntity mob) {
        if (!(mob instanceof PathAwareEntity) || mob instanceof net.minecraft.entity.ai.RangedAttackMob) return false;
        if (!mob.getAttributes().hasAttribute(EntityAttributes.GENERIC_ATTACK_DAMAGE)) return true;
        if (isKillerRabbit(mob)) return false;
        return mob instanceof net.minecraft.entity.passive.AnimalEntity && !nativeAnimalCombat(MobGameBridge.type(mob))
                && !(mob instanceof HoglinEntity) && !registered(mob, MemoryModuleType.ATTACK_TARGET);
    }
    private static boolean isKillerRabbit(MobEntity mob) {
        return mob instanceof net.minecraft.entity.passive.RabbitEntity rabbit
                && rabbit.getVariant() == net.minecraft.entity.passive.RabbitEntity.RabbitType.EVIL;
    }
    private static boolean needsMeleeFallback(MobEntity mob, LivingEntity target) {
        // Their Brain's food/hunting attacks cannot target a player; anger uses a contact strike instead.
        return canUseMeleeFallback(mob) || target instanceof ServerPlayerEntity
                && (mob instanceof net.minecraft.entity.passive.FrogEntity || mob instanceof net.minecraft.entity.passive.AxolotlEntity);
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
        else if (lease.meleeGoal != null) selectors.mobtalk$goalSelector().add(-1, lease.meleeGoal);
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
    boolean canAttack(MobEntity mob, LivingEntity target) {
        if (!target.isAlive() || target.isSpectator() || target.isInCreativeMode() || !mob.canTarget(target)) return false;
        boolean provokedPlayer = false;
        if (target instanceof ServerPlayerEntity player) {
            MobCard card = bridge.memory.card(mob.getUuid(), player.getUuid());
            int relationship = card == null ? 0 : card.relationship();
            boolean aggressive = mob.getTarget() == target || mob instanceof Angerable angry
                    && angry.getAngerTime() > 0 && player.getUuid().equals(angry.getAngryAt());
            if (!CombatRules.mayAttackPlayer(SpeciesCatalog.get(MobGameBridge.type(mob)).hostile(), relationship, aggressive)) return false;
            provokedPlayer = relationship <= -40;
        }
        if (mob instanceof SlimeEntity && !(target instanceof net.minecraft.entity.player.PlayerEntity)
                && !(target instanceof net.minecraft.entity.passive.IronGolemEntity)) return false;
        if (mob instanceof WardenEntity warden && !warden.isValidTarget(target)) return false;
        if (mob instanceof net.minecraft.entity.passive.BeeEntity bee && bee.hasStung()) return false;
        if (!provokedPlayer && mob instanceof net.minecraft.entity.passive.FrogEntity && !net.minecraft.entity.passive.FrogEntity.isValidFrogFood(target)) return false;
        if (mob instanceof net.minecraft.entity.passive.TameableEntity tameable && tameable.isTamed()
                && tameable.getOwner() != null && !provokedPlayer && !tameable.canAttackWithOwner(target, tameable.getOwner())) return false;
        if (mob instanceof HoglinEntity && registered(mob, MemoryModuleType.PACIFIED)
                && mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.PACIFIED).isPresent()) return false;
        if (mob instanceof net.minecraft.entity.boss.WitherEntity && target.getType().isIn(net.minecraft.registry.tag.EntityTypeTags.UNDEAD)) return false;
        String type = MobGameBridge.type(mob);
        if (!provokedPlayer && (type.equals("minecraft:fox") || type.equals("minecraft:ocelot")))
            return target instanceof net.minecraft.entity.passive.ChickenEntity || target instanceof net.minecraft.entity.passive.RabbitEntity
                    || target instanceof net.minecraft.entity.passive.FishEntity;
        if (!provokedPlayer && type.equals("minecraft:axolotl") && target instanceof net.minecraft.entity.player.PlayerEntity) return false;
        return true;
    }
    private void assignAttack(MobEntity mob, LivingEntity target) {
        Lease lease = leases.get(mob.getUuid());
        if (lease != null && lease.meleeGoal != null) {
            mob.setTarget(target);
            if (registered(mob, MemoryModuleType.ATTACK_TARGET)) mob.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
            return;
        }
        if (mob.getTarget() == target) return;
        if (mob instanceof Angerable angry) { angry.setAngryAt(target.getUuid()); angry.chooseRandomAngerTime(); }
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
        // Damage may be reflected by Thorns inside GoalSelector.tickGoals(). Mutate selectors only after entity ticks.
        for (Retaliation pending : List.copyOf(pendingRetaliations.values())) {
            if (pending.mob.getWorld() != world) continue;
            pendingRetaliations.remove(pending.mob.getUuid());
            retaliate(pending.mob, pending.player);
        }
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
        // Brain-driven hostiles (piglins, hoglins, wardens, zoglins) choose targets in memory, not through setTarget.
        if (registered(mob, MemoryModuleType.ATTACK_TARGET) && mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ATTACK_TARGET)
                .orElse(null) instanceof ServerPlayerEntity player && MobGameBridge.truce(mob, player)) {
            mob.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
            if (registered(mob, MemoryModuleType.ANGRY_AT)) mob.getBrain().forget(MemoryModuleType.ANGRY_AT);
            if (mob instanceof WardenEntity warden) warden.removeSuspect(player);
        }
        Lease lease = leases.get(mob.getUuid());
        if (lease == null || lease.action == ActionType.ATTACK && lease.meleeGoal == null) return;
        LivingEntity target = lease.target();
        if (target == null) return;
        if (registered(mob, MemoryModuleType.LOOK_TARGET)) mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(target, true), 20);
        if (lease.meleeGoal != null) assignAttack(mob, target);
        Vec3d destination = lease.action == ActionType.FLEE ? lease.fleePoint : target.getPos();
        if (destination != null && registered(mob, MemoryModuleType.WALK_TARGET))
            mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(destination, 1.1F, lease.action == ActionType.FOLLOW ? 4 : 1), 20);
        lease.move(true);
        mob.getNavigation().tick();
    }
    boolean suppressesNativeAttack(MobEntity mob) {
        Lease lease = leases.get(mob.getUuid());
        return lease != null && (lease.action == ActionType.FOLLOW || lease.action == ActionType.FLEE || lease.meleeGoal != null);
    }
    void hurtBy(MobEntity mob, ServerPlayerEntity player) {
        if (bridge.config.enabled) pendingRetaliations.put(mob.getUuid(), new Retaliation(mob, player));
    }
    private void retaliate(MobEntity mob, ServerPlayerEntity player) {
        if (!bridge.config.enabled || !mob.isAlive() || mob.isRemoved() || player.getWorld() != mob.getWorld()) return;
        UUID playerId = player.getUuid();
        Lease lease = leases.get(mob.getUuid());
        if (lease != null && lease.action == ActionType.ATTACK && lease.targetId.equals(playerId)) {
            if (lease.retaliatory) lease.expires = mob.getWorld().getTime() + 400;
            return; // Keep the native melee cooldown and any pre-existing explicit attack.
        }
        if (lease != null && lease.action == ActionType.FOLLOW && lease.targetId.equals(playerId)) stop(mob.getUuid(), true);
        else {
            ActionIntent saved = bridge.memory.action(mob.getUuid());
            if (saved != null && saved.type() == ActionType.FOLLOW && playerId.toString().equals(saved.target()))
                bridge.memory.recordAction(mob.getUuid(), ActionIntent.none());
        }
        if (!bridge.config.enabled || !mob.isAlive() || !available(mob).contains(ActionType.ATTACK) || !canAttack(mob, player)) return;
        apply(mob, player, new ActionIntent(ActionType.ATTACK, "player"), null);
        Lease retaliation = leases.get(mob.getUuid());
        if (retaliation != null && retaliation.action == ActionType.ATTACK && retaliation.targetId.equals(playerId)) {
            retaliation.retaliatory = true;
            retaliation.expires = mob.getWorld().getTime() + 400;
        }
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
            if (lease.meleeGoal != null) ((MobEntityAccessor) mob).mobtalk$goalSelector().remove(lease.meleeGoal);
            mob.getNavigation().stop();
            if (mob.getTarget() != null && mob.getTarget().getUuid().equals(lease.targetId)) mob.setTarget(null);
            for (MemoryModuleType<?> type : List.of(MemoryModuleType.WALK_TARGET, MemoryModuleType.LOOK_TARGET, MemoryModuleType.PATH))
                if (registered(mob, type)) mob.getBrain().forget(type);
            if (registered(mob, MemoryModuleType.ATTACK_TARGET))
                mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ATTACK_TARGET)
                        .filter(target -> target.getUuid().equals(lease.targetId)).ifPresent(target -> mob.getBrain().forget(MemoryModuleType.ATTACK_TARGET));
            lease.restoreAnger();
        }
        if (persist) bridge.memory.recordAction(id, ActionIntent.none());
    }
    void unload(MobEntity mob) { stop(mob.getUuid(), false); }
    void clearRuntime() { pendingRetaliations.clear(); for (UUID id : List.copyOf(leases.keySet())) stop(id, false); }

    private final class Lease {
        final MobEntity mob; final UUID targetId; final ActionType action; long expires;
        final MeleeAttackGoal meleeGoal;
        final long started;
        final UUID previousAngryAt, previousBrainAnger;
        final int previousAngerTime;
        final long previousBrainAngerExpiry;
        boolean retaliatory;
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
            started = mob.getWorld().getTime();
            previousAngryAt = mob instanceof Angerable angry ? angry.getAngryAt() : null;
            previousAngerTime = mob instanceof Angerable angry ? angry.getAngerTime() : 0;
            previousBrainAnger = registered(mob, MemoryModuleType.ANGRY_AT)
                    ? mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ANGRY_AT).orElse(null) : null;
            previousBrainAngerExpiry = previousBrainAnger == null ? 0 : mob.getBrain().getMemoryExpiry(MemoryModuleType.ANGRY_AT);
            meleeGoal = action == ActionType.ATTACK && needsMeleeFallback(mob, target())
                    ? new MeleeAttackGoal((PathAwareEntity) mob, 1.15, true) {
                        @Override public boolean canStart() { return leases.get(mob.getUuid()) == Lease.this && super.canStart(); }
                        @Override public boolean shouldContinue() { return leases.get(mob.getUuid()) == Lease.this && super.shouldContinue(); }
                        @Override protected void attack(LivingEntity target) {
                            if (!canAttack(target) || !MobActionController.this.canAttack(mob, target)) return;
                            resetCooldown();
                            mob.swingHand(Hand.MAIN_HAND);
                            if (mob.getAttributes().hasAttribute(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                                    && !(mob instanceof net.minecraft.entity.passive.FrogEntity)) mob.tryAttack(target);
                            else target.damage(mob.getDamageSources().mobAttack(mob), 2.0F);
                        }
                    } : null;
        }
        void restoreAnger() {
            if (action != ActionType.ATTACK) return;
            long elapsed = Math.max(0, mob.getWorld().getTime() - started);
            if (mob instanceof Angerable angry && targetId.equals(angry.getAngryAt())
                    && (!targetId.equals(previousAngryAt) || previousAngerTime == 0)) {
                int remaining = (int) Math.max(0, previousAngerTime - elapsed);
                angry.setAngryAt(remaining > 0 ? previousAngryAt : null);
                angry.setAngerTime(remaining);
            }
            if (registered(mob, MemoryModuleType.ANGRY_AT)
                    && mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ANGRY_AT).filter(targetId::equals).isPresent()) {
                long remaining = previousBrainAngerExpiry == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, previousBrainAngerExpiry - elapsed);
                if (previousBrainAnger == null || remaining == 0) mob.getBrain().forget(MemoryModuleType.ANGRY_AT);
                else if (remaining == Long.MAX_VALUE) mob.getBrain().remember(MemoryModuleType.ANGRY_AT, previousBrainAnger);
                else mob.getBrain().remember(MemoryModuleType.ANGRY_AT, previousBrainAnger, remaining);
            }
        }
        LivingEntity target() {
            Entity found = ((ServerWorld) mob.getWorld()).getEntity(targetId);
            return found instanceof LivingEntity living && living.isAlive() ? living : null;
        }
        void move(boolean afterBrain) {
            LivingEntity target = target();
            if (target == null) return;
            mob.getLookControl().lookAt(target, 30, 30);
            if (action == ActionType.ATTACK && mob.isInAttackRange(target)) { mob.getNavigation().stop(); return; }
            if (action == ActionType.FOLLOW && mob.squaredDistanceTo(target) <= 4 * 4) { mob.getNavigation().stop(); return; }
            if (mob.getWorld().getTime() < nextPath && (!afterBrain || mob.getNavigation().isFollowingPath())) return;
            nextPath = mob.getWorld().getTime() + 10;
            if (action == ActionType.FOLLOW || action == ActionType.ATTACK) { mob.getNavigation().startMovingTo(target, 1.1); return; }
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
