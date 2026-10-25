package io.github.mobtalk.core;

import java.util.Set;
import java.util.UUID;

/** What the world watch measured around one mob, for {@link ConversationStore#surroundings}: the lasting factors the game sees
 * directly (weather, night outdoors, monsters, an alarm, a wound, no bed or work), the creatures standing near (friends and
 * family are looked up in the store), and for a villager its sleep and whether there is food in its inventory. {@code night}
 * is the index of the last night (it stays the same through the following day), {@code dark} whether it is night now. */
public record MoodSample(Set<MoodFactor> factors, Set<UUID> near, boolean villager, boolean sleeping, boolean hasFood, long night, boolean dark) {
    public MoodSample {
        factors = factors == null ? Set.of() : Set.copyOf(factors);
        near = near == null ? Set.of() : Set.copyOf(near);
    }
    private static final Set<String> FOOD = Set.of("bread", "potato", "baked_potato", "carrot", "beetroot", "apple", "golden_apple", "golden_carrot",
            "cookie", "pumpkin_pie", "cake", "melon_slice", "sweet_berries", "glow_berries", "mushroom_stew", "beetroot_soup", "rabbit_stew",
            "suspicious_stew", "honey_bottle", "dried_kelp", "pumpkin", "melon", "wheat");
    /** Whether a villager would call this item a meal (an id with or without the minecraft: namespace; other mods' cooked food counts). */
    public static boolean food(String itemId) {
        if (itemId == null) return false;
        String id = itemId.substring(itemId.indexOf(':') + 1);
        return FOOD.contains(id) || id.startsWith("cooked_");
    }
    /** The night index of a world time in ticks: from dusk of one day through the next day. */
    public static long nightOf(long timeOfDay) { return Math.floorDiv(timeOfDay - 12_000L, 24_000L); }
}
