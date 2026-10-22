package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One API call, already read: no exchange, so routes are plain functions and tests can call them without sockets. */
public record Request(String method, String path, Map<String, String> query, Map<String, String> params,
                      Map<String, List<String>> headers, byte[] body, String ip, AdminAuth.Session session) {

    public String query(String key) { String value = query.get(key); return value == null || value.isBlank() ? null : value; }
    public String query(String key, String fallback) { String value = query(key); return value == null ? fallback : value; }
    public String param(String key) { return params.get(key); }
    public String header(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet())
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().get(0);
        return null;
    }
    public JsonObject json() { return Json.parseObject(new String(body, StandardCharsets.UTF_8)); }

    public long longQuery(String key, long fallback) {
        String value = query(key);
        if (value == null) return fallback;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException bad) { throw ApiException.badRequest("Параметр " + key + " должен быть числом."); }
    }
    public int intQuery(String key, int fallback, int min, int max) {
        long value = longQuery(key, fallback);
        return (int) Math.max(min, Math.min(max, value));
    }

    public Request withParams(Map<String, String> params) { return new Request(method, path, query, params, headers, body, ip, session); }
    public Request withSession(AdminAuth.Session session) { return new Request(method, path, query, params, headers, body, ip, session); }

    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return query;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq)), value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            query.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
        }
        return query;
    }

    private static String decode(String text) {
        try { return URLDecoder.decode(text, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException broken) { throw ApiException.badRequest("Неверная строка запроса."); }
    }
}
