package io.github.divineblock.cemetery;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldEvents;

/** A two blocks tall cross or monument. The whole model hangs on the lower half; the upper half only keeps the space, the
 * outline and the collision. Breaking either half breaks both, and only the lower half drops the item, as doors do. */
public class TallGraveBlock extends GraveBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;
    private final VoxelShape[] upper;
    private final double[][] lowerBoxes, upperBoxes;

    public TallGraveBlock(Settings settings, double[][] lower, double[][] upper) {
        super(settings, true, lower);
        this.upper = GraveShapes.horizontal(upper);
        this.lowerBoxes = lower;
        this.upperBoxes = upper;
        setDefaultState(getDefaultState().with(HALF, DoubleBlockHalf.LOWER));
    }

    @Override protected MapCodec<? extends Block> getCodec() { return createCodec(settings -> new TallGraveBlock(settings, lowerBoxes, upperBoxes)); }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { super.appendProperties(builder); builder.add(HALF); }

    @Override protected VoxelShape shape(BlockState state) {
        return state.get(HALF) == DoubleBlockHalf.UPPER ? upper[state.get(FACING).getHorizontal()] : super.shape(state);
    }

    @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();
        World world = ctx.getWorld();
        if (pos.getY() >= world.getTopY() - 1 || !world.getBlockState(pos.up()).canReplace(ctx)) return null;
        return super.getPlacementState(ctx);
    }

    @Override public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        boolean water = world.getFluidState(pos.up()).getFluid() == Fluids.WATER;
        world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER).with(WATERLOGGED, water), Block.NOTIFY_ALL);
    }

    @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world,
            BlockPos pos, BlockPos neighborPos) {
        DoubleBlockHalf half = state.get(HALF);
        if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            boolean partner = neighborState.isOf(this) && neighborState.get(HALF) != half;
            if (!partner) return state.get(WATERLOGGED) ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    /** In creative the lower half goes without a drop, so breaking the top leaves nothing behind. */
    @Override public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient && player.isCreative() && state.get(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.down();
            BlockState lower = world.getBlockState(below);
            if (lower.isOf(this) && lower.get(HALF) == DoubleBlockHalf.LOWER) {
                BlockState left = lower.get(WATERLOGGED) ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
                world.setBlockState(below, left, Block.NOTIFY_ALL | Block.SKIP_DROPS);
                world.syncWorldEvent(player, WorldEvents.BLOCK_BROKEN, below, Block.getRawIdFromState(lower));
            }
        }
        return super.onBreak(world, pos, state, player);
    }
}
