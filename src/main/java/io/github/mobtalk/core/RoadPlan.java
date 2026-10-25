package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** A road between two villages (thread «Дороги между деревнями», docs/roads.md; Dmitriy 2026-10-25: mostly straight with slight
 * bends, always lit, a tunnel or a bridge only where the ground is really uneven). The centreline is a spline through a few points
 * pushed sideways off the straight line; several such lines are tried and the cheapest wins. Its height follows the ground smoothed
 * and held to a gentle grade: a small hill is cut, a small dip filled, and only a deep cut becomes a tunnel and a high fill or deep
 * water a bridge. Pure: the ground comes from a {@link Terrain}, so it runs off the game thread and in tests. Ideas from RoadWeaver
 * (MIT, third_party/roadweaver/NOTICE.md): structure prediction, a slope-limited height profile, lamp posts. */
public final class RoadPlan {
    private RoadPlan() { }

    public enum Kind { GROUND, BRIDGE, TUNNEL }

    /** The ground as worldgen makes it: {@code floor} is the height a walker stands at on the solid ground (under water too),
     * {@code surface} the height above any water there (equal to the floor on dry land). */
    public interface Terrain {
        int floor(int x, int z);
        int surface(int x, int z);
        /** Both at once ({floor, surface}): a terrain that works out a whole column overrides this to do it once. */
        default int[] both(int x, int z) { return new int[] { floor(x, z), surface(x, z) }; }
    }

    /** The grade (height per block along the road), how deep a cut becomes a tunnel and how high a fill a bridge, how much bridge
     * and tunnel a road may have and how long it may be at most, and how many lines are tried. */
    public record Rules(double grade, int tunnelCut, int bridgeFill, int maxBridge, int maxTunnel, int maxLength, int variants) {
        public static Rules defaults() { return new Rules(0.33, 10, 9, 240, 200, 900, 6); }
    }

    /** One step of the centreline, a block apart: where (block coordinates, fractional), which way it runs (unit vector), the height
     * the walker's feet are at in half blocks (an odd value is a slab), and what the road is there. */
    public record Point(double x, double z, double dx, double dz, int h2, Kind kind) {
        public int blockX() { return (int) Math.floor(x); }
        public int blockZ() { return (int) Math.floor(z); }
    }

    /** A planned road: its centreline, how many of its points are bridge and tunnel, its cost (lower is better). */
    public record Road(List<Point> points, int bridge, int tunnel, double cost) {
        public int length() { return points.size(); }
    }

    /** The plan's answer: the road, or why there is none (a message for the log and the command). */
    public record Result(Road road, String refusal) {
        public boolean ok() { return road != null; }
    }

    /** How far apart the bend points are along the line, the most a bend pushes sideways, and how often the ground is sampled. */
    static final double BEND_SPACING = 80, BEND_MAX = 14, BEND_SHARE = 0.035;
    static final int SAMPLE_STEP = 2, SMOOTH_HALF = 6, MIN_SPECIAL_RUN = 4, MERGE_GAP = 6;
    /** A bridge's walkway stands this high over the water: the deck one block clear of it. */
    static final int DECK_OVER_WATER = 2;
    /** A dip this deep or deeper below both its rims, the rims within this many points on either side, is crossed at the rims'
     * level (a bridge or a causeway) instead of down and up again (Dmitriy 2026-10-25: the road sagged into every ravine). */
    static final int VALLEY_DEPTH = 4, VALLEY_REACH = 60;
    /** How far back from a tunnel's mouth the road's approach is evened out: it does not climb the hill only to go into it. */
    static final int TUNNEL_APPROACH = 48;
    /** What a half-block step up or down costs against a block of bridge (1.5) or tunnel (2): a road over a hill is not cheaper
     * than one through it just for being in the open. */
    static final double CLIMB_COST = 1.0;

    /** Water this deep is bridged (shallower is a causeway on fill). */
    private static boolean bridged(double depth) { return depth >= 1; }

    /** Plans the road from A to B: the cheapest of {@code rules.variants} lines that keeps within the rules. */
    public static Result plan(long seed, int ax, int az, int bx, int bz, Terrain terrain, Rules rules) {
        double length = Math.hypot(bx - ax, bz - az);
        if (length < 16) return new Result(null, "деревни слишком близко (" + Math.round(length) + " блоков)");
        if (length > rules.maxLength()) return new Result(null, "дальше " + rules.maxLength() + " блоков (" + Math.round(length) + ")");
        Terrain cached = cache(terrain);
        Road best = null; String refusal = null;
        for (int variant = 0; variant < Math.max(1, rules.variants()); variant++) {
            List<double[]> line = centreline(seed, variant, rules.variants(), ax, az, bx, bz);
            Road road = profile(line, cached, rules, true);
            String why = refusal(road, rules);
            if (why != null && road.tunnel() > rules.maxTunnel()) { // the evened approach made the tunnel too long: climb as before
                road = profile(line, cached, rules, false);
                why = refusal(road, rules);
            }
            if (why != null) { if (refusal == null) refusal = why; continue; }
            if (best == null || road.cost() < best.cost()) best = road;
        }
        return best != null ? new Result(best, null) : new Result(null, refusal);
    }

    static String refusal(Road road, Rules rules) {
        if (road.bridge() > rules.maxBridge()) return "мост длиннее " + rules.maxBridge() + " блоков (" + road.bridge() + ")";
        if (road.tunnel() > rules.maxTunnel()) return "тоннель длиннее " + rules.maxTunnel() + " блоков (" + road.tunnel() + ")";
        return null;
    }

    // ---- The line ----

    /** The centreline of one variant, points a block apart from A to B. Variant 0 is the straight line; the others bend more the
     * higher their number, by a seeded random push at each bend point. */
    static List<double[]> centreline(long seed, int variant, int variants, int ax, int az, int bx, int bz) {
        double length = Math.hypot(bx - ax, bz - az);
        double ux = (bx - ax) / length, uz = (bz - az) / length, nx = -uz, nz = ux;
        int bends = Math.max(1, (int) Math.round(length / BEND_SPACING));
        double amplitude = variant == 0 ? 0 : Math.min(BEND_MAX, length * BEND_SHARE) * variant / Math.max(1, variants - 1);
        Random random = new Random(seed * 31 + variant * 0x9E3779B97F4A7C15L);
        List<double[]> control = new ArrayList<>();
        for (int j = 0; j <= bends; j++) {
            double t = (double) j / bends;
            double push = j == 0 || j == bends ? 0 : (random.nextDouble() * 2 - 1) * amplitude;
            control.add(new double[] { ax + 0.5 + (bx - ax) * t + nx * push, az + 0.5 + (bz - az) * t + nz * push });
        }
        List<double[]> dense = new ArrayList<>();
        for (int j = 0; j < bends; j++) {
            double[] p0 = control.get(Math.max(0, j - 1)), p1 = control.get(j), p2 = control.get(j + 1), p3 = control.get(Math.min(bends, j + 2));
            int steps = 64;
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                dense.add(catmullRom(p0, p1, p2, p3, t));
            }
        }
        dense.add(control.get(bends));
        return resample(dense, 1.0);
    }

    private static double[] catmullRom(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        double[] out = new double[2];
        for (int k = 0; k < 2; k++)
            out[k] = 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2 + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
        return out;
    }

    /** The polyline walked at an even step; the last point is the polyline's end. */
    static List<double[]> resample(List<double[]> line, double step) {
        List<double[]> out = new ArrayList<>();
        out.add(line.get(0));
        double carried = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            double segment = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (segment == 0) continue;
            double at = step - carried;
            while (at <= segment) {
                double t = at / segment;
                out.add(new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t });
                at += step;
            }
            carried = segment - (at - step);
        }
        double[] end = line.get(line.size() - 1), last = out.get(out.size() - 1);
        if (Math.hypot(end[0] - last[0], end[1] - last[1]) > step * 0.3) out.add(end); else out.set(out.size() - 1, end);
        return out;
    }

    // ---- The height ----

    /** The road's height along the line and what it is at each point. */
    static Road profile(List<double[]> line, Terrain terrain, Rules rules) { return profile(line, terrain, rules, true); }

    static Road profile(List<double[]> line, Terrain terrain, Rules rules, boolean evenTunnels) {
        int n = line.size();
        double[] floor = new double[n], surface = new double[n];
        for (int i = 0; i < n; i += SAMPLE_STEP) sample(line, i, terrain, floor, surface);
        if ((n - 1) % SAMPLE_STEP != 0) sample(line, n - 1, terrain, floor, surface);
        for (int i = 0; i < n; i++) {
            if (i % SAMPLE_STEP == 0 || i == n - 1) continue;
            int a = i - i % SAMPLE_STEP, b = Math.min(n - 1, a + SAMPLE_STEP);
            double t = (double) (i - a) / (b - a);
            floor[i] = floor[a] + (floor[b] - floor[a]) * t; surface[i] = surface[a] + (surface[b] - surface[a]) * t;
        }
        boolean[] wet = new boolean[n];
        double[] base = new double[n];
        for (int i = 0; i < n; i++) { wet[i] = surface[i] - floor[i] >= 0.5; base[i] = wet[i] ? surface[i] + DECK_OVER_WATER : floor[i]; }
        double g = rules.grade();
        double[] road = heights(base, g);
        if (evenTunnels) {
            double[] evened = evenTunnelBase(base, road, floor, rules.tunnelCut(), TUNNEL_APPROACH);
            if (evened != null) road = heights(evened, g);
        }
        int[] h2 = new int[n];
        for (int i = 0; i < n; i++) {
            h2[i] = (int) Math.round(road[i] * 2);
            if (wet[i] && bridged(surface[i] - floor[i])) h2[i] = Math.max(h2[i], (int) Math.round((surface[i] + DECK_OVER_WATER) * 2));
        }
        for (int i = 1; i < n; i++) h2[i] = Math.max(h2[i], h2[i - 1] - 1);
        for (int i = n - 2; i >= 0; i--) h2[i] = Math.max(h2[i], h2[i + 1] - 1);
        Kind[] kinds = new Kind[n];
        for (int i = 0; i < n; i++) {
            double feet = h2[i] / 2.0, cut = floor[i] - feet, fill = feet - floor[i];
            if (cut >= rules.tunnelCut()) kinds[i] = Kind.TUNNEL;
            else if (wet[i] && (surface[i] - floor[i] >= 2 || fill >= 3) || fill >= rules.bridgeFill()) kinds[i] = Kind.BRIDGE;
            else kinds[i] = Kind.GROUND;
        }
        tidy(kinds, wet, floor, surface);
        List<Point> points = new ArrayList<>(n);
        int bridge = 0, tunnel = 0; double cost = 0;
        for (int i = 0; i < n; i++) {
            double[] here = line.get(i), ahead = line.get(Math.min(n - 1, i + 1)), behind = line.get(Math.max(0, i - 1));
            double dx = ahead[0] - behind[0], dz = ahead[1] - behind[1], d = Math.hypot(dx, dz);
            if (d == 0) { dx = 1; dz = 0; d = 1; }
            points.add(new Point(here[0], here[1], dx / d, dz / d, h2[i], kinds[i]));
            double feet = h2[i] / 2.0;
            if (i > 0) cost += CLIMB_COST * Math.abs(h2[i] - h2[i - 1]); // every half-block step up or down
            switch (kinds[i]) {
                case BRIDGE -> { bridge++; cost += 1.5; }
                case TUNNEL -> { tunnel++; cost += 2.0; }
                case GROUND -> cost += 0.1 + 0.08 * Math.abs(feet - floor[i]);
            }
        }
        return new Road(List.copyOf(points), bridge, tunnel, cost);
    }

    /** The road's height from the ground it follows ({@code base}): smoothed, held within reach of both ends, levelled across
     * ravines, and kept to the grade. */
    static double[] heights(double[] base, double g) {
        int n = base.length;
        double[] road = smooth(base, SMOOTH_HALF);
        // Both ends stand on the ground at their villages: the climb to a village on a plateau spreads back down the road rather
        // than the road arriving under the village in a tunnel. Each point stays within reach of both ends at the grade (when the
        // ends are too far apart in height for that, the road keeps to the ground as best it can instead).
        double a = base[0], b = base[n - 1];
        boolean pinned = Math.abs(a - b) <= g * (n - 1);
        if (pinned) for (int i = 0; i < n; i++)
            road[i] = clamp(road[i], Math.max(a - g * i, b - g * (n - 1 - i)), Math.min(a + g * i, b + g * (n - 1 - i)));
        levelValleys(road, VALLEY_DEPTH, VALLEY_REACH);
        for (int i = 1; i < n; i++) road[i] = clamp(road[i], road[i - 1] - g, road[i - 1] + g);
        for (int i = n - 2; i >= 0; i--) road[i] = clamp(road[i], road[i + 1] - g, road[i + 1] + g);
        return road;
    }

    /** Raises every dip at least {@code depth} below both of its rims (the highest road within {@code reach} points before and
     * after) to the lower rim, and the slopes down into it with it: the road crosses the ravine level, on a bridge where the fill is
     * high and on a causeway where it is low, instead of following the ground down and up. */
    static void levelValleys(double[] road, int depth, int reach) {
        int n = road.length;
        double[] rim = new double[n];
        for (int i = 0; i < n; i++) {
            double left = road[i], right = road[i];
            for (int k = Math.max(0, i - reach); k < i; k++) left = Math.max(left, road[k]);
            for (int k = i + 1; k <= Math.min(n - 1, i + reach); k++) right = Math.max(right, road[k]);
            rim[i] = Math.min(left, right);
        }
        boolean[] raise = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (rim[i] - road[i] < depth || raise[i]) continue;
            int a = i, b = i;
            while (a > 0 && rim[a - 1] - road[a - 1] > 0.25) a--;
            while (b < n - 1 && rim[b + 1] - road[b + 1] > 0.25) b++;
            for (int k = a; k <= b; k++) raise[k] = true;
        }
        for (int i = 0; i < n; i++) if (raise[i]) road[i] = rim[i];
    }

    /** The ground to follow again when a tunnel is coming (Dmitriy 2026-10-25: the road climbed the hill and then went into it
     * anyway): from the lowest road within {@code window} before a tunnel's mouth, the road keeps that level under the whole hill,
     * to where the ground comes down near that level again. Null when the road has no tunnel. The ends are never changed. */
    static double[] evenTunnelBase(double[] base, double[] road, double[] floor, int cut, int window) {
        int n = road.length;
        double[] out = null;
        for (int i = 1; i < n - 1; ) {
            if (floor[i] - road[i] < cut) { i++; continue; }
            int a = i;
            for (int k = Math.max(0, i - window); k < i; k++) if (road[k] < road[a]) a = k;
            double level = road[a];
            int b = i;
            while (b < n - 2 && floor[b + 1] - level > VALLEY_DEPTH) b++;
            if (out == null) out = base.clone();
            for (int k = Math.max(1, a); k <= b; k++) out[k] = Math.min(out[k], level);
            i = b + 1;
        }
        return out;
    }

    private static void sample(List<double[]> line, int i, Terrain terrain, double[] floor, double[] surface) {
        double[] p = line.get(i);
        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
        floor[i] = terrain.floor(x, z);
        surface[i] = Math.max(floor[i], terrain.surface(x, z));
    }

    /** A short bridge or tunnel over no deep water becomes plain road; a short stretch of plain road between two bridges (or two
     * tunnels) joins them. */
    static void tidy(Kind[] kinds, boolean[] wet, double[] floor, double[] surface) {
        int n = kinds.length;
        for (int i = 0; i < n; ) {
            int j = i; while (j < n && kinds[j] == kinds[i]) j++;
            if (kinds[i] != Kind.GROUND && j - i < MIN_SPECIAL_RUN) {
                boolean deep = false;
                for (int k = i; k < j; k++) deep |= wet[k] && surface[k] - floor[k] >= 2;
                if (!(kinds[i] == Kind.BRIDGE && deep)) for (int k = i; k < j; k++) kinds[k] = Kind.GROUND;
            }
            i = j;
        }
        for (int i = 0; i < n; ) {
            int j = i; while (j < n && kinds[j] == kinds[i]) j++;
            if (kinds[i] == Kind.GROUND && i > 0 && j < n && j - i <= MERGE_GAP && kinds[i - 1] == kinds[j])
                for (int k = i; k < j; k++) kinds[k] = kinds[j];
            i = j;
        }
    }

    static double[] smooth(double[] values, int half) {
        int n = values.length;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + values[i];
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - half), b = Math.min(n - 1, i + half);
            out[i] = (prefix[b + 1] - prefix[a]) / (b - a + 1);
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }

    /** The terrain with each column asked once (the worldgen height is costly). */
    static Terrain cache(Terrain terrain) {
        Map<Long, int[]> seen = new HashMap<>();
        return new Terrain() {
            private int[] at(int x, int z) {
                return seen.computeIfAbsent(((long) x << 32) ^ (z & 0xFFFFFFFFL), k -> terrain.both(x, z));
            }
            @Override public int floor(int x, int z) { return at(x, z)[0]; }
            @Override public int surface(int x, int z) { return at(x, z)[1]; }
        };
    }

    // ---- The network ----

    /** Which pairs of villages get a road: those no third village sits between (the relative neighbourhood graph: no C nearer to
     * both A and B than they are to each other), within {@code maxLength}. Indices into {@code xs}/{@code zs}, each pair once. */
    public static List<int[]> network(int[] xs, int[] zs, int maxLength) {
        List<int[]> out = new ArrayList<>();
        int n = xs.length;
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++) {
                double ab = dist(xs, zs, a, b);
                if (ab > maxLength) continue;
                boolean blocked = false;
                for (int c = 0; c < n && !blocked; c++) {
                    if (c == a || c == b) continue;
                    blocked = Math.max(dist(xs, zs, a, c), dist(xs, zs, b, c)) < ab;
                }
                if (!blocked) out.add(new int[] { a, b });
            }
        return out;
    }

    private static double dist(int[] xs, int[] zs, int a, int b) { return Math.hypot(xs[a] - xs[b], zs[a] - zs[b]); }

    // ---- Along the road ----

    /** Where the lamps go: every {@code spacing} points of plain road, the side swapping each time (+1 left, -1 right); on a bridge
     * twice as often on both sides; in a tunnel a hanging one under the crown. */
    public static int lampSide(Point point, int index, int spacing) {
        if (spacing <= 0) return 0;
        return switch (point.kind()) {
            case GROUND -> index % spacing == 0 ? ((index / spacing) % 2 == 0 ? 1 : -1) : 0;
            case BRIDGE -> index % Math.max(4, spacing / 2) == 0 ? 2 : 0;
            case TUNNEL -> index % Math.max(4, spacing / 2) == 0 ? 3 : 0;
        };
    }
}
