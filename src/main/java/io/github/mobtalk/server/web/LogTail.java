package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The end of {@code logs/latest.log}, read backwards in blocks so a big log costs only its tail; lines are parsed from the
 * Fabric format {@code [19:35:18] [Server thread/INFO] (mobtalk) text} and cleaned of keys and coordinates. */
public final class LogTail {
    private LogTail() { }

    private static final Pattern LINE = Pattern.compile("^\\[([0-9:.]+)] \\[([^/\\]]+)/([A-Z]+)] (?:\\(([^)]+)\\) |\\[([^\\]]+)]: )?(.*)$");
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");

    public static JsonObject tail(Path log, int lines, List<String> filters, String minLevel) throws IOException {
        return parse(log, Math.max(1, Math.min(2000, lines)), filters, minLevel);
    }

    /** The log as a file to download: the whole of it without filters, or its last {@code lines} matching ones, cleaned. */
    public static String export(Path log, Integer lines, List<String> filters, String minLevel) throws IOException {
        if (!Files.isRegularFile(log)) return "";
        if (lines == null && (filters == null || filters.isEmpty()) && minLevel == null) {
            StringBuilder out = new StringBuilder();
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) out.append(Redact.text(line)).append('\n');
            return out.toString();
        }
        StringBuilder out = new StringBuilder();
        for (com.google.gson.JsonElement element : parse(log, lines == null ? 200_000 : Math.max(1, Math.min(200_000, lines)), filters, minLevel).getAsJsonArray("lines")) {
            JsonObject line = element.getAsJsonObject();
            out.append('[').append(Json.string(line, "t")).append("] [").append(Json.string(line, "level")).append("] (")
                    .append(Json.string(line, "logger")).append(") ").append(Json.string(line, "text")).append('\n');
        }
        return out.toString();
    }

    private static JsonObject parse(Path log, int wanted, List<String> filters, String minLevel) throws IOException {
        int floor = minLevel == null ? 0 : Math.max(0, LEVELS.indexOf(minLevel.toUpperCase(Locale.ROOT)));
        List<JsonObject> out = new ArrayList<>();
        if (!Files.isRegularFile(log)) return Json.obj("lines", out);
        JsonObject pending = null;
        List<String> continuation = new ArrayList<>();
        for (String raw : lastLines(log, wanted * 4 + 200)) {
            Matcher matcher = LINE.matcher(raw);
            if (!matcher.matches()) { continuation.add(raw); continue; }
            if (pending != null) flush(pending, continuation, out, filters, floor);
            continuation.clear();
            String logger = matcher.group(4) != null ? matcher.group(4) : matcher.group(5) != null ? matcher.group(5) : matcher.group(2);
            pending = Json.obj("t", matcher.group(1), "level", matcher.group(3), "logger", logger, "text", matcher.group(6));
        }
        if (pending != null) flush(pending, continuation, out, filters, floor);
        List<JsonObject> last = out.size() > wanted ? out.subList(out.size() - wanted, out.size()) : out;
        return Json.obj("lines", new ArrayList<>(last));
    }

    private static void flush(JsonObject line, List<String> continuation, List<JsonObject> out, List<String> filters, int floor) {
        if (Math.max(0, LEVELS.indexOf(Json.string(line, "level"))) < floor) return;
        String logger = Json.string(line, "logger").toLowerCase(Locale.ROOT), text = Json.string(line, "text");
        if (filters != null && !filters.isEmpty() && filters.stream().noneMatch(f -> logger.contains(f) || text.toLowerCase(Locale.ROOT).contains("[" + f)))
            return;
        String full = continuation.isEmpty() ? text : text + "\n" + String.join("\n", continuation);
        line.addProperty("text", Redact.text(full));
        out.add(line);
    }

    /** The last {@code count} lines: reads a growing tail (64 KB, doubling up to 8 MB) until it holds enough of them. */
    static List<String> lastLines(Path file, int count) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long length = in.length();
            for (long size = 64 * 1024; ; size *= 2) {
                long start = Math.max(0, length - size);
                byte[] block = new byte[(int) (length - start)];
                in.seek(start);
                in.readFully(block);
                List<String> lines = new ArrayList<>(List.of(new String(block, StandardCharsets.UTF_8).split("\n", -1)));
                if (start > 0) lines.remove(0);
                lines.replaceAll(LogTail::strip);
                lines.removeIf(String::isEmpty);
                if (lines.size() >= count || start == 0 || size >= 8L * 1024 * 1024)
                    return lines.size() > count ? new ArrayList<>(lines.subList(lines.size() - count, lines.size())) : lines;
            }
        }
    }

    private static String strip(String line) { return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line; }
}
