package io.github.divineblock.boss;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
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
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** Херувим с пламенным мечом (Genesis 3:24): a four-faced, four-winged guardian in golden armour who keeps the way to the
 * tree of life. He never leaves his guarded place (12 blocks around his home, the church's altar or treasure). He sweeps the
 * flaming sword in an arc before him (in later phases back again behind him), lunges along a burning line, and throws the
 * sword spinning out and back. Ultimate «Обращающийся меч»: the sword turns around him in a widening circle for several
 * seconds; it runs low, so a jump clears it, or stay out of its reach. Whoever it strikes is cast out with «Изгнание из рая»:
 * thrown twenty blocks out of the guarded place, and for thirty seconds the flame pushes him back whenever he comes near. */
public final class CherubEntity extends SacredBoss {
    private static final TrackedData<Boolean> SPINNING = DataTracker.registerData(CherubEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    /** The guarded place: this many blocks around home. */
    static final double GUARD = 12;
    /** Where the exiled land, in blocks from home. */
    static final double EXILE_DISTANCE = 22;
    static final int EXILE_TICKS = 20 * 30;

    private int spinLeft;

    public CherubEntity(EntityType<? extends CherubEntity> type, World world) { super(type, world, BossBar.Color.YELLOW); }

    /** The exile is kept by each living cherub in its own tick ({@link #keepGate}): nothing global to hook. */
    static void registerHooks() { }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(SPINNING, false);
    }
    /** On the client too: whether the sword is turning around him now (the ultimate). */
    public boolean isSpinning() { return dataTracker.get(SPINNING); }

    @Override public BossKind kind() { return BossKind.CHERUB; }
    @Override protected String theme() { return "punishment_39"; }
    @Override protected float meleeDamage() { return 8f; }
    @Override protected double meleeReach() { return 4.5; }

    /** The centre of the guarded place (home, or where he stands if he has none). */
    public Vec3d guarded() { return home == null ? getPos() : Vec3d.ofBottomCenter(home); }

    @Override protected void mobTick() {
        if (home == null) setHome(getBlockPos());
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world) || isDead()) return;
        if (spinLeft > 0 && --spinLeft == 0) dataTracker.set(SPINNING, false);
        if (age % 5 == 0) keepGate(world);
        if (age % 4 == 0) {
            Vec3d tip = swordTip();
            particles(ParticleTypes.FLAME, tip.x, tip.y, tip.z, 2, 0.15, 0.4, 0.15, 0.01);
        }
    }

    /** Walks at the foe, but never out of the guarded place: at its edge he waits. Stands still while the sword turns. */
    @Override protected void pursue(LivingEntity target) {
        if (spinLeft > 0) { getNavigation().stop(); return; }
        if (age % 10 != 0) return;
        Vec3d center = guarded();
        Vec3d goal = target.getPos();
        Vec3d off = goal.subtract(center).multiply(1, 0, 1);
        if (off.length() > GUARD - 1) goal = center.add(off.normalize().multiply(GUARD - 1)).add(0, target.getY() - center.y, 0);
        if (goal.squaredDistanceTo(getPos()) > meleeReach() * meleeReach() * 0.6) getNavigation().startMovingTo(goal.x, goal.y, goal.z, 1.0);
        else getNavigation().stop();
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        // The turning sword is the whole of his attention: no throws or lunges until it stops.
        if (spinLeft > 0) return 20;
        double distance = distanceTo(target);
        int roll = random.nextInt(3);
        if (distance > 8 || roll == 0 && distance > 4) { throwSword(target); return 80; }
        if (distance > 4.5 || roll == 1) { lunge(target); return 70; }
        sweep(target);
        return 60;
    }

    /** «Взмах меча»: a burning arc is drawn on the ground before him, then the sword sweeps it; from phase 2 it comes back
     * behind him too (both arcs are shown from the start). */
    private void sweep(LivingEntity target) {
        Vec3d center = getPos();
        double facing = Math.atan2(target.getZ() - center.z, target.getX() - center.x);
        boolean back = phase >= 2;
        cast("Взмах пламенного меча", 22, true, left -> {
            if (left % 3 == 0) {
                drawArc(center, facing, 5.5, ParticleTypes.FLAME);
                if (back) drawArc(center, facing + Math.PI, 5.5, ParticleTypes.SMALL_FLAME);
            }
            if (left == 22) sound(SoundEvents.ITEM_FIRECHARGE_USE, 2f, 0.7f);
        }, () -> {
            swingHand(net.minecraft.util.Hand.MAIN_HAND);
            strikeArc(center, facing, 5.5);
            if (back) hazards.later(10, () -> { swingHand(net.minecraft.util.Hand.MAIN_HAND); strikeArc(center, facing + Math.PI, 5.5); });
        });
    }

    private void drawArc(Vec3d center, double facing, double radius, net.minecraft.particle.ParticleEffect effect) {
        for (double r = 1.5; r <= radius; r += 1.3)
            for (double a = -1.2; a <= 1.2; a += 0.6 / r)
                particles(effect, center.x + Math.cos(facing + a) * r, center.y + 0.15, center.z + Math.sin(facing + a) * r, 1, 0, 0, 0, 0);
    }

    private void strikeArc(Vec3d center, double facing, double radius) {
        sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.6f);
        for (double a = -1.2; a <= 1.2; a += 0.15)
            particles(ParticleTypes.FLAME, center.x + Math.cos(facing + a) * radius * 0.7, center.y + 1, center.z + Math.sin(facing + a) * radius * 0.7, 3, 0.4, 0.3, 0.4, 0.02);
        particles(ParticleTypes.SWEEP_ATTACK, center.x + Math.cos(facing) * 2.5, center.y + 1, center.z + Math.sin(facing) * 2.5, 3, 1, 0.2, 1, 0);
        for (LivingEntity victim : victims(center, radius + 0.5)) {
            double angle = MathHelper.wrapDegrees((Math.atan2(victim.getZ() - center.z, victim.getX() - center.x) - facing) * MathHelper.DEGREES_PER_RADIAN);
            if (Math.abs(angle) > 72 || victim.getY() - center.y > 3) continue;
            if (hurt(victim, 8f)) { victim.setOnFireFor(3); push(victim, center, 0.9, 0.3); }
        }
    }

    /** «Выпад»: a dotted line marks his path to the foe; then he lunges along it and the line burns. */
    private void lunge(LivingEntity target) {
        Vec3d from = getPos().add(0, 1, 0);
        Vec3d dir = target.getPos().subtract(getPos()).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : dir.normalize();
        Vec3d end = getPos().add(dir.multiply(Math.min(9, distanceTo(target) + 2)));
        Vec3d center = guarded();
        Vec3d off = end.subtract(center).multiply(1, 0, 1);
        if (off.length() > GUARD) end = center.add(off.normalize().multiply(GUARD)).add(0, end.y - center.y, 0);
        Vec3d to = end.add(0, 1, 0);
        Vec3d push = dir;
        hazards.beam(from, to, 20, 8, 0.8, ParticleTypes.SMALL_FLAME, ParticleTypes.FLAME, victim -> {
            if (hurt(victim, 9f)) { victim.setOnFireFor(3); victim.addVelocity(push.x * 0.8, 0.3, push.z * 0.8); victim.velocityModified = true; }
        });
        double length = to.distanceTo(from);
        cast("Выпад херувима", 20, true, left -> { if (left == 20) sound(SoundEvents.ENTITY_BLAZE_AMBIENT, 2f, 0.6f); }, () -> {
            setVelocity(push.x * Math.min(1.6, length * 0.2), 0.1, push.z * Math.min(1.6, length * 0.2));
            velocityModified = true;
            swingHand(net.minecraft.util.Hand.MAIN_HAND);
            sound(SoundEvents.ITEM_TRIDENT_RIPTIDE_1, 2f, 0.8f);
        });
    }

    /** «Брошенный меч»: the sword blazes up, then flies spinning at the foe and comes back to his hand (two from phase 3). */
    private void throwSword(LivingEntity target) {
        int swords = phase >= 3 ? 2 : 1;
        cast("Брошенный меч", 20, true, left -> {
            Vec3d tip = swordTip();
            particles(ParticleTypes.FLAME, tip.x, tip.y, tip.z, 4, 0.2, 0.5, 0.2, 0.02);
            if (left == 20) sound(SoundEvents.ITEM_FIRECHARGE_USE, 2f, 0.9f);
        }, () -> {
            sound(SoundEvents.ITEM_TRIDENT_THROW, 2f, 0.6f);
            for (int i = 0; i < swords; i++) spinningSword(target, i == 0 ? 0 : 0.35);
        });
    }

    private void spinningSword(LivingEntity target, double tilt) {
        Vec3d start = getPos().add(0, getHeight() * 0.45, 0);
        Vec3d aim = target.getPos().add(0, 1, 0).subtract(start).normalize();
        Vec3d dir = new Vec3d(aim.x * Math.cos(tilt) - aim.z * Math.sin(tilt), aim.y, aim.x * Math.sin(tilt) + aim.z * Math.cos(tilt));
        Vec3d[] pos = { start };
        Set<LivingEntity> struck = new HashSet<>();
        hazards.repeat(1, 48, step -> {
            if (step < 22) pos[0] = pos[0].add(dir.multiply(0.6));
            else {
                Vec3d back = getPos().add(0, getHeight() * 0.45, 0).subtract(pos[0]);
                if (back.length() < 1) return;
                pos[0] = pos[0].add(back.normalize().multiply(Math.min(0.7, back.length())));
            }
            Vec3d p = pos[0];
            double spin = step * 0.9;
            for (int k = -2; k <= 2; k++)
                particles(ParticleTypes.FLAME, p.x + Math.cos(spin) * k * 0.4, p.y, p.z + Math.sin(spin) * k * 0.4, 1, 0, 0, 0, 0);
            if (step % 6 == 0) getWorld().playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_BLAZE_BURN, SoundCategory.HOSTILE, 1f, 1.4f);
            for (LivingEntity victim : victims(p, 2.5))
                if (victim.getBoundingBox().expand(0.9).contains(p) && struck.add(victim) && hurt(victim, 7f)) victim.setOnFireFor(3);
        });
    }

    // ---- Ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        castUltimate(left -> {
            Vec3d tip = swordTip();
            particles(ParticleTypes.FLAME, tip.x, tip.y, tip.z, 6, 0.3, 0.6, 0.3, 0.03);
            double a = left * 0.4;
            for (int k = 0; k < 4; k++) {
                double angle = a + k * Math.PI / 2, r = 2 + (ULT_WARNING - left) * 0.15;
                particles(ParticleTypes.FLAME, getX() + Math.cos(angle) * r, getY() + 0.3, getZ() + Math.sin(angle) * r, 1, 0, 0, 0, 0);
            }
            if (left % 20 == 0) sound(SoundEvents.BLOCK_BELL_RESONATE, 2f, 0.8f);
        }, this::turningSword);
    }

    /** The sword turns around him low over the ground, its reach widening from two to fourteen blocks; a jump clears it. */
    private void turningSword() {
        int total = phase >= 3 ? 100 : 120;
        double speed = phase >= 3 ? 0.2 : 0.15;
        int blades = phase >= 3 ? 2 : 1;
        spinLeft = total;
        dataTracker.set(SPINNING, true);
        getNavigation().stop();
        sound(SoundEvents.ENTITY_WITHER_SHOOT, 2f, 0.5f);
        Map<LivingEntity, Integer> lastHit = new HashMap<>();
        double start = random.nextDouble() * Math.PI * 2;
        hazards.later(total + 10, () -> expose(EXPOSED_TICKS, "Пламенный меч угас — херувим открыт, бей!"));
        hazards.repeat(1, total, step -> {
            Vec3d center = getPos();
            double t = step / (double) total;
            double inner = 0.5 + 9.5 * t, outer = inner + 4.5;
            for (int b = 0; b < blades; b++) {
                double angle = start + step * speed + b * Math.PI;
                double cos = Math.cos(angle), sin = Math.sin(angle);
                for (double r = inner; r <= outer; r += 0.35)
                    particles(r > outer - 0.6 ? ParticleTypes.LAVA : ParticleTypes.FLAME, center.x + cos * r, center.y + 0.6, center.z + sin * r, 1, 0.02, 0.05, 0.02, 0);
                Vec3d a = center.add(cos * inner, 0.6, sin * inner), z = center.add(cos * outer, 0.6, sin * outer);
                for (LivingEntity victim : victims(center, outer + 1.5)) {
                    double above = victim.getY() - center.y;
                    if (above > 1.0 || above < -2) continue;
                    Vec3d feet = new Vec3d(victim.getX(), center.y + 0.6, victim.getZ());
                    if (Hazards.distanceToSegment(feet, a, z) > 0.6 + victim.getWidth() / 2) continue;
                    Integer last = lastHit.get(victim);
                    if (last != null && step - last < 15) continue;
                    lastHit.put(victim, step);
                    if (hurt(victim, 9f)) { victim.setOnFireFor(3); exile(victim); }
                }
            }
            if (step % 10 == 0) sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.5f + (float) t * 0.5f);
        });
    }

    // ---- «Изгнание из рая». ----

    /** Casts a victim out: twenty-odd blocks from home onto safe ground, under «Изгнание из рая» for thirty seconds. */
    void exile(LivingEntity victim) {
        if (!victim.isAlive()) return;
        if (!victim.hasStatusEffect(BossEffects.EXILE)) BossEffects.afflict(victim, BossEffects.EXILE, EXILE_TICKS);
        throwOut(victim);
        victim.sendMessage(Text.literal("Изгнание из рая: пламенный меч не пустит тебя назад").formatted(Formatting.GOLD));
    }

    private void throwOut(LivingEntity victim) {
        Vec3d center = guarded();
        BlockPos spot = landingSpot(victim, center);
        particles(ParticleTypes.FLAME, victim.getX(), victim.getY() + 1, victim.getZ(), 30, 0.4, 0.8, 0.4, 0.05);
        if (spot == null) { push(victim, center, 2.5, 0.8); return; }
        victim.requestTeleport(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        victim.fallDistance = 0;
        particles(ParticleTypes.FLAME, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, 30, 0.4, 0.8, 0.4, 0.05);
        getWorld().playSound(null, spot, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.5f, 0.6f);
        debug("изгнан " + victim.getName().getString() + " в " + spot.toShortString());
    }

    /** Safe ground about {@link #EXILE_DISTANCE} blocks from home, the victim's way out first, then other directions. */
    private BlockPos landingSpot(LivingEntity victim, Vec3d center) {
        World world = getWorld();
        Vec3d off = victim.getPos().subtract(center).multiply(1, 0, 1);
        double base = off.lengthSquared() < 1e-4 ? random.nextDouble() * Math.PI * 2 : Math.atan2(off.z, off.x);
        int y0 = MathHelper.floor(center.y);
        for (double distance = EXILE_DISTANCE; distance <= EXILE_DISTANCE + 6; distance += 3)
            for (int turn = 0; turn < 12; turn++) {
                double angle = base + (turn % 2 == 0 ? 1 : -1) * ((turn + 1) / 2) * Math.PI / 6;
                int x = MathHelper.floor(center.x + Math.cos(angle) * distance), z = MathHelper.floor(center.z + Math.sin(angle) * distance);
                for (int dy = 10; dy >= -10; dy--) {
                    BlockPos pos = new BlockPos(x, y0 + dy, z);
                    if (standable(world, pos)) return pos;
                }
            }
        // Nothing near his height: the surface far from home, if it is solid.
        int x = MathHelper.floor(center.x + Math.cos(base) * EXILE_DISTANCE), z = MathHelper.floor(center.z + Math.sin(base) * EXILE_DISTANCE);
        BlockPos top = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        return standable(world, top) ? top : null;
    }

    private static boolean standable(World world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        if (!below.isSideSolidFullSquare(world, pos.down(), Direction.UP) || below.getFluidState().isIn(FluidTags.LAVA)) return false;
        for (int y = 0; y < 2; y++) {
            BlockPos at = pos.up(y);
            BlockState state = world.getBlockState(at);
            if (!state.getCollisionShape(world, at).isEmpty() || !state.getFluidState().isEmpty() || state.isIn(BlockTags.FIRE)) return false;
        }
        return true;
    }

    /** The flaming gate: an exiled player who comes into the guarded place is pushed back out with fire, or thrown out again
     * when he got deep inside (an ender pearl, a door). */
    private void keepGate(ServerWorld world) {
        Vec3d center = guarded();
        for (ServerPlayerEntity player : world.getPlayers(p -> p.isAlive() && !p.isSpectator() && !p.isCreative()
                && p.hasStatusEffect(BossEffects.EXILE) && Math.abs(p.getY() - center.y) < 10)) {
            double flat = Math.hypot(player.getX() - center.x, player.getZ() - center.z);
            if (flat > GUARD + 1) continue;
            if (flat < GUARD - 4) { throwOut(player); player.sendMessage(Text.literal("Изгнание из рая: путь назад закрыт").formatted(Formatting.GOLD), true); continue; }
            push(player, center, 1.3, 0.45);
            player.setOnFireFor(2);
            particles(ParticleTypes.FLAME, player.getX(), player.getY() + 1, player.getZ(), 20, 0.4, 0.8, 0.4, 0.04);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 1.2f, 0.7f);
            player.sendMessage(Text.literal("Изгнание из рая: пламенный меч охраняет путь").formatted(Formatting.GOLD), true);
        }
    }

    // ---- The weak point: the parry. ----

    /** A blow of the sword taken on a raised shield is parried: the cherub reels, open. */
    @Override public boolean hurt(LivingEntity victim, float base) {
        if (victim instanceof net.minecraft.entity.player.PlayerEntity player && player.isBlocking() && spinLeft == 0 && weakReady()) {
            parried(player);
            return false;
        }
        return super.hurt(victim, base);
    }
    private void parried(LivingEntity by) {
        sound(SoundEvents.ITEM_SHIELD_BLOCK, 2.5f, 0.6f);
        sound(SoundEvents.BLOCK_ANVIL_LAND, 1.5f, 1.4f);
        particles(ParticleTypes.FLASH, by.getX(), by.getEyeY(), by.getZ(), 1, 0, 0, 0, 0);
        expose(EXPOSED_TICKS, "Удар отражён щитом — херувим потерял равновесие, бей!");
    }
    @Override protected String weakHint() { return "прими его удар на поднятый щит — отражённый, он открывается"; }
    @Override protected String ultHint() { return "перепрыгивай вращающийся клинок; когда меч угаснет — он открыт"; }
    @Override protected String phaseName(int phase) { return phase == 2 ? "Меч за спиной" : "Два клинка"; }
    @Override public void provokeWeakPoint(net.minecraft.server.network.ServerPlayerEntity player) { parried(player); }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ITEM_FIRECHARGE_USE, 3f, 0.5f);
        Vec3d tip = swordTip();
        particles(ParticleTypes.FLAME, tip.x, tip.y, tip.z, 60, 0.5, 1.5, 0.5, 0.1);
        for (ServerPlayerEntity player : playersNear(32))
            player.sendMessage(Text.literal(phase == 2 ? "Меч обращается во все стороны" : "Херувим разгорается").formatted(Formatting.GOLD), false);
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() {
        dataTracker.set(SPINNING, false);
        if (!(getWorld() instanceof ServerWorld world)) return;
        Vec3d center = guarded();
        for (ServerPlayerEntity player : world.getPlayers(p -> p.hasStatusEffect(BossEffects.EXILE) && p.squaredDistanceTo(center) < 64 * 64)) {
            player.removeStatusEffect(BossEffects.EXILE);
            player.sendMessage(Text.literal("Путь к древу жизни открыт").formatted(Formatting.GOLD), true);
        }
    }

    /** Where the flaming sword's tip is: raised before him, a little to his right, about level with the top of his head. */
    private Vec3d swordTip() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        double right = getHeight() * 0.2, ahead = getHeight() * 0.6;
        return new Vec3d(getX() - Math.cos(yaw) * right - Math.sin(yaw) * ahead, getY() + getHeight() * 1.05,
                getZ() - Math.sin(yaw) * right + Math.cos(yaw) * ahead);
    }
}
