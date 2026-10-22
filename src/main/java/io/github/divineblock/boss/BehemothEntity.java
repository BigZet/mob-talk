package io.github.divineblock.boss;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Бегемот (Job 40:15–24): the beast of the earth, who eats grass as an ox, whose strength is in his loins and whose bones are
 * as pieces of brass; he moves his tail like a cedar. Stomps (a ring on the ground to jump over, twice from phase 2), sweeps
 * with his cedar tail, spits swamp mud (slowness) and rams down a line marked on the ground («Таран», a chain of three in
 * phase 3; a ram into a wall stuns him for a moment). Ultimate «Сила чресл его»: he rears up, a long line is marked, then he
 * charges down it and the earth splits behind him — a trench when block damage is allowed, otherwise a quake along the line.
 * Whoever the quake catches gets «Прах земной» (no jumping) and slowness. */
public final class BehemothEntity extends SacredBoss {
    private static final TrackedData<Boolean> CHARGING = DataTracker.registerData(BehemothEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    static final double ULT_LENGTH = 22;

    private Vec3d chargeDir, chargeFrom;
    private int chargeLeft, chargeTotal, chainLeft, stunned;
    private boolean quake;
    private final Set<LivingEntity> trampled = new HashSet<>();
    private final Set<BlockPos> carved = new HashSet<>();

    public BehemothEntity(EntityType<? extends BehemothEntity> type, World world) { super(type, world, BossBar.Color.GREEN); }

    /** «Прах земной» already forbids jumping (mixin on jump): nothing else to hook on the server. */
    static void registerHooks() { }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(CHARGING, false);
    }
    /** On the client too: whether he is charging right now. */
    public boolean isCharging() { return dataTracker.get(CHARGING); }

    /** An attack came due during a charge; it follows right after the charge. */
    private boolean attackWaiting;

    @Override public BossKind kind() { return BossKind.BEHEMOTH; }
    @Override protected String theme() { return "punishment_02"; }
    @Override protected float meleeDamage() { return 10f; }
    @Override protected double meleeReach() { return 6.0; }

    @Override protected void pursue(LivingEntity target) {
        if (chargeLeft > 0 || stunned > 0) { getNavigation().stop(); return; }
        super.pursue(target);
    }

    @Override protected int attack(LivingEntity target) {
        if (chargeLeft > 0) { attackWaiting = true; return 20; }
        // Dazed after a crash he cannot move, but he can still spit the swamp at whoever comes close.
        if (stunned > 0) { mud(target); return 60; }
        double distance = distanceTo(target);
        int roll = random.nextInt(4), faster = 12 * (phase - 1);
        if (distance < 7 && roll == 0) { stomp(phase >= 2 ? 2 : 1); return 70 - faster; }
        if (distance < 9 && roll == 1) { tailSweep(); return 65 - faster; }
        if (distance > 7.5 && roll != 3 || roll == 2) { ram(target, phase >= 3 ? 2 : 0); return 100 - faster; }
        mud(target);
        return 60 - faster;
    }

    /** «Топот»: he rises on his hind legs, then the ground rings out from his feet (twice from phase 2). */
    private void stomp(int times) {
        cast("Топот", 20, true, left -> {
            particles(ground(), getX(), getY() + 0.2, getZ(), 4, 1.5, 0.1, 1.5, 0.1);
            if (left == 20) sound(SoundEvents.ENTITY_RAVAGER_ROAR, 2.5f, 0.7f);
        }, () -> {
            for (int i = 0; i < times; i++) hazards.later(1 + i * 16, () -> {
                Vec3d center = getPos();
                sound(SoundEvents.ENTITY_GENERIC_EXPLODE, 1.6f, 0.5f);
                sound(SoundEvents.ENTITY_RAVAGER_STEP, 3f, 0.4f);
                particles(ground(), center.x, center.y + 0.3, center.z, 60, 2.5, 0.2, 2.5, 0.2);
                hazards.ring(center, 0.45, 11, 1.2, ground(), victim -> {
                    if (hurt(victim, 7f)) {
                        push(victim, center, 0.7, 0.45);
                        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
                    }
                });
            });
        });
    }

    /** «Хвост, как кедр» (Job 40:17): an arc behind him is marked in green, then the cedar tail sweeps it. */
    private void tailSweep() {
        Vec3d center = getPos(), back = forward().multiply(-1);
        ParticleEffect needle = new DustParticleEffect(new Vector3f(0.22f, 0.42f, 0.16f), 1.6f);
        cast("Хвост, как кедр", 24, true, left -> {
            if (left % 3 == 0)
                for (int a = -110; a <= 110; a += 10) {
                    Vec3d dir = back.rotateY(a * MathHelper.RADIANS_PER_DEGREE);
                    for (double r = 4; r <= 9; r += 2.5) particles(needle, center.x + dir.x * r, center.y + 0.3, center.z + dir.z * r, 1, 0, 0, 0, 0);
                }
            if (left == 24) sound(SoundEvents.ENTITY_HOGLIN_ANGRY, 2.5f, 0.4f);
        }, () -> {
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 3f, 0.4f);
            sound(SoundEvents.BLOCK_WOOD_BREAK, 2f, 0.5f);
            for (int a = -110; a <= 110; a += 20) {
                Vec3d dir = back.rotateY(a * MathHelper.RADIANS_PER_DEGREE);
                particles(ParticleTypes.SWEEP_ATTACK, center.x + dir.x * 6.5, center.y + 1, center.z + dir.z * 6.5, 1, 0, 0, 0, 0);
            }
            for (LivingEntity victim : victims(center, 9.5)) {
                Vec3d rel = victim.getPos().subtract(center).multiply(1, 0, 1);
                if (rel.lengthSquared() > 1 && rel.normalize().dotProduct(back) < -0.35) continue;
                if (hurt(victim, 9f)) push(victim, center, 1.6, 0.5);
            }
        });
    }

    /** «Болотная тина»: mud gathers in his mouth, then two (three later) clots fly; whoever they hit is slowed. */
    private void mud(LivingEntity target) {
        int count = phase >= 2 ? 3 : 2;
        ParticleEffect mud = new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.MUD.getDefaultState());
        cast("Болотная тина", 20, true, left -> {
            Vec3d mouth = mouthPos();
            particles(mud, mouth.x, mouth.y, mouth.z, 3, 0.3, 0.2, 0.3, 0.01);
            if (left == 20) sound(SoundEvents.BLOCK_MUD_STEP, 3f, 0.5f);
        }, () -> {
            Vec3d mouth = mouthPos();
            Vec3d aim = target.getEyePos().subtract(mouth).normalize();
            for (int i = 0; i < count; i++) {
                Vec3d dir = aim.rotateY((float) ((i - (count - 1) / 2.0) * 0.2));
                hazards.orb(mouth, dir.multiply(0.45), 60, 0.5, mud, victim -> {
                    if (hurt(victim, 5f)) victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 1));
                    particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.MUD.getDefaultState()), victim.getX(), victim.getBodyY(0.5), victim.getZ(), 20, 0.4, 0.4, 0.4, 0.1);
                });
            }
            sound(SoundEvents.ENTITY_LLAMA_SPIT, 3f, 0.4f);
        });
    }

    /** «Таран»: a line on the ground toward the foe, he paws the earth, then charges down it. {@code chain} more follow. */
    private void ram(LivingEntity target, int chain) {
        Vec3d from = getPos();
        Vec3d dir = target.getPos().subtract(from).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 0.01 ? forward() : dir.normalize();
        double length = MathHelper.clamp(Math.sqrt(squaredDistanceTo(target)) + 5, 8, 18);
        Vec3d line = dir;
        int warn = chainLeft > 0 ? 16 : 26;
        cast("Таран", warn, true, left -> {
            face(line);
            if (left % 3 == 0) markLine(from, line, length, 1.6);
            particles(ground(), getX(), getY() + 0.2, getZ(), 3, 1.2, 0.1, 1.2, 0.1);
            if (left % 8 == 0) sound(SoundEvents.ENTITY_RAVAGER_STEP, 2f, 0.6f);
            if (left == warn) sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.6f);
        }, () -> startCharge(line, length, false, chain));
    }

    private void markLine(Vec3d from, Vec3d dir, double length, double halfWidth) {
        ParticleEffect dust = new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.COARSE_DIRT.getDefaultState());
        Vec3d side = new Vec3d(-dir.z, 0, dir.x);
        for (double s = 1; s <= length; s += 0.8) {
            Vec3d at = from.add(dir.multiply(s));
            particles(ParticleTypes.CRIT, at.x, at.y + 0.2, at.z, 1, 0, 0, 0, 0);
            particles(dust, at.x + side.x * halfWidth, at.y + 0.3, at.z + side.z * halfWidth, 1, 0, 0, 0, 0);
            particles(dust, at.x - side.x * halfWidth, at.y + 0.3, at.z - side.z * halfWidth, 1, 0, 0, 0, 0);
        }
    }

    private void startCharge(Vec3d dir, double length, boolean quake, int chain) {
        double speed = chargeSpeed(quake);
        chargeDir = dir;
        chargeFrom = getPos();
        chargeTotal = chargeLeft = (int) Math.ceil(length / speed);
        chainLeft = chain;
        this.quake = quake;
        trampled.clear();
        carved.clear();
        dataTracker.set(CHARGING, true);
        sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.5f);
    }
    private double chargeSpeed(boolean quake) { return quake ? 0.95 : 0.75 + 0.05 * phase; }

    @Override protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world)) return;
        if (stunned > 0) {
            stunned--;
            getNavigation().stop();
            if (age % 5 == 0) particles(ParticleTypes.CRIT, getX(), getY() + getHeight() + 0.3, getZ(), 4, 0.6, 0.2, 0.6, 0.05);
        }
        if (chargeLeft > 0) chargeStep(world);
        else if (age % 40 == 0 && getTarget() == null && world.getBlockState(getBlockPos().down()).isOf(Blocks.GRASS_BLOCK)) {
            // He eats grass as an ox (Job 40:15).
            particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.SHORT_GRASS.getDefaultState()), mouthPos().x, getY() + 0.3, mouthPos().z, 8, 0.3, 0.1, 0.3, 0.05);
            sound(SoundEvents.ENTITY_GENERIC_EAT, 1f, 0.5f);
        }
    }

    private void chargeStep(ServerWorld world) {
        getNavigation().stop();
        face(chargeDir);
        double speed = chargeSpeed(quake);
        setVelocity(chargeDir.x * speed, getVelocity().y, chargeDir.z * speed);
        particles(ground(), getX(), getY() + 0.2, getZ(), 8, 1.2, 0.1, 1.2, 0.15);
        if (age % 4 == 0) sound(SoundEvents.ENTITY_RAVAGER_STEP, 2.5f, 0.6f);
        if (quake && mayBreakBlocks()) carve(world);
        for (LivingEntity victim : victims(getPos().add(chargeDir.multiply(3.0)), 3.4))
            if (trampled.add(victim) && hurt(victim, quake ? 12f : 10f)) {
                push(victim, getPos(), 2.2, 0.6);
                particles(ParticleTypes.CRIT, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 14, 0.4, 0.4, 0.4, 0.3);
            }
        boolean crashed = horizontalCollision && chargeTotal - chargeLeft > 3;
        if (--chargeLeft <= 0 || crashed) endCharge(crashed);
    }

    private void endCharge(boolean crashed) {
        chargeLeft = 0;
        dataTracker.set(CHARGING, false);
        setVelocity(getVelocity().multiply(0.2, 1, 0.2));
        if (crashed) {
            stunned = 40;
            if (weakReady()) { stunned = EXPOSED_TICKS; expose(EXPOSED_TICKS, "Бегемот врезался в стену и оглушён — бей!"); }
            sound(SoundEvents.BLOCK_ANVIL_LAND, 2f, 0.5f);
            sound(SoundEvents.ENTITY_RAVAGER_STUNNED, 2.5f, 0.6f);
            particles(ground(), getX(), getY() + 1, getZ(), 40, 1.5, 0.8, 1.5, 0.2);
        }
        if (quake) { earthquake(chargeFrom, getPos()); return; }
        if (chainLeft > 0 && !crashed) {
            int next = chainLeft - 1;
            hazards.later(6, () -> {
                LivingEntity target = getTarget();
                if (target != null && isAlive() && !isCasting()) { chainLeft = 1; ram(target, next); }
            });
        } else if (attackWaiting) {
            // An attack fell due while he was charging: he turns on the foe as soon as he stops (telegraphed as always).
            hazards.later(6, () -> {
                LivingEntity target = getTarget();
                if (target != null && isAlive() && !isCasting() && chargeLeft == 0) attack(target);
            });
        }
        attackWaiting = false;
        chainLeft = 0;
    }

    /** The trench behind him as he charges (only when block damage is allowed): dirt, grass and stone, no ores or chests, no drops. */
    private void carve(ServerWorld world) {
        Vec3d at = getPos().subtract(chargeDir.multiply(2));
        Vec3d side = new Vec3d(-chargeDir.z, 0, chargeDir.x);
        for (int off = -1; off <= 1; off++) {
            BlockPos top = BlockPos.ofFloored(at.x + side.x * off, getY() - 0.5, at.z + side.z * off);
            for (int depth = 0; depth < (off == 0 ? 2 : 1); depth++) {
                BlockPos pos = top.down(depth);
                if (!carved.add(pos)) continue;
                BlockState state = world.getBlockState(pos);
                if (!breakable(state)) break;
                BlockPos above = pos.up();
                BlockState plant = world.getBlockState(above);
                if (depth == 0 && !plant.isAir() && plant.isReplaceable() && plant.getFluidState().isEmpty() && !plant.hasBlockEntity())
                    world.setBlockState(above, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
                particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 14, 0.4, 0.5, 0.4, 0.2);
                particles(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, state), pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5, 4, 0.5, 0.5, 0.5, 0);
            }
        }
    }

    private static boolean breakable(BlockState state) {
        if (state.isAir() || state.hasBlockEntity() || state.isOf(Blocks.BEDROCK) || !state.getFluidState().isEmpty()) return false;
        return state.isIn(BlockTags.DIRT) || state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.FARMLAND) || state.isIn(BlockTags.BASE_STONE_OVERWORLD);
    }

    /** After the long charge: the earth along the whole path cracks for a moment, then quakes. */
    private void earthquake(Vec3d from, Vec3d to) {
        sound(SoundEvents.ENTITY_GENERIC_EXPLODE, 2.5f, 0.4f);
        Vec3d line = to.subtract(from);
        double length = Math.max(1, line.length());
        Vec3d dir = line.multiply(1 / length);
        debug("Сила чресл его: разлом длиной " + (int) length + (mayBreakBlocks() ? ", ров" : ", без ломки"));
        hazards.repeat(3, 5, step -> {
            for (double s = 0; s <= length; s += 1.2) {
                Vec3d at = from.add(dir.multiply(s));
                particles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.2, at.z, 1, 0.6, 0.1, 0.6, 0.02);
                particles(ground(at), at.x, at.y + 0.3, at.z, 2, 0.8, 0.2, 0.8, 0.1);
            }
            if (step == 0) sound(SoundEvents.ENTITY_WARDEN_DIG, 3f, 0.5f);
        });
        hazards.later(16, () -> {
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE, 3f, 0.3f);
            for (double s = 0; s <= length; s += 2) {
                Vec3d at = from.add(dir.multiply(s));
                particles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0.5, 0.2, 0.5, 0);
                particles(ground(at), at.x, at.y + 0.5, at.z, 20, 1.2, 0.6, 1.2, 0.3);
            }
            for (LivingEntity victim : victims(from.add(line.multiply(0.5)), length / 2 + 4)) {
                double distance = Hazards.distanceToSegment(victim.getPos(), from, to);
                if (distance <= 3.5) {
                    if (hurt(victim, 10f)) push(victim, from.add(dir.multiply(MathHelper.clamp(victim.getPos().subtract(from).dotProduct(dir), 0, length))), 0.6, 0.5);
                    BossEffects.afflict(victim, BossEffects.DUST, 20 * 15);
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 8, 1));
                    victim.sendMessage(Text.literal("Прах земной: земля держит ноги, не прыгнуть").formatted(Formatting.GOLD));
                } else if (distance <= 9 && victim.isOnGround()) {
                    // The shaking further out: the ground jolts under the feet.
                    victim.addVelocity((random.nextDouble() - 0.5) * 0.3, 0.15, (random.nextDouble() - 0.5) * 0.3);
                    victim.velocityModified = true;
                }
            }
        });
    }

    // ---- The ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        if (chargeLeft > 0) { chainLeft = 0; quake = false; endCharge(false); }
        Vec3d from = getPos();
        Vec3d flat = target.getPos().subtract(from).multiply(1, 0, 1);
        Vec3d dir = flat.lengthSquared() < 0.01 ? forward() : flat.normalize();
        castUltimate(left -> {
            face(dir);
            if (left % 3 == 0) markLine(from, dir, ULT_LENGTH, 2.2);
            particles(ground(), getX(), getY() + 0.2, getZ(), 6, 2.5, 0.1, 2.5, 0.15);
            if (left % 20 == 0) sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.4f + 0.1f * (3 - left / 20));
            if (left % 10 == 5) sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 3f, 0.5f);
        }, () -> startCharge(dir, ULT_LENGTH, true, 0));
    }

    @Override protected String weakHint() { return "встань спиной к скале или стене и уйди от тарана — он врежется и оглохнет"; }
    @Override protected String ultHint() { return "уйди с полосы тарана; стена за спиной оглушит его"; }
    @Override protected String phaseName(int phase) { return phase == 2 ? "Кости как медь" : "Сила чресл его"; }
    @Override protected Vec3d weakSpot() { return new Vec3d(getX(), getY() + getHeight() * 0.85, getZ()); }
    /** Tests: a stone wall between him and the player, then the ram (he crashes into it). */
    @Override public void provokeWeakPoint(net.minecraft.server.network.ServerPlayerEntity player) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        Vec3d dir = player.getPos().subtract(getPos()).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 0.01 ? forward() : dir.normalize();
        Vec3d mid = getPos().add(dir.multiply(getWidth() * 0.5 + 3));
        Vec3d side = new Vec3d(-dir.z, 0, dir.x);
        java.util.List<BlockPos> wall = new java.util.ArrayList<>();
        for (int w = -4; w <= 4; w++) for (int h = 0; h < 6; h++) {
            BlockPos at = BlockPos.ofFloored(mid.add(side.multiply(w))).up(h);
            if (world.getBlockState(at).isAir()) { world.setBlockState(at, Blocks.STONE.getDefaultState()); wall.add(at); }
        }
        hazards.later(20 * 9, () -> { for (BlockPos at : wall) if (world.getBlockState(at).isOf(Blocks.STONE)) world.setBlockState(at, Blocks.AIR.getDefaultState()); });
        ram(player, 0);
    }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.4f);
        particles(ground(), getX(), getY() + 0.5, getZ(), 80, 2.5, 0.5, 2.5, 0.3);
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() {
        dataTracker.set(CHARGING, false);
        for (LivingEntity victim : victims(getPos(), 32)) victim.removeStatusEffect(BossEffects.DUST);
    }

    // ---- Where his parts are. ----

    private ParticleEffect ground() { return ground(getPos()); }
    /** Debris of the block under {@code at} (dirt when it is air or invisible). */
    private ParticleEffect ground(Vec3d at) {
        BlockState state = getWorld().getBlockState(BlockPos.ofFloored(at.x, at.y - 0.5, at.z));
        if (state.isAir() || state.getRenderType() == BlockRenderType.INVISIBLE) state = Blocks.DIRT.getDefaultState();
        return new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
    }
    private Vec3d forward() { return Vec3d.fromPolar(0, getBodyYaw()); }
    /** The mouth: the head reaches well past the hitbox, about five blocks ahead of the middle. */
    private Vec3d mouthPos() { Vec3d f = forward(); return getPos().add(f.x * 4.8, getHeight() * 0.55, f.z * 4.8); }
    private void face(Vec3d dir) {
        float yaw = (float) (MathHelper.atan2(dir.z, dir.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        setYaw(yaw); setBodyYaw(yaw); setHeadYaw(yaw);
    }
}
