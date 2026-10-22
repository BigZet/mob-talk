package io.github.rusflora.hogweed;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShovelItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/** Sosnowsky's hogweed, one block per metre of height. STAGE: 0 seedling, 1 rosette, 2 big rosette (two blocks), 3 flowering,
 * 4 ripe seed heads, 5 dead dry stalk (three blocks each). PART counts from the ground: 0 the root with leaves, 1 the stalk,
 * 2 the umbel. Only the root part ticks; it grows the plant, smothers what grows around it and, once the seeds are ripe,
 * throws them about, so a single plant turns a meadow into a thicket. Like the real one it flowers once and dies.
 * Cutting the stalk leaves the rosette, breaking the root without a shovel leaves a seedling: only digging it out kills it. */
public class HogweedBlock extends Block implements Fertilizable {
    /** Block updates for the client only: no neighbour or shape updates (which would break loose parts off as items), no drops. */
    private static final int QUIET = NOTIFY_LISTENERS | SKIP_DROPS | FORCE_STATE;
    public static final int SEEDLING = 0, ROSETTE = 1, BIG = 2, FLOWERING = 3, SEEDING = 4, DRY = 5;
    public static final IntProperty STAGE = IntProperty.of("stage", 0, 5);
    public static final IntProperty PART = IntProperty.of("part", 0, 2);
    private static final VoxelShape SEEDLING_SHAPE = createCuboidShape(4, 0, 4, 12, 6, 12);
    private static final VoxelShape ROSETTE_SHAPE = createCuboidShape(1, 0, 1, 15, 10, 15);
    private static final VoxelShape STALK_SHAPE = createCuboidShape(5, 0, 5, 11, 16, 11);
    private static final VoxelShape FULL_SHAPE = createCuboidShape(1, 0, 1, 15, 16, 15);

    public HogweedBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(STAGE, SEEDLING).with(PART, 0));
    }

    @Override protected MapCodec<? extends Block> getCodec() { return createCodec(HogweedBlock::new); }
    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(STAGE, PART); }

    /** Blocks the plant stands on at a stage. */
    public static int height(int stage) { return stage <= ROSETTE ? 1 : stage == BIG ? 2 : 3; }

    public BlockState state(int stage, int part) { return getDefaultState().with(STAGE, stage).with(PART, part); }

    @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int stage = state.get(STAGE), part = state.get(PART);
        if (stage == SEEDLING) return SEEDLING_SHAPE;
        if (stage == ROSETTE) return ROSETTE_SHAPE;
        if (stage == DRY || part == 1) return STALK_SHAPE;
        return FULL_SHAPE;
    }

    // ---- Standing and falling apart ----

    public static boolean isSoil(BlockState ground) { return ground.isIn(BlockTags.DIRT) || ground.isOf(Blocks.FARMLAND); }

    @Override protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        if (state.get(PART) == 0) return isSoil(world.getBlockState(pos.down()));
        BlockState below = world.getBlockState(pos.down());
        return below.isOf(this) && below.get(PART) == state.get(PART) - 1;
    }

    @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor, WorldAccess world,
                                                             BlockPos pos, BlockPos neighborPos) {
        if (!canPlaceAt(state, world, pos)) return Blocks.AIR.getDefaultState();
        int stage = state.get(STAGE), part = state.get(PART);
        if (direction == Direction.UP && part < height(stage) - 1 && !(neighbor.isOf(this) && neighbor.get(PART) == part + 1)) {
            // The stalk above was cut: a living root puts out a rosette again, a dead one has nothing left.
            if (stage == DRY) return Blocks.AIR.getDefaultState();
            return part == 0 ? state(ROSETTE, 0) : Blocks.AIR.getDefaultState();
        }
        return state;
    }

    /** The root grows back from what is left in the ground unless the plant was dug out with a shovel or had died. */
    @Override public void afterBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool) {
        super.afterBreak(world, player, pos, state, blockEntity, tool);
        if (world.isClient) return;
        int stage = state.get(STAGE);
        if (stage >= ROSETTE && !tool.isOf(Items.SHEARS)) Hogweed.splashSap(player);
        if (state.get(PART) == 0 && stage != DRY && stage != SEEDLING && !(tool.getItem() instanceof ShovelItem)
                && world.getBlockState(pos).isAir() && isSoil(world.getBlockState(pos.down())))
            world.setBlockState(pos, state(SEEDLING, 0), NOTIFY_ALL);
    }

    // ---- Touching it ----

    @Override protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity living) || state.get(STAGE) == SEEDLING) return;
        entity.slowMovement(state, new Vec3d(0.8, 0.85, 0.8));
        if (!world.isClient && state.get(STAGE) != DRY) Hogweed.splashSap(living);
    }

    // ---- Life ----

    @Override protected boolean hasRandomTicks(BlockState state) { return state.get(PART) == 0; }

    @Override protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        HogweedConfig config = HogweedConfig.get();
        int stage = state.get(STAGE);
        if (mowed(world, pos)) return;
        if (stage >= ROSETTE && stage <= SEEDING) smother(world, pos, stage == ROSETTE ? 1 : 2, random);
        if (stage >= SEEDING) scatterSeeds(world, pos, random, stage == SEEDING ? config.seedsPerTick : 1);
        if (stage == DRY) {
            if (random.nextInt(14) == 0) removePlant(world, pos);
            return;
        }
        // It grows only by day and under open sky: at night, under a roof or a dense canopy it stands still, and a seedling
        // in deep shade slowly dies.
        if (!sunlit(world, pos, stage)) {
            if (stage == SEEDLING && world.isDay() && random.nextInt(12) == 0) removePlant(world, pos);
            return;
        }
        // Chance per tick to move to the next stage: seedlings rise fast, a rosette takes its time, the flowers are short-lived.
        double chance = switch (stage) { case SEEDLING -> 0.25; case ROSETTE -> 0.12; case BIG -> 0.08; case FLOWERING -> 0.2; default -> 0.15; };
        if (random.nextDouble() < chance * config.growthSpeed) advance(world, pos, state);
    }

    /** Daylight with nothing solid above the plant: no roof, no tree crown (leaves count). Read from the heightmap, which is
     * exact at once and cheaper than the light engine. */
    public static boolean sunlit(ServerWorld world, BlockPos root, int stage) {
        return world.isDay() && openSky(world, root);
    }

    public static boolean openSky(WorldView world, BlockPos pos) {
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ()) <= pos.getY();
    }

    /** Villagers keep their village clear: a plant with a villager within 16 blocks is mowed down to the ground. */
    private boolean mowed(ServerWorld world, BlockPos pos) {
        if (!HogweedConfig.get().villagersMow) return false;
        if (world.getEntitiesByClass(VillagerEntity.class, new Box(pos).expand(16, 6, 16), LivingEntity::isAlive).isEmpty()) return false;
        removePlant(world, pos);
        world.playSound(null, pos, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.BLOCKS, 0.6f, 1.1f);
        return true;
    }

    /** Takes the whole plant away from its root up, without dropping anything (eaten, mowed, rotted away). */
    public void removePlant(WorldAccess world, BlockPos root) {
        BlockState state = world.getBlockState(root);
        int height = state.isOf(this) ? height(state.get(STAGE)) : 1;
        for (int part = height - 1; part >= 0; part--)
            if (world.getBlockState(root.up(part)).isOf(this)) world.setBlockState(root.up(part), Blocks.AIR.getDefaultState(), QUIET);
        if (world instanceof ServerWorld server) ThicketLimits.removed(server, root);
    }

    /** Moves the plant to its next stage if there is room above. True if it changed. */
    public boolean advance(ServerWorld world, BlockPos pos, BlockState state) {
        int stage = state.get(STAGE);
        if (stage == DRY) return false;
        int next = stage + 1, have = height(stage), need = height(next);
        for (int part = have; part < need; part++) if (!roomFor(world, pos.up(part))) return false;
        setStage(world, pos, next);
        return true;
    }

    /** Rewrites all parts for a stage, from the top down so no part is ever left without the one below. */
    public void setStage(WorldAccess world, BlockPos root, int stage) {
        for (int part = height(stage) - 1; part >= 0; part--) world.setBlockState(root.up(part), state(stage, part), NOTIFY_LISTENERS);
    }

    public static boolean roomFor(WorldView world, BlockPos pos) {
        BlockState there = world.getBlockState(pos);
        return (there.isAir() || there.isReplaceable() && !there.isOf(Hogweed.BLOCK)) && world.getFluidState(pos).isEmpty();
    }

    /** One random spot in the shade around the plant loses its grass, flowers, sapling or crop. The bare ground is soon seeded.
     * Nothing is dropped: a spreading thicket must not litter the ground with items. */
    private void smother(ServerWorld world, BlockPos pos, int radius, Random random) {
        HogweedConfig config = HogweedConfig.get();
        if (!config.suppressPlants) return;
        int dx = random.nextBetween(-radius, radius), dz = random.nextBetween(-radius, radius);
        if (dx == 0 && dz == 0) return;
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos at = pos.add(dx, dy, dz);
            if (!world.isChunkLoaded(at)) return;
            BlockState there = world.getBlockState(at);
            if (there.isOf(this)) return;
            if (there.isIn(BlockTags.SAPLINGS)) { world.setBlockState(at, Blocks.DEAD_BUSH.getDefaultState(), QUIET); return; }
            if (there.getBlock() instanceof CropBlock crop) {
                if (!config.suppressCrops) return;
                int age = crop.getAge(there);
                world.setBlockState(at, age > 0 && random.nextBoolean() ? crop.withAge(age - 1) : Blocks.AIR.getDefaultState(), QUIET);
                return;
            }
            if (there.isOf(Blocks.SHORT_GRASS) || there.isOf(Blocks.FERN) || there.isOf(Blocks.TALL_GRASS) || there.isOf(Blocks.LARGE_FERN)
                    || there.isIn(BlockTags.SMALL_FLOWERS) || there.isIn(BlockTags.TALL_FLOWERS)) {
                clear(world, at, there);
                return;
            }
            if (there.isOf(Blocks.GRASS_BLOCK) && world.getBlockState(at.up()).isAir() && random.nextInt(3) == 0) {
                world.setBlockState(at, Blocks.DIRT.getDefaultState(), QUIET);
                return;
            }
        }
    }

    /** Removes a plant without drops; for a two-block plant the upper half goes first, so it never breaks off as an item. */
    private static void clear(ServerWorld world, BlockPos at, BlockState there) {
        if (there.contains(Properties.DOUBLE_BLOCK_HALF)) {
            BlockPos lower = there.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? at : at.down();
            world.setBlockState(lower.up(), Blocks.AIR.getDefaultState(), QUIET);
            world.setBlockState(lower, Blocks.AIR.getDefaultState(), QUIET);
        } else {
            world.setBlockState(at, Blocks.AIR.getDefaultState(), QUIET);
        }
    }

    /** Throws seeds around; each lands on the surface and sprouts if the ground suits and the thicket there is not full yet.
     * Only already loaded chunks are touched, and every check is a handful of block reads. */
    public void scatterSeeds(ServerWorld world, BlockPos pos, Random random, int seeds) {
        HogweedConfig config = HogweedConfig.get();
        if (!config.spread) return;
        int radius = config.spreadRadius;
        if (random.nextInt(3) == 0 && nearWater(world, pos)) radius *= 2;
        for (int i = 0; i < seeds; i++) {
            int dx = Math.round((random.nextFloat() - random.nextFloat()) * radius), dz = Math.round((random.nextFloat() - random.nextFloat()) * radius);
            if (dx == 0 && dz == 0) continue;
            BlockPos landing = surface(world, pos.add(dx, 0, dz));
            if (landing == null || !openSky(world, landing) || crowd(world, landing) >= config.maxPlantsNearby || !ThicketLimits.allows(world, landing))
                continue;
            BlockState there = world.getBlockState(landing);
            if (!there.isAir()) clear(world, landing, there);
            world.setBlockState(landing, getDefaultState(), NOTIFY_LISTENERS);
            ThicketLimits.added(world, landing);
        }
    }

    /** The free spot right above soil within four blocks up or down, or null. */
    private BlockPos surface(ServerWorld world, BlockPos around) {
        if (!world.isChunkLoaded(around)) return null;
        BlockPos.Mutable at = around.mutableCopy().move(0, 4, 0);
        for (int i = 0; i < 9; i++, at.move(Direction.DOWN)) {
            if (roomFor(world, at) && isSoil(world.getBlockState(at.down()))) return at.toImmutable();
            if (!world.getFluidState(at).isEmpty()) return null;
        }
        return null;
    }

    /** Hogweed roots in the 7x7 square around pos. */
    public int crowd(WorldView world, BlockPos pos) {
        int count = 0;
        for (BlockPos at : BlockPos.iterate(pos.add(-3, -2, -3), pos.add(3, 2, 3))) {
            BlockState state = world.getBlockState(at);
            if (state.isOf(this) && state.get(PART) == 0) count++;
        }
        return count;
    }

    private static boolean nearWater(WorldView world, BlockPos pos) {
        for (BlockPos at : BlockPos.iterate(pos.add(-3, -2, -3), pos.add(3, 0, 3)))
            if (world.getFluidState(at).isIn(FluidTags.WATER)) return true;
        return false;
    }

    // ---- Bone meal ----

    @Override public boolean isFertilizable(WorldView world, BlockPos pos, BlockState state) { return state.get(STAGE) < DRY; }
    @Override public boolean canGrow(World world, Random random, BlockPos pos, BlockState state) { return true; }
    @Override public void grow(ServerWorld world, Random random, BlockPos pos, BlockState state) {
        BlockPos root = pos.down(state.get(PART));
        advance(world, root, world.getBlockState(root));
    }
}
