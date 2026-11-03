package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RoadPlanTest {
    private static RoadPlan.Terrain flat(int y) {
        return new RoadPlan.Terrain() {
            @Override public int floor(int x, int z) { return y; }
            @Override public int surface(int x, int z) { return y; }
        };
    }

    private static long count(RoadPlan.Road road, RoadPlan.Kind kind) { return road.points().stream().filter(p -> p.kind() == kind).count(); }

    @Test void flatGroundIsAPlainRoadFromEndToEnd() {
        RoadPlan.Result result = RoadPlan.plan(1, 0, 0, 400, 100, flat(70), RoadPlan.Rules.defaults());
        assertTrue(result.ok(), result.refusal());
        RoadPlan.Road road = result.road();
        assertEquals(0, road.bridge()); assertEquals(0, road.tunnel());
        assertTrue(road.points().stream().allMatch(p -> p.h2() == 140));
        RoadPlan.Point first = road.points().get(0), last = road.points().get(road.length() - 1);
        assertEquals(0, first.blockX()); assertEquals(0, first.blockZ());
        assertEquals(400, last.blockX()); assertEquals(100, last.blockZ());
        for (int i = 1; i < road.length() - 1; i++) { // the last step may be short: it ends on B
            RoadPlan.Point a = road.points().get(i - 1), b = road.points().get(i);
            assertEquals(1.0, Math.hypot(b.x() - a.x(), b.z() - a.z()), 0.35, "points a block apart at " + i);
        }
    }

    @Test void bendsStaySlight() {
        for (int variant = 0; variant < 6; variant++) {
            List<double[]> line = RoadPlan.centreline(7, variant, 6, 0, 0, 600, 0);
            double worst = line.stream().mapToDouble(p -> Math.abs(p[1] - 0.5)).max().orElse(0);
            assertTrue(worst <= RoadPlan.BEND_MAX + 2, "variant " + variant + " strays " + worst);
            if (variant == 0) assertTrue(worst < 0.01, "variant 0 is straight");
        }
    }

    @Test void aSmallHillIsCutNotTunnelled() {
        RoadPlan.Terrain hill = new RoadPlan.Terrain() {
            @Override public int floor(int x, int z) { return 70 + (int) Math.max(0, 5 - Math.abs(x - 150) / 6.0); }
            @Override public int surface(int x, int z) { return floor(x, z); }
        };
        RoadPlan.Road road = RoadPlan.plan(2, 0, 0, 300, 0, hill, RoadPlan.Rules.defaults()).road();
        assertNotNull(road);
        assertEquals(0, road.tunnel()); assertEquals(0, road.bridge());
    }

    @Test void aMountainGetsATunnelAndARiverABridge() {
        RoadPlan.Terrain land = new RoadPlan.Terrain() {
            @Override public int floor(int x, int z) {
                if (x >= 140 && x <= 220) return 70 + 40; // a sheer ridge, forty high and eighty wide
                if (x >= 300 && x <= 330) return 55; // a river bed
                return 70;
            }
            @Override public int surface(int x, int z) { return x >= 300 && x <= 330 ? 63 : floor(x, z); }
        };
        RoadPlan.Road road = RoadPlan.plan(3, 0, 0, 450, 0, land, RoadPlan.Rules.defaults()).road();
        assertNotNull(road);
        assertTrue(road.tunnel() > 40, "tunnel " + road.tunnel());
        assertTrue(road.bridge() >= 25, "bridge " + road.bridge());
        for (RoadPlan.Point p : road.points()) {
            if (p.x() >= 300 && p.x() <= 330) assertTrue(p.h2() >= 128, "deck above the water at " + p.x());
        }
        for (int i = 1; i < road.length(); i++)
            assertTrue(Math.abs(road.points().get(i).h2() - road.points().get(i - 1).h2()) <= 1, "no step over half a block at " + i);
    }

    @Test void aVillageOnAPlateauIsReachedAtItsOwnLevel() {
        RoadPlan.Terrain land = new RoadPlan.Terrain() { // a valley floor at 70, then a cliff up to a plateau at 110 forty blocks before B
            @Override public int floor(int x, int z) { return x >= 560 ? 110 : 70; }
            @Override public int surface(int x, int z) { return floor(x, z); }
        };
        RoadPlan.Road road = RoadPlan.plan(4, 0, 0, 600, 0, land, RoadPlan.Rules.defaults()).road();
        assertNotNull(road);
        RoadPlan.Point last = road.points().get(road.length() - 1);
        assertTrue(last.h2() >= 218, "arrives at the plateau, not under it: " + last.h2() / 2.0);
        assertEquals(140, road.points().get(0).h2());
        for (RoadPlan.Point p : road.points()) if (p.x() >= 575) assertNotEquals(RoadPlan.Kind.TUNNEL, p.kind(), "no tunnel under the village at " + p.x());
    }

    @Test void tooLongOrTooMuchBridgeIsRefused() {
        assertFalse(RoadPlan.plan(1, 0, 0, 2000, 0, flat(70), RoadPlan.Rules.defaults()).ok());
        RoadPlan.Terrain sea = new RoadPlan.Terrain() {
            @Override public int floor(int x, int z) { return x > 50 && x < 500 ? 40 : 70; }
            @Override public int surface(int x, int z) { return x > 50 && x < 500 ? 63 : 70; }
        };
        RoadPlan.Result result = RoadPlan.plan(1, 0, 0, 550, 0, sea, RoadPlan.Rules.defaults());
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("мост"), result.refusal());
    }

    @Test void theNetworkSkipsAVillageBehindAnother() {
        int[] xs = { 0, 300, 600, 300 }, zs = { 0, 0, 0, 2000 };
        List<int[]> edges = RoadPlan.network(xs, zs, 900);
        assertEquals(2, edges.size(), "A-B and B-C, not A-C, and the far one alone");
        assertTrue(edges.stream().anyMatch(e -> e[0] == 0 && e[1] == 1));
        assertTrue(edges.stream().anyMatch(e -> e[0] == 1 && e[1] == 2));
    }

    @Test void aRavineIsCrossedAtItsRimsNotDownIntoIt() {
        RoadPlan.Terrain land = new RoadPlan.Terrain() { // a ravine twenty wide and twelve deep in flat land at 70
            @Override public int floor(int x, int z) { return x >= 190 && x <= 210 ? 58 : 70; }
            @Override public int surface(int x, int z) { return floor(x, z); }
        };
        RoadPlan.Road road = RoadPlan.plan(5, 0, 0, 400, 0, land, RoadPlan.Rules.defaults()).road();
        assertNotNull(road);
        for (RoadPlan.Point p : road.points())
            if (p.x() >= 185 && p.x() <= 215) assertTrue(p.h2() >= 136, "keeps near the rims over the ravine at " + p.x() + ": " + p.h2() / 2.0);
        assertTrue(road.bridge() >= 15, "a bridge over the ravine: " + road.bridge());
    }

    @Test void noClimbBeforeATunnel() {
        RoadPlan.Terrain land = new RoadPlan.Terrain() { // a hill rising gently from 70 to a ridge of 120, thirty blocks across the top
            @Override public int floor(int x, int z) { return 70 + (int) Math.max(0, Math.min(50, 50 - (Math.abs(x - 250) - 15) * 0.6)); }
            @Override public int surface(int x, int z) { return floor(x, z); }
        };
        RoadPlan.Road road = RoadPlan.plan(6, 0, 0, 500, 0, land, RoadPlan.Rules.defaults()).road();
        assertNotNull(road);
        assertTrue(road.tunnel() > 0, "a tunnel through the ridge");
        int highest = road.points().stream().mapToInt(RoadPlan.Point::h2).max().orElse(0);
        assertTrue(highest <= 150, "the road stays low and goes in at the foot: highest " + highest / 2.0);
    }

    @Test void aBranchMeetsTheHighwayAtItsHeight() {
        // The highway runs on a bank three blocks over the ground where the branch joins it.
        RoadPlan.Result result = RoadPlan.plan(5, 0, 0, 0, 120, flat(70), RoadPlan.Rules.defaults(), 73);
        assertTrue(result.ok(), result.refusal());
        List<RoadPlan.Point> points = result.road().points();
        RoadPlan.Point last = points.get(points.size() - 1);
        assertEquals(146, last.h2());
        for (RoadPlan.Point p : points) {
            if (Math.hypot(p.x() - last.x(), p.z() - last.z()) <= 5) { assertEquals(146, p.h2()); assertEquals(RoadPlan.Kind.GROUND, p.kind()); }
        }
        for (int i = 1; i < points.size(); i++) assertTrue(Math.abs(points.get(i).h2() - points.get(i - 1).h2()) <= 1, "smooth at " + i);
        assertEquals(140, points.get(0).h2(), "the village end stays on the ground");
    }

    @Test void aJunctionKeepsToPlainRoadAwayFromTheEnds() {
        List<RoadPlan.Point> highway = RoadPlan.plan(1, 0, 0, 400, 0, flat(70), RoadPlan.Rules.defaults()).road().points();
        int at = RoadPlan.junction(highway, 200, 150, 0, highway.size() - 1, 6);
        assertEquals(200, highway.get(at).blockX(), 1);
        assertEquals(10, RoadPlan.junction(highway, -100, 0, 10, highway.size() - 1, 6));
    }
}
