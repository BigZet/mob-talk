package io.github.mobtalk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/** Venice's own account check (free, no model called): whether the key may still spend and what is left on it. */
final class VeniceBalance {
    static final URI ENDPOINT = URI.create("https://api.venice.ai/api/v1/api_keys/rate_limits");
    /** Below this (USD, DIEM and bundled credits together) Venice is treated as empty: a few lines would use up the rest. */
    static final double LOW = 0.10;
    private VeniceBalance() { }

    /** {@code permitted} false: Venice itself refuses this key (no balance, expired). {@code total} NaN when not reported. */
    record Status(boolean permitted, double total) {
        boolean low() { return !permitted || !Double.isNaN(total) && total < LOW; }
        String describe() {
            String left = Double.isNaN(total) ? "остаток не сообщён" : String.format(java.util.Locale.ROOT, "остаток %.2f", total);
            return left + (low() ? (permitted ? ", почти пусто" : ", доступ закрыт") : "");
        }
    }

    static Status check(BoundedHttp http, String key) throws IOException, InterruptedException {
        byte[] body = http.send(HttpRequest.newBuilder(ENDPOINT).timeout(http.timeout())
                .header("Authorization", "Bearer " + key).GET().build(), 256 * 1024, "Venice");
        return parse(new String(body, StandardCharsets.UTF_8));
    }

    /** Unknown fields count as fine: only an explicit refusal or a reported low balance hands speech over. */
    static Status parse(String json) {
        try {
            JsonObject data = JsonParser.parseString(json).getAsJsonObject();
            if (data.has("data") && data.get("data").isJsonObject()) data = data.getAsJsonObject("data");
            boolean permitted = !data.has("accessPermitted") || !data.get("accessPermitted").isJsonPrimitive() || data.get("accessPermitted").getAsBoolean();
            double total = Double.NaN;
            if (data.has("balances") && data.get("balances").isJsonObject()) {
                for (var entry : data.getAsJsonObject("balances").entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
                        total = (Double.isNaN(total) ? 0 : total) + value.getAsDouble();
                }
            }
            return new Status(permitted, total);
        } catch (RuntimeException malformed) { return new Status(true, Double.NaN); }
    }
}
