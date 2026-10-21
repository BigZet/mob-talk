package io.github.divineblock.boss;

import java.util.List;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Архангел гнева: the god's last avenger in gold and crimson armour, with a flaming sword, a halo and the trumpet of the
 * seventh angel at his belt. He walks, and leaps into short glides on his wings. Attacks: «Огненный меч» (a sweeping arc in
 * front of him, twice in the last phase), «Труба» (the trumpet calls lightning into marked circles around the foe; real
 * lightning only when block damage is allowed), «Падение с небес» (he rises above a marked circle and dives into it).
 * Ultimate «Последний суд»: pillars of fire in marked circles, then a ring of fire spreading from him, then a beam from
 * his sword; everyone struck takes «Опаление» (healing burns). Phase 2 opens his wings: he flies more; phase 3 makes him
 * faster and his halo burns brighter. */
public final class ArchangelEntity extends SacredBoss {
    private static final DustParticleEffect GOLD_DUST = new DustParticleEffect(new Vector3f(1f, 0.82f, 0.35f), 1.6f);
    private static final DustParticleEffect CRIMSON_DUST = new DustParticleEffect(new Vector3f(0.75f, 0.08f, 0.08f), 1.4f);
    /** «Опаление» from the Last Judgement lasts this long. */
    static final int SCORCH_TICKS = 20 * 20;

    /** Gliding after a leap: the fall is slowed by his wings. */
    private boolean gliding;
    private int leapCooldown = 60;

    public ArchangelEntity(EntityType<? extends ArchangelEntity> type, World world) { super(type, world, BossBar.Color.RED); }

    /** «Опаление» already works in {@link BossEffects}: nothing of its own to hook. */
    static void registerHooks() { }

    @Override public BossKind kind() { return BossKind.ARCHANGEL; }
    @Override protected String theme() { return "punishment_20"; }
    @Override protected float meleeDamage() { return 9f; }
    @Override protected double meleeReach() { return 5.5; }

    // ---- Movement: walking, leaps and glides. ----

    @Override protected void pursue(LivingEntity target) {
        super.pursue(target);
        if (--leapCooldown > 0 || !isOnGround()) return;
        float chance = phase >= 2 ? 0.25f : 0.1f;
        if (distanceTo(target) > 6 && random.nextFloat() < chance) leap(target);
        else leapCooldown = 10;
    }

    /** A beat of the wings: up and toward the foe, then a glide. */
    private void leap(LivingEntity target) {
        Vec3d to = target.getPos().subtract(getPos()).multiply(1, 0, 1);
        if (to.lengthSquared() < 1e-3) return;
        to = to.normalize().multiply(Math.min(1.1, 0.12 * to.length()));
        setVelocity(to.x, phase >= 2 ? 0.95 : 0.8, to.z);
        velocityModified = true;
        gliding = true;
        leapCooldown = phase >= 3 ? 50 : phase == 2 ? 70 : 110;
        sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 2f, 1.2f);
        particles(ParticleTypes.CLOUD, getX(), getY() + 0.2, getZ(), 16, 1, 0.1, 1, 0.05);
    }

    @Override protected void mobTick() {
        super.mobTick();
        if (!isCasting() && hasNoGravity()) setNoGravity(false);
        if (gliding) {
            if (isOnGround() && getVelocity().y <= 0) gliding = false;
            else if (getVelocity().y < -0.12) setVelocity(getVelocity().x, -0.12, getVelocity().z);
            if (age % 4 == 0) particles(ParticleTypes.END_ROD, getX(), getY() + getHeight() * 0.6, getZ(), 2, 0.9, 0.3, 0.9, 0.01);
        }
        // The halo of the last phase burns brighter.
        if (phase >= 3 && age % 3 == 0) particles(ParticleTypes.END_ROD, getX(), getY() + getHeight() + 0.1, getZ(), 2, 0.35, 0.05, 0.35, 0.005);
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        double distance = distanceTo(target);
        int roll = random.nextInt(3);
        int quick = phase >= 3 ? 15 : 0;
        if (distance < 6 && roll != 2) { sword(target, phase >= 3); return 60 - quick; }
        if (roll == 0 || distance > 14) { trumpet(target); return 90 - quick; }
        dive(target);
        return 95 - quick;
    }

    /** «Огненный меч»: the blade flares, the arc in front of him is marked, then he sweeps it. */
    private void sword(LivingEntity target, boolean twice) {
        int warn = phase >= 3 ? 16 : 20;
        cast("Огненный меч", warn, true, left -> {
            if (left % 2 == 0) arcMarks(ParticleTypes.FLAME, 5.5, 2);
            if (left == warn) sound(SoundEvents.ITEM_FIRECHARGE_USE, 1.5f, 0.7f);
        }, () -> {
            sweep(5.5, 10f);
            if (!twice) return;
            // The return stroke: marked again in blue fire before it comes.
            for (int i = 0; i < 4; i++) hazards.later(1 + i * 4, () -> arcMarks(ParticleTypes.SOUL_FIRE_FLAME, 5.5, 3));
            hazards.later(18, () -> { if (target.isAlive()) getLookControl().lookAt(target, 90, 90); sweep(5.5, 8f); });
        });
    }

    /** Hurts and sets alight everyone in the arc of 120 degrees in front of him. */
    private void sweep(double reach, float damage) {
        swingHand(Hand.MAIN_HAND);
        sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.6f);
        sound(SoundEvents.ENTITY_BLAZE_SHOOT, 1.4f, 0.6f);
        arcMarks(ParticleTypes.SWEEP_ATTACK, reach, 1);
        arcMarks(ParticleTypes.FLAME, reach, 4);
        Vec3d facing = facing();
        for (LivingEntity victim : victims(getPos(), reach + 1)) {
            Vec3d to = victim.getPos().subtract(getPos()).multiply(1, 0, 1);
            if (to.lengthSquared() > 1e-3 && to.normalize().dotProduct(facing) < 0.5) continue;
            if (hurt(victim, damage)) { victim.setOnFireFor(3); push(victim, getPos(), 0.8, 0.3); }
        }
    }

    /** «Труба»: he lifts the trumpet and blows; circles appear around the foe, and lightning falls into them. */
    private void trumpet(LivingEntity target) {
        int count = phase >= 3 ? 6 : phase == 2 ? 4 : 3;
        cast("Труба", 24, true, left -> {
            Vec3d horn = hornPos();
            particles(GOLD_DUST, horn.x, horn.y, horn.z, 2, 0.15, 0.15, 0.15, 0);
            if (left == 24) sound(SoundEvents.EVENT_RAID_HORN, 3f, 1.3f);
        }, () -> {
            sound(SoundEvents.EVENT_RAID_HORN, 4f, 0.9f);
            Vec3d horn = hornPos();
            particles(ParticleTypes.SONIC_BOOM, horn.x, horn.y, horn.z, 1, 0, 0, 0, 0);
            for (int i = 0; i < count; i++) {
                Vec3d at = i == 0 ? target.getPos() : target.getPos().add((random.nextDouble() - 0.5) * 10, 0, (random.nextDouble() - 0.5) * 10);
                hazards.zone(ground(at), 2.2, 30 + i * 6, ParticleTypes.ELECTRIC_SPARK, this::lightning, victim -> {
                    if (!mayBreakBlocks() && hurt(victim, 8f)) victim.setOnFireFor(2);
                });
            }
        });
    }

    /** Lightning at a point: a real bolt when block damage is allowed (it hurts and burns by itself), otherwise a harmless
     * one whose damage the zone deals, capped. */
    private void lightning(Vec3d at) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
        if (bolt == null) return;
        bolt.refreshPositionAfterTeleport(at);
        bolt.setCosmetic(!mayBreakBlocks());
        world.spawnEntity(bolt);
        particles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.5, at.z, 30, 1, 0.5, 1, 0.2);
    }

    /** He is never struck by his own lightning. */
    @Override public void onStruckByLightning(ServerWorld world, LightningEntity lightning) { }

    /** «Падение с небес»: a circle under the foe; he rises above it, hangs a moment and dives. */
    private void dive(LivingEntity target) {
        Vec3d spot = ground(target.getPos());
        int warn = phase >= 3 ? 26 : 32;
        hazards.zone(spot, 3.2, warn + 5, ParticleTypes.FLAME, at -> {
            particles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 1.5, 0.2, 1.5, 0);
            particles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, 40, 2, 0.2, 2, 0.08);
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.5f, 0.8f);
        }, victim -> { if (hurt(victim, 11f)) push(victim, spot, 1.2, 0.5); });
        cast("Падение с небес", warn, true, left -> {
            setNoGravity(true);
            setVelocity(spot.add(0, 7, 0).subtract(getPos()).multiply(0.15));
            velocityModified = true;
            if (left == warn) { sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 2.5f, 0.8f); sound(SoundEvents.ENTITY_PHANTOM_FLAP, 2f, 0.6f); }
            if (left % 3 == 0) particles(ParticleTypes.END_ROD, getX(), getY() + getHeight() * 0.4, getZ(), 3, 1.6, 1.2, 1.6, 0.02);
        }, () -> {
            setNoGravity(false);
            gliding = false;
            setVelocity(0, -2.4, 0);
            velocityModified = true;
            for (double y = 0; y < 7; y += 0.5) particles(ParticleTypes.FLAME, spot.x, spot.y + y, spot.z, 2, 0.2, 0.2, 0.2, 0.01);
            sound(SoundEvents.ITEM_TRIDENT_THUNDER, 1.5f, 1.2f);
        });
    }

    // ---- The Last Judgement. ----

    @Override protected void ultimate(LivingEntity target) {
        Vec3d center = getPos();
        castUltimate(left -> {
            // Gold gathers over his halo and the ground smoulders where the pillars will stand.
            particles(GOLD_DUST, getX(), getY() + getHeight() + 0.4, getZ(), 6, 0.8, 0.3, 0.8, 0);
            for (int i = 0; i < 3; i++) {
                double angle = random.nextDouble() * Math.PI * 2, r = 3 + random.nextDouble() * 9;
                particles(ParticleTypes.SMALL_FLAME, center.x + Math.cos(angle) * r, center.y + 0.1, center.z + Math.sin(angle) * r, 1, 0, 0.02, 0, 0);
            }
            if (left % 20 == 0) sound(SoundEvents.EVENT_RAID_HORN, 4f, 0.6f + (60 - left) / 120f);
        }, () -> lastJudgement(target, center));
    }

    /** Three parts, each with its own warning: pillars of fire in marked circles, a ring of fire, the sword's beam. */
    private void lastJudgement(LivingEntity target, Vec3d center) {
        sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.6f);
        for (var player : playersNear(40)) player.sendMessage(Text.literal("Последний суд: огонь, кольцо, меч").formatted(Formatting.GOLD), true);
        List<LivingEntity> judged = victims(center, 24);
        int pillars = 6 + phase * 2;
        for (int i = 0; i < pillars; i++) {
            Vec3d at;
            if (i < judged.size()) at = judged.get(i).getPos();
            else if (!judged.isEmpty() && i < judged.size() * 3) at = judged.get(i % judged.size()).getPos().add((random.nextDouble() - 0.5) * 8, 0, (random.nextDouble() - 0.5) * 8);
            else { double angle = random.nextDouble() * Math.PI * 2, r = 3 + random.nextDouble() * 10; at = center.add(Math.cos(angle) * r, 0, Math.sin(angle) * r); }
            hazards.zone(ground(at), 2.0, 25 + i * 3, ParticleTypes.FLAME, this::firePillar, this::judge);
        }
        // The ring is foretold by gold on the ground around him, then spreads; a jump clears it.
        hazards.later(55, () -> { particles(GOLD_DUST, getX(), getY() + 0.3, getZ(), 40, 2, 0.1, 2, 0); sound(SoundEvents.BLOCK_BEACON_DEACTIVATE, 3f, 0.6f); });
        hazards.later(72, () -> {
            sound(SoundEvents.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
            hazards.ring(getPos(), 0.35, 20, 1.4, ParticleTypes.FLAME, this::judge);
        });
        // The sword's beam toward the foe: a dotted line first, then fire.
        hazards.later(110, () -> {
            LivingEntity aim = getTarget() != null && getTarget().isAlive() ? getTarget() : target;
            Vec3d from = getPos().add(0, 1.6, 0);
            Vec3d dir = aim.getPos().add(0, 1, 0).subtract(from);
            if (dir.lengthSquared() < 1e-3) dir = facing();
            Vec3d to = from.add(dir.normalize().multiply(24));
            swingHand(Hand.MAIN_HAND);
            sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 3f, 0.6f);
            hazards.beam(from, to, 22, 30, 1.0, ParticleTypes.END_ROD, ParticleTypes.FLAME, this::judge);
            hazards.later(22, () -> sound(SoundEvents.ENTITY_BLAZE_SHOOT, 3f, 0.4f));
        });
    }

    private void firePillar(Vec3d at) {
        for (double y = 0; y < 8; y += 0.5) particles(ParticleTypes.FLAME, at.x, at.y + y, at.z, 4, 0.6, 0.2, 0.6, 0.02);
        particles(ParticleTypes.LAVA, at.x, at.y + 0.5, at.z, 6, 0.8, 0.2, 0.8, 0);
        sound(SoundEvents.ENTITY_BLAZE_SHOOT, 2f, 0.7f);
    }

    /** A hit of the Last Judgement: fire and «Опаление». */
    private void judge(LivingEntity victim) {
        if (!hurt(victim, 9f)) return;
        victim.setOnFireFor(4);
        if (!victim.hasStatusEffect(BossEffects.SCORCH)) victim.sendMessage(Text.literal("Опаление: лечение жжёт").formatted(Formatting.GOLD));
        BossEffects.afflict(victim, BossEffects.SCORCH, SCORCH_TICKS);
        particles(CRIMSON_DUST, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 10, 0.3, 0.5, 0.3, 0);
    }

    // ---- Phases. ----

    @Override protected void onPhase(int phase) {
        if (phase == 2) {
            sound(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 1.4f);
            particles(ParticleTypes.END_ROD, getX(), getY() + getHeight() * 0.6, getZ(), 60, 2.5, 1, 2.5, 0.05);
            leapCooldown = 0;
        }
        if (phase == 3) {
            sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 3f, 0.6f);
            particles(GOLD_DUST, getX(), getY() + getHeight(), getZ(), 60, 1, 0.3, 1, 0);
            addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
        }
    }

    @Override protected void onDefeated() {
        for (LivingEntity victim : victims(getPos(), 32)) victim.removeStatusEffect(BossEffects.SCORCH);
        particles(ParticleTypes.END_ROD, getX(), getY() + getHeight() * 0.5, getZ(), 120, 2.5, 3, 2.5, 0.1);
    }

    // ---- Geometry. ----

    private Vec3d facing() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** Draws the arc of 120 degrees in front of him out to {@code reach}, {@code rows} rows deep. */
    private void arcMarks(ParticleEffect effect, double reach, int rows) {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        for (int row = 0; row < rows; row++) {
            double r = reach * (row + 1) / rows;
            for (int i = -6; i <= 6; i++) {
                double a = yaw + i * (Math.PI / 3) / 6;
                particles(effect, getX() - Math.sin(a) * r, getY() + 1.1, getZ() + Math.cos(a) * r, 1, 0, 0, 0, 0);
            }
        }
    }

    /** The trumpet lifted to his lips (on his left). */
    private Vec3d hornPos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(getX() - Math.sin(yaw) * 0.7 + Math.cos(yaw) * 0.5, getY() + getHeight() * 0.78, getZ() + Math.cos(yaw) * 0.7 + Math.sin(yaw) * 0.5);
    }

    /** The ground under a point (down to 8 blocks), so circles lie on the floor even when the foe jumps. */
    private Vec3d ground(Vec3d at) {
        BlockPos pos = BlockPos.ofFloored(at);
        for (int i = 0; i < 8; i++) {
            BlockPos below = pos.down();
            if (!getWorld().getBlockState(below).getCollisionShape(getWorld(), below).isEmpty()) break;
            pos = below;
        }
        return new Vec3d(at.x, pos.getY(), at.z);
    }
}
