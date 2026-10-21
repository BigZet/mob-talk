package io.github.divineblock.boss;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.SwimNavigation;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Левиафан (Job 41, Isaiah 27:1): the crooked serpent, the dragon that is in the sea. Fast in water, slow on land, where it
 * crawls back to the nearest water (or, when block damage is allowed, sinks a temporary pool around itself). Bites with a
 * lunge (from the surface too), sweeps its tail, breathes «Пламенники из пасти» (fire orbs) and a scalding cloud of smoke
 * from its nostrils. Ultimate «Пучина»: the water churns in a wide circle, then a whirlpool of ten blocks pulls everyone to
 * the centre and down for eight seconds and lays «Тяжесть глубин» on them; on land it is a vortex of sand and wind. */
public final class LeviathanEntity extends SacredBoss {
    static final double WHIRL = 10;
    private static final int POOL_LIFE = 20 * 120;
    private static final double POOL_RADIUS = 5.3;

    private Vec3d swimTo;
    private BlockPos waterNear;
    private int scanTimer, poolLeft, poolCooldown, poolTop;
    /** The pool dug on land: the ground it replaced and the plants that stood on it, put back when it drains. */
    private final Map<BlockPos, BlockState> poolGround = new LinkedHashMap<>(), poolPlants = new LinkedHashMap<>();

    public LeviathanEntity(EntityType<? extends LeviathanEntity> type, World world) { super(type, world, BossBar.Color.BLUE); }

    /** «Тяжесть глубин» on the server: under water the breath runs out twice as fast (the sinking itself is client side). */
    static void registerHooks() {
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            for (ServerPlayerEntity player : world.getPlayers())
                if (player.getAir() > 0 && player.isSubmergedInWater() && player.hasStatusEffect(BossEffects.DEPTHS) && !player.isCreative())
                    player.setAir(player.getAir() - 1);
        });
    }

    @Override public BossKind kind() { return BossKind.LEVIATHAN; }
    @Override protected String theme() { return "punishment_57"; }
    @Override protected float meleeDamage() { return 9f; }
    @Override protected double meleeReach() { return 5.0; }

    // ---- A creature of the sea: no floating up, swims by itself, breathes water. ----

    @Override protected void initGoals() {
        goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 24f));
        goalSelector.add(9, new LookAroundGoal(this));
        targetSelector.add(1, new RevengeGoal(this));
        targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }
    @Override protected EntityNavigation createNavigation(World world) { return new SwimNavigation(this, world); }
    @Override protected int getNextAirUnderwater(int air) { return air; }
    @Override public boolean isPushedByFluids() { return false; }

    private double swimAccel() { return 0.026 + 0.006 * (phase - 1); }

    /** In water it steers itself toward {@link #swimTo}; out of water it walks (and falls) like any mob. */
    @Override public void travel(Vec3d input) {
        if (!isLogicalSideForUpdatingMovement() || !isTouchingWater()) { super.travel(input); return; }
        Vec3d velocity = getVelocity();
        if (swimTo != null && !isCasting()) {
            Vec3d to = swimTo.subtract(getPos());
            if (to.lengthSquared() > 1) velocity = velocity.add(to.normalize().multiply(swimAccel()));
        }
        if (!isSubmergedInWater()) velocity = velocity.add(0, -0.03, 0);
        move(MovementType.SELF, velocity);
        setVelocity(getVelocity().multiply(0.88));
        if (!isCasting() && velocity.horizontalLengthSquared() > 0.004) {
            float yaw = (float) (MathHelper.atan2(velocity.z, velocity.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
            setYaw(MathHelper.stepUnwrappedAngleTowards(getYaw(), yaw, 8f));
            setBodyYaw(getYaw());
        }
        updateLimbs(false);
    }

    @Override protected void pursue(LivingEntity target) {
        if (isTouchingWater()) {
            double reach = meleeReach() * 0.8;
            swimTo = squaredDistanceTo(target) > reach * reach ? target.getPos().add(0, 0.2, 0) : null;
            return;
        }
        swimTo = null;
        // On land: slow, and back to the water unless the foe is right here.
        Vec3d to = waterNear != null && squaredDistanceTo(target) > 36 ? Vec3d.ofBottomCenter(waterNear) : target.getPos();
        if (squaredDistanceTo(to) > 4) getMoveControl().moveTo(to.x, to.y, to.z, 0.5 + 0.1 * (phase - 1));
    }

    /** Posed without a mind (the showcase portrait), it rises until its back rides on the water instead of lying on the
     * bottom of the pool, the way it surfaces when it hunts. */
    @Override public void tick() {
        super.tick();
        if (!getWorld().isClient && isAiDisabled() && getWorld().getFluidState(BlockPos.ofFloored(getX(), getY() + getHeight() * 0.18, getZ())).isIn(FluidTags.WATER))
            setPosition(getX(), getY() + 0.2, getZ());
    }

    @Override protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world)) return;
        if (isCasting() || getTarget() == null) swimTo = null;
        if (age % 6 == 0) {
            Vec3d nose = mouthPos().add(0, 0.4, 0);
            particles(isSubmergedInWater() ? ParticleTypes.BUBBLE : ParticleTypes.SMOKE, nose.x, nose.y, nose.z, 2, 0.15, 0.1, 0.15, 0.01);
        }
        if (poolLeft > 0 && --poolLeft == 0) drainPool(world);
        if (poolCooldown > 0) poolCooldown--;
        if (--scanTimer <= 0) { scanTimer = 40; lookForWater(world); }
    }

    /** Out of water: remember the nearest water, or dig a pool when there is none and the settings allow it. */
    private void lookForWater(ServerWorld world) {
        waterNear = null;
        if (isTouchingWater()) return;
        for (BlockPos pos : BlockPos.iterateOutwards(getBlockPos(), 12, 4, 12))
            if (world.getFluidState(pos).isIn(FluidTags.WATER)) { waterNear = pos.toImmutable(); break; }
        if (waterNear == null && poolGround.isEmpty() && poolCooldown == 0 && getTarget() != null && isOnGround()
                && !world.getDimension().ultrawarm() && mayBreakBlocks()) digPool(world);
    }

    // ---- The temporary pool. ----

    private static boolean diggable(BlockState state) {
        if (state.hasBlockEntity() || state.isOf(Blocks.BEDROCK)) return false;
        if (!state.getFluidState().isEmpty() && !state.getFluidState().isIn(FluidTags.WATER)) return false;
        return state.isAir() || state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CLAY)
                || state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.TERRACOTTA) || state.isOf(Blocks.SNOW_BLOCK);
    }

    /** A shallow bowl of water around it (two deep in the middle, one at the rim); it drains after two minutes or at death. */
    private void digPool(ServerWorld world) {
        BlockPos base = getBlockPos();
        int r = (int) Math.ceil(POOL_RADIUS);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > POOL_RADIUS) continue;
            BlockPos top = base.add(dx, 0, dz);
            int depth = distance <= 3.4 ? 2 : 1;
            while (depth > 0 && !world.getBlockState(top.down(depth + 1)).isSolidBlock(world, top.down(depth + 1))) depth--;
            boolean ok = depth > 0;
            for (int y = 1; y <= depth && ok; y++) ok = diggable(world.getBlockState(top.down(y)));
            if (!ok) continue;
            BlockState plant = world.getBlockState(top);
            if (!plant.isAir() && plant.isReplaceable() && plant.getFluidState().isEmpty() && !plant.hasBlockEntity()) {
                poolPlants.put(top, plant);
                if (plant.contains(Properties.DOUBLE_BLOCK_HALF)) poolPlants.put(top.up(), world.getBlockState(top.up()));
            }
            for (int y = 1; y <= depth; y++) poolGround.put(top.down(y), world.getBlockState(top.down(y)));
        }
        if (poolGround.isEmpty()) { poolCooldown = 20 * 30; return; }
        for (BlockPos pos : poolPlants.keySet()) world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
        for (BlockPos pos : poolGround.keySet()) {
            world.setBlockState(pos, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
            if (random.nextInt(3) == 0) particles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 6, 0.4, 0.1, 0.4, 0.1);
        }
        poolTop = base.getY();
        poolLeft = POOL_LIFE;
        sound(SoundEvents.ENTITY_GENERIC_SPLASH, 3f, 0.5f);
        sound(SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 3f, 0.6f);
        debug("вырыл временный пруд: " + poolGround.size() + " блоков");
    }

    /** Puts the ground back where the water still is, lifts whoever is inside onto it, and the plants back on top. */
    private void drainPool(ServerWorld world) {
        if (poolGround.isEmpty() && poolPlants.isEmpty()) return;
        for (Map.Entry<BlockPos, BlockState> entry : poolGround.entrySet()) {
            BlockState now = world.getBlockState(entry.getKey());
            if (now.isAir() || now.getFluidState().isIn(FluidTags.WATER) && !now.hasBlockEntity())
                world.setBlockState(entry.getKey(), entry.getValue(), Block.NOTIFY_ALL);
        }
        for (Map.Entry<BlockPos, BlockState> entry : poolPlants.entrySet())
            if (world.getBlockState(entry.getKey()).isAir()) world.setBlockState(entry.getKey(), entry.getValue(), Block.NOTIFY_LISTENERS);
        for (LivingEntity inside : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(POOL_RADIUS + 3, 4, POOL_RADIUS + 3),
                living -> living.isAlive() && living.getY() < poolTop && poolGround.containsKey(living.getBlockPos())))
            inside.requestTeleport(inside.getX(), poolTop, inside.getZ());
        debug("пруд высох: " + poolGround.size() + " блоков");
        poolGround.clear();
        poolPlants.clear();
        poolLeft = 0;
        poolCooldown = 20 * 30;
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        double distance = distanceTo(target);
        int roll = random.nextInt(4);
        if (distance < 8 && roll == 0) tailSweep();
        else if (distance < 14 && roll == 1) bite(target);
        else if (distance < 12 && roll == 2) steam(target);
        else lamps(target);
        return phase == 1 ? 70 : phase == 2 ? 55 : 42;
    }

    /** «Бросок»: the maw opens, then it lunges at the foe (out of the water too) and bites whoever it reaches. */
    private void bite(LivingEntity target) {
        cast("Бросок Левиафана", 20, true, left -> {
            Vec3d mouth = mouthPos();
            particles(ParticleTypes.SPLASH, mouth.x, mouth.y, mouth.z, 6, 0.6, 0.3, 0.6, 0.05);
            particles(ParticleTypes.FLAME, mouth.x, mouth.y, mouth.z, 1, 0.3, 0.1, 0.3, 0.01);
            if (left == 20) sound(SoundEvents.ENTITY_RAVAGER_ROAR, 2.5f, 0.5f);
        }, () -> {
            Vec3d dir = target.getPos().add(0, target.getHeight() * 0.4, 0).subtract(getPos()).normalize();
            face(dir);
            if (isTouchingWater()) setVelocity(dir.multiply(1.15 + 0.12 * phase).add(0, isSubmergedInWater() ? 0 : 0.35, 0));
            else setVelocity(dir.x * 0.9, 0.45 + Math.max(0, dir.y) * 0.4, dir.z * 0.9);
            sound(SoundEvents.ENTITY_GUARDIAN_ATTACK, 2.5f, 0.4f);
            sound(SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 2f, 0.6f);
            Set<LivingEntity> bitten = new HashSet<>();
            hazards.repeat(1, 14, step -> {
                Vec3d mouth = mouthPos();
                particles(ParticleTypes.SPLASH, mouth.x, mouth.y, mouth.z, 4, 0.5, 0.3, 0.5, 0.1);
                for (LivingEntity victim : victims(mouth, 3.0))
                    if (bitten.add(victim) && hurt(victim, 11f)) {
                        push(victim, getPos(), 0.9, 0.3);
                        particles(ParticleTypes.CRIT, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 12, 0.4, 0.4, 0.4, 0.2);
                    }
            });
        });
    }

    /** «Удар хвоста»: the water behind it is marked, then the tail sweeps it. */
    private void tailSweep() {
        Vec3d back = getPos().subtract(forward().multiply(5.5));
        double radius = phase >= 3 ? 6.5 : 5.5;
        cast("Удар хвоста", 22, true, left -> { if (left == 22) sound(SoundEvents.ENTITY_DOLPHIN_SPLASH, 2.5f, 0.4f); }, () -> { });
        hazards.zone(back, radius, 22, ParticleTypes.SPLASH, at -> {
            particles(ParticleTypes.SWEEP_ATTACK, at.x, at.y + 1, at.z, 8, radius * 0.4, 0.4, radius * 0.4, 0);
            particles(ParticleTypes.SPLASH, at.x, at.y + 1, at.z, 60, radius * 0.5, 0.5, radius * 0.5, 0.2);
            sound(SoundEvents.ENTITY_GENERIC_SPLASH, 3f, 0.5f);
        }, victim -> { if (hurt(victim, 9f)) push(victim, back, 1.5, 0.5); });
    }

    /** «Пламенники из пасти» (Job 41:19): fire gathers in the maw, then three burning lamps fly out (five, then seven later). */
    private void lamps(LivingEntity target) {
        int count = phase == 1 ? 3 : phase == 2 ? 5 : 7;
        cast("Пламенники из пасти", 26, true, left -> {
            Vec3d mouth = mouthPos();
            particles(ParticleTypes.FLAME, mouth.x, mouth.y, mouth.z, 4, 0.3, 0.2, 0.3, 0.02);
            particles(ParticleTypes.LARGE_SMOKE, mouth.x, mouth.y + 0.4, mouth.z, 1, 0.2, 0.1, 0.2, 0.01);
            if (left == 26) sound(SoundEvents.ITEM_FIRECHARGE_USE, 2.5f, 0.5f);
        }, () -> {
            Vec3d mouth = mouthPos();
            Vec3d aim = target.getEyePos().subtract(mouth).normalize();
            for (int i = 0; i < count; i++) {
                double spread = (i - (count - 1) / 2.0) * 0.16;
                Vec3d dir = new Vec3d(aim.x * Math.cos(spread) - aim.z * Math.sin(spread), aim.y, aim.x * Math.sin(spread) + aim.z * Math.cos(spread));
                hazards.orb(mouth, dir.multiply(0.5), 60, 0.5, ParticleTypes.FLAME, victim -> {
                    if (hurt(victim, 6f)) victim.setOnFireFor(4);
                    particles(ParticleTypes.LAVA, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
                });
            }
            sound(SoundEvents.ENTITY_BLAZE_SHOOT, 2.5f, 0.5f);
        });
    }

    /** «Дым из ноздрей» (Job 41:20): smoke pours from its nostrils, then a scalding cloud stands where the foe is. */
    private void steam(LivingEntity target) {
        Vec3d mouth = mouthPos();
        Vec3d toward = target.getPos().add(0, 1, 0).subtract(mouth);
        Vec3d cloud = mouth.add(toward.normalize().multiply(Math.min(toward.length(), 7)));
        cast("Дым из ноздрей", 22, true, left -> {
            Vec3d nose = mouthPos().add(0, 0.4, 0);
            particles(ParticleTypes.LARGE_SMOKE, nose.x, nose.y, nose.z, 3, 0.2, 0.1, 0.2, 0.03);
            if (left % 4 == 0) particles(ParticleTypes.CLOUD, cloud.x, cloud.y - 0.8, cloud.z, 6, 2, 0.1, 2, 0);
            if (left == 22) sound(SoundEvents.BLOCK_FIRE_EXTINGUISH, 2.5f, 0.5f);
        }, () -> {
            sound(SoundEvents.BLOCK_FIRE_EXTINGUISH, 3f, 0.4f);
            Set<LivingEntity> scalded = new HashSet<>();
            hazards.repeat(4, 15, step -> {
                particles(ParticleTypes.CLOUD, cloud.x, cloud.y, cloud.z, 30, 2.2, 1.4, 2.2, 0.02);
                particles(ParticleTypes.LARGE_SMOKE, cloud.x, cloud.y, cloud.z, 10, 2, 1.2, 2, 0.01);
                for (LivingEntity victim : victims(cloud, 4)) {
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 50, 0, false, true));
                    if (scalded.add(victim)) hurt(victim, 4f);
                }
            });
        });
    }

    // ---- The ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        Vec3d center = getPos();
        boolean water = isTouchingWater();
        double surface = water ? surfaceAbove(center) : center.y;
        castUltimate(left -> {
            // The water churns on the edge of the coming whirlpool, bubbles rise inside it.
            ParticleEffect churn = water ? ParticleTypes.SPLASH : sand();
            for (int i = 0; i < 24; i++) {
                double angle = age * 0.15 + i * Math.PI * 2 / 24;
                particles(churn, center.x + Math.cos(angle) * WHIRL, surface + 0.2, center.z + Math.sin(angle) * WHIRL, 2, 0.2, 0.1, 0.2, 0.05);
            }
            for (int i = 0; i < 4; i++) {
                double angle = random.nextDouble() * Math.PI * 2, r = random.nextDouble() * WHIRL;
                double x = center.x + Math.cos(angle) * r, z = center.z + Math.sin(angle) * r;
                if (water) {
                    for (double y = center.y; y < surface; y += 0.8) particles(ParticleTypes.BUBBLE_COLUMN_UP, x, y, z, 1, 0.1, 0.1, 0.1, 0);
                    particles(ParticleTypes.BUBBLE_POP, x, surface, z, 3, 0.3, 0, 0.3, 0.02);
                } else particles(ParticleTypes.CLOUD, x, surface + 0.3, z, 2, 0.3, 0.3, 0.3, 0.02);
            }
            if (left % 20 == 0) sound(SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 3f, 0.5f);
        }, () -> abyss(center, water, surface));
    }

    /** «Пучина»: eight seconds of whirlpool; everyone in it is drawn to the middle and down, and the deep lays its weight on them. */
    private void abyss(Vec3d center, boolean water, double surface) {
        sound(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 2.5f, 0.5f);
        sound(SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 3f, 0.4f);
        Set<LivingEntity> caught = new HashSet<>();
        double strength = 1 + 0.15 * (phase - 1);
        debug("Пучина: " + (water ? "водоворот" : "вихрь песка"));
        hazards.repeat(2, 80, step -> {
            for (int i = 0; i < 30; i++) {
                double r = (i % 10 + 1) * WHIRL / 10, angle = step * 0.4 + i * 0.7 - r * 0.15;
                double y = water ? surface - (1 - r / WHIRL) * 3 : center.y + 0.3 + (i % 3) * 0.6;
                particles(water ? (i % 3 == 0 ? ParticleTypes.BUBBLE_COLUMN_UP : ParticleTypes.SPLASH) : (i % 2 == 0 ? sand() : ParticleTypes.CLOUD),
                        center.x + Math.cos(angle) * r, y, center.z + Math.sin(angle) * r, 1, 0.1, 0.1, 0.1, 0.02);
            }
            if (step % 20 == 0) sound(SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 3f, 0.5f);
            for (LivingEntity victim : victims(center, WHIRL)) {
                Vec3d to = center.subtract(victim.getPos());
                double flat = Math.hypot(to.x, to.z);
                Vec3d in = flat < 0.5 ? Vec3d.ZERO : new Vec3d(to.x / flat, 0, to.z / flat);
                double pull = (0.08 + 0.05 * (1 - flat / WHIRL)) * strength;
                double down = water ? (victim.isTouchingWater() ? -0.12 : -0.03) : 0;
                // Toward the middle, around it, and down.
                victim.addVelocity(in.x * pull - in.z * 0.06, down, in.z * pull + in.x * 0.06);
                victim.velocityModified = true;
                if (water && victim.isSubmergedInWater()) victim.setAir(Math.max(0, victim.getAir() - 6));
                if (caught.add(victim)) {
                    BossEffects.afflict(victim, BossEffects.DEPTHS, 20 * 15);
                    if (!water) victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 4, 1));
                    victim.sendMessage(Text.literal("Тяжесть глубин: пучина тянет на дно").formatted(Formatting.DARK_AQUA));
                }
                if (step % 10 == 0 && flat < 3) hurt(victim, 5f);
            }
        });
    }

    private double surfaceAbove(Vec3d center) {
        BlockPos pos = BlockPos.ofFloored(center);
        for (int i = 0; i < 16 && getWorld().getFluidState(pos.up()).isIn(FluidTags.WATER); i++) pos = pos.up();
        return pos.getY() + 0.9;
    }

    private static ParticleEffect sand() { return new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.SAND.getDefaultState()); }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.4f);
        particles(ParticleTypes.SPLASH, getX(), getY() + 1.5, getZ(), 80, 2, 1, 2, 0.3);
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() {
        if (getWorld() instanceof ServerWorld world) drainPool(world);
        for (LivingEntity victim : victims(getPos(), 32)) victim.removeStatusEffect(BossEffects.DEPTHS);
    }

    @Override public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && getWorld() instanceof ServerWorld world) drainPool(world);
        super.remove(reason);
    }

    @Override public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (poolGround.isEmpty()) return;
        nbt.put("LeviathanPool", writeBlocks(poolGround));
        nbt.put("LeviathanPlants", writeBlocks(poolPlants));
        nbt.putInt("LeviathanPoolLeft", poolLeft);
        nbt.putInt("LeviathanPoolTop", poolTop);
    }
    @Override public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        readBlocks(nbt.getList("LeviathanPool", NbtElement.COMPOUND_TYPE), poolGround);
        readBlocks(nbt.getList("LeviathanPlants", NbtElement.COMPOUND_TYPE), poolPlants);
        poolLeft = poolGround.isEmpty() ? 0 : Math.max(1, nbt.getInt("LeviathanPoolLeft"));
        poolTop = nbt.getInt("LeviathanPoolTop");
    }
    private static NbtList writeBlocks(Map<BlockPos, BlockState> blocks) {
        NbtList list = new NbtList();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            NbtCompound one = new NbtCompound();
            one.putLong("Pos", entry.getKey().asLong());
            one.put("State", NbtHelper.fromBlockState(entry.getValue()));
            list.add(one);
        }
        return list;
    }
    private static void readBlocks(NbtList list, Map<BlockPos, BlockState> into) {
        into.clear();
        for (int i = 0; i < list.size(); i++) {
            NbtCompound one = list.getCompound(i);
            into.put(BlockPos.fromLong(one.getLong("Pos")), NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), one.getCompound("State")));
        }
    }

    // ---- Where its parts are. ----

    private Vec3d forward() { return Vec3d.fromPolar(0, getBodyYaw()); }
    /** The maw: about four blocks ahead of its middle (the head reaches past the hitbox), at the height of the jaws. */
    private Vec3d mouthPos() { Vec3d f = forward(); return getPos().add(f.x * 3.9, getHeight() * 0.42, f.z * 3.9); }
    private void face(Vec3d dir) {
        float yaw = (float) (MathHelper.atan2(dir.z, dir.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        setYaw(yaw); setBodyYaw(yaw); setHeadYaw(yaw);
    }
}
