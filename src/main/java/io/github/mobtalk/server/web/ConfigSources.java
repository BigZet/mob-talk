package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Every settings source the admin page shows (Mob Talk, the divine block, the admin itself), all in the JSON protocol of plan 12.3.
 * Each applied change becomes a {@code config_change} record, which is also what a rollback reads. */
public final class ConfigSources {
    private final Map<String, UnaryOperator<String>> sources = new LinkedHashMap<>();
    private final HistoryStore history;

    public ConfigSources(HistoryStore history) { this.history = history; }

    public synchronized void add(String id, UnaryOperator<String> source) { sources.put(id, source); }
    public synchronized boolean has(String id) { return sources.containsKey(id); }

    public JsonObject describeAll() {
        JsonArray list = new JsonArray();
        for (String id : ids()) {
            try {
                JsonObject described = call(id, Json.obj("op", "describe"));
                if (!described.has("id")) described.addProperty("id", id);
                list.add(described);
            } catch (ApiException broken) {
                list.add(Json.obj("id", id, "title", id, "groups", new JsonArray(), "error", broken.getMessage()));
            }
        }
        return Json.obj("sources", list);
    }

    public JsonObject values(String id) {
        // Values are not cleaned: settings hold no secrets by design, and a cleaned prompt written back by a rollback would be damaged.
        JsonObject values = call(id, Json.obj("op", "values"), false);
        return Json.obj("values", values.has("values") && values.get("values").isJsonObject() ? values.get("values") : values);
    }

    /** Applies what the source accepts and writes one {@code config_change} per changed key. */
    public JsonObject patch(String id, JsonObject wanted, String by) { return patch(id, wanted, by, null); }

    private JsonObject patch(String id, JsonObject wanted, String by, String rollbackOf) {
        JsonObject before = values(id).getAsJsonObject("values");
        JsonObject answer = call(id, Json.obj("op", "patch", "values", wanted));
        JsonObject after = values(id).getAsJsonObject("values");
        JsonArray applied = answer.has("applied") && answer.get("applied").isJsonArray() ? answer.getAsJsonArray("applied") : new JsonArray();
        for (JsonElement key : applied) {
            String name = key.getAsString();
            JsonElement old = before.get(name), now = after.get(name);
            if (Objects.equals(old, now) && rollbackOf == null) continue;
            history.append("config_change", null, null, "Настройка " + id + "." + name + ": " + show(old) + " → " + show(now) + " (" + by + ")",
                    Json.obj("source", id, "key", name, "before", old, "after", now, "by", by, "rollbackOf", rollbackOf));
        }
        if (!answer.has("ok")) answer.addProperty("ok", !answer.has("errors") || answer.getAsJsonObject("errors").size() == 0);
        if (!answer.has("errors")) answer.add("errors", new JsonObject());
        answer.add("applied", applied);
        return answer;
    }

    /** Puts one change back. 409 when the value moved on since that change. */
    public JsonObject rollback(String recordId, String by) {
        JsonObject record = history.get(recordId);
        if (record == null || !"config_change".equals(Json.string(record, "kind"))) throw ApiException.notFound("Нет такого изменения настройки.");
        JsonObject data = record.getAsJsonObject("data");
        String source = Json.string(data, "source"), key = Json.string(data, "key");
        JsonElement now = values(source).getAsJsonObject("values").get(key);
        if (!Objects.equals(now, data.get("after")))
            throw ApiException.conflict("С тех пор " + key + " уже меняли (сейчас " + show(now) + "): откат отменён.");
        String old = show(data.get("before"));
        if (old.contains(Redact.HIDDEN) || old.contains(Redact.PLACE)) throw ApiException.conflict("Старое значение в истории скрыто: откатите вручную.");
        JsonObject wanted = new JsonObject();
        wanted.add(key, data.get("before"));
        JsonObject answer = patch(source, wanted, by, recordId);
        if (answer.getAsJsonObject("errors").size() > 0) throw ApiException.conflict("Источник не принял старое значение: " + answer.get("errors"));
        return Json.ok("Откачено: " + key + " = " + show(data.get("before")));
    }

    public JsonObject actions(String id) {
        JsonObject answer = call(id, Json.obj("op", "actions"));
        if (!answer.has("actions")) answer.add("actions", new JsonArray());
        return answer;
    }

    public JsonObject action(String id, String action, JsonObject args, String by) {
        JsonObject answer = call(id, Json.obj("op", "action", "id", action, "args", args));
        boolean ok = !answer.has("ok") || answer.get("ok").getAsBoolean();
        // Источники пишут итог по-разному: message, text (Божественный блок) или error при отказе.
        String message = firstOf(answer, "message", "text", "error");
        history.append("command", null, null, "Действие " + id + "." + action + " (" + by + "): " + (ok ? "выполнено" : "отказ"),
                Json.obj("command", id + " " + action + " " + Json.GSON.toJson(args), "by", by, "result", ok ? 1 : 0,
                        "output", List.of(String.valueOf(message))));
        if (!ok) throw ApiException.badRequest(message == null || message.isBlank() ? "Источник отказал." : message);
        answer.addProperty("ok", true);
        if (message != null) answer.addProperty("message", message);
        return answer;
    }

    private static String firstOf(JsonObject answer, String... keys) {
        for (String key : keys) { String value = Json.string(answer, key); if (value != null && !value.isBlank()) return value; }
        return null;
    }

    /** The source's journal ({@code {"op":"journal"}}), for the divine block's verdicts; empty when it keeps none. */
    public JsonObject journal(String id, long since, int limit) {
        return call(id, Json.obj("op", "journal", "since", since, "limit", limit));
    }

    /** {@code {"op":"metrics"}} of a source, or an empty object when it has none or fails. */
    public JsonObject metrics(String id) {
        try { return has(id) ? call(id, Json.obj("op", "metrics")) : new JsonObject(); }
        catch (ApiException broken) { return new JsonObject(); }
    }

    private synchronized List<String> ids() { return List.copyOf(sources.keySet()); }

    public JsonObject call(String id, JsonObject request) { return call(id, request, true); }

    private JsonObject call(String id, JsonObject request, boolean clean) {
        UnaryOperator<String> source;
        synchronized (this) { source = sources.get(id); }
        if (source == null) throw ApiException.notFound("Нет источника настроек " + id + ".");
        String answer;
        try { answer = source.apply(Json.GSON.toJson(request)); }
        catch (ApiException failure) { throw failure; }
        catch (RuntimeException | LinkageError failure) {
            throw new ApiException(500, "source_failed", "Источник " + id + " упал: " + failure.getClass().getSimpleName());
        }
        try {
            JsonElement parsed = JsonParser.parseString(answer == null ? "{}" : answer);
            if (!parsed.isJsonObject()) throw new ApiException(500, "source_failed", "Источник " + id + " ответил не объектом.");
            return clean ? Redact.object(parsed.getAsJsonObject()) : parsed.getAsJsonObject();
        } catch (JsonParseException broken) { throw new ApiException(500, "source_failed", "Источник " + id + " ответил не JSON."); }
    }

    private static String show(JsonElement value) {
        if (value == null || value.isJsonNull()) return "—";
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }
}
