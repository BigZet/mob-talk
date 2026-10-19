package io.github.mobtalk.core.place;

/** A finer class of block than {@link Material}, for what a place is for rather than what a body can do in it: logs and planks
 * are both wood to a body, but a log column is a tree and planks are a house; a torch and a rail are both air, but together they
 * are a mine. Decided by the block id alone (no game code), so generated scenes, sampled worlds and the Python tools that read
 * saved regions share it. The ordinal is a classifier input channel after the materials: append, never reorder. */
public enum BlockFamily {
    OTHER, LOG, PLANKS, NATURAL_STONE, SOIL, SAND_GRAVEL, MASONRY, GLASS, WOOL, BED, STORAGE, WORKSTATION, LIGHT, RAIL, CROP,
    ORE, ICE_SNOW, REDSTONE, DECORATION, METAL, WATER_PLANT, COBWEB_SPAWNER, COLORED, SANDSTONE;

    public static final int COUNT = values().length;
    private static final BlockFamily[] BY_ID = values();
    public static BlockFamily of(int id) { return id < 0 || id >= BY_ID.length ? OTHER : BY_ID[id]; }

    private static boolean any(String s, String... parts) { for (String p : parts) if (s.contains(p)) return true; return false; }
    private static boolean is(String s, String... names) { for (String n : names) if (s.equals(n)) return true; return false; }

    /** The family of a block id ("minecraft:oak_planks" or "oak_planks"). Air, water, lava and anything unknown are OTHER. */
    public static BlockFamily of(String id) {
        if (id == null) return OTHER;
        String s = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (s.isEmpty() || s.endsWith("air") || is(s, "water", "lava", "bubble_column")) return OTHER;
        if (s.startsWith("stripped_")) return PLANKS; // stripped logs are cut by hand
        if (s.endsWith("_log") || s.endsWith("_wood") || s.endsWith("_stem") || s.endsWith("_hyphae") || is(s, "mangrove_roots", "muddy_mangrove_roots")) return LOG;
        if (s.endsWith("_bed")) return BED;
        if (is(s, "chest", "trapped_chest", "barrel", "ender_chest") || s.endsWith("shulker_box")) return STORAGE;
        if (is(s, "crafting_table", "furnace", "blast_furnace", "smoker", "anvil", "chipped_anvil", "damaged_anvil", "grindstone", "stonecutter", "loom",
                "lectern", "cartography_table", "fletching_table", "smithing_table", "brewing_stand", "composter", "cauldron", "water_cauldron",
                "lava_cauldron", "powder_snow_cauldron", "enchanting_table", "bell", "beacon", "respawn_anchor", "lodestone", "crafter", "decorated_pot")) return WORKSTATION;
        if (is(s, "rail", "powered_rail", "detector_rail", "activator_rail")) return RAIL;
        if (s.endsWith("torch") || s.endsWith("lantern") || s.endsWith("candle") || s.endsWith("candle_cake") || s.endsWith("froglight")
                || is(s, "glowstone", "sea_lantern", "shroomlight", "redstone_lamp", "end_rod", "jack_o_lantern", "campfire", "soul_campfire")) return LIGHT;
        if (s.endsWith("_ore") || s.startsWith("raw_") && s.endsWith("_block") || is(s, "ancient_debris")) return ORE;
        if (is(s, "wheat", "carrots", "potatoes", "beetroots", "melon", "pumpkin", "carved_pumpkin", "melon_stem", "pumpkin_stem", "attached_melon_stem",
                "attached_pumpkin_stem", "sugar_cane", "hay_block", "farmland", "sweet_berry_bush", "cocoa", "nether_wart", "torchflower_crop", "pitcher_crop")
                || s.startsWith("potted_") || s.equals("flower_pot")) return CROP;
        if (is(s, "ice", "packed_ice", "blue_ice", "snow", "snow_block", "powder_snow", "frosted_ice")) return ICE_SNOW;
        if (s.startsWith("redstone") || s.endsWith("_button") || s.endsWith("_pressure_plate") || is(s, "repeater", "comparator", "lever", "tripwire",
                "tripwire_hook", "target", "daylight_detector", "note_block", "observer", "piston", "sticky_piston", "piston_head", "dispenser", "dropper",
                "hopper", "tnt", "sculk_sensor", "calibrated_sculk_sensor", "lightning_rod")) return REDSTONE;
        if (s.endsWith("_wool") || s.endsWith("_carpet")) return WOOL;
        if (s.endsWith("glass") || s.endsWith("glass_pane")) return GLASS;
        if (s.endsWith("terracotta") || s.endsWith("concrete") || s.endsWith("concrete_powder")) return COLORED;
        if (is(s, "cobweb", "spawner", "trial_spawner", "vault", "infested_stone", "infested_stone_bricks")) return COBWEB_SPAWNER;
        if (s.contains("sandstone")) return SANDSTONE;
        if (s.startsWith("iron_") || s.startsWith("gold_block") || is(s, "chain", "iron_bars", "copper_block", "heavy_core") || s.contains("copper")) return METAL;
        if (s.endsWith("_sign") || s.endsWith("_banner") || s.endsWith("_head") || s.endsWith("_skull") || is(s, "bookshelf", "chiseled_bookshelf",
                "ladder", "scaffolding", "armor_stand", "painting", "item_frame", "flower_pot", "skeleton_skull")) return DECORATION;
        if (s.endsWith("_planks") || s.endsWith("_door") && !s.startsWith("iron") || s.endsWith("_trapdoor") && !s.startsWith("iron") || s.endsWith("_fence")
                && !s.startsWith("nether_brick") || s.endsWith("_fence_gate") || any(s, "oak_", "spruce_", "birch_", "jungle_", "acacia_", "cherry_",
                "mangrove_", "bamboo_", "crimson_", "warped_") && (s.endsWith("_stairs") || s.endsWith("_slab")) || is(s, "bamboo_block", "bamboo_mosaic")) return PLANKS;
        if (is(s, "kelp", "kelp_plant", "seagrass", "tall_seagrass", "sea_pickle", "lily_pad") || s.contains("coral")) return WATER_PLANT;
        if (is(s, "stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "netherrack", "basalt", "blackstone", "end_stone", "dripstone_block",
                "pointed_dripstone", "obsidian", "crying_obsidian", "bedrock", "magma_block", "soul_soil", "smooth_basalt", "amethyst_block", "budding_amethyst",
                "moss_block", "sculk", "sculk_vein", "glow_lichen", "crimson_nylium", "warped_nylium", "nether_wart_block", "warped_wart_block")) return NATURAL_STONE;
        if (is(s, "dirt", "grass_block", "podzol", "mycelium", "mud", "coarse_dirt", "rooted_dirt", "clay", "dirt_path", "packed_mud")) return SOIL;
        if (is(s, "sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel", "soul_sand")) return SAND_GRAVEL;
        if (s.contains("brick") || s.contains("cobble") || s.startsWith("polished_") || s.startsWith("smooth_") || s.startsWith("chiseled_") || s.startsWith("cut_")
                || s.endsWith("_tiles") || s.endsWith("_stairs") || s.endsWith("_slab") || s.endsWith("_wall") || is(s, "purpur_block", "purpur_pillar", "quartz_block",
                "quartz_pillar", "prismarine", "dark_prismarine", "reinforced_deepslate")) return MASONRY;
        return OTHER;
    }
}
