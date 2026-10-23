package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Where players died, for the admin's map (death markers). Coordinates stay out of the history by design, so the points live
 * in their own file next to the waypoints ({@code config/mobtalk-deaths.json}), the last {@link #LIMIT} of them. */
public final class Deaths {
    public static final int LIMIT = 500;
    private final Path file;
    private final JsonArray points;

    public Deaths(Path file) {
        this.file = file;
        JsonArray loaded = new JsonArray();
        try {
            if (file != null && Files.isRegularFile(file)) {
                JsonElement tree = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (tree.isJsonArray()) loaded = tree.getAsJsonArray();
            }
        } catch (IOException | RuntimeException broken) { /* An empty list; the file is rewritten on the next death. */ }
        points = loaded;
    }

    public synchronized void add(long t, UUID player, String name, String world, int x, int y, int z, String cause) {
        points.add(Json.obj("t", t, "player", Json.player(player, name), "world", world, "x", x, "y", y, "z", z, "cause", cause));
        while (points.size() > LIMIT) points.remove(0);
        try { if (file != null) { Files.createDirectories(file.getParent()); Files.writeString(file, Json.GSON.toJson(points), StandardCharsets.UTF_8); } }
        catch (IOException ignored) { /* The point stays in memory; the next death writes the file again. */ }
    }

    /** Map markers ({@code kind: death}) of one world. */
    public synchronized List<JsonObject> markers(String world) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement point : points) {
            JsonObject p = point.getAsJsonObject();
            if (!world.equals(Json.string(p, "world"))) continue;
            String who = Json.string(p.getAsJsonObject("player"), "name");
            out.add(Json.obj("kind", "death", "id", "death-" + p.get("t").getAsLong() + "-" + who, "name", "† " + who + ": " + Json.string(p, "cause"),
                    "x", p.get("x"), "z", p.get("z"), "t", p.get("t")));
        }
        return out;
    }
}
