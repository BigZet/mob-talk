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

/** Where the roads meet a village (docs/roads.md). Since Dmitriy's word of 2026-10-25 («дорога не упирается прям в деревню, но
 * проходит близко к ней … есть сход в деревню — тропа») the roads of a village meet at its junction beside the village: a paved
 * square with a lean-to shelter, a bench, a hitching post and lamps, where the carts and wagons change roads or turn round, and
 * from the square a trail three wide (a dirt path with gravel and coarse dirt, lanterns along it) leads over the open ground to
 * the village's nearest street. The village's houses are never touched. The earlier way is kept for roads laid before: a road
 * that runs into the village stops where its pieces begin, a short paved way leads to the nearest street, a village two or more
 * such roads come to has its streets between their entries paved, and one only one road comes to gets a station where it ends.
 * Only blocks worldgen made (and the road's own) are touched. Server thread only, once the chunks concerned are loaded. */
final class RoadVillages {
    private RoadVillages() { }

    /** How far the way from a road's end to the streets may run, the step between lamps along a paved street, the station's length
     * along the road and half its width. */
    static final int CONNECT = 40, LAMP_STEP = 20, STATION = 9;
    static final double STATION_HALF = 4.5;
    /** The junction's square reaches this far from its middle; the trail to the village may run this far (as the crow flies) and
     * has a lantern every so many blocks. */
    static final int PLAZA = 5, TRAIL = 72, TRAIL_LAMP = 12;

    /** A village's junction: where its roads meet beside it, the height of the feet there, the ways the roads leave it (unit
     * vectors) and the land loaded for the work {minX, minZ, maxX, maxZ}. */
    record Hub(int x, int y, int z, List<double[]> ways, int[] area) { }

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
        /** A junction: the trail's length (0 when none could be laid, {@code why} saying why), and whether the shelter stands. */
        boolean hub, shelter; int trail, trees; String why;
        @Override public String toString() {
            if (hub) return "развязка: тропа " + trail + " блоков" + (why != null ? " (" + why + ")" : "") + ", фонарей " + lamps + (shelter ? ", навес" : "") + ", срублено деревьев " + trees;
            return "подъездов " + connected + ", вымощено улиц " + paved + ", фонарей " + lamps + (station ? ", станция" : "");
        }
    }

    // ---- The junction ----

    /** The village's street piece nearest the spot (by its box); null when the village has no streets. */
    static BlockBox nearestStreet(StructureStart start, int x, int z) {
        BlockBox best = null; double bestD = Double.MAX_VALUE;
        for (StructurePiece piece : start.getChildren()) {
            if (!street(piece)) continue;
            BlockBox box = piece.getBoundingBox();
            double d = Math.hypot(Math.max(0, Math.max(box.getMinX() - x, x - box.getMaxX())), Math.max(0, Math.max(box.getMinZ() - z, z - box.getMaxZ())));
            if (d < bestD) { bestD = d; best = box; }
        }
        return best;
    }

    /** Builds the village's junction: the square where its roads meet, the trail from it to the village's nearest street, the
     * shelter, hitching post and lamps; then trunks it left hanging are felled and hanging leaves taken away. */
    static Report junction(ServerWorld world, StructureStart start, Hub hub, RoadBuilder.Palette palette, boolean lamps, int strip) {
        Report report = new Report();
        report.hub = true;
        List<BlockBox> others = new ArrayList<>(), pieces = new ArrayList<>();
        for (StructurePiece piece : start.getChildren()) { pieces.add(piece.getBoundingBox()); if (!street(piece)) others.add(piece.getBoundingBox()); }
        Set<Long> cleared = new HashSet<>();
        int[] area = { hub.x() - PLAZA - 3, hub.z() - PLAZA - 3, hub.x() + PLAZA + 3, hub.z() + PLAZA + 3 };
        plaza(world, palette, hub, others, cleared, area);
        // The trees round the square go as those beside the road do (the strip), so nothing hangs over it.
        Set<Long> seen = new HashSet<>();
        for (int dx = -PLAZA - strip; dx <= PLAZA + strip; dx++)
            for (int dz = -PLAZA - strip; dz <= PLAZA + strip; dz++)
                report.trees += RoadBuilder.fellTrunks(world, hub.x() + dx, hub.z() + dz, hub.y() - 4, hub.y() + RoadBuilder.BANK + 2, pieces, palette, seen, area);
        Map<Long, Integer> streets = streets(world, start, hub.area());
        long goal = trail(world, palette, hub, streets, others, cleared, area, lamps, report);
        double[] in = goal == Long.MIN_VALUE ? null : unit(kx(goal) - hub.x(), kz(goal) - hub.z());
        furnish(world, palette, hub, in, lamps, report);
        RoadBuilder.tidy(world, cleared, pieces, area, hub.y() - 12, hub.y() + 24);
        return report;
    }

    private static double[] unit(double x, double z) {
        double d = Math.hypot(x, z);
        return d < 1e-6 ? new double[] { 1, 0 } : new double[] { x / d, z / d };
    }

    /** The square: paving level with the junction, a curb round it except where a road comes in, everything above cleared. */
    private static void plaza(ServerWorld world, RoadBuilder.Palette palette, Hub hub, List<BlockBox> others, Set<Long> cleared, int[] area) {
        for (int dx = -PLAZA; dx <= PLAZA; dx++)
            for (int dz = -PLAZA; dz <= PLAZA; dz++) {
                if (inside(others, hub.x() + dx, hub.y(), hub.z() + dz)) continue;
                boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == PLAZA;
                if (ring) for (double[] way : hub.ways()) {
                    double along = dx * way[0] + dz * way[1], across = -dx * way[1] + dz * way[0];
                    if (along > 0 && Math.abs(across) <= RoadBuilder.CURB + 0.5) ring = false; // the road's mouth
                }
                column(world, palette, new BlockPos(hub.x() + dx, hub.y() - 1, hub.z() + dz), ring ? palette.curb() : null, true, cleared);
            }
    }

    /** One column of the square or the trail: {@code block} (or the road's paving) at {@code ground}, filled under down to the
     * ground when {@code fill}, the room above cleared as over the road. False when something built stands there. */
    private static boolean column(ServerWorld world, RoadBuilder.Palette palette, BlockPos ground, Block block, boolean fill, Set<Long> cleared) {
        BlockState state = world.getBlockState(ground);
        if (!RoadBuilder.natural(state) && !palette.owns(state.getBlock()) && !state.isOf(Blocks.DIRT_PATH) && !trailBlock(state)) return false;
        for (int k = 1; k <= 2; k++) {
            BlockState above = world.getBlockState(ground.up(k));
            if (!RoadBuilder.natural(above) && !palette.owns(above.getBlock())) return false;
        }
        if (block == null) {
            Block[] paving = palette.paving();
            block = paving[Math.floorMod(ground.getX() * 31 + ground.getZ() * 17 + (ground.getX() ^ ground.getZ()) * 7, paving.length)];
        }
        if (!state.isOf(block)) world.setBlockState(ground, block.getDefaultState(), Block.NOTIFY_LISTENERS);
        if (fill) for (int k = 1; k <= RoadBuilder.FILL; k++) {
            BlockPos below = ground.down(k);
            if (!RoadBuilder.open(world.getBlockState(below))) break;
            world.setBlockState(below, palette.fill().getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        RoadBuilder.clearAbove(world, palette, ground.getX(), ground.getZ(), ground.getY(), cleared);
        return true;
    }

    /** The trail's blocks: a dirt path down the middle with gravel and coarse dirt at the sides (sandstone in the sand). */
    private static Block trailBlock(RoadBuilder.Palette palette, int x, int z, boolean middle) {
        boolean sand = palette == RoadBuilder.Palette.SANDSTONE;
        if (middle) return sand ? Blocks.SMOOTH_SANDSTONE : Blocks.DIRT_PATH;
        int pick = Math.floorMod(x * 29 + z * 13 + (x ^ z) * 5, 4);
        if (sand) return pick == 0 ? Blocks.CUT_SANDSTONE : pick == 1 ? Blocks.SMOOTH_SANDSTONE : Blocks.SANDSTONE;
        return pick == 0 ? Blocks.GRAVEL : pick == 1 ? Blocks.COARSE_DIRT : Blocks.DIRT_PATH;
    }

    private static boolean trailBlock(BlockState state) {
        return state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.SMOOTH_SANDSTONE)
                || state.isOf(Blocks.CUT_SANDSTONE) || state.isOf(Blocks.SANDSTONE);
    }

    /** The trail from the square to the village's nearest street: the shortest walk over open ground (a step of one block at most)
     * from the junction to any street block, paved three wide from where it leaves the square, a lantern every {@link #TRAIL_LAMP}
     * blocks beside it. The street block reached, or MIN_VALUE when none can be. */
    private static long trail(ServerWorld world, RoadBuilder.Palette palette, Hub hub, Map<Long, Integer> streets, List<BlockBox> others, Set<Long> cleared, int[] area,
                              boolean lamps, Report report) {
        if (streets.isEmpty()) { report.why = "улиц рядом нет"; return Long.MIN_VALUE; }
        int[] bounds = hub.area();
        Map<Long, Long> from = new HashMap<>();
        Map<Long, Integer> height = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long first = key(hub.x(), hub.z());
        from.put(first, first); height.put(first, hub.y() - 1); queue.add(first);
        long goal = Long.MIN_VALUE;
        while (!queue.isEmpty() && goal == Long.MIN_VALUE) {
            long here = queue.poll();
            int x = kx(here), z = kz(here), y = height.get(here);
            if (Math.abs(x - hub.x()) > TRAIL || Math.abs(z - hub.z()) > TRAIL) continue;
            for (Direction direction : Direction.Type.HORIZONTAL) {
                int nx = x + direction.getOffsetX(), nz = z + direction.getOffsetZ();
                if (nx < bounds[0] || nz < bounds[1] || nx > bounds[2] || nz > bounds[3]) continue;
                long next = key(nx, nz);
                if (from.containsKey(next)) continue;
                Integer street = streets.get(next);
                if (street != null && Math.abs(street - y) <= 1) { from.put(next, here); goal = next; break; }
                boolean square = Math.max(Math.abs(nx - hub.x()), Math.abs(nz - hub.z())) <= PLAZA;
                int ny = square ? hub.y() - 1 : top(world, nx, nz, y + 2, y - 2);
                if (ny == Integer.MIN_VALUE || Math.abs(ny - y) > 1 || inside(others, nx, ny, nz) || (!square && !walkable(world, palette, new BlockPos(nx, ny, nz)))) continue;
                from.put(next, here); height.put(next, ny); queue.add(next);
            }
        }
        if (goal == Long.MIN_VALUE) { report.why = "до улицы не дошла"; return Long.MIN_VALUE; }
        List<Long> way = new ArrayList<>();
        for (long k = from.get(goal); k != first; k = from.get(k)) way.add(k);
        Collections.reverse(way);
        int laid = 0;
        for (long k : way) {
            int x = kx(k), z = kz(k);
            if (Math.max(Math.abs(x - hub.x()), Math.abs(z - hub.z())) <= PLAZA) continue;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) continue;
                int y = height.get(k), cy = top(world, x + dx, z + dz, y + 1, y - 1);
                if (cy == Integer.MIN_VALUE || streets.containsKey(key(x + dx, z + dz)) || inside(others, x + dx, cy, z + dz)) continue;
                if (column(world, palette, new BlockPos(x + dx, cy, z + dz), trailBlock(palette, x + dx, z + dz, dx == 0 && dz == 0), false, cleared)) {
                    area[0] = Math.min(area[0], x + dx); area[1] = Math.min(area[1], z + dz); area[2] = Math.max(area[2], x + dx); area[3] = Math.max(area[3], z + dz);
                }
            }
            laid++;
        }
        report.trail = laid;
        if (!lamps) return goal;
        int count = 0;
        for (int i = 0; i < way.size(); i++) {
            long k = way.get(i);
            if (Math.max(Math.abs(kx(k) - hub.x()), Math.abs(kz(k) - hub.z())) <= PLAZA) continue;
            if (++count % TRAIL_LAMP != TRAIL_LAMP / 2) continue;
            long ahead = way.get(Math.min(way.size() - 1, i + 1)), behind = way.get(Math.max(0, i - 1));
            int fx = Integer.signum(kx(ahead) - kx(behind)), fz = Integer.signum(kz(ahead) - kz(behind));
            if (fx == 0 && fz == 0) continue;
            int side = (count / TRAIL_LAMP) % 2 == 0 ? 1 : -1;
            int sx = -fz * side, sz = fx * side;
            int px = kx(k) + sx * 2, pz = kz(k) + sz * 2, y = top(world, px, pz, height.get(k) + 2, height.get(k) - 2);
            if (y == Integer.MIN_VALUE || !walkable(world, palette, new BlockPos(px, y, pz))) continue;
            if (lamp(world, palette, new BlockPos(px, y + 1, pz), -sx, -sz)) report.lamps++;
        }
        return goal;
    }

    /** The shelter with its bench and lanterns where no road or trail leaves the square: a lean-to along the middle of a side, or
     * shifted toward a corner; or, when every side has a way through it (a village three roads and the trail come to), a small
     * gazebo in a corner between two of the ways. The hitching post and hay stand across the square from it when no way leaves
     * there either; a lamp stands at each corner of the square the shelter does not take. */
    private static void furnish(ServerWorld world, RoadBuilder.Palette palette, Hub hub, double[] in, boolean lamps, Report report) {
        BlockPos origin = new BlockPos(hub.x(), hub.y(), hub.z());
        List<double[]> busy = new ArrayList<>(hub.ways());
        if (in != null) busy.add(in);
        Direction out = null; int shift = 0; double best = Double.MAX_VALUE;
        for (Direction candidate : Direction.Type.HORIZONTAL) {
            Direction side = candidate.rotateYClockwise();
            for (int s : new int[] { 0, 4, -4 }) {
                if (crossed(busy, candidate, side, s, 4)) continue;
                // The side the ways face away from is best; the middle of a side before a corner.
                double facing = -1;
                for (double[] way : busy) facing = Math.max(facing, candidate.getOffsetX() * way[0] + candidate.getOffsetZ() * way[1]);
                double score = facing + Math.abs(s) / 8.0;
                if (score < best) { best = score; out = candidate; shift = s; }
            }
        }
        int cornerX = 0, cornerZ = 0;
        if (out == null) {
            best = Double.MAX_VALUE;
            for (int sx : new int[] { -1, 1 }) for (int sz : new int[] { -1, 1 }) {
                if (crossedRect(busy, sx * PLAZA, sz * PLAZA, sx * (PLAZA + 4), sz * (PLAZA + 4))) continue;
                double facing = -1;
                for (double[] way : busy) facing = Math.max(facing, (sx * way[0] + sz * way[1]) / Math.sqrt(2));
                if (facing < best) { best = facing; cornerX = sx; cornerZ = sz; }
            }
        }
        if (out != null) {
            Direction side = out.rotateYClockwise();
            BlockPos base = origin.offset(side, shift);
            Set<Long> cleared = new HashSet<>();
            for (int c = 1; c <= 2; c++) for (int a = -3; a <= 3; a++)
                column(world, palette, base.offset(out, PLAZA + c).offset(side, a).down(), null, true, cleared);
            BlockState roof = Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);
            boolean built = false;
            for (int c = 1; c <= 2; c++) for (int a = -3; a <= 3; a++) {
                BlockPos under = base.offset(out, PLAZA + c).offset(side, a);
                if (free(world, palette, under.up(3))) { set(world, under.up(3), roof); built = true; }
                if (c == 2 && Math.abs(a) == 3) for (int k = 0; k < 3; k++) if (free(world, palette, under.up(k))) set(world, under.up(k), palette.post().getDefaultState());
            }
            for (int a = -1; a <= 1; a++) {
                BlockPos seat = base.offset(out, PLAZA + 2).offset(side, a);
                if (free(world, palette, seat)) set(world, seat, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, out).with(StairsBlock.HALF, BlockHalf.BOTTOM));
            }
            for (int a : new int[] { -2, 2 }) {
                BlockPos hang = base.offset(out, PLAZA + 1).offset(side, a).up(2);
                if (lamps && free(world, palette, hang)) set(world, hang, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
            }
            report.shelter = built;
            // Across the square: the hitching post and hay, when no way leaves that side either.
            if (!crossed(busy, out.getOpposite(), side, 0, 2)) {
                BlockPos hitch = origin.offset(out, -(PLAZA + 1)).offset(side, -1), hay = origin.offset(out, -(PLAZA + 1)).offset(side, 1);
                hitch(world, palette, hitch, hay);
            }
        } else if (cornerX != 0) {
            // The gazebo: a 3×3 pad in the corner, a post at each corner of it, a slab roof, a bench along its far sides, a lantern
            // hung in the middle.
            Set<Long> cleared = new HashSet<>();
            for (int i = 1; i <= 3; i++) for (int j = 1; j <= 3; j++)
                column(world, palette, origin.add(cornerX * (PLAZA + i), -1, cornerZ * (PLAZA + j)), null, true, cleared);
            BlockState roof = Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);
            boolean built = false;
            for (int i = 1; i <= 3; i++) for (int j = 1; j <= 3; j++) {
                BlockPos under = origin.add(cornerX * (PLAZA + i), 0, cornerZ * (PLAZA + j));
                if (free(world, palette, under.up(3))) { set(world, under.up(3), roof); built = true; }
                if ((i == 1 || i == 3) && (j == 1 || j == 3)) for (int k = 0; k < 3; k++) if (free(world, palette, under.up(k))) set(world, under.up(k), palette.post().getDefaultState());
            }
            Direction towardX = cornerX > 0 ? Direction.WEST : Direction.EAST, towardZ = cornerZ > 0 ? Direction.NORTH : Direction.SOUTH;
            for (int j = 1; j <= 2; j++) {
                BlockPos seat = origin.add(cornerX * (PLAZA + 3), 0, cornerZ * (PLAZA + j));
                if (free(world, palette, seat)) set(world, seat, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, towardX).with(StairsBlock.HALF, BlockHalf.BOTTOM));
            }
            BlockPos seat = origin.add(cornerX * (PLAZA + 2), 0, cornerZ * (PLAZA + 3));
            if (free(world, palette, seat)) set(world, seat, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, towardZ).with(StairsBlock.HALF, BlockHalf.BOTTOM));
            BlockPos hang = origin.add(cornerX * (PLAZA + 2), 2, cornerZ * (PLAZA + 2));
            if (lamps && free(world, palette, hang)) set(world, hang, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
            report.shelter = built;
            // The hitching post and hay in the corner across, when no way runs through it.
            if (!crossedRect(busy, -cornerX * PLAZA, -cornerZ * PLAZA, -cornerX * (PLAZA + 3), -cornerZ * (PLAZA + 3)))
                hitch(world, palette, origin.add(-cornerX * (PLAZA + 2), 0, -cornerZ * (PLAZA + 1)), origin.add(-cornerX * (PLAZA + 1), 0, -cornerZ * (PLAZA + 2)));
        }
        if (!lamps) return;
        for (int sx : new int[] { -1, 1 }) for (int sz : new int[] { -1, 1 }) {
            int px = hub.x() + sx * (PLAZA + 1), pz = hub.z() + sz * (PLAZA + 1);
            if (out != null) {
                Direction side = out.rotateYClockwise();
                int c = sx * (PLAZA + 1) * out.getOffsetX() + sz * (PLAZA + 1) * out.getOffsetZ(), a = sx * (PLAZA + 1) * side.getOffsetX() + sz * (PLAZA + 1) * side.getOffsetZ();
                if (c >= PLAZA + 1 && c <= PLAZA + 2 && a >= shift - 3 && a <= shift + 3) continue; // under the shelter
            } else if (sx == cornerX && sz == cornerZ) continue; // the gazebo's corner
            int y = top(world, px, pz, hub.y() + 2, hub.y() - 3);
            if (y == Integer.MIN_VALUE || !walkable(world, palette, new BlockPos(px, y, pz))) continue;
            if (lamp(world, palette, new BlockPos(px, y + 1, pz), -sx, 0)) report.lamps++;
        }
    }

    /** A hitching post (two fence blocks) and a hay bale beside it, each on solid ground with room above. */
    private static void hitch(ServerWorld world, RoadBuilder.Palette palette, BlockPos hitch, BlockPos hay) {
        if (free(world, palette, hitch) && free(world, palette, hitch.up()) && !free(world, palette, hitch.down())) {
            set(world, hitch, palette.post().getDefaultState()); set(world, hitch.up(), palette.post().getDefaultState());
        }
        if (free(world, palette, hay) && !free(world, palette, hay.down())) set(world, hay, Blocks.HAY_BLOCK.getDefaultState());
    }

    /** Whether a way out of the square runs through a footprint beside it: the columns {@link #PLAZA} to {@code PLAZA + 3} out
     * along {@code out} and {@code shift} ± {@code half} along {@code side}. */
    private static boolean crossed(List<double[]> ways, Direction out, Direction side, int shift, int half) {
        int x0 = out.getOffsetX() * PLAZA + side.getOffsetX() * (shift - half), z0 = out.getOffsetZ() * PLAZA + side.getOffsetZ() * (shift - half);
        int x1 = out.getOffsetX() * (PLAZA + 3) + side.getOffsetX() * (shift + half), z1 = out.getOffsetZ() * (PLAZA + 3) + side.getOffsetZ() * (shift + half);
        return crossedRect(ways, x0, z0, x1, z1);
    }

    /** Whether a way out of the square runs through the columns of a rectangle (given from the square's middle, any corner
     * order): a way's corridor is as wide as the road with a block to spare on each side. */
    private static boolean crossedRect(List<double[]> ways, int ax, int az, int bx, int bz) {
        for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++)
            for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++)
                for (double[] way : ways) {
                    double along = x * way[0] + z * way[1], across = -x * way[1] + z * way[0];
                    if (along > 0 && Math.abs(across) <= RoadBuilder.CURB + 1.5) return true;
                }
        return false;
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

    /** A street piece: the vanilla villages' are "streets", CTOV's "roads" (ctov:village/plains/roads/straight and the like). */
    static boolean street(StructurePiece piece) {
        if (!(piece instanceof PoolStructurePiece pool)) return false;
        String element = pool.getPoolElement().toString();
        return element.contains("/streets/") || element.contains("/roads/");
    }

    /** The blocks a street is made of: the vanilla path and the desert's sandstone, and CTOV's mix of a road (cobbles, bricks, mud,
     * sand, coarse dirt and the like; the grass it mixes in is crossed as open ground). */
    private static boolean streetBlock(BlockState state) {
        return state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.SMOOTH_SANDSTONE) || state.isOf(Blocks.SANDSTONE) || state.isOf(Blocks.CUT_SANDSTONE)
                || state.isOf(Blocks.RED_SANDSTONE) || state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.MOSSY_COBBLESTONE) || state.isOf(Blocks.STONE_BRICKS)
                || state.isOf(Blocks.MOSSY_STONE_BRICKS) || state.isOf(Blocks.MUD_BRICKS) || state.isOf(Blocks.MUD) || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.TERRACOTTA) || state.isOf(Blocks.RED_SAND) || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.COBBLED_DEEPSLATE) || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.MYCELIUM) || state.isIn(BlockTags.PLANKS);
    }

    /** The top block (by the heightmap) of every column of the village's street pieces that is street: the path (dirt path, the
     * desert's sandstone), the planks of a street over water. The grass worldgen leaves in a path here and there is crossed as open
     * ground, and a piece's box is wider than its path. Keyed by the column, the value its height. */
    private static Map<Long, Integer> streets(ServerWorld world, StructureStart start) { return streets(world, start, null); }

    /** The same within an area {minX, minZ, maxX, maxZ} (the land loaded), when one is given. */
    private static Map<Long, Integer> streets(ServerWorld world, StructureStart start, int[] area) {
        Map<Long, Integer> out = new HashMap<>();
        for (StructurePiece piece : start.getChildren()) {
            if (!street(piece)) continue;
            BlockBox box = piece.getBoundingBox();
            if (area != null && (box.getMaxX() < area[0] || box.getMinX() > area[2] || box.getMaxZ() < area[1] || box.getMinZ() > area[3])) continue;
            for (int x = box.getMinX(); x <= box.getMaxX(); x++)
                for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                    if (area != null && (x < area[0] || x > area[2] || z < area[1] || z > area[3])) continue;
                    // A street follows the ground: its box keeps the template's height, not the path's.
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    if (y == Integer.MIN_VALUE) continue;
                    BlockState state = world.getBlockState(new BlockPos(x, y, z));
                    if (streetBlock(state)) out.put(key(x, z), y);
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
