package io.github.mobtalk.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** «Новый мир» из веб-админки (раздел 12.8 плана). Мир, в котором сервер работает, не сдвинуть, пока он открыт, поэтому в два шага:
 * админка оставляет в папке сервера заявку {@link #MARKER} и останавливает сервер; при следующем старте выделенного сервера, до
 * загрузки мира, {@link #apply} переносит папку мира в {@code world-backups/<имя>-<время>} (данные Mob Talk лежат в ней же и уходят
 * с ней) и пишет сид и тип мира в {@code server.properties}. Перезапуск — скрипт {@code run-local-server.ps1}, systemd или Docker. */
public final class NewWorld {
    private NewWorld() { }
    public static final String MARKER = "mobtalk-new-world.json";
    /** Типы мира, которые принимает {@code level-type}. */
    public static final Set<String> TYPES = Set.of("minecraft:normal", "minecraft:flat", "minecraft:large_biomes", "minecraft:amplified");

    /** Папка мира из {@code server.properties} ({@code level-name}, по умолчанию {@code world}). */
    public static String levelName(Path runDir) {
        for (String line : properties(runDir)) if (line.startsWith("level-name=")) {
            String name = line.substring("level-name=".length()).strip();
            return name.isEmpty() ? "world" : name;
        }
        return "world";
    }

    /** Заявка на новый мир: сид (пусто — случайный), тип (пусто — как был) и кто попросил. */
    public static void request(Path runDir, String seed, String type, String by) throws IOException {
        if (type != null && !type.isBlank() && !TYPES.contains(type)) throw new IllegalArgumentException("Тип мира — один из " + TYPES);
        if (seed != null && seed.length() > 64) throw new IllegalArgumentException("Сид длиннее 64 символов");
        JsonObject json = new JsonObject();
        json.addProperty("seed", seed == null ? "" : seed.strip());
        json.addProperty("type", type == null ? "" : type.strip());
        json.addProperty("by", by == null ? "" : by);
        json.addProperty("at", System.currentTimeMillis());
        Files.writeString(runDir.resolve(MARKER), json.toString(), StandardCharsets.UTF_8);
    }

    /** При старте выделенного сервера: заявки нет — ""; есть — старый мир в резерв, новые сид и тип, строка для журнала. */
    public static String apply(Path runDir, LocalDateTime now) throws IOException {
        Path marker = runDir.resolve(MARKER);
        if (!Files.exists(marker)) return "";
        JsonObject request = JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8)).getAsJsonObject();
        String seed = request.has("seed") ? request.get("seed").getAsString() : "", type = request.has("type") ? request.get("type").getAsString() : "";
        String level = levelName(runDir);
        Path world = runDir.resolve(level);
        String moved = "";
        if (Files.isDirectory(world)) {
            Path backups = Files.createDirectories(runDir.resolve("world-backups"));
            Path target = backups.resolve(level + "-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.move(world, target);
            moved = runDir.relativize(target).toString();
        }
        List<String> lines = new ArrayList<>(properties(runDir));
        put(lines, "level-seed", seed);
        if (!type.isBlank()) put(lines, "level-type", type.replace(":", "\\:"));
        Files.write(runDir.resolve("server.properties"), lines, StandardCharsets.UTF_8);
        Files.delete(marker);
        return "Новый мир «" + level + "»" + (seed.isBlank() ? " со случайным сидом" : " с сидом " + seed) + (type.isBlank() ? "" : ", тип " + type)
                + (moved.isEmpty() ? "" : "; старый мир перенесён в " + moved);
    }

    private static List<String> properties(Path runDir) {
        try { Path file = runDir.resolve("server.properties"); return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of(); }
        catch (IOException unreadable) { return List.of(); }
    }
    private static void put(List<String> lines, String key, String value) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(key + "=")) { lines.set(i, key + "=" + value); return; }
        lines.add(key + "=" + value);
    }
}
