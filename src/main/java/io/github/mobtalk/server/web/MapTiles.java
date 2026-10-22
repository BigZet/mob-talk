package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** The world seen from above for the admin map: {@code <root>/<world>/<zoom>/<tx>_<tz>.png}, 256×256 pixels, 1 px = 2^zoom blocks.
 * The game hands in a chunk's top colours ({@link #update}, cheap: a queue); one background thread paints them into zoom 0 tiles,
 * then rebuilds the coarser zooms from their four children. Unexplored pixels stay transparent. */
public final class MapTiles implements MapSource {
    public static final int SIZE = 256, MAX_ZOOM = 4;
    private static final int CACHE = 24;

    private record Chunk(String world, int cx, int cz, int[] colors) { }
    private record Key(String world, int zoom, int tx, int tz) { }

    private final Path root;
    private final Consumer<String> log;
    private final ConcurrentLinkedQueue<Chunk> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Map<Key, BufferedImage> cache = new LinkedHashMap<>(32, 0.75f, true);
    /** Painted but not yet saved: never evicted. */
    private final Set<Key> unsaved = new java.util.HashSet<>();
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService painter = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-map"); thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });

    public MapTiles(Path root, Consumer<String> log) { this.root = root; this.log = log; }

    @Override public int tileSize(String world) { return SIZE; }
    @Override public int maxZoom(String world) { return MAX_ZOOM; }
    @Override public String name(String world) { return "mobtalk"; }

    /** Called with {"world","zoom","tx","tz"} for every tile that changed. */
    @Override public void listen(Consumer<JsonObject> listener) { listeners.add(listener); }

    /** One chunk's top view: 256 ARGB colours, row by row (z, then x); alpha 0 means «not known». Safe from the game thread. */
    public void update(String world, int cx, int cz, int[] colors) {
        if (colors.length != 256) throw new IllegalArgumentException("нужно 256 цветов");
        pending.add(new Chunk(world, cx, cz, colors.clone()));
        if (draining.compareAndSet(false, true)) painter.execute(this::drain);
    }

    /** The PNG of a tile, or null when nothing of it is explored. */
    @Override public byte[] tile(String world, int zoom, int tx, int tz) throws IOException {
        if (zoom < 0 || zoom > MAX_ZOOM) throw ApiException.badRequest("zoom — от 0 до " + MAX_ZOOM + ".");
        Path file = file(new Key(world, zoom, tx, tz));
        return file == null || !Files.isRegularFile(file) ? null : Files.readAllBytes(file);
    }

    /** When a tile last changed, for the ETag; 0 when there is none. */
    @Override public long modified(String world, int zoom, int tx, int tz) throws IOException {
        if (zoom < 0 || zoom > MAX_ZOOM) throw ApiException.badRequest("zoom — от 0 до " + MAX_ZOOM + ".");
        Path file = file(new Key(world, zoom, tx, tz));
        return file == null || !Files.isRegularFile(file) ? 0 : Files.getLastModifiedTime(file).toMillis();
    }

    /** Worlds that have tiles. */
    @Override public List<String> worlds() throws IOException {
        List<String> worlds = new ArrayList<>();
        if (!Files.isDirectory(root)) return worlds;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) worlds.add(unfold(dir.getFileName().toString()));
        }
        worlds.sort(null);
        return worlds;
    }

    /** The explored area of a world in blocks, from its zoom 0 tiles: {minX, minZ, maxX, maxZ} or null. */
    @Override public int[] bounds(String world) throws IOException {
        Path dir = root.resolve(fold(world)).resolve("0");
        if (!Files.isDirectory(dir)) return null;
        int[] box = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.png")) {
            for (Path file : files) {
                String[] parts = file.getFileName().toString().replace(".png", "").split("_");
                if (parts.length != 2) continue;
                try {
                    int tx = Integer.parseInt(parts[0]), tz = Integer.parseInt(parts[1]);
                    int x0 = tx * SIZE, z0 = tz * SIZE, x1 = x0 + SIZE - 1, z1 = z0 + SIZE - 1;
                    box = box == null ? new int[]{x0, z0, x1, z1} : new int[]{Math.min(box[0], x0), Math.min(box[1], z0), Math.max(box[2], x1), Math.max(box[3], z1)};
                } catch (NumberFormatException notOurs) { /* Skip. */ }
            }
        }
        return box;
    }

    /** Waits for every queued chunk to be painted and saved; tests and shutdown only. */
    public void flush() {
        try { painter.submit(() -> { }).get(60, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException stuck) { log.accept("карта: не успела дорисоваться: " + stuck); }
    }

    public void close() { flush(); painter.shutdown(); }

    private void drain() {
        draining.set(false);
        Set<Key> dirty = new LinkedHashSet<>();
        for (Chunk chunk; (chunk = pending.poll()) != null; ) {
            int tx = Math.floorDiv(chunk.cx * 16, SIZE), tz = Math.floorDiv(chunk.cz * 16, SIZE);
            Key key = new Key(chunk.world, 0, tx, tz);
            BufferedImage image = image(key);
            if (image == null) continue;
            int ox = chunk.cx * 16 - tx * SIZE, oz = chunk.cz * 16 - tz * SIZE;
            boolean changed = false;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int color = chunk.colors[z * 16 + x];
                if ((color >>> 24) == 0 || image.getRGB(ox + x, oz + z) == color) continue;
                image.setRGB(ox + x, oz + z, color);
                changed = true;
            }
            if (changed) { dirty.add(key); unsaved.add(key); }
        }
        for (int zoom = 0; zoom <= MAX_ZOOM && !dirty.isEmpty(); zoom++) {
            Set<Key> parents = new LinkedHashSet<>();
            for (Key key : dirty) {
                if (zoom > 0) { unsaved.add(key); rebuild(key); }
                save(key);
                unsaved.remove(key);
                if (zoom < MAX_ZOOM) parents.add(new Key(key.world, zoom + 1, Math.floorDiv(key.tx, 2), Math.floorDiv(key.tz, 2)));
            }
            dirty = parents;
        }
    }

    /** A coarser tile from its four children: each pixel is the average of the known pixels of a 2×2 block. */
    private void rebuild(Key key) {
        BufferedImage image = image(key);
        if (image == null) return;
        for (int quarter = 0; quarter < 4; quarter++) {
            int qx = quarter & 1, qz = quarter >> 1;
            BufferedImage child = image(new Key(key.world, key.zoom - 1, key.tx * 2 + qx, key.tz * 2 + qz));
            for (int z = 0; z < SIZE / 2; z++) for (int x = 0; x < SIZE / 2; x++) {
                int a = 0, r = 0, g = 0, b = 0, n = 0;
                for (int d = 0; d < 4; d++) {
                    int c = child == null ? 0 : child.getRGB(x * 2 + (d & 1), z * 2 + (d >> 1));
                    if ((c >>> 24) == 0) continue;
                    r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
                }
                if (n > 0) a = 0xFF;
                image.setRGB(qx * SIZE / 2 + x, qz * SIZE / 2 + z, n == 0 ? 0 : (a << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n));
            }
        }
    }

    private BufferedImage image(Key key) {
        BufferedImage image = cache.get(key);
        if (image != null) return image;
        Path file = file(key);
        if (file == null) return null;
        try {
            if (Files.isRegularFile(file)) {
                BufferedImage read = ImageIO.read(file.toFile());
                if (read != null && read.getWidth() == SIZE && read.getHeight() == SIZE) {
                    image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                    image.getGraphics().drawImage(read, 0, 0, null);
                }
            }
        } catch (IOException broken) { log.accept("карта: тайл " + file.getFileName() + " не читается, рисую заново"); }
        if (image == null) image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        cache.put(key, image);
        if (cache.size() > CACHE) {
            var oldest = cache.keySet().iterator();
            // Never the tile just asked for: with every other one unsaved it would be the only candidate, and its paint would be lost.
            while (cache.size() > CACHE && oldest.hasNext()) {
                Key candidate = oldest.next();
                if (!candidate.equals(key) && !unsaved.contains(candidate)) oldest.remove();
            }
        }
        return image;
    }

    private void save(Key key) {
        BufferedImage image = cache.get(key);
        Path file = file(key);
        if (image == null || file == null) return;
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, png.toByteArray());
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { log.accept("карта: не сохранился " + file.getFileName() + ": " + failure.getMessage()); return; }
        JsonObject event = Json.obj("world", key.world, "zoom", key.zoom, "tx", key.tx, "tz", key.tz);
        for (Consumer<JsonObject> listener : listeners) {
            try { listener.accept(event); } catch (RuntimeException ignored) { /* The tile is saved either way. */ }
        }
    }

    /** The file of a tile; null for a world id that cannot be a folder name. */
    private Path file(Key key) {
        if (!key.world.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?") || key.world.contains("..")) return null;
        return root.resolve(fold(key.world)).resolve(Integer.toString(key.zoom)).resolve(key.tx + "_" + key.tz + ".png");
    }

    /** {@code minecraft:overworld} ⇄ {@code minecraft~overworld}: a colon is not allowed in a Windows file name; a slash becomes a dash. */
    static String fold(String world) { return world.replace(':', '~').replace('/', '+'); }
    static String unfold(String folder) { return folder.replace('~', ':').replace('+', '/'); }
}
