package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The 3D map (BlueMap) behind the admin page: its webapp and map files served under {@code /map3d/} for a signed-in admin, and
 * rendering only while someone looks. The page sends a keepalive; the first one starts rendering ({@code bluemap start}), three
 * minutes without one stop it ({@code bluemap stop}). BlueMap's own web server stays off, so its live player list is answered here.
 * BlueMap takes commands only after «BlueMap loaded!» in the log and remembers start/stop across restarts, so once it has loaded
 * the first thing sent is a stop (or a start, if a page is already looking). */
public final class BlueMapControl {
    public static final long IDLE = 3 * 60_000L;
    /** BlueMap's webapp may compile shaders and parse with workers: the only place with eval and blob workers, framed by the admin. */
    public static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-eval'; worker-src 'self' blob:; img-src 'self' data: blob:; "
            + "style-src 'self' 'unsafe-inline'; connect-src 'self'; font-src 'self' data:; frame-ancestors 'self'; base-uri 'none'; form-action 'self'";
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s*%");

    static final String LOADED = "BlueMap loaded!";

    private final Path webroot, log;
    private final GameView game;
    private final LongSupplier clock;
    private long lastKeepalive;
    private boolean rendering, settled, loaded;
    private JsonObject cachedStatus;
    private long cachedAt;

    public BlueMapControl(Path webroot, Path log, GameView game, LongSupplier clock) { this.webroot = webroot; this.log = log; this.game = game; this.clock = clock; }

    /** Whether BlueMap has said it is loaded in this run's log (latest.log starts anew with every server start). */
    synchronized boolean loaded() {
        if (loaded || log == null || !Files.isRegularFile(log)) return loaded;
        try (Stream<String> lines = Files.lines(log, java.nio.charset.StandardCharsets.UTF_8)) { loaded = lines.anyMatch(line -> line.contains(LOADED)); }
        catch (IOException | java.io.UncheckedIOException unreadable) { /* Not yet. */ }
        return loaded;
    }

    public boolean available() { return Files.isRegularFile(webroot.resolve("index.html")); }

    /** The page is looking: start rendering if it is paused. */
    public synchronized JsonObject keepalive() {
        if (!available()) throw ApiException.unavailable("BlueMap не установлен на сервере.");
        lastKeepalive = clock.getAsLong();
        if (!loaded()) return Json.obj("ok", true, "rendering", false, "loading", true);
        settled = true;
        if (!rendering) {
            JsonObject answer = game.runCommand("bluemap start");
            rendering = answer.has("ok") && answer.get("ok").getAsBoolean();
            cachedStatus = null;
        }
        return Json.obj("ok", true, "rendering", rendering);
    }

    /** Every half minute: nobody looked for three minutes — pause. Right after BlueMap loads, settles what an earlier run left going. */
    public synchronized void sweep() {
        if (!available() || !loaded()) return;
        long now = clock.getAsLong();
        if (!settled) {
            settled = true;
            boolean looking = lastKeepalive > 0 && now - lastKeepalive <= IDLE;
            JsonObject answer = game.runCommand(looking ? "bluemap start" : "bluemap stop");
            rendering = looking && answer.has("ok") && answer.get("ok").getAsBoolean();
            cachedStatus = null;
        } else if (rendering && now - lastKeepalive > IDLE) {
            game.runCommand("bluemap stop");
            rendering = false;
            cachedStatus = null;
        }
    }

    /** {"available","rendering","progress":0..1|null,"lastRender":t|null}; the command's answer is read at most every 10 s. */
    public synchronized JsonObject status() {
        if (!available()) return Json.obj("available", false, "rendering", false, "progress", null, "lastRender", null);
        if (!loaded()) return Json.obj("available", true, "loading", true, "rendering", false, "progress", null, "lastRender", lastRender());
        long now = clock.getAsLong();
        if (cachedStatus != null && now - cachedAt < 10_000) return cachedStatus;
        Double progress = null;
        try {
            JsonObject answer = game.runCommand("bluemap");
            JsonElement output = answer.get("output");
            if (output != null && output.isJsonArray()) for (JsonElement line : output.getAsJsonArray()) {
                Matcher percent = PERCENT.matcher(line.getAsString());
                if (percent.find()) { progress = Math.min(1, Double.parseDouble(percent.group(1).replace(',', '.')) / 100); break; }
            }
        } catch (RuntimeException unreadable) { /* No progress then. */ }
        cachedStatus = Json.obj("available", true, "rendering", rendering, "progress", progress, "lastRender", lastRender());
        cachedAt = now;
        return cachedStatus;
    }

    /** A file of the webapp or the maps: {@code X} or its stored {@code X.gz} (sent as gzip); a missing tile is 204, not 404. */
    public Response file(String path) throws IOException {
        if (!available()) return Response.text(404, "text/plain; charset=utf-8", "BlueMap не установлен на сервере.");
        String clean = StaticFiles.clean(path);
        if (clean == null) return Response.text(404, "text/plain; charset=utf-8", "Не найдено");
        if (clean.matches("maps/[^/]+/live/players\\.json")) return Response.json(players(clean.split("/")[1]));
        if (clean.matches("maps/[^/]+/live/markers\\.json")) return Response.json(new JsonObject());
        Path base = webroot.toAbsolutePath().normalize(), file = base.resolve(clean).normalize();
        if (!file.startsWith(base)) return Response.text(404, "text/plain; charset=utf-8", "Не найдено");
        String type = type(clean);
        if (Files.isRegularFile(file)) return Response.bytes(200, type, Files.readAllBytes(file)).header("Cache-Control", "no-cache");
        Path gz = file.resolveSibling(file.getFileName() + ".gz");
        if (Files.isRegularFile(gz)) return Response.bytes(200, type, Files.readAllBytes(gz)).header("Content-Encoding", "gzip").header("Cache-Control", "no-cache");
        if (clean.startsWith("maps/") && clean.contains("/tiles/")) return Response.bytes(204, type, new byte[0]);
        return Response.text(404, "text/plain; charset=utf-8", "Не найдено");
    }

    /** BlueMap's live player list for a map, from the admin's positions: players in another world are «foreign». */
    JsonObject players(String mapId) {
        String id = mapId.toLowerCase(Locale.ROOT);
        String world = id.contains("nether") ? "minecraft:the_nether" : id.contains("end") ? "minecraft:the_end" : "minecraft:overworld";
        JsonArray players = new JsonArray();
        for (JsonElement element : game.positions().getAsJsonArray("players")) {
            JsonObject place = element.getAsJsonObject();
            JsonObject player = place.getAsJsonObject("player");
            players.add(Json.obj("uuid", Json.string(player, "uuid"), "name", Json.string(player, "name"), "foreign", !world.equals(Json.string(place, "world")),
                    "position", Json.obj("x", place.get("x"), "y", place.get("y"), "z", place.get("z")),
                    "rotation", Json.obj("pitch", 0, "yaw", place.has("yaw") ? place.get("yaw") : 0, "roll", 0)));
        }
        return Json.obj("players", players);
    }

    private Long lastRender() {
        Path maps = webroot.resolve("maps");
        if (!Files.isDirectory(maps)) return null;
        try (Stream<Path> dirs = Files.walk(maps, 4)) {
            long newest = dirs.filter(Files::isDirectory).mapToLong(dir -> {
                try { return Files.getLastModifiedTime(dir).toMillis(); } catch (IOException gone) { return 0; }
            }).max().orElse(0);
            return newest == 0 ? null : newest;
        } catch (IOException | java.io.UncheckedIOException unreadable) { return null; }
    }

    private static String type(String path) {
        for (String binary : List.of(".prbm", ".bin")) if (path.endsWith(binary)) return "application/octet-stream";
        return StaticFiles.type(path);
    }
}
