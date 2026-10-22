package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Keys, tokens, passwords and coordinates never reach the history or an API answer: every string passes through here. */
public final class Redact {
    private Redact() { }

    public static final String HIDDEN = "[скрыто]";
    public static final String PLACE = "[координаты]";

    /** Fields whose value is dropped whatever it looks like. */
    private static final Set<String> SECRET_FIELDS = Set.of("password", "passwordhash", "token", "secret", "apikey", "api_key", "authorization",
            "cookie", "x", "y", "z", "pos", "position", "coords", "coordinates", "seed", "blockpos", "spawn", "respawn");

    private static final Pattern[] SECRETS = {
            Pattern.compile("sk-or-v1-[A-Za-z0-9]{16,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
            Pattern.compile("(?i)\\b(oauth:)[a-z0-9]{10,}"),
            Pattern.compile("[A-Za-z0-9+/_=-]{40,}"),
    };
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|token|password|passwd|secret|пароль|ключ|токен)(\\s*[:=]\\s*|\\s+)(\"?)[^\\s\",;]{4,}\\3");
    private static final Pattern AXES = Pattern.compile("(?i)(?<![\\p{L}\\d])([xyz])\\s*[=:]\\s*-?\\d+(?:[.,]\\d+)?");
    private static final Pattern TRIPLE = Pattern.compile(
            "[\\[(]?\\s*-?\\d{1,8}(?:\\.\\d+)?\\s*[,;]\\s*-?\\d{1,3}(?:\\.\\d+)?\\s*[,;]\\s*-?\\d{1,8}(?:\\.\\d+)?\\s*[\\])]?"
                    + "|\\[\\s*-?\\d{1,8}(?:\\.\\d+)?\\s+-?\\d{1,3}(?:\\.\\d+)?\\s+-?\\d{1,8}(?:\\.\\d+)?\\s*]");
    private static final Pattern COORD_WORD = Pattern.compile("(?i)(координат\\p{L}*|coords?|coordinates|BlockPos\\{?)\\s*:?\\s*[^\\n.;]{0,40}?-?\\d+[^\\n.;]{0,40}");

    public static String text(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Pattern secret : SECRETS) out = secret.matcher(out).replaceAll(HIDDEN);
        out = NAMED_SECRET.matcher(out).replaceAll("$1$2" + HIDDEN);
        out = COORD_WORD.matcher(out).replaceAll(PLACE);
        out = AXES.matcher(out).replaceAll("$1=?");
        out = TRIPLE.matcher(out).replaceAll(PLACE);
        return out;
    }

    /** A copy of the tree with every string cleaned and every secret-named field hidden. */
    public static JsonElement tree(JsonElement element) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            return primitive.isString() ? new JsonPrimitive(text(primitive.getAsString())) : primitive;
        }
        if (element.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) copy.add(tree(item));
            return copy;
        }
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            boolean secret = SECRET_FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT)) && !entry.getValue().isJsonNull();
            copy.add(entry.getKey(), secret ? new JsonPrimitive(HIDDEN) : tree(entry.getValue()));
        }
        return copy;
    }

    public static JsonObject object(JsonObject object) { return object == null ? null : tree(object).getAsJsonObject(); }
}
