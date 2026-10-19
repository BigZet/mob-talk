package io.github.mobtalk.core.place;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Block families by id: what a block says about the purpose of a place. */
class BlockFamilyTest {
    @Test void familiesSeparateWhatMaterialsMerge() {
        assertEquals(BlockFamily.LOG, BlockFamily.of("minecraft:oak_log"));
        assertEquals(BlockFamily.PLANKS, BlockFamily.of("minecraft:oak_planks"));
        assertEquals(BlockFamily.PLANKS, BlockFamily.of("minecraft:stripped_birch_log"), "stripped logs are cut by hand");
        assertEquals(BlockFamily.PLANKS, BlockFamily.of("minecraft:spruce_stairs"));
        assertEquals(BlockFamily.MASONRY, BlockFamily.of("minecraft:stone_brick_stairs"));
        assertEquals(BlockFamily.MASONRY, BlockFamily.of("minecraft:cobblestone"));
        assertEquals(BlockFamily.NATURAL_STONE, BlockFamily.of("minecraft:deepslate"));
        assertEquals(BlockFamily.NATURAL_STONE, BlockFamily.of("minecraft:smooth_basalt"), "geodes are nature");
        assertEquals(BlockFamily.RAIL, BlockFamily.of("minecraft:powered_rail"));
        assertEquals(BlockFamily.LIGHT, BlockFamily.of("minecraft:wall_torch"));
        assertEquals(BlockFamily.LIGHT, BlockFamily.of("minecraft:redstone_torch"));
        assertEquals(BlockFamily.BED, BlockFamily.of("minecraft:red_bed"));
        assertEquals(BlockFamily.STORAGE, BlockFamily.of("minecraft:barrel"));
        assertEquals(BlockFamily.ORE, BlockFamily.of("minecraft:deepslate_redstone_ore"));
        assertEquals(BlockFamily.SANDSTONE, BlockFamily.of("minecraft:cut_sandstone"));
        assertEquals(BlockFamily.METAL, BlockFamily.of("minecraft:iron_door"));
        assertEquals(BlockFamily.COLORED, BlockFamily.of("minecraft:white_concrete"));
        assertEquals(BlockFamily.OTHER, BlockFamily.of("minecraft:water"));
        assertEquals(BlockFamily.OTHER, BlockFamily.of("minecraft:cave_air"));
        assertEquals(BlockFamily.OTHER, BlockFamily.of(null));
    }

    @Test void familyCubeFollowsBlockNames() {
        VoxelGrid g = new VoxelGrid(12, 8);
        g.set(0, -1, 0, Material.WOOD, "minecraft:oak_planks");
        g.set(1, 0, 0, Material.AIR, "minecraft:rail");
        byte[] fam = g.familyCube();
        assertEquals(BlockFamily.PLANKS.ordinal(), fam[((8) * 16 + 6) * 16 + 8]);
        assertEquals(BlockFamily.RAIL.ordinal(), fam[((9) * 16 + 7) * 16 + 8]);
        assertEquals(BlockFamily.OTHER.ordinal(), fam[0]);
    }
}
