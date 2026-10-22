package io.github.divineblock.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Как распознаётся молитва голосом: тем же путём, что выбрал игрок в Mob Talk (личная настройка {@code sttPath} в
 * {@code config/mobtalk.json}), без зависимости от jar Mob Talk. {@code local} — свой Whisper у игрока, {@code server} — запись
 * уходит на сервер ({@code PrayAudioPayload}), где её распознаёт Mob Talk через entrypoint {@code mobtalk-stt}; {@code text} —
 * голосом не молиться. Без Mob Talk — как раньше, локально. */
public final class PrayerVoicePath {
    private PrayerVoicePath() { }
    public enum Route { LOCAL, SERVER, TEXT }

    /** {@code wanted} — {@code sttPath} из Mob Talk (или пусто), {@code localInstalled} — есть Whisper helper,
     * {@code serverAvailable} — сервер принимает аудио молитвы (на нём есть и Божественный блок, и распознавание Mob Talk). */
    public static Route choose(String wanted, boolean localInstalled, boolean serverAvailable) {
        String value = wanted == null ? "" : wanted.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "server" -> serverAvailable ? Route.SERVER : Route.LOCAL;
            case "text" -> Route.TEXT;
            case "local" -> Route.LOCAL;
            case "auto" -> localInstalled || !serverAvailable ? Route.LOCAL : Route.SERVER;
            default -> Route.LOCAL; // Без Mob Talk: как было.
        };
    }

    /** {@code sttPath} из {@code mobtalk.json} в папке настроек: пусто без файла (Mob Talk нет), {@code auto} без поля. Читает диск. */
    public static String mobTalkPath(Path configDirectory) {
        try {
            Path file = configDirectory.resolve("mobtalk.json");
            if (!Files.isRegularFile(file)) return "";
            JsonElement value = JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("sttPath");
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "auto";
        } catch (Exception unreadable) { return ""; }
    }
}
