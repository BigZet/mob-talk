package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** The admin's named places for teleports, in {@code config/mobtalk-waypoints.json} (a file of the server, never in history). */
public final class Waypoints {
    private final Path file;
    private final JsonArray points;

    public Waypoints(Path file) {
        this.file = file;
        JsonArray loaded = new JsonArray();
        try {
            if (file != null && Files.isRegularFile(file)) {
                JsonElement tree = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (tree.isJsonArray()) loaded = tree.getAsJsonArray();
            }
        } catch (IOException | RuntimeException broken) { /* An empty list; the file is rewritten on the next change. */ }
        points = loaded;
    }

    public synchronized JsonObject list() { return Json.obj("waypoints", points.deepCopy()); }

    public synchronized JsonObject find(String id) {
        for (JsonElement point : points) if (id.equals(Json.string(point.getAsJsonObject(), "id"))) return point.getAsJsonObject().deepCopy();
        return null;
    }

    public synchronized JsonObject add(JsonObject body) throws IOException {
        String name = Json.string(body, "name"), world = Json.string(body, "world");
        if (name == null || name.isBlank() || name.length() > 64) throw ApiException.badRequest("Имя точки — от 1 до 64 символов.");
        if (world == null || world.isBlank()) throw ApiException.badRequest("Нужен мир, например minecraft:overworld.");
        double x = coordinate(body, "x"), y = coordinate(body, "y"), z = coordinate(body, "z");
        JsonObject point = Json.obj("id", UUID.randomUUID().toString().substring(0, 8), "name", name.strip(), "world", world, "x", x, "y", y, "z", z);
        points.add(point);
        save();
        return point.deepCopy();
    }

    public synchronized boolean remove(String id) throws IOException {
        for (int i = 0; i < points.size(); i++)
            if (id.equals(Json.string(points.get(i).getAsJsonObject(), "id"))) { points.remove(i); save(); return true; }
        return false;
    }

    static double coordinate(JsonObject body, String key) {
        JsonElement value = body.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw ApiException.badRequest("Нужна координата " + key + ".");
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || Math.abs(number) > 30_000_000) throw ApiException.badRequest("Координата " + key + " за краем мира.");
        return number;
    }

    private void save() throws IOException {
        if (file == null) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Json.GSON.toJson(points), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
