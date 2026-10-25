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
        if (solids == null) solids = solids(template.get());
        template.get().place(world, at, at, data, random, Block.NOTIFY_LISTENERS);
        restore(world, chunkBox, template.get(), data, at);
        underfill(world, chunkBox, frame);
        unrail(world, chunkBox, frame);
    }

    /** The template's solid blocks in the church's coordinates (x, z, then the heights), read once. */
    private static volatile java.util.Map<Long, java.util.BitSet> solids;
    private static java.util.Map<Long, java.util.BitSet> solids(StructureTemplate template) {
        net.minecraft.nbt.NbtCompound nbt = template.writeNbt(new net.minecraft.nbt.NbtCompound());
        net.minecraft.nbt.NbtList palette = nbt.getList("palette", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        java.util.Map<Long, java.util.BitSet> map = new java.util.HashMap<>();
        for (net.minecraft.nbt.NbtElement element : nbt.getList("blocks", net.minecraft.nbt.NbtElement.COMPOUND_TYPE)) {
            net.minecraft.nbt.NbtCompound block = (net.minecraft.nbt.NbtCompound) element;
            String name = palette.getCompound(block.getInt("state")).getString("Name");
            if (name.equals("minecraft:air") || name.equals("minecraft:light") || name.equals("minecraft:structure_void")) continue;
            net.minecraft.nbt.NbtList pos = block.getList("pos", net.minecraft.nbt.NbtElement.INT_TYPE);
            map.computeIfAbsent(key(ANCHOR_X - pos.getInt(0), ANCHOR_Z - pos.getInt(2)), k -> new java.util.BitSet()).set(pos.getInt(1));
        }
        return map;
    }
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    /** The canvas writing only inside the author's church: under its roof (some block of the template over the spot), and
     * over the author's own masonry only below the arches' springing (the piers the deacon door passes through). */
    private static ChurchBuilder.Canvas indoors(ChurchBuilder.Canvas canvas) {
        java.util.Map<Long, java.util.BitSet> map = solids;
        if (map == null) return canvas;
        return new ChurchBuilder.Canvas() {
            boolean inside(int x, int y, int z) {
                java.util.BitSet heights = map.get(key(x, z));
                if (heights == null || heights.nextSetBit(y + 1) < 0) return false;
                return !heights.get(y) || y < STAND + 9;
            }
            @Override public void set(int x, int y, int z, BlockState state) { if (inside(x, y, z)) canvas.set(x, y, z, state); }
            @Override public void foundation(int x, int y, int z, BlockState state) { canvas.foundation(x, y, z, state); }
            @Override public void chest(int x, int y, int z, BlockState state) { canvas.chest(x, y, z, state); }
            @Override public boolean column(int x, int z) { return canvas.column(x, z); }
        };
    }

    /** The author's fine detail hangs on blocks a survival player could not hang it on: buttons and signs on walls and fences,
     * levers, tripwire hooks, doors on slabs. Placing the template checks every block against its neighbours and drops
     * those (about nine hundred, the facade's pattern of buttons among them), so they are set back as the template has them. */
    private static void restore(StructureWorldAccess world, BlockBox chunkBox, StructureTemplate template, StructurePlacementData data, BlockPos at) {
        for (StructureTemplate.StructureBlockInfo info : fragile(template)) {
            BlockPos pos = StructureTemplate.transform(data, info.pos()).add(at);
            if (!chunkBox.contains(pos) || !world.getBlockState(pos).isAir()) continue;
            world.setBlockState(pos, info.state().mirror(data.getMirror()).rotate(data.getRotation()), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            if (info.nbt() == null) continue;
            net.minecraft.block.entity.BlockEntity entity = world.getBlockEntity(pos);
            if (entity != null) entity.read(info.nbt(), world.getRegistryManager());
        }
    }
    private static java.util.List<StructureTemplate.StructureBlockInfo> fragile;
    private static synchronized java.util.List<StructureTemplate.StructureBlockInfo> fragile(StructureTemplate template) {
        if (fragile != null) return fragile;
        java.util.List<StructureTemplate.StructureBlockInfo> found = new java.util.ArrayList<>();
        StructurePlacementData plain = new StructurePlacementData();
        for (Block block : net.minecraft.registry.Registries.BLOCK) {
            BlockState state = block.getDefaultState();
            if (state.isIn(net.minecraft.registry.tag.BlockTags.BUTTONS) || state.isIn(net.minecraft.registry.tag.BlockTags.ALL_SIGNS)
                    || state.isIn(net.minecraft.registry.tag.BlockTags.DOORS) || block == Blocks.LEVER || block == Blocks.TRIPWIRE_HOOK)
                found.addAll(template.getInfosForBlock(BlockPos.ORIGIN, plain, block));
        }
        return fragile = java.util.List.copyOf(found);
    }

    /** For the self-test: the template's blocks that are not standing in the world where it put them, counted by
     * "expected -> found" (only the block's kind is compared: stairs and fences take their shapes from their neighbours),
     * with up to {@code samples} positions noted. This mod's fittings and the cut-away railing differ by design. */
    public static java.util.Map<String, Integer> templateDiff(ServerWorld world, ChurchPiece.Frame frame, java.util.List<String> samples, int max) {
        java.util.Map<String, Integer> diff = new java.util.TreeMap<>();
        Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(TEMPLATE);
        if (template.isEmpty()) return diff;
        BlockPos at = frame.world(ANCHOR_X, 0, ANCHOR_Z);
        StructurePlacementData data = new StructurePlacementData().setRotation(frame.rotation().rotate(BlockRotation.CLOCKWISE_180)).setIgnoreEntities(true);
        for (Block block : net.minecraft.registry.Registries.BLOCK) {
            if (block == Blocks.AIR || block == Blocks.STRUCTURE_VOID) continue;
            for (StructureTemplate.StructureBlockInfo info : template.get().getInfosForBlock(at, data, block)) {
                Block found = world.getBlockState(info.pos()).getBlock();
                if (found == block) continue;
                String key = net.minecraft.registry.Registries.BLOCK.getId(block).getPath() + " -> " + net.minecraft.registry.Registries.BLOCK.getId(found).getPath();
                diff.merge(key, 1, Integer::sum);
                if (samples.size() < max) samples.add(key + " @ " + ChurchYard.local(frame, info.pos()).toShortString());
            }
        }
        return diff;
    }

    /** The author's island had a glass railing round the church's plinth (light grey panes on the floor's level, y = 2): in a
     * churchyard it stands as a glass fence round the walls, so it goes. */
    private static void unrail(StructureWorldAccess world, BlockBox chunkBox, ChurchPiece.Frame frame) {
        BlockBox box = ChurchPiece.box(frame, ChurchBuilder.Variant.SLAVIC);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int y = frame.origin().getY() + 2;
        for (int x = Math.max(box.getMinX(), chunkBox.getMinX()); x <= Math.min(box.getMaxX(), chunkBox.getMaxX()); x++)
            for (int z = Math.max(box.getMinZ(), chunkBox.getMinZ()); z <= Math.min(box.getMaxZ(), chunkBox.getMaxZ()); z++)
                if (world.getBlockState(pos.set(x, y, z)).isOf(Blocks.LIGHT_GRAY_STAINED_GLASS_PANE)) world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    /** The template's floor stands at y = 2 over the ground at y = -1 (its island was cut away): the gap under every floor block
     * is filled with earth down to the ground, so no hollow shows under the church. */
    private static void underfill(StructureWorldAccess world, BlockBox chunkBox, ChurchPiece.Frame frame) {
        BlockBox box = ChurchPiece.box(frame, ChurchBuilder.Variant.SLAVIC);
        int y0 = frame.origin().getY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = Math.max(box.getMinX(), chunkBox.getMinX()); x <= Math.min(box.getMaxX(), chunkBox.getMaxX()); x++)
            for (int z = Math.max(box.getMinZ(), chunkBox.getMinZ()); z <= Math.min(box.getMaxZ(), chunkBox.getMaxZ()); z++)
                for (int y = y0 + 1; y >= y0 - 3; y--) {
                    pos.set(x, y, z);
                    if (!world.getBlockState(pos).isAir() || world.getBlockState(pos.up()).isAir()) continue; // only under something
                    world.setBlockState(pos, Blocks.DIRT.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
    }

    /** This mod's fittings inside the author's church. */
    static void fit(ChurchBuilder.Canvas canvas) {
        BlockState air = Blocks.AIR.getDefaultState();
        // The author's benches across the nave and the candles on them: an Orthodox nave stands free.
        for (int x = 35; x <= 49; x++) for (int z = -8; z <= 8; z++) for (int y = STAND; y <= STAND + 1; y++) canvas.set(x, y, z, air);
        // The second lectern with its carpet before the sanctuary, the levers by the holy table.
        for (int x = 53; x <= 55; x++) canvas.set(x, ALTAR_Y, 0, air);
        canvas.set(ALTAR_X, ALTAR_Y, -1, air); canvas.set(ALTAR_X, ALTAR_Y, 1, air);
        // The iconostasis across the nave's east end (the stone church's, moved here) and a step up behind the royal doors. It
        // is wider and taller than the author's sanctuary arch: where it would rise over the side aisles' roofs, or into the
        // arch above, the author's masonry stays.
        ChurchBuilder.iconostasis(ChurchBuilder.shifted(indoors(canvas), ICONOSTASIS_X - ChurchBuilder.ICONOSTASIS_X, STAND - 1, 0), true);
        for (int z = -1; z <= 0; z++) {
            canvas.set(ICONOSTASIS_X + 1, STAND, z, ChurchBuilder.stair(Blocks.SMOOTH_SANDSTONE_STAIRS, Direction.EAST, StairShape.STRAIGHT));
            canvas.set(ICONOSTASIS_X + 1, STAND + 1, z, air);
            canvas.set(ICONOSTASIS_X + 1, STAND + 2, z, air);
        }
        // The south deacon door opens on the author's sanctuary wall (a pier, a candle and two lightning rods behind it): a way
        // is cut through to the side room beyond, as the north door has one.
        for (int x = ICONOSTASIS_X + 1; x <= ICONOSTASIS_X + 2; x++) for (int y = STAND; y <= STAND + 2; y++) canvas.set(x, y, 5, air);
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
