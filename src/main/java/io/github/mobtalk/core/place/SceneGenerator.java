package io.github.mobtalk.core.place;

import java.util.Random;

/** Procedural voxel scenes with a creature in the middle and a known label: pits, chambers, funnels, cages, houses, farms,
 * mines, traps, open ground, pillars, ponds, each with random sizes, materials and orientation. One source of truth for the
 * unit tests of the place analysis, for the classifier's dataset and, later, for building test scenes in a real world. */
public final class SceneGenerator {
    /** The classes the classifier learns; the ordinal is the label in the dataset file, so append, never reorder. */
    public enum Label {
        PIT("яма"), CHAMBER("закрытая камера"), FUNNEL("воронка"), CAGE("клетка"), HOUSE("дом"), FARM("ферма"), MINE("шахта"),
        TRAP("ловушка"), OPEN("открытое место"), PILLAR("столб"), POND("водоём");
        public final String russian;
        Label(String russian) { this.russian = russian; }
        public static final Label[] ALL = values();
    }

    /** A generated scene: the grid, its label, and what the place analysis should say about it. */
    public record Scene(VoxelGrid grid, Label label, String note) { }

    /** Box size: 33×25×33, enough for the flood's radius of 12. */
    public static final int RADIUS_XZ = 16, RADIUS_Y = 12;

    private final Random random;
    public SceneGenerator(long seed) { random = new Random(seed); }
    public SceneGenerator(Random random) { this.random = random; }

    public Scene generate(Label label) {
        VoxelGrid g = new VoxelGrid(RADIUS_XZ, RADIUS_Y);
        return switch (label) {
            case PIT -> pit(g);
            case CHAMBER -> chamber(g);
            case FUNNEL -> funnel(g);
            case CAGE -> cage(g);
            case HOUSE -> house(g);
            case FARM -> farm(g);
            case MINE -> mine(g);
            case TRAP -> trap(g);
            case OPEN -> open(g);
            case PILLAR -> pillar(g);
            case POND -> pond(g);
        };
    }

    private int between(int lo, int hi) { return lo + random.nextInt(hi - lo + 1); }
    private boolean chance(double p) { return random.nextDouble() < p; }
    private <T> T pick(T[] options) { return options[random.nextInt(options.length)]; }

    private static final String[] GROUNDS = {"minecraft:grass_block", "minecraft:dirt", "minecraft:stone", "minecraft:sand", "minecraft:snow_block", "minecraft:gravel"};
    private static final String[] ROCKS = {"minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone", "minecraft:andesite", "minecraft:dirt", "minecraft:netherrack"};
    private static final String[] WALLS = {"minecraft:stone", "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:dirt", "minecraft:oak_planks", "minecraft:deepslate", "minecraft:bricks"};
    private static final String[] HARD = {"minecraft:bedrock", "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:reinforced_deepslate"};
    private static final String[] GLASSES = {"minecraft:glass", "minecraft:iron_bars", "minecraft:glass_pane", "minecraft:tinted_glass"};
    private static final String[] WOODS = {"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:oak_log", "minecraft:dark_oak_planks"};
    private static final String[] CROPS = {"minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots"};

    /** Fills everything at or below {@code surfaceY} with ground, with gentle hills when {@code noise} is set. */
    private void terrain(VoxelGrid g, int surfaceY, boolean noise, String top) {
        double ax = random.nextDouble() * Math.PI * 2, az = random.nextDouble() * Math.PI * 2, f = 0.2 + random.nextDouble() * 0.3;
        String rock = pick(ROCKS);
        for (int x = -g.radiusXZ; x <= g.radiusXZ; x++) for (int z = -g.radiusXZ; z <= g.radiusXZ; z++) {
            int h = surfaceY;
            if (noise) h += Math.max(-1, Math.min(1, (int) Math.round(Math.sin(x * f + ax) * 0.8 + Math.cos(z * f + az) * 0.8)));
            if (x == 0 && z == 0) h = surfaceY;
            for (int y = -g.radiusY; y <= h; y++) g.set(x, y, z, Material.SOLID, y == h ? top : y > h - 3 ? "minecraft:dirt" : rock);
            if (noise && h >= surfaceY && chance(0.25) && !(x == 0 && z == 0)) g.set(x, h + 1, z, Material.PLANT, "minecraft:short_grass");
        }
    }
    /** Levels the ground under a structure: solid up to {@code surfaceY}, air above, inclusive corners. */
    private void flatten(VoxelGrid g, int x1, int z1, int x2, int z2, int surfaceY, String top) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
            for (int y = surfaceY + 1; y <= g.radiusY; y++) if (g.material(x, y, z) != Material.AIR) g.set(x, y, z, Material.AIR, "minecraft:air");
            for (int y = surfaceY; y >= surfaceY - 3; y--) if (g.passable(x, y, z)) g.set(x, y, z, Material.SOLID, y == surfaceY ? top : "minecraft:dirt");
        }
    }
    /** Everything is rock, no sky: a scene underground. */
    private void underground(VoxelGrid g) {
        String rock = pick(new String[]{"minecraft:stone", "minecraft:deepslate", "minecraft:stone", "minecraft:tuff"});
        g.fill(-g.radiusXZ, -g.radiusY, -g.radiusXZ, g.radiusXZ, g.radiusY, g.radiusXZ, Material.SOLID, rock);
        for (int i = 0; i < 40; i++) g.set(between(-g.radiusXZ, g.radiusXZ), between(-g.radiusY, g.radiusY), between(-g.radiusXZ, g.radiusXZ), Material.SOLID,
                pick(new String[]{"minecraft:iron_ore", "minecraft:coal_ore", "minecraft:gravel", "minecraft:dirt", "minecraft:andesite"}));
        for (int x = -g.radiusXZ; x <= g.radiusXZ; x++) for (int z = -g.radiusXZ; z <= g.radiusXZ; z++) g.openAbove(x, z, false);
    }
    private void tree(VoxelGrid g, int x, int baseY, int z) {
        int height = between(4, 6);
        String log = pick(new String[]{"minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log"});
        for (int y = 0; y < height; y++) g.set(x, baseY + y, z, Material.WOOD, log);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = height - 3; dy <= height + 1; dy++)
            if ((dx != 0 || dz != 0 || dy >= height) && Math.abs(dx) + Math.abs(dz) + Math.abs(dy - height + 1) <= 4
                    && g.material(x + dx, baseY + dy, z + dz) == Material.AIR) g.set(x + dx, baseY + dy, z + dz, Material.LEAVES, "minecraft:oak_leaves");
    }

    Scene pit(VoxelGrid g) {
        int w = between(1, 5), l = between(1, 5), depth = between(2, 8);
        int surface = depth - 1;
        terrain(g, surface, chance(0.5), pick(GROUNDS));
        int ox = -random.nextInt(w), oz = -random.nextInt(l);
        flatten(g, ox - 2, oz - 2, ox + w + 1, oz + l + 1, surface, g.block(0, surface, 0));
        g.fill(ox, 0, oz, ox + w - 1, surface + 1, oz + l - 1, Material.AIR, "minecraft:air");
        boolean lined = chance(0.35);
        String lining = lined ? pick(HARD) : pick(WALLS);
        if (lined || chance(0.5)) {
            for (int x = ox - 1; x <= ox + w; x++) for (int z = oz - 1; z <= oz + l; z++) for (int y = -1; y <= surface; y++)
                if ((x < ox || x > ox + w - 1 || z < oz || z > oz + l - 1 || y == -1) && !g.passable(x, y, z)) g.set(x, y, z, lined ? Material.UNBREAKABLE : Material.SOLID, lining);
        }
        if (chance(0.1)) g.fill(ox, 0, oz, ox + w - 1, 0, oz + l - 1, Material.WATER);
        return new Scene(g, Label.PIT, "яма " + w + "×" + l + " глубиной " + depth + (lined ? " из " + lining : ""));
    }

    Scene chamber(VoxelGrid g) {
        int w = between(1, 6), l = between(1, 6), h = between(1, 4);
        boolean buried = chance(0.5);
        String wall = chance(0.4) ? pick(HARD) : pick(WALLS);
        Material kind = isHard(wall) ? Material.UNBREAKABLE : wall.contains("planks") ? Material.WOOD : Material.SOLID;
        if (buried) underground(g); else terrain(g, -1, chance(0.5), pick(GROUNDS));
        int ox = -random.nextInt(w), oz = -random.nextInt(l);
        int thick = between(1, 2);
        if (!buried) flatten(g, ox - thick - 1, oz - thick - 1, ox + w + thick, oz + l + thick, -1, "minecraft:grass_block");
        g.fill(ox - thick, -1, oz - thick, ox + w - 1 + thick, h + thick, oz + l - 1 + thick, kind, wall);
        g.fill(ox, 0, oz, ox + w - 1, h, oz + l - 1, Material.AIR, "minecraft:air");
        if (chance(0.3) && w * l > 1) {
            int cx = ox + random.nextInt(w), cz = oz + random.nextInt(l);
            if (cx != 0 || cz != 0) g.set(cx, 0, cz, Material.SOLID, pick(new String[]{"minecraft:chest", "minecraft:crafting_table", "minecraft:barrel"}));
        }
        return new Scene(g, Label.CHAMBER, "камера " + w + "×" + l + "×" + (h + 1) + " из " + wall + (buried ? " под землёй" : ""));
    }
    private static boolean isHard(String id) { for (String h : HARD) if (h.equals(id)) return true; return false; }

    Scene funnel(VoxelGrid g) {
        boolean wet = chance(0.7), lava = chance(0.5);
        // The rim must stay inside the flood's radius, or the far edge reads as a way out.
        int depth = wet ? between(4, 8) : between(4, 6), halfTop = between(depth - 1, Math.min(depth + 3, wet ? 8 : 6));
        int step = Math.max(1, (int) Math.ceil(halfTop / (double) (depth - 1)));
        int[] half = new int[depth];
        for (int i = 0; i < depth; i++) half[i] = Math.max(0, halfTop - i * step);
        int reach = Math.max(0, Place.RADIUS - 2 - halfTop);
        // The creature's level, counted from the top: on a ledge in a dry funnel, anywhere in the water of a wet one.
        int level = between(0, depth - 2);
        if (!wet) { int chosen = -1; for (int l = depth - 2; l >= 0; l--) if (half[l + 1] + 1 <= half[l] && half[l + 1] + 1 <= reach) { chosen = l; if (chance(0.5)) break; } level = chosen < 0 ? depth - 2 : chosen; }
        int cx = (chance(0.5) ? 1 : -1) * (wet ? between(0, Math.min(half[level], reach)) : Math.min(reach, half[level + 1] + 1));
        // Dry funnels are terraces two blocks tall, which no creature climbs; wet ones are one-block rings under water.
        int tall = wet ? 1 : 2, surface = level * tall + tall - 1;
        terrain(g, surface, false, pick(GROUNDS));
        String wall = chance(0.3) ? pick(HARD) : pick(WALLS);
        Material kind = isHard(wall) ? Material.UNBREAKABLE : Material.SOLID;
        for (int i = 0; i < depth; i++) {
            int h = half[i];
            for (int dy = 0; dy < tall; dy++) {
                int y = surface - i * tall - dy;
                for (int x = cx - h - 1; x <= cx + h + 1; x++) for (int z = -h - 1; z <= h + 1; z++) {
                    boolean inside = Math.abs(x - cx) <= h && Math.abs(z) <= h;
                    if (inside) {
                        if (wet) { g.set(x, y, z, Material.WATER); g.flow(x, y, z, -Math.signum(x - cx) * 0.5f, -0.5f, -Math.signum(z) * 0.5f); }
                        else g.set(x, y, z, Material.AIR, "minecraft:air");
                    } else if (!g.passable(x, y, z)) g.set(x, y, z, kind, wall);
                }
                if (i == depth - 1 && dy == tall - 1) for (int x = cx - h - 1; x <= cx + h + 1; x++) for (int z = -h - 1; z <= h + 1; z++) if (!g.passable(x, y - 1, z)) g.set(x, y - 1, z, kind, wall);
            }
        }
        int bottomY = surface - depth * tall + 1, holeDepth = between(2, 5);
        for (int y = bottomY - 1; y >= bottomY - holeDepth; y--) g.set(cx, y, 0, Material.AIR, "minecraft:air");
        if (lava) g.fill(cx - 1, bottomY - holeDepth - 1, -1, cx + 1, bottomY - holeDepth - 1, 1, Material.LAVA);
        else for (int y = bottomY - holeDepth - 1; y >= -g.radiusY; y--) g.set(cx, y, 0, Material.AIR, "minecraft:air");
        if (!g.passable(0, 0, 0)) g.set(0, 0, 0, wet ? Material.WATER : Material.AIR);
        if (!g.passable(0, 1, 0)) g.set(0, 1, 0, Material.AIR, "minecraft:air");
        return new Scene(g, Label.FUNNEL, "воронка глубиной " + depth + (wet ? " с водой" : " сухая") + (lava ? ", внизу лава" : ", внизу дыра"));
    }

    Scene cage(VoxelGrid g) {
        int w = between(1, 4), l = between(1, 4), h = between(2, 4);
        terrain(g, -1, chance(0.5), pick(GROUNDS));
        String glass = pick(GLASSES);
        int ox = -random.nextInt(w), oz = -random.nextInt(l);
        flatten(g, ox - 2, oz - 2, ox + w + 1, oz + l + 1, -1, "minecraft:grass_block");
        g.fill(ox - 1, 0, oz - 1, ox + w, h, oz + l, Material.GLASS, glass);
        if (chance(0.3)) g.fill(ox - 1, -1, oz - 1, ox + w, -1, oz + l, Material.GLASS, glass);
        g.fill(ox, 0, oz, ox + w - 1, h - 1, oz + l - 1, Material.AIR, "minecraft:air");
        boolean roofless = chance(0.25);
        if (roofless) g.fill(ox, h, oz, ox + w - 1, h, oz + l - 1, Material.AIR, "minecraft:air");
        boolean door = chance(0.3);
        if (door) { int side = random.nextInt(4); int dx = side == 0 ? ox + w : side == 1 ? ox - 1 : between(ox, ox + w - 1); int dz = side == 2 ? oz + l : side == 3 ? oz - 1 : between(oz, oz + l - 1);
            g.set(dx, 0, dz, Material.DOOR_CLOSED, "minecraft:iron_door"); g.set(dx, 1, dz, Material.DOOR_CLOSED, "minecraft:iron_door"); }
        return new Scene(g, Label.CAGE, "клетка " + w + "×" + l + "×" + h + " из " + glass + (roofless ? " без крыши" : "") + (door ? " с дверью" : ""));
    }

    Scene house(VoxelGrid g) {
        int w = between(3, 8), l = between(3, 8), h = between(3, 4);
        terrain(g, -1, chance(0.5), pick(GROUNDS));
        String wall = chance(0.6) ? pick(WOODS) : pick(new String[]{"minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:bricks"});
        Material kind = wall.contains("planks") || wall.contains("log") ? Material.WOOD : Material.SOLID;
        int ox = -random.nextInt(w), oz = -random.nextInt(l);
        flatten(g, ox - 3, oz - 3, ox + w + 2, oz + l + 2, -1, "minecraft:grass_block");
        g.fill(ox - 1, -1, oz - 1, ox + w, h, oz + l, kind, wall);
        g.fill(ox, -1, oz, ox + w - 1, -1, oz + l - 1, Material.WOOD, "minecraft:oak_planks");
        g.fill(ox, 0, oz, ox + w - 1, h - 1, oz + l - 1, Material.AIR, "minecraft:air");
        // Windows at eye level on each wall.
        for (int x = ox; x <= ox + w - 1; x += 2) { if (chance(0.6)) g.set(x, 1, oz - 1, Material.GLASS, "minecraft:glass_pane"); if (chance(0.6)) g.set(x, 1, oz + l, Material.GLASS, "minecraft:glass_pane"); }
        for (int z = oz; z <= oz + l - 1; z += 2) { if (chance(0.6)) g.set(ox - 1, 1, z, Material.GLASS, "minecraft:glass_pane"); if (chance(0.6)) g.set(ox + w, 1, z, Material.GLASS, "minecraft:glass_pane"); }
        // The door.
        int side = random.nextInt(4);
        int dx = side == 0 ? ox + w : side == 1 ? ox - 1 : between(ox, ox + w - 1), dz = side == 2 ? oz + l : side == 3 ? oz - 1 : between(oz, oz + l - 1);
        boolean open = chance(0.5);
        g.set(dx, 0, dz, open ? Material.DOOR_OPEN : Material.DOOR_CLOSED, "minecraft:oak_door");
        g.set(dx, 1, dz, open ? Material.DOOR_OPEN : Material.DOOR_CLOSED, "minecraft:oak_door");
        // Furniture.
        for (int i = between(0, 4); i > 0; i--) {
            int fx = between(ox, ox + w - 1), fz = between(oz, oz + l - 1);
            if (fx == 0 && fz == 0 || fx == dx && fz == dz) continue;
            g.set(fx, 0, fz, Material.SOLID, pick(new String[]{"minecraft:chest", "minecraft:crafting_table", "minecraft:furnace", "minecraft:bookshelf", "minecraft:red_bed"}));
        }
        if (chance(0.5)) for (int x = ox - 2; x <= ox + w + 1; x++) for (int z = oz - 2; z <= oz + l + 1; z++)
            if (Math.abs(Math.abs(x - ox - (w - 1) / 2.0) - Math.abs(z - oz - (l - 1) / 2.0)) < 1.5 && g.material(x, h + 1, z) == Material.AIR) g.set(x, h + 1, z, kind, wall);
        return new Scene(g, Label.HOUSE, "дом " + w + "×" + l + " из " + wall + ", дверь " + (open ? "открыта" : "закрыта"));
    }

    Scene farm(VoxelGrid g) {
        int w = between(5, 12), l = between(5, 12);
        terrain(g, -1, false, "minecraft:grass_block");
        int ox = -random.nextInt(w), oz = -random.nextInt(l);
        boolean alongX = chance(0.5);
        int every = between(3, 5);
        String crop = pick(CROPS);
        for (int x = ox; x <= ox + w - 1; x++) for (int z = oz; z <= oz + l - 1; z++) {
            int row = alongX ? z - oz : x - ox;
            if (row % every == every - 1) { g.set(x, -1, z, Material.WATER); g.set(x, 0, z, Material.AIR, "minecraft:air"); }
            else { g.set(x, -1, z, Material.FARMLAND); if (chance(0.8)) g.set(x, 0, z, Material.PLANT, crop); }
        }
        if (g.material(0, 0, 0) == Material.WATER) { g.set(0, -1, 0, Material.SOLID, "minecraft:dirt"); g.set(0, 0, 0, Material.AIR, "minecraft:air"); }
        boolean fenced = chance(0.7);
        if (fenced) {
            String fence = pick(new String[]{"minecraft:oak_fence", "minecraft:spruce_fence", "minecraft:cobblestone_wall"});
            for (int x = ox - 1; x <= ox + w; x++) { g.set(x, 0, oz - 1, Material.FENCE, fence); g.set(x, 0, oz + l, Material.FENCE, fence); }
            for (int z = oz - 1; z <= oz + l; z++) { g.set(ox - 1, 0, z, Material.FENCE, fence); g.set(ox + w, 0, z, Material.FENCE, fence); }
            int gx = chance(0.5) ? ox - 1 : ox + w, gz = between(oz, oz + l - 1);
            g.set(gx, 0, gz, chance(0.5) ? Material.DOOR_OPEN : Material.DOOR_CLOSED, "minecraft:oak_fence_gate");
            for (int x = ox - 1; x <= ox + w; x++) for (int z = oz - 1; z <= oz + l; z++) if (g.material(x, -1, z) == Material.AIR) g.set(x, -1, z, Material.SOLID, "minecraft:grass_block");
        }
        if (chance(0.3)) { int tx = between(-g.radiusXZ + 3, g.radiusXZ - 3), tz = between(-g.radiusXZ + 3, g.radiusXZ - 3);
            if (!(tx >= ox - 1 && tx <= ox + w && tz >= oz - 1 && tz <= oz + l)) tree(g, tx, 0, tz); }
        return new Scene(g, Label.FARM, "ферма " + w + "×" + l + (fenced ? " за забором" : ""));
    }

    Scene mine(VoxelGrid g) {
        underground(g);
        boolean alongX = chance(0.5);
        int width = between(1, 2), height = between(2, 3);
        boolean deadEnd = chance(0.4), deadWest = chance(0.5);
        int from = deadEnd && deadWest ? -between(2, 8) : -g.radiusXZ, to = deadEnd && !deadWest ? between(2, 8) : g.radiusXZ;
        for (int t = from; t <= to; t++) for (int s = 0; s < width; s++) for (int y = 0; y < height; y++)
            if (alongX) g.set(t, y, s, Material.AIR, "minecraft:air"); else g.set(s, y, t, Material.AIR, "minecraft:air");
        for (int t = from; t <= to; t += between(3, 6)) {
            if (chance(0.5)) { if (alongX) { g.set(t, 0, -1, Material.WOOD, "minecraft:oak_fence"); g.set(t, 0, width, Material.WOOD, "minecraft:oak_log"); } else { g.set(-1, 0, t, Material.WOOD, "minecraft:oak_log"); g.set(width, 0, t, Material.WOOD, "minecraft:oak_log"); } }
        }
        if (chance(0.5)) { int b = between(from + 2, Math.max(from + 2, to - 2)); int len = between(3, 10);
            for (int t = 0; t <= len; t++) for (int y = 0; y < 2; y++) if (alongX) g.set(b, y, width + t, Material.AIR, "minecraft:air"); else g.set(width + t, y, b, Material.AIR, "minecraft:air"); }
        if (width == 2 && chance(0.2)) { int t = between(from + 1, Math.max(from + 1, to - 1)); if (alongX) g.set(t, -1, 0, Material.LAVA); else g.set(0, -1, t, Material.LAVA); }
        if (chance(0.15)) { int t = between(from + 1, Math.max(from + 1, to - 1)); if (alongX) g.set(t, 0, 0, Material.WATER); else g.set(0, 0, t, Material.WATER); }
        g.set(0, 0, 0, Material.AIR, "minecraft:air"); g.set(0, 1, 0, Material.AIR, "minecraft:air");
        return new Scene(g, Label.MINE, "тоннель " + width + "×" + height + (from > -g.radiusXZ && to < g.radiusXZ ? " с двумя тупиками" : from > -g.radiusXZ || to < g.radiusXZ ? " с тупиком" : " сквозной"));
    }

    Scene trap(VoxelGrid g) {
        boolean buried = chance(0.4);
        int w = between(3, 7), l = between(3, 7), depth = between(2, 5);
        if (buried) underground(g); else terrain(g, depth - 1, false, pick(GROUNDS));
        int ox = -random.nextInt(w), oz = -random.nextInt(l);
        String wall = chance(0.3) ? pick(HARD) : pick(WALLS);
        Material kind = isHard(wall) ? Material.UNBREAKABLE : Material.SOLID;
        g.fill(ox - 1, -2, oz - 1, ox + w, depth + (buried ? 1 : 0), oz + l, kind, wall);
        g.fill(ox, -1, oz, ox + w - 1, depth, oz + l - 1, Material.AIR, "minecraft:air");
        boolean lava = chance(0.7);
        g.fill(ox, -1, oz, ox + w - 1, -1, oz + l - 1, lava ? Material.LAVA : Material.WATER);
        int iw = between(1, 2), il = between(1, 2);
        g.fill(Math.max(ox, -iw + 1), -1, Math.max(oz, -il + 1), Math.min(ox + w - 1, iw - 1), -1, Math.min(oz + l - 1, il - 1), Material.SOLID, pick(ROCKS));
        if (!lava) g.fill(ox, -1, oz, ox + w - 1, -1, oz + l - 1, Material.WATER);
        g.set(0, -1, 0, Material.SOLID, "minecraft:stone");
        for (int i = between(1, 4); i > 0; i--) {
            int side = random.nextInt(4);
            int mx = side == 0 ? ox + w : side == 1 ? ox - 1 : between(ox, ox + w - 1), mz = side == 2 ? oz + l : side == 3 ? oz - 1 : between(oz, oz + l - 1);
            g.set(mx, between(0, depth - 1), mz, Material.MACHINE, pick(new String[]{"minecraft:piston", "minecraft:sticky_piston", "minecraft:dispenser", "minecraft:observer", "minecraft:tnt"}));
        }
        if (chance(0.3)) g.set(between(ox, ox + w - 1), depth, between(oz, oz + l - 1), Material.DOOR_CLOSED, "minecraft:oak_trapdoor");
        return new Scene(g, Label.TRAP, "ловушка " + w + "×" + l + (lava ? " с лавой" : " с водой") + ", механизмы в стенах");
    }

    Scene open(VoxelGrid g) {
        boolean forest = chance(0.5);
        terrain(g, -1, chance(0.8), pick(new String[]{"minecraft:grass_block", "minecraft:grass_block", "minecraft:sand", "minecraft:snow_block", "minecraft:dirt"}));
        for (int i = forest ? between(4, 12) : between(0, 2); i > 0; i--) {
            int tx = between(-g.radiusXZ + 2, g.radiusXZ - 2), tz = between(-g.radiusXZ + 2, g.radiusXZ - 2);
            if (Math.abs(tx) <= 1 && Math.abs(tz) <= 1) continue;
            int base = -g.radiusY; while (base < g.radiusY && !g.passable(tx, base + 1, tz)) base++;
            tree(g, tx, base + 1, tz);
        }
        for (int i = between(0, 3); i > 0; i--) { int rx = between(-10, 10), rz = between(-10, 10); if (Math.abs(rx) + Math.abs(rz) > 2) g.fill(rx, 0, rz, rx + between(0, 2), between(0, 1), rz + between(0, 2), Material.SOLID, "minecraft:cobblestone"); }
        if (chance(0.2)) { int px = between(-10, 10), pz = between(-10, 10); if (Math.abs(px) + Math.abs(pz) > 4) g.fill(px, -1, pz, px + between(1, 3), -1, pz + between(1, 3), Material.WATER); }
        g.set(0, 0, 0, Material.AIR, "minecraft:air"); g.set(0, 1, 0, Material.AIR, "minecraft:air");
        return new Scene(g, Label.OPEN, forest ? "лес" : "поле");
    }

    Scene pillar(VoxelGrid g) {
        int height = between(5, 10), w = between(1, 3), l = between(1, 3);
        terrain(g, -height - 1, chance(0.6), pick(GROUNDS));
        flatten(g, -4, -4, 4, 4, -height - 1, g.block(0, -height - 1, 0));
        String rock = pick(new String[]{"minecraft:stone", "minecraft:dirt", "minecraft:cobblestone", "minecraft:sand", "minecraft:oak_log"});
        int ox = -random.nextInt(w), oz = -random.nextInt(l);
        g.fill(ox, -height - 1, oz, ox + w - 1, -1, oz + l - 1, rock.contains("log") ? Material.WOOD : Material.SOLID, rock);
        if (chance(0.3)) { // A cliff: the ground is high on one side, but a block-wide gap keeps the top an island.
            int side = random.nextInt(4), gap = between(2, 4);
            for (int x = -g.radiusXZ; x <= g.radiusXZ; x++) for (int z = -g.radiusXZ; z <= g.radiusXZ; z++) {
                boolean high = side == 0 ? x > ox + w - 1 + gap : side == 1 ? x < ox - gap : side == 2 ? z > oz + l - 1 + gap : z < oz - gap;
                if (high) for (int y = -height; y <= -1; y++) g.set(x, y, z, Material.SOLID, "minecraft:stone");
            }
        }
        return new Scene(g, Label.PILLAR, "столб " + w + "×" + l + " высотой " + height);
    }

    Scene pond(VoxelGrid g) {
        int rx = between(2, 6), rz = between(2, 6), depth = between(1, 4);
        terrain(g, 0, chance(0.5), pick(new String[]{"minecraft:grass_block", "minecraft:sand", "minecraft:dirt"}));
        for (int x = -g.radiusXZ; x <= g.radiusXZ; x++) for (int z = -g.radiusXZ; z <= g.radiusXZ; z++) {
            double r = (x * x) / (double) (rx * rx) + (z * z) / (double) (rz * rz);
            if (r > 1) continue;
            int d = Math.max(1, (int) Math.round(depth * (1 - r)));
            for (int y = 0; y > -d; y--) g.set(x, y, z, Material.WATER);
            g.set(x, -d, z, Material.SOLID, pick(new String[]{"minecraft:sand", "minecraft:gravel", "minecraft:clay", "minecraft:dirt"}));
            g.set(x, 1, z, Material.AIR, "minecraft:air");
        }
        if (chance(0.3)) { float fx = chance(0.5) ? 0.4f : -0.4f; for (int x = -rx; x <= rx; x++) for (int z = -rz; z <= rz; z++) if (g.material(x, 0, z) == Material.WATER) g.flow(x, 0, z, fx, 0, 0); }
        g.set(0, 0, 0, Material.WATER); g.set(0, 1, 0, Material.AIR, "minecraft:air");
        return new Scene(g, Label.POND, "водоём " + (2 * rx + 1) + "×" + (2 * rz + 1) + " глубиной " + depth);
    }
}
