package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SettingsScope;
import io.github.mobtalk.server.ServerConfig;
import io.github.mobtalk.core.Tunables;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Mob Talk's own server settings in the {@code mobtalk-admin} protocol (plan 12.3): every number of {@link Tunables} and the
 * server's switches and models, with Russian titles from {@code ru_ru.json}, in the order of the F7 tabs. Personal fields
 * (microphone, Whisper, HUD, names) are never listed: they live on each player's client. */
public final class MobTalkAdminSource implements UnaryOperator<String> {
    public static final String ID = "mobtalk";

    /** The personal layer of plan 12.2: {@link SettingsScope}. */
    public static boolean personal(String field) { return SettingsScope.personal(field); }

    private record Extra(String field, Tunables.Section section, String group, String type, List<String> options, String title, String help) { }

    /** The server settings that are not numbers in {@link Tunables}. */
    private static final List<Extra> EXTRAS = List.of(
            new Extra("enabled", Tunables.Section.GENERAL, "main", "bool", null, null, "Главный выключатель: без него мобы не слушают и не отвечают."),
            new Extra("vision", Tunables.Section.GENERAL, "main", "enum", List.of("off", "unusual", "always"), null, null),
            new Extra("keepAcquainted", Tunables.Section.GENERAL, "main", "bool", null, null, null),
            new Extra("streamOverlay", Tunables.Section.GENERAL, "overlay", "bool", null, "Оверлей для OBS на сервере", "Выкл — сервер никому не шлёт данные оверлея (бог, милость, кары, события); субтитры жителей у стримера всё равно работают."),
            new Extra("streamOverlayAudience", Tunables.Section.GENERAL, "overlay", "enum", List.of("streamer", "all"), "Кому данные оверлея", "streamer — стримеру и админам, all — всем, кто включил оверлей в F7."),
            new Extra("llmProvider", Tunables.Section.MODEL, "llm", "enum", List.of("openrouter", "venice"), "Кто отвечает", null),
            new Extra("llmModel", Tunables.Section.MODEL, "llm", "string", null, "Модель OpenRouter", null),
            new Extra("veniceModel", Tunables.Section.MODEL, "llm", "string", null, "Модель Venice", null),
            new Extra("reasoningEffort", Tunables.Section.MODEL, "llm", "enum", List.of("", "none", "minimal", "low", "medium", "high"), "Рассуждение", null),
            new Extra("backgroundModel", Tunables.Section.MODEL, "llm", "string", null, null, "Пусто — та же модель, что для ответов."),
            new Extra("decider", Tunables.Section.MODEL, "llm", "enum", List.of("rules", "jev"), null, null),
            new Extra("ttsProvider", Tunables.Section.VOICE, "tts", "enum", List.of("openrouter", "venice"), "Кто озвучивает", null),
            new Extra("ttsModel", Tunables.Section.VOICE, "tts", "string", null, null, null),
            new Extra("veniceTtsModel", Tunables.Section.VOICE, "tts", "string", null, "Модель озвучки Venice", null),
            new Extra("veniceVoice", Tunables.Section.VOICE, "tts", "string", null, null, null),
            new Extra("defaultVoice", Tunables.Section.VOICE, "tts", "string", null, "Голос по умолчанию (ElevenLabs)", null),
            new Extra("sttServer", Tunables.Section.VOICE, "sttServer", "bool", null, "Распознавание на сервере (OpenRouter)", "Игроки без своего Whisper говорят голосом: запись уходит на сервер и распознаётся моделью OpenRouter."),
            new Extra("sttServerLocal", Tunables.Section.VOICE, "sttServer", "bool", null, "Свой Whisper на сервере", "Сервер распознаёт своим Whisper (нужна видеокарта или терпение); при сбое — OpenRouter, если он включён."),
            new Extra("sttModel", Tunables.Section.VOICE, "sttServer", "string", null, "Модель распознавания", "Модель OpenRouter для речи игроков."),
            new Extra("sttFallbackModel", Tunables.Section.VOICE, "sttServer", "string", null, "Запасная модель распознавания", "Если основная не ответила."),
            new Extra("rudeness", Tunables.Section.TALK, "reply", "enum", List.of("0", "1", "2", "3"), null, "0 — без мата, 1 — грубовато, 2 — мат моментами, 3 — без тормозов."),
            new Extra("commonPrompt", Tunables.Section.TALK, "reply", "string", null, null, null),
            new Extra("socialLayer", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("familyLife", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("coSleep", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("weddingCeremony", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("rivals", Tunables.Section.SOCIAL, "rivals", "bool", null, null, null),
            new Extra("rivalSteal", Tunables.Section.SOCIAL, "rivals", "bool", null, null, null),
            new Extra("rivalTrials", Tunables.Section.SOCIAL, "rivals", "bool", null, null, null),
            new Extra("rivalBellScene", Tunables.Section.SOCIAL, "rivals", "bool", null, null, null),
            new Extra("rivalHud", Tunables.Section.SOCIAL, "rivals", "bool", null, null, null),
            new Extra("pregnancy", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("pregnancyBelly", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("pregnancyStatus", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("familyFade", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("socialBehavior", Tunables.Section.SOCIAL, "switches", "bool", null, "Мобы ведут себя по отношениям", null),
            new Extra("initiative", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("giftsFirst", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("villageChatter", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("villageNotices", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("villageSites", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("villageDefense", Tunables.Section.SOCIAL, "defense", "bool", null, null, null),
            new Extra("villageNoSpawn", Tunables.Section.SOCIAL, "defense", "bool", null, null, null),
            new Extra("alarmAgainstPlayers", Tunables.Section.SOCIAL, "defense", "bool", null, null, null),
            new Extra("alarmChat", Tunables.Section.SOCIAL, "defense", "bool", null, null, null),
            new Extra("alarmSound", Tunables.Section.SOCIAL, "defense", "bool", null, null, null),
            new Extra("villageNoConversion", Tunables.Section.SOCIAL, "defense", "bool", null, null, null),
            new Extra("villageTowers", Tunables.Section.SOCIAL, "towers", "bool", null, null, null),
            new Extra("barsInVillages", Tunables.Section.SOCIAL, "bars", "bool", null, "Бары в новых деревнях", null),
            new Extra("barEvenings", Tunables.Section.SOCIAL, "bars", "bool", null, "Вечером жители идут в бар", null),
            new Extra("barVoiced", Tunables.Section.SOCIAL, "bars", "bool", null, "Озвучка разговоров в баре", null),
            new Extra("barAliceSpeaker", Tunables.Section.SOCIAL, "bars", "bool", null, "Колонка Алисы вместо проигрывателя", null),
            new Extra("barDrinks", Tunables.Section.SOCIAL, "bars", "bool", null, "Бармен продаёт выпивку", null),
            new Extra("villageGuards", Tunables.Section.SOCIAL, "guards", "bool", null, null, null),
            new Extra("villageRoads", Tunables.Section.SOCIAL, "roads", "bool", null, null, null),
            new Extra("roadLamps", Tunables.Section.SOCIAL, "roads", "bool", null, null, null),
            new Extra("roadBypass", Tunables.Section.SOCIAL, "roads", "bool", null, null, null),
            new Extra("roadDecor", Tunables.Section.SOCIAL, "roads", "bool", null, null, null),
            new Extra("villageHeavy", Tunables.Section.SOCIAL, "guards", "bool", null, null, null),
            new Extra("villageCavalry", Tunables.Section.SOCIAL, "guards", "bool", null, null, null),
            new Extra("officials", Tunables.Section.SOCIAL, "officials", "bool", null, null, null),
            new Extra("divineTidings", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("divinePanic", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("divineMayExile", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("divineMourners", Tunables.Section.SOCIAL, "switches", "bool", null, null, null),
            new Extra("placeAnalysis", Tunables.Section.ACTIONS, "place", "bool", null, null, null),
            new Extra("placeClassifier", Tunables.Section.ACTIONS, "place", "bool", null, null, null));

    private static final Map<String, String> GROUP_TITLES = Map.of("main", "Основное", "llm", "Модель ответа", "tts", "Озвучка",
            "switches", "Включение", "place", "Анализ места", "overlay", "Оверлей для стрима");

    /** One described field: where it sits, its type, how to read and write it. */
    record Spec(String key, Tunables.Section section, String group, String type, JsonObject description, Field field, Tunables.Tunable tunable) { }

    private final Supplier<MobTalkConfig> current;
    private final Consumer<UnaryOperator<MobTalkConfig>> update;
    private final Map<String, Spec> specs = new LinkedHashMap<>();
    private final JsonObject description;

    /** The Fabric entrypoint {@code mobtalk-admin}: the server layer of {@link ServerConfig}. */
    public MobTalkAdminSource() { this(ServerConfig::get, ServerConfig::update, SettingsScope::personal); }

    /** {@code update} is {@code ServerConfig.update} on a server: it saves and tells the listeners. */
    public MobTalkAdminSource(Supplier<MobTalkConfig> current, Consumer<UnaryOperator<MobTalkConfig>> update, Predicate<String> personal) {
        this.current = current; this.update = update;
        Map<String, String> lang = lang();
        MobTalkConfig defaults = new MobTalkConfig();
        List<Spec> all = new ArrayList<>();
        for (Tunables.Tunable tunable : Tunables.ALL) {
            if (personal.test(tunable.field())) continue;
            Field field = field(tunable.field());
            boolean integer = field.getType() == int.class;
            String label = lang.getOrDefault(tunable.langKey(), tunable.field());
            JsonObject json = Json.obj("key", tunable.field(), "title", title(label), "type", integer ? "int" : "decimal",
                    "min", number(tunable.min(), integer), "max", number(tunable.max(), integer), "step", number(tunable.step(), integer),
                    "default", number(tunable.defaultValue(), integer), "unit", unit(label), "help", lang.get(tunable.langKey() + ".tooltip"), "danger", null);
            all.add(new Spec(tunable.field(), tunable.section(), tunable.group(), integer ? "int" : "decimal", json, field, tunable));
        }
        for (Extra extra : EXTRAS) {
            if (personal.test(extra.field())) continue;
            Field field = field(extra.field());
            String title = extra.title() != null ? extra.title() : lang.getOrDefault("mobtalk.settings." + extra.field(), extra.field());
            String help = extra.help() != null ? extra.help() : lang.get("mobtalk.settings." + extra.field() + ".tooltip");
            JsonObject json = Json.obj("key", extra.field(), "title", title, "type", extra.type(), "default", read(field, defaults), "help", help, "danger", null);
            if (extra.options() != null) {
                JsonArray options = new JsonArray();
                for (String option : extra.options()) options.add(Json.obj("value", option, "title", option.isEmpty() ? "по умолчанию провайдера" : option));
                json.add("options", options);
            }
            all.add(new Spec(extra.field(), extra.section(), extra.group(), extra.type(), json, field, null));
        }
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        for (Tunables.Section section : Tunables.Section.values()) {
            for (Spec spec : all) {
                if (spec.section() != section) continue;
                specs.put(spec.key(), spec);
                String sectionTitle = lang.getOrDefault("mobtalk.settings.tab." + section.name().toLowerCase(Locale.ROOT), section.name());
                JsonObject group = groups.computeIfAbsent(section.name().toLowerCase(Locale.ROOT) + "." + spec.group(), id -> Json.obj("id", id,
                        "title", lang.getOrDefault("mobtalk.settings.group." + spec.group(), GROUP_TITLES.getOrDefault(spec.group(), spec.group())),
                        "section", sectionTitle, "fields", new JsonArray()));
                group.getAsJsonArray("fields").add(spec.description());
            }
        }
        description = Json.obj("id", ID, "title", "Mob Talk", "groups", Json.array(groups.values()));
    }

    @Override public String apply(String request) {
        JsonObject query = Json.parseObject(request);
        String op = Json.string(query, "op");
        JsonElement answer = switch (op == null ? "" : op) {
            case "describe" -> description;
            case "values" -> values();
            case "patch" -> patch(query.has("values") && query.get("values").isJsonObject() ? query.getAsJsonObject("values") : new JsonObject());
            case "journal" -> Json.obj("items", new JsonArray());
            case "metrics" -> new JsonObject();
            case "actions" -> Json.obj("actions", Json.array(List.of(Json.obj("id", "reset-section", "title", "Сбросить раздел к умолчаниям",
                    "args", List.of(Json.obj("key", "section", "title", "Раздел", "type", "enum",
                            "options", java.util.Arrays.stream(Tunables.Section.values()).map(s -> s.name().toLowerCase(Locale.ROOT)).toList())),
                    "danger", "Все числа и переключатели раздела вернутся к умолчаниям."),
                    Json.obj("id", "overlay-banner", "title", "Баннер на оверлей стримера",
                            "args", List.of(Json.obj("key", "text", "title", "Текст", "type", "string"), Json.obj("key", "minutes", "title", "Минут (1–120)", "type", "int")), "danger", null),
                    Json.obj("id", "overlay-clear", "title", "Убрать баннер с оверлея", "args", List.of(), "danger", null))));
            case "action" -> action(Json.string(query, "id"), query.has("args") && query.get("args").isJsonObject() ? query.getAsJsonObject("args") : new JsonObject());
            default -> Json.obj("ok", false, "error", "неизвестная операция: " + op);
        };
        return Json.GSON.toJson(answer);
    }

    private JsonObject values() {
        MobTalkConfig config = current.get();
        JsonObject values = new JsonObject();
        for (Spec spec : specs.values()) values.add(spec.key(), read(spec.field(), config));
        return values;
    }

    private JsonObject patch(JsonObject wanted) {
        Map<Spec, Object> accepted = new LinkedHashMap<>();
        JsonObject errors = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : wanted.entrySet()) {
            Spec spec = specs.get(entry.getKey());
            if (spec == null) { errors.addProperty(entry.getKey(), "неизвестная или личная настройка"); continue; }
            try { accepted.put(spec, parse(spec, entry.getValue())); }
            catch (IllegalArgumentException bad) { errors.addProperty(entry.getKey(), bad.getMessage()); }
        }
        if (!accepted.isEmpty()) update.accept(config -> {
            MobTalkConfig copy = config.copy();
            accepted.forEach((spec, value) -> write(spec.field(), copy, value));
            return copy;
        });
        return Json.obj("ok", errors.isEmpty(), "applied", accepted.keySet().stream().map(Spec::key).toList(), "errors", errors);
    }

    private JsonObject action(String id, JsonObject args) {
        if ("overlay-banner".equals(id)) {
            long minutes = Json.number(args, "minutes", 5);
            return Json.obj("ok", true, "message", io.github.mobtalk.server.OverlayHub.banner(Json.string(args, "text"), (int) Math.max(1, Math.min(120, minutes))));
        }
        if ("overlay-clear".equals(id)) return Json.obj("ok", true, "message", io.github.mobtalk.server.OverlayHub.clearBanner());
        if (!"reset-section".equals(id)) return Json.obj("ok", false, "message", "Нет такого действия.");
        String name = Json.string(args, "section");
        Tunables.Section section;
        try { section = Tunables.Section.valueOf(name == null ? "" : name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { return Json.obj("ok", false, "message", "Нет такого раздела: " + name); }
        update.accept(config -> {
            MobTalkConfig copy = config.copy();
            Tunables.resetSection(copy, section);
            // Only the fields this source shows may change; a personal one (the HUD, Whisper) keeps the value it had.
            for (Field field : MobTalkConfig.class.getFields())
                if (!specs.containsKey(field.getName()) && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) write(field, copy, readRaw(field, config));
            return copy;
        });
        return Json.obj("ok", true, "message", "Раздел сброшен.");
    }

    private static Object parse(Spec spec, JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) throw new IllegalArgumentException("нужно значение");
        switch (spec.type()) {
            case "bool" -> {
                if (!value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("нужно да или нет");
                return value.getAsBoolean();
            }
            case "int", "decimal" -> {
                double number;
                try { number = value.getAsDouble(); } catch (NumberFormatException notNumber) { throw new IllegalArgumentException("нужно число"); }
                Tunables.Tunable tunable = spec.tunable();
                if (!Double.isFinite(number) || number < tunable.min() || number > tunable.max())
                    throw new IllegalArgumentException("вне границ " + plain(tunable.min()) + "–" + plain(tunable.max()));
                if (spec.type().equals("int")) {
                    if (number != Math.rint(number)) throw new IllegalArgumentException("нужно целое число");
                    return (int) number;
                }
                return tunable.normalize(number);
            }
            case "enum" -> {
                String text = value.getAsString();
                for (JsonElement option : spec.description().getAsJsonArray("options"))
                    if (option.getAsJsonObject().get("value").getAsString().equals(text))
                        return spec.field().getType() == int.class ? Integer.parseInt(text) : text;
                throw new IllegalArgumentException("нет такого варианта: " + text);
            }
            default -> {
                String text = value.getAsString();
                if (text.length() > 4000) throw new IllegalArgumentException("длиннее 4000 символов");
                return text;
            }
        }
    }

    private static JsonElement read(Field field, MobTalkConfig config) {
        Object value = readRaw(field, config);
        return field.getName().equals("rudeness") ? Json.tree(String.valueOf(value)) : Json.tree(value);
    }

    private static Object readRaw(Field field, MobTalkConfig config) {
        try { return field.get(config); } catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void write(Field field, MobTalkConfig config, Object value) {
        try { field.set(config, value); } catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Field field(String name) {
        try { return MobTalkConfig.class.getField(name); }
        catch (NoSuchFieldException missing) { throw new IllegalStateException("MobTalkConfig без поля " + name, missing); }
    }

    private static Number number(double value, boolean integer) { return integer ? (Number) (long) Math.round(value) : (Number) value; }
    private static String plain(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }

    /** «Дальность разговора: %s бл.» → «Дальность разговора». */
    static String title(String label) {
        int colon = label.indexOf(':'), mark = label.indexOf("%s");
        String title = colon > 0 && (mark < 0 || colon < mark) ? label.substring(0, colon) : label.replace("%s", "");
        return title.replaceAll("\\s+", " ").strip();
    }

    /** «Дальность разговора: %s бл.» → «бл.»; «Длина ответа: до %s слов» → «слов». */
    static String unit(String label) {
        int mark = label.indexOf("%s");
        if (mark < 0) return null;
        String after = label.substring(mark + 2).strip();
        return after.isEmpty() ? null : after;
    }

    static Map<String, String> lang() {
        Map<String, String> lang = new LinkedHashMap<>();
        try (InputStream in = MobTalkAdminSource.class.getClassLoader().getResourceAsStream("assets/mobtalk/lang/ru_ru.json")) {
            if (in == null) return lang;
            JsonObject object = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) if (entry.getValue().isJsonPrimitive()) lang.put(entry.getKey(), entry.getValue().getAsString());
        } catch (IOException | RuntimeException broken) { /* Field names stand in for titles. */ }
        return lang;
    }
}
