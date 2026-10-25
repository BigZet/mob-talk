package io.github.mobtalk.game;

import io.github.mobtalk.core.RoadPlan;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;
import it.unimi.dsi.fastutil.longs.LongSet;

/** Lays the part of a planned road that falls in one loaded chunk (docs/roads.md). Every column the road touches is worked out from
 * the nearest point of the centreline: the paved middle five blocks wide, a curb each side, and beyond them earthworks — the ground
 * above a 45° line from the curb is dug away, the ground below one is banked up with turf. On a bridge the deck stands on piers with a
 * wall for a railing; in a tunnel the middle is dug out under a stepped brick vault with brick walls. A column with anything not
 * natural in the way (a house, a chest, a player's build, a structure's piece such as the village itself) is left alone, so the road
 * ends where the village's streets begin. Lamps: a street lamp with an arm over the road, lanterns on the bridge railing and under the
 * tunnel vault; all lanterns, always lit. Server thread only; re-laying a chunk changes nothing. Lamp post after RoadWeaver's
 * LamppostDecoration (MIT, third_party/roadweaver/NOTICE.md). */
final class RoadBuilder {
    private RoadBuilder() { }

    /** Half the paved width and the curb's outer edge (blocks from the centreline), and how far the earthworks reach. */
    static final double PAVED = 2.25, CURB = 3.25, REACH = 9.0;
    /** Head room above the road and in a tunnel, the deepest fill under the road, the tallest earthwork, the deepest pier. */
    static final int CLEAR = 5, FILL = 10, BANK = 8, PIER = 64, PIER_SPACING = 14, TREE_CLEAR = 16;
    /** An open cut is dug to the sky this far above the head room: no shelf of rock is left hanging over the road. */
    static final int OPEN_CUT = 14;
    private static final int FLAGS = Block.NOTIFY_LISTENERS, FLAGS_SHAPED = Block.NOTIFY_ALL;

    /** The blocks of one road style. */
    record Palette(Block[] paving, Block[] pavingSlabs, Block curb, Block curbSlab, Block fill, Block deck, Block deckSlab, Block railing,
                   Block pier, Block lining, Block postBase, Block post) {
        static final Palette STONE = new Palette(
                new Block[] { Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.CRACKED_STONE_BRICKS },
                new Block[] { Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_SLAB, Blocks.COBBLESTONE_SLAB, Blocks.ANDESITE_SLAB, Blocks.STONE_BRICK_SLAB },
                Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE_SLAB, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB,
                Blocks.STONE_BRICK_WALL, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_WALL, Blocks.DARK_OAK_FENCE);
        static final Palette SANDSTONE = new Palette(
                new Block[] { Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE },
                new Block[] { Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SANDSTONE_SLAB, Blocks.CUT_SANDSTONE_SLAB },
                Blocks.CUT_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE_SLAB, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.CUT_SANDSTONE_SLAB,
                Blocks.SANDSTONE_WALL, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE_WALL, Blocks.JUNGLE_FENCE);
        boolean owns(Block block) {
            if (block == curb || block == curbSlab || block == fill || block == deck || block == deckSlab || block == railing || block == pier
                    || block == lining || block == postBase || block == post || block == Blocks.LANTERN) return true;
            for (Block b : paving) if (b == block) return true;
            for (Block b : pavingSlabs) if (b == block) return true;
            return false;
        }
    }

    /** The style of the whole road, chosen once so that it does not change colour halfway: sandstone when most of it runs through
     * deserts, badlands and beaches, stone otherwise. Biomes are read from the generator where the chunks are not loaded. */
    static int palette(ServerWorld world, List<RoadPlan.Point> points) {
        int sandy = 0, all = 0;
        for (int i = 0; i < points.size(); i += 24) {
            RoadPlan.Point p = points.get(i);
            var biome = world.getBiome(new BlockPos(p.blockX(), p.h2() >> 1, p.blockZ()));
            all++;
            if (biome.isIn(BiomeTags.IS_BADLANDS) || biome.isIn(BiomeTags.IS_BEACH) || biome.getKey().map(k -> k.getValue().getPath().contains("desert")).orElse(false)) sandy++;
        }
        return sandy * 2 > all ? 1 : 0;
    }

    static Palette palette(int id) { return id == 1 ? Palette.SANDSTONE : Palette.STONE; }

    /** The village a road end belongs to, from its key ("minecraft:village_plains@12,-5": the structure and the chunk it starts in):
     * its start with all its pieces, read from that chunk at the structure stage (made now if need be: cheap, and the pieces are
     * known from it before a block of the village stands). Null for an end that is no village (a test spot) or a village the world
     * did not make after all. */
    static StructureStart village(ServerWorld world, String key) {
        int at = key.indexOf('@'), comma = key.indexOf(',', at);
        if (at < 0 || comma < 0) return null;
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(key.substring(0, at));
        Structure structure = id == null ? null : world.getRegistryManager().get(RegistryKeys.STRUCTURE).get(id);
        if (structure == null) return null;
        try {
            int cx = Integer.parseInt(key.substring(at + 1, comma)), cz = Integer.parseInt(key.substring(comma + 1));
            Chunk holder = world.getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, true);
            StructureStart start = holder == null ? null : holder.getStructureStart(structure);
            return start == null || !start.hasChildren() ? null : start;
        } catch (NumberFormatException wrong) { return null; }
    }

    /** Where the road meets the village of {@code key} at its start ({@code atStart}) or its end: walking in from the open country,
     * the first point whose road would touch one of the village's pieces is where the village's own streets take over, and nothing
     * is laid past it. Known before any chunk of the road is laid (the road once ran on into a house when its end was learnt too
     * late, Dmitriy 2026-10-25). */
    static int end(ServerWorld world, String key, List<RoadPlan.Point> points, boolean atStart) {
        StructureStart start = village(world, key);
        return start == null ? (atStart ? 0 : points.size() - 1) : meet(start, points, atStart);
    }

    private static boolean near(BlockBox box, RoadPlan.Point p, double margin) {
        return p.x() >= box.getMinX() - margin && p.x() <= box.getMaxX() + 1 + margin && p.z() >= box.getMinZ() - margin && p.z() <= box.getMaxZ() + 1 + margin;
    }

    /** The first point to lay at the start ({@code atStart}) or the last one at the end. */
    private static int meet(StructureStart start, List<RoadPlan.Point> points, boolean atStart) {
        int n = points.size();
        for (int k = 0; k < n; k++) {
            int i = atStart ? n - 1 - k : k;
            RoadPlan.Point p = points.get(i);
            int y = p.h2() / 2;
            for (StructurePiece piece : start.getChildren()) {
                BlockBox box = piece.getBoundingBox();
                // A street follows the ground and keeps its template's height in its box: a wide window in height.
                if (box.getMaxY() < y - MEET_BELOW || box.getMinY() > y + MEET_ABOVE || !near(box, p, CURB + 1)) continue;
                // The road ends before the village, never in it: a bridge or tunnel that meets it there ends where it last stood on the
                // ground, if that is near (it once ran on over the village's houses until it landed, Dmitriy 2026-10-25).
                int outward = atStart ? 1 : -1, end = i + outward;
                end = Math.max(0, Math.min(n - 1, end));
                for (int back = 0, at = end; back <= LAND_BACK && at >= 0 && at < n; back++, at += outward)
                    if (points.get(at).kind() == RoadPlan.Kind.GROUND) { end = at; break; }
                return end;
            }
        }
        return atStart ? 0 : n - 1;
    }

    /** How far below and above the road a village's piece is looked for, and how far back from the village a bridge or tunnel that
     * meets it looks for ground to end on. */
    static final int MEET_BELOW = 16, MEET_ABOVE = 24, LAND_BACK = 24;

    /** One column of the road in the chunk: the nearest centreline point and the signed distance across from it. */
    private record Column(int x, int z, int index, double across, double along) { }

    /** Counts of what was done, for the log and the self-test. */
    static final class Report {
        int columns, skipped, lamps, dammed, drops, leaves;
        @Override public String toString() {
            return columns + " столбцов, пропущено " + skipped + ", фонарей " + lamps + ", запружено " + dammed + ", убрано выпавшего " + drops + ", висячей листвы " + leaves;
        }
    }

    /** Lays the road's part in this chunk. */
    static Report lay(ServerWorld world, List<RoadPlan.Point> points, ChunkPos chunk, Palette palette, int from, int to, int lampTo, boolean lamps, int lampSpacing,
                      List<List<RoadPlan.Point>> others) {
        Report report = new Report();
        Map<Long, Column> columns = columns(points, chunk);
        if (columns.isEmpty()) return report;
        // Where another road's paving and curb are: this road's curbs, railings, banks and lamps keep off them, so that where two
        // roads meet or run side by side one paved way is left, with no railing across it (carts drove through one, 2026-10-25).
        Map<Long, Integer> taken = new HashMap<>();
        for (List<RoadPlan.Point> other : others)
            for (Map.Entry<Long, Column> entry : columns(other, chunk).entrySet())
                if (Math.abs(entry.getValue().across()) <= CURB) taken.merge(entry.getKey(), other.get(entry.getValue().index()).h2() >> 1, Math::max);
        List<BlockBox> pieces = pieces(world, chunk);
        Set<Long> cleared = new HashSet<>();
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (Column column : columns.values()) {
            RoadPlan.Point point = points.get(column.index());
            if (column.index() < from || column.index() > to) { report.skipped++; continue; }
            // A bridge or tunnel passes through a piece's box where nothing of the piece stands (the check below still keeps off
            // every block that is not natural); the paved road keeps off the pieces altogether.
            if (point.kind() == RoadPlan.Kind.GROUND && blockedByStructure(pieces, column, point)) { report.skipped++; continue; }
            Integer other = taken.get(BlockPos.asLong(column.x(), 0, column.z()));
            if (other != null && Math.abs(column.across()) > PAVED && Math.abs(other - (point.h2() >> 1)) <= OVERLAP) { report.skipped++; continue; }
            if (column(world, palette, point, column, null)) { column(world, palette, point, column, cleared); report.columns++; }
            else report.skipped++;
            low = Math.min(low, (point.h2() >> 1) - FILL - BANK); high = Math.max(high, (point.h2() >> 1) + CLEAR + OPEN_CUT + TREE_CLEAR);
        }
        report.dammed = dam(world, palette, cleared);
        if (lamps) report.lamps = lamps(world, palette, points, chunk, from, lampTo, lampSpacing, taken);
        if (low <= high) report.leaves = leaves(world, chunk, low, high + TREE_CLEAR);
        if (low <= high) report.drops = sweepDrops(world, chunk, low, high);
        return report;
    }

    /** Water (or lava) left beside a block the road dug out would run onto the road and wash over it (Dmitriy 2026-10-25): every
     * such face is shut with the road's stone, a retaining wall against a pond or a river bank. */
    private static int dam(ServerWorld world, Palette palette, Set<Long> cleared) {
        int dammed = 0;
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (long packed : cleared) {
            for (net.minecraft.util.math.Direction direction : DAMMED) {
                at.set(packed).move(direction);
                if (cleared.contains(at.asLong())) continue;
                BlockState state = world.getBlockState(at);
                if (state.getFluidState().isEmpty() || !natural(state)) continue;
                world.setBlockState(at, palette.lining().getDefaultState(), FLAGS);
                dammed++;
            }
        }
        return dammed;
    }

    private static final net.minecraft.util.math.Direction[] DAMMED = { net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH,
            net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST, net.minecraft.util.math.Direction.UP };

    /** How far in height two roads may differ and still be one way where they overlap (more: one passes over the other). */
    static final int OVERLAP = 2;

    /** Leaves the road left hanging (their tree's trunk cut away) would rot and drop saplings onto the road (Dmitriy 2026-10-25):
     * every leaf in and just round the chunk that no log reaches within six leaves, as the game counts, is taken away at once. Logs
     * are looked for seven blocks beyond, so a tree standing next door keeps its crown. */
    private static int leaves(ServerWorld world, ChunkPos chunk, int low, int high) {
        // A crown reaches over the chunk's edge: leaves are taken up to eight blocks round it, logs looked for seven beyond that.
        int reach = 8, margin = reach + 7, x0 = chunk.getStartX() - margin, z0 = chunk.getStartZ() - margin, size = 16 + 2 * margin;
        low = Math.max(low, world.getBottomY()); high = Math.min(high, world.getTopY() - 1);
        if (low > high) return 0;
        int height = high - low + 1;
        byte[] dist = new byte[size * size * height]; // 0 not a leaf, 1..6 reached, 7 a leaf not reached
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        BlockPos.Mutable at = new BlockPos.Mutable();
        List<int[]> logs = new ArrayList<>();
        for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) for (int dy = 0; dy < height; dy++) {
            BlockState state = world.getBlockState(at.set(x0 + dx, low + dy, z0 + dz));
            if (state.isIn(BlockTags.LEAVES) && state.contains(LeavesBlock.PERSISTENT) && !state.get(LeavesBlock.PERSISTENT)) dist[(dx * size + dz) * height + dy] = 7;
            else if (state.isIn(BlockTags.LOGS)) logs.add(new int[] { dx, dz, dy, 0 });
        }
        int[][] steps = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        queue.addAll(logs);
        while (!queue.isEmpty()) {
            int[] here = queue.poll();
            if (here[3] >= 6) continue;
            for (int[] step : steps) {
                int nx = here[0] + step[0], nz = here[1] + step[1], ny = here[2] + step[2];
                if (nx < 0 || nz < 0 || ny < 0 || nx >= size || nz >= size || ny >= height) continue;
                int k = (nx * size + nz) * height + ny;
                if (dist[k] != 7) continue;
                dist[k] = (byte) (here[3] + 1);
                queue.add(new int[] { nx, nz, ny, here[3] + 1 });
            }
        }
        int removed = 0;
        for (int dx = margin - reach; dx < margin + 16 + reach; dx++) for (int dz = margin - reach; dz < margin + 16 + reach; dz++) for (int dy = 0; dy < height; dy++)
            if (dist[(dx * size + dz) * height + dy] == 7) { world.setBlockState(at.set(x0 + dx, low + dy, z0 + dz), Blocks.AIR.getDefaultState(), FLAGS); removed++; }
        return removed;
    }

    /** Grass, flowers and seeds that the earthworks knocked loose drop as items; the road's own work leaves none lying about. Only
     * items that came into being this very tick, in and just round this chunk, are taken. */
    private static int sweepDrops(ServerWorld world, ChunkPos chunk, int low, int high) {
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(chunk.getStartX() - 2, low, chunk.getStartZ() - 2, chunk.getEndX() + 3, high, chunk.getEndZ() + 3);
        List<net.minecraft.entity.ItemEntity> items = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, box, item -> item.getItemAge() <= 1);
        for (net.minecraft.entity.ItemEntity item : items) item.discard();
        return items.size();
    }

    /** The columns of the chunk the road covers, each with its nearest centreline point. */
    private static Map<Long, Column> columns(List<RoadPlan.Point> points, ChunkPos chunk) {
        Map<Long, Column> out = new HashMap<>();
        int minX = chunk.getStartX(), minZ = chunk.getStartZ(), maxX = chunk.getEndX(), maxZ = chunk.getEndZ();
        for (int i = 0; i < points.size(); i++) {
            RoadPlan.Point p = points.get(i);
            if (p.x() < minX - REACH - 1 || p.x() > maxX + REACH + 1 || p.z() < minZ - REACH - 1 || p.z() > maxZ + REACH + 1) continue;
            double nx = -p.dz(), nz = p.dx();
            for (double step = -REACH; step <= REACH; step += 0.5) {
                int x = MathHelper.floor(p.x() + nx * step), z = MathHelper.floor(p.z() + nz * step);
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                // The block's centre measured from this point: along the road and across it. The point nearest along wins, and the
                // true distance across decides paving, curb or bank (on a diagonal the sample step would put the curb on the paving).
                double cx = x + 0.5 - p.x(), cz = z + 0.5 - p.z(), along = Math.abs(cx * p.dx() + cz * p.dz()), across = cx * nx + cz * nz;
                long key = BlockPos.asLong(x, 0, z);
                Column old = out.get(key);
                if (old == null || along < old.along()) out.put(key, new Column(x, z, i, across, along));
            }
        }
        return out;
    }

    /** The bounding boxes of the structure pieces this chunk takes part in (villages, outposts, ruins…), read from the chunks holding
     * their starts when those are loaded; never loads one (that would stall the server thread). A structure whose start is not loaded
     * is left to the natural-blocks check, which keeps the road off its buildings anyway. */
    private static List<BlockBox> pieces(ServerWorld world, ChunkPos chunk) {
        List<BlockBox> out = new ArrayList<>();
        WorldChunk here = world.getChunkManager().getWorldChunk(chunk.x, chunk.z);
        if (here == null) return out;
        for (Map.Entry<Structure, LongSet> entry : here.getStructureReferences().entrySet()) {
            for (long reference : entry.getValue()) {
                WorldChunk holder = world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(reference), ChunkPos.getPackedZ(reference));
                StructureStart start = holder == null ? null : holder.getStructureStart(entry.getKey());
                if (start == null || !start.hasChildren()) continue;
                for (StructurePiece piece : start.getChildren()) out.add(piece.getBoundingBox());
            }
        }
        return out;
    }

    /** A column inside a piece near the road's height (one block of margin round it) is the structure's, not the road's. */
    private static boolean blockedByStructure(List<BlockBox> pieces, Column column, RoadPlan.Point point) {
        int y = point.h2() / 2;
        for (BlockBox box : pieces) {
            if (column.x() < box.getMinX() - 1 || column.x() > box.getMaxX() + 1 || column.z() < box.getMinZ() - 1 || column.z() > box.getMaxZ() + 1) continue;
            if (box.getMaxY() < y - 6 || box.getMinY() > y + 8) continue; // a mineshaft deep below, a sky platform far above
            return true;
        }
        return false;
    }

    // ---- One column ----

    /** Works one column, noting each block it digs out in {@code cleared}; with {@code cleared} null only checks that everything it
     * would change is natural (or the road's own). */
    private static boolean column(ServerWorld world, Palette palette, RoadPlan.Point point, Column column, Set<Long> cleared) {
        Ops ops = new Ops(world, palette, column.x(), column.z(), cleared);
        int h2 = point.h2(), y0 = h2 >> 1;
        boolean slab = (h2 & 1) == 1;
        double side = Math.abs(column.across());
        int variant = Math.floorMod(column.x() * 31 + column.z() * 17 + (column.x() ^ column.z()) * 7, palette.paving().length);
        switch (point.kind()) {
            case GROUND -> {
                if (side <= CURB) {
                    boolean paved = side <= PAVED;
                    Block full = paved ? palette.paving()[variant] : palette.curb(), half = paved ? palette.pavingSlabs()[variant] : palette.curbSlab();
                    ops.set(y0 - 1, full.getDefaultState());
                    if (slab) ops.set(y0, half.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM)); else ops.clear(y0);
                    ops.fillDown(y0 - 2, palette.fill().getDefaultState());
                    ops.clearUp(y0 + 1, y0 + CLEAR);
                    ops.digToSky(y0 + CLEAR + 1, y0 + CLEAR + OPEN_CUT);
                } else bank(ops, h2, side - CURB);
            }
            case BRIDGE -> {
                if (side > CURB) return true;
                ops.set(y0 - 1, palette.deck().getDefaultState());
                if (side <= PAVED) {
                    if (slab) ops.set(y0, palette.deckSlab().getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM)); else ops.clear(y0);
                    if (Math.floorMod(column.index(), PIER_SPACING) <= 1) ops.pierDown(y0 - 2, palette.pier().getDefaultState());
                } else ops.setShaped(y0, palette.railing().getDefaultState());
                ops.clearUp(y0 + 1, y0 + CLEAR);
            }
            case TUNNEL -> {
                if (side > CURB) return true;
                if (side <= PAVED) {
                    ops.set(y0 - 1, palette.paving()[variant].getDefaultState());
                    if (slab) ops.set(y0, palette.pavingSlabs()[variant].getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM)); else ops.clear(y0);
                    ops.fillDown(y0 - 2, palette.fill().getDefaultState());
                    int top = side <= 1.25 ? y0 + CLEAR : y0 + CLEAR - 1;
                    for (int y = y0 + 1; y <= top; y++) ops.dig(y);
                    ops.set(top + 1, palette.lining().getDefaultState());
                } else {
                    ops.set(y0 - 1, palette.curb().getDefaultState());
                    for (int y = y0; y <= y0 + CLEAR - 1; y++) ops.set(y, palette.lining().getDefaultState());
                    ops.set(y0 + CLEAR, palette.lining().getDefaultState());
                }
            }
        }
        return ops.ok;
    }

    /** Earthworks beside the curb, {@code out} blocks beyond it: the ground above the 45° line from the road is dug away, a hollow
     * below the line is banked up, both topped with the turf of the place (grass, or sand in a desert). */
    private static void bank(Ops ops, int h2, double out) {
        int feet = (h2 + 1) >> 1, reach = (int) Math.floor(out);
        int ground = ops.groundAt(feet + BANK + 2, feet - BANK - 2);
        if (ground == Integer.MIN_VALUE) return;
        int over = feet + 1 + reach, under = feet - 1 - reach;
        if (ground > over && ground - feet <= BANK + reach) {
            BlockState top = ops.top(ground - 1);
            for (int y = over; y < ground; y++) ops.dig(y);
            if (top != null) ops.set(over - 1, top);
        } else if (ground < under && feet - ground <= BANK + reach) {
            BlockState top = ops.top(ground - 1);
            for (int y = ground; y < under; y++) ops.set(y, Blocks.DIRT.getDefaultState());
            ops.set(under, top != null ? top : Blocks.GRASS_BLOCK.getDefaultState());
            if (top != null && top.isOf(Blocks.GRASS_BLOCK) && ground - 1 >= ops.world.getBottomY()) ops.set(ground - 1, Blocks.DIRT.getDefaultState());
        }
    }

    // ---- Lamps ----

    private static int lamps(ServerWorld world, Palette palette, List<RoadPlan.Point> points, ChunkPos chunk, int from, int to, int spacing, Map<Long, Integer> taken) {
        int placed = 0;
        for (int i = Math.max(0, from); i <= Math.min(points.size() - 1, to); i++) {
            RoadPlan.Point p = points.get(i);
            int side = RoadPlan.lampSide(p, i, spacing);
            if (side == 0) continue;
            double nx = -p.dz(), nz = p.dx();
            int y0 = p.h2() >> 1, feet = (p.h2() + 1) >> 1;
            if (side == 1 || side == -1) {
                BlockPos post = new BlockPos(MathHelper.floor(p.x() + nx * side * 2.8), 0, MathHelper.floor(p.z() + nz * side * 2.8));
                BlockPos arm = new BlockPos(MathHelper.floor(p.x() + nx * side * 1.8), 0, MathHelper.floor(p.z() + nz * side * 1.8));
                if (post.equals(arm) || taken.containsKey(BlockPos.asLong(post.getX(), 0, post.getZ())) || taken.containsKey(BlockPos.asLong(arm.getX(), 0, arm.getZ()))) continue;
                boolean any = false;
                if (inside(chunk, post) && naturalOrOwn(world, palette, post.withY(feet), CLEAR)) {
                    any = true;
                    set(world, post.withY(feet), palette.postBase().getDefaultState(), true);
                    for (int k = 1; k <= 4; k++) set(world, post.withY(feet + k), palette.post().getDefaultState(), true);
                }
                // The arm goes with its post, also over the edge into the next chunk (all eight round a chunk being laid are loaded).
                if (any && naturalOrOwn(world, palette, arm.withY(feet + 3), 2)) {
                    any = true;
                    set(world, arm.withY(feet + 4), palette.post().getDefaultState(), true);
                    set(world, arm.withY(feet + 3), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true), true);
                }
                if (any) placed++;
            } else if (side == 2) {
                for (int s = -1; s <= 1; s += 2) {
                    BlockPos rail = new BlockPos(MathHelper.floor(p.x() + nx * s * 2.8), y0, MathHelper.floor(p.z() + nz * s * 2.8));
                    if (!inside(chunk, rail) || !world.getBlockState(rail).isOf(palette.railing())) continue;
                    set(world, rail.up(), palette.railing().getDefaultState(), true);
                    set(world, rail.up(2), Blocks.LANTERN.getDefaultState(), true);
                    placed++;
                }
            } else {
                BlockPos crown = new BlockPos(p.blockX(), y0 + CLEAR, p.blockZ());
                if (inside(chunk, crown) && world.getBlockState(crown.up()).isOf(palette.lining()) && world.getBlockState(crown).isAir()) {
                    set(world, crown, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true), true);
                    placed++;
                }
            }
        }
        return placed;
    }

    private static boolean inside(ChunkPos chunk, BlockPos pos) { return (pos.getX() >> 4) == chunk.x && (pos.getZ() >> 4) == chunk.z; }

    private static boolean naturalOrOwn(ServerWorld world, Palette palette, BlockPos from, int height) {
        for (int k = 0; k <= height; k++) {
            BlockState state = world.getBlockState(from.up(k));
            if (!natural(state) && !palette.owns(state.getBlock())) return false;
        }
        return true;
    }

    private static void set(ServerWorld world, BlockPos pos, BlockState state, boolean shaped) {
        if (world.getBlockState(pos) != state) world.setBlockState(pos, state, shaped ? FLAGS_SHAPED : FLAGS);
    }

    // ---- What may be changed ----

    /** Ground, rock, water, plants and trees: what worldgen made and the road may cut, fill or clear. */
    static boolean natural(BlockState state) {
        if (state.isAir()) return true;
        Block block = state.getBlock();
        if (block instanceof FluidBlock) return true;
        if (state.hasBlockEntity()) return false;
        if (state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.LOGS)
                || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.SAPLINGS)
                || state.isIn(BlockTags.ICE) || state.isIn(BlockTags.SNOW) || state.isIn(BlockTags.TERRACOTTA) || state.isIn(BlockTags.CAVE_VINES)
                || state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.COPPER_ORES) || state.isIn(BlockTags.GOLD_ORES)
                || state.isIn(BlockTags.REDSTONE_ORES) || state.isIn(BlockTags.LAPIS_ORES) || state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES)
                || state.isIn(BlockTags.CORALS) || state.isIn(BlockTags.CORAL_BLOCKS) || state.isIn(BlockTags.WALL_CORALS))
            return true;
        return NATURAL.contains(block);
    }

    private static final Set<Block> NATURAL = Set.of(Blocks.GRAVEL, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW,
            Blocks.CACTUS, Blocks.SUGAR_CANE, Blocks.PUMPKIN, Blocks.MELON, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.SWEET_BERRY_BUSH, Blocks.VINE,
            Blocks.MOSS_CARPET, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE, Blocks.CALCITE, Blocks.MAGMA_BLOCK, Blocks.OBSIDIAN,
            Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.LILY_PAD,
            Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.SEA_PICKLE, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SMALL_DRIPLEAF, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.GLOW_LICHEN, Blocks.SPORE_BLOSSOM, Blocks.HANGING_ROOTS,
            Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.COCOA, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.SMOOTH_BASALT,
            Blocks.COBWEB, Blocks.BEE_NEST, Blocks.SNIFFER_EGG, Blocks.PINK_PETALS, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL);

    /** A block the road may leave hanging over its cut: tree parts and loose stuff above are cleared further up. */
    private static boolean loose(BlockState state) {
        return state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES) || state.getBlock() instanceof FallingBlock || state.isOf(Blocks.VINE)
                || state.isIn(BlockTags.REPLACEABLE) || state.isOf(Blocks.SNOW) || state.getBlock() instanceof FluidBlock || state.isOf(Blocks.COCOA);
    }

    /** Open for fill under the road: air, water, plants, snow. */
    private static boolean open(BlockState state) {
        return state.isAir() || state.getBlock() instanceof FluidBlock || state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.SAPLINGS) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.SEAGRASS)
                || state.isOf(Blocks.TALL_SEAGRASS) || state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT) || state.isOf(Blocks.LILY_PAD);
    }

    /** The changes of one column; in the check pass {@link #ok} turns false at the first block that is not ours to touch. */
    private static final class Ops {
        final ServerWorld world; final Palette palette; final int x, z; final boolean write; final Set<Long> cleared;
        final BlockPos.Mutable at = new BlockPos.Mutable();
        boolean ok = true;
        Ops(ServerWorld world, Palette palette, int x, int z, Set<Long> cleared) {
            this.world = world; this.palette = palette; this.x = x; this.z = z; this.cleared = cleared; this.write = cleared != null;
        }

        BlockState get(int y) { return world.getBlockState(at.set(x, y, z)); }
        private boolean mine(BlockState state) { return natural(state) || palette.owns(state.getBlock()); }

        void set(int y, BlockState state) { place(y, state, FLAGS); }
        void setShaped(int y, BlockState state) { place(y, state, FLAGS_SHAPED); }
        private void place(int y, BlockState state, int flags) {
            if (y < world.getBottomY() || y >= world.getTopY()) return;
            BlockState old = get(y);
            if (!write) { if (!mine(old)) ok = false; return; }
            if (old != state) world.setBlockState(at.set(x, y, z), state, flags);
            if (state.isAir()) cleared.add(BlockPos.asLong(x, y, z));
        }
        /** Clears a block, but never a street lamp's post or lantern: a lamp's arm reaches over the road, and the chunk laid next
         * must not cut it off (the lamps came out broken, Dmitriy 2026-10-25). */
        void clear(int y) {
            BlockState old = get(y);
            if (old.isOf(palette.post()) || old.isOf(Blocks.LANTERN)) return;
            if (!old.isAir() || write) set(y, Blocks.AIR.getDefaultState());
        }
        /** Digs a block out (ground, rock, a tree, water): the same as clearing. */
        void dig(int y) { clear(y); }

        /** Clears the head room and then whatever would hang over it: tree parts and sand up to {@link #TREE_CLEAR} above. */
        void clearUp(int from, int to) {
            for (int y = from; y <= to; y++) clear(y);
            for (int y = to + 1; y <= to + TREE_CLEAR; y++) {
                BlockState state = get(y);
                if (state.isAir()) break;
                if (!loose(state)) break;
                if (state.getBlock() instanceof FluidBlock) break;
                set(y, Blocks.AIR.getDefaultState());
            }
        }

        /** Digs the column open from {@code from} up to its top when the top is no higher than {@code limit}; a column whose ground
         * goes higher (a tunnel's mouth, a cliff) is left as it is. */
        void digToSky(int from, int limit) {
            int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (top <= from || top > limit + 1) return;
            for (int y = from; y < top; y++) clear(y);
            for (int y = top; y <= top + TREE_CLEAR; y++) {
                BlockState state = get(y);
                if (state.isAir() || !loose(state) || state.getBlock() instanceof FluidBlock) break;
                set(y, Blocks.AIR.getDefaultState());
            }
        }

        /** Fills the hollow under the road down to the ground, at most {@link #FILL} deep. */
        void fillDown(int from, BlockState fill) {
            for (int y = from; y > from - FILL; y--) {
                BlockState state = get(y);
                if (!open(state)) break;
                set(y, fill);
            }
        }

        /** A pier under a bridge down to the ground or the bed of the water. */
        void pierDown(int from, BlockState pier) {
            for (int y = from; y > from - PIER && y >= world.getBottomY(); y--) {
                BlockState state = get(y);
                if (!open(state) && !state.isOf(pier.getBlock())) break;
                set(y, pier);
            }
        }

        /** The height a walker stands at on this column's ground, looking down from {@code high} to {@code low}; tree parts and plants
         * do not count. MIN_VALUE when there is none in the range. */
        int groundAt(int high, int low) {
            for (int y = high; y >= low; y--) {
                BlockState state = get(y);
                if (state.isAir() || open(state) || state.isIn(BlockTags.LOGS)) continue;
                return y + 1;
            }
            return Integer.MIN_VALUE;
        }

        /** The turf to put back on top: the block there when it is grass, sand, snow or the like; null to leave the dug face bare. */
        BlockState top(int y) {
            BlockState state = get(y);
            if (state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM) || state.isIn(BlockTags.SAND)
                    || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.MUD)) return state;
            if (state.isIn(BlockTags.DIRT)) return Blocks.GRASS_BLOCK.getDefaultState();
            return null;
        }
    }
}
