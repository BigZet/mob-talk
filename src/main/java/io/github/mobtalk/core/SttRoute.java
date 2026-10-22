package io.github.mobtalk.core;

import java.util.List;
import java.util.Locale;

/** Какой путь распознавания у игрока на самом деле (раздел 3 плана сервера): его личный {@code sttPath} пересекается с путями,
 * которые сервер разрешил в {@link ServerFeatures#sttPaths()}, и с тем, установлен ли у него локальный Whisper. Если выбранного
 * пути нет, берётся следующий по порядку {@code local → server → text} и игрок получает подсказку, почему. */
public final class SttRoute {
    private SttRoute() { }

    public static final String AUTO = "auto", LOCAL = "local", SERVER = "server", TEXT = "text";
    /** Пути со стороны клиента; {@code server-local} — способ сервера распознать то же аудио, клиенту он виден как {@code server}. */
    public static final List<String> CLIENT_PATHS = List.of(LOCAL, SERVER, TEXT);
    public static final String SERVER_LOCAL = "server-local";

    /** {@code hint} пустой, когда игрок получил то, что выбрал. */
    public record Choice(String path, String hint) {
        public boolean voice() { return LOCAL.equals(path) || SERVER.equals(path); }
    }

    /** Мусор и пустое значение дают {@link #AUTO}. */
    public static String normalize(String path) {
        String value = path == null ? "" : path.strip().toLowerCase(Locale.ROOT);
        return CLIENT_PATHS.contains(value) ? value : AUTO;
    }

    /** Имя модели OpenRouter вида {@code автор/модель}. */
    public static boolean validModel(String model) { return model != null && model.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}/[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"); }

    /** {@code allowed} — пути сервера; пустой список (сервер без Mob Talk или ответ ещё не пришёл) оставляет только локальный путь
     * и текст. {@code localReady} — у игрока установлен Whisper helper. */
    public static Choice choose(String personal, List<String> allowed, boolean localReady) {
        String wanted = normalize(personal);
        List<String> paths = allowed == null || allowed.isEmpty() ? List.of(LOCAL, TEXT) : allowed;
        if (!AUTO.equals(wanted) && usable(wanted, paths, localReady)) return new Choice(wanted, "");
        String fallback = TEXT;
        for (String path : CLIENT_PATHS) if (usable(path, paths, localReady)) { fallback = path; break; }
        if (AUTO.equals(wanted)) return new Choice(fallback, "");
        return new Choice(fallback, why(wanted, paths, localReady) + " " + then(fallback));
    }

    private static boolean usable(String path, List<String> paths, boolean localReady) {
        if (TEXT.equals(path)) return true;
        return paths.contains(path) && (!LOCAL.equals(path) || localReady);
    }

    private static String why(String wanted, List<String> paths, boolean localReady) {
        if (LOCAL.equals(wanted) && paths.contains(LOCAL) && !localReady) return "Локальный Whisper не установлен.";
        return switch (wanted) {
            case LOCAL -> "Локальное распознавание на этом сервере выключено.";
            case SERVER -> "Распознавание на сервере выключено или у сервера нет ключа.";
            default -> "Этот путь недоступен.";
        };
    }

    private static String then(String path) {
        return switch (path) {
            case LOCAL -> "Распознаёт ваш локальный Whisper.";
            case SERVER -> "Речь распознаёт сервер.";
            default -> "Голос недоступен: пишите текстом (T).";
        };
    }
}
