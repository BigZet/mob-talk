package io.github.divineblock.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/** Clears trees and bushes over the churchyard before its pieces are placed: the church is generated after the forest has
 * grown (top layer step), and the cemetery's own pieces only lay their blocks on the ground. Logs, leaves and plants go;
 * the ground stays. */
public final class ClearingPiece extends StructurePiece {
    public ClearingPiece(BlockBox box) { super(ChurchWorldgen.CLEARING_PIECE, 0, box); }

    public ClearingPiece(StructureContext context, NbtCompound nbt) { super(ChurchWorldgen.CLEARING_PIECE, nbt); }

    @Override protected void writeNbt(StructureContext context, NbtCompound nbt) { }

    @Override public void generate(StructureWorldAccess world, StructureAccessor structures, ChunkGenerator generator, Random random,
                                   BlockBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int x = Math.max(boundingBox.getMinX(), chunkBox.getMinX()); x <= Math.min(boundingBox.getMaxX(), chunkBox.getMaxX()); x++)
            for (int z = Math.max(boundingBox.getMinZ(), chunkBox.getMinZ()); z <= Math.min(boundingBox.getMaxZ(), chunkBox.getMaxZ()); z++)
                for (int y = boundingBox.getMaxY(); y >= boundingBox.getMinY(); y--) {
                    at.set(x, y, z);
                    BlockState state = world.getBlockState(at);
                    if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.VINE) || state.isOf(Blocks.SNOW)
                            || state.isOf(Blocks.MUSHROOM_STEM) || state.isOf(Blocks.BROWN_MUSHROOM_BLOCK) || state.isOf(Blocks.RED_MUSHROOM_BLOCK)
                            || state.isIn(BlockTags.REPLACEABLE_BY_TREES) && !state.isAir())
                        world.setBlockState(at, state.getFluidState().isEmpty() ? Blocks.AIR.getDefaultState() : state.getFluidState().getBlockState(), 2);
                }
    }
}
