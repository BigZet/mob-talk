package io.github.divineblock.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
 * values out of bounds instead of clamping them. Plain Java, for the tests and the web admin. */
public final class DivineSettings {
    /** Fields that stay with the player. The god's voice is synthesised by each player's own game for now, so his voice and
     * its model are personal too; they move to the server once the server speaks for him. */
    public static final Set<String> PERSONAL = Set.of("voice", "voiceTemple", "voiceRoomSize", "voiceReverb", "voiceEchoMs", "voiceEchoLevel",
            "voiceEchoRepeats", "chantVolume", "effects", "cameraShake", "voiceId", "ttsModel", "whisperUrl", "whisperAutoStart", "microphoneName");

    public enum Type { INT, BOOL, ENUM, STRING, LIST;
        String id() { return switch (this) { case INT -> "int"; case BOOL -> "bool"; case ENUM -> "enum"; case STRING, LIST -> "string"; }; } }

    /** One server field: its JSON key (the field's name), group, title, type and bounds. */
    public record Setting(String key, String group, String title, Type type, int min, int max, int step, List<String> options, String help) { }
    public record Group(String id, String title) { }

    public static final List<Group> GROUPS = List.of(new Group("god", "Характер бога"), new Group("speech", "Речь и решения"),
            new Group("world", "Мир"), new Group("wrath", "Кары"), new Group("bosses", "Боссы"));

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
            number("protectRadius", "god", "Радиус защиты дома", "Сколько блоков вокруг кровати игрока кары не трогают.", 0, 256, 1),
            flag("debug", "god", "Отладка", "Показывать игрокам числа вердикта и служебные строки."),
            choice("provider", "speech", "Речь", "Кто пишет слова бога: OpenRouter или Venice с запасным OpenRouter.", names(DivineConfig.Provider.values())),
            flag("useJev", "speech", "Решатель Jev", "Решения бога принимает отдельная быстрая модель."),
            text("chatModel", "speech", "Модель речи OpenRouter", "Модель OpenRouter для слов бога."),
            text("veniceModel", "speech", "Модель речи Venice", "Модель Venice для слов бога."),
            number("speechTimeoutSeconds", "speech", "Ожидание речи, с", "Сколько ждать модель, прежде чем бог скажет по шаблону.", 3, 60, 1),
            flag("churches", "world", "Церкви в мире", "Церкви появляются в новых чанках."),
            number("churchSpacing", "world", "Редкость церквей", "Не больше одной церкви на столько чанков по каждой стороне.", 32, 256, 1),
            choice("churchVariant", "world", "Вид церквей", "Пусто — все вперемешку, иначе один вид.", List.of("", "STONE", "WOOD", "SLAVIC")),
            flag("escalation", "wrath", "Кара за карой хуже", "Каждая следующая кара тяжелее прежней."),
            number("comboActs", "wrath", "Продолжения", "Сколько кар может последовать за первой подряд.", 0, 2, 1),
            flag("lingeringCurses", "wrath", "Проклятия на дни", "Проклятия держатся несколько игровых дней; выкл — минуты."),
            number("curseDays", "wrath", "Срок проклятий, дн.", "Сколько игровых дней держится проклятие.", 1, 7, 1),
            flag("landscapeWrath", "wrath", "Кары меняют ландшафт", "Воронки, разломы, потопы; выкл ставит на их место другую кару той же группы."),
            flag("allowEternalBan", "wrath", "Вечное изгнание", "За четвёртую хулу бог банит игрока на сервере навсегда; снимает админ."),
            flag("banBackup", "wrath", "Копия мира перед изгнанием", "Перед баном сохраняется копия мира."),
            list("disabled", "wrath", "Выключенные деяния", "Id деяний через запятую (список — /divineblock list); выключенное не выпадает."),
            list("disabledBosses", "bosses", "Выключенные боссы", "Id боссов через запятую; вместо выключенного приходит усиленный обычный моб."),
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
            case LIST -> { @SuppressWarnings("unchecked") List<String> ids = (List<String>) value; yield new JsonPrimitive(String.join(",", ids)); }
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
            case LIST -> {
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
        return new Setting(key, group, title, Type.INT, min, max, step, List.of(), help);
    }
    private static Setting flag(String key, String group, String title, String help) { return new Setting(key, group, title, Type.BOOL, 0, 0, 0, List.of(), help); }
    private static Setting choice(String key, String group, String title, String help, List<String> options) {
        return new Setting(key, group, title, Type.ENUM, 0, 0, 0, options, help);
    }
    private static Setting text(String key, String group, String title, String help) { return new Setting(key, group, title, Type.STRING, 0, 0, 0, List.of(), help); }
    private static Setting list(String key, String group, String title, String help) { return new Setting(key, group, title, Type.LIST, 0, 0, 0, List.of(), help); }

    /** The JSON text of {@link #values}. */
    public static String valuesJson(DivineConfig config) { return GSON.toJson(values(config)); }
}
