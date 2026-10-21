package io.github.rusflora.block;

import com.mojang.serialization.MapCodec;
import io.github.rusflora.world.ShrubShapes;
import net.minecraft.block.BlockState;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/** A young shrub. In daylight it grows into the full shrub of its kind; bone meal hurries it. */
public class ShrubSaplingBlock extends PlantBlock implements Fertilizable {
    private static final VoxelShape SHAPE = PlantBlock.createCuboidShape(2, 0, 2, 14, 12, 14);
    public final ShrubKind kind;

    public ShrubSaplingBlock(ShrubKind kind, Settings settings) { super(settings); this.kind = kind; }

    @Override protected MapCodec<? extends PlantBlock> getCodec() { return createCodec(settings -> new ShrubSaplingBlock(kind, settings)); }

    @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }

    @Override protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (world.getLightLevel(pos.up()) >= 9 && random.nextInt(7) == 0) grow(world, random, pos, state);
    }

    @Override public boolean isFertilizable(WorldView world, BlockPos pos, BlockState state) { return true; }
    @Override public boolean canGrow(World world, Random random, BlockPos pos, BlockState state) { return random.nextFloat() < 0.45f; }

    @Override public void grow(ServerWorld world, Random random, BlockPos pos, BlockState state) {
        BlockState air = net.minecraft.block.Blocks.AIR.getDefaultState();
        world.setBlockState(pos, air, NO_REDRAW);
        if (!ShrubShapes.grow(world, pos.down(), kind, random, false)) world.setBlockState(pos, state, NO_REDRAW);
    }
}
