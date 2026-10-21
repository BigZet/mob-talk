package io.github.divineblock.cemetery;

import net.minecraft.block.Block;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/** Shapes drawn for a block facing north, in pixels {x1, y1, z1, x2, y2, z2}, turned for the other three sides the way the
 * blockstate's y rotation turns the model. */
final class GraveShapes {
    private GraveShapes() { }

    /** Indexed by {@link Direction#getHorizontal()}: south, west, north, east. */
    static VoxelShape[] horizontal(double[]... boxes) {
        VoxelShape[] shapes = new VoxelShape[4];
        for (Direction facing : Direction.Type.HORIZONTAL) {
            VoxelShape shape = VoxelShapes.empty();
            for (double[] b : boxes) shape = VoxelShapes.union(shape, turned(b, facing));
            shapes[facing.getHorizontal()] = shape.simplify();
        }
        return shapes;
    }

    private static VoxelShape turned(double[] b, Direction facing) {
        double x1 = b[0], z1 = b[2], x2 = b[3], z2 = b[5];
        return switch (facing) {
            case EAST -> Block.createCuboidShape(16 - z2, b[1], x1, 16 - z1, b[4], x2);
            case SOUTH -> Block.createCuboidShape(16 - x2, b[1], 16 - z2, 16 - x1, b[4], 16 - z1);
            case WEST -> Block.createCuboidShape(z1, b[1], 16 - x2, z2, b[4], 16 - x1);
            default -> Block.createCuboidShape(x1, b[1], z1, x2, b[4], z2);
        };
    }

    static double[] box(double x1, double y1, double z1, double x2, double y2, double z2) { return new double[] { x1, y1, z1, x2, y2, z2 }; }
}
