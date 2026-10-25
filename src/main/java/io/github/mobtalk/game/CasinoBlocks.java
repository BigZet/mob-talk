package io.github.mobtalk.game;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import io.github.mobtalk.server.ServerConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The casino's blocks (models and textures from tools/casino/decor.py). All face whoever places them: the model's front is its north
 * side. The big tables fill their other cells with invisible {@link Part}s, so they can be walked against and broken anywhere. */
public final class CasinoBlocks {
    private CasinoBlocks() { }

    /** What a right click on the block does (handled by {@link CasinoLife} on the server). */
    public enum Use { NONE, BLACKJACK, ROULETTE, POKER, SEAT }

    /** Quarter turns clockwise from north, the way the blockstate turns the model. */
    static int turns(Direction facing) { return (facing.getHorizontal() + 2) % 4; }

    /** A cell offset given in the model's frame (x east, z south, facing north) as a world offset for this facing. */
    static BlockPos cell(BlockPos origin, Direction facing, int dx, int dz) {
        int x = dx, z = dz;
        for (int i = 0; i < turns(facing); i++) { int t = x; x = -z; z = t; }
        return origin.add(x, 0, z);
    }

    /** Boxes in pixels for a north-facing model, turned for each facing. */
    static Map<Direction, VoxelShape> shapes(double[]... boxes) {
        Map<Direction, VoxelShape> out = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Type.HORIZONTAL) {
            VoxelShape shape = VoxelShapes.empty();
            for (double[] b : boxes) {
                double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
                for (int i = 0; i < turns(facing); i++) {
                    double nx0 = 16 - z1, nx1 = 16 - z0, nz0 = x0, nz1 = x1;
                    x0 = nx0; x1 = nx1; z0 = nz0; z1 = nz1;
                }
                shape = VoxelShapes.union(shape, Block.createCuboidShape(x0, b[1], z0, x1, b[4], z1));
            }
            out.put(facing, shape);
        }
        return out;
    }

    /** One piece of furniture. {@code cells} are the other cells it fills, in the model's frame ({x, z} pairs). */
    public static class Decor extends HorizontalFacingBlock {
        private final Map<Direction, VoxelShape> outline, collision;
        final int[][] cells;
        final Use use;
        private final boolean onWall;

        public Decor(Settings settings, Map<Direction, VoxelShape> outline, Map<Direction, VoxelShape> collision, int[][] cells, Use use, boolean onWall) {
            super(settings);
            this.outline = outline;
            this.collision = collision;
            this.cells = cells;
            this.use = use;
            this.onWall = onWall;
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
        }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return MapCodec.unit(this); }

        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction facing;
            if (onWall) {
                Direction side = ctx.getSide();
                facing = side.getAxis().isHorizontal() ? side : ctx.getHorizontalPlayerFacing().getOpposite();
            } else facing = ctx.getHorizontalPlayerFacing().getOpposite();
            for (BlockPos at : others(ctx.getBlockPos(), facing))
                if (!ctx.getWorld().getBlockState(at).canReplace(ctx) || !ctx.getWorld().getWorldBorder().contains(at)) return null;
            return getDefaultState().with(FACING, facing);
        }

        /** The cells beside the block's own that this piece fills. */
        List<BlockPos> others(BlockPos pos, Direction facing) {
            List<BlockPos> out = new ArrayList<>();
            for (int[] c : cells) out.add(cell(pos, facing, c[0], c[1]));
            return out;
        }

        @Override public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            if (world.isClient) return;
            for (BlockPos at : others(pos, state.get(FACING))) world.setBlockState(at, CasinoContent.PART.getDefaultState(), Block.NOTIFY_ALL);
        }

        @Override protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.isOf(newState.getBlock()) && !world.isClient)
                for (BlockPos at : others(pos, state.get(FACING)))
                    if (world.getBlockState(at).isOf(CasinoContent.PART)) world.setBlockState(at, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            super.onStateReplaced(state, world, pos, newState, moved);
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (use == Use.NONE) return ActionResult.PASS;
            if (!world.isClient && player instanceof ServerPlayerEntity server) CasinoLife.use(server, pos, state, use);
            return ActionResult.success(world.isClient);
        }

        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return outline.get(state.get(FACING));
        }
        @Override public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
            sparkle(this, outline.get(state.get(FACING)), world, pos, random);
        }
        @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return collision.get(state.get(FACING));
        }
    }

    /** A cell of a neon letter of a casino sign (the letters of «КАЗИНО» and «ФОРТУНА», each two blocks wide and two tall, and a gold star
     * one block wide between the words). {@code part}: 0 top left, 1 top right, 2 bottom left, 3 bottom right. A right click turns
     * to the next letter, a right click while sneaking to the next cell of it. */
    public static class Letter extends Decor {
        public static final IntProperty LETTER = IntProperty.of("letter", 0, 10);
        public static final IntProperty PART = IntProperty.of("part", 0, 3);
        public Letter(Settings settings, Map<Direction, VoxelShape> shape) {
            super(settings, shape, shape, new int[0][], Use.NONE, true);
            setDefaultState(getDefaultState().with(LETTER, 0).with(PART, 0));
        }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, LETTER, PART); }
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (!world.isClient) world.setBlockState(pos, state.cycle(player.isSneaking() ? PART : LETTER), Block.NOTIFY_ALL);
            return ActionResult.success(world.isClient);
        }
    }

    /** A glint of light on one of the casino's lights ({@link CasinoContent#SPARKLE}): now and then a spark appears at a random point
     * of the block's shape and fades. A look only, switched off by {@code casinoSparkles}. */
    static void sparkle(Block block, VoxelShape shape, World world, BlockPos pos, Random random) {
        Float chance = CasinoContent.SPARKLE.get(block);
        if (chance == null || shape.isEmpty() || !ServerConfig.get().casinoSparkles) return;
        for (int i = 0; i < 2; i++) {
            if (random.nextFloat() > chance) continue;
            Box b = shape.getBoundingBox();
            double x = pos.getX() + b.minX - 0.05 + random.nextDouble() * (b.getLengthX() + 0.1);
            double y = pos.getY() + b.minY + random.nextDouble() * b.getLengthY();
            double z = pos.getZ() + b.minZ - 0.05 + random.nextDouble() * (b.getLengthZ() + 0.1);
            world.addParticle(random.nextInt(4) == 0 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.WAX_OFF, x, y, z, 0, 0, 0);
        }
    }

    /** The jackpot board over a bank of slot machines: three blocks wide ({@code part} 0 to 2, from the viewer's left), on a wall.
     * A board placed to the right of another one's left or middle part continues it; a right click turns to the next part. */
    public static class Jackpot extends Decor {
        public static final IntProperty PART = IntProperty.of("part", 0, 2);
        public Jackpot(Settings settings, Map<Direction, VoxelShape> shape) {
            super(settings, shape, shape, new int[0][], Use.NONE, true);
            setDefaultState(getDefaultState().with(PART, 0));
        }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, PART); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState state = super.getPlacementState(ctx);
            if (state == null) return null;
            Direction facing = state.get(FACING);
            BlockState left = ctx.getWorld().getBlockState(ctx.getBlockPos().offset(facing.rotateYClockwise()));
            if (left.isOf(this) && left.get(FACING) == facing && left.get(PART) < 2) state = state.with(PART, left.get(PART) + 1);
            return state;
        }
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (!world.isClient) world.setBlockState(pos, state.cycle(PART), Block.NOTIFY_ALL);
            return ActionResult.success(world.isClient);
        }
    }

    /** A fluted column: a base where it stands on something else, a capital where nothing of it is above, the shaft between. */
    public static class Column extends Block {
        public enum Piece implements StringIdentifiable {
            BASE, SHAFT, CAPITAL;
            @Override public String asString() { return name().toLowerCase(java.util.Locale.ROOT); }
        }
        public static final EnumProperty<Piece> PIECE = EnumProperty.of("part", Piece.class);
        public Column(Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(PIECE, Piece.CAPITAL));
        }
        @Override protected MapCodec<? extends Block> getCodec() { return MapCodec.unit(this); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(PIECE); }
        private BlockState shaped(BlockState state, WorldView world, BlockPos pos) {
            boolean above = world.getBlockState(pos.up()).isOf(this), below = world.getBlockState(pos.down()).isOf(this);
            return state.with(PIECE, !above ? Piece.CAPITAL : below ? Piece.SHAFT : Piece.BASE);
        }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) { return shaped(getDefaultState(), ctx.getWorld(), ctx.getBlockPos()); }
        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            return direction.getAxis() == Direction.Axis.Y ? shaped(state, world, pos) : state;
        }
    }

    /** A piece of a palm's trunk. {@code bend} sets it over towards its facing's right in steps of 1.5 pixels, so a trunk whose
     * pieces lean more and then less as it rises curves. A piece placed on a trunk takes its facing and bend; a right click bends
     * it one step further (round to straight again). */
    public static class Palm extends HorizontalFacingBlock {
        public static final IntProperty BEND = IntProperty.of("bend", 0, 3);
        private final List<Map<Direction, VoxelShape>> shapes = new ArrayList<>();
        private final boolean solid;
        public Palm(Settings settings, double[] box, boolean solid) {
            super(settings);
            this.solid = solid;
            for (int b = 0; b <= 3; b++)
                shapes.add(shapes(new double[]{box[0] + b * 1.5, box[1], box[2], box[3] + b * 1.5, box[4], box[5]}));
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(BEND, 0));
        }
        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return MapCodec.unit(this); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, BEND); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState below = ctx.getWorld().getBlockState(ctx.getBlockPos().down());
            if (below.getBlock() instanceof Palm) return getDefaultState().with(FACING, below.get(FACING)).with(BEND, below.get(BEND));
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (!world.isClient) world.setBlockState(pos, state.cycle(BEND), Block.NOTIFY_ALL);
            return ActionResult.success(world.isClient);
        }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return shapes.get(state.get(BEND)).get(state.get(FACING));
        }
        @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return solid ? getOutlineShape(state, world, pos, context) : VoxelShapes.empty();
        }
    }

    /** A jet of a fountain, two blocks tall, standing in the pool's water: splashes at its top and where it falls. */
    public static class FountainJet extends Block implements net.minecraft.block.Waterloggable {
        public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
        private static final VoxelShape SHAPE = Block.createCuboidShape(5, 0, 5, 11, 16, 11);
        public FountainJet(Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(WATERLOGGED, false));
        }
        @Override protected MapCodec<? extends Block> getCodec() { return MapCodec.unit(this); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(WATERLOGGED); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(WATERLOGGED, ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER);
        }
        @Override protected FluidState getFluidState(BlockState state) {
            return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
        }
        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            if (state.get(WATERLOGGED)) world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
            return state;
        }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
        @Override public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
            for (int i = 0; i < 3; i++) {
                double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.9, z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.9;
                world.addParticle(ParticleTypes.SPLASH, x, pos.getY() + 1.9 + random.nextDouble() * 0.2, z, 0, 0.1, 0);
                world.addParticle(i == 0 ? ParticleTypes.FALLING_WATER : ParticleTypes.SPLASH, x, pos.getY() + (i == 0 ? 1.8 : 1.02), z, 0, 0, 0);
            }
        }
    }

    /** An invisible filler cell of a big table: breaking or using it breaks or uses the table. */
    public static class Part extends Block {
        private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 15, 16);
        public Part(Settings settings) { super(settings); }
        @Override protected MapCodec<? extends Block> getCodec() { return MapCodec.unit(this); }
        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.INVISIBLE; }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }

        /** The table this cell belongs to: the nearest piece whose cells include it. */
        static BlockPos owner(BlockView world, BlockPos pos) {
            for (BlockPos at : BlockPos.iterateOutwards(pos, 2, 0, 2)) {
                BlockState state = world.getBlockState(at);
                if (state.getBlock() instanceof Decor decor && decor.others(at.toImmutable(), state.get(HorizontalFacingBlock.FACING)).contains(pos))
                    return at.toImmutable();
            }
            return null;
        }

        @Override public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
            BlockPos owner = owner(world, pos);
            if (owner != null && !world.isClient) world.breakBlock(owner, !player.isCreative(), player);
            return super.onBreak(world, pos, state, player);
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            BlockPos owner = owner(world, pos);
            if (owner == null) return ActionResult.PASS;
            BlockState table = world.getBlockState(owner);
            return table.getBlock() instanceof Decor decor ? decor.onUse(table, world, owner, player, hit.withBlockPos(owner)) : ActionResult.PASS;
        }
    }

    /** The slot machine: two blocks tall, the cabinet below and the reels with the lever above. */
    public static class SlotMachine extends HorizontalFacingBlock {
        public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;
        private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 16, 15);
        public SlotMachine(Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER));
        }
        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return MapCodec.unit(this); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, HALF); }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockPos pos = ctx.getBlockPos();
            World world = ctx.getWorld();
            if (pos.getY() >= world.getTopY() - 1 || !world.getBlockState(pos.up()).canReplace(ctx)) return null;
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }
        @Override public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
        }
        @Override protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
            if (state.get(HALF) == DoubleBlockHalf.LOWER) return true;
            BlockState below = world.getBlockState(pos.down());
            return below.isOf(this) && below.get(HALF) == DoubleBlockHalf.LOWER;
        }
        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            DoubleBlockHalf half = state.get(HALF);
            if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)
                    && !(neighbor.isOf(this) && neighbor.get(HALF) != half)) return Blocks.AIR.getDefaultState();
            return super.getStateForNeighborUpdate(state, direction, neighbor, world, pos, neighborPos);
        }
        /** Breaking the top breaks the cabinet too, which drops the machine (the top drops nothing). */
        @Override public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
            if (!world.isClient && state.get(HALF) == DoubleBlockHalf.UPPER) {
                BlockPos below = pos.down();
                BlockState lower = world.getBlockState(below);
                if (lower.isOf(this) && lower.get(HALF) == DoubleBlockHalf.LOWER) {
                    if (player.isCreative()) world.setBlockState(below, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
                    else world.breakBlock(below, true, player);
                }
            }
            return super.onBreak(world, pos, state, player);
        }
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (!world.isClient && player instanceof ServerPlayerEntity server)
                CasinoLife.spin(server, state.get(HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos);
            return ActionResult.success(world.isClient);
        }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
        @Override public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
            if (state.get(HALF) == DoubleBlockHalf.UPPER) sparkle(this, SHAPE, world, pos, random);
        }
    }

    /** Chips left lying about: one to four stacks; placing chips on chips adds a stack. */
    public static class Chips extends HorizontalFacingBlock {
        public static final IntProperty CHIPS = IntProperty.of("chips", 1, 4);
        private static final VoxelShape SHAPE = Block.createCuboidShape(3, 0, 3, 13, 9, 13);
        public Chips(Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(CHIPS, 1));
        }
        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return MapCodec.unit(this); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, CHIPS); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState here = ctx.getWorld().getBlockState(ctx.getBlockPos());
            if (here.isOf(this)) return here.with(CHIPS, Math.min(4, here.get(CHIPS) + 1));
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }
        @Override protected boolean canReplace(BlockState state, ItemPlacementContext ctx) {
            return !ctx.shouldCancelInteraction() && ctx.getStack().isOf(asItem()) && state.get(CHIPS) < 4 || super.canReplace(state, ctx);
        }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
    }
}
