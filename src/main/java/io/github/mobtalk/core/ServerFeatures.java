package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.List;

/** Что сервер умеет и разрешает этому игроку; уходит при входе в {@code ServerFeaturesS2C} и при смене серверных настроек.
 * {@code sttPaths} — разрешённые пути распознавания ({@code local}, {@code server}, {@code text}); {@code level} — уровень прав игрока
 * ({@code PLAYER}, {@code STREAMER}, {@code ADMIN}); {@code streamer} — ник стримера или пусто. */
public record ServerFeatures(String version, boolean enabled, boolean dialogue, boolean transcription, boolean speech,
                             List<String> sttPaths, int maxRecordingSeconds, String level, String streamer) {
    private static final Gson GSON = new GsonBuilder().create();

    public ServerFeatures {
        version = version == null ? "" : version;
        sttPaths = sttPaths == null ? List.of() : List.copyOf(sttPaths);
        level = level == null ? "PLAYER" : level;
        streamer = streamer == null ? "" : streamer;
    }

    public String toJson() { return GSON.toJson(this); }
    /** Мусор или пустая строка дают {@link #none()}. */
    public static ServerFeatures fromJson(String json) {
        try {
            ServerFeatures parsed = GSON.fromJson(json, ServerFeatures.class);
            return parsed == null ? none() : parsed;
        } catch (RuntimeException failure) { return none(); }
    }
    /** Сервер без Mob Talk или до ответа: ничего не включено. */
    public static ServerFeatures none() { return new ServerFeatures("", false, false, false, false, List.of(), 0, "PLAYER", ""); }
}
