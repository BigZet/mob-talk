package io.github.divineblock.world;

import static io.github.divineblock.world.ChurchBuilder.YARD_MAX_X;
import static io.github.divineblock.world.ChurchBuilder.YARD_MAX_Z;
import static io.github.divineblock.world.ChurchBuilder.YARD_MIN_X;
import static io.github.divineblock.world.ChurchBuilder.YARD_MIN_Z;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.SlabType;
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

/** The churchyard: flat open grass inside a white stone wall like a monastery's, round corner towers under little tents, the
 * Holy Gate in the west wall with an icon and a domed top, a smaller gate in the south wall out to the cemetery, paths to the
 * church's doors. It is generated before the church (the church draws over it) and it is meant to stay open: the bosses and
 * punishments use it (see {@link ChurchSites#yard}). */
public final class ChurchYard extends StructurePiece {
    /** The wall's outer line and the corner towers reach this far beyond the open yard. */
    static final int REACH = 3, TOP = 24;
    final ChurchPiece.Frame frame;
    final ChurchBuilder.Variant variant;

    public ChurchYard(ChurchPiece.Frame frame, ChurchBuilder.Variant variant) {
        super(ChurchWorldgen.YARD_PIECE, 0, box(frame));
        this.frame = frame; this.variant = variant;
    }

    public ChurchYard(StructureContext context, NbtCompound nbt) {
        super(ChurchWorldgen.YARD_PIECE, nbt);
        frame = ChurchPiece.Frame.read(nbt);
        variant = nbt.getString("Variant").equals("WOOD") ? ChurchBuilder.Variant.WOOD : ChurchBuilder.Variant.STONE;
    }

    /** The whole yard with its wall and towers (the box starts at the floor level: the ground is levelled up to under it). */
    static BlockBox box(ChurchPiece.Frame frame) {
        return frame.box(YARD_MIN_X - REACH, 0, YARD_MIN_Z - REACH, YARD_MAX_X + REACH, TOP, YARD_MAX_Z + REACH);
    }
    /** The open ground inside the wall, from the ground up to 24 blocks over the floor. */
    static BlockBox open(ChurchPiece.Frame frame) { return frame.box(YARD_MIN_X, -1, YARD_MIN_Z, YARD_MAX_X, TOP, YARD_MAX_Z); }

    @Override protected void writeNbt(StructureContext context, NbtCompound nbt) { frame.write(nbt); nbt.putString("Variant", variant.name()); }

    @Override public void generate(StructureWorldAccess world, StructureAccessor structures, ChunkGenerator generator, Random random,
                                   BlockBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        draw(ChurchPiece.canvas(world, chunkBox, frame, random));
    }

    static void draw(ChurchBuilder.Canvas canvas) {
        int wx0 = YARD_MIN_X - 1, wx1 = YARD_MAX_X + 1, wz0 = YARD_MIN_Z - 1, wz1 = YARD_MAX_Z + 1;
        // Level grass inside, cleared of trees and hills up to the top of the box.
        for (int x = wx0; x <= wx1; x++)
            for (int z = wz0; z <= wz1; z++) {
                if (!canvas.column(x, z)) continue;
                boolean wall = x == wx0 || x == wx1 || z == wz0 || z == wz1;
                canvas.foundation(x, -2, z, wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.DIRT.getDefaultState());
                canvas.set(x, -1, z, wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState());
                for (int y = 0; y <= TOP; y++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
                if (wall) wall(canvas, x, z, x == wx0 || x == wx1 ? z - wz0 : x - wx0);
            }
        // Paths: from the Holy Gate to the porch, from the south gate to the refectory's south door, around the church.
        BlockState path = Blocks.DIRT_PATH.getDefaultState();
        for (int x = YARD_MIN_X; x <= -7; x++) for (int z = -1; z <= 1; z++) if (canvas.column(x, z)) canvas.set(x, -1, z, path);
        for (int z = 10; z <= YARD_MAX_Z; z++) for (int x = 27; x <= 29; x++) if (canvas.column(x, z)) canvas.set(x, -1, z, path);
        for (int x = 20; x <= 29; x++) for (int z = 10; z <= 12; z++) if (canvas.column(x, z)) canvas.set(x, -1, z, path);
        // Lantern posts along the west path.
        for (int x : new int[] { -28, -20, -12 })
            for (int z : new int[] { -3, 3 }) {
                canvas.set(x, 0, z, Blocks.STONE_BRICK_WALL.getDefaultState().with(WallBlock.UP, true));
                canvas.set(x, 1, z, Blocks.STONE_BRICK_WALL.getDefaultState().with(WallBlock.UP, true));
                canvas.set(x, 2, z, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, false));
            }
        for (int[] c : new int[][] { { wx0, wz0 }, { wx1, wz0 }, { wx0, wz1 }, { wx1, wz1 } }) tower(canvas, c[0], c[1]);
        holyGate(canvas, wx0);
        southGate(canvas, wz1);
    }

    /** The white wall, five blocks high with a coping, a buttress every eight blocks. */
    private static void wall(ChurchBuilder.Canvas canvas, int x, int z, int along) {
        boolean buttress = along % 8 == 0;
        for (int y = 0; y <= 4; y++)
            canvas.set(x, y, z, y == 0 ? Blocks.STONE_BRICKS.getDefaultState() : buttress ? Blocks.QUARTZ_BRICKS.getDefaultState() : Blocks.SMOOTH_QUARTZ.getDefaultState());
        canvas.set(x, 5, z, buttress ? Blocks.QUARTZ_BRICKS.getDefaultState() : Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        if (buttress) canvas.set(x, 6, z, Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
    }

    /** A round corner tower under a tent with a gilded apple. */
    private static void tower(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int x = cx - 3; x <= cx + 3; x++)
            for (int z = cz - 3; z <= cz + 3; z++) {
                if (!canvas.column(x, z)) continue;
                double d = Math.hypot(x - cx, z - cz);
                if (d > 2.8) continue;
                canvas.foundation(x, -1, z, Blocks.STONE_BRICKS.getDefaultState());
                for (int y = 0; y <= 8; y++) canvas.set(x, y, z, y == 0 ? Blocks.STONE_BRICKS.getDefaultState() : y == 8 || y == 4 ? Blocks.QUARTZ_BRICKS.getDefaultState() : Blocks.SMOOTH_QUARTZ.getDefaultState());
                if (d > 1.8 && (Math.abs(x - cx) == 0 || Math.abs(z - cz) == 0)) canvas.set(x, 6, z, ChurchBuilder.pane(Math.abs(x - cx) == 0 ? Direction.Axis.X : Direction.Axis.Z));
            }
        for (int k = 0; k <= 5; k++) {
            double r = 3.2 - k * 0.6;
            for (int x = cx - 4; x <= cx + 4; x++)
                for (int z = cz - 4; z <= cz + 4; z++)
                    if (Math.hypot(x - cx, z - cz) <= Math.max(0.5, r)) canvas.set(x, 9 + k, z, Blocks.WAXED_OXIDIZED_CUT_COPPER.getDefaultState());
        }
        canvas.set(cx, 15, cz, ChurchBlocks.CROSS_APPLE.getDefaultState());
    }

    /** The Holy Gate: a gate tower astride the west wall, an arch through it, icons over the arch, a drum with an onion and
     * the cross. */
    private static void holyGate(ChurchBuilder.Canvas canvas, int wx) {
        for (int x = wx - 2; x <= wx + 2; x++)
            for (int z = -4; z <= 4; z++) {
                if (!canvas.column(x, z)) continue;
                canvas.foundation(x, -1, z, Blocks.STONE_BRICKS.getDefaultState());
                for (int y = 0; y <= 10; y++) {
                    boolean arch = Math.abs(z) <= 1 && y <= 3 || Math.abs(z) == 0 && y == 4;
                    boolean edge = Math.abs(z) == 4 || x == wx - 2 || x == wx + 2;
                    canvas.set(x, y, z, arch ? Blocks.AIR.getDefaultState() : y == 10 || y == 0 || Math.abs(z) == 4 || (y == 5 && edge) ? Blocks.QUARTZ_BRICKS.getDefaultState()
                            : Blocks.SMOOTH_QUARTZ.getDefaultState());
                }
                if (Math.abs(z) <= 1) canvas.set(x, -1, z, Blocks.DIRT_PATH.getDefaultState());
            }
        // Little kokoshnik gables on the top, a drum, the onion and the cross.
        for (int z = -4; z <= 4; z++) for (int x = wx - 2; x <= wx + 2; x++) if (Math.abs(z) <= 3 && Math.abs(x - wx) <= 1) canvas.set(x, 11, z, Blocks.SMOOTH_QUARTZ.getDefaultState());
        for (int y = 12; y <= 13; y++)
            for (int x = wx - 2; x <= wx + 2; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.hypot(x - wx, z) <= 1.6) canvas.set(x, y, z, y == 13 ? Blocks.QUARTZ_BRICKS.getDefaultState() : Blocks.SMOOTH_QUARTZ.getDefaultState());
        double[] onion = { 1.8, 2.4, 2.4, 1.9, 1.2, 0.5 };
        for (int h = 0; h < onion.length; h++)
            for (int x = wx - 3; x <= wx + 3; x++)
                for (int z = -3; z <= 3; z++)
                    if (Math.hypot(x - wx, z) <= onion[h]) canvas.set(x, 14 + h, z, Blocks.GOLD_BLOCK.getDefaultState());
        canvas.set(wx, 20, 0, ChurchBlocks.CROSS_APPLE.getDefaultState());
        canvas.set(wx, 22, 0, ChurchBlocks.DOME_CROSS.getDefaultState().with(ChurchBlocks.DomeCrossBlock.AXIS, Direction.Axis.Z));
        // The icons over the arch, outside and in.
        canvas.set(wx - 3, 6, 0, ChurchBlocks.ICON_PANEL.getDefaultState().with(ChurchBlocks.IconBlock.ART, ChurchBlocks.IconBlock.TRINITY).with(ChurchBlocks.Facing.FACING, Direction.WEST));
        canvas.set(wx + 3, 6, 0, ChurchBlocks.ICON_PANEL.getDefaultState().with(ChurchBlocks.IconBlock.ART, ChurchBlocks.IconBlock.THEOTOKOS).with(ChurchBlocks.Facing.FACING, Direction.EAST));
    }

    /** The small gate out to the cemetery: two piers, an arch, a little roof and a cross. */
    private static void southGate(ChurchBuilder.Canvas canvas, int wz) {
        int gx = ChurchBuilder.CEMETERY_ENTRANCE_X;
        for (int x = gx - 2; x <= gx + 2; x++)
            for (int z = wz - 1; z <= wz + 1; z++) {
                if (!canvas.column(x, z)) continue;
                canvas.foundation(x, -1, z, Blocks.STONE_BRICKS.getDefaultState());
                for (int y = 0; y <= 6; y++) {
                    boolean arch = Math.abs(x - gx) <= 1 && y <= 3;
                    canvas.set(x, y, z, arch ? Blocks.AIR.getDefaultState() : y == 6 || y == 0 ? Blocks.QUARTZ_BRICKS.getDefaultState() : Blocks.SMOOTH_QUARTZ.getDefaultState());
                }
                if (Math.abs(x - gx) <= 1) canvas.set(x, -1, z, Blocks.DIRT_PATH.getDefaultState());
            }
        for (int x = gx - 1; x <= gx + 1; x++) canvas.set(x, 7, wz, Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB.getDefaultState());
        canvas.set(gx, 7, wz, ChurchBlocks.CROSS_APPLE.getDefaultState());
        canvas.set(gx, 9, wz, ChurchBlocks.DOME_CROSS.getDefaultState().with(ChurchBlocks.DomeCrossBlock.AXIS, Direction.Axis.X));
        canvas.set(gx, 4, wz - 2, ChurchBlocks.ICON_PANEL.getDefaultState().with(ChurchBlocks.IconBlock.ART, ChurchBlocks.IconBlock.CHRIST).with(ChurchBlocks.Facing.FACING, Direction.NORTH));
    }
}
