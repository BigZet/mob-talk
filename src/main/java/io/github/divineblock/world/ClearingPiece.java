package io.github.divineblock.world;

import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
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
 * grown (top layer step), and the cemetery's own pieces only lay their blocks on the ground. Logs, leaves and plants go,
 * ponds are filled with earth; the ground stays. The churchyard's own trees (feature pieces of the pool) may be grown while a
 * neighbouring chunk is generated and spill into this one, so columns within {@link #KEEP} blocks of them are left alone. */
public final class ClearingPiece extends StructurePiece {
    private static final int KEEP = 4;
    private final List<BlockPos> trees;

    public ClearingPiece(BlockBox box, List<BlockPos> trees) { super(ChurchWorldgen.CLEARING_PIECE, 0, box); this.trees = List.copyOf(trees); }

    public ClearingPiece(StructureContext context, NbtCompound nbt) {
        super(ChurchWorldgen.CLEARING_PIECE, nbt);
        trees = java.util.Arrays.stream(nbt.getLongArray("Trees")).mapToObj(BlockPos::fromLong).toList();
    }

    @Override protected void writeNbt(StructureContext context, NbtCompound nbt) {
        nbt.putLongArray("Trees", trees.stream().mapToLong(BlockPos::asLong).toArray());
    }

    private boolean nearTree(int x, int z) {
        for (BlockPos tree : trees) if (Math.abs(tree.getX() - x) <= KEEP && Math.abs(tree.getZ() - z) <= KEEP) return true;
        return false;
    }

    /** Also reaches into the eight neighbouring chunks (the feature step may write there): a tree growing in a chunk decorated
     * after this one spills its crown over the border into the cleared yard, and the crown then hung in the air once that
     * chunk's own clearing took the trunk. There only natural leaves (not persistent; the churchyard's template trees have
     * persistent ones) and vines go, so plots and trees already laid in a neighbour stay. */
    @Override public void generate(StructureWorldAccess world, StructureAccessor structures, ChunkGenerator generator, Random random,
                                   BlockBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int x = Math.max(boundingBox.getMinX(), chunkBox.getMinX() - 16); x <= Math.min(boundingBox.getMaxX(), chunkBox.getMaxX() + 16); x++)
            for (int z = Math.max(boundingBox.getMinZ(), chunkBox.getMinZ() - 16); z <= Math.min(boundingBox.getMaxZ(), chunkBox.getMaxZ() + 16); z++) {
                if (nearTree(x, z)) continue;
                if (!chunkBox.contains(x, chunkBox.getMinY(), z)) {
                    for (int y = boundingBox.getMaxY(); y >= boundingBox.getMinY(); y--) {
                        BlockState state = world.getBlockState(at.set(x, y, z));
                        if (state.isIn(BlockTags.LEAVES) && !state.getOrEmpty(LeavesBlock.PERSISTENT).orElse(false) || state.isOf(Blocks.VINE))
                            world.setBlockState(at, Blocks.AIR.getDefaultState(), 2);
                    }
                    continue;
                }
                for (int y = boundingBox.getMaxY(); y >= boundingBox.getMinY(); y--) {
                    at.set(x, y, z);
                    BlockState state = world.getBlockState(at);
                    // Ponds and lakes are placed after the terrain the site was judged by: fill them, grass on top.
                    if (state.isOf(Blocks.WATER) || state.isOf(Blocks.ICE)) {
                        boolean top = world.getBlockState(at.up()).isAir();
                        world.setBlockState(at, (top ? Blocks.GRASS_BLOCK : Blocks.DIRT).getDefaultState(), 2);
                        continue;
                    }
                    if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.VINE) || state.isOf(Blocks.SNOW)
                            || state.isOf(Blocks.MUSHROOM_STEM) || state.isOf(Blocks.BROWN_MUSHROOM_BLOCK) || state.isOf(Blocks.RED_MUSHROOM_BLOCK)
                            || state.isIn(BlockTags.REPLACEABLE_BY_TREES) && !state.isAir())
                        world.setBlockState(at, (state.getFluidState().isEmpty() ? Blocks.AIR : Blocks.DIRT).getDefaultState(), 2);
                }
            }
    }
}
