package io.github.divineblock.boss;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Зверь из бездны (Revelation 13): seven heads and ten crowned horns, a leopard's body, a bear's feet, lions' mouths and
 * names of blasphemy on the heads; one head wounded to death. Every seventh of its health one head dies and droops; in the
 * last phase the deadly wound is healed (the wounded head rises, and the beast heals a little, once).
 * Attacks: «Семь пастей» (bites in a marked cone, more heads bite harder), «Медвежья лапа» (a claw blow on a marked circle
 * in front of it), «Пасть льва» (fire breath along marked lines), «Рёв зверя» (a roar that weakens and slows).
 * Ultimate «Число зверя»: three marks of six; then six orbs, six marked circles and six beams, one after another, each with
 * its warning. Who is struck takes «Начертание» for two minutes: no buying or selling (the rule lives in {@link BossEffects}). */
public final class BeastEntity extends SacredBoss {
    /** Heads that have died, 0..6, for the client to droop them. */
    private static final TrackedData<Byte> HEADS_LOST = DataTracker.registerData(BeastEntity.class, TrackedDataHandlerRegistry.BYTE);
    /** Whether the deadly wound has healed (phase 3). */
    private static final TrackedData<Boolean> HEALED = DataTracker.registerData(BeastEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    public static final int HEADS = 7;
    static final int MARK_TICKS = 20 * 120;
    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.02f, 0.04f), 1.8f);
    private static final DustParticleEffect SIX = new DustParticleEffect(new Vector3f(0.9f, 0.1f, 0.1f), 2.2f);

    public BeastEntity(EntityType<? extends BeastEntity> type, World world) { super(type, world, BossBar.Color.RED); }

    /** «Начертание» already forbids trading in {@link BossEffects}: nothing more to hook. */
    static void registerHooks() { }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(HEADS_LOST, (byte) 0);
        builder.add(HEALED, false);
    }
    /** On the client too: how many heads have died. */
    public int headsLost() { return dataTracker.get(HEADS_LOST); }
    /** On the client too: whether the wounded head has healed. */
    public boolean woundHealed() { return dataTracker.get(HEALED); }
    private int headsAlive() { return HEADS - headsLost() - (woundHealed() ? 0 : 1); }

    @Override public BossKind kind() { return BossKind.BEAST; }
    @Override protected String theme() { return "punishment_56"; }
    @Override protected float meleeDamage() { return 9f; }
    @Override protected double meleeReach() { return 4.6; }

    @Override protected void mobTick() {
        super.mobTick();
        // A head dies for each seventh of health lost; never more than six, and none come back.
        int lost = Math.min(HEADS - 1, (int) ((1f - getHealth() / getMaxHealth()) * HEADS));
        if (lost > headsLost()) {
            dataTracker.set(HEADS_LOST, (byte) lost);
            sound(SoundEvents.ENTITY_RAVAGER_HURT, 2f, 0.5f);
            particles(BLOOD, getX(), getY() + 3.4, getZ(), 30, 1.2, 0.4, 1.2, 0);
            for (var player : playersNear(32)) player.sendMessage(Text.literal("Одна из голов зверя поникла").formatted(Formatting.DARK_RED), true);
        }
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        double distance = distanceTo(target);
        int roll = random.nextInt(4);
        int quick = phase >= 3 ? 12 : phase == 2 ? 6 : 0;
        if (distance < 6 && roll < 2) { if (roll == 0) bite(); else claw(); return 55 - quick; }
        if (roll == 3 && distance < 12) { roar(); return 80 - quick; }
        breath(target);
        return 85 - quick;
    }

    /** «Семь пастей»: the cone in front of it is marked, then every living head bites. */
    private void bite() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        cast("Семь пастей", 20, true, left -> {
            if (left % 2 == 0) cone(ParticleTypes.CRIMSON_SPORE, yaw, 6.5, 0.6);
            if (left == 20) sound(SoundEvents.ENTITY_HOGLIN_ANGRY, 2f, 0.5f);
        }, () -> {
            sound(SoundEvents.ENTITY_RAVAGER_ATTACK, 2f, 0.6f);
            cone(ParticleTypes.CRIT, yaw, 6.5, 0.6);
            for (LivingEntity victim : victims(getPos(), 7.5))
                if (inCone(victim, yaw, 6.5, 0.6) && hurt(victim, 4f + headsAlive() * 1.2f)) {
                    push(victim, getPos(), 0.6, 0.2);
                    if (phase >= 3) markOf(victim);
                }
        });
    }

    /** «Медвежья лапа»: a circle in front of it, a raised paw, a crushing blow. */
    private void claw() {
        Vec3d at = getPos().add(facing().multiply(3));
        cast("Медвежья лапа", 18, true, left -> { if (left == 18) sound(SoundEvents.ENTITY_POLAR_BEAR_WARNING, 2f, 0.5f); }, () -> { });
        hazards.zone(at, 2.6, 18, ParticleTypes.CRIMSON_SPORE, center -> {
            particles(ParticleTypes.SWEEP_ATTACK, center.x, center.y + 1, center.z, 4, 1.2, 0.3, 1.2, 0);
            sound(SoundEvents.ENTITY_RAVAGER_ATTACK, 2f, 0.8f);
        }, victim -> { if (hurt(victim, 11f)) push(victim, getPos(), 1.3, 0.4); });
    }

    /** «Пасть льва»: lines of fire from the lions' mouths toward the foe, shown before they burn. */
    private void breath(LivingEntity target) {
        Vec3d mouth = getPos().add(facing().multiply(2.2)).add(0, 3, 0);
        Vec3d aim = target.getPos().add(0, 0.8, 0).subtract(mouth);
        if (aim.lengthSquared() < 1e-3) aim = facing();
        Vec3d dir = aim.normalize();
        int lines = phase >= 2 ? 3 : 1;
        cast("Пасть льва", 22, true, left -> {
            particles(ParticleTypes.FLAME, mouth.x, mouth.y, mouth.z, 3, 0.8, 0.3, 0.8, 0.01);
            if (left == 22) sound(SoundEvents.ENTITY_BLAZE_AMBIENT, 2f, 0.4f);
        }, () -> sound(SoundEvents.ENTITY_BLAZE_SHOOT, 2.5f, 0.5f));
        for (int i = 0; i < lines; i++) {
            double spread = (i - (lines - 1) / 2.0) * 0.35;
            Vec3d d = new Vec3d(dir.x * Math.cos(spread) - dir.z * Math.sin(spread), dir.y, dir.x * Math.sin(spread) + dir.z * Math.cos(spread));
            hazards.beam(mouth, mouth.add(d.multiply(14)), 22, 24, 0.9, ParticleTypes.SMOKE, ParticleTypes.FLAME, victim -> {
                if (hurt(victim, 5f)) victim.setOnFireFor(3);
            });
        }
    }

    /** «Рёв зверя»: every head lifts; then a roar that weakens and slows all near it. */
    private void roar() {
        cast("Рёв зверя", 22, true, left -> {
            particles(ParticleTypes.SMOKE, getX(), getY() + 3.5, getZ(), 4, 1.2, 0.5, 1.2, 0.02);
            if (left == 22) sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.2f, 0.7f);
        }, () -> {
            sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.4f);
            sound(SoundEvents.ENTITY_WARDEN_ROAR, 1.5f, 0.8f);
            particles(ParticleTypes.SONIC_BOOM, getX(), getY() + 2, getZ(), 1, 0, 0, 0, 0);
            for (LivingEntity victim : victims(getPos(), 11)) {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 1));
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 1));
                push(victim, getPos(), 0.9, 0.2);
                hurt(victim, 3f);
            }
        });
    }

    // ---- The number of the beast. ----

    @Override protected void ultimate(LivingEntity target) {
        Vec3d center = getPos();
        castUltimate(left -> {
            // Three sixes burn in the air above it: six marks in each of three rings.
            for (int ring = 0; ring < 3; ring++) {
                if (left > 60 - ring * 20) continue;
                for (int i = 0; i < 6; i++) {
                    double angle = Math.PI * 2 * i / 6 + age * 0.03 * (ring % 2 == 0 ? 1 : -1);
                    double r = 2.5 + ring * 2;
                    particles(SIX, center.x + Math.cos(angle) * r, center.y + 5 + ring * 0.8, center.z + Math.sin(angle) * r, 1, 0, 0, 0, 0);
                }
            }
            if (left % 20 == 0) sound(SoundEvents.ENTITY_WITHER_AMBIENT, 2f, 0.4f);
        }, () -> numberOfBeast(target));
    }

    /** Six orbs, then six circles, then six beams; each part announced before it comes. */
    private void numberOfBeast(LivingEntity target) {
        announce("Шесть: пламя из пастей");
        sound(SoundEvents.ENTITY_WITHER_SHOOT, 2f, 0.5f);
        Vec3d mouths = getPos().add(0, 3.6, 0);
        for (int i = 0; i < 6; i++) {
            LivingEntity aimAt = aim(target);
            double angle = Math.PI * 2 * i / 6;
            Vec3d from = mouths.add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
            Vec3d dir = aimAt.getEyePos().subtract(from).normalize();
            int delay = i * 4;
            hazards.later(1 + delay, () -> {
                particles(ParticleTypes.FLAME, from.x, from.y, from.z, 10, 0.2, 0.2, 0.2, 0.05);
                hazards.orb(from, dir.multiply(0.4), 80, 0.5, ParticleTypes.FLAME, this::mark);
            });
        }
        hazards.later(50, () -> {
            announce("Шесть: круги под ногами");
            sound(SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 2f, 0.5f);
            LivingEntity aimAt = aim(target);
            for (int i = 0; i < 6; i++) {
                double angle = Math.PI * 2 * i / 6 + random.nextDouble() * 0.4;
                double r = i == 0 ? 0 : 3.5;
                Vec3d at = aimAt.getPos().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
                hazards.zone(at, 2.0, 30 + i * 4, SIX, center -> {
                    particles(ParticleTypes.LAVA, center.x, center.y + 0.3, center.z, 8, 1, 0.2, 1, 0);
                    particles(ParticleTypes.FLAME, center.x, center.y + 1, center.z, 20, 0.8, 1, 0.8, 0.02);
                    sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1f, 0.7f);
                }, this::mark);
            }
        });
        hazards.later(110, () -> {
            announce("Шесть: лучи от зверя");
            sound(SoundEvents.ENTITY_WITHER_SPAWN, 1f, 0.7f);
            Vec3d from = getPos().add(0, 1.2, 0);
            LivingEntity aimAt = aim(target);
            double start = Math.atan2(aimAt.getZ() - from.z, aimAt.getX() - from.x) + Math.PI / 6;
            for (int i = 0; i < 6; i++) {
                double angle = start + Math.PI * 2 * i / 6;
                Vec3d to = from.add(Math.cos(angle) * 18, 0, Math.sin(angle) * 18);
                hazards.beam(from, to, 28, 24, 1.0, SIX, ParticleTypes.FLAME, this::mark);
            }
        });
    }

    /** A hit of the number: harm and «Начертание». */
    private void mark(LivingEntity victim) {
        if (!hurt(victim, 7f)) return;
        markOf(victim);
    }

    private void markOf(LivingEntity victim) {
        if (!victim.hasStatusEffect(BossEffects.MARK_OF_BEAST))
            victim.sendMessage(Text.literal("Начертание зверя: ни купить, ни продать").formatted(Formatting.DARK_RED));
        BossEffects.afflict(victim, BossEffects.MARK_OF_BEAST, MARK_TICKS);
        particles(BLOOD, victim.getX(), victim.getEyeY() + 0.2, victim.getZ(), 6, 0.15, 0.1, 0.15, 0);
    }

    private LivingEntity aim(LivingEntity fallback) {
        LivingEntity now = getTarget();
        return now != null && now.isAlive() ? now : fallback;
    }

    private void announce(String line) {
        for (var player : playersNear(40)) player.sendMessage(Text.literal("⚠ " + line).formatted(Formatting.RED), true);
    }

    // ---- Phases. ----

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.5f);
        if (phase == 3 && !woundHealed()) {
            // «И смертельная рана его исцелела» (Rev 13:3).
            dataTracker.set(HEALED, true);
            heal(getMaxHealth() * 0.06f);
            sound(SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE, 2f, 0.5f);
            particles(BLOOD, getX(), getY() + 3.6, getZ(), 40, 1, 0.5, 1, 0);
            particles(ParticleTypes.TOTEM_OF_UNDYING, getX(), getY() + 3, getZ(), 40, 1.2, 0.8, 1.2, 0.3);
            for (var player : playersNear(48)) player.sendMessage(Text.literal("Смертельная рана зверя исцелела").formatted(Formatting.DARK_RED));
            addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
        }
    }

    @Override protected void onDefeated() {
        for (LivingEntity victim : victims(getPos(), 32)) victim.removeStatusEffect(BossEffects.MARK_OF_BEAST);
    }

    @Override public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putByte("BeastHeadsLost", (byte) headsLost());
        nbt.putBoolean("BeastHealed", woundHealed());
    }
    @Override public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(HEADS_LOST, (byte) Math.max(0, Math.min(HEADS - 1, nbt.getByte("BeastHeadsLost"))));
        dataTracker.set(HEALED, nbt.getBoolean("BeastHealed"));
    }

    // ---- Geometry. ----

    private Vec3d facing() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** Draws a cone in front of it: {@code half} is the half-angle in radians. */
    private void cone(net.minecraft.particle.ParticleEffect effect, float yaw, double reach, double half) {
        for (double r = 1.5; r <= reach; r += 1.25)
            for (int i = -4; i <= 4; i++) {
                double a = yaw + half * i / 4;
                particles(effect, getX() - Math.sin(a) * r, getY() + 0.3, getZ() + Math.cos(a) * r, 1, 0, 0, 0, 0);
            }
    }

    private boolean inCone(LivingEntity victim, float yaw, double reach, double half) {
        Vec3d to = victim.getPos().subtract(getPos()).multiply(1, 0, 1);
        double distance = to.length();
        if (distance > reach + getWidth() / 2) return false;
        if (distance < getWidth() / 2 + 0.5) return true;
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        return to.normalize().dotProduct(forward) >= Math.cos(half);
    }
}
