package io.github.divineblock.core;

import java.util.Map;

/** What gifts thrown on the altar are worth to the god, by item id. A diamond weighs more than bread; rotten flesh insults. */
public final class Offerings {
    private static final Map<String, Integer> VALUE = Map.ofEntries(
            Map.entry("minecraft:nether_star", 80), Map.entry("minecraft:dragon_egg", 100), Map.entry("minecraft:enchanted_golden_apple", 60),
            Map.entry("minecraft:netherite_ingot", 40), Map.entry("minecraft:netherite_block", 300), Map.entry("minecraft:diamond_block", 90),
            Map.entry("minecraft:diamond", 10), Map.entry("minecraft:emerald_block", 45), Map.entry("minecraft:emerald", 5),
            Map.entry("minecraft:golden_apple", 12), Map.entry("minecraft:gold_block", 27), Map.entry("minecraft:gold_ingot", 3),
            Map.entry("minecraft:iron_block", 9), Map.entry("minecraft:iron_ingot", 1), Map.entry("minecraft:totem_of_undying", 30),
            Map.entry("minecraft:heart_of_the_sea", 30), Map.entry("minecraft:echo_shard", 6), Map.entry("minecraft:amethyst_shard", 1),
            Map.entry("minecraft:lapis_lazuli", 1), Map.entry("minecraft:cake", 6), Map.entry("minecraft:honey_bottle", 2),
            Map.entry("minecraft:bread", 1), Map.entry("minecraft:cooked_beef", 1), Map.entry("minecraft:golden_carrot", 2),
            Map.entry("minecraft:wheat", 0), Map.entry("minecraft:poppy", 1), Map.entry("minecraft:sunflower", 1), Map.entry("minecraft:candle", 1));
    private static final java.util.Set<String> INSULTS = java.util.Set.of("minecraft:rotten_flesh", "minecraft:poisonous_potato", "minecraft:spider_eye",
            "minecraft:dirt", "minecraft:bone", "minecraft:pufferfish", "minecraft:dead_bush");

    /** The worth of a pile; values grow with the count but a stack of junk never outweighs one real gift. */
    public static int value(String itemId, int count) {
        Integer each = VALUE.get(itemId);
        if (each == null) return 0;
        return (int) Math.min(400, (long) each * Math.max(0, count));
    }
    public static boolean insults(String itemId) { return INSULTS.contains(itemId); }

    private Offerings() { }
}
