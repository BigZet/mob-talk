package io.github.divineblock.world;

import io.github.divineblock.DivineBlockMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

/** Draws the Orthodox church block by block in its own coordinates, an original design after Russian churches of the 17th
 * century "ship" plan: a tall tented bell tower in the west, a low refectory, a two-light cube (chetverik) with three
 * zakomaras a side and five onion domes on drums (or, in the wooden church, an octagon under a tall tent with four little
 * onions at the corners), three apses in the east. East is +x (the altar), south is +z, y = 0 is the floor; the ground lies
 * at y = -1.
 *
 * <pre>
 *   x: -6..-1 porch, 0..12 bell tower, 12..30 refectory, 30..54 the cube (iconostasis at x = 48), 54..62 apses
 *   z: -12..12 the cube; the refectory -9..9; the tower -6..6
 * </pre>
 * The walled churchyard around it is {@link ChurchYard}; the cemetery lies south beyond the yard's wall.
 * The whole drawing is turned with the cemetery when it is placed ({@link ChurchPiece.Frame}).
 * The whole drawing runs again for every chunk the church touches; the canvas keeps only that chunk's blocks, so the random
 * choices must come in the same order every time (one seeded random, no branching on what the world holds). */
public final class ChurchBuilder {
    public enum Variant { STONE, WOOD }

    /** The open churchyard inside the white wall (the wall itself stands one block outside these bounds), ground at y = -1. */
    public static final int YARD_MIN_X = -32, YARD_MAX_X = 88, YARD_MIN_Z = -38, YARD_MAX_Z = 38;
    /** The cemetery south of the yard's wall (21 x 27 stand-in plot, ground at y = -1): the cemetery pool's entrance jigsaw
     * lands on (ENTRANCE_X, -1, MIN_Z), just outside the yard's south gate, and the graves run south from it. */
    public static final int CEMETERY_MIN_X = 18, CEMETERY_MAX_X = 38, CEMETERY_MIN_Z = 41, CEMETERY_MAX_Z = 67, CEMETERY_ENTRANCE_X = 28;
    /** Everything the church itself may touch (the yard and the cemetery are their own pieces). */
    public static final int MIN_X = -7, MAX_X = 63, MIN_Z = -13, MAX_Z = 13, MIN_Y = -14, MAX_Y = 60;
    /** Where the god's altar stands (the holy table behind the royal doors) and the middle of the nave floor. */
    public static final int ALTAR_X = 55, ALTAR_Z = 0, NAVE_X = 42, NAVE_Z = 0;
    /** The chest in the sanctuary (the vestry side, north of the altar). */
    public static final int CHEST_X = 57, CHEST_Z = -4;
    /** The boss seal under the sanctuary floor (placed by {@link ChurchPiece} when the bosses' block exists). */
    public static final int SEAL_X = 57, SEAL_Z = 3;
    /** A safe spot outdoors on the path before the porch (y = 0 is standing on the ground). */
    public static final int OUTDOOR_X = -10, OUTDOOR_Z = 0;
    /** The iconostasis line and the great bell (its block is the middle of the bell). */
    public static final int ICONOSTASIS_X = 48, BELL_X = 6, BELL_Y = 22, BELL_Z = 0;

    /** Where the blocks go; the piece clips them to the chunk being generated. */
    public interface Canvas {
        void set(int x, int y, int z, BlockState state);
        /** Fills downwards from y until the ground: the foundation under walls standing on a slope. */
        void foundation(int x, int y, int z, BlockState state);
        void chest(int x, int y, int z, BlockState state);
        /** Whether anything in this column can land in the chunk being drawn: lets the big loops skip the rest. */
        default boolean column(int x, int z) { return true; }
    }

    private final Canvas canvas;
    private final Random random;
    private final Variant variant;
    private final boolean stone;
    final BlockState mainDome, smallDome, roof;
    final Block roofStairs, roofSlab;

    public ChurchBuilder(Canvas canvas, long seed, Variant variant) {
        this.canvas = canvas;
        this.random = Random.create(seed);
        this.variant = variant;
        this.stone = variant == Variant.STONE;
        if (stone) {
            mainDome = random.nextInt(3) > 0 ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.BLUE_CONCRETE.getDefaultState();
            smallDome = mainDome.isOf(Blocks.GOLD_BLOCK) && random.nextBoolean() ? Blocks.BLUE_CONCRETE.getDefaultState()
                    : random.nextBoolean() ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.LIGHT_BLUE_CONCRETE.getDefaultState(); // green read as tree crowns
            // Grey iron roofs mostly (as on the user's reference church), sometimes green copper.
            boolean green = random.nextInt(3) == 0;
            roofStairs = green ? Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS : Blocks.POLISHED_ANDESITE_STAIRS;
            roofSlab = green ? Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB : Blocks.POLISHED_ANDESITE_SLAB;
            roof = (green ? Blocks.WAXED_OXIDIZED_CUT_COPPER : Blocks.POLISHED_ANDESITE).getDefaultState();
        } else {
            // Aspen shingles (lemekh) weather silver-grey.
            mainDome = Blocks.POLISHED_ANDESITE.getDefaultState();
            smallDome = mainDome;
            roofStairs = Blocks.SPRUCE_STAIRS;
            roofSlab = Blocks.SPRUCE_SLAB;
            roof = Blocks.SPRUCE_PLANKS.getDefaultState();
        }
    }

    public void build() {
        clear();
        foundations();
        floors();
        walls();
        bellTower();
        refectoryRoof();
        apses();
        if (stone) stoneTop(); else woodTop();
        porch();
        openings();
        interior();
        iconostasis();
        sanctuary();
    }

    // ---- Plan. ----

    static boolean inTower(int x, int z) { return x >= 0 && x <= 12 && Math.abs(z) <= 6; }
    static boolean inRefectory(int x, int z) { return x >= 12 && x <= 30 && Math.abs(z) <= 9; }
    static boolean inCube(int x, int z) { return x >= 30 && x <= 54 && Math.abs(z) <= 12; }
    static double apse(int x, int z) { return Math.hypot(x - 54, z); }
    static boolean inSideApse(int x, int z) { return x > 54 && (Math.hypot(x - 54, z - 8.5) <= 3.6 || Math.hypot(x - 54, z + 8.5) <= 3.6); }
    static boolean inApses(int x, int z) { return x > 54 && (apse(x, z) <= 7.5 || inSideApse(x, z)); }
    static boolean inPorch(int x, int z) { return x >= -6 && x <= -1 && Math.abs(z) <= 4; }
    static boolean inChurch(int x, int z) { return inTower(x, z) || inRefectory(x, z) || inCube(x, z) || inApses(x, z); }

    /** Air above the floor where the church stands, and a block around it, so trees and hills do not grow through it. */
    private void clear() {
        for (int x = MIN_X; x <= MAX_X; x++)
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (!canvas.column(x, z)) continue;
                boolean near = inChurch(x, z) || inChurch(x + 1, z) || inChurch(x - 1, z) || inChurch(x, z + 1) || inChurch(x, z - 1) || inPorch(x, z);
                if (!near) continue;
                for (int y = 0; y <= MAX_Y; y++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
            }
    }

    private void foundations() {
        BlockState plinth = stone ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
        for (int x = MIN_X; x <= MAX_X; x++)
            for (int z = MIN_Z; z <= MAX_Z; z++)
                if (canvas.column(x, z) && (inChurch(x, z) || inPorch(x, z))) canvas.foundation(x, -1, z, plinth);
    }

    private void floors() {
        for (int x = -6; x <= MAX_X; x++)
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (!canvas.column(x, z) || !(inChurch(x, z) || inPorch(x, z))) continue;
                BlockState floor;
                if (inPorch(x, z)) floor = stone ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
                else if (stone) floor = ((x + z) & 1) == 0 ? Blocks.POLISHED_ANDESITE.getDefaultState() : Blocks.POLISHED_DEEPSLATE.getDefaultState();
                else floor = (x & 3) == 0 ? Blocks.DARK_OAK_PLANKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
                canvas.set(x, 0, z, floor);
            }
    }

    // ---- Walls. ----

    private BlockState outer(int y, Direction.Axis along) {
        if (!stone) return Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, along);
        if (y <= 1) return pick(Blocks.STONE_BRICKS, 6, Blocks.MOSSY_STONE_BRICKS, 1, Blocks.CRACKED_STONE_BRICKS, 1);
        return Blocks.BRICKS.getDefaultState();
    }
    /** The inside face: a dark wainscot below, frescoes above in the stone church; planed logs in the wooden one. */
    private BlockState inner(int y, Direction.Axis along) {
        if (!stone) return y <= 2 ? Blocks.DARK_OAK_PLANKS.getDefaultState() : Blocks.STRIPPED_SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, along);
        if (y <= 2) return Blocks.DARK_OAK_PLANKS.getDefaultState();
        if (y % 6 == 3) return Blocks.YELLOW_TERRACOTTA.getDefaultState(); // ochre bands between the fresco registers
        if (y % 6 == 0) return Blocks.LIGHT_BLUE_TERRACOTTA.getDefaultState();
        return pick(Blocks.SMOOTH_SANDSTONE, 12, Blocks.WHITE_TERRACOTTA, 3, Blocks.TERRACOTTA, 1);
    }
    /** The cream carved trim of the brick church (pilasters, cornices, frames), dark oak in the log church. */
    private BlockState accent() { return stone ? Blocks.SMOOTH_SANDSTONE.getDefaultState() : Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState(); }
    /** Brick for the stone church's upper walls and drums. */
    private BlockState wallWhite() { return Blocks.BRICKS.getDefaultState(); }

    /** A two-layer box wall: outer face at the given bounds, inner face one block in. */
    private void boxWalls(int x0, int x1, int z0, int z1, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                canvas.set(x, y, z0, outer(y, Direction.Axis.X)); canvas.set(x, y, z1, outer(y, Direction.Axis.X));
                if (x > x0 && x < x1) { canvas.set(x, y, z0 + 1, inner(y, Direction.Axis.X)); canvas.set(x, y, z1 - 1, inner(y, Direction.Axis.X)); }
            }
            for (int z = z0 + 1; z < z1; z++) {
                canvas.set(x0, y, z, outer(y, Direction.Axis.Z)); canvas.set(x1, y, z, outer(y, Direction.Axis.Z));
                if (z > z0 + 1 && z < z1 - 1) { canvas.set(x0 + 1, y, z, inner(y, Direction.Axis.Z)); canvas.set(x1 - 1, y, z, inner(y, Direction.Axis.Z)); }
            }
        }
    }

    int cubeTop() { return stone ? 20 : 16; }

    private void walls() {
        boxWalls(12, 30, -9, 9, 1, 10);
        boxWalls(30, 54, -12, 12, 1, cubeTop());
        int top = cubeTop();
        // Pilasters dividing each facade of the cube into three bays, a cornice under the zakomaras; the refectory's lesenes.
        for (int y = 1; y <= top; y++) {
            for (int x : new int[] { 30, 38, 46, 54 }) { canvas.set(x, y, -12, accent()); canvas.set(x, y, 12, accent()); }
            for (int z : new int[] { -12, -4, 4, 12 }) { canvas.set(54, y, z, accent()); if (Math.abs(z) > 9 || y > 10) canvas.set(30, y, z, accent()); }
        }
        for (int x = 30; x <= 54; x++) { canvas.set(x, top, -12, accent()); canvas.set(x, top, 12, accent()); }
        for (int z = -12; z <= 12; z++) { canvas.set(30, top, z, accent()); canvas.set(54, top, z, accent()); }
        for (int y = 1; y <= 10; y++)
            for (int x : new int[] { 12, 18, 24, 30 }) { canvas.set(x, y, -9, accent()); canvas.set(x, y, 9, accent()); }
        for (int x = 12; x <= 30; x++) { canvas.set(x, 10, -9, accent()); canvas.set(x, 10, 9, accent()); }
        // Ceilings: a starry vault over the cube with the drum open above its middle; a plain one over the refectory.
        for (int x = 31; x <= 53; x++)
            for (int z = -11; z <= 11; z++) {
                if (!stone) break;
                double d = Math.hypot(x - NAVE_X, z - NAVE_Z);
                canvas.set(x, top, z, d <= 3.6 ? Blocks.AIR.getDefaultState()
                        : random.nextInt(16) == 0 ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.BLUE_TERRACOTTA.getDefaultState());
            }
        for (int x = 13; x <= 29; x++)
            for (int z = -8; z <= 8; z++) canvas.set(x, 10, z, stone ? Blocks.SMOOTH_QUARTZ.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState());
    }

    // ---- Bell tower: a closed tier, the open belfry with its bells, an octagon, the tent, a small onion and the cross. ----

    private void bellTower() {
        boxWalls(0, 12, -6, 6, 1, 14);
        for (int y = 1; y <= 14; y++) for (int[] c : new int[][] { { 0, -6 }, { 0, 6 }, { 12, -6 }, { 12, 6 } }) canvas.set(c[0], y, c[1], accent());
        for (int x = 0; x <= 12; x++) { canvas.set(x, 14, -6, accent()); canvas.set(x, 14, 6, accent()); }
        for (int z = -6; z <= 6; z++) { canvas.set(0, 14, z, accent()); canvas.set(12, 14, z, accent()); }
        BlockState deck = stone ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
        for (int x = 1; x <= 11; x++) for (int z = -5; z <= 5; z++) { canvas.set(x, 15, z, deck); canvas.set(x, 28, z, deck); }
        canvas.set(1, 15, -5, Blocks.AIR.getDefaultState()); // the hatch for the ladder
        // The belfry: corner piers and wide arches narrowing at the top; a parapet between the piers.
        for (int y = 15; y <= 28; y++)
            for (int x = 0; x <= 12; x++)
                for (int z = -6; z <= 6; z++) {
                    boolean edge = x == 0 || x == 12 || Math.abs(z) == 6;
                    if (!edge) continue;
                    int along = x == 0 || x == 12 ? Math.abs(z) : Math.abs(x - 6);
                    boolean open = y >= 17 && y <= 25 && along <= 3 || y == 26 && along <= 2;
                    if (y == 15 || y == 28) canvas.set(x, y, z, accent());
                    else if (!open) canvas.set(x, y, z, along >= 5 ? accent() : stone ? wallWhite() : Blocks.SPRUCE_LOG.getDefaultState());
                    else canvas.set(x, y, z, Blocks.AIR.getDefaultState());
                    if (y == 16 && along <= 3) canvas.set(x, y, z, stone ? Blocks.STONE_BRICK_WALL.getDefaultState() : Blocks.SPRUCE_FENCE.getDefaultState());
                }
        // Beams across the belfry and the bells hung from them: the great bell in the middle, two medium and four small.
        BlockState beamZ = Blocks.DARK_OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Z);
        BlockState beamX = Blocks.DARK_OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X);
        for (int z = -6; z <= 6; z++) canvas.set(BELL_X, 24, z, beamZ);
        for (int x = 0; x <= 12; x++) if (x != BELL_X) canvas.set(x, 24, BELL_Z, beamX);
        canvas.set(BELL_X, BELL_Y, BELL_Z, bell(2));
        for (int z : new int[] { -3, 3 }) canvas.set(BELL_X, 23, z, bell(1));
        for (int z : new int[] { -5, 5 }) canvas.set(BELL_X, 23, z, bell(0));
        for (int x : new int[] { 2, 10 }) canvas.set(x, 23, BELL_Z, bell(0));
        // The octagon, the tent with its dormers (slukhi), the small onion and the cross.
        BlockState tent = stone ? roof : Blocks.SPRUCE_PLANKS.getDefaultState();
        for (int y = 29; y <= 33; y++)
            for (int x = 0; x <= 12; x++)
                for (int z = -6; z <= 6; z++) {
                    int dx = x - 6;
                    if (inOctagon(dx, z, 6.0) && (y == 29 || !inOctagon(dx, z, 5.0))) canvas.set(x, y, z, y == 33 || y == 29 ? accent() : stone ? wallWhite() : Blocks.SPRUCE_LOG.getDefaultState());
                }
        for (int y = 30; y <= 32; y++) { canvas.set(6, y, -6, pane(Direction.Axis.X)); canvas.set(6, y, 6, pane(Direction.Axis.X)); canvas.set(0, y, 0, pane(Direction.Axis.Z)); canvas.set(12, y, 0, pane(Direction.Axis.Z)); }
        for (int k = 0; k <= 12; k++) octagonDisc(6, 0, 34 + k, 6.4 - k * 0.47, tent);
        for (Direction side : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
            int sx = 6 + side.getOffsetX() * 5, sz = side.getOffsetZ() * 5;
            canvas.set(sx, 36, sz, accent()); canvas.set(sx, 37, sz, Blocks.GLASS.getDefaultState()); canvas.set(sx, 38, sz, accent());
        }
        for (int y = 47; y <= 48; y++) disc(6, 0, y, 1.2, y == 48 ? accent() : stone ? wallWhite() : Blocks.SPRUCE_WOOD.getDefaultState());
        onion(6, 0, 49, 1.2, 2.3, 6, smallDome, false);
        crossOn(6, 55, 0);
        // Inside: a ladder up to the bells.
        for (int y = 1; y <= 15; y++) canvas.set(1, y, -5, Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.EAST));
    }
    private static BlockState bell(int size) { return ChurchBlocks.CHURCH_BELL.getDefaultState().with(ChurchBlocks.ChurchBellBlock.SIZE, size); }

    private void refectoryRoof() { gable(13, 29, -9, 9, 11); }

    /** A gable roof along x over z0..z1 (one block of eaves each side), its ends walled. */
    private void gable(int x0, int x1, int z0, int z1, int y0) {
        BlockState wall = stone ? Blocks.SMOOTH_QUARTZ.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
        for (int k = 0; ; k++) {
            int north = z0 - 1 + k, south = z1 + 1 - k, y = y0 + k;
            if (north > south) break;
            for (int x = x0; x <= x1; x++) {
                if (north == south) { canvas.set(x, y, north, roofSlab.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM)); continue; }
                canvas.set(x, y, north, stair(roofStairs, Direction.SOUTH, StairShape.STRAIGHT));
                canvas.set(x, y, south, stair(roofStairs, Direction.NORTH, StairShape.STRAIGHT));
                for (int z = north + 1; z <= south - 1; z++) canvas.set(x, y, z, x == x0 || x == x1 ? wall : roof);
            }
            if (north == south) break;
        }
    }

    // ---- Apses: the great one in the middle, a smaller one each side, under half-domes. ----

    private void apses() {
        int top = 14;
        for (int x = 54; x <= MAX_X; x++)
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (x == 54 || !inApses(x, z) || !canvas.column(x, z)) continue;
                double d = apse(x, z);
                for (int y = 1; y <= top; y++) {
                    if (d > 7.5) { if (y <= 11) canvas.set(x, y, z, stone ? outer(y, Direction.Axis.Y) : Blocks.SPRUCE_WOOD.getDefaultState()); }
                    else if (d > 6.5) canvas.set(x, y, z, y == top ? accent() : stone ? outer(y, Direction.Axis.Y) : Blocks.SPRUCE_WOOD.getDefaultState());
                    else if (d > 5.5) canvas.set(x, y, z, stone ? inner(y, Direction.Axis.Y) : Blocks.STRIPPED_SPRUCE_WOOD.getDefaultState());
                }
            }
        for (int level = 0; level <= 5; level++) {
            double radius = 7.6 - level * level * 0.22 - level * 0.3;
            for (int x = 55; x <= MAX_X; x++)
                for (int z = -8; z <= 8; z++)
                    if (apse(x, z) <= radius) canvas.set(x, top + 1 + level, z, roof);
        }
        for (int side : new int[] { -1, 1 })
            for (int level = 0; level <= 2; level++)
                for (int x = 55; x <= 58; x++)
                    for (int z = 4 * side; Math.abs(z) <= 13; z += side)
                        if (Math.hypot(x - 54, z - 8.5 * side) <= 3.7 - level * 1.2 && apse(x, z) > 7.0) canvas.set(x, 12 + level, z, roof);
        // The sanctuary is open into the great apse.
        for (int y = 1; y <= 12; y++) for (int z = -5; z <= 5; z++) for (int x = 53; x <= 54; x++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
        for (int z = -5; z <= 5; z++) canvas.set(54, 13, z, accent());
    }

    // ---- The cube's top. ----

    private void stoneTop() {
        int top = cubeTop();
        // A hipped roof behind the zakomaras, rising to the drums.
        for (int k = 0; k <= 7; k++) ring(30 + k, 54 - k, -12 + k, 12 - k, top + 1 + k);
        for (int x = 38; x <= 46; x++) for (int z = -4; z <= 4; z++) canvas.set(x, top + 8, z, roof);
        // Zakomaras: three half-rounds on each facade, roofed along their curve.
        for (int x = 30; x <= 54; x++) {
            int h = zakomara(x - 30);
            for (int y = top + 1; y <= top + h; y++) { canvas.set(x, y, -12, wallWhite()); canvas.set(x, y, 12, wallWhite()); }
            canvas.set(x, top + 1 + h, -12, roofSlab.getDefaultState()); canvas.set(x, top + 1 + h, 12, roofSlab.getDefaultState());
        }
        for (int z = -12; z <= 12; z++) {
            int h = zakomara(z + 12);
            for (int y = top + 1; y <= top + h; y++) { canvas.set(54, y, z, wallWhite()); canvas.set(30, y, z, wallWhite()); }
            canvas.set(54, top + 1 + h, z, roofSlab.getDefaultState()); canvas.set(30, top + 1 + h, z, roofSlab.getDefaultState());
        }
        // The great light drum with eight windows, a cornice, the onion and the cross.
        for (int y = top + 1; y <= 32; y++)
            for (int x = NAVE_X - 5; x <= NAVE_X + 5; x++)
                for (int z = -5; z <= 5; z++) {
                    double d = Math.hypot(x - NAVE_X, z);
                    if (d <= 3.6) canvas.set(x, y, z, y == 32 ? Blocks.BLUE_TERRACOTTA.getDefaultState() : Blocks.AIR.getDefaultState());
                    else if (d <= 4.7) canvas.set(x, y, z, y == top + 1 || y == 32 || y == 24 ? accent() : wallWhite());
                }
        for (int y = 26; y <= 30; y++) {
            canvas.set(NAVE_X + 4, y, 0, pane(Direction.Axis.Z)); canvas.set(NAVE_X - 4, y, 0, pane(Direction.Axis.Z));
            canvas.set(NAVE_X, y, 4, pane(Direction.Axis.X)); canvas.set(NAVE_X, y, -4, pane(Direction.Axis.X));
            for (int[] c : new int[][] { { 3, 3 }, { -3, 3 }, { 3, -3 }, { -3, -3 } }) canvas.set(NAVE_X + c[0], y, c[1], pane(Direction.Axis.X));
        }
        onion(NAVE_X, 0, 33, 4.7, 7.2, 13, mainDome, mainDome.isOf(Blocks.BLUE_CONCRETE));
        crossOn(NAVE_X, 46, 0);
        // Four blind drums over the corners.
        for (int[] c : new int[][] { { 34, -8 }, { 50, -8 }, { 34, 8 }, { 50, 8 } }) {
            for (int y = top + 1; y <= 28; y++) disc(c[0], c[1], y, 2.7, y == 28 || y == 24 ? accent() : wallWhite());
            onion(c[0], c[1], 29, 2.7, 4.2, 8, smallDome, smallDome.isOf(Blocks.BLUE_CONCRETE));
            crossOn(c[0], 37, c[1]);
        }
    }
    /** The height of a zakomara over a bay of eight: a half-round between the pilasters. */
    private static int zakomara(int along) {
        int a = along % 8;
        return (int) Math.round(Math.sqrt(Math.max(0, 16 - (a - 4) * (a - 4))));
    }

    private void woodTop() {
        int top = cubeTop();
        // The octagon on the cube, a skirt roof (politsa) over the cube's corners, the tall tent, the drum and the onion.
        BlockState log = Blocks.SPRUCE_WOOD.getDefaultState();
        for (int x = 29; x <= 55; x++)
            for (int z = -13; z <= 13; z++) {
                int dx = x - NAVE_X;
                if (!inOctagon(dx, z, 9.0)) canvas.set(x, top + 1, z, roofSlab.getDefaultState());
                for (int y = top + 1; y <= top + 10; y++)
                    if (inOctagon(dx, z, 9.0) && !inOctagon(dx, z, 8.0)) canvas.set(x, y, z, y == top + 10 ? accent() : log);
                if (x >= 31 && x <= 53 && Math.abs(z) <= 11 && !inOctagon(dx, z, 8.0)) canvas.set(x, top, z, Blocks.SPRUCE_PLANKS.getDefaultState());
            }
        for (int y = top + 2; y <= top + 5; y++) {
            canvas.set(NAVE_X, y, -9, pane(Direction.Axis.X)); canvas.set(NAVE_X, y, 9, pane(Direction.Axis.X));
            canvas.set(NAVE_X - 9, y, 0, pane(Direction.Axis.Z)); canvas.set(NAVE_X + 9, y, 0, pane(Direction.Axis.Z));
        }
        int sky = top + 11;
        octagonDisc(NAVE_X, 0, sky, 9.8, Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        for (int x = NAVE_X - 8; x <= NAVE_X + 8; x++)
            for (int z = -8; z <= 8; z++)
                if (inOctagon(x - NAVE_X, z, 8.0))
                    canvas.set(x, sky, z, Math.hypot(x - NAVE_X, z) <= 1.5 ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.BLUE_TERRACOTTA.getDefaultState());
        for (int k = 1; k <= 16; k++) octagonDisc(NAVE_X, 0, sky + k, 9.2 - k * 0.53, roof);
        for (int y = sky + 17; y <= sky + 18; y++) disc(NAVE_X, 0, y, 1.4, Blocks.SPRUCE_WOOD.getDefaultState());
        onion(NAVE_X, 0, sky + 19, 1.4, 2.8, 7, mainDome, false);
        crossOn(NAVE_X, sky + 26, 0);
        // Four small onions on drums over the cube's corners.
        for (int[] c : new int[][] { { 34, -8 }, { 50, -8 }, { 34, 8 }, { 50, 8 } }) {
            for (int y = top + 2; y <= top + 4; y++) disc(c[0], c[1], y, 1.2, Blocks.SPRUCE_WOOD.getDefaultState());
            onion(c[0], c[1], top + 5, 1.2, 2.2, 5, smallDome, false);
            crossOn(c[0], top + 10, c[1]);
        }
        // A little onion over the apse.
        onion(56, 0, 21, 0.8, 1.6, 4, smallDome, false);
        crossOn(56, 25, 0);
    }

    // ---- The porch: steps up to the tower's west door under a gabled canopy on two posts. ----

    private void porch() {
        for (int z = -3; z <= 3; z++) canvas.set(-6, 0, z, stair(stone ? Blocks.STONE_BRICK_STAIRS : Blocks.SPRUCE_STAIRS, Direction.EAST, StairShape.STRAIGHT));
        for (int[] c : new int[][] { { -5, -4 }, { -5, 4 }, { -1, -4 }, { -1, 4 } })
            for (int y = 1; y <= 4; y++) canvas.set(c[0], y, c[1], stone ? Blocks.QUARTZ_PILLAR.getDefaultState() : Blocks.STRIPPED_SPRUCE_LOG.getDefaultState());
        gable(-6, -1, -4, 4, 5);
        for (int z : new int[] { -3, 3 }) canvas.set(-5, 4, z, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        // The patron's icon over the door, under the canopy.
        canvas.set(-1, 4, 0, icon(ChurchBlocks.IconBlock.CHRIST, Direction.WEST));
    }

    // ---- Doors, windows. ----

    private void openings() {
        door(0, 0, Direction.EAST);
        for (int y = 1; y <= 4; y++) { canvas.set(0, y, -1, accent()); canvas.set(0, y, 1, accent()); }
        canvas.set(0, 3, 0, accent());
        canvas.set(0, 4, 0, accent());
        for (int y = 1; y <= 2; y++) canvas.set(1, y, 0, Blocks.AIR.getDefaultState());
        // The arches from the tower into the refectory and from the refectory into the cube; side doors of the refectory.
        for (int y = 1; y <= 4; y++) for (int z = -1; z <= 1; z++) for (int x = 11; x <= 13; x++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
        for (int y = 1; y <= 8; y++) for (int z = -4; z <= 4; z++) for (int x = 29; x <= 31; x++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
        for (int z : new int[] { -9, 9 }) {
            door(21, z, z < 0 ? Direction.NORTH : Direction.SOUTH);
            for (int y = 1; y <= 2; y++) canvas.set(21, y, z < 0 ? -8 : 8, Blocks.AIR.getDefaultState());
            for (int y = 1; y <= 3; y++) { canvas.set(20, y, z, accent()); canvas.set(22, y, z, accent()); }
            canvas.set(21, 3, z, accent());
        }
        // Two lights of windows in the cube (the "two-light" chetverik), framed; the refectory's, the tower's, the apse's.
        int[] upper = stone ? new int[] { 12, 16 } : new int[] { 10, 13 };
        for (int x : new int[] { 34, 42, 50 }) for (int z : new int[] { -12, 12 }) { window(x, z, 3, 7); window(x, z, upper[0], upper[1]); }
        for (int x : new int[] { 15, 27 }) for (int z : new int[] { -9, 9 }) window(x, z, 3, 7);
        for (int z : new int[] { -3, 3 }) window(0, z, 5, 9, Direction.Axis.Z);
        for (int z : new int[] { -6, 6 }) window(6, z, 5, 10);
        for (int y = 4; y <= 9; y++) {
            canvas.set(61, y, 0, pane(Direction.Axis.Z)); canvas.set(60, y, 0, Blocks.AIR.getDefaultState());
            for (int s : new int[] { -1, 1 }) { canvas.set(59, y, 5 * s, pane(Direction.Axis.X)); canvas.set(58, y, 4 * s, Blocks.AIR.getDefaultState()); }
        }
    }
    private void window(int x, int z, int y0, int y1) { window(x, z, y0, y1, Direction.Axis.X); }
    /** A narrow window deep in the double wall, with a sill and a little hood. */
    private void window(int x, int z, int y0, int y1, Direction.Axis along) {
        int inX = along == Direction.Axis.Z ? (x < 20 ? 1 : -1) : 0, inZ = along == Direction.Axis.X ? (z < 0 ? 1 : -1) : 0;
        for (int y = y0; y <= y1; y++) { canvas.set(x, y, z, pane(along)); canvas.set(x + inX, y, z + inZ, Blocks.AIR.getDefaultState()); }
        canvas.set(x, y0 - 1, z, accent());
        canvas.set(x, y1 + 1, z, accent());
    }
    private void door(int x, int z, Direction facing) {
        canvas.set(x, 1, z, Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.LOWER).with(DoorBlock.HINGE, DoorHinge.LEFT));
        canvas.set(x, 2, z, Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.UPPER).with(DoorBlock.HINGE, DoorHinge.LEFT));
    }

    // ---- Inside, after Russian parish churches: the panikadilo under the drum, the analoy with the feast's icon and the big
    // candle stand behind it, candle stands before the great icons, the memorial table (kanun) with its crucifix, the choirs'
    // lecterns, icons in kiots on the walls, a carpet runner, benches only along the walls. ----

    private void interior() {
        int top = cubeTop();
        int chandelierY = stone ? 13 : 12, chainTop = stone ? 31 : top + 10;
        canvas.set(NAVE_X, chandelierY, 0, ChurchBlocks.CHANDELIER.getDefaultState());
        for (int y = chandelierY + 2; y <= chainTop; y++) canvas.set(NAVE_X, y, 0, Blocks.CHAIN.getDefaultState());
        // The refectory's smaller horos and the tower's lamp.
        canvas.set(21, 7, 0, ChurchBlocks.CHANDELIER.getDefaultState());
        canvas.set(21, 9, 0, Blocks.CHAIN.getDefaultState());
        for (int y = 6; y <= 14; y++) canvas.set(6, y, 0, Blocks.CHAIN.getDefaultState());
        canvas.set(6, 5, 0, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        // The carpet runner from the door to the analoy.
        for (int x = 2; x <= 39; x++) for (int z = -1; z <= 1; z++) canvas.set(x, 1, z, (z == 0 ? Blocks.RED_CARPET : Blocks.YELLOW_CARPET).getDefaultState());
        canvas.set(40, 1, 0, ChurchBlocks.ANALOY.getDefaultState().with(ChurchBlocks.Facing.FACING, Direction.WEST));
        canvas.set(41, 1, 0, ChurchBlocks.CANDLE_STAND.getDefaultState());
        // Candle stands before the great icons of the Saviour and the Theotokos; the choirs' lecterns either side of the soleas.
        canvas.set(44, 1, 3, ChurchBlocks.CANDLE_STAND.getDefaultState());
        canvas.set(44, 1, -3, ChurchBlocks.CANDLE_STAND.getDefaultState());
        for (int z : new int[] { -8, 8 }) canvas.set(45, 1, z, Blocks.LECTERN.getDefaultState().with(LecternBlock.FACING, Direction.WEST));
        // The kanun: a candle table before a crucifix in the north-west corner.
        canvas.set(33, 1, -9, ChurchBlocks.CANDLE_STAND.getDefaultState());
        canvas.set(33, 2, -10, ChurchBlocks.DOME_CROSS.getDefaultState().with(ChurchBlocks.DomeCrossBlock.AXIS, Direction.Axis.X));
        // Kiots: icons on the side walls with a candle stand before each.
        int[] saints = { ChurchBlocks.IconBlock.NICHOLAS, ChurchBlocks.IconBlock.TRINITY, ChurchBlocks.IconBlock.MICHAEL,
                ChurchBlocks.IconBlock.BAPTIST, ChurchBlocks.IconBlock.THEOTOKOS, ChurchBlocks.IconBlock.NICHOLAS };
        int i = 0;
        for (int z : new int[] { -10, 10 })
            for (int x : new int[] { 37, 38, 46 }) {
                canvas.set(x, 4, z, icon(saints[i++ % saints.length], z < 0 ? Direction.SOUTH : Direction.NORTH));
            }
        for (int z : new int[] { -9, 9 }) canvas.set(37, 1, z, ChurchBlocks.CANDLE_STAND.getDefaultState());
        // Benches for the old and the weak, only along the walls.
        for (int x = 31; x <= 35; x++) {
            if (x > 34) canvas.set(x, 1, -10, stair(Blocks.DARK_OAK_STAIRS, Direction.NORTH, StairShape.STRAIGHT));
            canvas.set(x, 1, 10, stair(Blocks.DARK_OAK_STAIRS, Direction.SOUTH, StairShape.STRAIGHT));
        }
        for (int x = 14; x <= 28; x++) {
            if (x == 20 || x == 21 || x == 22) continue;
            canvas.set(x, 1, -7, stair(Blocks.DARK_OAK_STAIRS, Direction.NORTH, StairShape.STRAIGHT));
            canvas.set(x, 1, 7, stair(Blocks.DARK_OAK_STAIRS, Direction.SOUTH, StairShape.STRAIGHT));
        }
        // Icons along the refectory walls, the candle counter by the tower door.
        for (int x : new int[] { 17, 25 }) {
            canvas.set(x, 4, -7, icon(x == 17 ? ChurchBlocks.IconBlock.THEOTOKOS : ChurchBlocks.IconBlock.NICHOLAS, Direction.SOUTH));
            canvas.set(x, 4, 7, icon(x == 17 ? ChurchBlocks.IconBlock.CHRIST : ChurchBlocks.IconBlock.TRINITY, Direction.NORTH));
        }
        for (int z = 3; z <= 4; z++) canvas.set(9, 1, z, Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        canvas.set(9, 2, 3, candles(3, false));
        canvas.set(10, 4, 5, icon(ChurchBlocks.IconBlock.MICHAEL, Direction.NORTH));
        // A few cobwebs high in the tower.
        for (int k = 0; k < 4; k++) canvas.set(2 + random.nextInt(8), 11 + random.nextInt(3), random.nextBoolean() ? -4 : 4, Blocks.COBWEB.getDefaultState());
    }
    private static BlockState candles(int count, boolean lit) {
        return Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, count).with(CandleBlock.LIT, lit);
    }
    private static BlockState icon(int art, Direction facing) {
        return ChurchBlocks.ICON_PANEL.getDefaultState().with(ChurchBlocks.IconBlock.ART, art).with(ChurchBlocks.Facing.FACING, facing);
    }

    /** The iconostasis across the cube at x = 48, on the raised soleas with its ambo: the local tier (the royal doors, the great
     * icons of the Saviour and the Theotokos, the deacon doors with archangels, the temple's icon), the Deesis, the feasts and
     * the prophets on gilded beams, the Crucifixion over all. Icons hang on its west face (x = 47); the royal doors stand in
     * its line. */
    private void iconostasis() {
        int x = ICONOSTASIS_X, top = stone ? 15 : 13;
        BlockState back = stone ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState beam = stone ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Z);
        BlockState column = stone ? Blocks.RAW_GOLD_BLOCK.getDefaultState() : Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState();
        BlockState soleas = (stone ? Blocks.SMOOTH_QUARTZ_SLAB : Blocks.SPRUCE_SLAB).getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int z = -10; z <= 10; z++) { canvas.set(46, 1, z, soleas); canvas.set(47, 1, z, soleas); }
        for (int z = -2; z <= 2; z++) canvas.set(45, 1, z, soleas);
        for (int z = -10; z <= 10; z++)
            for (int y = 1; y <= top; y++) canvas.set(x, y, z, y == 5 || y == 8 || y == 11 ? beam : back);
        // The local tier: (z) -10 col, -9..-8 St Nicholas and the Trinity, -7 col, -6 north deacon door, -5 col,
        // -4..-3 the Theotokos, -2 col, -1..0 the royal doors, 1 col, 2..3 the Saviour, 4 col, 5 south deacon door, 6 col,
        // 7..8 the Baptist and Michael, 9..10 col.
        for (int z : new int[] { -10, -7, -5, -2, 1, 4, 6, 9, 10 }) for (int y = 1; y <= 4; y++) canvas.set(x - 1, y, z, column);
        large(x - 1, -4, ChurchBlocks.IconBlock.THEOTOKOS_LARGE);
        large(x - 1, 2, ChurchBlocks.IconBlock.CHRIST_LARGE);
        for (int y = 2; y <= 3; y++) {
            canvas.set(x - 1, y, -9, icon(y == 3 ? ChurchBlocks.IconBlock.NICHOLAS : ChurchBlocks.IconBlock.TRINITY, Direction.WEST));
            canvas.set(x - 1, y, -8, icon(y == 3 ? ChurchBlocks.IconBlock.TRINITY : ChurchBlocks.IconBlock.NICHOLAS, Direction.WEST));
            canvas.set(x - 1, y, 7, icon(y == 3 ? ChurchBlocks.IconBlock.BAPTIST : ChurchBlocks.IconBlock.MICHAEL, Direction.WEST));
            canvas.set(x - 1, y, 8, icon(y == 3 ? ChurchBlocks.IconBlock.MICHAEL : ChurchBlocks.IconBlock.BAPTIST, Direction.WEST));
        }
        // The royal doors in the iconostasis' own line, the two leaves three blocks high; air behind them is the sanctuary.
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 2; c++) canvas.set(x, 3 - r, -1 + c, icon(ChurchBlocks.IconBlock.ROYAL_DOORS + r * 2 + c, Direction.WEST));
        canvas.set(x, 4, -1, beam); canvas.set(x, 4, 0, beam);
        // The deacon doors: real doors through to the sanctuary, an archangel over each.
        for (int z : new int[] { -6, 5 }) {
            door(x, z, Direction.EAST);
            canvas.set(x - 1, 3, z, icon(ChurchBlocks.IconBlock.MICHAEL, Direction.WEST));
        }
        // The upper tiers: Deesis (y 6..7, larger: two icons a saint), the feasts (y 9..10), the prophets (y 12..13).
        int[] tier = { ChurchBlocks.IconBlock.NICHOLAS, ChurchBlocks.IconBlock.MICHAEL, ChurchBlocks.IconBlock.BAPTIST, ChurchBlocks.IconBlock.THEOTOKOS,
                ChurchBlocks.IconBlock.CHRIST, ChurchBlocks.IconBlock.THEOTOKOS, ChurchBlocks.IconBlock.BAPTIST, ChurchBlocks.IconBlock.MICHAEL, ChurchBlocks.IconBlock.NICHOLAS };
        for (int z = -9; z <= 9; z++) {
            int a = Math.abs(z);
            canvas.set(x - 1, 6, z, icon(tier[Math.min(8, (z + 9) / 2)], Direction.WEST));
            canvas.set(x - 1, 7, z, icon(z == 0 ? ChurchBlocks.IconBlock.CHRIST : tier[Math.min(8, (z + 9) / 2)], Direction.WEST));
            if (a % 2 == 0 || a < 8) canvas.set(x - 1, 9, z, icon((z + 10) % 6, Direction.WEST));
            canvas.set(x - 1, 10, z, icon(z == 0 ? ChurchBlocks.IconBlock.TRINITY : (z + 13) % 6, Direction.WEST));
            if (a <= 7) { canvas.set(x - 1, 12, z, icon(a % 2 == 0 ? ChurchBlocks.IconBlock.BAPTIST : ChurchBlocks.IconBlock.NICHOLAS, Direction.WEST)); }
            if (a <= 5 && top >= 14) canvas.set(x - 1, 13, z, icon(z == 0 ? ChurchBlocks.IconBlock.THEOTOKOS : ChurchBlocks.IconBlock.MICHAEL, Direction.WEST));
        }
        // The tall crucifix over the iconostasis.
        canvas.set(x, top + 1, 0, beam);
        canvas.set(x, top + 3, 0, ChurchBlocks.DOME_CROSS.getDefaultState().with(ChurchBlocks.DomeCrossBlock.AXIS, Direction.Axis.Z));
        // Tall candle stands on the soleas by the royal doors.
        canvas.set(46, 1, -2, ChurchBlocks.CANDLE_STAND.getDefaultState());
        canvas.set(46, 1, 1, ChurchBlocks.CANDLE_STAND.getDefaultState());
        // Before the deacon doors the soleas steps down to the floor, so one walks through them upright.
        for (int z : new int[] { -6, 5 }) { canvas.set(46, 1, z, Blocks.AIR.getDefaultState()); canvas.set(47, 1, z, Blocks.AIR.getDefaultState()); }
    }
    /** A great 2 x 2 icon at x, its left (north) column at z, its bottom at y = 2. */
    private void large(int x, int z, int first) {
        canvas.set(x, 3, z, icon(first, Direction.WEST)); canvas.set(x, 3, z + 1, icon(first + 1, Direction.WEST));
        canvas.set(x, 2, z, icon(first + 2, Direction.WEST)); canvas.set(x, 2, z + 1, icon(first + 3, Direction.WEST));
    }

    /** Behind the iconostasis: the god's altar as the holy table, the seven-branched stand behind it, the table of oblation, the
     * vestry chest, the high place with the Saviour's icon. */
    private void sanctuary() {
        for (int x = 49; x <= 60; x++)
            for (int z = -11; z <= 11; z++)
                if (x <= 53 || apse(x, z) <= 5.5)
                    canvas.set(x, 0, z, stone ? Blocks.POLISHED_DEEPSLATE.getDefaultState() : Blocks.DARK_OAK_PLANKS.getDefaultState());
        for (int x = 53; x <= 57; x++) for (int z = -2; z <= 2; z++) canvas.set(x, 1, z, Blocks.RED_CARPET.getDefaultState());
        canvas.set(ALTAR_X, 1, ALTAR_Z, DivineBlockMod.ALTAR.getDefaultState());
        canvas.set(57, 1, 0, ChurchBlocks.CANDLE_STAND.getDefaultState());
        canvas.chest(CHEST_X, 1, CHEST_Z, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));
        for (int x = 51; x <= 52; x++) canvas.set(x, 1, -7, Blocks.DARK_OAK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        canvas.set(51, 2, -7, candles(2, true));
        canvas.set(59, 2, -2, icon(ChurchBlocks.IconBlock.CHRIST, Direction.WEST));
        canvas.set(59, 2, 2, icon(ChurchBlocks.IconBlock.THEOTOKOS, Direction.WEST));
    }

    // ---- Shapes. ----

    /** The gilded apple on a dome's tip at y, the eight-pointed cross above it (the cross block's middle at y + 2). */
    private void crossOn(int x, int y, int z) {
        canvas.set(x, y, z, ChurchBlocks.CROSS_APPLE.getDefaultState());
        canvas.set(x, y + 1, z, Blocks.AIR.getDefaultState());
        canvas.set(x, y + 2, z, ChurchBlocks.DOME_CROSS.getDefaultState().with(ChurchBlocks.DomeCrossBlock.AXIS, Direction.Axis.Z));
        canvas.set(x, y + 3, z, Blocks.AIR.getDefaultState());
    }

    /** One ring of a hipped roof: stairs facing inwards, corners turned. */
    private void ring(int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean n = z == z0, s = z == z1, w = x == x0, e = x == x1;
                if (!(n || s || w || e)) continue;
                BlockState state;
                if (s && e) state = stair(roofStairs, Direction.NORTH, StairShape.OUTER_LEFT);
                else if (s && w) state = stair(roofStairs, Direction.NORTH, StairShape.OUTER_RIGHT);
                else if (n && e) state = stair(roofStairs, Direction.SOUTH, StairShape.OUTER_RIGHT);
                else if (n && w) state = stair(roofStairs, Direction.SOUTH, StairShape.OUTER_LEFT);
                else if (n) state = stair(roofStairs, Direction.SOUTH, StairShape.STRAIGHT);
                else if (s) state = stair(roofStairs, Direction.NORTH, StairShape.STRAIGHT);
                else if (w) state = stair(roofStairs, Direction.EAST, StairShape.STRAIGHT);
                else state = stair(roofStairs, Direction.WEST, StairShape.STRAIGHT);
                canvas.set(x, y, z, state);
            }
    }

    /** An onion dome on a drum of radius drum: swelling to radius widest, then drawn to a point. Blue domes get golden stars. */
    private void onion(int cx, int cz, int y0, double drum, double widest, int height, BlockState material, boolean stars) {
        for (int h = 0; h < height; h++) {
            double t = (h + 0.5) / height, r;
            if (t < 0.35) r = drum + (widest - drum) * Math.sin(t / 0.35 * Math.PI / 2);
            else r = widest * Math.pow(Math.cos((t - 0.35) / 0.65 * Math.PI / 2), 1.25);
            int span = (int) Math.ceil(r) + 1;
            for (int x = cx - span; x <= cx + span; x++)
                for (int z = cz - span; z <= cz + span; z++)
                    if (Math.hypot(x - cx, z - cz) <= Math.max(0.4, r))
                        canvas.set(x, y0 + h, z, stars && random.nextInt(9) == 0 ? Blocks.GOLD_BLOCK.getDefaultState() : material);
        }
    }

    private void disc(int cx, int cz, int y, double r, BlockState state) {
        int span = (int) Math.ceil(r);
        for (int x = cx - span; x <= cx + span; x++)
            for (int z = cz - span; z <= cz + span; z++)
                if (Math.hypot(x - cx, z - cz) <= r) canvas.set(x, y, z, state);
    }
    private void octagonDisc(int cx, int cz, int y, double r, BlockState state) {
        int span = (int) Math.ceil(r);
        for (int x = cx - span; x <= cx + span; x++)
            for (int z = cz - span; z <= cz + span; z++)
                if (inOctagon(x - cx, z - cz, r)) canvas.set(x, y, z, state);
    }
    static boolean inOctagon(int dx, int dz, double r) {
        return Math.abs(dx) <= r && Math.abs(dz) <= r && Math.abs(dx) + Math.abs(dz) <= r * 1.42;
    }

    static BlockState stair(Block block, Direction facing, StairShape shape) { return stair(block, facing, shape, BlockHalf.BOTTOM); }
    static BlockState stair(Block block, Direction facing, StairShape shape, BlockHalf half) {
        return block.getDefaultState().with(StairsBlock.FACING, facing).with(StairsBlock.SHAPE, shape).with(StairsBlock.HALF, half);
    }
    static BlockState pane(Direction.Axis along) {
        BlockState pane = Blocks.GLASS_PANE.getDefaultState();
        return along == Direction.Axis.X ? pane.with(PaneBlock.EAST, true).with(PaneBlock.WEST, true) : pane.with(PaneBlock.NORTH, true).with(PaneBlock.SOUTH, true);
    }

    /** A weighted pick: block, weight, block, weight… */
    private BlockState pick(Object... choices) {
        int total = 0;
        for (int i = 1; i < choices.length; i += 2) total += (Integer) choices[i];
        int roll = random.nextInt(total);
        for (int i = 0; i < choices.length; i += 2) {
            roll -= (Integer) choices[i + 1];
            if (roll < 0) return ((Block) choices[i]).getDefaultState();
        }
        return ((Block) choices[0]).getDefaultState();
    }
}
