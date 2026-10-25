package io.github.mobtalk.game;

import io.github.mobtalk.core.RoadPlan;
import java.util.function.Consumer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/** The village roads as the client's self-test sees them (docs/roads.md): plan a road between two spots, hold its chunks until it is
 * laid, ask whether it is, where a point of it is, and bring a trip of its traffic to a spot. Server thread only. */
public final class Roads {
    private Roads() { }

    private static MobGameBridge bridge(ServerWorld world) { return MobGameBridge.active(world.getServer()); }

    /** Plans a road from one spot to the other; {@code done} hears the command's line ("дорога N: …" or why there is none). */
    public static void build(ServerWorld world, int ax, int az, int bx, int bz, Consumer<String> done) {
        MobGameBridge bridge = bridge(world);
        if (bridge == null) { done.accept("Mob Talk не запущен"); return; }
        bridge.roads.build(world, ax, az, bx, bz, done);
    }

    /** Holds the road's chunks loaded until it is laid; the number of chunks held. */
    public static int load(ServerWorld world, int id) {
        MobGameBridge bridge = bridge(world);
        RoadNetwork.Road road = bridge == null ? null : bridge.roads.road(world, id);
        return road == null ? 0 : bridge.roads.load(world, road);
    }

    /** Chunks of the road laid, and all of them. */
    public static int[] laid(ServerWorld world, int id) {
        MobGameBridge bridge = bridge(world);
        RoadNetwork.Road road = bridge == null ? null : bridge.roads.road(world, id);
        return road == null ? new int[] { 0, 0 } : new int[] { road.done.size(), road.chunks().size() };
    }

    /** The road's street lamps with an arm (a hanging lantern under a fence), and those of them whose arm does not join the post:
     * no fence beside the arm with a fence under that. */
    public static int[] lampFaults(ServerWorld world, int id) {
        MobGameBridge bridge = bridge(world);
        RoadNetwork.Road road = bridge == null ? null : bridge.roads.road(world, id);
        if (road == null) return new int[] { 0, 0 };
        int lamps = 0, faults = 0;
        net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
        for (long packed : road.done) {
            net.minecraft.util.math.ChunkPos chunk = new net.minecraft.util.math.ChunkPos(packed);
            int[] range = range(road, chunk);
            for (int x = chunk.getStartX(); x <= chunk.getEndX(); x++) for (int z = chunk.getStartZ(); z <= chunk.getEndZ(); z++)
                for (int y = range[0]; y <= range[1]; y++) {
                    net.minecraft.block.BlockState state = world.getBlockState(at.set(x, y, z));
                    if (!state.isOf(net.minecraft.block.Blocks.LANTERN) || !state.get(net.minecraft.block.LanternBlock.HANGING)) continue;
                    net.minecraft.block.BlockState arm = world.getBlockState(at.set(x, y + 1, z));
                    if (!(arm.getBlock() instanceof net.minecraft.block.FenceBlock)) continue; // a tunnel's lantern hangs from the lining
                    lamps++;
                    boolean joined = false;
                    for (net.minecraft.util.math.Direction side : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                        at.set(x, y + 1, z).move(side);
                        if (world.getBlockState(at).isOf(arm.getBlock()) && world.getBlockState(at.move(net.minecraft.util.math.Direction.DOWN)).isOf(arm.getBlock())) joined = true;
                    }
                    if (!joined) faults++;
                }
        }
        return new int[] { lamps, faults };
    }

    /** Logs by the road that hang in the air: clusters of logs (joined face, edge or corner) within the road's chunks and their
     * neighbours that stand on nothing at all. */
    public static int hangingLogs(ServerWorld world, int id) {
        MobGameBridge bridge = bridge(world);
        RoadNetwork.Road road = bridge == null ? null : bridge.roads.road(world, id);
        if (road == null) return 0;
        int hanging = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
        for (long packed : road.done) {
            net.minecraft.util.math.ChunkPos chunk = new net.minecraft.util.math.ChunkPos(packed);
            int[] range = range(road, chunk);
            int x0 = chunk.getStartX() - 24, z0 = chunk.getStartZ() - 24, x1 = chunk.getEndX() + 24, z1 = chunk.getEndZ() + 24;
            for (int x = chunk.getStartX(); x <= chunk.getEndX(); x++) for (int z = chunk.getStartZ(); z <= chunk.getEndZ(); z++)
                for (int y = range[0]; y <= range[1] + 30; y++) {
                    if (seen.contains(net.minecraft.util.math.BlockPos.asLong(x, y, z)) || !world.getBlockState(at.set(x, y, z)).isIn(net.minecraft.registry.tag.BlockTags.LOGS)) continue;
                    java.util.ArrayDeque<net.minecraft.util.math.BlockPos> queue = new java.util.ArrayDeque<>();
                    java.util.List<net.minecraft.util.math.BlockPos> cluster = new java.util.ArrayList<>();
                    queue.add(new net.minecraft.util.math.BlockPos(x, y, z)); seen.add(net.minecraft.util.math.BlockPos.asLong(x, y, z));
                    boolean grounded = false;
                    while (!queue.isEmpty()) {
                        net.minecraft.util.math.BlockPos log = queue.poll();
                        cluster.add(log);
                        if (log.getX() <= x0 || log.getX() >= x1 || log.getZ() <= z0 || log.getZ() >= z1 || log.getY() <= world.getBottomY() + 1) grounded = true;
                        net.minecraft.block.BlockState below = world.getBlockState(log.down());
                        if (!below.isAir() && !below.isIn(net.minecraft.registry.tag.BlockTags.LOGS) && !below.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)
                                && !below.isIn(net.minecraft.registry.tag.BlockTags.REPLACEABLE) && below.getFluidState().isEmpty()) grounded = true;
                        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            net.minecraft.util.math.BlockPos next = log.add(dx, dy, dz);
                            if (!seen.contains(next.asLong()) && world.getBlockState(next).isIn(net.minecraft.registry.tag.BlockTags.LOGS)) { seen.add(next.asLong()); queue.add(next); }
                        }
                    }
                    if (!grounded) hanging += cluster.size();
                }
        }
        return hanging;
    }

    /** Columns of the paved road (and, on plain road, its curbs) with anything but air in the head room over them, a lamp's arm
     * and lantern apart: a pillar of rock, a trunk, a structure's block left standing in the road. {count, x, y, z of the first}. */
    public static int[] obstacles(ServerWorld world, int id) {
        MobGameBridge bridge = bridge(world);
        RoadNetwork.Road road = bridge == null ? null : bridge.roads.road(world, id);
        if (road == null) return new int[] { 0, 0, 0, 0 };
        int[] out = { 0, 0, 0, 0 };
        java.util.Set<Long> seen = new java.util.HashSet<>();
        net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
        RoadBuilder.Palette palette = RoadBuilder.palette(road.palette);
        int from = road.from == Integer.MIN_VALUE ? 0 : road.from, to = road.to == Integer.MIN_VALUE ? road.points.size() - 1 : road.to;
        for (int i = from; i <= to; i++) {
            RoadPlan.Point p = road.points.get(i);
            if (!road.done.contains(net.minecraft.util.math.ChunkPos.toLong(p.blockX() >> 4, p.blockZ() >> 4))) continue;
            double nx = -p.dz(), nz = p.dx();
            int y0 = p.h2() >> 1;
            for (double step = -RoadBuilder.CURB; step <= RoadBuilder.CURB; step += 0.5) {
                int x = net.minecraft.util.math.MathHelper.floor(p.x() + nx * step), z = net.minecraft.util.math.MathHelper.floor(p.z() + nz * step);
                // The block's own centre decides, as when the road was laid: a sample beyond the curb is the bank's.
                if (Math.abs((x + 0.5 - p.x()) * nx + (z + 0.5 - p.z()) * nz) > RoadBuilder.CURB) continue;
                if (!seen.add(net.minecraft.util.math.BlockPos.asLong(x, 0, z))) continue;
                for (int y = y0 + 1; y <= y0 + 4; y++) {
                    net.minecraft.block.BlockState state = world.getBlockState(at.set(x, y, z));
                    // The road's own (a lamp's base and post, a railing, a tunnel's wall) is no obstacle.
                    if (state.isAir() || state.getBlock() instanceof net.minecraft.block.FenceBlock || palette.owns(state.getBlock())) continue;
                    if (out[0]++ == 0) { out[1] = x; out[2] = y; out[3] = z; }
                    break;
                }
            }
        }
        return out;
    }

    /** Trunks (a tree's or a huge mushroom's) rooted within {@code strip} blocks of the curb at the road's level, on plain road
     * and bridges: what the tree strip should have felled. {count, x, y, z of the first}. */
    public static int[] stripTrees(ServerWorld world, int id, int strip) {
        MobGameBridge bridge = bridge(world);
        RoadNetwork.Road road = bridge == null ? null : bridge.roads.road(world, id);
        if (road == null) return new int[] { 0, 0, 0, 0 };
        int[] out = { 0, 0, 0, 0 };
        java.util.Set<Long> seen = new java.util.HashSet<>();
        net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
        int from = road.from == Integer.MIN_VALUE ? 0 : road.from, to = road.to == Integer.MIN_VALUE ? road.points.size() - 1 : road.to;
        double reach = RoadBuilder.CURB + strip;
        for (int i = from; i <= to; i++) {
            RoadPlan.Point p = road.points.get(i);
            if (p.kind() == RoadPlan.Kind.TUNNEL || !road.done.contains(net.minecraft.util.math.ChunkPos.toLong(p.blockX() >> 4, p.blockZ() >> 4))) continue;
            double nx = -p.dz(), nz = p.dx();
            int feet = (p.h2() + 1) >> 1;
            for (double step = -reach; step <= reach; step += 0.5) {
                int x = net.minecraft.util.math.MathHelper.floor(p.x() + nx * step), z = net.minecraft.util.math.MathHelper.floor(p.z() + nz * step);
                if (Math.abs((x + 0.5 - p.x()) * nx + (z + 0.5 - p.z()) * nz) > reach) continue; // by the block's centre, as the strip is cut
                if (!seen.add(net.minecraft.util.math.BlockPos.asLong(x, 0, z))) continue;
                for (int y = feet - 4; y <= feet + RoadBuilder.BANK + 2; y++) {
                    net.minecraft.block.BlockState state = world.getBlockState(at.set(x, y, z)), below = world.getBlockState(at.set(x, y - 1, z));
                    if (!trunk(state) || trunk(below)) continue;
                    if (out[0]++ == 0) { out[1] = x; out[2] = y; out[3] = z; }
                    break;
                }
            }
        }
        return out;
    }

    private static boolean trunk(net.minecraft.block.BlockState state) {
        return state.isIn(net.minecraft.registry.tag.BlockTags.LOGS) || state.isOf(net.minecraft.block.Blocks.MUSHROOM_STEM)
                || state.isOf(net.minecraft.block.Blocks.BROWN_MUSHROOM_BLOCK) || state.isOf(net.minecraft.block.Blocks.RED_MUSHROOM_BLOCK);
    }

    /** The village's junction {x, feet, z}, null when it has none (no road planned to it yet, or roads laid into it before). */
    public static int[] hub(ServerWorld world, String key) {
        MobGameBridge bridge = bridge(world);
        return bridge == null ? null : bridge.roads.hubOf(world, key);
    }

    /** Whether a trip arriving at the village's junction by its first road has another road to drive on along. */
    public static boolean onward(ServerWorld world, String key) {
        MobGameBridge bridge = bridge(world);
        if (bridge == null) return false;
        for (RoadNetwork.Road road : bridge.roads.roads(world))
            if (!road.branch() && (road.a.equals(key) || road.b.equals(key))) return bridge.roads.onward(world, road, key, new java.util.Random(1)) != null;
        return false;
    }

    /** The heights the road runs at in the chunk, a little below and well above. */
    private static int[] range(RoadNetwork.Road road, net.minecraft.util.math.ChunkPos chunk) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (RoadPlan.Point p : road.points) {
            if (p.x() < chunk.getStartX() - 12 || p.x() > chunk.getEndX() + 12 || p.z() < chunk.getStartZ() - 12 || p.z() > chunk.getEndZ() + 12) continue;
            low = Math.min(low, (p.h2() >> 1) - 4); high = Math.max(high, (p.h2() >> 1) + 12);
        }
        return low > high ? new int[] { 0, -1 } : new int[] { low, high };
    }

    public static int length(ServerWorld world, int id) {
        MobGameBridge bridge = bridge(world);
        RoadNetwork.Road road = bridge == null ? null : bridge.roads.road(world, id);
        return road == null ? 0 : road.points.size();
    }

    /** The middle of the road at point {@code index}, at the height of the feet on it. */
    public static Vec3d point(ServerWorld world, int id, int index) {
        RoadNetwork.Road road = bridge(world).roads.road(world, id);
        RoadPlan.Point p = road.points.get(Math.max(0, Math.min(road.points.size() - 1, index)));
        return new Vec3d(p.x(), p.h2() / 2.0, p.z());
    }

    /** The first point of a bridge and of a tunnel, -1 where the road has none. */
    public static int[] firsts(ServerWorld world, int id) {
        RoadNetwork.Road road = bridge(world).roads.road(world, id);
        int bridge = -1, tunnel = -1;
        for (int i = 0; i < road.points.size(); i++) {
            RoadPlan.Kind kind = road.points.get(i).kind();
            if (kind == RoadPlan.Kind.BRIDGE && bridge < 0) bridge = i;
            if (kind == RoadPlan.Kind.TUNNEL && tunnel < 0) tunnel = i;
        }
        return new int[] { bridge, tunnel };
    }

    /** A trip of the road ({@code cart}, {@code wagon} or {@code rider}) to its point nearest the spot, taking shape there. */
    public static String bring(ServerWorld world, int id, double x, double z, String kind) {
        MobGameBridge bridge = bridge(world);
        return bridge == null ? "Mob Talk не запущен" : bridge.traffic.bring(world, id, x, z, kind);
    }

    /** The villages with that many roads (2: two or more). */
    public static java.util.List<String> villagesWith(ServerWorld world, int roads) {
        MobGameBridge bridge = bridge(world);
        return bridge == null ? java.util.List.of() : bridge.roads.villagesWith(world, roads);
    }

    /** The village's box {minX, minY, minZ, maxX, maxY, maxZ}, null when the world has no such village. */
    public static int[] villageBox(ServerWorld world, String key) {
        net.minecraft.structure.StructureStart start = RoadBuilder.village(world, key);
        if (start == null) return null;
        net.minecraft.util.math.BlockBox box = start.getBoundingBox();
        return new int[] { box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ() };
    }

    /** Holds (or lets go) the village's chunks and a margin round it loaded, and has its streets and station done again. */
    public static void holdVillage(ServerWorld world, String key, boolean hold) {
        int[] box = villageBox(world, key);
        if (box == null) return;
        int minX = box[0] - 32, minZ = box[2] - 32, maxX = box[3] + 32, maxZ = box[5] + 32;
        // Round the junction too, so the roads' chunks beside it are laid (their nine neighbours loaded) before it is photographed.
        int[] hub = hub(world, key);
        if (hub != null) { minX = Math.min(minX, hub[0] - 40); minZ = Math.min(minZ, hub[2] - 40); maxX = Math.max(maxX, hub[0] + 40); maxZ = Math.max(maxZ, hub[2] + 40); }
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) world.setChunkForced(cx, cz, hold);
        if (hold) bridge(world).roads.redoVillage(world, key);
    }

    /** What was done in the village this session ("подъездов 1, …, станция"), null until it is. */
    public static String villageReport(ServerWorld world, String key) {
        MobGameBridge bridge = bridge(world);
        return bridge == null ? null : bridge.roads.villageReport(world, key);
    }

    /** Where a road comes into the village, {x, feet, z, ix, iz} (the way in a unit vector). */
    public static double[] villageEntry(ServerWorld world, String key) {
        MobGameBridge bridge = bridge(world);
        return bridge == null ? null : bridge.roads.villageEntry(world, key);
    }
}
