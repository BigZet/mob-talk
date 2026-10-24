package io.github.mobtalk.server.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** The admin page's own settings in {@code config/mobtalk-admin.json}: where it listens, how long history lives, whether prompts
 * are kept, and the password hash (never the password). Also the {@code admin} settings source for the page itself. */
public final class AdminSettings {
    public String bind = "127.0.0.1";
    public int port = 8787;
    public int historyDays = 90;
    /** Keep the whole prompt and the model's raw answer in each dialogue record; off by default so history stays small. */
    public boolean keepPrompts = false;
    public String passwordHash = "";
    /** squaremap's tiles folder relative to the server; empty — looked up in {@code squaremap/web/tiles}, {@code config/squaremap/web/tiles}. */
    public String squaremapTiles = "";
    /** BlueMap's webroot relative to the server (its {@code webserver.conf} default). */
    public String bluemapWebroot = "bluemap/web";
    /** Моменты для монтажа: что считать ярким моментом и когда открывать сессию записи. */
    public MomentRules moments = new MomentRules();

    public static AdminSettings load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return new AdminSettings();
        try {
            AdminSettings settings = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AdminSettings.class);
            return settings == null ? new AdminSettings() : settings.normalized();
        } catch (JsonParseException broken) { throw new IOException(file.getFileName() + " повреждён: " + broken.getMessage()); }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    AdminSettings normalized() {
        if (bind == null || bind.isBlank()) bind = "127.0.0.1";
        port = Math.max(0, Math.min(65535, port));
        historyDays = Math.max(1, Math.min(3650, historyDays));
        if (passwordHash == null) passwordHash = "";
        if (moments == null) moments = new MomentRules();
        moments.normalized();
        return this;
    }

    /** The settings source {@code admin}: history lifetime and prompt keeping change live; the address needs a restart. */
    public static UnaryOperator<String> source(AdminSettings settings, Path file, HistoryStore history) {
        return request -> {
            JsonObject query = Json.parseObject(request);
            String op = String.valueOf(Json.string(query, "op"));
            JsonObject answer = switch (op) {
                case "describe" -> Json.obj("id", "admin", "title", "Админка", "groups", List.of(Json.obj("id", "admin.main", "title", "Журнал",
                        "section", "Админка", "fields", List.of(
                                Json.obj("key", "historyDays", "title", "Хранить историю", "type", "int", "min", 1, "max", 3650, "step", 1,
                                        "default", 90, "unit", "дней", "help", "Записи старше удаляются раз в сутки.", "danger", null),
                                Json.obj("key", "keepPrompts", "title", "Сохранять промпты и сырые ответы", "type", "bool", "default", false,
                                        "help", "Для отладки: каждая запись диалога вырастает в десятки раз.", "danger", "История станет заметно больше."))),
                        Json.obj("id", "admin.moments", "title", "Моменты для монтажа", "section", "Админка", "fields", List.of(
                                Json.obj("key", "momentsEnabled", "title", "Отмечать яркие моменты", "type", "bool", "default", true,
                                        "help", "Кары, чудеса, боссы, свадьбы, смерти и сцены с жителями попадают во вкладку «Моменты» с таймкодом записи.", "danger", null),
                                Json.obj("key", "momentsThreshold", "title", "Наименьший вес момента", "type", "int", "min", 1, "max", 3, "step", 1, "default", 2,
                                        "unit", "из 3", "help", "1 — всё подряд, 3 — только самое громкое. Ручные метки попадают всегда.", "danger", null),
                                Json.obj("key", "momentsMergeSeconds", "title", "Склеивать события одного игрока", "type", "int", "min", 5, "max", 600, "step", 5,
                                        "default", 45, "unit", "с", "help", "События ближе этого сливаются в один момент: «кара → смерть».", "danger", null),
                                Json.obj("key", "momentsManualBack", "title", "Ручная метка захватывает", "type", "int", "min", 0, "max", 600, "step", 5,
                                        "default", 30, "unit", "с назад", "help", "Обычно жмут после смешного: метка начинается раньше.", "danger", null),
                                Json.obj("key", "momentsSessionAuto", "title", "Сессия записи без OBS начинается", "type", "enum", "default", "streamer_join",
                                        "options", List.of(Json.obj("value", "streamer_join", "title", "при входе стримера"), Json.obj("value", "any_join", "title", "при входе любого игрока"),
                                                Json.obj("value", "manual", "title", "только вручную")),
                                        "help", "С OBS сессия начинается и кончается вместе с эфиром или записью.", "danger", null)))));
                case "values" -> Json.obj("historyDays", settings.historyDays, "keepPrompts", settings.keepPrompts,
                        "momentsEnabled", settings.moments.enabled, "momentsThreshold", settings.moments.threshold, "momentsMergeSeconds", settings.moments.mergeSeconds,
                        "momentsManualBack", settings.moments.manualBackSeconds, "momentsSessionAuto", settings.moments.sessionAuto);
                case "patch" -> {
                    JsonObject values = query.has("values") && query.get("values").isJsonObject() ? query.getAsJsonObject("values") : new JsonObject();
                    List<String> applied = new ArrayList<>();
                    JsonObject errors = new JsonObject();
                    for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                        JsonElement value = entry.getValue();
                        switch (entry.getKey()) {
                            case "historyDays" -> {
                                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsDouble() == Math.rint(value.getAsDouble())
                                        && value.getAsInt() >= 1 && value.getAsInt() <= 3650) {
                                    settings.historyDays = value.getAsInt(); history.retentionDays(settings.historyDays); applied.add("historyDays");
                                } else errors.addProperty("historyDays", "вне границ 1–3650");
                            }
                            case "keepPrompts" -> {
                                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) { settings.keepPrompts = value.getAsBoolean(); applied.add("keepPrompts"); }
                                else errors.addProperty("keepPrompts", "нужно да или нет");
                            }
                            case "momentsEnabled" -> {
                                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) { settings.moments.enabled = value.getAsBoolean(); applied.add("momentsEnabled"); }
                                else errors.addProperty("momentsEnabled", "нужно да или нет");
                            }
                            case "momentsThreshold", "momentsMergeSeconds", "momentsManualBack" -> {
                                int min = entry.getKey().equals("momentsThreshold") ? 1 : entry.getKey().equals("momentsMergeSeconds") ? 5 : 0;
                                int max = entry.getKey().equals("momentsThreshold") ? 3 : 600;
                                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsDouble() == Math.rint(value.getAsDouble())
                                        && value.getAsInt() >= min && value.getAsInt() <= max) {
                                    switch (entry.getKey()) {
                                        case "momentsThreshold" -> settings.moments.threshold = value.getAsInt();
                                        case "momentsMergeSeconds" -> settings.moments.mergeSeconds = value.getAsInt();
                                        default -> settings.moments.manualBackSeconds = value.getAsInt();
                                    }
                                    applied.add(entry.getKey());
                                } else errors.addProperty(entry.getKey(), "вне границ " + min + "–" + max);
                            }
                            case "momentsSessionAuto" -> {
                                String auto = value.isJsonPrimitive() ? value.getAsString() : "";
                                if (MomentRules.SESSION_AUTO.contains(auto)) { settings.moments.sessionAuto = auto; applied.add("momentsSessionAuto"); }
                                else errors.addProperty("momentsSessionAuto", "одно из: streamer_join, any_join, manual");
                            }
                            default -> errors.addProperty(entry.getKey(), "неизвестная настройка");
                        }
                    }
                    if (!applied.isEmpty() && file != null) {
                        try { settings.save(file); } catch (IOException failure) { errors.addProperty("file", "не сохранилось: " + failure.getMessage()); }
                    }
                    yield Json.obj("ok", errors.isEmpty(), "applied", applied, "errors", errors);
                }
                case "actions" -> Json.obj("actions", List.of());
                default -> Json.obj("ok", false, "message", "неизвестная операция");
            };
            return Json.GSON.toJson(answer);
        };
    }
}
