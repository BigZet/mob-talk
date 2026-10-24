package io.github.mobtalk.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The watchtower's plan: a villager can walk in at the door and up the steps to the platform without bumping its head. */
class TowerPlanTest {
    private static Map<String, TowerPlan.Block> finalState(List<TowerPlan.Block> plan) {
        Map<String, TowerPlan.Block> state = new HashMap<>();
        for (TowerPlan.Block block : plan) state.put(block.x() + "," + block.y() + "," + block.z(), block);
        return state;
    }
    private static TowerPlan.Kind at(Map<String, TowerPlan.Block> state, int x, int y, int z) {
        TowerPlan.Block block = state.get(x + "," + y + "," + z);
        return block == null ? TowerPlan.Kind.AIR : block.kind();
    }

    @Test void theStairClimbsToThePlatformWithHeadroom() {
        Map<String, TowerPlan.Block> state = finalState(TowerPlan.plan());
        List<TowerPlan.Block> steps = state.values().stream().filter(b -> b.kind() == TowerPlan.Kind.STAIR)
                .sorted(Comparator.comparingInt(TowerPlan.Block::y)).toList();
        assertEquals(TowerPlan.PLATFORM, steps.size());
        int px = 0, pz = -TowerPlan.RADIUS, py = 0; // standing in the doorway on the floor
        for (TowerPlan.Block step : steps) {
            assertEquals(py + 1, step.y(), "each step one higher");
            assertEquals(1, Math.abs(step.x() - px) + Math.abs(step.z() - pz), "each step next to the last: " + step);
            int dx = step.x() - px, dz = step.z() - pz;
            TowerPlan.Facing way = dx > 0 ? TowerPlan.Facing.EAST : dx < 0 ? TowerPlan.Facing.WEST : dz > 0 ? TowerPlan.Facing.SOUTH : TowerPlan.Facing.NORTH;
            assertEquals(way, step.facing(), "a step faces the way up");
            for (int head = step.y() + 1; head <= step.y() + 2; head++)
                assertEquals(TowerPlan.Kind.AIR, at(state, step.x(), head, step.z()), "headroom over " + step + " at y=" + head);
            px = step.x(); pz = step.z(); py = step.y();
        }
        assertEquals(TowerPlan.PLATFORM, py, "the last step is level with the platform");
    }

    @Test void theDoorLeadsInAndThePostStandsOnThePlatform() {
        Map<String, TowerPlan.Block> state = finalState(TowerPlan.plan());
        assertEquals(TowerPlan.Kind.DOOR_LOWER, at(state, 0, 1, -2));
        assertEquals(TowerPlan.Kind.DOOR_UPPER, at(state, 0, 2, -2));
        assertEquals(TowerPlan.Kind.AIR, at(state, 0, 1, -3), "the way in is clear");
        assertEquals(TowerPlan.Kind.FLOOR, at(state, 0, 0, -3), "and has ground");
        assertEquals(TowerPlan.Kind.POST_BLOCK, at(state, 0, TowerPlan.PLATFORM + 1, 0));
        assertEquals(TowerPlan.Kind.POST, at(state, 0, TowerPlan.PLATFORM, 0), "the post stands on the newel");
        assertEquals(TowerPlan.Kind.LANTERN, at(state, 0, TowerPlan.ROOF - 1, 0));
        assertEquals(TowerPlan.Kind.ROOF, at(state, 0, TowerPlan.ROOF, 0), "the lantern hangs from the roof");
        // An archer can stand beside the post: platform under, air at feet and head.
        assertEquals(TowerPlan.Kind.WALL, at(state, -1, TowerPlan.PLATFORM, 0));
        assertEquals(TowerPlan.Kind.AIR, at(state, -1, TowerPlan.PLATFORM + 1, 0));
        assertEquals(TowerPlan.Kind.AIR, at(state, -1, TowerPlan.PLATFORM + 2, 0));
    }

    @Test void airComesBeforeTheBlocksItClears() {
        List<TowerPlan.Block> plan = TowerPlan.plan();
        int lastAir = -1, firstSolid = Integer.MAX_VALUE;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).kind() == TowerPlan.Kind.AIR) lastAir = i;
            else firstSolid = Math.min(firstSolid, i);
        }
        assertTrue(lastAir < firstSolid, "the builder clears first, then builds");
    }

    @Test void turningKeepsTheTowerAndMovesTheDoor() {
        for (TowerPlan.Facing door : TowerPlan.Facing.values()) {
            Map<String, TowerPlan.Block> state = finalState(TowerPlan.rotated(door));
            assertEquals(finalState(TowerPlan.plan()).size(), state.size());
            int[] spot = switch (door) { case NORTH -> new int[] {0, -2}; case EAST -> new int[] {2, 0}; case SOUTH -> new int[] {0, 2}; case WEST -> new int[] {-2, 0}; };
            assertEquals(TowerPlan.Kind.DOOR_LOWER, at(state, spot[0], 1, spot[1]), "door on the " + door + " side");
        }
    }

    @Test void stylesFollowTheBiome() {
        assertEquals(TowerPlan.Style.SANDSTONE, TowerPlan.Style.forBiome("minecraft:desert"));
        assertEquals(TowerPlan.Style.ACACIA, TowerPlan.Style.forBiome("minecraft:savanna_plateau"));
        assertEquals(TowerPlan.Style.SPRUCE, TowerPlan.Style.forBiome("minecraft:snowy_plains"));
        assertEquals(TowerPlan.Style.SPRUCE, TowerPlan.Style.forBiome("minecraft:taiga"));
        assertEquals(TowerPlan.Style.JUNGLE, TowerPlan.Style.forBiome("minecraft:sparse_jungle"));
        assertEquals(TowerPlan.Style.OAK, TowerPlan.Style.forBiome("minecraft:plains"));
        assertEquals(TowerPlan.Style.OAK, TowerPlan.Style.forBiome(null));
    }
}
