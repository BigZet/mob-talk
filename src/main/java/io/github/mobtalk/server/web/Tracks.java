package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Where players walked, for the map's paths: the admin's own store ({@code <root>/<yyyyMMdd>.bin}, UTC days), never the history
 * or the log. A point is kept every 5 s, or sooner after a move of more than 4 blocks; standing still writes nothing new for a minute.
 * Record: long t, long uuidHigh, long uuidLow, byte world (index into {@code worlds.json}), float x, y, z. */
public final class Tracks {
    public static final long EVERY = 5_000, STILL = 60_000;
    public static final double STEP = 4;
    private static final int RECORD = 8 * 3 + 1 + 4 * 3;

    private record Last(long t, String world, double x, double y, double z) { }

    private final Path root;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private volatile int keepDays;
    private final Map<UUID, Last> last = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final List<String> worlds = new ArrayList<>();
    private final java.util.Set<Path> checked = new java.util.HashSet<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-tracks"); thread.setDaemon(true); return thread;
    });

    public Tracks(Path root, LongSupplier clock, int keepDays, Consumer<String> log) {
        this.root = root; this.clock = clock; this.keepDays = Math.max(1, keepDays); this.log = log;
        try {
            Path file = root.resolve("worlds.json");
            if (Files.isRegularFile(file)) for (JsonElement world : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) worlds.add(world.getAsString());
            Path known = root.resolve("names.json");
            if (Files.isRegularFile(known))
                for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(known)).getAsJsonObject().entrySet())
                    names.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
        } catch (IOException | RuntimeException broken) { log.accept("пути: служебные файлы не прочитались: " + broken.getMessage()); }
    }

    /** Feeds a {@code GET /api/players/positions} answer; keeps the points that pass the 5 s / 4 blocks rule. */
    public void observe(JsonObject positions) {
        if (positions == null || !positions.has("players")) return;
        long t = Json.number(positions, "t", clock.getAsLong());
        for (JsonElement element : positions.getAsJsonArray("players")) {
            JsonObject place = element.getAsJsonObject();
            if (place.has("online") && !place.get("online").getAsBoolean()) continue;
            JsonObject player = place.getAsJsonObject("player");
            try {
                record(UUID.fromString(Json.string(player, "uuid")), Json.string(player, "name"), t, Json.string(place, "world"),
                        place.get("x").getAsDouble(), place.get("y").getAsDouble(), place.get("z").getAsDouble());
            } catch (RuntimeException incomplete) { /* A place without coordinates: nothing to keep. */ }
        }
    }

    public void record(UUID player, String name, long t, String world, double x, double y, double z) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        Last before = last.get(player);
        if (before != null) {
            double moved = Math.sqrt(sq(x - before.x) + sq(y - before.y) + sq(z - before.z));
            boolean sameWorld = world.equals(before.world);
            if (sameWorld && moved > STEP) { /* Keep: a real move. */ }
            else if (sameWorld && t - before.t < EVERY) return;
            else if (sameWorld && moved < 0.5 && t - before.t < STILL) return;
        }
        last.put(player, new Last(t, world, x, y, z));
        if (name != null && !name.equals(names.put(player, name))) writer.execute(this::saveNames);
        writer.execute(() -> write(player, t, world, x, y, z));
    }

    /** {"tracks":[{"player":{uuid,name},"points":[[t,x,y,z],…]}]} for one world, each player thinned to about {@code points}. */
    public JsonObject query(String world, long from, long to, UUID only, int points) {
        flush();
        Map<UUID, List<double[]>> byPlayer = new LinkedHashMap<>();
        int index;
        synchronized (worlds) { index = worlds.indexOf(world); }
        if (index >= 0) {
            LocalDate first = day(Math.max(from, clock.getAsLong() - (keepDays + 1L) * 86_400_000L)), lastDay = day(Math.min(to, clock.getAsLong() + 86_400_000L));
            for (LocalDate day = first; !day.isAfter(lastDay); day = day.plusDays(1)) {
                Path file = root.resolve(HistoryStore.DAY.format(day) + ".bin");
                if (!Files.isRegularFile(file)) continue;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    while (true) {
                        long t = in.readLong();
                        UUID player = new UUID(in.readLong(), in.readLong());
                        int w = in.readUnsignedByte();
                        float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
                        if (w != index || t < from || t > to || (only != null && !only.equals(player))) continue;
                        byPlayer.computeIfAbsent(player, k -> new ArrayList<>()).add(new double[]{t, x, y, z});
                    }
                } catch (EOFException end) {
                    // The last whole record has been read; a torn one after it is ignored.
                } catch (IOException failure) { log.accept("пути: " + file.getFileName() + " не читается: " + failure.getMessage()); }
            }
        }
        JsonArray tracks = new JsonArray();
        int wanted = Math.max(2, Math.min(20_000, points));
        byPlayer.forEach((player, list) -> {
            JsonArray array = new JsonArray();
            int n = list.size();
            double stride = n <= wanted ? 1 : (n - 1) / (double) (wanted - 1);
            int previous = -1;
            for (double i = 0; Math.round(i) < n; i += stride) {
                int at = (int) Math.round(i);
                if (at == previous) continue;
                previous = at;
                double[] p = list.get(at);
                array.add(Json.array(List.of((long) p[0], round(p[1]), round(p[2]), round(p[3]))));
            }
            if (previous != n - 1) { double[] p = list.get(n - 1); array.add(Json.array(List.of((long) p[0], round(p[1]), round(p[2]), round(p[3])))); }
            tracks.add(Json.obj("player", Json.player(player, names.getOrDefault(player, player.toString().substring(0, 8))), "points", array));
        });
        return Json.obj("world", world, "from", from, "to", to, "tracks", tracks);
    }

    public void keepDays(int days) { keepDays = Math.max(1, days); }

    /** Deletes days past the keeping time; returns how many files. */
    public int prune() {
        flush();
        LocalDate oldest = day(clock.getAsLong()).minusDays(keepDays);
        int removed = 0;
        if (!Files.isDirectory(root)) return 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.bin")) {
            for (Path file : files) {
                try { if (LocalDate.parse(file.getFileName().toString().substring(0, 8), HistoryStore.DAY).isBefore(oldest)) { Files.deleteIfExists(file); removed++; } }
                catch (RuntimeException notADay) { /* Not ours. */ }
            }
        } catch (IOException failure) { log.accept("пути: не удалось почистить: " + failure.getMessage()); }
        return removed;
    }

    public void flush() {
        try { writer.submit(() -> { }).get(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException stuck) { log.accept("пути: запись не успела: " + stuck); }
    }

    public void close() { flush(); writer.shutdown(); }

    private void write(UUID player, long t, String world, double x, double y, double z) {
        try {
            int index;
            synchronized (worlds) {
                index = worlds.indexOf(world);
                if (index < 0) {
                    if (worlds.size() >= 255) return;
                    worlds.add(world);
                    index = worlds.size() - 1;
                    Files.createDirectories(root);
                    Files.writeString(root.resolve("worlds.json"), Json.GSON.toJson(worlds), StandardCharsets.UTF_8);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(RECORD);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeLong(t); out.writeLong(player.getMostSignificantBits()); out.writeLong(player.getLeastSignificantBits());
                out.writeByte(index); out.writeFloat((float) x); out.writeFloat((float) y); out.writeFloat((float) z);
            }
            Files.createDirectories(root);
            Path file = root.resolve(HistoryStore.DAY.format(day(t)) + ".bin");
            // A record torn by a crash would shift every later one: cut the file back to whole records once per run.
            if (checked.add(file) && Files.isRegularFile(file) && Files.size(file) % RECORD != 0)
                try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) { channel.truncate(Files.size(file) / RECORD * RECORD); }
            Files.write(file, bytes.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) { log.accept("пути: не записалось: " + failure.getMessage()); }
    }

    private void saveNames() {
        try {
            JsonObject json = new JsonObject();
            names.forEach((uuid, name) -> json.addProperty(uuid.toString(), name));
            Files.createDirectories(root);
            Files.writeString(root.resolve("names.json"), Json.GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException failure) { log.accept("пути: имена не записались: " + failure.getMessage()); }
    }

    private static LocalDate day(long millis) { return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(); }
    private static double sq(double v) { return v * v; }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
