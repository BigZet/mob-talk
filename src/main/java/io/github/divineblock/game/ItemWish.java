package io.github.divineblock.game;

import java.util.List;
import java.util.Locale;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/** Finds the item a prayer names in the game's own registry (mod items included), by its shown name in the game's language
 * or its id, and decides how many the god's power pays for. Operator and creative-only items are never given. */
final class ItemWish {
    private static final List<String> FORBIDDEN_PARTS = List.of("command_block", "structure_", "spawn_egg");
    private static final java.util.Set<String> FORBIDDEN = java.util.Set.of("jigsaw", "barrier", "debug_stick", "bedrock", "knowledge_book", "end_portal_frame",
            "reinforced_deepslate", "light", "spawner", "trial_spawner", "vault", "petrified_oak_slab", "air", "budding_amethyst");

    record Wish(Item item, int count, boolean tooGreedy) { }

    private ItemWish() { }

    static boolean forbidden(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        String path = id.getPath();
        if (item == Items.AIR) return true;
        if (FORBIDDEN.contains(path)) return true;
        for (String banned : FORBIDDEN_PARTS) if (path.contains(banned)) return true;
        return false;
    }

    /** The item the words name, the longest name winning ("алмазный меч" over "алмаз"); null when none fits. */
    static Item find(String prayer) {
        if (prayer == null || prayer.isBlank()) return null;
        String text = " " + prayer.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ") + " ";
        Item best = null; int bestLength = 0;
        for (Item item : Registries.ITEM) {
            if (forbidden(item)) continue;
            String shown = new ItemStack(item).getName().getString().toLowerCase(Locale.ROOT);
            String path = Registries.ITEM.getId(item).getPath().replace('_', ' ');
            for (String name : new String[] { shown, path }) {
                if (name.length() < 3 || name.length() <= bestLength) continue;
                if (matches(text, name)) { best = item; bestLength = name.length(); }
            }
        }
        return best;
    }

    /** A name matches when every word of it starts a word of the prayer, allowing Russian endings ("алмазов" for "алмаз"). */
    static boolean matches(String text, String name) {
        for (String word : name.split(" ")) {
            if (word.isBlank()) continue;
            String stem = word.length() > 5 ? word.substring(0, word.length() - 2) : word;
            if (!text.contains(" " + stem)) return false;
        }
        return true;
    }

    /** How mighty an item is: tier 1 common things .. tier 4 the near-unobtainable. */
    static int tier(Item item) {
        String path = Registries.ITEM.getId(item).getPath();
        if (path.contains("netherite") || path.equals("elytra") || path.equals("beacon") || path.equals("nether_star") || path.equals("dragon_egg")
                || path.equals("enchanted_golden_apple") || path.equals("totem_of_undying") || path.equals("heart_of_the_sea")) return 4;
        if (path.equals("diamond_block") || path.equals("emerald_block") || path.equals("ancient_debris") || path.equals("trident") || path.contains("shulker")) return 3;
        if (path.contains("diamond") || path.contains("emerald") || path.equals("golden_apple") || path.contains("gold_block")) return 2;
        Rarity rarity = new ItemStack(item).getRarity();
        return switch (rarity) { case EPIC -> 4; case RARE -> 3; case UNCOMMON -> 2; default -> 1; };
    }

    /** What the god grants for a named item at a power: the item when its tier fits, else a lesser default gift. */
    static Wish grant(Item named, int power) {
        if (named != null && tier(named) <= power) {
            int base = power <= 1 ? 4 : power == 2 ? 12 : 32;
            int count = Math.max(1, Math.min(new ItemStack(named).getMaxCount(), base / tier(named)));
            return new Wish(named, count, false);
        }
        Item fallback = switch (Math.max(1, Math.min(3, power))) { case 1 -> Items.BREAD; case 2 -> Items.GOLDEN_APPLE; default -> Items.DIAMOND; };
        int count = power <= 1 ? 6 : power == 2 ? 2 : 3;
        return new Wish(fallback, count, named != null);
    }
}
