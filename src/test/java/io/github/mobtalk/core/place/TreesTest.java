package io.github.mobtalk.core.place;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Trees by rule: a log column of one kind under leaves of that kind, standing on the ground. */
class TreesTest {
    private static VoxelGrid ground() {
        VoxelGrid g = new VoxelGrid(12, 8);
        g.fill(-12, -8, -12, 12, -1, 12, Material.SOLID, "minecraft:grass_block");
        return g;
    }
    private static void tree(VoxelGrid g, int x, int z, String kind, int height) {
        g.fill(x - 2, height - 1, z - 2, x + 2, height + 1, z + 2, Material.LEAVES, "minecraft:" + kind + "_leaves");
        g.fill(x, 0, z, x, height, z, Material.WOOD, "minecraft:" + kind + "_log");
    }

    @Test void anOakOverheadAndABirchNearby() {
        VoxelGrid g = ground();
        tree(g, 1, 0, "oak", 5);
        tree(g, 7, -1, "birch", 6);
        List<Trees.Tree> trees = Trees.find(g);
        assertEquals(2, trees.size(), trees.toString());
        assertEquals("oak", trees.get(0).species().id());
        assertEquals("birch", trees.get(1).species().id());
        assertEquals("oak", Trees.overhead(g, 0, trees).id());
        List<String> facts = Trees.facts(g, 0);
        assertTrue(facts.contains("Над тобой крона: дуба."), facts.toString());
        assertTrue(facts.stream().anyMatch(f -> f.contains("берёза в 7 блоках к востоку")), facts.toString());
        assertFalse(facts.stream().anyMatch(f -> f.contains("роща")), "two trees are not a grove");
    }

    @Test void aGroveIsCountedByKind() {
        VoxelGrid g = ground();
        tree(g, 6, 6, "spruce", 6); tree(g, -6, 6, "spruce", 7); tree(g, 6, -6, "spruce", 5); tree(g, -6, -6, "birch", 5);
        List<String> facts = Trees.facts(g, 0);
        assertTrue(facts.contains("Вокруг роща из 4 деревьев: ели — 3, берёза."), facts.toString());
        assertNull(Trees.overhead(g, 0, Trees.find(g)), "open sky above the creature");
    }

    @Test void logsWithoutACrownOrInTheAirAreNotTrees() {
        VoxelGrid g = ground();
        g.fill(3, 0, 3, 3, 4, 3, Material.WOOD, "minecraft:oak_log"); // a house corner post
        g.fill(-5, 5, -5, -1, 7, -1, Material.LEAVES, "minecraft:oak_leaves");
        g.fill(-3, 3, -3, -3, 6, -3, Material.WOOD, "minecraft:oak_log"); // a branch hanging over air
        tree(g, 8, 8, "acacia", 5);
        g.fill(8, 4, 8, 8, 5, 8, Material.WOOD, "minecraft:birch_log"); // a mismatched top does not make the acacia a birch
        List<Trees.Tree> trees = Trees.find(g);
        assertEquals(1, trees.size(), trees.toString());
        assertEquals("acacia", trees.getFirst().species().id());
    }

    @Test void aTwoByTwoTrunkIsOneTree() {
        VoxelGrid g = ground();
        g.fill(-2, 8 - 3, -2, 3, 8, 3, Material.LEAVES, "minecraft:dark_oak_leaves");
        g.fill(0, 0, 0, 1, 6, 1, Material.WOOD, "minecraft:dark_oak_log");
        List<Trees.Tree> trees = Trees.find(g);
        assertEquals(1, trees.size(), trees.toString());
        assertEquals(4, trees.getFirst().columns());
        assertTrue(Trees.facts(g, 0).stream().anyMatch(f -> f.contains("тёмный дуб прямо здесь") || f.contains("тёмного дуба")), Trees.facts(g, 0).toString());
    }

    @Test void aRoofBetweenHidesTheCrown() {
        VoxelGrid g = ground();
        tree(g, 1, 0, "oak", 6);
        g.fill(-1, 3, -1, 1, 3, 1, Material.SOLID, "minecraft:stone_bricks");
        g.set(1, 3, 0, Material.WOOD, "minecraft:oak_log");
        assertNull(Trees.overhead(g, 0, Trees.find(g)));
    }
}
