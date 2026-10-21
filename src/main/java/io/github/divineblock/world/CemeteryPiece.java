package io.github.divineblock.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/** The stand-in churchyard, used only when the cemetery pool {@code divineblock:cemetery/start} is missing from the data
 * (the cemetery thread's real one is a jigsaw structure attached by {@link ChurchStructure}). It fills the same plot: a
 * fence with a gate towards the church, a path, rows of mounds with simple crosses. See docs/church-cemetery-interface.md. */
public final class CemeteryPiece extends StructurePiece {
    /** The plot in its own coordinates: x 0..WIDTH-1, z 0..DEPTH-1 away from the church, ground at y = -1. */
    public static final int WIDTH = ChurchBuilder.CEMETERY_MAX_X - ChurchBuilder.CEMETERY_MIN_X + 1,
            DEPTH = ChurchBuilder.CEMETERY_MAX_Z - ChurchBuilder.CEMETERY_MIN_Z + 1, HEIGHT = 8;
    public static final int GATE_X = ChurchBuilder.CEMETERY_ENTRANCE_X - ChurchBuilder.CEMETERY_MIN_X;

    final ChurchPiece.Frame frame;
    final long seed;

    public CemeteryPiece(ChurchPiece.Frame church, long seed) {
        super(ChurchWorldgen.CEMETERY_PIECE, 0, box(church));
        this.frame = church; this.seed = seed;
    }

    public CemeteryPiece(StructureContext context, NbtCompound nbt) {
        super(ChurchWorldgen.CEMETERY_PIECE, nbt);
        frame = ChurchPiece.Frame.read(nbt);
        seed = nbt.getLong("Seed");
    }

    static BlockBox box(ChurchPiece.Frame church) {
        return church.box(ChurchBuilder.CEMETERY_MIN_X, -1, ChurchBuilder.CEMETERY_MIN_Z, ChurchBuilder.CEMETERY_MAX_X, HEIGHT, ChurchBuilder.CEMETERY_MAX_Z);
    }

    @Override protected void writeNbt(StructureContext context, NbtCompound nbt) { frame.write(nbt); nbt.putLong("Seed", seed); }

    @Override public void generate(StructureWorldAccess world, StructureAccessor structures, ChunkGenerator generator, Random random,
                                   BlockBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        ChurchBuilder.Canvas church = ChurchPiece.canvas(world, chunkBox, frame, random);
        ChurchBuilder.Canvas plot = new ChurchBuilder.Canvas() {
            @Override public void set(int x, int y, int z, BlockState state) { church.set(x + ChurchBuilder.CEMETERY_MIN_X, y, z + ChurchBuilder.CEMETERY_MIN_Z, state); }
            @Override public void foundation(int x, int y, int z, BlockState state) { church.foundation(x + ChurchBuilder.CEMETERY_MIN_X, y, z + ChurchBuilder.CEMETERY_MIN_Z, state); }
            @Override public void chest(int x, int y, int z, BlockState state) { church.chest(x + ChurchBuilder.CEMETERY_MIN_X, y, z + ChurchBuilder.CEMETERY_MIN_Z, state); }
        };
        for (int x = 0; x < WIDTH; x++)
            for (int z = 0; z < DEPTH; z++) {
                plot.foundation(x, -2, z, Blocks.DIRT.getDefaultState());
                plot.set(x, -1, z, Blocks.GRASS_BLOCK.getDefaultState());
                for (int y = 0; y < HEIGHT + 8; y++) plot.set(x, y, z, Blocks.AIR.getDefaultState());
            }
        placeholder(plot, seed);
    }

    static void placeholder(ChurchBuilder.Canvas canvas, long seed) {
        Random random = Random.create(seed);
        for (int x = 0; x < WIDTH; x++)
            for (int z = 0; z < DEPTH; z++) {
                boolean edge = x == 0 || z == 0 || x == WIDTH - 1 || z == DEPTH - 1;
                if (!edge) continue;
                if (z == 0 && x == GATE_X) {
                    canvas.set(x, 0, z, Blocks.SPRUCE_FENCE_GATE.getDefaultState().with(FenceGateBlock.FACING, Direction.NORTH));
                    continue;
                }
                boolean alongX = z == 0 || z == DEPTH - 1;
                boolean corner = (x == 0 || x == WIDTH - 1) && (z == 0 || z == DEPTH - 1);
                BlockState fence = Blocks.SPRUCE_FENCE.getDefaultState();
                if (alongX || corner) fence = fence.with(FenceBlock.EAST, x < WIDTH - 1).with(FenceBlock.WEST, x > 0);
                if (!alongX || corner) fence = fence.with(FenceBlock.NORTH, z > 0).with(FenceBlock.SOUTH, z < DEPTH - 1);
                canvas.set(x, 0, z, fence);
            }
        for (int z = 1; z < DEPTH - 1; z++) canvas.set(GATE_X, -1, z, Blocks.DIRT_PATH.getDefaultState());
        for (int row = 3; row < DEPTH - 2; row += 4)
            for (int x = 2; x < WIDTH - 2; x += 4) {
                if (Math.abs(x - GATE_X) < 2 || random.nextInt(5) == 0) continue;
                canvas.set(x, -1, row, Blocks.PODZOL.getDefaultState());
                canvas.set(x, -1, row + 1, Blocks.PODZOL.getDefaultState());
                canvas.set(x, 0, row + 1, Blocks.FERN.getDefaultState());
                BlockState post = Blocks.SPRUCE_FENCE.getDefaultState();
                canvas.set(x, 0, row, post);
                canvas.set(x, 1, row, post.with(FenceBlock.EAST, true).with(FenceBlock.WEST, true));
                canvas.set(x, 2, row, post);
                canvas.set(x - 1, 1, row, post.with(FenceBlock.EAST, true));
                canvas.set(x + 1, 1, row, post.with(FenceBlock.WEST, true));
            }
    }
}
