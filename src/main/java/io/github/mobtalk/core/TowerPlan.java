package io.github.mobtalk.core;

import java.util.*;

/** The archers' watchtower (design §4): 5×5 at the base, a winding stair of stair blocks inside (villagers do not climb ladders), a
 * platform at {@link #PLATFORM} with a railing, a roof on four posts, a lantern and the archer's post in the middle. Pure Java: the
 * plan is a list of block kinds at local positions in build order, with the door on the north side (−z); {@link #rotated} turns it
 * so the door faces the village. The game maps each {@link Kind} to a block of the tower's style.
 *
 * <p>Local frame: x east, z south, y up; (0, 0, 0) is the floor block under the tower's centre, the floor is y = 0, the ground
 * floor's air starts at y = 1. Foundation columns below y = 0 are the game's business (it fills down to the ground). */
public final class TowerPlan {
    private TowerPlan() { }

    /** The platform's floor level (blocks at y = 6, standing height 7); the railing above it, the roof at {@link #ROOF}. */
    public static final int PLATFORM = 6, ROOF = 10, RADIUS = 2, LARGE_RADIUS = 3;
    /** Half the side of a tower: 5×5 for a small one, 7×7 for a large one (two archers' and two crossbowmen's posts). */
    public static int radius(boolean large) { return large ? LARGE_RADIUS : RADIUS; }
    /** The full height the tower takes, floor to roof, for the site check. */
    public static final int HEIGHT = ROOF + 1;

    public enum Kind {
        /** Air the builder clears (inside the walls, above the steps, the door's way in). */
        AIR,
        /** The foundation course and the ground floor. */
        FLOOR,
        /** Corner posts and the central newel. */
        POST,
        /** Walls and the platform floor. */
        WALL,
        /** A step: {@link Block#facing} is the way up. */
        STAIR,
        /** The door: lower and upper half, {@link Block#facing} is the way in. */
        DOOR_LOWER, DOOR_UPPER,
        /** A slit in the wall (a glass pane or a bar). */
        WINDOW,
        /** The railing round the platform. */
        RAIL,
        /** The roof: slabs on top. */
        ROOF,
        /** The lantern under the roof. */
        LANTERN,
        /** The archer's post, the job site of the archer. */
        POST_BLOCK,
        /** The crossbowman's post on a large tower. */
        CROSSBOW_POST
    }

    public enum Facing { NORTH, EAST, SOUTH, WEST;
        /** One quarter turn clockwise, {@code turns} times. */
        public Facing rotate(int turns) { return values()[Math.floorMod(ordinal() + turns, 4)]; }
        public Facing opposite() { return rotate(2); }
    }

    /** One block of the plan. {@code facing} is null for blocks that have no direction. */
    public record Block(int x, int y, int z, Kind kind, Facing facing) {
        Block rotated(int turns) {
            int nx = x, nz = z;
            for (int i = 0; i < Math.floorMod(turns, 4); i++) { int t = nx; nx = -nz; nz = t; }
            return new Block(nx, y, nz, kind, facing == null ? null : facing.rotate(turns));
        }
    }

    /** The ring of the 3×3 inside, clockwise from the cell behind the door; the stair climbs it one step per cell. */
    static final int[][] RING = { {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1} };
    /** Steps: in ring cells 1–6 at heights 1–6; the last one reaches the platform. Cell 0, behind the door, is a landing at floor
     * level: a step right behind the door could not be climbed, the lintel over the doorway is in the way of the head. */
    static final int STEPS = PLATFORM;

    /** The plan with the door on the north side, in build order: floor, walls course by course with the steps, the platform,
     * the railing, the roof posts, the roof, the lantern, the post. Air comes first, so a builder clears before it builds. */
    public static List<Block> plan() { return plan(false); }

    /** The plan of a small (5×5) or large (7×7) tower: the same stair round the newel in the middle; a large one has a room round it
     * and on its platform two archers' posts (front and back) and two crossbowmen's (the sides) instead of one post in the middle. */
    public static List<Block> plan(boolean large) {
        final int r = radius(large);
        List<Block> out = new ArrayList<>();
        // Clear the inside and the space above the tower, and the way in at the door.
        for (int y = 1; y <= ROOF; y++)
            for (int x = -r; x <= r; x++)
                for (int z = -r; z <= r; z++) out.add(new Block(x, y, z, Kind.AIR, null));
        out.add(new Block(0, 1, -r - 1, Kind.AIR, null));
        out.add(new Block(0, 2, -r - 1, Kind.AIR, null));
        // Floor.
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) out.add(new Block(x, 0, z, Kind.FLOOR, null));
        out.add(new Block(0, 0, -r - 1, Kind.FLOOR, null));
        Set<Long> holes = platformHoles();
        for (int y = 1; y < PLATFORM; y++) {
            for (int x = -r; x <= r; x++)
                for (int z = -r; z <= r; z++) {
                    boolean edge = Math.abs(x) == r || Math.abs(z) == r;
                    if (!edge) continue;
                    boolean corner = Math.abs(x) == r && Math.abs(z) == r;
                    if (corner) { out.add(new Block(x, y, z, Kind.POST, null)); continue; }
                    if (x == 0 && z == -r && y <= 2) {
                        out.add(new Block(x, y, z, y == 1 ? Kind.DOOR_LOWER : Kind.DOOR_UPPER, Facing.SOUTH));
                        continue;
                    }
                    boolean slit = y == 3 && (x == 0 || z == 0) && !(x == 0 && z == -r);
                    out.add(new Block(x, y, z, slit ? Kind.WINDOW : Kind.WALL, null));
                }
            out.add(new Block(0, y, 0, Kind.POST, null)); // The newel the stair winds round.
            if (y <= STEPS) {
                int[] cell = RING[y];
                out.add(new Block(cell[0], y, cell[1], Kind.STAIR, climb(y - 1)));
            }
        }
        // The last step sits at the platform's height and is part of it.
        int[] last = RING[STEPS];
        out.add(new Block(last[0], PLATFORM, last[1], Kind.STAIR, climb(STEPS - 1)));
        // The platform: the whole 5×5 but the stairwell above the top steps and the last step itself.
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                if (holes.contains(key(x, z)) || x == last[0] && z == last[1]) continue;
                boolean corner = Math.abs(x) == r && Math.abs(z) == r;
                out.add(new Block(x, PLATFORM, z, corner || x == 0 && z == 0 ? Kind.POST : Kind.WALL, null));
            }
        // Railing round the edge, the roof posts at the corners.
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                boolean edge = Math.abs(x) == r || Math.abs(z) == r;
                boolean corner = Math.abs(x) == r && Math.abs(z) == r;
                if (corner) for (int y = PLATFORM + 1; y < ROOF; y++) out.add(new Block(x, y, z, Kind.POST, null));
                else if (edge) out.add(new Block(x, PLATFORM + 1, z, Kind.RAIL, null));
            }
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) out.add(new Block(x, ROOF, z, Kind.ROOF, null));
        out.add(new Block(0, ROOF - 1, 0, Kind.LANTERN, null));
        if (!large) out.add(new Block(0, PLATFORM + 1, 0, Kind.POST_BLOCK, null));
        else {
            out.add(new Block(0, PLATFORM + 1, -2, Kind.POST_BLOCK, null));
            out.add(new Block(0, PLATFORM + 1, 2, Kind.POST_BLOCK, null));
            out.add(new Block(-2, PLATFORM + 1, 0, Kind.CROSSBOW_POST, null));
            out.add(new Block(2, PLATFORM + 1, 0, Kind.CROSSBOW_POST, null));
        }
        return List.copyOf(out);
    }

    /** The plan turned so the door faces {@code door} (north is the plan's own). */
    public static List<Block> rotated(Facing door) { return rotated(door, false); }
    public static List<Block> rotated(Facing door, boolean large) {
        int turns = door.ordinal();
        List<Block> out = new ArrayList<>();
        for (Block block : plan(large)) out.add(block.rotated(turns));
        return List.copyOf(out);
    }

    /** The way up to step {@code i}: the direction from the cell before it (the landing for the first) to its own cell. */
    static Facing climb(int i) {
        int[] to = RING[i + 1];
        int[] from = RING[i];
        int dx = to[0] - from[0], dz = to[1] - from[1];
        if (dx > 0) return Facing.EAST;
        if (dx < 0) return Facing.WEST;
        return dz > 0 ? Facing.SOUTH : Facing.NORTH;
    }

    /** Cells of the platform left open: over a step one climbs from. Step i is a block at y = i + 1; one stands on it at i + 2, and
     * stepping up to the next one lifts the head over this cell to i + 4, so it needs air from i + 2 to i + 4. */
    static Set<Long> platformHoles() {
        Set<Long> holes = new HashSet<>();
        for (int i = 0; i < STEPS - 1; i++)
            if (PLATFORM >= i + 2 && PLATFORM <= i + 4) holes.add(key(RING[i + 1][0], RING[i + 1][1]));
        return holes;
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    /** The block kinds a tower's style needs, by the biome's name: desert, savanna, taiga (spruce), jungle, plains (oak). */
    public enum Style { OAK, SPRUCE, ACACIA, JUNGLE, SANDSTONE;
        public static Style forBiome(String biome) {
            String b = biome == null ? "" : biome.toLowerCase(Locale.ROOT);
            if (b.contains("desert") || b.contains("badlands") || b.contains("beach")) return SANDSTONE;
            if (b.contains("savanna")) return ACACIA;
            if (b.contains("jungle") || b.contains("bamboo")) return JUNGLE;
            if (b.contains("taiga") || b.contains("snow") || b.contains("frozen") || b.contains("grove") || b.contains("ice")) return SPRUCE;
            return OAK;
        }
    }
}
