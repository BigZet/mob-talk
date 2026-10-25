package io.github.divineblock.world;

import static io.github.divineblock.world.ChurchBuilder.YARD_MAX_X;
import static io.github.divineblock.world.ChurchBuilder.YARD_MAX_Z;
import static io.github.divineblock.world.ChurchBuilder.YARD_MIN_X;
import static io.github.divineblock.world.ChurchBuilder.YARD_MIN_Z;
import static io.github.divineblock.world.ChurchBuilder.stair;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BlockTags;
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

/** The churchyard, after Russian monastery yards: a white stone wall with pilasters, a red brick frieze, icons in niches on
 * its inner face and a coped roof of green tiles; round corner towers under tents with gilded crosses; the Holy Gate in the
 * west wall built as a little gate church with kokoshniks, a drum and a golden onion; a smaller domed gate in the south wall
 * out to the cemetery. Inside: paved paths from the gates to the doors, lanterns on posts, benches and flower beds; along
 * the walls a holy-spring kiosk, a well, a chapel, a bell arch, a worship cross, a woodshed, birches, firs and bushes. The
 * middle of the yard stays open, flat grass: the bosses and punishments use it (see {@link ChurchSites#yard}). Generated
 * before the church, which draws over it. */
public final class ChurchYard extends StructurePiece {
    /** The wall's outer line and the corner towers reach this far beyond the open yard; the yard is open up to TOP. */
    static final int REACH = 3, TOP = 32;
    /** Furnishings keep to this band along the walls: the rest of the yard is open ground. */
    public static final int BAND = 9;
    final ChurchPiece.Frame frame;
    final ChurchBuilder.Variant variant;

    public ChurchYard(ChurchPiece.Frame frame, ChurchBuilder.Variant variant) {
        super(ChurchWorldgen.YARD_PIECE, 0, box(frame));
        this.frame = frame; this.variant = variant;
    }

    public ChurchYard(StructureContext context, NbtCompound nbt) {
        super(ChurchWorldgen.YARD_PIECE, nbt);
        frame = ChurchPiece.Frame.read(nbt);
        variant = ChurchBuilder.Variant.of(nbt.getString("Variant"));
    }

    /** The whole yard with its wall and towers (the box starts at the floor level: the ground is levelled up to under it). */
    static BlockBox box(ChurchPiece.Frame frame) {
        return frame.box(YARD_MIN_X - REACH, 0, YARD_MIN_Z - REACH, YARD_MAX_X + REACH, TOP, YARD_MAX_Z + REACH);
    }
    /** The open ground inside the wall, from the ground up to TOP blocks over the floor. */
    static BlockBox open(ChurchPiece.Frame frame) { return frame.box(YARD_MIN_X, -1, YARD_MIN_Z, YARD_MAX_X, TOP, YARD_MAX_Z); }

    @Override protected void writeNbt(StructureContext context, NbtCompound nbt) { frame.write(nbt); nbt.putString("Variant", variant.name()); }

    @Override public void generate(StructureWorldAccess world, StructureAccessor structures, ChunkGenerator generator, Random random,
                                   BlockBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        draw(ChurchPiece.canvas(world, chunkBox, frame, random), variant);
        sweep(world, chunkBox);
    }

    /** Trees decorated in a neighbouring chunk after this one was drawn reach into the yard and the church with their crowns:
     * every chunk of the yard sweeps leaves (and loose tree trunks outside the church, and the snow on them) out of itself
     * and its eight neighbours, which world generation lets it touch. The yard's own trees stay: their leaves are persistent
     * and their trunks stand on the planted spots. */
    private void sweep(StructureWorldAccess world, BlockBox chunkBox) {
        BlockBox yard = open(frame), church = ChurchPiece.box(frame, variant);
        int floor = frame.origin().getY();
        int x0 = Math.max(yard.getMinX(), chunkBox.getMinX() - 16), x1 = Math.min(yard.getMaxX(), chunkBox.getMaxX() + 16);
        int z0 = Math.max(yard.getMinZ(), chunkBox.getMinZ() - 16), z1 = Math.min(yard.getMaxZ(), chunkBox.getMaxZ() + 16);
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean planted = planted(local(frame, new BlockPos(x, floor, z)));
                for (int y = floor; y <= floor + 44; y++) {
                    at.set(x, y, z);
                    BlockState state = world.getBlockState(at);
                    if (state.isAir()) continue;
                    boolean leaves = state.isIn(BlockTags.LEAVES) && !(state.contains(LeavesBlock.PERSISTENT) && state.get(LeavesBlock.PERSISTENT));
                    boolean tree = leaves || state.isOf(Blocks.VINE)
                            || (state.isIn(BlockTags.LOGS) && !planted || state.isOf(Blocks.SNOW) && y > floor) && (!church.contains(at) || wild(state));
                    if (tree) world.setBlockState(at, Blocks.AIR.getDefaultState(), 2);
                }
            }
    }

    /** A tree's trunk inside the church's box: KAIZEN87's church has only stripped logs of its own (the drawn churches are
     * built of logs, so there the box keeps every log); a forest trunk standing in its gaps stays hanging once its crown is swept. */
    private boolean wild(BlockState state) {
        return variant == ChurchBuilder.Variant.SLAVIC && state.isIn(BlockTags.LOGS)
                && !net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).getPath().startsWith("stripped_");
    }

    // ---- Layout (church coordinates; the open yard is x -32..88, z -38..38, the wall stands one block outside). ----

    static final int WX0 = YARD_MIN_X - 1, WX1 = YARD_MAX_X + 1, WZ0 = YARD_MIN_Z - 1, WZ1 = YARD_MAX_Z + 1;
    /** The yard's trees: x, z, and 0 a birch, 1 a fir. */
    static final int[][] TREES = {
            { -14, -35, 0 }, { -4, -35, 1 }, { 9, -35, 0 }, { 19, -35, 1 }, { 31, -35, 0 }, { 56, -35, 1 }, { 67, -35, 0 }, { 77, -35, 1 },
            { -14, 35, 1 }, { -2, 35, 0 }, { 10, 35, 1 }, { 57, 35, 0 }, { 67, 35, 1 },
            { -29, -14, 0 }, { -29, 14, 1 }, { -29, -22, 1 }, { -29, 24, 0 },
            { 85, -28, 1 }, { 85, -16, 0 }, { 85, 0, 1 }, { 85, 16, 0 }, { 85, 28, 1 } };
    /** The church's own coordinates of a world position (the inverse of {@link ChurchPiece.Frame#world}). */
    static BlockPos local(ChurchPiece.Frame frame, BlockPos at) {
        net.minecraft.util.BlockRotation back = switch (frame.rotation()) {
            case CLOCKWISE_90 -> net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> net.minecraft.util.BlockRotation.CLOCKWISE_90;
            default -> frame.rotation();
        };
        return at.subtract(frame.origin()).rotate(back);
    }
    static boolean planted(BlockPos local) {
        for (int[] t : TREES) if (t[0] == local.getX() && t[1] == local.getZ()) return true;
        return false;
    }

    static void draw(ChurchBuilder.Canvas canvas, ChurchBuilder.Variant variant) {
        // Level grass inside, cleared of trees and hills up to the top of the box.
        for (int x = WX0 - 1; x <= WX1 + 1; x++)
            for (int z = WZ0 - 1; z <= WZ1 + 1; z++) {
                if (!canvas.column(x, z)) continue;
                boolean outside = x < WX0 || x > WX1 || z < WZ0 || z > WZ1;
                boolean wall = !outside && (x == WX0 || x == WX1 || z == WZ0 || z == WZ1);
                if (outside) {
                    // The wall's outer face: a stone footing (the woods outside stay whole: a cut trunk leaves its crown hanging).
                    canvas.foundation(x, -1, z, Blocks.STONE_BRICKS.getDefaultState());
                    continue;
                }
                canvas.foundation(x, -2, z, wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.DIRT.getDefaultState());
                canvas.set(x, -1, z, wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState());
                for (int y = 0; y <= TOP; y++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
            }
        walls(canvas);
        paths(canvas, variant);
        for (int[] c : new int[][] { { WX0, WZ0 }, { WX1, WZ0 }, { WX0, WZ1 }, { WX1, WZ1 } }) tower(canvas, c[0], c[1]);
        holyGate(canvas, WX0);
        southGate(canvas, WZ1);
        spring(canvas, -26, -31);
        well(canvas, -25, 32);
        chapel(canvas, 44, -32);
        bellArch(canvas, 45, 34);
        worshipCross(canvas, 4, -31);
        woodshed(canvas, 79, 33);
        for (int[] t : TREES) if (t[2] == 0) birch(canvas, t[0], t[1]); else fir(canvas, t[0], t[1]);
        bushes(canvas);
    }

    // ---- The wall. ----

    private static final BlockState WHITE = Blocks.SMOOTH_QUARTZ.getDefaultState(), PIER = Blocks.QUARTZ_BRICKS.getDefaultState(),
            BRICK = Blocks.BRICKS.getDefaultState(), TRIM = Blocks.SMOOTH_SANDSTONE.getDefaultState(), PLINTH = Blocks.STONE_BRICKS.getDefaultState(),
            TILE = Blocks.WAXED_OXIDIZED_CUT_COPPER.getDefaultState(), GOLD = Blocks.GOLD_BLOCK.getDefaultState();
    private static final Block TILE_STAIRS = Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS, TILE_SLAB = Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB,
            QUARTZ_STAIRS = Blocks.SMOOTH_QUARTZ_STAIRS, TRIM_STAIRS = Blocks.SMOOTH_SANDSTONE_STAIRS;

    private static void walls(ChurchBuilder.Canvas canvas) {
        for (int z = WZ0 + 4; z <= WZ1 - 4; z++) {
            if (Math.abs(z) > 6) wallBay(canvas, WX0, z, Direction.EAST, z - WZ0);
            wallBay(canvas, WX1, z, Direction.WEST, z - WZ0);
        }
        for (int x = WX0 + 4; x <= WX1 - 4; x++) {
            wallBay(canvas, x, WZ0, Direction.SOUTH, x - WX0);
            if (Math.abs(x - ChurchBuilder.CEMETERY_ENTRANCE_X) > 4) wallBay(canvas, x, WZ1, Direction.NORTH, x - WX0);
        }
    }

    /** One block of the wall at (x, z), its inner face towards in: a stone plinth, white masonry with a red frieze of bricks,
     * a pilaster every eight blocks, an icon in a niche every sixteen, a cornice and a coped tiled roof. */
    private static void wallBay(ChurchBuilder.Canvas canvas, int x, int z, Direction in, int along) {
        if (!canvas.column(x, z) && !canvas.column(x + in.getOffsetX(), z + in.getOffsetZ()) && !canvas.column(x - in.getOffsetX(), z - in.getOffsetZ())) return;
        boolean pilaster = along % 8 == 0, niche = along % 16 == 8;
        int ix = x + in.getOffsetX(), iz = z + in.getOffsetZ(), ox = x - in.getOffsetX(), oz = z - in.getOffsetZ();
        Direction out = in.getOpposite();
        for (int y = 0; y <= 4; y++) {
            BlockState s = y == 0 ? PLINTH : pilaster ? PIER : y == 4 ? BRICK : y == 3 ? (along % 2 == 0 ? BRICK : WHITE) : WHITE;
            canvas.set(x, y, z, s);
        }
        canvas.set(x, 5, z, PIER);
        canvas.set(x, 6, z, TILE);
        canvas.set(x, 7, z, TILE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        // Both faces: the plinth's chamfer, the pilasters, the cornice and the tiles.
        for (int[] f : new int[][] { { ix, iz, 1 }, { ox, oz, 0 } }) {
            int fx = f[0], fz = f[1];
            Direction toWall = f[2] == 1 ? out : in;
            canvas.set(fx, 0, fz, stair(Blocks.STONE_BRICK_STAIRS, toWall, StairShape.STRAIGHT));
            if (pilaster) for (int y = 1; y <= 4; y++) canvas.set(fx, y, fz, PIER);
            canvas.set(fx, 5, fz, stair(QUARTZ_STAIRS, toWall, StairShape.STRAIGHT, BlockHalf.TOP));
            canvas.set(fx, 6, fz, stair(TILE_STAIRS, toWall, StairShape.STRAIGHT));
        }
        if (niche) {
            canvas.set(ix, 1, iz, Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
            canvas.set(ix, 2, iz, icon(ART[Math.floorMod(along / 16, ART.length)], in));
            canvas.set(ix, 3, iz, stair(QUARTZ_STAIRS, out, StairShape.STRAIGHT, BlockHalf.TOP));
            canvas.set(x, 2, z, Blocks.GOLD_BLOCK.getDefaultState());
            canvas.set(ox, 2, oz, Blocks.CHISELED_QUARTZ_BLOCK.getDefaultState());
        }
    }
    private static final int[] ART = { ChurchBlocks.IconBlock.NICHOLAS, ChurchBlocks.IconBlock.THEOTOKOS, ChurchBlocks.IconBlock.TRINITY,
            ChurchBlocks.IconBlock.MICHAEL, ChurchBlocks.IconBlock.BAPTIST, ChurchBlocks.IconBlock.CHRIST };
    static BlockState icon(int art, Direction facing) {
        return ChurchBlocks.ICON_PANEL.getDefaultState().with(ChurchBlocks.IconBlock.ART, art).with(ChurchBlocks.Facing.FACING, facing);
    }

    /** A round corner tower: white with a red band and slit windows, a cornice of brackets, a green tent, the gilded cross. */
    private static void tower(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 2.5) continue;
                int x = cx + dx, z = cz + dz;
                canvas.foundation(x, -1, z, PLINTH);
                for (int y = 0; y <= 11; y++) {
                    BlockState s = y == 0 ? PLINTH : y == 6 || y == 10 ? BRICK : y == 11 ? PIER : WHITE;
                    canvas.set(x, y, z, d > 1.5 ? s : Blocks.AIR.getDefaultState());
                }
                canvas.set(x, 0, z, PLINTH);
                if (d > 1.5 && (dx == 0 || dz == 0)) for (int y = 7; y <= 8; y++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 2.5 && d <= 3.0) canvas.set(cx + dx, 11, cz + dz, Ornament.edgeStair(QUARTZ_STAIRS, dx, dz).with(net.minecraft.block.StairsBlock.HALF, BlockHalf.TOP));
            }
        Ornament.tent(canvas, cx, cz, 12, 3.0, 20, 0.6, TILE, TILE_STAIRS, false);
        canvas.set(cx, 21, cz, GOLD);
        cross(canvas, cx, 22, cz, Direction.Axis.X);
    }

    /** The gilded apple and the eight-pointed cross over it (the cross block's middle two above the apple). */
    private static void cross(ChurchBuilder.Canvas canvas, int x, int y, int z, Direction.Axis axis) {
        canvas.set(x, y, z, ChurchBlocks.CROSS_APPLE.getDefaultState());
        canvas.set(x, y + 1, z, Blocks.AIR.getDefaultState());
        canvas.set(x, y + 2, z, ChurchBlocks.DOME_CROSS.getDefaultState().with(ChurchBlocks.DomeCrossBlock.AXIS, axis));
        canvas.set(x, y + 3, z, Blocks.AIR.getDefaultState());
    }

    // ---- The gates. ----

    /** The Holy Gate: a little gate church astride the west wall (x wx-2..wx+2, z -6..6): a great arch with two wickets beside
     * it, brick with cream pilasters and cornices, an icon over the arch on both faces, three kokoshniks a face, a drum, the
     * golden onion and the cross. */
    private static void holyGate(ChurchBuilder.Canvas canvas, int wx) {
        for (int x = wx - 2; x <= wx + 2; x++)
            for (int z = -6; z <= 6; z++) {
                canvas.foundation(x, -1, z, PLINTH);
                canvas.set(x, -1, z, Math.abs(z) <= 1 ? Blocks.POLISHED_ANDESITE.getDefaultState() : PLINTH);
                boolean face = x == wx - 2 || x == wx + 2;
                for (int y = 0; y <= 9; y++) {
                    boolean arch = Math.abs(z) <= 1 && y <= 4 || z == 0 && y == 5;
                    boolean wicket = Math.abs(z) == 4 && y <= 2;
                    BlockState s;
                    if (arch || wicket) s = Blocks.AIR.getDefaultState();
                    else if (y == 0) s = PLINTH;
                    else if (Math.abs(z) == 6 || Math.abs(z) == 2 && y <= 6 || y == 9 || y == 7 && face) s = TRIM;
                    else s = BRICK;
                    canvas.set(x, y, z, s);
                }
                // The arch's curve: the shoulders turned in.
                if (Math.abs(z) == 1) canvas.set(x, 5, z, stair(Blocks.BRICK_STAIRS, z < 0 ? Direction.NORTH : Direction.SOUTH, StairShape.STRAIGHT, BlockHalf.TOP));
                if (Math.abs(z) == 4) canvas.set(x, 3, z, Blocks.BRICK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
            }
        // The archivolt on both faces, the icons over the arch.
        for (int x : new int[] { wx - 2, wx + 2 }) {
            for (int z = -2; z <= 2; z++) canvas.set(x, 6, z, TRIM);
            canvas.set(x, 5, -2, TRIM); canvas.set(x, 5, 2, TRIM);
            // The icons hang on the face of the wall (an icon block set into it is a dark hole with a board at the back).
            int out = x < wx ? -1 : 1;
            Direction facing = x < wx ? Direction.WEST : Direction.EAST;
            canvas.set(x + out, 7, 0, icon(x < wx ? ChurchBlocks.IconBlock.TRINITY : ChurchBlocks.IconBlock.THEOTOKOS, facing));
            // The kokoshniks: a tall one in the middle, a smaller one each side.
            Ornament.kokoshnik(canvas, false, x, 0, 2, 10, 4, BRICK, TRIM_STAIRS, GOLD);
            Ornament.kokoshnik(canvas, false, x, -4, 1, 10, 2, BRICK, TRIM_STAIRS, TRIM);
            Ornament.kokoshnik(canvas, false, x, 4, 1, 10, 2, BRICK, TRIM_STAIRS, TRIM);
            canvas.set(x, 11, 0, BRICK);
            canvas.set(x + out, 11, 0, icon(ChurchBlocks.IconBlock.CHRIST, facing));
        }
        // Behind the kokoshniks: a roof of tiles over the gate, the drum, the onion, the cross.
        for (int z = -5; z <= 5; z++) {
            canvas.set(wx - 1, 10, z, TILE); canvas.set(wx, 10, z, TILE); canvas.set(wx + 1, 10, z, TILE);
            canvas.set(wx, 11, z, TILE_SLAB.getDefaultState());
        }
        for (int y = 11; y <= 15; y++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    if (Math.hypot(dx, dz) <= 1.6) canvas.set(wx + dx, y, dz, y == 15 || y == 11 ? TRIM : (dx == 0 || dz == 0) && y >= 12 && y <= 13 && Math.hypot(dx, dz) > 0.5
                            ? ChurchBuilder.pane(dx == 0 ? Direction.Axis.X : Direction.Axis.Z) : BRICK);
        Ornament.onion(canvas, wx, 0, 16, 1.6, 2.6, 8, (dx, dz, h) -> GOLD);
        cross(canvas, wx, 24, 0, Direction.Axis.Z);
        // Little pinnacles on the four corners.
        for (int x : new int[] { wx - 2, wx + 2 })
            for (int z : new int[] { -6, 6 }) {
                canvas.set(x, 10, z, Blocks.SANDSTONE_WALL.getDefaultState().with(WallBlock.UP, true));
                canvas.set(x, 11, z, GOLD);
                canvas.set(x, 12, z, ChurchBlocks.CROSS_APPLE.getDefaultState());
            }
        // The lantern under the arch, on a chain from its crown.
        canvas.set(wx, 5, 0, Blocks.CHAIN.getDefaultState());
        canvas.set(wx, 4, 0, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
    }

    /** The small gate out to the cemetery: brick piers with cream trim, an arch, a kokoshnik a face, a little drum and onion. */
    private static void southGate(ChurchBuilder.Canvas canvas, int wz) {
        int gx = ChurchBuilder.CEMETERY_ENTRANCE_X;
        for (int x = gx - 4; x <= gx + 4; x++)
            for (int z = wz - 1; z <= wz + 1; z++) {
                canvas.foundation(x, -1, z, PLINTH);
                if (Math.abs(x - gx) <= 1) canvas.set(x, -1, z, Blocks.POLISHED_ANDESITE.getDefaultState());
                for (int y = 0; y <= 6; y++) {
                    boolean arch = Math.abs(x - gx) <= 1 && y <= 3 || x == gx && y == 4;
                    BlockState s = arch ? Blocks.AIR.getDefaultState() : y == 0 ? PLINTH : y == 6 || Math.abs(x - gx) == 4 || Math.abs(x - gx) == 2 ? TRIM : BRICK;
                    if (Math.abs(x - gx) == 4 && y > 5) s = Blocks.AIR.getDefaultState();
                    canvas.set(x, y, z, s);
                }
                if (Math.abs(x - gx) == 1) canvas.set(x, 4, z, stair(Blocks.BRICK_STAIRS, x < gx ? Direction.WEST : Direction.EAST, StairShape.STRAIGHT, BlockHalf.TOP));
            }
        for (int z : new int[] { wz - 1, wz + 1 }) {
            Ornament.kokoshnik(canvas, true, z, gx, 3, 7, 3, BRICK, TRIM_STAIRS, GOLD);
            canvas.set(gx, 8, z + (z < wz ? -1 : 1), icon(z < wz ? ChurchBlocks.IconBlock.CHRIST : ChurchBlocks.IconBlock.NICHOLAS, z < wz ? Direction.NORTH : Direction.SOUTH));
        }
        for (int x = gx - 2; x <= gx + 2; x++) canvas.set(x, 7, wz, TILE);
        for (int y = 8; y <= 10; y++) { canvas.set(gx, y, wz, y == 10 ? TRIM : BRICK); }
        Ornament.onion(canvas, gx, wz, 11, 0.8, 1.6, 5, (dx, dz, h) -> GOLD);
        cross(canvas, gx, 16, wz, Direction.Axis.X);
        canvas.set(gx, 4, wz, Blocks.CHAIN.getDefaultState());
        canvas.set(gx, 3, wz, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
    }

    // ---- Paths, lanterns, benches, flower beds. ----

    private static void pave(ChurchBuilder.Canvas canvas, int x0, int x1, int z0, int z1, boolean alongX) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                if (!canvas.column(x, z)) continue;
                boolean edge = alongX ? z == z0 || z == z1 : x == x0 || x == x1;
                canvas.set(x, -1, z, edge ? Blocks.POLISHED_ANDESITE.getDefaultState()
                        : ((x + z) & 1) == 0 ? Blocks.BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState());
            }
    }

    private static void paths(ChurchBuilder.Canvas canvas, ChurchBuilder.Variant variant) {
        boolean slavic = variant == ChurchBuilder.Variant.SLAVIC;
        pave(canvas, YARD_MIN_X, -7, -2, 2, true);              // Holy Gate to the porch
        if (slavic) {
            // KAIZEN87's church: the south gate to its south porch, its north porch (by the tower) to the chapel.
            pave(canvas, 26, 30, 31, YARD_MAX_Z, false);
            pave(canvas, 26, 41, 29, 32, true);
            pave(canvas, 33, 45, -30, -28, true);
        } else {
            pave(canvas, 26, 30, 13, YARD_MAX_Z, false);         // south gate north to the refectory's south door
            pave(canvas, 19, 30, 10, 13, true);
            pave(canvas, 19, 23, -27, -10, false);               // the north door out to the chapel and the spring
            pave(canvas, -22, 44, -28, -26, true);
            pave(canvas, 43, 45, -29, -28, false);
        }
        // Lantern posts along the west and south paths.
        for (int x : new int[] { -28, -20, -12 }) for (int z : new int[] { -3, 3 }) lanternPost(canvas, x, z);
        for (int z : slavic ? new int[] { 34 } : new int[] { 18, 26, 34 }) for (int x : new int[] { 25, 31 }) lanternPost(canvas, x, z);
        for (int x : new int[] { 7, 31 }) lanternPost(canvas, x, -29);
        // Benches along the west path, facing it, and by the chapel.
        for (int side : new int[] { -1, 1 })
            for (int x = -18; x <= -16; x++)
                canvas.set(x, 0, 4 * side, stair(Blocks.DARK_OAK_STAIRS, side < 0 ? Direction.NORTH : Direction.SOUTH, StairShape.STRAIGHT));
        for (int side : new int[] { -1, 1 }) {
            canvas.set(-19, 0, 4 * side, Blocks.SPRUCE_TRAPDOOR.getDefaultState().with(net.minecraft.block.TrapdoorBlock.OPEN, true)
                    .with(net.minecraft.block.TrapdoorBlock.FACING, Direction.WEST));
            canvas.set(-15, 0, 4 * side, Blocks.SPRUCE_TRAPDOOR.getDefaultState().with(net.minecraft.block.TrapdoorBlock.OPEN, true)
                    .with(net.minecraft.block.TrapdoorBlock.FACING, Direction.EAST));
        }
        if (slavic) return; // its porches stand where the bench and the flower beds go
        for (int x = 37; x <= 39; x++) canvas.set(x, 0, -30, stair(Blocks.DARK_OAK_STAIRS, Direction.NORTH, StairShape.STRAIGHT));
        // Flower beds either side of the south path.
        BlockState[] flowers = { Blocks.POPPY.getDefaultState(), Blocks.CORNFLOWER.getDefaultState(), Blocks.OXEYE_DAISY.getDefaultState(),
                Blocks.ALLIUM.getDefaultState(), Blocks.LILY_OF_THE_VALLEY.getDefaultState(), Blocks.AZURE_BLUET.getDefaultState() };
        for (int z = 15; z <= 33; z++)
            for (int x : new int[] { 22, 23, 33, 34 }) {
                if (z % 8 == 2) continue; // beside the lantern posts
                if (!canvas.column(x, z)) continue;
                canvas.set(x, -1, z, Blocks.GRASS_BLOCK.getDefaultState());
                canvas.set(x, 0, z, flowers[Math.floorMod(x * 3 + z * 5, flowers.length)]);
            }
        for (int z = 15; z <= 33; z++) for (int x : new int[] { 21, 35 }) if (canvas.column(x, z)) canvas.set(x, -1, z, Blocks.POLISHED_ANDESITE.getDefaultState());
    }

    private static void lanternPost(ChurchBuilder.Canvas canvas, int x, int z) {
        canvas.set(x, -1, z, PLINTH);
        canvas.set(x, 0, z, Blocks.STONE_BRICK_WALL.getDefaultState().with(WallBlock.UP, true));
        canvas.set(x, 1, z, Blocks.DARK_OAK_FENCE.getDefaultState());
        canvas.set(x, 2, z, Blocks.DARK_OAK_FENCE.getDefaultState());
        canvas.set(x, 3, z, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, false));
    }

    // ---- Buildings along the walls. ----

    /** The holy spring: an octagonal open kiosk on eight posts over a stone basin of water, under a green tent with a cross. */
    private static void spring(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                if (!ChurchBuilder.inOctagon(dx, dz, 3.0)) continue;
                int x = cx + dx, z = cz + dz;
                boolean basin = ChurchBuilder.inOctagon(dx, dz, 1.0);
                canvas.set(x, -1, z, basin ? Blocks.WATER.getDefaultState() : Blocks.POLISHED_ANDESITE.getDefaultState());
                if (basin) { canvas.set(x, -2, z, Blocks.WATER.getDefaultState()); canvas.set(x, -3, z, PLINTH); }
                else if (ChurchBuilder.inOctagon(dx, dz, 2.0)) canvas.set(x, 0, z, Blocks.STONE_BRICK_SLAB.getDefaultState());
                boolean post = Math.abs(dx) == 3 && Math.abs(dz) == 1 || Math.abs(dx) == 1 && Math.abs(dz) == 3;
                if (post) {
                    canvas.set(x, 0, z, Blocks.CHISELED_QUARTZ_BLOCK.getDefaultState());
                    for (int y = 1; y <= 3; y++) canvas.set(x, y, z, Blocks.QUARTZ_PILLAR.getDefaultState());
                }
                if (!ChurchBuilder.inOctagon(dx, dz, 2.0)) canvas.set(x, 4, z, PIER);
            }
        Ornament.tent(canvas, cx, cz, 5, 3.6, 11, 0.6, TILE, TILE_STAIRS, true);
        canvas.set(cx, 12, cz, GOLD);
        cross(canvas, cx, 13, cz, Direction.Axis.X);
        canvas.set(cx, 3, cz, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        canvas.set(cx, 4, cz, Blocks.CHAIN.getDefaultState());
        canvas.set(cx - 2, 0, cz - 2, ChurchBlocks.CANDLE_STAND.getDefaultState());
    }

    /** A well: a stone ring over deep water, two posts with a beam and a chain, a little plank roof. */
    private static void well(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx, z = cz + dz;
                if (dx == 0 && dz == 0) {
                    for (int y = -4; y <= -1; y++) canvas.set(x, y, z, Blocks.WATER.getDefaultState());
                    canvas.set(x, -5, z, PLINTH);
                    continue;
                }
                canvas.set(x, -1, z, PLINTH);
                canvas.set(x, 0, z, Blocks.MOSSY_STONE_BRICKS.getDefaultState());
                canvas.set(x, 1, z, Blocks.STONE_BRICK_SLAB.getDefaultState());
            }
        for (int x : new int[] { cx - 2, cx + 2 }) for (int y = 0; y <= 3; y++) canvas.set(x, y, cz, Blocks.SPRUCE_FENCE.getDefaultState());
        for (int x = cx - 2; x <= cx + 2; x++) {
            canvas.set(x, 4, cz, Blocks.STRIPPED_SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X));
            canvas.set(x, 4, cz - 1, stair(Blocks.SPRUCE_STAIRS, Direction.SOUTH, StairShape.STRAIGHT));
            canvas.set(x, 4, cz + 1, stair(Blocks.SPRUCE_STAIRS, Direction.NORTH, StairShape.STRAIGHT));
            canvas.set(x, 5, cz, Blocks.SPRUCE_SLAB.getDefaultState());
        }
        canvas.set(cx, 3, cz, Blocks.CHAIN.getDefaultState());
        canvas.set(cx, 2, cz, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
    }

    /** A chapel: a little brick cube with cream corners and window frames, a door to the south, kokoshniks, a tent and a
     * golden onion; inside an icon with a candle stand before it. */
    private static void chapel(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx, z = cz + dz;
                canvas.foundation(x, -1, z, PLINTH);
                canvas.set(x, -1, z, Blocks.POLISHED_ANDESITE.getDefaultState());
                boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 3, corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                for (int y = 0; y <= 6; y++) {
                    BlockState s = !edge ? Blocks.AIR.getDefaultState() : y == 0 ? PLINTH : corner || y == 6 ? TRIM : y == 5 ? (((dx + dz) & 1) == 0 ? BRICK : TRIM) : BRICK;
                    canvas.set(x, y, z, s);
                }
                if (!edge) { canvas.set(x, 6, z, Blocks.SMOOTH_QUARTZ.getDefaultState()); canvas.set(x, 0, z, Blocks.POLISHED_ANDESITE.getDefaultState()); }
            }
        // Windows, the door, the icon and its candles.
        for (int y = 2; y <= 3; y++) {
            canvas.set(cx - 3, y, cz, ChurchBuilder.pane(Direction.Axis.Z)); canvas.set(cx + 3, y, cz, ChurchBuilder.pane(Direction.Axis.Z));
        }
        for (int x : new int[] { cx - 3, cx + 3 }) { canvas.set(x, 4, cz, TRIM); canvas.set(x, 1, cz, TRIM); }
        canvas.set(cx, 1, cz + 3, Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER).with(DoorBlock.HINGE, DoorHinge.LEFT));
        canvas.set(cx, 2, cz + 3, Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.UPPER).with(DoorBlock.HINGE, DoorHinge.LEFT));
        canvas.set(cx, 0, cz + 3, PLINTH);
        canvas.set(cx, 0, cz + 4, stair(Blocks.STONE_BRICK_STAIRS, Direction.NORTH, StairShape.STRAIGHT));
        canvas.set(cx, 3, cz + 3, TRIM);
        canvas.set(cx, 4, cz + 4, icon(ChurchBlocks.IconBlock.NICHOLAS, Direction.SOUTH));
        canvas.set(cx, 4, cz + 3, BRICK);
        canvas.set(cx, 2, cz - 2, icon(ChurchBlocks.IconBlock.THEOTOKOS, Direction.SOUTH));
        canvas.set(cx, 1, cz - 1, ChurchBlocks.CANDLE_STAND.getDefaultState());
        canvas.set(cx, 4, cz, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        canvas.set(cx, 5, cz, Blocks.CHAIN.getDefaultState());
        // A kokoshnik on each face, the tent, the drum, the onion, the cross.
        for (int s : new int[] { -3, 3 }) {
            Ornament.kokoshnik(canvas, true, cz + s, cx, 2, 7, 3, BRICK, TRIM_STAIRS, GOLD);
            Ornament.kokoshnik(canvas, false, cx + s, cz, 2, 7, 3, BRICK, TRIM_STAIRS, GOLD);
        }
        Ornament.tent(canvas, cx, cz, 7, 2.6, 10, 1.0, TILE, TILE_STAIRS, true);
        for (int y = 11; y <= 12; y++) canvas.set(cx, y, cz, y == 12 ? TRIM : BRICK);
        Ornament.onion(canvas, cx, cz, 13, 0.6, 1.5, 4, Ornament.stars(Blocks.BLUE_CONCRETE.getDefaultState(), GOLD));
        cross(canvas, cx, 17, cz, Direction.Axis.X);
    }

    /** A bell arch (zvonnitsa): two brick piers, an arch with three bells under a little tiled roof and a cross. */
    private static void bellArch(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int dx = -3; dx <= 3; dx++) {
            int x = cx + dx;
            boolean pier = Math.abs(dx) >= 2;
            canvas.foundation(x, -1, cz, PLINTH);
            for (int y = 0; y <= 8; y++) {
                BlockState s = y == 0 ? PLINTH : pier ? (Math.abs(dx) == 3 || y == 4 ? TRIM : BRICK) : y >= 7 ? (y == 8 ? TRIM : BRICK) : Blocks.AIR.getDefaultState();
                canvas.set(x, y, cz, s);
            }
            if (Math.abs(dx) == 1) canvas.set(x, 6, cz, stair(Blocks.BRICK_STAIRS, dx < 0 ? Direction.WEST : Direction.EAST, StairShape.STRAIGHT, BlockHalf.TOP));
            canvas.set(x, 9, cz, Math.abs(dx) == 3 ? TILE_SLAB.getDefaultState() : TILE);
        }
        canvas.set(cx, 6, cz, ChurchBlocks.CHURCH_BELL.getDefaultState().with(ChurchBlocks.ChurchBellBlock.SIZE, 1));
        Ornament.kokoshnik(canvas, true, cz, cx, 2, 10, 2, BRICK, TRIM_STAIRS, GOLD);
        cross(canvas, cx, 13, cz, Direction.Axis.X);
    }

    /** A worship cross on a stepped plinth, candles at its foot. */
    private static void worshipCross(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            canvas.set(cx + dx, -1, cz + dz, PLINTH);
            canvas.set(cx + dx, 0, cz + dz, dx == 0 && dz == 0 ? Blocks.CHISELED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICK_SLAB.getDefaultState());
        }
        canvas.set(cx, 1, cz, Blocks.POLISHED_ANDESITE.getDefaultState());
        canvas.set(cx, 2, cz, Blocks.AIR.getDefaultState());
        canvas.set(cx, 3, cz, ChurchBlocks.DOME_CROSS.getDefaultState().with(ChurchBlocks.DomeCrossBlock.AXIS, Direction.Axis.X));
        canvas.set(cx, 4, cz, Blocks.AIR.getDefaultState());
        canvas.set(cx, 1, cz + 1, ChurchBlocks.CANDLE_STAND.getDefaultState());
    }

    /** A plank woodshed with a gabled roof, firewood stacked along its side. */
    private static void woodshed(ChurchBuilder.Canvas canvas, int cx, int cz) {
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx, z = cz + dz;
                canvas.foundation(x, -1, z, Blocks.COBBLESTONE.getDefaultState());
                boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 2, corner = Math.abs(dx) == 3 && Math.abs(dz) == 2;
                for (int y = 0; y <= 3; y++)
                    canvas.set(x, y, z, !edge ? Blocks.AIR.getDefaultState() : corner ? Blocks.SPRUCE_LOG.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState());
            }
        for (int x = cx - 4; x <= cx + 4; x++) {
            for (int k = 0; k <= 2; k++) {
                canvas.set(x, 4 + k, cz - 3 + k, stair(Blocks.SPRUCE_STAIRS, Direction.SOUTH, StairShape.STRAIGHT));
                canvas.set(x, 4 + k, cz + 3 - k, stair(Blocks.SPRUCE_STAIRS, Direction.NORTH, StairShape.STRAIGHT));
                if (Math.abs(x - cx) == 3 && k < 2) for (int z = cz - 2 + k; z <= cz + 2 - k; z++) canvas.set(x, 4 + k, z, Blocks.SPRUCE_PLANKS.getDefaultState());
            }
            canvas.set(x, 7, cz, Blocks.SPRUCE_SLAB.getDefaultState());
            for (int z = cz - 1; z <= cz + 1; z++) if (Math.abs(x - cx) == 3) canvas.set(x, 6, z, Blocks.SPRUCE_PLANKS.getDefaultState());
        }
        canvas.set(cx, 0, cz - 2, Blocks.SPRUCE_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER).with(DoorBlock.HINGE, DoorHinge.LEFT));
        canvas.set(cx, 1, cz - 2, Blocks.SPRUCE_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH).with(DoorBlock.HALF, DoubleBlockHalf.UPPER).with(DoorBlock.HINGE, DoorHinge.LEFT));
        canvas.set(cx - 2, 0, cz, Blocks.BARREL.getDefaultState());
        canvas.set(cx + 2, 0, cz, Blocks.HAY_BLOCK.getDefaultState());
        for (int x = cx - 3; x <= cx - 1; x++) for (int y = 0; y <= 1; y++)
            canvas.set(x, y, cz - 3, Blocks.OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Z));
    }

    // ---- Trees and bushes (persistent leaves: they never wither and the sweep leaves them). ----

    private static BlockState leaves(Block block) { return block.getDefaultState().with(LeavesBlock.PERSISTENT, true); }

    private static void birch(ChurchBuilder.Canvas canvas, int cx, int cz) {
        BlockState leaf = leaves(Blocks.BIRCH_LEAVES);
        for (int y = 3; y <= 7; y++) {
            double r = y <= 4 ? 2.4 : y <= 6 ? 1.6 : 0.8;
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++)
                    if (Math.hypot(dx, dz) <= r && Math.floorMod(cx * 31 + cz * 17 + dx * 7 + dz * 5 + y * 3, 11) != 0)
                        canvas.set(cx + dx, y, cz + dz, leaf);
        }
        for (int y = 0; y <= 6; y++) canvas.set(cx, y, cz, Blocks.BIRCH_LOG.getDefaultState());
        canvas.set(cx, -1, cz, Blocks.DIRT.getDefaultState());
    }

    private static void fir(ChurchBuilder.Canvas canvas, int cx, int cz) {
        BlockState leaf = leaves(Blocks.SPRUCE_LEAVES);
        for (int y = 1; y <= 9; y++) {
            double r = (y % 2 == 1 ? 2.6 : 1.7) * (1 - (y - 1) / 10.0);
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++)
                    if (Math.hypot(dx, dz) <= Math.max(0.5, r)) canvas.set(cx + dx, y, cz + dz, leaf);
        }
        canvas.set(cx, 10, cz, leaf);
        for (int y = 0; y <= 6; y++) canvas.set(cx, y, cz, Blocks.SPRUCE_LOG.getDefaultState());
        canvas.set(cx, -1, cz, Blocks.DIRT.getDefaultState());
    }

    private static void bushes(ChurchBuilder.Canvas canvas) {
        int[][] at = { { -9, -37 }, { 3, -37 }, { 14, -37 }, { 25, -37 }, { 36, -37 }, { 51, -37 }, { 62, -37 }, { 72, -37 }, { 82, -37 },
                { -8, 37 }, { 4, 37 }, { 16, 37 }, { 40, 37 }, { 51, 37 }, { 62, 37 }, { 72, 37 },
                { -31, -18 }, { -31, -9 }, { -31, 9 }, { -31, 19 }, { 87, -22 }, { 87, -8 }, { 87, 8 }, { 87, 22 } };
        for (int[] b : at) {
            int kind = Math.floorMod(b[0] * 7 + b[1] * 3, 3);
            if (kind == 0) canvas.set(b[0], 0, b[1], Blocks.FLOWERING_AZALEA.getDefaultState());
            else if (kind == 1) canvas.set(b[0], 0, b[1], Blocks.AZALEA.getDefaultState());
            else {
                canvas.set(b[0], 0, b[1], leaves(Blocks.OAK_LEAVES));
                canvas.set(b[0] + 1, 0, b[1], leaves(Blocks.OAK_LEAVES));
                canvas.set(b[0], 1, b[1], leaves(Blocks.OAK_LEAVES));
            }
            canvas.set(b[0] + (kind == 2 ? -1 : 1), 0, b[1], Blocks.ROSE_BUSH.getDefaultState().with(net.minecraft.block.TallPlantBlock.HALF, DoubleBlockHalf.LOWER));
            canvas.set(b[0] + (kind == 2 ? -1 : 1), 1, b[1], Blocks.ROSE_BUSH.getDefaultState().with(net.minecraft.block.TallPlantBlock.HALF, DoubleBlockHalf.UPPER));
        }
    }
}
