package io.github.rusflora.hogweed;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/** A dry hogweed umbel on a cut stalk: decoration for dried bouquets. Stands on any solid top, burns well. */
public class DryUmbelBlock extends PlantBlock {
    private static final VoxelShape SHAPE = createCuboidShape(2, 0, 2, 14, 15, 14);

    public DryUmbelBlock(Settings settings) { super(settings); }

    @Override protected MapCodec<? extends PlantBlock> getCodec() { return createCodec(DryUmbelBlock::new); }
    @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
    @Override protected boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos) { return floor.isSideSolidFullSquare(world, pos, Direction.UP); }
}
