package io.github.mobtalk.core.place;

import java.util.Locale;
import java.util.Map;

/** Russian words for the blocks a creature is most likely to name; anything else keeps its id without the namespace. */
public final class BlockNames {
    private BlockNames() { }
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("bedrock", "бедрок"), Map.entry("obsidian", "обсидиан"), Map.entry("crying_obsidian", "плачущий обсидиан"),
            Map.entry("reinforced_deepslate", "укреплённый сланец"), Map.entry("barrier", "невидимый барьер"),
            Map.entry("glass", "стекло"), Map.entry("glass_pane", "стеклянная панель"), Map.entry("iron_bars", "железная решётка"),
            Map.entry("tinted_glass", "тёмное стекло"), Map.entry("water", "вода"), Map.entry("lava", "лава"),
            Map.entry("stone", "камень"), Map.entry("cobblestone", "булыжник"), Map.entry("deepslate", "сланец"), Map.entry("cobbled_deepslate", "сланцевый булыжник"),
            Map.entry("dirt", "земля"), Map.entry("grass_block", "дёрн"), Map.entry("sand", "песок"), Map.entry("gravel", "гравий"),
            Map.entry("netherrack", "адский камень"), Map.entry("stone_bricks", "каменные кирпичи"), Map.entry("bricks", "кирпичи"),
            Map.entry("oak_planks", "доски"), Map.entry("spruce_planks", "еловые доски"), Map.entry("oak_log", "бревно"), Map.entry("spruce_log", "еловое бревно"),
            Map.entry("oak_leaves", "листва"), Map.entry("oak_door", "дверь"), Map.entry("iron_door", "железная дверь"),
            Map.entry("oak_trapdoor", "люк"), Map.entry("oak_fence", "забор"), Map.entry("oak_fence_gate", "калитка"),
            Map.entry("cobblestone_wall", "каменная ограда"), Map.entry("ladder", "лестница"), Map.entry("vine", "лианы"),
            Map.entry("scaffolding", "леса"), Map.entry("fire", "огонь"), Map.entry("soul_fire", "синий огонь"), Map.entry("magma_block", "магма"),
            Map.entry("cactus", "кактус"), Map.entry("sweet_berry_bush", "ягодный куст"), Map.entry("campfire", "костёр"),
            Map.entry("pointed_dripstone", "сталактит"), Map.entry("wheat", "пшеница"), Map.entry("carrots", "морковь"),
            Map.entry("potatoes", "картошка"), Map.entry("beetroots", "свёкла"), Map.entry("farmland", "грядка"), Map.entry("short_grass", "трава"),
            Map.entry("tall_grass", "высокая трава"), Map.entry("piston", "поршень"), Map.entry("sticky_piston", "липкий поршень"),
            Map.entry("dispenser", "раздатчик"), Map.entry("hopper", "воронка-бункер"), Map.entry("tnt", "динамит"), Map.entry("observer", "наблюдатель"),
            Map.entry("hay_block", "сено"), Map.entry("snow_block", "снег"), Map.entry("ice", "лёд"), Map.entry("packed_ice", "плотный лёд"),
            Map.entry("chest", "сундук"), Map.entry("crafting_table", "верстак"), Map.entry("furnace", "печь"), Map.entry("bookshelf", "книжный шкаф"));

    public static String russian(String id) {
        if (id == null) return "блок";
        String bare = id.startsWith("minecraft:") ? id.substring(10) : id;
        String known = NAMES.get(bare);
        if (known != null) return known;
        if (bare.endsWith("_door")) return "дверь";
        if (bare.endsWith("_trapdoor")) return "люк";
        if (bare.endsWith("_fence_gate")) return "калитка";
        if (bare.endsWith("_fence")) return "забор";
        if (bare.endsWith("_wall")) return "ограда";
        if (bare.endsWith("_leaves")) return "листва";
        if (bare.endsWith("_log") || bare.endsWith("_wood") || bare.endsWith("_stem")) return "бревно";
        if (bare.endsWith("_planks")) return "доски";
        if (bare.endsWith("_glass") || bare.endsWith("_glass_pane")) return "цветное стекло";
        if (bare.endsWith("_ore")) return "руда";
        if (bare.endsWith("_wool")) return "шерсть";
        return bare.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
