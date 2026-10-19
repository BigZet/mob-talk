package io.github.mobtalk.core.place;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** Blocks grouped into things, the way SmartPlay groups voxels: connected cells of one kind become one object with a direction,
 * a distance, a size and what it affords ("стена из бедрока 4×3 в 1 блоке к северу: не прокопать"). Plain stone and air are
 * not objects — the walls of the place are the flood's business — and only the few most telling objects are kept. */
public final class PlaceObjects {
    /** How far around the creature blocks are grouped. */
    public static final int RADIUS = 8;
    /** How many objects a prompt gets. */
    public static final int LIMIT = 8;

    /** One grouped object. {@code distance} is the Chebyshev distance of its nearest cell from the creature's feet. */
    public record Item(Material material, String block, int cells, int sizeX, int sizeY, int sizeZ, int nearX, int nearY, int nearZ,
                       int distance, double score, float flowX, float flowZ) {
        public String direction() {
            if (nearX == 0 && nearZ == 0) return nearY < 0 ? nearY == -1 ? "под ногами" : "внизу" : "над головой";
            String side = Math.abs(nearX) >= Math.abs(nearZ) ? nearX > 0 ? "к востоку" : "к западу" : nearZ > 0 ? "к югу" : "к северу";
            return side + (nearY >= 2 ? ", выше" : nearY <= -2 ? ", ниже" : "");
        }
        public String size() {
            int[] dims = {sizeX, sizeY, sizeZ};
            java.util.Arrays.sort(dims);
            if (dims[2] <= 1) return "";
            return dims[1] <= 1 ? dims[2] + " в длину" : dims[0] <= 1 ? dims[2] + "×" + dims[1] : dims[2] + "×" + dims[1] + "×" + dims[0];
        }
        String flowText() {
            double horizontal = Math.hypot(flowX, flowZ);
            if (horizontal < 0.2) return "стоячая";
            return "течёт на " + (Math.abs(flowX) > Math.abs(flowZ) ? flowX > 0 ? "восток" : "запад" : flowZ > 0 ? "юг" : "север");
        }
        /** The object in words: name, size, distance and direction, then what it means for the creature. */
        public String text() {
            String name = BlockNames.russian(block), size = size();
            String where = distance <= 1 ? (direction().startsWith("под") || direction().startsWith("над") ? direction() : "вплотную " + direction())
                    : "в " + distance + " " + Place.plural(distance, "блоке", "блоках", "блоках") + " " + direction();
            String wall = sizeY > 1 && (sizeX == 1 || sizeZ == 1) && cells >= 4 ? "стена из " : "";
            return switch (material) {
                case UNBREAKABLE -> wall + name + sized(size) + " " + where + ": не прокопать";
                case GLASS -> wall + name + sized(size) + " " + where + ": видно насквозь, не пройти";
                case WATER -> "вода" + sized(size) + " " + where + ", " + flowText();
                case LAVA -> "лава" + sized(size) + " " + where + ": смертельно";
                case DOOR_OPEN -> name + " " + where + ", открыта";
                case DOOR_CLOSED -> name + " " + where + ", закрыта";
                case FENCE -> name + sized(size) + " " + where + ": не перепрыгнуть";
                case CLIMBABLE -> name + sized(size) + " " + where + ": можно залезть";
                case HAZARD -> name + sized(size) + " " + where + ": обожжёт или поранит";
                case MACHINE -> name + sized(size) + " " + where + ": механизм, кто-то это строил";
                case WOOD -> (sizeY >= 2 && (sizeX == 1 || sizeZ == 1) && cells >= 4 ? "стена из " : "") + name + sized(size) + " " + where;
                case LEAVES -> "листва" + sized(size) + " " + where + ": дерево";
                case PLANT -> name + (cells >= 4 ? " вокруг (" + cells + " " + Place.plural(cells, "блок", "блока", "блоков") + ")" : " " + where);
                case FARMLAND -> "грядки" + sized(size) + " " + where;
                default -> name + sized(size) + " " + where;
            };
        }
        private static String sized(String size) { return size.isEmpty() ? "" : " " + size; }
    }

    private PlaceObjects() { }

    public static List<Item> find(VoxelGrid grid, int originY) { return find(grid, originY, LIMIT); }

    public static List<Item> find(VoxelGrid grid, int originY, int limit) {
        int R = Math.min(RADIUS, grid.radiusXZ), RY = Math.min(RADIUS, grid.radiusY - Math.abs(originY));
        BitSet seen = new BitSet();
        List<Item> items = new ArrayList<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = -R; x <= R; x++) for (int y = originY - RY; y <= originY + RY; y++) for (int z = -R; z <= R; z++) {
            int i = grid.index(x, y, z);
            if (i < 0 || seen.get(i)) continue;
            Material m = grid.material(x, y, z);
            if (m == Material.AIR || m == Material.SOLID) continue;
            String block = grid.block(x, y, z);
            boolean byKind = m == Material.PLANT || m == Material.LEAVES || m == Material.WATER || m == Material.LAVA;
            seen.set(i); queue.add(new int[]{x, y, z});
            int cells = 0, minX = x, maxX = x, minY = y, maxY = y, minZ = z, maxZ = z, nearX = x, nearY = y, nearZ = z, near = Integer.MAX_VALUE;
            float flowX = 0, flowZ = 0;
            while (!queue.isEmpty()) {
                int[] c = queue.poll(); cells++;
                minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]); minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]);
                minZ = Math.min(minZ, c[2]); maxZ = Math.max(maxZ, c[2]);
                int d = Math.max(Math.abs(c[0]), Math.max(Math.abs(c[1] - originY), Math.abs(c[2])));
                int tie = Math.abs(c[0]) + Math.abs(c[1] - originY) + Math.abs(c[2]);
                if (d < near || d == near && tie < Math.abs(nearX) + Math.abs(nearY - originY) + Math.abs(nearZ)) { near = d; nearX = c[0]; nearY = c[1]; nearZ = c[2]; }
                float[] flow = grid.flow(c[0], c[1], c[2]);
                if (flow != null) { flowX += flow[0]; flowZ += flow[2]; }
                for (int[] step : STEPS) {
                    int nx = c[0] + step[0], ny = c[1] + step[1], nz = c[2] + step[2];
                    if (Math.abs(nx) > R || Math.abs(nz) > R || ny < originY - RY || ny > originY + RY) continue;
                    int ni = grid.index(nx, ny, nz);
                    if (ni < 0 || seen.get(ni)) continue;
                    if (grid.material(nx, ny, nz) != m || !byKind && !grid.block(nx, ny, nz).equals(block)) continue;
                    seen.set(ni); queue.add(new int[]{nx, ny, nz});
                }
            }
            double score = weight(m) + 4.0 / (1 + near) + 0.4 * Math.log(cells + 1) / Math.log(2);
            items.add(new Item(m, block, cells, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, nearX, nearY - originY, nearZ, near, score, flowX, flowZ));
        }
        items.sort(Comparator.comparingDouble((Item item) -> -item.score()));
        List<Item> out = new ArrayList<>();
        boolean plant = false, leaves = false;
        for (Item item : items) {
            if (item.material() == Material.PLANT) { if (plant) continue; plant = true; }
            if (item.material() == Material.LEAVES) { if (leaves) continue; leaves = true; }
            out.add(item);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** How much an object matters by what it is: danger and rarity first. */
    static double weight(Material m) {
        return switch (m) {
            case LAVA -> 10; case HAZARD -> 6; case MACHINE -> 6; case DOOR_OPEN, DOOR_CLOSED -> 5; case UNBREAKABLE -> 4; case GLASS -> 4;
            case CLIMBABLE -> 3; case WATER -> 3; case FENCE -> 2; case FARMLAND -> 1.5; case WOOD -> 1; case LEAVES -> 0.5; case PLANT -> 0.5;
            default -> 0;
        };
    }
}
