package io.github.mobtalk.server.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Collection;
import java.util.UUID;

/** Small JSON helpers for the admin API; everything is Gson trees so a fixture, a source and a live view look the same. */
public final class Json {
    private Json() { }

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    public static JsonObject parseObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text == null || text.isBlank() ? "{}" : text);
            if (!element.isJsonObject()) throw ApiException.badRequest("Ожидался объект JSON.");
            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException broken) { throw ApiException.badRequest("Тело запроса — не JSON."); }
    }

    public static JsonObject obj(Object... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) object.add((String) pairs[i], tree(pairs[i + 1]));
        return object;
    }

    public static JsonArray array(Collection<?> values) {
        JsonArray array = new JsonArray();
        for (Object value : values) array.add(tree(value));
        return array;
    }

    /** A plain value, a tree or null as a JSON element. */
    public static JsonElement tree(Object value) {
        if (value == null) return com.google.gson.JsonNull.INSTANCE;
        if (value instanceof JsonElement element) return element;
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Collection<?> collection) return array(collection);
        if (value instanceof UUID || value instanceof CharSequence || value instanceof Enum<?>) return new JsonPrimitive(value.toString());
        return GSON.toJsonTree(value);
    }

    public static String string(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? null : element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    public static long number(JsonObject object, String key, long fallback) {
        JsonElement element = object == null ? null : object.get(key);
        try { return element == null || element.isJsonNull() ? fallback : element.getAsLong(); }
        catch (RuntimeException notNumber) { return fallback; }
    }

    /** {"uuid":…,"name":…} — how the API names a player. */
    public static JsonObject player(UUID uuid, String name) { return obj("uuid", uuid == null ? null : uuid.toString(), "name", name); }
    public static JsonObject ok(String message) { return message == null ? obj("ok", true) : obj("ok", true, "message", message); }
}
