package io.github.mobtalk.core.place;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trees found by rule, not guessed: a column of logs of one kind standing on the ground under a crown of leaves of the same
 * kind is a tree of that kind. Neighbouring trunk columns (2×2 dark oaks, mega spruces) are one tree; a log column with no
 * crown (a house corner, a pillar) is not a tree; a log that hangs in the air is a branch. */
public final class Trees {
    private Trees() { }

    /** A kind of tree: what its trunk and crown are made of and how a Russian sentence names it. */
    public record Species(String id, String nominative, String genitive, String plural, List<String> trunks, List<String> crowns) { }

    public static final List<Species> SPECIES = List.of(
            new Species("oak", "дуб", "дуба", "дубы", List.of("oak_log", "oak_wood"), List.of("oak_leaves")),
            new Species("azalea", "азалия", "азалии", "азалии", List.of("oak_log", "oak_wood"), List.of("azalea_leaves", "flowering_azalea_leaves")),
            new Species("birch", "берёза", "берёзы", "берёзы", List.of("birch_log", "birch_wood"), List.of("birch_leaves")),
            new Species("spruce", "ель", "ели", "ели", List.of("spruce_log", "spruce_wood"), List.of("spruce_leaves")),
            new Species("jungle", "тропическое дерево", "тропического дерева", "тропические деревья", List.of("jungle_log", "jungle_wood"), List.of("jungle_leaves")),
            new Species("acacia", "акация", "акации", "акации", List.of("acacia_log", "acacia_wood"), List.of("acacia_leaves")),
            new Species("dark_oak", "тёмный дуб", "тёмного дуба", "тёмные дубы", List.of("dark_oak_log", "dark_oak_wood"), List.of("dark_oak_leaves")),
            new Species("mangrove", "мангровое дерево", "мангрового дерева", "мангровые деревья", List.of("mangrove_log", "mangrove_wood"), List.of("mangrove_leaves")),
            new Species("cherry", "вишня", "вишни", "вишни", List.of("cherry_log", "cherry_wood"), List.of("cherry_leaves")),
            new Species("crimson", "багровый гриб", "багрового гриба", "багровые грибы", List.of("crimson_stem"), List.of("nether_wart_block", "shroomlight")),
            new Species("warped", "искажённый гриб", "искажённого гриба", "искажённые грибы", List.of("warped_stem"), List.of("warped_wart_block", "shroomlight")),
            new Species("mushroom", "огромный гриб", "огромного гриба", "огромные грибы", List.of("mushroom_stem"), List.of("red_mushroom_block", "brown_mushroom_block")));

    public static Species species(String id) {
        for (Species s : SPECIES) if (s.id.equals(id)) return s;
        return null;
    }

    /** One tree: its kind, the middle of its trunk relative to the creature, the trunk's bottom and top, and how many crown cells were seen. */
    public record Tree(Species species, double x, double z, int baseY, int topY, int columns, int crown) {
        public int distance() { return (int) Math.round(Math.max(Math.abs(x), Math.abs(z))); }
        public String direction() {
            if (Math.abs(x) < 0.75 && Math.abs(z) < 0.75) return "прямо здесь";
            return Math.abs(x) >= Math.abs(z) ? x > 0 ? "к востоку" : "к западу" : z > 0 ? "к югу" : "к северу";
        }
    }

    /** How many leaves must hang around the top of a trunk for it to be a tree rather than a log pillar. */
    static final int MIN_CROWN = 4;
    /** How far from the trunk's top a crown is looked for, sideways and up/down. */
    static final int CROWN_REACH = 3;

    private static String bare(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    /** Every tree whose trunk stands in the grid. */
    public static List<Tree> find(VoxelGrid grid) {
        int r = grid.radiusXZ, ry = grid.radiusY;
        record Run(int x, int z, int bottom, int top, String trunk) { }
        List<Run> runs = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            int y = -ry;
            while (y <= ry) {
                String id = bare(grid.block(x, y, z));
                if (!isTrunk(id)) { y++; continue; }
                int bottom = y;
                while (y + 1 <= ry && bare(grid.block(x, y + 1, z)).equals(id)) y++;
                int top = y;
                y++;
                if (top - bottom + 1 < 2) continue;
                // A trunk stands on something: ground, roots, or the bottom of the box. A run over air or leaves is a branch.
                if (bottom > -ry) {
                    Material below = grid.material(x, bottom - 1, z);
                    if (below.passable || below == Material.LEAVES) continue;
                }
                runs.add(new Run(x, z, bottom, top, id));
            }
        }
        // Join trunk columns that touch (2×2 trunks, mangrove props) into one tree.
        int n = runs.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            Run a = runs.get(i), b = runs.get(j);
            if (!a.trunk.equals(b.trunk) || Math.abs(a.x - b.x) > 1 || Math.abs(a.z - b.z) > 1) continue;
            if (a.top < b.bottom || b.top < a.bottom) continue;
            parent[find(parent, i)] = find(parent, j);
        }
        Map<Integer, List<Run>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(runs.get(i));
        List<Tree> trees = new ArrayList<>();
        for (List<Run> group : groups.values()) {
            int bottom = Integer.MAX_VALUE, top = Integer.MIN_VALUE, minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            double sx = 0, sz = 0;
            for (Run run : group) {
                bottom = Math.min(bottom, run.bottom); top = Math.max(top, run.top);
                minX = Math.min(minX, run.x); maxX = Math.max(maxX, run.x); minZ = Math.min(minZ, run.z); maxZ = Math.max(maxZ, run.z);
                sx += run.x; sz += run.z;
            }
            String trunk = group.getFirst().trunk;
            Species best = null; int bestCrown = 0;
            for (Species species : SPECIES) {
                if (!species.trunks.contains(trunk)) continue;
                int crown = 0;
                for (int x = minX - CROWN_REACH; x <= maxX + CROWN_REACH; x++)
                    for (int z = minZ - CROWN_REACH; z <= maxZ + CROWN_REACH; z++)
                        for (int y = top - CROWN_REACH; y <= Math.min(ry, top + CROWN_REACH); y++)
                            if (species.crowns.contains(bare(grid.block(x, y, z)))) crown++;
                if (crown > bestCrown) { best = species; bestCrown = crown; }
            }
            // A trunk cut off by the top of the box may carry its crown above it: then the leaves around its top half will do.
            boolean cut = top >= ry;
            if (best == null || bestCrown < (cut ? 1 : MIN_CROWN)) continue;
            trees.add(new Tree(best, sx / group.size(), sz / group.size(), bottom, top, group.size(), bestCrown));
        }
        trees.sort(Comparator.comparingDouble(t -> Math.hypot(t.x, t.z)));
        return trees;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    static boolean isTrunk(String bare) {
        for (Species s : SPECIES) if (s.trunks.contains(bare)) return true;
        return false;
    }

    /** The kind of crown that hangs over the creature's head (feet at 0,{@code originY},0), or null. */
    public static Species overhead(VoxelGrid grid, int originY, List<Tree> trees) {
        for (int y = originY + 2; y <= Math.min(grid.radiusY, originY + 12); y++) {
            String id = bare(grid.block(0, y, 0));
            for (Tree tree : trees) {
                if (Math.hypot(tree.x, tree.z) > 5) continue;
                if (tree.species.crowns.contains(id)) return tree.species;
            }
            if (!grid.passable(0, y, 0) && grid.material(0, y, 0) != Material.LEAVES) return null; // a roof between: not under a tree
        }
        return null;
    }

    /** The trees as short facts for the prompt: what hangs overhead, the nearest trees, a grove. Empty when there are none. */
    public static List<String> facts(VoxelGrid grid, int originY) {
        List<Tree> trees = find(grid);
        return facts(trees, overhead(grid, originY, trees));
    }

    public static List<String> facts(List<Tree> trees, Species overhead) {
        List<String> out = new ArrayList<>();
        if (overhead != null) out.add("Над тобой крона: " + overhead.genitive + ".");
        if (trees.isEmpty()) return out;
        if (trees.size() >= 3) {
            Map<Species, Integer> counts = new LinkedHashMap<>();
            for (Tree tree : trees) counts.merge(tree.species, 1, Integer::sum);
            List<String> parts = new ArrayList<>();
            counts.entrySet().stream().sorted(Map.Entry.<Species, Integer>comparingByValue().reversed())
                    .forEach(e -> parts.add(e.getValue() == 1 ? e.getKey().nominative : e.getKey().plural + " — " + e.getValue()));
            out.add("Вокруг роща из " + trees.size() + " " + Place.plural(trees.size(), "дерева", "деревьев", "деревьев") + ": " + String.join(", ", parts) + ".");
        }
        List<String> near = new ArrayList<>();
        for (Tree tree : trees) {
            if (near.size() >= 2) break;
            int d = tree.distance();
            if (overhead == tree.species && tree.distance() <= 1) continue;
            String where = tree.direction();
            near.add(tree.species.nominative + " " + (where.equals("прямо здесь") ? where : "в " + d + " " + Place.plural(d, "блоке", "блоках", "блоках") + " " + where));
        }
        if (!near.isEmpty()) out.add((near.size() == 1 ? "Ближайшее дерево: " : "Ближайшие деревья: ") + String.join("; ", near) + ".");
        return out;
    }
}
