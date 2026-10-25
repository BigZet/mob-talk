package io.github.mobtalk.game;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import java.util.EnumMap;
import java.util.Map;

/** The village bar's furniture ({@link BarContent}): a block that faces one of four sides, with an outline drawn for the north side
 * and turned for the others. {@code seat} is how high a villager sits on it (blocks), 0 for a block nobody sits on. The model of
 * each piece is made by tools/bar/bar.py. */
public class BarFurnitureBlock extends HorizontalFacingBlock {
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
    private final boolean solid;
    final double seat;

    /** {@code boxes}: pixel boxes {x1, y1, z1, x2, y2, z2} for the north-facing piece. {@code solid}: it blocks walking. */
    BarFurnitureBlock(Settings settings, double seat, boolean solid, double[]... boxes) {
        super(settings);
        this.seat = seat;
        this.solid = solid;
        for (Direction side : Direction.Type.HORIZONTAL) {
            VoxelShape shape = VoxelShapes.empty();
            for (double[] b : boxes) shape = VoxelShapes.union(shape, turned(b, side));
            shapes.put(side, shape.simplify());
        }
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    /** A north-facing box turned to face {@code side} (clockwise seen from above: north, east, south, west). */
    private static VoxelShape turned(double[] b, Direction side) {
        double x1 = b[0], z1 = b[2], x2 = b[3], z2 = b[5];
        return switch (side) {
            case EAST -> Block.createCuboidShape(16 - z2, b[1], x1, 16 - z1, b[4], x2);
            case SOUTH -> Block.createCuboidShape(16 - x2, b[1], 16 - z2, 16 - x1, b[4], 16 - z1);
            case WEST -> Block.createCuboidShape(z1, b[1], 16 - x2, z2, b[4], 16 - x1);
            default -> Block.createCuboidShape(x1, b[1], z1, x2, b[4], z2);
        };
    }

    @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return MapCodec.unit(this); }
    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
    /** Placed by hand it faces the player, as a chair one sits on facing away from the table would. */
    @Override public BlockState getPlacementState(ItemPlacementContext context) { return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite()); }
    @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return shapes.get(state.get(FACING)); }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return solid ? shapes.get(state.get(FACING)) : VoxelShapes.empty();
    }
}
