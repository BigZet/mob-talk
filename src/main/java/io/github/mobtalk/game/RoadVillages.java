package io.github.mobtalk.game;

import io.github.mobtalk.core.RoadPlan;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

/** Where the roads meet a village (docs/roads.md; Dmitriy chose on 2026-10-25 to leave the village's houses be and pave its own
 * streets). Each road stops where the village's pieces begin; from there a short paved way leads to the nearest of the village's
 * streets. A village two or more roads come to has its streets between their entries paved with the road's stone, lamps along
 * them: the highway runs through the village along its main street, and every lane of the village turns off it. A village only
 * one road comes to gets a station where the road ends: a paved square with a shelter, a bench and a hitching post, where the
 * carts and wagons stop. Only blocks worldgen made (and the road's own) are touched: never a house, a field or a fence of the
 * village. Server thread only, once the village's chunks are all loaded. */
final class RoadVillages {
    private RoadVillages() { }

    /** How far the way from a road's end to the streets may run, the step between lamps along a paved street, the station's length
     * along the road and half its width. */
    static final int CONNECT = 40, LAMP_STEP = 20, STATION = 9;
    static final double STATION_HALF = 4.5;

    /** A road coming in: its last point before the village and the way into the village from there (a unit vector). */
    record Entry(RoadPlan.Point at, double ix, double iz) {
        static Entry of(List<RoadPlan.Point> points, int end, boolean atStart) {
            RoadPlan.Point at = points.get(end), in = points.get(MathHelper.clamp(end + (atStart ? -3 : 3), 0, points.size() - 1));
            double ix = in.x() - at.x(), iz = in.z() - at.z(), d = Math.hypot(ix, iz);
            if (d < 1e-6) { ix = atStart ? -at.dx() : at.dx(); iz = atStart ? -at.dz() : at.dz(); d = Math.hypot(ix, iz); }
            return new Entry(at, ix / d, iz / d);
        }
    }

    /** What was done, for the log. */
    static final class Report {
        int connected, paved, lamps; boolean station;
        @Override public String toString() {
            return "подъездов " + connected + ", вымощено улиц " + paved + ", фонарей " + lamps + (station ? ", станция" : "");
        }
    }

    static Report work(ServerWorld world, StructureStart start, List<Entry> entries, RoadBuilder.Palette palette, boolean lamps) {
        Report report = new Report();
        Map<Long, Integer> streets = streets(world, start);
        List<BlockBox> others = new ArrayList<>();
        for (StructurePiece piece : start.getChildren()) if (!street(piece)) others.add(piece.getBoundingBox());
        List<Long> reached = new ArrayList<>();
        for (Entry entry : entries) {
            long at = connect(world, palette, entry, streets, others, report);
            if (at != Long.MIN_VALUE) reached.add(at);
        }
        if (entries.size() >= 2 && reached.size() >= 2)
            for (int i = 1; i < reached.size(); i++) through(world, palette, streets, others, start.getBoundingBox(), reached.get(0), reached.get(i), lamps, report);
        if (entries.size() == 1) { station(world, palette, entries.get(0), lamps); report.station = true; }
        return report;
    }

    // ---- The village's streets ----

    private static boolean street(StructurePiece piece) {
        return piece instanceof PoolStructurePiece pool && pool.getPoolElement().toString().contains("/streets/");
    }

    /** The top block (by the heightmap) of every column of the village's street pieces that is street: the path (dirt path, the
     * desert's sandstone), the planks of a street over water. The grass worldgen leaves in a path here and there is crossed as open
     * ground, and a piece's box is wider than its path. Keyed by the column, the value its height. */
    private static Map<Long, Integer> streets(ServerWorld world, StructureStart start) {
        Map<Long, Integer> out = new HashMap<>();
        for (StructurePiece piece : start.getChildren()) {
            if (!street(piece)) continue;
            BlockBox box = piece.getBoundingBox();
            for (int x = box.getMinX(); x <= box.getMaxX(); x++)
                for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                    // A street follows the ground: its box keeps the template's height, not the path's.
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    if (y == Integer.MIN_VALUE) continue;
                    BlockState state = world.getBlockState(new BlockPos(x, y, z));
                    if (state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.SMOOTH_SANDSTONE) || state.isIn(BlockTags.PLANKS)) out.put(key(x, z), y);
                }
        }
        return out;
    }

    /** The highest block of the column between {@code high} and {@code low} that one walks on (plants, snow and air are skipped). */
    private static int top(ServerWorld world, int x, int z, int high, int low) {
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int y = high; y >= low; y--) {
            BlockState state = world.getBlockState(at.set(x, y, z));
            if (state.isAir() || state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.FLOWERS) || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.SAPLINGS)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static long key(int x, int z) { return BlockPos.asLong(x, 0, z); }
    private static int kx(long key) { return BlockPos.unpackLongX(key); }
    private static int kz(long key) { return BlockPos.unpackLongZ(key); }

    // ---- From the road's end to the streets ----

    /** Finds the shortest walk over open ground from the road's end to a street of the village and paves it three wide; the street
     * reached, or MIN_VALUE when none is near. */
    private static long connect(ServerWorld world, RoadBuilder.Palette palette, Entry entry, Map<Long, Integer> streets, List<BlockBox> others, Report report) {
        RoadPlan.Point at = entry.at();
        int sx = at.blockX(), sz = at.blockZ(), sy = (at.h2() >> 1) - 1;
        Map<Long, Long> from = new HashMap<>();
        Map<Long, Integer> height = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long first = key(sx, sz);
        from.put(first, first); height.put(first, sy); queue.add(first);
        long goal = Long.MIN_VALUE;
        while (!queue.isEmpty() && goal == Long.MIN_VALUE) {
            long here = queue.poll();
            int x = kx(here), z = kz(here), y = height.get(here);
            if (Math.abs(x - sx) > CONNECT || Math.abs(z - sz) > CONNECT) continue;
            for (Direction direction : Direction.Type.HORIZONTAL) {
                int nx = x + direction.getOffsetX(), nz = z + direction.getOffsetZ();
                long next = key(nx, nz);
                if (from.containsKey(next)) continue;
                Integer street = streets.get(next);
                if (street != null && Math.abs(street - y) <= 1) { from.put(next, here); goal = next; break; }
                int ny = top(world, nx, nz, y + 2, y - 2);
                if (ny == Integer.MIN_VALUE || Math.abs(ny - y) > 1 || inside(others, nx, ny, nz) || !walkable(world, palette, new BlockPos(nx, ny, nz))) continue;
                from.put(next, here); height.put(next, ny); queue.add(next);
            }
        }
        if (goal == Long.MIN_VALUE) return Long.MIN_VALUE;
        List<Long> way = new ArrayList<>();
        for (long k = from.get(goal); k != first; k = from.get(k)) way.add(k);
        for (long k : way) {
            int x = kx(k), z = kz(k);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) continue;
                int y = height.get(k), cy = top(world, x + dx, z + dz, y + 1, y - 1);
                if (cy == Integer.MIN_VALUE || streets.containsKey(key(x + dx, z + dz)) || inside(others, x + dx, cy, z + dz)) continue;
                pave(world, palette, new BlockPos(x + dx, cy, z + dz));
            }
        }
        report.connected++;
        return goal;
    }

    private static boolean inside(List<BlockBox> boxes, int x, int y, int z) {
        for (BlockBox box : boxes) if (box.contains(x, y, z)) return true;
        return false;
    }

    /** Ground one may pave a way over: natural (or the road's own) with room to walk above. */
    private static boolean walkable(ServerWorld world, RoadBuilder.Palette palette, BlockPos ground) {
        BlockState state = world.getBlockState(ground);
        if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS) || !state.getFluidState().isEmpty()) return false;
        if (!RoadBuilder.natural(state) && !palette.owns(state.getBlock())) return false;
        for (int k = 1; k <= 2; k++) {
            BlockState above = world.getBlockState(ground.up(k));
            if (!above.isAir() && !above.isIn(BlockTags.REPLACEABLE) && !above.isIn(BlockTags.FLOWERS) && !above.isOf(Blocks.SNOW)) return false;
        }
        return true;
    }

    /** Lays one paving block where the ground's top is, and takes away the grass or flowers on it. */
    private static void pave(ServerWorld world, RoadBuilder.Palette palette, BlockPos ground) {
        BlockState state = world.getBlockState(ground);
        if (state.isIn(BlockTags.PLANKS) || (!RoadBuilder.natural(state) && !palette.owns(state.getBlock()) && !state.isOf(Blocks.DIRT_PATH))) return;
        if (!state.getFluidState().isEmpty()) return;
        Block[] paving = palette.paving();
        Block block = paving[Math.floorMod(ground.getX() * 31 + ground.getZ() * 17 + (ground.getX() ^ ground.getZ()) * 7, paving.length)];
        if (!state.isOf(block)) world.setBlockState(ground, block.getDefaultState(), Block.NOTIFY_LISTENERS);
        BlockState above = world.getBlockState(ground.up());
        if (above.isIn(BlockTags.REPLACEABLE) && !above.isAir() || above.isIn(BlockTags.FLOWERS)) world.setBlockState(ground.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    // ---- Through the village ----

    /** How much dearer a step over open ground is than one along a street, and how far round the village the way may run. */
    static final int OFF_STREET = 6, AROUND = 16;

    /** Paves the cheapest way between two streets the roads reach: along the village's streets (the whole width of each street on
     * it) and, where they do not join up (a step too high, a gap worldgen left), over a little open ground, paved three wide. */
    private static void through(ServerWorld world, RoadBuilder.Palette palette, Map<Long, Integer> streets, List<BlockBox> others, BlockBox village,
                                long a, long b, boolean lamps, Report report) {
        Map<Long, Long> from = new HashMap<>();
        Map<Long, Integer> height = new HashMap<>(), cost = new HashMap<>();
        PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.comparingLong(e -> e[0]));
        from.put(a, a); height.put(a, streets.get(a)); cost.put(a, 0); queue.add(new long[] { 0, a });
        while (!queue.isEmpty()) {
            long[] head = queue.poll();
            long here = head[1];
            if (head[0] > cost.get(here)) continue;
            if (here == b) break;
            int x = kx(here), z = kz(here), y = height.get(here);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = x + dx, nz = z + dz;
                if (nx < village.getMinX() - AROUND || nx > village.getMaxX() + AROUND || nz < village.getMinZ() - AROUND || nz > village.getMaxZ() + AROUND) continue;
                long next = key(nx, nz);
                Integer ny = streets.get(next);
                int step = (dx != 0 && dz != 0) ? 14 : 10;
                if (ny == null) {
                    int ground = top(world, nx, nz, y + 2, y - 2);
                    if (ground == Integer.MIN_VALUE || inside(others, nx, ground, nz) || !walkable(world, palette, new BlockPos(nx, ground, nz))) continue;
                    ny = ground; step *= OFF_STREET;
                }
                if (Math.abs(ny - y) > 1) continue;
                int c = cost.get(here) + step;
                Integer old = cost.get(next);
                if (old != null && old <= c) continue;
                cost.put(next, c); from.put(next, here); height.put(next, ny); queue.add(new long[] { c, next });
            }
        }
        if (!from.containsKey(b)) return;
        List<Long> way = new ArrayList<>();
        for (long k = b; ; k = from.get(k)) { way.add(k); if (k == a) break; }
        Set<Long> paved = new HashSet<>();
        for (long k : way) {
            boolean street = streets.containsKey(k);
            int r = street ? 2 : 1;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                long near = key(kx(k) + dx, kz(k) + dz);
                Integer y = streets.get(near);
                if (y == null && street) continue;
                if (y == null) {
                    int h = height.get(k), ground = top(world, kx(near), kz(near), h + 1, h - 1);
                    if (ground == Integer.MIN_VALUE || inside(others, kx(near), ground, kz(near))) continue;
                    y = ground;
                }
                if (!paved.add(near)) continue;
                pave(world, palette, new BlockPos(kx(near), y, kz(near)));
            }
        }
        Map<Long, Integer> heights = new HashMap<>(streets);
        for (long k : way) heights.putIfAbsent(k, height.get(k));
        report.paved += paved.size();
        if (!lamps) return;
        for (int i = LAMP_STEP / 2; i < way.size(); i += LAMP_STEP) {
            long k = way.get(i), ahead = way.get(Math.min(way.size() - 1, i + 1)), behind = way.get(Math.max(0, i - 1));
            int fx = Integer.signum(kx(ahead) - kx(behind)), fz = Integer.signum(kz(ahead) - kz(behind));
            if (fx == 0 && fz == 0) continue;
            // Beside the street, on whichever side has open ground: the first column off the street, the arm over the street's edge.
            for (int side : new int[] { 1, -1 }) {
                int sx = -fz * side, sz = fx * side;
                BlockPos post = null;
                for (int d = 1; d <= 4 && post == null; d++) {
                    int px = kx(k) + sx * d, pz = kz(k) + sz * d;
                    if (streets.containsKey(key(px, pz)) || paved.contains(key(px, pz))) continue;
                    int y = top(world, px, pz, heights.get(k) + 2, heights.get(k) - 2);
                    if (y != Integer.MIN_VALUE && walkable(world, palette, new BlockPos(px, y, pz))) post = new BlockPos(px, y + 1, pz);
                    else break;
                }
                if (post != null && lamp(world, palette, post, -sx, -sz)) { report.lamps++; break; }
            }
        }
    }

    /** A street lamp: the post standing at {@code base}, its arm reaching one block toward (ax, az) with the lantern under it. */
    static boolean lamp(ServerWorld world, RoadBuilder.Palette palette, BlockPos base, int ax, int az) {
        BlockPos arm = base.add(ax, 4, az);
        for (int k = 0; k <= 4; k++) if (!free(world, palette, base.up(k))) return false;
        if (!free(world, palette, arm) || !free(world, palette, arm.down())) return false;
        set(world, base, palette.postBase().getDefaultState());
        for (int k = 1; k <= 4; k++) set(world, base.up(k), palette.post().getDefaultState());
        set(world, arm, palette.post().getDefaultState());
        set(world, arm.down(), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        return true;
    }

    private static boolean free(ServerWorld world, RoadBuilder.Palette palette, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.FLOWERS) || state.isOf(Blocks.SNOW) || palette.owns(state.getBlock());
    }

    private static void set(ServerWorld world, BlockPos pos, BlockState state) {
        if (world.getBlockState(pos) != state) world.setBlockState(pos, state, Block.NOTIFY_ALL);
    }

    // ---- The station ----

    /** Where the only road into a village ends: the road widens into a square {@link #STATION} blocks long, with a lean-to shelter
     * and a bench on one side (lanterns under its roof), a hitching post and a hay bale on the other. The carts and wagons stop
     * here and go in; the village's streets are reached by the way paved from it. */
    private static void station(ServerWorld world, RoadBuilder.Palette palette, Entry entry, boolean lamps) {
        RoadPlan.Point at = entry.at();
        double ox = -entry.ix(), oz = -entry.iz(), nx = -oz, nz = ox; // out along the road, and across it
        int y0 = at.h2() >> 1;
        for (double a = 0; a <= STATION; a += 0.5)
            for (double c = -STATION_HALF; c <= STATION_HALF; c += 0.5) {
                BlockPos column = new BlockPos(MathHelper.floor(at.x() + ox * a + nx * c), y0 - 1, MathHelper.floor(at.z() + oz * a + nz * c));
                square(world, palette, column);
            }
        Direction out = Direction.getFacing(ox, 0, oz), side = Direction.getFacing(nx, 0, nz);
        BlockPos origin = BlockPos.ofFloored(at.x(), y0, at.z());
        // The shelter on the left: posts at its outer corners, a roof of slabs over the strip beside the road.
        BlockState roof = Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);
        for (int a = 1; a <= 7; a++) for (int c = 3; c <= 4; c++) {
            BlockPos under = origin.offset(out, a).offset(side, c);
            if (free(world, palette, under.up(3))) set(world, under.up(3), roof);
            if (c == 4 && (a == 1 || a == 7)) for (int k = 0; k < 3; k++) if (free(world, palette, under.up(k))) set(world, under.up(k), palette.post().getDefaultState());
        }
        for (int a = 3; a <= 5; a++) {
            BlockPos seat = origin.offset(out, a).offset(side, 4);
            if (free(world, palette, seat)) set(world, seat, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, side).with(StairsBlock.HALF, BlockHalf.BOTTOM));
        }
        for (int a : new int[] { 2, 6 }) {
            BlockPos hang = origin.offset(out, a).offset(side, 3).up(2);
            if (lamps && free(world, palette, hang)) set(world, hang, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        }
        // The other side: a hitching post and hay for the horses, and a street lamp.
        BlockPos hitch = origin.offset(out, 4).offset(side, -4), hay = origin.offset(out, 6).offset(side, -4);
        if (free(world, palette, hitch)) set(world, hitch, palette.post().getDefaultState());
        if (free(world, palette, hitch.up())) set(world, hitch.up(), palette.post().getDefaultState());
        if (free(world, palette, hay)) set(world, hay, Blocks.HAY_BLOCK.getDefaultState());
        if (lamps) lamp(world, palette, origin.offset(out, 8).offset(side, -4), side.getOffsetX(), side.getOffsetZ());
    }

    /** One column of the station's square: paving level with the road, fill under it, the room above cleared of what worldgen put. */
    private static void square(ServerWorld world, RoadBuilder.Palette palette, BlockPos ground) {
        BlockState state = world.getBlockState(ground);
        if (!RoadBuilder.natural(state) && !palette.owns(state.getBlock())) return;
        for (int k = 1; k <= 4; k++) {
            BlockState above = world.getBlockState(ground.up(k));
            if (!RoadBuilder.natural(above) && !palette.owns(above.getBlock())) return;
        }
        pave(world, palette, ground);
        if (!world.getBlockState(ground).isOf(palette.paving()[0]) && !Arrays.asList(palette.paving()).contains(world.getBlockState(ground).getBlock())) {
            world.setBlockState(ground, palette.paving()[0].getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        for (int k = 1; k <= 2; k++) {
            BlockState below = world.getBlockState(ground.down(k));
            if (below.isAir() || !below.getFluidState().isEmpty() || below.isIn(BlockTags.REPLACEABLE)) world.setBlockState(ground.down(k), palette.fill().getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        for (int k = 1; k <= 4; k++) {
            BlockState above = world.getBlockState(ground.up(k));
            if (above.isAir() || above.isOf(palette.post()) || above.isOf(Blocks.LANTERN)) continue;
            world.setBlockState(ground.up(k), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
    }
}
