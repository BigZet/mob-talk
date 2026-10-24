package io.github.divineblock.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.divineblock.boss.BossKind;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The two layers of {@link DivineConfig} on a server. Personal fields belong to each player's own game (how the god sounds
 * and looks to him, his microphone and Whisper) and stay in his F9; every other field is the server's: the host or the web
 * admin sets it, and a player's F9 shows it read-only. This class lists the server's fields with their titles, types and
 * bounds (the groups follow F9), reads them as JSON and applies a JSON patch that refuses unknown keys, personal keys and
 * values out of bounds instead of clamping them. A {@code flags} field is a list of switches: described with every option
 * (id, Russian title, help, group), valued as the comma-joined ids switched off. Plain Java, for the tests and the web admin. */
public final class DivineSettings {
    /** Fields that stay with the player. The god's voice is synthesised by each player's own game for now, so his voice and
     * its model are personal too; they move to the server once the server speaks for him. */
    public static final Set<String> PERSONAL = Set.of("voice", "voiceTemple", "voiceRoomSize", "voiceReverb", "voiceEchoMs", "voiceEchoLevel",
            "voiceEchoRepeats", "chantVolume", "effects", "cameraShake", "voiceId", "ttsModel", "whisperUrl", "whisperAutoStart", "microphoneName",
            "rouletteShow", "rouletteVolume", "rouletteJackpotFx", "rouletteFx");

    public enum Type { INT, BOOL, ENUM, STRING, LIST, FLAGS;
        String id() { return switch (this) { case INT -> "int"; case BOOL -> "bool"; case ENUM -> "enum"; case STRING, LIST -> "string"; case FLAGS -> "flags"; }; } }

    /** One switch of a {@code flags} field: the id that goes into the list when it is off, its Russian title and help, and
     * the group it is shown in (all of a group come together). */
    public record Flag(String id, String title, String help, String group, String groupTitle) { }

    /** One server field: its JSON key (the field's name), group, title, type and bounds; {@code flags} for {@link Type#FLAGS}. */
    public record Setting(String key, String group, String title, Type type, int min, int max, int step, List<String> options, String help, List<Flag> flags) { }

    /** «Бог с булавой» in {@code disabledBosses}: the god himself, not a boss (the same id as {@code DivineBosses.GOD_AVATAR_ID}). */
    public static final String GOD_AVATAR_ID = "god_avatar";
    public record Group(String id, String title) { }

    public static final List<Group> GROUPS = List.of(new Group("god", "Характер бога"), new Group("speech", "Речь и решения"),
            new Group("world", "Мир"), new Group("wrath", "Кары"), new Group("roulette", "Рулетка кары"), new Group("bosses", "Боссы"));

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Gson GSON = new Gson();
    public static final List<Setting> SERVER = List.of(
            choice("personality", "god", "Нрав", "Как бог отвечает на молитвы и обиды.", names(DivineConfig.Personality.values())),
            number("maxTier", "god", "Потолок кар", "Самая тяжёлая кара: 0 — знамения … 4 — апокалипсис.", 0, 4, 1),
            number("whimWeight", "god", "Прихоть бога", "Насколько часто бог поступает по своей прихоти, а не по заслугам; 0 — предсказуем.", 0, 3, 1),
            number("cooldownSeconds", "god", "Пауза алтаря, с", "Сколько ждать между молитвами у алтаря.", 0, 600, 1),
            flag("allowWither", "god", "Визеры", "Бог может призвать визеров."),
            flag("allowDragon", "god", "Драконы", "Бог может призвать драконов Края вне Края."),
            flag("protectBlocks", "god", "Беречь блоки", "Кары не ломают блоки вовсе."),
            number("protectRadius", "god", "Радиус дома", "Кары по дому бьют; великая воронка открывается на столько блоков дальше кровати, а пруд у дома потоп не осушает.", 0, 256, 1),
            flag("debug", "god", "Отладка", "Показывать игрокам числа вердикта и служебные строки."),
            choice("provider", "speech", "Речь", "Кто пишет слова бога: OpenRouter или Venice с запасным OpenRouter.", names(DivineConfig.Provider.values())),
            flag("useJev", "speech", "Решатель Jev", "Решения бога принимает отдельная быстрая модель."),
            text("chatModel", "speech", "Модель речи OpenRouter", "Модель OpenRouter для слов бога."),
            text("veniceModel", "speech", "Модель речи Venice", "Модель Venice для слов бога."),
            number("speechTimeoutSeconds", "speech", "Ожидание речи, с", "Сколько ждать модель, прежде чем бог скажет по шаблону.", 3, 60, 1),
            flag("churches", "world", "Церкви в мире", "Церкви появляются в новых чанках."),
            number("churchSpacing", "world", "Редкость церквей", "Не больше одной церкви на столько чанков по каждой стороне.", 32, 256, 1),
            choice("churchVariant", "world", "Вид церквей", "Пусто — все вперемешку, иначе один вид.", List.of("", "STONE", "WOOD", "SLAVIC")),
            flag("churchGuardians", "bosses", "Страж в церкви", "Когда игрок подходит к алтарю, из печати под ним встаёт страж церкви. В церкви у спавна — никогда."),
            flag("spawnAtChurch", "world", "Спавн у Славянской церкви", "Новый мир начинается у входа в Славянскую церковь: она ставится у спавна, туда приходят новые игроки."),
            flag("escalation", "wrath", "Кара за карой хуже", "Каждая следующая кара тяжелее прежней."),
            flag("lingeringCurses", "wrath", "Долгие проклятия", "Проклятия держатся срок ниже; выкл — три минуты."),
            number("curseMinutes", "wrath", "Срок проклятий, мин", "Сколько минут реального времени держится проклятие; дольше 20 минут не бывает.",
                    CurseTerm.MIN_MINUTES, CurseTerm.MAX_MINUTES, 1),
            flag("landscapeWrath", "wrath", "Кары меняют ландшафт", "Воронки, разломы, потопы; выкл ставит на их место другую кару той же группы."),
            flag("allowEternalBan", "wrath", "Вечное изгнание", "За четвёртую хулу бог банит игрока на сервере навсегда; снимает админ."),
            flag("banBackup", "wrath", "Копия мира перед изгнанием", "Перед баном сохраняется копия мира."),
            flag("rouletteEnabled", "roulette", "Рулетка кары", "Перед карой три барабана крутятся и встают на её символ; 777 — конец света."),
            choice("rouletteMode", "roulette", "Длительность рулетки", "SHORT — около 3 с, NORMAL — около 6,5 с, DRAMATIC — около 9 с.",
                    List.of(Roulette.Mode.SHORT.name(), Roulette.Mode.NORMAL.name(), Roulette.Mode.DRAMATIC.name())),
            choice("rouletteAudience", "roulette", "Кому показывать", "SINNER — только грешнику, RADIUS — игрокам рядом, ALL — всем на сервере.",
                    names(Roulette.Audience.values())),
            number("rouletteRadius", "roulette", "Радиус показа, блоков", "Игроки ближе видят рулетку чужой кары («Кара для …»).", 16, 256, 1),
            flag("rouletteTease", "roulette", "Дразнилка «почти 777»", "Для кар К3–К4 третий барабан иногда медленно проползает мимо семёрки."),
            number("rouletteTeaseChance", "roulette", "Шанс дразнилки, %", "Как часто третий барабан дразнит семёркой.", 0, 100, 1),
            number("rouletteMercyChance", "roulette", "Случайное помилование, %",
                    "Как часто третий барабан в последний миг проскакивает на голубя и кара не падает (ступень гнева не сбрасывается); на вечное изгнание не действует.", 0, 25, 1),
            choice("rouletteTails", "roulette", "Рулетка продолжений", "Перед второй волной и повторным толчком: OFF — нет, MINI — один барабан на 2 с, FULL — полная.",
                    names(Roulette.Tails.values())),
            flag("rouletteAdmin", "roulette", "Кары из админки через рулетку", "Деяние гнева, посланное из веб-админки, тоже падает после рулетки."),
            flags("disabled", "wrath", "Деяния бога", "Выключенное деяние не выпадает: бог берёт другое из той же группы.", deedFlags()),
            flags("disabledBosses", "bosses", "Боссы", "Вместо выключенного босса приходит усиленный обычный моб с его именем.", bossFlags()),
            number("bossDifficulty", "bosses", "Сила боссов, %", "Здоровье и урон боссов относительно обычного.", 50, 300, 1),
            flag("bossBlockDamage", "bosses", "Боссы ломают блоки", "Удары и ульты боссов могут разрушать блоки."));

    private DivineSettings() { }

    public static boolean personal(String field) { return PERSONAL.contains(field); }

    /** Every settable field of {@link DivineConfig}, by name: what the tests check against the two lists. */
    public static List<String> allFields() {
        List<String> out = new ArrayList<>();
        for (Field field : DivineConfig.class.getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())) out.add(field.getName());
        return out;
    }

    public static Setting setting(String key) {
        for (Setting setting : SERVER) if (setting.key().equals(key)) return setting;
        return null;
    }

    /** {@code {"id":"divineblock","title":…,"groups":[{"id","title","fields":[…]}]}} for the web admin. */
    public static JsonObject describe() {
        JsonObject out = new JsonObject();
        out.addProperty("id", "divineblock");
        out.addProperty("title", "Божественный блок");
        JsonArray groups = new JsonArray();
        for (Group group : GROUPS) {
            JsonObject g = new JsonObject();
            g.addProperty("id", group.id()); g.addProperty("title", group.title());
            JsonArray fields = new JsonArray();
            for (Setting setting : SERVER) {
                if (!setting.group().equals(group.id())) continue;
                JsonObject f = new JsonObject();
                f.addProperty("key", setting.key()); f.addProperty("title", setting.title()); f.addProperty("type", setting.type().id());
                if (setting.type() == Type.INT) { f.addProperty("min", setting.min()); f.addProperty("max", setting.max()); f.addProperty("step", setting.step()); }
                if (setting.type() == Type.ENUM) { JsonArray options = new JsonArray(); setting.options().forEach(options::add); f.add("options", options); }
                if (setting.type() == Type.FLAGS) {
                    JsonArray options = new JsonArray();
                    for (Flag flag : setting.flags()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", flag.id()); o.addProperty("title", flag.title()); o.addProperty("help", flag.help());
                        o.addProperty("group", flag.group()); o.addProperty("groupTitle", flag.groupTitle());
                        options.add(o);
                    }
                    f.add("options", options);
                }
                f.addProperty("help", setting.help());
                fields.add(f);
            }
            g.add("fields", fields);
            groups.add(g);
        }
        out.add("groups", groups);
        return out;
    }

    /** The server's fields of this config: {@code {"key":value,…}}; lists as ids joined with commas. */
    public static JsonObject values(DivineConfig config) {
        JsonObject out = new JsonObject();
        for (Setting setting : SERVER) out.add(setting.key(), read(config, setting));
        return out;
    }

    public record Patched(List<String> applied, Map<String, String> errors) {
        public boolean ok() { return errors.isEmpty(); }
        public JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("ok", ok());
            JsonArray keys = new JsonArray(); applied.forEach(keys::add); out.add("applied", keys);
            JsonObject why = new JsonObject(); errors.forEach(why::addProperty); out.add("errors", why);
            return out;
        }
    }

    /** Applies the valid values of {@code patch} to {@code config} and names the reason for every refused one. */
    public static Patched patch(DivineConfig config, JsonObject patch) {
        List<String> applied = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            String key = entry.getKey();
            Setting setting = setting(key);
            if (setting == null) { errors.put(key, personal(key) ? "личная настройка игрока: меняется в его F9" : "нет такой настройки"); continue; }
            String problem = write(config, setting, entry.getValue());
            if (problem == null) applied.add(key); else errors.put(key, problem);
        }
        return new Patched(applied, errors);
    }

    /** The server's values laid over a player's own config (his personal fields stay): what a player on a server plays with. */
    public static DivineConfig overlay(DivineConfig own, String serverJson) {
        DivineConfig out = own.copy();
        if (serverJson == null || serverJson.isBlank()) return out;
        try { patch(out, JsonParser.parseString(serverJson).getAsJsonObject()); } catch (RuntimeException broken) { return own.copy(); }
        return out.sanitized();
    }

    /** {@code to} with the personal fields of {@code from}: what a player on a server saves to his own file. */
    public static DivineConfig withPersonal(DivineConfig to, DivineConfig from) {
        DivineConfig out = to.copy();
        try {
            for (String name : PERSONAL) { Field field = DivineConfig.class.getField(name); field.set(out, field.get(from)); }
        } catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
        return out.sanitized();
    }

    // ---- Reading and writing one field. ----

    private static JsonElement read(DivineConfig config, Setting setting) {
        Object value = get(config, setting.key());
        return switch (setting.type()) {
            case INT -> new JsonPrimitive((Integer) value);
            case BOOL -> new JsonPrimitive((Boolean) value);
            case ENUM -> new JsonPrimitive(value instanceof Enum<?> e ? e.name() : String.valueOf(value));
            case STRING -> new JsonPrimitive(String.valueOf(value));
            case LIST, FLAGS -> { @SuppressWarnings("unchecked") List<String> ids = (List<String>) value; yield new JsonPrimitive(String.join(",", ids)); }
        };
    }

    /** Null when written; else why not. */
    private static String write(DivineConfig config, Setting setting, JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return "нужно простое значение";
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        switch (setting.type()) {
            case INT -> {
                if (!primitive.isNumber()) return "нужно число";
                double number = primitive.getAsDouble();
                if (number != Math.rint(number)) return "нужно целое число";
                if (number < setting.min() || number > setting.max()) return "вне границ " + setting.min() + "…" + setting.max();
                set(config, setting.key(), (int) number);
            }
            case BOOL -> {
                if (!primitive.isBoolean()) return "нужно да или нет";
                set(config, setting.key(), primitive.getAsBoolean());
            }
            case ENUM -> {
                String name = primitive.getAsString();
                if (!setting.options().contains(name)) return "нет такого варианта: " + name;
                Object current = get(config, setting.key());
                if (current instanceof Enum<?> e) set(config, setting.key(), enumValue(e.getDeclaringClass(), name));
                else set(config, setting.key(), name);
            }
            case STRING -> {
                String text = primitive.getAsString().strip();
                if (text.isEmpty() || text.length() > 120 || text.chars().anyMatch(Character::isISOControl)) return "нужна непустая строка до 120 знаков";
                set(config, setting.key(), text);
            }
            case LIST, FLAGS -> {
                List<String> ids = new ArrayList<>();
                for (String part : primitive.getAsString().split(",")) {
                    String id = part.strip().toLowerCase(Locale.ROOT);
                    if (id.isEmpty()) continue;
                    if (!ID.matcher(id).matches()) return "неверный id: " + id;
                    if (setting.key().equals("disabled") && Miracle.byId(id) == null) return "нет такого деяния: " + id;
                    if (!ids.contains(id)) ids.add(id);
                }
                set(config, setting.key(), ids);
            }
        }
        return null;
    }

    private static Object get(DivineConfig config, String name) {
        try { return DivineConfig.class.getField(name).get(config); }
        catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void set(DivineConfig config, String name, Object value) {
        try { DivineConfig.class.getField(name).set(config, value); }
        catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object enumValue(Class type, String name) { return Enum.valueOf(type, name); }

    private static List<String> names(Enum<?>[] values) { return Arrays.stream(values).map(Enum::name).toList(); }
    private static Setting number(String key, String group, String title, String help, int min, int max, int step) {
        return new Setting(key, group, title, Type.INT, min, max, step, List.of(), help, List.of());
    }
    private static Setting flag(String key, String group, String title, String help) { return new Setting(key, group, title, Type.BOOL, 0, 0, 0, List.of(), help, List.of()); }
    private static Setting choice(String key, String group, String title, String help, List<String> options) {
        return new Setting(key, group, title, Type.ENUM, 0, 0, 0, options, help, List.of());
    }
    private static Setting text(String key, String group, String title, String help) { return new Setting(key, group, title, Type.STRING, 0, 0, 0, List.of(), help, List.of()); }
    private static Setting flags(String key, String group, String title, String help, List<Flag> flags) {
        return new Setting(key, group, title, Type.FLAGS, 0, 0, 0, List.of(), help, flags);
    }

    /** Every act of the god by kind: punishments, mercies, jests, then the omens; the help names its group of strength. */
    static List<Flag> deedFlags() {
        List<Flag> out = new ArrayList<>();
        for (Miracle.Kind kind : List.of(Miracle.Kind.WRATH, Miracle.Kind.HELP, Miracle.Kind.JEST, Miracle.Kind.OMEN))
            for (Miracle miracle : Miracle.values()) {
                if (miracle.kind != kind) continue;
                MiracleGroup group = null;
                for (MiracleGroup g : MiracleGroup.values()) if (g.members.contains(miracle)) { group = g; break; }
                String help = (group == null ? "" : group.name() + " «" + group.label + "»: ") + miracle.description;
                String[] where = switch (kind) {
                    case WRATH -> new String[] { "wrath", "Кары" }; case HELP -> new String[] { "miracle", "Блага" };
                    case JEST -> new String[] { "jest", "Шутки" }; case OMEN -> new String[] { "omen", "Знамения" };
                };
                out.add(new Flag(miracle.id(), miracle.title(), help, where[0], where[1]));
            }
        return List.copyOf(out);
    }

    /** Every sacred boss by where he comes from first, and the god with the mace with the end of the world. */
    static List<Flag> bossFlags() {
        List<Flag> out = new ArrayList<>();
        for (BossKind.Place place : BossKind.Place.values()) {
            String title = switch (place) {
                case CHURCH -> "Храм"; case CEMETERY -> "Погост"; case GOD -> "Посланники бога"; case FINAL -> "Конец света";
                case WATER -> "Пучина"; case WILD -> "Дикие земли"; case NIGHT -> "Ночь";
            };
            String group = place.name().toLowerCase(Locale.ROOT);
            for (BossKind kind : BossKind.values())
                if (kind.places.get(0) == place)
                    out.add(new Flag(kind.id, kind.title, "Ульта «" + kind.ultimate + "», недуг «" + kind.affliction + "». Выключенный приходит обычным усиленным мобом с его именем.",
                            group, title));
            if (place == BossKind.Place.FINAL)
                out.add(new Flag(GOD_AVATAR_ID, "Бог с булавой", "Сам бог выходит из туч и бьёт булавой. Выключенный: остаются его молнии и ударные волны без фигуры.",
                        group, title));
        }
        return List.copyOf(out);
    }

    /** The JSON text of {@link #values}. */
    public static String valuesJson(DivineConfig config) { return GSON.toJson(values(config)); }
}
