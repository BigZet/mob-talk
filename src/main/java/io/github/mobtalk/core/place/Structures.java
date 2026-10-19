package io.github.mobtalk.core.place;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Vanilla structures around a creature, read from the world's structure registry rather than guessed from blocks: which
 * structure (a plains village, a mineshaft, a pillager outpost) and which piece of it (a church, a smithy, a corridor) the
 * creature stands in or next to. The server side collects {@link Piece}s; this class names them in Russian and turns them into
 * a few short facts. Unknown structures and pieces fall back to their ids. */
public final class Structures {
    private Structures() { }

    /** How far around the creature pieces are reported. */
    public static final int RADIUS = 24;

    /** One structure piece near the creature. {@code dx, dy, dz} point from the creature's feet to the nearest cell of the piece's
     * box (all zero when inside); {@code inStart} says the creature is within the whole structure's box. */
    public record Piece(String structure, String pieceType, String template, int dx, int dy, int dz, boolean inside, boolean inStart, int volume) {
        public int distance() { return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))); }
        public String direction() {
            if (dx == 0 && dz == 0) return dy < 0 ? "под тобой" : "над тобой";
            String side = Math.abs(dx) >= Math.abs(dz) ? dx > 0 ? "к востоку" : "к западу" : dz > 0 ? "к югу" : "к северу";
            return side + (dy >= 3 ? ", выше" : dy <= -3 ? ", ниже" : "");
        }
    }

    private static final Map<String, String> STRUCTURES = Map.ofEntries(
            Map.entry("village_plains", "равнинная деревня"), Map.entry("village_desert", "пустынная деревня"),
            Map.entry("village_savanna", "саванная деревня"), Map.entry("village_snowy", "снежная деревня"),
            Map.entry("village_taiga", "таёжная деревня"), Map.entry("pillager_outpost", "аванпост разбойников"),
            Map.entry("mineshaft", "заброшенная шахта"), Map.entry("mineshaft_mesa", "заброшенная шахта"),
            Map.entry("stronghold", "подземная крепость"), Map.entry("fortress", "адская крепость"),
            Map.entry("bastion_remnant", "бастион"), Map.entry("desert_pyramid", "пустынный храм"),
            Map.entry("jungle_pyramid", "храм в джунглях"), Map.entry("swamp_hut", "хижина ведьмы"), Map.entry("igloo", "иглу"),
            Map.entry("ruined_portal", "разрушенный портал"), Map.entry("ruined_portal_desert", "разрушенный портал"),
            Map.entry("ruined_portal_jungle", "разрушенный портал"), Map.entry("ruined_portal_swamp", "разрушенный портал"),
            Map.entry("ruined_portal_mountain", "разрушенный портал"), Map.entry("ruined_portal_ocean", "разрушенный портал"),
            Map.entry("ruined_portal_nether", "разрушенный портал"), Map.entry("shipwreck", "затонувший корабль"),
            Map.entry("shipwreck_beached", "выброшенный на берег корабль"), Map.entry("monument", "подводный монумент"),
            Map.entry("ocean_ruin_cold", "подводные руины"), Map.entry("ocean_ruin_warm", "подводные руины"),
            Map.entry("mansion", "лесной особняк"), Map.entry("ancient_city", "древний город"), Map.entry("trail_ruins", "древние руины"),
            Map.entry("trial_chambers", "испытательные палаты"), Map.entry("buried_treasure", "клад"), Map.entry("end_city", "город Края"),
            Map.entry("nether_fossil", "окаменелость"));

    /** Template path fragments and what the piece is, most specific first. */
    private static final String[][] TEMPLATE_WORDS = {
            {"fountain", "колодец"}, {"meeting_point", "площадь"}, {"town_center", "площадь"}, {"streets", "улица"}, {"temple", "церковь"},
            {"weaponsmith", "кузница"}, {"armorer", "кузница"}, {"tool_smith", "кузница"}, {"toolsmith", "кузница"}, {"library", "библиотека"},
            {"cartographer", "дом картографа"}, {"butcher", "мясная лавка"}, {"fisher", "дом рыбака"}, {"fletcher", "дом лучника"},
            {"mason", "дом каменщика"}, {"shepherd", "дом пастуха"}, {"tannery", "кожевня"}, {"animal_pen", "загон"}, {"hoglin_stable", "загон хоглинов"}, {"stable", "конюшня"},
            {"large_farm", "поле"}, {"small_farm", "огород"}, {"farm", "поле"}, {"lamp", "фонарь"}, {"accessory", "украшение"},
            {"well", "колодец"}, {"house", "дом"}, {"hut", "хижина"}, {"cottage", "дом"},
            {"watchtower", "сторожевая башня"}, {"feature_cage", "клетка"}, {"feature_tent", "палатка"}, {"feature_logs", "поленница"},
            {"feature_targets", "мишени"}, {"feature_plate", "площадка"}, {"base_plate", "площадка"},
            {"treasure", "сокровищница"}, {"bastion/bridge", "мост"}, {"bastion/units", "жилой блок"},
            {"rampart", "крепостной вал"}, {"city_center", "центр города"}, {"ancient_city/walls", "стена"}, {"ancient_city/structures", "постройка"},
            {"trail_ruins/tower", "башня"}, {"trail_ruins/roads", "дорога"}, {"trail_ruins/buildings", "здание"},
            {"trial_chambers/corridor", "коридор"}, {"hallway", "коридор"}, {"intersection", "перекрёсток"}, {"trial_chambers/chamber", "зал"},
            {"atrium", "атриум"}, {"entrance", "вход"}, {"igloo/top", "иглу"}, {"igloo/middle", "лаз вниз"}, {"igloo/bottom", "подвал"},
            {"shipwreck", "корабль"}, {"underwater_ruin", "руины"}, {"ruined_portal", "портал"}, {"nether_fossils", "скелет"},
            {"end_city/ship", "корабль Края"}, {"end_city/tower", "башня"}, {"end_city/bridge", "мост"}, {"end_city/base", "основание башни"},
            {"woodland_mansion", "особняк"}};

    /** Piece types of the hand-coded structures (mineshafts, strongholds, fortresses, temples). */
    private static final Map<String, String> PIECE_TYPES = Map.ofEntries(
            Map.entry("mscorridor", "коридор"), Map.entry("mscrossing", "перекрёсток"), Map.entry("msroom", "комната"), Map.entry("msstairs", "лестница"),
            Map.entry("shcc", "коридор"), Map.entry("shfc", "коридор"), Map.entry("sh5c", "перекрёсток"), Map.entry("shlt", "поворот"),
            Map.entry("shrt", "поворот"), Map.entry("shrc", "комната"), Map.entry("shsd", "лестница"), Map.entry("shssd", "винтовая лестница"),
            Map.entry("shs", "лестница"), Map.entry("shstart", "лестница"), Map.entry("shli", "библиотека"), Map.entry("shph", "комната с порталом"),
            Map.entry("shpr", "тюремная камера"), Map.entry("nebcr", "мост"), Map.entry("nebef", "мост"), Map.entry("nebs", "мост"),
            Map.entry("nebsr", "мост"), Map.entry("nesr", "мост"), Map.entry("nestart", "мост"), Map.entry("nemt", "площадка с рассадником"),
            Map.entry("necsr", "комната с адским наростом"), Map.entry("nece", "коридор"), Map.entry("necca", "коридор"), Map.entry("nerc", "перекрёсток"),
            Map.entry("nesc", "коридор"), Map.entry("nesclt", "поворот"), Map.entry("nesccrt", "поворот"), Map.entry("necsc", "лестница"),
            Map.entry("necctb", "коридор"), Map.entry("tedp", "храм"), Map.entry("tejp", "храм"), Map.entry("tesh", "хижина"), Map.entry("iglu", "иглу"),
            Map.entry("omb", "монумент"), Map.entry("omcr", "зал"), Map.entry("omdxr", "зал"), Map.entry("omdxyr", "зал"), Map.entry("omdyr", "зал"),
            Map.entry("omdyzr", "зал"), Map.entry("omdzr", "зал"), Map.entry("omentry", "вход"), Map.entry("ompenthouse", "верхний зал"),
            Map.entry("omsimple", "зал"), Map.entry("omsimplet", "зал"), Map.entry("omwr", "крыло"), Map.entry("btp", "клад"));

    /** Pieces that are ground rather than a place: streets, lamps, decorations. Named when the creature stands on them, never listed as "nearby". */
    private static final Set<String> MINOR = Set.of("улица", "фонарь", "украшение", "дорога", "поленница", "мишени", "площадка");

    public static String bare(String id) { return id == null ? "" : id.startsWith("minecraft:") ? id.substring(10) : id; }

    /** "равнинная деревня", or the id for a structure the table does not know. */
    public static String structureName(String structure) {
        String bare = bare(structure);
        String known = STRUCTURES.get(bare);
        if (known != null) return known;
        if (bare.startsWith("village_")) return "деревня";
        if (bare.startsWith("ruined_portal")) return "разрушенный портал";
        if (bare.startsWith("shipwreck")) return "затонувший корабль";
        return bare.substring(bare.indexOf(':') + 1).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    /** "кузница", "коридор"; null when nothing more specific than the structure itself is known. */
    public static String pieceName(String pieceType, String template) {
        String path = template == null ? "" : bare(template).toLowerCase(Locale.ROOT);
        if (!path.isEmpty()) {
            if (path.contains("/zombie/")) path = path.replace("/zombie/", "/");
            for (String[] word : TEMPLATE_WORDS) if (path.contains(word[0])) return word[1];
        }
        String type = bare(pieceType).toLowerCase(Locale.ROOT);
        String known = PIECE_TYPES.get(type);
        if (known != null) return known;
        if (type.startsWith("ne") && type.contains("b")) return "мост";
        return null;
    }

    /** Whether a village piece carries no building: a street, a lamp, a decoration. */
    public static boolean minor(Piece piece) {
        String name = pieceName(piece.pieceType, piece.template);
        return name != null && MINOR.contains(name);
    }

    private static String label(Piece piece) {
        String structure = structureName(piece.structure), name = pieceName(piece.pieceType, piece.template);
        return name == null || name.equals(structure) ? structure : name + " (" + structure + ")";
    }

    /** At most three short lines: what the creature is inside, what territory it is on, what stands nearby. */
    public static List<String> facts(List<Piece> pieces) {
        List<String> out = new ArrayList<>();
        if (pieces == null || pieces.isEmpty()) return out;
        // Inside: the smallest box that holds the creature, buildings before streets.
        Piece inside = pieces.stream().filter(Piece::inside)
                .min(Comparator.comparing((Piece p) -> minor(p)).thenComparingInt(Piece::volume)).orElse(null);
        Set<String> said = new LinkedHashSet<>();
        if (inside != null) {
            String name = pieceName(inside.pieceType, inside.template), structure = structureName(inside.structure);
            out.add(minor(inside) ? "Ты на территории: " + structure + (name == null ? "" : ", " + name) + "."
                    : "Ты внутри: " + (name == null || name.equals(structure) ? structure : name + " — " + structure) + ".");
            said.add(label(inside));
        } else {
            pieces.stream().filter(Piece::inStart).findFirst().ifPresent(p -> {
                out.add("Ты на территории: " + structureName(p.structure) + ".");
                said.add(structureName(p.structure));
            });
        }
        List<String> near = new ArrayList<>();
        pieces.stream().filter(p -> !p.inside && !minor(p) && p.distance() <= RADIUS).sorted(Comparator.comparingInt(Piece::distance)).forEach(p -> {
            String label = label(p);
            if (near.size() >= 3 || !said.add(label)) return;
            near.add(label + " в " + p.distance() + " " + Place.plural(p.distance(), "блоке", "блоках", "блоках") + " " + p.direction());
        });
        if (!near.isEmpty()) out.add("Рядом: " + String.join("; ", near) + ".");
        return out;
    }
}
