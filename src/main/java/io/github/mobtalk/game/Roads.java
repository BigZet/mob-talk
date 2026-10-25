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
}
