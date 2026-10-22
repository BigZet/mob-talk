package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Objects;

/** Личный слой настроек одного игрока: только поля, которые {@link SettingsScope#personal} относит к игроку, в JSON того же вида,
 * что {@code mobtalk.json}. Клиент шлёт его при входе и при изменении ({@code PlayerSettingsC2S}); сервер хранит по UUID
 * ({@code PlayerSettingsStore}). Чужие и серверные ключи при разборе отбрасываются. */
public final class PlayerSettings {
    private static final Gson GSON = new GsonBuilder().create();
    private final JsonObject values;

    private PlayerSettings(JsonObject values) { this.values = values; }

    /** Личные поля этого снимка. */
    public static PlayerSettings of(MobTalkConfig config) {
        return new PlayerSettings(filter(GSON.toJsonTree(config == null ? new MobTalkConfig() : config).getAsJsonObject()));
    }
    /** Значения по умолчанию. */
    public static PlayerSettings defaults() { return of(new MobTalkConfig()); }

    /** Разбор JSON от клиента: не объект или мусор дают настройки по умолчанию; серверные и неизвестные ключи отбрасываются. */
    public static PlayerSettings fromJson(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json == null ? "" : json);
            return parsed.isJsonObject() ? new PlayerSettings(filter(parsed.getAsJsonObject())) : defaults();
        } catch (RuntimeException failure) { return defaults(); }
    }
    private static JsonObject filter(JsonObject source) {
        JsonObject kept = new JsonObject();
        for (String name : SettingsScope.personalFields()) if (source.has(name)) kept.add(name, source.get(name));
        return kept;
    }

    public String toJson() { return GSON.toJson(values); }
    /** Значение поля как JSON; null, если игрок его не прислал. */
    public JsonElement value(String field) { return values.get(field); }
    public Map<String, JsonElement> values() { return values.asMap(); }

    /** Серверный снимок с личными полями этого игрока поверх: по нему клиент рисует и слушает. Поля, которых игрок не прислал,
     * остаются значениями по умолчанию. */
    public MobTalkConfig applyTo(MobTalkConfig server) {
        JsonObject merged = GSON.toJsonTree(SettingsScope.serverOnly(server)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) merged.add(entry.getKey(), entry.getValue());
        try { return GSON.fromJson(merged, MobTalkConfig.class); }
        catch (RuntimeException failure) { return SettingsScope.serverOnly(server); }
    }

    @Override public boolean equals(Object other) { return other instanceof PlayerSettings p && p.values.equals(values); }
    @Override public int hashCode() { return Objects.hash(values); }
    @Override public String toString() { return "PlayerSettings" + values; }
}
