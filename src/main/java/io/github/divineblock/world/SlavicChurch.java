package io.github.divineblock.world;

import io.github.divineblock.DivineBlockMod;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.enums.StairShape;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;

/** The third kind of church: "Slavic Church", built by KAIZEN87 and used in this mod with the author's permission (see
 * CREDITS.md). A centric Russian Revival church with a tall tented bell tower and a great gilded dome, kept as a structure
 * template (data/divineblock/structure/church/slavic_church.nbt, exported from the author's world without its island) and
 * placed in the church's own coordinates like the others: +x towards the altar (the template's apse), the nave's middle at
 * (42, 0); the floor is the template's stone floor at y = 2, the ground at y = -1. Inside it gets this mod's fittings: the
 * iconostasis with the royal doors, the god's altar on the holy table, the chest, the boss seal, the panikadilo, the analoy
 * and candle stands; the author's benches and second lectern give way to them. */
public final class SlavicChurch {
    private SlavicChurch() { }

    static final Identifier TEMPLATE = Identifier.of(DivineBlockMod.ID, "church/slavic_church");
    /** The church's box in its own coordinates (the template turned half round: x = 35 - its x, z = 29 - its z, y = its y + 56). */
    public static final int MIN_X = -6, MAX_X = 72, MIN_Z = -30, MAX_Z = 27, MAX_Y = 98;
    /** Feet on the floor; the iconostasis line; the holy table, the chest and the seal on the raised sanctuary. */
    public static final int STAND = 3, ICONOSTASIS_X = 52, ALTAR_X = 58, ALTAR_Y = 4, CHEST_X = 61, CHEST_Y = 4, CHEST_Z = -1,
            SEAL_X = 61, SEAL_Y = 3, SEAL_Z = 1;
    /** The tower's open belfry tier (y 39–45 around the tower's axis at x 23, z -19): a beam at its top and the bells under it. */
    public static final int BELL_X = 23, BELL_Z = -19, BEAM_Y = 45, BELLS = 5;
    /** Where the template's corner (0, 0, 0) lands in the church's coordinates before the half turn. */
    private static final int ANCHOR_X = 72, ANCHOR_Z = 27;

    /** Places the author's church, the part of it inside the chunk being generated. */
    static void place(StructureWorldAccess world, BlockBox chunkBox, ChurchPiece.Frame frame, Random random) {
        ServerWorld server = world.toServerWorld();
        Optional<StructureTemplate> template = server.getStructureTemplateManager().getTemplate(TEMPLATE);
        if (template.isEmpty()) { DivineBlockMod.LOG.warn("church template {} is missing", TEMPLATE); return; }
        BlockPos at = frame.world(ANCHOR_X, 0, ANCHOR_Z);
        StructurePlacementData data = new StructurePlacementData().setRotation(frame.rotation().rotate(BlockRotation.CLOCKWISE_180))
                .setBoundingBox(chunkBox).setIgnoreEntities(true).setUpdateNeighbors(false);
        template.get().place(world, at, at, data, random, Block.NOTIFY_LISTENERS);
    }

    /** This mod's fittings inside the author's church. */
    static void fit(ChurchBuilder.Canvas canvas) {
        BlockState air = Blocks.AIR.getDefaultState();
        // The author's benches across the nave and the candles on them: an Orthodox nave stands free.
        for (int x = 35; x <= 49; x++) for (int z = -8; z <= 8; z++) for (int y = STAND; y <= STAND + 1; y++) canvas.set(x, y, z, air);
        // The second lectern with its carpet before the sanctuary, the levers by the holy table.
        for (int x = 53; x <= 55; x++) canvas.set(x, ALTAR_Y, 0, air);
        canvas.set(ALTAR_X, ALTAR_Y, -1, air); canvas.set(ALTAR_X, ALTAR_Y, 1, air);
        // The iconostasis across the nave's east end (the stone church's, moved here) and a step up behind the royal doors.
        ChurchBuilder.iconostasis(ChurchBuilder.shifted(canvas, ICONOSTASIS_X - ChurchBuilder.ICONOSTASIS_X, STAND - 1, 0), true);
        for (int z = -1; z <= 0; z++) {
            canvas.set(ICONOSTASIS_X + 1, STAND, z, ChurchBuilder.stair(Blocks.SMOOTH_SANDSTONE_STAIRS, Direction.EAST, StairShape.STRAIGHT));
            canvas.set(ICONOSTASIS_X + 1, STAND + 1, z, air);
            canvas.set(ICONOSTASIS_X + 1, STAND + 2, z, air);
        }
        // The sanctuary: the god's altar as the holy table, the seven-branched stand behind it, the vestry chest.
        canvas.set(ALTAR_X, ALTAR_Y, 0, DivineBlockMod.ALTAR.getDefaultState());
        canvas.set(ALTAR_X + 2, ALTAR_Y, 0, ChurchBlocks.CANDLE_STAND.getDefaultState());
        canvas.chest(CHEST_X, CHEST_Y, CHEST_Z, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));
        // The nave: the panikadilo under the dome in place of the author's lamp, the analoy and its candle stand, candle stands
        // before the great icons, the choirs' lecterns, a carpet runner from the door.
        for (int y = 14; y <= 16; y++) canvas.set(ChurchBuilder.NAVE_X, y, 0, air);
        canvas.set(ChurchBuilder.NAVE_X, 15, 0, ChurchBlocks.CHANDELIER.getDefaultState());
        canvas.set(ChurchBuilder.NAVE_X, 17, 0, Blocks.CHAIN.getDefaultState());
        for (int x = 8; x <= 39; x++) for (int z = -1; z <= 1; z++) canvas.set(x, STAND, z, (z == 0 ? Blocks.RED_CARPET : Blocks.YELLOW_CARPET).getDefaultState());
        canvas.set(40, STAND, 0, ChurchBlocks.ANALOY.getDefaultState().with(ChurchBlocks.Facing.FACING, Direction.WEST));
        canvas.set(41, STAND, 0, ChurchBlocks.CANDLE_STAND.getDefaultState());
        for (int z : new int[] { -3, 3 }) canvas.set(ICONOSTASIS_X - 4, STAND, z, ChurchBlocks.CANDLE_STAND.getDefaultState());
        for (int z : new int[] { -8, 8 }) canvas.set(ICONOSTASIS_X - 3, STAND, z, Blocks.LECTERN.getDefaultState().with(LecternBlock.FACING, Direction.WEST));
        // The author's belfry has no floor and no bells: a cross of beams at the top of the open tier, the great bell in the
        // middle (its block two below the beam), two medium and two small ones along the beams.
        BlockState beamZ = Blocks.DARK_OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Z);
        BlockState beamX = Blocks.DARK_OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X);
        for (int d = -5; d <= 5; d++) {
            canvas.set(BELL_X, BEAM_Y, BELL_Z + d, beamZ);
            if (d != 0) canvas.set(BELL_X + d, BEAM_Y, BELL_Z, beamX);
        }
        canvas.set(BELL_X, BEAM_Y - 2, BELL_Z, bell(2));
        for (int d : new int[] { -3, 3 }) canvas.set(BELL_X, BEAM_Y - 1, BELL_Z + d, bell(1));
        for (int d : new int[] { -3, 3 }) canvas.set(BELL_X + d, BEAM_Y - 1, BELL_Z, bell(0));
    }

    private static BlockState bell(int size) { return ChurchBlocks.CHURCH_BELL.getDefaultState().with(ChurchBlocks.ChurchBellBlock.SIZE, size); }
}
