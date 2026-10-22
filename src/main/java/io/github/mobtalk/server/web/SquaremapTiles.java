package io.github.mobtalk.server.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** squaremap's own tiles read from disk, its web server off (Дмитрий chose the detailed map). squaremap writes
 * {@code web/tiles/<namespace>_<path>/<zoom>/<x>_<z>.png}, 512×512, its zoom {@code max} (per world {@code settings.json},
 * 3 by default) being 1 px = 1 block, each step down half the detail; the API's zoom is {@code max − squaremap zoom}. */
public final class SquaremapTiles implements MapSource {
    public static final int SIZE = 512;

    private final Path tiles;
    private final Consumer<String> log;
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Long> seen = new HashMap<>();
    private long lastScan;

    public SquaremapTiles(Path tiles, Consumer<String> log) { this.tiles = tiles; this.log = log; }

    /** The first of the usual places that holds tiles: {@code squaremap/web/tiles}, {@code config/squaremap/web/tiles}. */
    public static Path find(Path gameDir, String configured) {
        if (configured != null && !configured.isBlank()) return gameDir.resolve(configured);
        for (String candidate : List.of("squaremap/web/tiles", "config/squaremap/web/tiles"))
            if (Files.isDirectory(gameDir.resolve(candidate))) return gameDir.resolve(candidate);
        return gameDir.resolve("squaremap/web/tiles");
    }

    public boolean available() { return Files.isDirectory(tiles); }

    @Override public int tileSize(String world) { return SIZE; }
    @Override public String name(String world) { return "squaremap"; }

    /** Whether squaremap has drawn anything of this world (it skips the End). */
    public boolean has(String world) {
        Path dir = folder(world);
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (Stream<Path> zooms = Files.list(dir)) {
            return zooms.filter(Files::isDirectory).anyMatch(zoom -> {
                try (Stream<Path> files = Files.list(zoom)) { return files.anyMatch(f -> f.getFileName().toString().endsWith(".png")); }
                catch (IOException unreadable) { return false; }
            });
        } catch (IOException unreadable) { return false; }
    }

    /** squaremap's zoom levels of the world, from its {@code settings.json} (3 when it says nothing). */
    @Override public int maxZoom(String world) { return squaremapMax(world); }

    @Override public byte[] tile(String world, int zoom, int tx, int tz) throws IOException {
        Path file = file(world, zoom, tx, tz);
        return file == null || !Files.isRegularFile(file) ? null : Files.readAllBytes(file);
    }

    @Override public long modified(String world, int zoom, int tx, int tz) throws IOException {
        Path file = file(world, zoom, tx, tz);
        return file == null || !Files.isRegularFile(file) ? 0 : Files.getLastModifiedTime(file).toMillis();
    }

    @Override public List<String> worlds() throws IOException {
        List<String> worlds = new ArrayList<>();
        if (!available()) return worlds;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(tiles, Files::isDirectory)) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                int underscore = name.indexOf('_');
                worlds.add(underscore < 0 ? name : name.substring(0, underscore) + ":" + name.substring(underscore + 1));
            }
        }
        worlds.sort(null);
        return worlds;
    }

    @Override public int[] bounds(String world) throws IOException {
        Path dir = folder(world);
        if (dir == null) return null;
        Path detailed = dir.resolve(Integer.toString(squaremapMax(world)));
        if (!Files.isDirectory(detailed)) return null;
        int[] box = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(detailed, "*.png")) {
            for (Path file : files) {
                String[] parts = file.getFileName().toString().replace(".png", "").split("_");
                if (parts.length != 2) continue;
                try {
                    int x0 = Integer.parseInt(parts[0]) * SIZE, z0 = Integer.parseInt(parts[1]) * SIZE;
                    box = box == null ? new int[]{x0, z0, x0 + SIZE - 1, z0 + SIZE - 1}
                            : new int[]{Math.min(box[0], x0), Math.min(box[1], z0), Math.max(box[2], x0 + SIZE - 1), Math.max(box[3], z0 + SIZE - 1)};
                } catch (NumberFormatException notATile) { /* Skip. */ }
            }
        }
        return box;
    }

    @Override public void listen(Consumer<JsonObject> listener) { listeners.add(listener); }

    /** Finds tiles squaremap changed since the last call and tells the listeners; the admin calls it now and then while a page is open. */
    @Override public void poll() { scan(); }

    public synchronized int scan() {
        if (!available() || listeners.isEmpty()) return 0;
        long since = lastScan;
        lastScan = System.currentTimeMillis();
        int changed = 0;
        try (Stream<Path> files = Files.walk(tiles, 3)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".png"))::iterator) {
                long modified = Files.getLastModifiedTime(file).toMillis();
                String key = tiles.relativize(file).toString();
                Long before = seen.put(key, modified);
                if (since == 0 || (before != null && before == modified) || modified < since - 1000) continue;
                JsonObject event = event(file);
                if (event == null) continue;
                changed++;
                for (Consumer<JsonObject> listener : listeners) {
                    try { listener.accept(event); } catch (RuntimeException ignored) { /* Next one. */ }
                }
            }
        } catch (IOException | java.io.UncheckedIOException failure) { log.accept("squaremap: тайлы не просматриваются: " + failure.getMessage()); }
        return changed;
    }

    private JsonObject event(Path file) {
        Path zoomDir = file.getParent(), worldDir = zoomDir.getParent();
        String[] parts = file.getFileName().toString().replace(".png", "").split("_");
        try {
            String world = worlds().stream().filter(w -> w.replace(':', '_').equals(worldDir.getFileName().toString())).findFirst().orElse(null);
            if (world == null || parts.length != 2) return null;
            int zoom = squaremapMax(world) - Integer.parseInt(zoomDir.getFileName().toString());
            if (zoom < 0) return null;
            return Json.obj("world", world, "zoom", zoom, "tx", Integer.parseInt(parts[0]), "tz", Integer.parseInt(parts[1]));
        } catch (IOException | NumberFormatException notATile) { return null; }
    }

    private Path file(String world, int zoom, int tx, int tz) {
        if (zoom < 0 || zoom > 8) throw ApiException.badRequest("zoom — от 0 до " + maxZoom(world) + ".");
        Path dir = folder(world);
        if (dir == null) return null;
        int squaremapZoom = squaremapMax(world) - zoom;
        if (squaremapZoom < 0) return null;
        return dir.resolve(Integer.toString(squaremapZoom)).resolve(tx + "_" + tz + ".png");
    }

    private Path folder(String world) {
        if (world == null || !world.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || world.contains("..")) return null;
        return tiles.resolve(world.replace(':', '_').replace('/', '_'));
    }

    /** {@code zoom.max} of the world's {@code settings.json}, 3 when it has none. */
    int squaremapMax(String world) {
        Path dir = folder(world);
        if (dir == null) return 3;
        try {
            Path settings = dir.resolve("settings.json");
            if (Files.isRegularFile(settings)) {
                JsonElement zoom = JsonParser.parseString(Files.readString(settings)).getAsJsonObject().get("zoom");
                if (zoom != null && zoom.isJsonObject() && zoom.getAsJsonObject().has("max")) return zoom.getAsJsonObject().get("max").getAsInt();
            }
        } catch (IOException | RuntimeException broken) { /* The default. */ }
        return 3;
    }
}
