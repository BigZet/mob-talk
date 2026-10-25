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
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
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
        @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return collision.get(state.get(FACING));
        }
    }

    /** A cell of a neon letter of the grand casino's facade sign («КАЗИНО», each letter two blocks wide and two tall, and a gold star
     * one block wide between the words). {@code part}: 0 top left, 1 top right, 2 bottom left, 3 bottom right. A right click turns
     * to the next letter, a right click while sneaking to the next cell of it. */
    public static class Letter extends Decor {
        public static final IntProperty LETTER = IntProperty.of("letter", 0, 6);
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
