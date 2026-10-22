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
                                        "help", "Для отладки: каждая запись диалога вырастает в десятки раз.", "danger", "История станет заметно больше.")))));
                case "values" -> Json.obj("historyDays", settings.historyDays, "keepPrompts", settings.keepPrompts);
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
