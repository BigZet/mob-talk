package io.github.divineblock.boss;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import io.github.divineblock.boss.ai.SeraphMind;

/** Шестикрылый серафим (Isaiah 6): a burning face in six wings full of eyes. It flies, circling its foe four to six blocks up,
 * throws volleys of burning feathers, sends a ring of fire over the ground beneath it, and now and then comes down for a few
 * seconds («Нисхождение»), the only time a sword can reach it. Ultimate «Пламень с небес»: marked circles fall around the
 * player one after another for six seconds and strike as columns of fire; whoever is caught burns and takes «Опаление»
 * (healing burns instead). Phase 2 throws more feathers, phase 3 drops the columns faster. */
public final class SeraphEntity extends SacredBoss {
    private static final TrackedData<Boolean> DESCENDING = DataTracker.registerData(SeraphEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    /** How high it hovers over the ground, in blocks. */
    static final double HOVER = 3.5;
    /** Where the face of light is, as a share of the height (feathers fly from it, fire gathers round it). */
    static final double FACE = 0.57;
    static final int DESCENT_TICKS = 70;

    private int descendLeft;
    private Vec3d landing;
    private double orbit;
    /** The trained mind (ml/seraph_dodge/v2) and its decision for this tick (null while it fights the old way). */
    private final SeraphMind mind = new SeraphMind();
    private SeraphMind.Move move;
    private double featherSide, featherUp;
    private boolean featherLead;

    public SeraphEntity(EntityType<? extends SeraphEntity> type, World world) {
        super(type, world, BossBar.Color.RED);
        setNoGravity(true);
    }

    /** The scorch's rules live in {@link BossEffects}: nothing of its own to hook. */
    static void registerHooks() { }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(DESCENDING, false);
    }
    /** On the client too: whether it is down on the ground now (wings open, face bared). */
    public boolean isDescending() { return dataTracker.get(DESCENDING); }

    @Override public BossKind kind() { return BossKind.SERAPH; }
    @Override protected String theme() { return "punishment_59"; }
    @Override protected float meleeDamage() { return 7f; }
    @Override protected double meleeReach() { return 4.0; }
    @Override public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource source) { return false; }
    @Override public boolean hasNoGravity() { return true; }
    /** Water hurts the flame: a splash bottle of water, rain and water itself reach it (and open its weak point). */
    @Override public boolean hurtByWater() { return true; }
    @Override public boolean damage(DamageSource source, float amount) {
        if (!getWorld().isClient && source.isOf(net.minecraft.entity.damage.DamageTypes.DROWN)) {
            // Rain or water: it opens the flame, but harms nothing by itself.
            if (weakReady()) expose(EXPOSED_TICKS, "Вода погасила пламень — бей!");
            return false;
        }
        if (!getWorld().isClient && source.getSource() instanceof net.minecraft.entity.projectile.thrown.SnowballEntity) amount = Math.max(amount, 3f);
        return super.damage(source, amount);
    }

    // ---- Flight. ----

    @Override protected void mobTick() {
        think();
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld) || isDead()) return;
        if (descendLeft > 0 && --descendLeft == 0) {
            dataTracker.set(DESCENDING, false);
            landing = null;
            sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 2f, 1.2f);
        }
        hold();
        if (age % 3 == 0) particles(ParticleTypes.FLAME, getX(), getY() + getHeight() * FACE, getZ(), 3, 1.2, 1.4, 1.2, 0.01);
        if (age % 12 == 0) particles(ParticleTypes.SMALL_FLAME, getX(), getY() + 0.2, getZ(), 3, 0.3, 0.1, 0.3, 0.02);
    }

    /** The mind's tick, before the fight loop: it remembers every tick, decides, sweeps arrows back, starts the feathers. */
    private void think() {
        move = null;
        LivingEntity target = getTarget();
        if (!(getWorld() instanceof ServerWorld) || isDead() || target == null || descendLeft > 0 || !config().seraphDodge) return;
        double ground = groundBelow(getX(), getY() + 1, getZ(), 24);
        move = mind.tick(this, target, ground, isCasting() ? castProgress() : -1, config().seraphDodgeSkill / 100.0);
        if (move == null) return;
        if (move.startDeflect()) sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 2f, 0.6f);
        if (mind.closingWings())
            particles(ParticleTypes.FLAME, getX(), getY() + getHeight() * FACE, getZ(), 10, getWidth() * 0.5, getHeight() * 0.25, getWidth() * 0.5, 0.0);
        if (mind.deflecting()) {
            // «Огненный взмах»: arrows about to strike fly back at whoever shot them.
            var box = getBoundingBox();
            for (var arrow : getWorld().getEntitiesByClass(net.minecraft.entity.projectile.PersistentProjectileEntity.class, box.expand(6),
                    a -> a.isAlive() && a.getOwner() != this && SeraphMind.strikesThisTick(a, box))) {
                SeraphMind.throwBack(this, arrow);
                particles(ParticleTypes.LAVA, arrow.getX(), arrow.getY(), arrow.getZ(), 4, 0.2, 0.2, 0.2, 0);
                sound(SoundEvents.ITEM_SHIELD_BLOCK, 2f, 0.7f);
            }
        }
        if (move.startFeathers() && !isCasting()) {
            featherSide = move.aimSide(); featherUp = move.aimUp(); featherLead = true;
            feathers(target);
        }
    }

    /** Keeps the height: hovering over the ground below (under the vault, if any), or down on it while descending. */
    private void hold() {
        double ground = groundBelow(getX(), getY() + 1, getZ(), 24);
        double lift = move != null && descendLeft == 0 ? move.lift() : 0;
        double wanted = descendLeft > 0 ? ground + 0.05
                : Math.min(ground + HOVER + Math.sin(age * 0.05) * 0.6 + lift * SeraphMind.LIFT, ceilingAbove() - getHeight() - 0.3);
        wanted = Math.max(wanted, ground + 0.05);
        Vec3d v = getVelocity();
        double dy = MathHelper.clamp((wanted - getY()) * 0.08, -0.35, 0.3);
        setVelocity(v.x, v.y * 0.6 + dy + lift * 0.06, v.z);
        fallDistance = 0;
    }

    /** Circles the foe at a distance, or settles on the landing spot while descending. */
    @Override protected void pursue(LivingEntity target) {
        Vec3d goal;
        if (descendLeft > 0 && landing != null) goal = landing;
        else {
            orbit += phase >= 3 ? 0.03 : 0.02;
            double radius = move != null ? SeraphMind.ORBIT : 7;
            goal = new Vec3d(target.getX() + Math.cos(orbit) * radius, getY(), target.getZ() + Math.sin(orbit) * radius);
        }
        Vec3d flat = new Vec3d(goal.x - getX(), 0, goal.z - getZ());
        Vec3d v = getVelocity();
        double speed = descendLeft > 0 ? 0.15 : 0.32;
        Vec3d push = flat.lengthSquared() < 0.25 ? Vec3d.ZERO : flat.normalize().multiply(0.05);
        Vec3d next = new Vec3d(v.x * 0.9 + push.x, v.y, v.z * 0.9 + push.z);
        if (move != null) {
            // «Рывок крыльями»: the mind steers a little all the time and now and then throws itself aside.
            next = next.add(move.steerX() * SeraphMind.STEER_ACC, 0, move.steerZ() * SeraphMind.STEER_ACC);
            speed += SeraphMind.STEER_CAP * move.steerAmount();
            if (move.dashing()) {
                next = new Vec3d(move.dashDir().x * move.dashSpeed(), v.y, move.dashDir().z * move.dashSpeed());
                speed = move.dashSpeed();
                if (age % 2 == 0) sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 1.6f, 1.5f);
                particles(ParticleTypes.FLAME, getX(), getY() + getHeight() * 0.5, getZ(), 12, getWidth() * 0.4, getHeight() * 0.3, getWidth() * 0.4, 0.0);
                particles(ParticleTypes.CLOUD, getX(), getY() + getHeight() * 0.5, getZ(), 4, getWidth() * 0.3, getHeight() * 0.2, getWidth() * 0.3, 0.02);
            }
        }
        double horizontal = Math.hypot(next.x, next.z);
        if (horizontal > speed) next = new Vec3d(next.x * speed / horizontal, next.y, next.z * speed / horizontal);
        setVelocity(next);
        float yaw = (float) (MathHelper.atan2(target.getZ() - getZ(), target.getX() - getX()) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        setYaw(yaw);
        bodyYaw = yaw;
        headYaw = yaw;
    }

    /** The top of the solid ground below a point (searching {@code depth} blocks down), or far below when there is none. */
    private double groundBelow(double x, double y, double z, int depth) {
        World world = getWorld();
        BlockPos.Mutable pos = BlockPos.ofFloored(x, y, z).mutableCopy();
        for (int i = 0; i < depth; i++, pos.move(Direction.DOWN)) {
            BlockState state = world.getBlockState(pos);
            var shape = state.getCollisionShape(world, pos);
            if (!shape.isEmpty()) return pos.getY() + shape.getMax(Direction.Axis.Y);
            if (!state.getFluidState().isEmpty()) return pos.getY() + 0.9;
        }
        return y - depth;
    }

    /** The lowest solid block above the head (a few blocks up), so it flies under a church vault. */
    private double ceilingAbove() {
        World world = getWorld();
        BlockPos.Mutable pos = BlockPos.ofFloored(getX(), getY() + getHeight(), getZ()).mutableCopy();
        for (int i = 0; i < 8; i++, pos.move(Direction.UP))
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) return pos.getY();
        return getY() + getHeight() + 8;
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        if (descendLeft > 0) { fireRing(); return 45; }
        if (move != null) {
            // The mind looses the feathers itself; the timer brings only the ring and the descent, more rarely.
            if (random.nextInt(2) == 0 && age > 200) { descend(target); return DESCENT_TICKS + 60; }
            fireRing();
            return 140;
        }
        int roll = random.nextInt(4);
        if (roll == 0 && age > 200) { descend(target); return DESCENT_TICKS + 40; }
        if (roll == 1) { fireRing(); return 70; }
        feathers(target);
        return phase >= 2 ? 55 : 65;
    }

    /** «Огненные перья»: the wings flare, then a fan of burning feathers flies at the foe. */
    private void feathers(LivingEntity target) {
        int count = phase >= 2 ? 8 : 5;
        cast("Огненные перья", 20, false, left -> {
            particles(ParticleTypes.FLAME, getX(), getY() + getHeight() * FACE, getZ(), 8, 2.4, 1.2, 2.4, 0.02);
            if (left == 20) sound(SoundEvents.ENTITY_BLAZE_AMBIENT, 2f, 0.7f);
        }, () -> {
            Vec3d from = new Vec3d(getX(), getY() + getHeight() * FACE, getZ());
            Vec3d at = featherLead ? SeraphMind.featherAim(this, target, featherSide, featherUp) : target.getEyePos();
            if (featherLead) mind.fired();
            featherLead = false;
            Vec3d aim = at.subtract(from).normalize();
            for (int i = 0; i < count; i++) {
                double spread = (i - (count - 1) / 2.0) * 0.14;
                Vec3d dir = new Vec3d(aim.x * Math.cos(spread) - aim.z * Math.sin(spread), aim.y + (random.nextDouble() - 0.5) * 0.08,
                        aim.x * Math.sin(spread) + aim.z * Math.cos(spread)).normalize();
                hazards.orb(from, dir.multiply(0.5), 60, 0.35, ParticleTypes.FLAME, victim -> {
                    if (hurt(victim, 5f)) victim.setOnFireFor(3);
                    particles(ParticleTypes.LAVA, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 4, 0.3, 0.3, 0.3, 0);
                });
            }
            sound(SoundEvents.ENTITY_BLAZE_SHOOT, 2f, 0.8f);
        });
    }

    /** «Огненный круг»: fire gathers on the ground below, then a burning ring spreads from it (a jump clears it). */
    private void fireRing() {
        Vec3d center = new Vec3d(getX(), groundBelow(getX(), getY() + 0.5, getZ(), 24), getZ());
        cast("Огненный круг", 22, false, left -> {
            particles(ParticleTypes.FLAME, center.x, center.y + 0.2, center.z, 6, 1.2, 0.05, 1.2, 0.01);
            if (left == 22) sound(SoundEvents.ITEM_FIRECHARGE_USE, 2f, 0.6f);
        }, () -> {
            sound(SoundEvents.ENTITY_BLAZE_SHOOT, 2f, 0.5f);
            hazards.ring(center, 0.32, phase >= 3 ? 14 : 11, 1.2, ParticleTypes.FLAME, victim -> {
                if (hurt(victim, 6f)) { victim.setOnFireFor(4); push(victim, center, 0.6, 0.3); }
            });
        });
    }

    /** «Нисхождение»: light falls on a spot near the foe; the seraph comes down there and stays on the ground for a while,
     * bared to swords, then rises again. Landing throws a small ring of fire. */
    private void descend(LivingEntity target) {
        Vec3d toward = new Vec3d(getX() - target.getX(), 0, getZ() - target.getZ());
        toward = toward.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : toward.normalize();
        double x = target.getX() + toward.x * 4, z = target.getZ() + toward.z * 4;
        Vec3d spot = new Vec3d(x, groundBelow(x, target.getY() + 2, z, 8), z);
        cast("Нисхождение", 24, true, left -> {
            particles(ParticleTypes.FLAME, spot.x, spot.y + 0.1, spot.z, 5, 0.8, 0.05, 0.8, 0);
            particles(ParticleTypes.END_ROD, spot.x, spot.y + 2, spot.z, 1, 0.3, 1.5, 0.3, 0);
            if (left == 24) sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 2f, 1.4f);
        }, () -> {
            landing = spot;
            descendLeft = DESCENT_TICKS;
            dataTracker.set(DESCENDING, true);
            sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 2f, 0.6f);
            hazards.later(18, () -> {
                Vec3d at = new Vec3d(getX(), groundBelow(getX(), getY() + 1, getZ(), 8), getZ());
                particles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, 50, 2.2, 0.2, 2.2, 0.05);
                hazards.ring(at, 0.3, 5, 1.0, ParticleTypes.SMALL_FLAME, victim -> { if (hurt(victim, 4f)) victim.setOnFireFor(2); });
            });
            for (ServerPlayerEntity player : playersNear(32))
                player.sendMessage(Text.literal("Серафим сошёл на землю — бей!").formatted(Formatting.GOLD), true);
        });
    }

    // ---- Ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        castUltimate(left -> {
            for (int i = 0; i < 6; i++) {
                double angle = random.nextDouble() * Math.PI * 2, r = 2 + random.nextDouble() * 8;
                particles(ParticleTypes.FLAME, target.getX() + Math.cos(angle) * r, target.getY() + 6 + random.nextDouble() * 3,
                        target.getZ() + Math.sin(angle) * r, 1, 0, -0.2, 0, 0.02);
            }
            particles(ParticleTypes.FLAME, getX(), getY() + getHeight() * FACE, getZ(), 10, 2.5, 1.6, 2.5, 0.05);
            if (left % 20 == 0) sound(SoundEvents.BLOCK_BELL_RESONATE, 2f, 1.4f);
        }, () -> flameFromHeaven(target));
    }

    /** Over six seconds, 14..20 circles fall around the foe (every third right under him, the rest scattered), each striking
     * as a column of fire after its warning (shorter in phase 3). */
    private void flameFromHeaven(LivingEntity target) {
        int count = phase >= 3 ? 20 : phase == 2 ? 16 : 14;
        int warn = phase >= 3 ? 20 : 28;
        int every = Math.max(3, 120 / count);
        boolean fire = mayBreakBlocks();
        sound(SoundEvents.ENTITY_WITHER_SHOOT, 2f, 0.6f);
        debug("Пламень с небес: столпов " + count + ", огонь на земле " + fire);
        hazards.repeat(every, count, step -> {
            LivingEntity aim = target.isAlive() ? target : getTarget();
            if (aim == null) return;
            double x = aim.getX(), z = aim.getZ();
            if (step % 3 != 0) {
                double angle = random.nextDouble() * Math.PI * 2, r = 1.5 + random.nextDouble() * 6;
                x += Math.cos(angle) * r;
                z += Math.sin(angle) * r;
            }
            Vec3d at = new Vec3d(x, groundBelow(x, aim.getY() + 3, z, 10), z);
            hazards.zone(at, 1.8, warn, ParticleTypes.FLAME, center -> {
                for (int h = 0; h < 12; h++)
                    particles(h % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, center.x, center.y + h * 0.7, center.z, 6, 0.5, 0.3, 0.5, 0.02);
                getWorld().playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 1.5f, 0.5f);
                if (fire) kindle(BlockPos.ofFloored(center.x, center.y + 0.1, center.z));
            }, victim -> {
                if (!hurt(victim, 8f)) return;
                victim.setOnFireFor(5);
                if (!victim.hasStatusEffect(BossEffects.SCORCH)) victim.sendMessage(Text.literal("Опаление: лечение жжёт").formatted(Formatting.GOLD));
                BossEffects.afflict(victim, BossEffects.SCORCH, 20 * 10);
            });
        });
    }

    /** Lights fire on a struck spot: only in air, over a floor that holds it. */
    private void kindle(BlockPos pos) {
        World world = getWorld();
        if (!world.getBlockState(pos).isAir()) return;
        BlockState fire = AbstractFireBlock.getState(world, pos);
        if (fire.canPlaceAt(world, pos)) world.setBlockState(pos, fire);
    }

    // ---- The weak point: water and snow. ----

    /** A snowball, or a splash bottle of plain water (the harmful potions are only potions). */
    @Override protected boolean weakTrigger(DamageSource source, float amount) {
        var direct = source.getSource();
        if (direct instanceof net.minecraft.entity.projectile.thrown.SnowballEntity) return true;
        return direct instanceof net.minecraft.entity.projectile.thrown.PotionEntity potion
                && potion.getStack().getOrDefault(net.minecraft.component.DataComponentTypes.POTION_CONTENTS, net.minecraft.component.type.PotionContentsComponent.DEFAULT)
                        .matches(net.minecraft.potion.Potions.WATER);
    }
    @Override protected String weakHint() { return "пламень гасит вода — снежок, взрывная бутылка воды или дождь"; }
    @Override protected String weakReason() { return "Вода погасила пламень — бей!"; }
    @Override protected String ultHint() { return "не стой в отмеченных кругах: огненные столпы падают по очереди"; }
    @Override protected String phaseName(int phase) { return phase == 2 ? "Шесть крыл" : "Свят, свят, свят"; }
    @Override protected Vec3d weakSpot() { return new Vec3d(getX(), getY() + getHeight() * FACE, getZ()); }
    @Override public void provokeWeakPoint(net.minecraft.server.network.ServerPlayerEntity player) {
        var ball = new net.minecraft.entity.projectile.thrown.SnowballEntity(getWorld(), player);
        Vec3d face = weakSpot();
        ball.setPosition(face.x, face.y, face.z - 0.8);
        damage(getDamageSources().thrown(ball, player), 0f);
        ball.discard();
    }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_BLAZE_AMBIENT, 3f, 0.5f);
        particles(ParticleTypes.FLAME, getX(), getY() + getHeight() * FACE, getZ(), 100, 3, 2.5, 3, 0.1);
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() {
        dataTracker.set(DESCENDING, false);
        for (LivingEntity victim : victims(getPos(), 32)) victim.removeStatusEffect(BossEffects.SCORCH);
    }
}
