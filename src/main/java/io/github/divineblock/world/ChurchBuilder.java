package io.github.divineblock.world;

import io.github.divineblock.DivineBlockMod;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.GlazedTerracottaBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.RodBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.Attachment;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

/** Draws the Orthodox church block by block in its own coordinates, an original design after photographs of Russian village
 * churches (white stone with three zakomaras a side and onion domes, or a northern log church under a tall tent). East is +x
 * (the altar stands to the east, as in every Orthodox church), south is +z, y = 0 is the floor; the ground lies at y = -1.
 *
 * <pre>
 *   x: -3..-1 porch, 0..6 bell tower, 6..15 refectory, 15..29 nave (iconostasis at x = 27), 29..35 apse with the altar
 *   z: -7..7 the nave; the refectory -5..5; the tower -3..3; the churchyard lies south (see {@link #CEMETERY_MIN_X})
 * </pre>
 * The whole drawing is turned with the churchyard when it is placed ({@link ChurchPiece.Frame}).
 * The whole drawing runs again for every chunk the church touches; the canvas keeps only that chunk's blocks, so the random
 * choices must come in the same order every time (one seeded random, no branching on what the world holds). */
public final class ChurchBuilder {
    public enum Variant { STONE, WOOD }

    /** The churchyard south of the church, in the same coordinates (21 x 27, ground at y = -1): the cemetery pool's entrance
     * jigsaw lands on (ENTRANCE_X, -1, MIN_Z) and the graves run south from it. */
    public static final int CEMETERY_MIN_X = 8, CEMETERY_MAX_X = 28, CEMETERY_MIN_Z = 10, CEMETERY_MAX_Z = 36, CEMETERY_ENTRANCE_X = 18;
    /** Everything the church itself may touch (the cemetery is its own piece). */
    public static final int MIN_X = -4, MAX_X = 36, MIN_Z = -8, MAX_Z = 8, MIN_Y = -14, MAX_Y = 44;
    /** Where the god's altar stands (the holy table behind the royal doors) and the middle of the nave floor. */
    public static final int ALTAR_X = 31, ALTAR_Z = 0, NAVE_X = 22, NAVE_Z = 0;
    /** The chest in the sanctuary (the vestry corner, north of the altar). */
    public static final int CHEST_X = 32, CHEST_Z = -3;
    /** The boss seal under the sanctuary floor (placed by {@link ChurchPiece} when the bosses' block exists). */
    public static final int SEAL_X = 32, SEAL_Z = 2;

    /** Where the blocks go; the piece clips them to the chunk being generated. */
    public interface Canvas {
        void set(int x, int y, int z, BlockState state);
        /** Fills downwards from y until the ground: the foundation under walls standing on a slope. */
        void foundation(int x, int y, int z, BlockState state);
        void chest(int x, int y, int z, BlockState state);
    }

    private final Canvas canvas;
    private final Random random;
    private final Variant variant;
    /** Stone churches have one great dome or five; their palette of dome colours. */
    private final boolean fiveDomes;
    private final BlockState mainDome, smallDome, roof, roofBlock;
    private final Block roofStairs, roofSlab;

    public ChurchBuilder(Canvas canvas, long seed, Variant variant) {
        this.canvas = canvas;
        this.random = Random.create(seed);
        this.variant = variant;
        if (variant == Variant.STONE) {
            fiveDomes = random.nextInt(3) > 0;
            mainDome = random.nextInt(3) > 0 ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.BLUE_CONCRETE.getDefaultState();
            smallDome = random.nextBoolean() ? Blocks.BLUE_CONCRETE.getDefaultState() : Blocks.BLACK_CONCRETE.getDefaultState();
            boolean green = random.nextInt(3) > 0;
            roofStairs = green ? Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS : Blocks.WAXED_WEATHERED_CUT_COPPER_STAIRS;
            roofSlab = green ? Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB : Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB;
            roofBlock = (green ? Blocks.WAXED_OXIDIZED_CUT_COPPER : Blocks.WAXED_WEATHERED_CUT_COPPER).getDefaultState();
        } else {
            fiveDomes = false;
            mainDome = Blocks.POLISHED_ANDESITE.getDefaultState();
            smallDome = mainDome;
            roofStairs = Blocks.SPRUCE_STAIRS;
            roofSlab = Blocks.SPRUCE_SLAB;
            roofBlock = Blocks.SPRUCE_PLANKS.getDefaultState();
        }
        roof = roofBlock;
    }

    public void build() {
        clear();
        foundations();
        floors();
        if (variant == Variant.STONE) stoneShell(); else woodShell();
        bellTower();
        refectoryRoof();
        apse();
        if (variant == Variant.STONE) stoneTop(); else woodTop();
        openings();
        interior();
        iconostasis();
        sanctuary();
        surroundings();
    }

    // ---- Ground. ----

    private boolean inChurch(int x, int z) {
        if (x >= 0 && x <= 6 && Math.abs(z) <= 3) return true;
        if (x >= 6 && x <= 15 && Math.abs(z) <= 5) return true;
        if (x >= 15 && x <= 29 && Math.abs(z) <= 7) return true;
        return x > 29 && apseDistance(x, z) <= 6.3;
    }
    private static double apseDistance(int x, int z) { return Math.sqrt((x - 29) * (x - 29) + z * z); }

    /** Air above the floor where the church stands, and a little around it, so trees and hills do not grow through it. */
    private void clear() {
        for (int x = MIN_X; x <= MAX_X; x++)
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                boolean near = inChurch(x, z) || inChurch(x + 1, z) || inChurch(x - 1, z) || inChurch(x, z + 1) || inChurch(x, z - 1)
                        || x >= -4 && x <= -1 && Math.abs(z) <= 3;
                if (!near) continue;
                for (int y = 0; y <= MAX_Y; y++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
            }
    }

    private void foundations() {
        BlockState plinth = variant == Variant.STONE ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
        for (int x = MIN_X; x <= MAX_X; x++)
            for (int z = MIN_Z; z <= MAX_Z; z++)
                if (inChurch(x, z) || x >= -3 && x <= -1 && Math.abs(z) <= 2) canvas.foundation(x, -1, z, plinth);
    }

    private void floors() {
        for (int x = 0; x <= MAX_X; x++)
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (!inChurch(x, z)) continue;
                BlockState floor;
                if (variant == Variant.STONE)
                    floor = ((x + z) & 1) == 0 ? Blocks.POLISHED_ANDESITE.getDefaultState() : Blocks.POLISHED_DEEPSLATE.getDefaultState();
                else floor = (x & 3) == 0 ? Blocks.DARK_OAK_PLANKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
                canvas.set(x, 0, z, floor);
            }
    }

    // ---- Walls. ----

    private BlockState outer(int x, int y, int z, Direction.Axis along) {
        if (variant == Variant.WOOD) return Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, along);
        if (y <= 1) return pick(Blocks.STONE_BRICKS, 6, Blocks.MOSSY_STONE_BRICKS, 2, Blocks.CRACKED_STONE_BRICKS, 1);
        return pick(Blocks.SMOOTH_QUARTZ, 14, Blocks.CALCITE, 2, Blocks.DIORITE, 1);
    }
    /** The inside face: dark wainscot below, frescoes above in the stone church; planed logs in the wooden one. */
    private BlockState inner(int y, Direction.Axis along) {
        if (variant == Variant.WOOD)
            return y <= 2 ? Blocks.DARK_OAK_PLANKS.getDefaultState() : Blocks.STRIPPED_SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, along);
        if (y <= 2) return Blocks.DARK_OAK_PLANKS.getDefaultState();
        // Old soot-darkened frescoes: earthy and muted, a band of ochre under the vault.
        if (y == 9) return Blocks.YELLOW_TERRACOTTA.getDefaultState();
        return pick(Blocks.BROWN_TERRACOTTA, 9, Blocks.TERRACOTTA, 4, Blocks.GRAY_TERRACOTTA, 2, Blocks.RED_TERRACOTTA, 1);
    }
    private BlockState accent() {
        return variant == Variant.STONE ? Blocks.QUARTZ_BRICKS.getDefaultState() : Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState();
    }

    /** A two-layer box wall: outer face at the given bounds, inner face one block in. */
    private void boxWalls(int x0, int x1, int z0, int z1, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                canvas.set(x, y, z0, outer(x, y, z0, Direction.Axis.X)); canvas.set(x, y, z1, outer(x, y, z1, Direction.Axis.X));
                if (x > x0 && x < x1) { canvas.set(x, y, z0 + 1, inner(y, Direction.Axis.X)); canvas.set(x, y, z1 - 1, inner(y, Direction.Axis.X)); }
            }
            for (int z = z0 + 1; z < z1; z++) {
                canvas.set(x0, y, z, outer(x0, y, z, Direction.Axis.Z)); canvas.set(x1, y, z, outer(x1, y, z, Direction.Axis.Z));
                if (z > z0 + 1 && z < z1 - 1) { canvas.set(x0 + 1, y, z, inner(y, Direction.Axis.Z)); canvas.set(x1 - 1, y, z, inner(y, Direction.Axis.Z)); }
            }
        }
    }

    private void stoneShell() {
        boxWalls(6, 15, -5, 5, 1, 6);    // refectory
        boxWalls(15, 29, -7, 7, 1, 12);  // nave
        // Pilasters at the corners and between the three bays of each facade, a cornice under the zakomaras.
        for (int y = 1; y <= 12; y++)
            for (int x : new int[] { 15, 19, 20, 24, 25, 29 }) { canvas.set(x, y, -7, accent()); canvas.set(x, y, 7, accent()); }
        for (int y = 1; y <= 12; y++)
            for (int z : new int[] { -7, -3, -2, 2, 3, 7 }) canvas.set(29, y, z, accent());
        for (int x = 15; x <= 29; x++) { canvas.set(x, 12, -7, accent()); canvas.set(x, 12, 7, accent()); }
        for (int z = -7; z <= 7; z++) canvas.set(29, 12, z, accent());
        // The vault: a starry sky inside, with an opening up into the great drum.
        for (int x = 16; x <= 28; x++)
            for (int z = -6; z <= 6; z++) {
                double d = Math.hypot(x - NAVE_X, z - NAVE_Z);
                canvas.set(x, 12, z, d <= 1.6 ? Blocks.AIR.getDefaultState()
                        : random.nextInt(14) == 0 ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.BLUE_TERRACOTTA.getDefaultState());
            }
        for (int x = 7; x <= 14; x++) for (int z = -4; z <= 4; z++) canvas.set(x, 6, z, Blocks.DARK_OAK_PLANKS.getDefaultState());
    }

    private void woodShell() {
        boxWalls(6, 15, -5, 5, 1, 6);
        boxWalls(15, 29, -7, 7, 1, 8);
        for (int x = 7; x <= 14; x++) for (int z = -4; z <= 4; z++) canvas.set(x, 6, z, Blocks.SPRUCE_PLANKS.getDefaultState());
    }

    // ---- Bell tower: closed tiers, an open belfry with a bell, a tent and a small dome. ----

    private void bellTower() {
        for (int y = 1; y <= 10; y++)
            for (int x = 0; x <= 6; x++)
                for (int z = -3; z <= 3; z++) {
                    boolean edge = x == 0 || x == 6 || Math.abs(z) == 3;
                    if (!edge) continue;
                    boolean corner = (x == 0 || x == 6) && Math.abs(z) == 3;
                    canvas.set(x, y, z, corner || y == 5 || y == 10 ? accent() : outer(x, y, z, x == 0 || x == 6 ? Direction.Axis.Z : Direction.Axis.X));
                }
        BlockState deck = variant == Variant.STONE ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
        for (int x = 0; x <= 6; x++) for (int z = -3; z <= 3; z++) { canvas.set(x, 11, z, deck); canvas.set(x, 16, z, accent()); }
        canvas.set(1, 11, -2, Blocks.AIR.getDefaultState()); // the hatch for the ladder
        // Belfry: corner piers, arches narrowing at the top, a railing in the wooden one.
        for (int y = 12; y <= 15; y++)
            for (int x = 0; x <= 6; x++)
                for (int z = -3; z <= 3; z++) {
                    boolean edge = x == 0 || x == 6 || Math.abs(z) == 3;
                    if (!edge) continue;
                    int along = x == 0 || x == 6 ? Math.abs(z) : Math.abs(x - 3);
                    boolean open = along <= (y == 15 ? 1 : 2);
                    if (!open) canvas.set(x, y, z, variant == Variant.STONE ? outer(x, y, z, Direction.Axis.Y)
                            : Blocks.SPRUCE_LOG.getDefaultState());
                    else if (y == 12 && variant == Variant.WOOD) canvas.set(x, y, z, Blocks.SPRUCE_FENCE.getDefaultState());
                }
        canvas.set(3, 15, 0, Blocks.BELL.getDefaultState().with(BellBlock.ATTACHMENT, Attachment.CEILING).with(BellBlock.FACING, Direction.EAST));
        // Tent, the small dome and its cross.
        BlockState tent = variant == Variant.STONE ? roofBlock : Blocks.SPRUCE_PLANKS.getDefaultState();
        for (int k = 0; k <= 9; k++) octagonDisc(3, 0, 17 + k, 3.6 - k * 0.32, tent);
        // Dormer windows ("slukhi") on the lower tent.
        for (Direction side : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
            int sx = 3 + side.getOffsetX() * 3, sz = side.getOffsetZ() * 3;
            canvas.set(sx, 18, sz, accent());
            canvas.set(sx, 19, sz, accent());
        }
        onion(3, 0, 27, 0.8, 1.7, 5, smallDome.isOf(Blocks.BLACK_CONCRETE) ? mainDome : smallDome, false);
        cross(3, 32, 0, false);
        // Inside: a ladder up to the bell.
        for (int y = 1; y <= 11; y++) canvas.set(1, y, -2, Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.EAST));
    }

    private void refectoryRoof() {
        gable(6, 14, -5, 5, 7);
    }

    /** A gable roof along x over z0..z1 (one block of eaves each side), its ends walled. */
    private void gable(int x0, int x1, int z0, int z1, int y0) {
        BlockState wall = variant == Variant.STONE ? Blocks.SMOOTH_QUARTZ.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
        for (int k = 0; ; k++) {
            int north = z0 - 1 + k, south = z1 + 1 - k, y = y0 + k;
            if (north > south) break;
            for (int x = x0; x <= x1; x++) {
                if (x <= 6 && Math.abs(north) <= 3 && Math.abs(south) <= 3) continue; // the bell tower stands there
                if (north == south) { canvas.set(x, y, north, roofSlab.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM)); continue; }
                if (x > 6 || Math.abs(north) > 3) canvas.set(x, y, north, stair(roofStairs, Direction.SOUTH, StairShape.STRAIGHT));
                if (x > 6 || Math.abs(south) > 3) canvas.set(x, y, south, stair(roofStairs, Direction.NORTH, StairShape.STRAIGHT));
                for (int z = north + 1; z <= south - 1; z++) if (x > 6 || Math.abs(z) > 3) canvas.set(x, y, z, x == x0 || x == x1 ? wall : roof);
            }
            if (north == south) break;
        }
    }

    // ---- Apse: a half-round with a half-dome roof. ----

    private void apse() {
        int top = variant == Variant.STONE ? 9 : 7;
        for (int x = 29; x <= MAX_X; x++)
            for (int z = -7; z <= 7; z++) {
                double d = apseDistance(x, z);
                if (x == 29 || d > 6.3) continue;
                for (int y = 1; y <= top; y++) {
                    if (d > 5.3) canvas.set(x, y, z, variant == Variant.STONE ? outer(x, y, z, Direction.Axis.Y) : Blocks.SPRUCE_WOOD.getDefaultState());
                    else if (d > 4.3) canvas.set(x, y, z, variant == Variant.STONE ? inner(y, Direction.Axis.Y) : Blocks.STRIPPED_SPRUCE_WOOD.getDefaultState());
                }
            }
        for (int level = 0; level <= 4; level++) {
            double radius = 6.3 - level * level * 0.35 - level * 0.5;
            for (int x = 30; x <= MAX_X; x++)
                for (int z = -7; z <= 7; z++)
                    if (apseDistance(x, z) <= radius) canvas.set(x, top + 1 + level, z, roof);
        }
    }

    // ---- The nave's top. ----

    private void stoneTop() {
        // A hipped roof under the zakomaras, rising to the drum.
        for (int k = 0; k <= 5; k++) ring(15 + k, 29 - k, -7 + k, 7 - k, 13 + k);
        for (int x = 20; x <= 24; x++) for (int z = -2; z <= 2; z++) canvas.set(x, 18, z, roof);
        // Zakomaras: three half-rounds on each facade.
        for (int x = 15; x <= 29; x++) {
            int h = zakomara(x - 15);
            for (int y = 13; y <= 12 + h; y++) { canvas.set(x, y, -7, wallWhite()); canvas.set(x, y, 7, wallWhite()); }
            if (h > 0) { canvas.set(x, 13 + h, -7, roofSlab.getDefaultState()); canvas.set(x, 13 + h, 7, roofSlab.getDefaultState()); }
        }
        for (int z = -7; z <= 7; z++) {
            int h = zakomara(z + 7);
            for (int y = 13; y <= 12 + h; y++) { canvas.set(29, y, z, wallWhite()); canvas.set(15, y, z, wallWhite()); }
            if (h > 0) { canvas.set(29, 13 + h, z, roofSlab.getDefaultState()); canvas.set(15, 13 + h, z, roofSlab.getDefaultState()); }
        }
        // The great drum with its windows, a cornice and the onion.
        for (int y = 13; y <= 21; y++)
            for (int x = NAVE_X - 3; x <= NAVE_X + 3; x++)
                for (int z = -3; z <= 3; z++) {
                    double d = Math.hypot(x - NAVE_X, z);
                    if (d <= 1.6) canvas.set(x, y, z, y == 21 ? Blocks.BLUE_TERRACOTTA.getDefaultState() : Blocks.AIR.getDefaultState());
                    else if (d <= 2.6) canvas.set(x, y, z, y == 21 || y == 13 ? accent() : wallWhite());
                }
        for (int y = 16; y <= 18; y++) {
            canvas.set(NAVE_X + 2, y, 0, pane(Direction.Axis.Z)); canvas.set(NAVE_X - 2, y, 0, pane(Direction.Axis.Z));
            canvas.set(NAVE_X, y, 2, pane(Direction.Axis.X)); canvas.set(NAVE_X, y, -2, pane(Direction.Axis.X));
        }
        onion(NAVE_X, 0, 22, 2.6, 4.2, 9, mainDome, mainDome.isOf(Blocks.BLUE_CONCRETE));
        cross(NAVE_X, 31, 0, true);
        if (fiveDomes)
            for (int[] c : new int[][] { { 17, -5 }, { 27, -5 }, { 17, 5 }, { 27, 5 } }) {
                for (int y = 14; y <= 18; y++) disc(c[0], c[1], y, 1.5, y == 18 ? accent() : wallWhite());
                onion(c[0], c[1], 19, 1.5, 2.4, 6, smallDome, !smallDome.isOf(Blocks.GOLD_BLOCK));
                cross(c[0], 25, c[1], false);
            }
    }
    private BlockState wallWhite() { return pick(Blocks.SMOOTH_QUARTZ, 14, Blocks.CALCITE, 2); }
    /** The height of the zakomara over a 15-wide facade: three half-rounds of five. */
    private static int zakomara(int along) {
        int d = Math.abs(along % 5 - 2);
        return d == 0 ? 2 : d == 1 ? 2 : 1;
    }

    private void woodTop() {
        // The octagon on the square, its corners roofed, the tall tent, the drum and a small onion: the northern tented church.
        BlockState log = Blocks.SPRUCE_WOOD.getDefaultState();
        for (int y = 9; y <= 13; y++)
            for (int x = 15; x <= 29; x++)
                for (int z = -7; z <= 7; z++) {
                    int dx = x - NAVE_X;
                    boolean in7 = inOctagon(dx, z, 7.0), in6 = inOctagon(dx, z, 6.0);
                    if (in7 && !in6) canvas.set(x, y, z, y == 13 ? accent() : log);
                    else if (y == 9 && !in7) canvas.set(x, y, z, roofSlab.getDefaultState());
                    else if (y == 9 && in6 && (Math.abs(dx) >= 6 || Math.abs(z) >= 6)) canvas.set(x, y, z, Blocks.SPRUCE_PLANKS.getDefaultState());
                }
        for (int k = 1; k <= 12; k++) octagonDisc(NAVE_X, 0, 14 + k, 7.2 - k * 0.5, roof);
        octagonDisc(NAVE_X, 0, 14, 7.6, Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        // The "sky" ceiling of the octagon: blue with a golden sun.
        for (int x = 16; x <= 28; x++)
            for (int z = -6; z <= 6; z++)
                if (inOctagon(x - NAVE_X, z, 6.0))
                    canvas.set(x, 14, z, Math.hypot(x - NAVE_X, z) <= 1.5 ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.BLUE_TERRACOTTA.getDefaultState());
        for (int y = 27; y <= 28; y++) disc(NAVE_X, 0, y, 1.2, Blocks.SPRUCE_WOOD.getDefaultState());
        onion(NAVE_X, 0, 29, 1.2, 2.4, 6, mainDome, false);
        cross(NAVE_X, 35, 0, true);
        // Two small onions over the refectory gable and the apse: the many-domed northern silhouette.
        onion(15, 0, 14, 0.8, 1.5, 4, smallDome, false);
        cross(15, 18, 0, false);
        onion(33, 0, 13, 0.8, 1.5, 4, smallDome, false);
        cross(33, 17, 0, false);
    }

    // ---- Doors, windows. ----

    private void openings() {
        // West door of the tower under a stepped portal.
        door(0, 0, Direction.EAST);
        for (int y = 1; y <= 3; y++) { canvas.set(0, y, -1, accent()); canvas.set(0, y, 1, accent()); }
        canvas.set(0, 3, 0, accent());
        canvas.set(-1, 3, 0, stair(variant == Variant.STONE ? Blocks.QUARTZ_STAIRS : Blocks.SPRUCE_STAIRS, Direction.EAST, StairShape.STRAIGHT, BlockHalf.TOP));
        canvas.set(-1, 4, 0, stair(roofStairs, Direction.EAST, StairShape.STRAIGHT));
        // The arches from the tower into the refectory and from the refectory into the nave (through both double walls).
        for (int y = 1; y <= 3; y++) for (int z = -1; z <= 1; z++) for (int x = 6; x <= 7; x++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
        for (int y = 1; y <= 5; y++) for (int z = -2; z <= 2; z++) for (int x = 14; x <= 16; x++) canvas.set(x, y, z, Blocks.AIR.getDefaultState());
        for (int z = -1; z <= 1; z++) for (int x = 15; x <= 16; x++) canvas.set(x, 6, z, Blocks.AIR.getDefaultState());
        // The opening from the nave into the sanctuary (the iconostasis stands in it).
        for (int y = 1; y <= 8; y++) for (int z = -4; z <= 4; z++) { canvas.set(28, y, z, Blocks.AIR.getDefaultState()); canvas.set(29, y, z, Blocks.AIR.getDefaultState()); }
        // Narrow windows, deep in the double wall.
        for (int x : new int[] { 9, 12 }) window(x, -5, Direction.Axis.X, 2, 4);
        for (int x : new int[] { 9, 12 }) window(x, 5, Direction.Axis.X, 2, 4);
        for (int x : new int[] { 17, 22, 27 }) { window(x, -7, Direction.Axis.X, 3, 6); window(x, 7, Direction.Axis.X, 3, 6); }
        if (variant == Variant.STONE) for (int x : new int[] { 17, 22, 27 }) { window(x, -7, Direction.Axis.X, 9, 10); window(x, 7, Direction.Axis.X, 9, 10); }
        for (int y = 3; y <= 5; y++) { canvas.set(35, y, 0, pane(Direction.Axis.Z)); canvas.set(34, y, 0, Blocks.AIR.getDefaultState()); }
        for (int y = 6; y <= 8; y++) canvas.set(0, y, 1, pane(Direction.Axis.Z)); // not over the ladder at z = -2
    }
    private void window(int x, int z, Direction.Axis along, int y0, int y1) {
        int in = z < 0 ? 1 : -1;
        for (int y = y0; y <= y1; y++) { canvas.set(x, y, z, pane(along)); canvas.set(x, y, z + in, Blocks.AIR.getDefaultState()); }
    }
    private void door(int x, int z, Direction facing) {
        canvas.set(x, 1, z, Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.LOWER).with(DoorBlock.HINGE, DoorHinge.LEFT));
        canvas.set(x, 2, z, Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.UPPER).with(DoorBlock.HINGE, DoorHinge.LEFT));
    }

    // ---- Inside: candles, a hanging chandelier, benches along the walls, cobwebs high up. ----

    private void interior() {
        // The horos under the drum: a cross of slabs on a chain, candles at its ends, a lantern beneath.
        int horosY = variant == Variant.STONE ? 7 : 8, chainTop = variant == Variant.STONE ? 20 : 13;
        for (int y = horosY + 1; y <= chainTop; y++) canvas.set(NAVE_X, y, 0, Blocks.CHAIN.getDefaultState());
        BlockState slab = Blocks.DARK_OAK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);
        canvas.set(NAVE_X, horosY, 0, Blocks.GOLD_BLOCK.getDefaultState());
        canvas.set(NAVE_X, horosY - 1, 0, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        for (Direction side : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST })
            for (int r = 1; r <= 2; r++) {
                canvas.set(NAVE_X + side.getOffsetX() * r, horosY, side.getOffsetZ() * r, slab);
                if (r == 2) canvas.set(NAVE_X + side.getOffsetX() * r, horosY + 1, side.getOffsetZ() * r, candles(2, true));
            }
        // Candle stands before the iconostasis and the memorial table at the west end.
        for (int z : new int[] { -3, 3 }) candleStand(25, z, 3);
        candleStand(24, -5, 2);
        candleStand(24, 5, 2);
        for (int x = 17; x <= 19; x++) {
            canvas.set(x, 1, -4, Blocks.DARK_OAK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
            if (random.nextInt(3) > 0) canvas.set(x, 2, -4, candles(1 + random.nextInt(3), random.nextInt(3) > 0));
        }
        // The lectern with the gospel stands before the royal doors.
        canvas.set(25, 1, 0, Blocks.LECTERN.getDefaultState().with(LecternBlock.FACING, Direction.WEST));
        // Benches for the old along the walls.
        for (int x = 20; x <= 23; x++) {
            canvas.set(x, 1, -5, stair(Blocks.DARK_OAK_STAIRS, Direction.NORTH, StairShape.STRAIGHT));
            canvas.set(x, 1, 5, stair(Blocks.DARK_OAK_STAIRS, Direction.SOUTH, StairShape.STRAIGHT));
        }
        // The refectory: a long table and a single lamp; the porch tower dark but for one candle.
        for (int x = 9; x <= 12; x++) canvas.set(x, 1, -2, Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        canvas.set(11, 2, -2, candles(3, true));
        canvas.set(10, 5, 0, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        canvas.set(5, 1, 2, Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        canvas.set(5, 2, 2, candles(1, true));
        // Cobwebs and dust where no one reaches.
        int roofLine = variant == Variant.STONE ? 11 : 8;
        for (int i = 0; i < 14; i++) {
            int x = 17 + random.nextInt(11), z = random.nextBoolean() ? -5 : 5;
            canvas.set(x, roofLine - random.nextInt(2), z, Blocks.COBWEB.getDefaultState());
        }
        for (int i = 0; i < 6; i++) canvas.set(1 + random.nextInt(5), 6 + random.nextInt(4), random.nextBoolean() ? -2 : 2, Blocks.COBWEB.getDefaultState());
    }
    private void candleStand(int x, int z, int candles) {
        canvas.set(x, 1, z, Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState());
        canvas.set(x, 2, z, Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState());
        canvas.set(x, 3, z, candles(candles, true));
    }
    private static BlockState candles(int count, boolean lit) {
        return Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, count).with(CandleBlock.LIT, lit);
    }

    /** The iconostasis across the nave at x = 27: the local tier with the royal and deacon doors, the feasts, the prophets and the cross. */
    private void iconostasis() {
        int x = 27;
        boolean stone = variant == Variant.STONE;
        BlockState frame = stone ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState();
        BlockState beam = stone ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.DARK_OAK_PLANKS.getDefaultState();
        Block[] icons = { Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.RED_GLAZED_TERRACOTTA };
        int top = stone ? 10 : 8;
        for (int z = -6; z <= 6; z++)
            for (int y = 1; y <= top; y++) {
                int a = Math.abs(z);
                BlockState state;
                if (a == 6) state = frame;
                else if (y <= 3) {
                    if (a <= 1) state = y == 3 && a == 1 ? beam : Blocks.AIR.getDefaultState();            // royal doors
                    else if (a == 4) state = y <= 2 ? Blocks.AIR.getDefaultState() : icon(icons[2], z); // deacon doors
                    else if (a == 5) state = frame;
                    else state = icon(a == 2 ? icons[0] : icons[1], z);
                } else if (y == 4 || y == 7) state = beam;
                else if (y <= 6) state = icon(icons[(a + y) % 2 == 0 ? 0 : 1], z);
                else if (y <= 9) state = a <= 4 ? icon(icons[(a + y) % 3 == 0 ? 3 : 0], z) : Blocks.AIR.getDefaultState();
                else state = a == 0 ? frame : Blocks.AIR.getDefaultState();
                canvas.set(x, y, z, state);
            }
        // The crucifix over all.
        canvas.set(x, top + 1, 0, frame);
        canvas.set(x, top + 2, 0, frame);
        canvas.set(x, top + 1, -1, frame);
        canvas.set(x, top + 1, 1, frame);
    }
    private static BlockState icon(Block block, int z) {
        return block.getDefaultState().with(GlazedTerracottaBlock.FACING, z % 2 == 0 ? Direction.WEST : Direction.NORTH);
    }

    /** Behind the royal doors: the god's altar as the holy table, a seven-branched stand, the vestry chest. */
    private void sanctuary() {
        for (int x = 28; x <= 34; x++)
            for (int z = -4; z <= 4; z++)
                if (x <= 29 || apseDistance(x, z) <= 4.3)
                    canvas.set(x, 0, z, variant == Variant.STONE ? Blocks.POLISHED_DEEPSLATE.getDefaultState() : Blocks.DARK_OAK_PLANKS.getDefaultState());
        canvas.set(ALTAR_X, 1, ALTAR_Z, DivineBlockMod.ALTAR.getDefaultState());
        for (int z : new int[] { -1, 1 }) canvas.set(ALTAR_X, 1, z, Blocks.RED_CARPET.getDefaultState());
        canvas.set(33, 1, 0, Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState());
        canvas.set(33, 2, 0, candles(4, true));
        canvas.chest(CHEST_X, 1, CHEST_Z, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));
        canvas.set(30, 1, 3, Blocks.DARK_OAK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        canvas.set(30, 2, 3, candles(1, false));
    }

    /** The porch steps, a path to the west, a lantern by the door. */
    private void surroundings() {
        BlockState step = variant == Variant.STONE ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
        for (int x = -3; x <= -1; x++) for (int z = -2; z <= 2; z++) canvas.set(x, -1, z, step);
        for (int z = -1; z <= 1; z++)
            canvas.set(-1, 0, z, stair(variant == Variant.STONE ? Blocks.STONE_BRICK_STAIRS : Blocks.SPRUCE_STAIRS, Direction.EAST, StairShape.STRAIGHT));
        for (int z : new int[] { -2, 2 }) {
            canvas.set(-2, 0, z, variant == Variant.STONE ? Blocks.STONE_BRICK_WALL.getDefaultState() : Blocks.SPRUCE_FENCE.getDefaultState());
            canvas.set(-2, 1, z, Blocks.LANTERN.getDefaultState());
        }
        // The patron's icon over the door.
        canvas.set(0, 5, 0, icon(Blocks.YELLOW_GLAZED_TERRACOTTA, 0));
    }

    // ---- Shapes. ----

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

    /** The eight-pointed Orthodox cross: a short top bar, the long bar, the slanting foot; copper-gold rods. */
    private void cross(int x, int y0, int z, boolean big) {
        BlockState up = Blocks.LIGHTNING_ROD.getDefaultState().with(RodBlock.FACING, Direction.UP);
        BlockState bar = Blocks.LIGHTNING_ROD.getDefaultState().with(RodBlock.FACING, Direction.NORTH);
        int tall = big ? 6 : 4;
        for (int v = 0; v <= tall; v++) canvas.set(x, y0 + v, z, up);
        int main = big ? 4 : 3;
        canvas.set(x, y0 + main, z - 1, bar); canvas.set(x, y0 + main, z + 1, bar);
        if (big) {
            canvas.set(x, y0 + main, z - 2, bar); canvas.set(x, y0 + main, z + 2, bar);
            canvas.set(x, y0 + 6, z - 1, bar); canvas.set(x, y0 + 6, z + 1, bar);
            canvas.set(x, y0 + 1, z - 1, bar); canvas.set(x, y0 + 2, z + 1, bar);
        } else canvas.set(x, y0 + 1, z + 1, bar);
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
    private static boolean inOctagon(int dx, int dz, double r) {
        return Math.abs(dx) <= r && Math.abs(dz) <= r && Math.abs(dx) + Math.abs(dz) <= r * 1.42;
    }

    private static BlockState stair(Block block, Direction facing, StairShape shape) { return stair(block, facing, shape, BlockHalf.BOTTOM); }
    private static BlockState stair(Block block, Direction facing, StairShape shape, BlockHalf half) {
        return block.getDefaultState().with(StairsBlock.FACING, facing).with(StairsBlock.SHAPE, shape).with(StairsBlock.HALF, half);
    }
    private static BlockState pane(Direction.Axis along) {
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
