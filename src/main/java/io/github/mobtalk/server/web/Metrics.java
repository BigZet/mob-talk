package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** The server's vital signs over time. Ten-second points for 48 hours ({@code raw/<day>.bin}) and their five-minute roll-ups for
 * 90 days ({@code <day>.bin}), both in memory for reading and on disk to survive a restart. Recording only touches memory; one
 * writer thread does the disk. Days are UTC so a file never straddles a clock change. */
public final class Metrics {
    public static final int STEP = 10, ROLLUP = 300;
    static final long RAW_KEEP = 48L * 3600, ROLLUP_KEEP = 90L * 86400;
    private static final int N = MetricSeries.values().length;

    private final Path dir;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final Deque<MetricsFile.Point> raw = new ArrayDeque<>();
    private final List<MetricsFile.Point> rollups = new ArrayList<>();
    private final List<MetricsFile.Point> bucket = new ArrayList<>();
    private long bucketStart = -1;
    private final Set<Path> repaired = new HashSet<>();
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private volatile JsonObject latest;
    private long lastPrunedDay = -1;
    private final java.util.Queue<Map.Entry<Path, MetricsFile.Point>> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-metrics"); thread.setDaemon(true); return thread;
    });

    public Metrics(Path dir, LongSupplier clock, Consumer<String> log) { this.dir = dir; this.clock = clock; this.log = log; }

    /** Every new point as {@code /api/metrics/now} shows it. */
    public void listen(Consumer<JsonObject> listener) { listeners.add(listener); }

    /** Reads what earlier runs kept and rolls up any five minutes they left open. Call once, off the game thread. */
    public synchronized void load() {
        long now = clock.getAsLong() / 1000;
        List<MetricsFile.Point> stored = new ArrayList<>();
        try {
            for (Path file : files(dir.resolve("raw"))) stored.addAll(MetricsFile.read(file));
            for (Path file : files(dir)) for (MetricsFile.Point point : MetricsFile.read(file)) if (point.t() >= now - ROLLUP_KEEP) rollups.add(point);
        } catch (IOException failure) { log.accept("метрики: не прочитались: " + failure.getMessage()); }
        stored.sort((a, b) -> Long.compare(a.t(), b.t()));
        for (MetricsFile.Point point : stored) if (point.t() >= now - RAW_KEEP) raw.add(point);
        rollups.sort((a, b) -> Long.compare(a.t(), b.t()));
        // Five-minute buckets the last run did not close (it stopped mid-bucket) are rolled up from its raw points.
        long lastRollup = rollups.isEmpty() ? Long.MIN_VALUE : rollups.get(rollups.size() - 1).t();
        List<MetricsFile.Point> closed = new ArrayList<>();
        for (MetricsFile.Point point : stored) {
            long start = point.t() - Math.floorMod(point.t(), ROLLUP);
            if (start <= lastRollup) continue;
            if (start != bucketStart && bucketStart >= 0) closed.add(foldBucket());
            bucketStart = start;
            bucket.add(point);
        }
        if (bucketStart >= 0 && bucketStart + ROLLUP <= now) { closed.add(foldBucket()); bucketStart = -1; }
        rollups.addAll(closed);
        writeRollups(closed);
        if (!raw.isEmpty()) latest = nowJson(raw.peekLast(), List.of());
    }

    /** One sample, normally every ten seconds; series left out are «no value». Cheap: memory only. */
    public void record(Map<MetricSeries, Double> values, List<JsonObject> worlds) { record(clock.getAsLong() / 1000, values, worlds); }

    public synchronized void record(long tSeconds, Map<MetricSeries, Double> values, List<JsonObject> worlds) {
        float[] array = new float[N];
        Arrays.fill(array, Float.NaN);
        values.forEach((series, value) -> { if (value != null && Double.isFinite(value)) array[series.ordinal()] = value.floatValue(); });
        MetricsFile.Point point = new MetricsFile.Point(tSeconds, array);
        raw.addLast(point);
        while (!raw.isEmpty() && raw.peekFirst().t() < tSeconds - RAW_KEEP) raw.pollFirst();
        Path rawFile = dir.resolve("raw").resolve(day(tSeconds) + ".bin");
        queue(rawFile, point);
        long start = tSeconds - Math.floorMod(tSeconds, ROLLUP);
        if (bucketStart >= 0 && start != bucketStart) {
            MetricsFile.Point rolled = foldBucket();
            rollups.add(rolled);
            while (!rollups.isEmpty() && rollups.get(0).t() < tSeconds - ROLLUP_KEEP) rollups.remove(0);
            writeRollups(List.of(rolled));
        }
        bucketStart = start;
        bucket.add(point);
        long today = tSeconds / 86400;
        if (today != lastPrunedDay) { lastPrunedDay = today; writer.execute(() -> prune(tSeconds)); }
        latest = nowJson(point, worlds);
        for (Consumer<JsonObject> listener : listeners) {
            try { listener.accept(latest); } catch (RuntimeException ignored) { /* The point is kept either way. */ }
        }
    }

    public JsonObject now() { JsonObject last = latest; return last == null ? Json.obj("t", null, "values", new JsonObject(), "worlds", new JsonArray()) : last; }

    /** Columns for uPlot: bucket starts in seconds and one array per series, thinned to about {@code points}. */
    public synchronized JsonObject query(long fromMillis, long toMillis, int points, Collection<MetricSeries> wanted) {
        long from = fromMillis / 1000, to = toMillis / 1000, now = clock.getAsLong() / 1000;
        if (to < from) throw ApiException.badRequest("Начало периода позже конца.");
        boolean useRaw = from >= now - RAW_KEEP;
        int base = useRaw ? STEP : ROLLUP;
        long span = Math.max(1, to - from);
        long step = (long) base * Math.max(1, (long) Math.ceil(span / (double) base / Math.max(1, points)));
        long first = from - Math.floorMod(from, step);
        int count = (int) ((to - first) / step) + 1;
        List<MetricSeries> series = wanted.isEmpty() ? List.of(MetricSeries.values()) : List.copyOf(wanted);
        float[][] buffers = new float[series.size()][];
        List<MetricsFile.Point> source = new ArrayList<>(useRaw ? raw : rollups);
        if (!useRaw && bucketStart >= 0 && !bucket.isEmpty()) source.add(foldPreview());
        JsonArray times = new JsonArray();
        Map<MetricSeries, JsonArray> columns = new LinkedHashMap<>();
        for (MetricSeries one : series) columns.put(one, new JsonArray());
        int index = 0;
        while (index < source.size() && source.get(index).t() < first) index++;
        for (int b = 0; b < count; b++) {
            long start = first + b * step, end = start + step;
            List<MetricsFile.Point> inside = new ArrayList<>();
            while (index < source.size() && source.get(index).t() < end) { if (source.get(index).t() <= to) inside.add(source.get(index)); index++; }
            times.add(start);
            for (int s = 0; s < series.size(); s++) {
                MetricSeries one = series.get(s);
                if (buffers[s] == null || buffers[s].length < inside.size()) buffers[s] = new float[Math.max(8, inside.size())];
                for (int i = 0; i < inside.size(); i++) buffers[s][i] = inside.get(i).values()[one.ordinal()];
                float value = MetricSeries.fold(one.fold, buffers[s], inside.size());
                columns.get(one).add(Float.isNaN(value) ? null : round(value));
            }
        }
        JsonObject out = Json.obj("from", from * 1000, "to", to * 1000, "step", step, "t", times);
        JsonObject values = new JsonObject();
        columns.forEach((one, column) -> values.add(one.id, column));
        out.add("series", values);
        return out;
    }

    /** Waits for the writer; tests and shutdown only. */
    public void flush() {
        try { writer.submit(() -> { }).get(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException stuck) { log.accept("метрики: запись не успела: " + stuck); }
    }

    public void close() { flush(); writer.shutdown(); }

    private MetricsFile.Point foldBucket() {
        MetricsFile.Point folded = foldPreview();
        bucket.clear();
        return folded;
    }

    private MetricsFile.Point foldPreview() {
        float[] values = new float[N], column = new float[bucket.size()];
        for (MetricSeries series : MetricSeries.values()) {
            for (int i = 0; i < bucket.size(); i++) column[i] = bucket.get(i).values()[series.ordinal()];
            values[series.ordinal()] = MetricSeries.fold(series.fold, column, bucket.size());
        }
        return new MetricsFile.Point(bucketStart, values);
    }

    private void writeRollups(List<MetricsFile.Point> points) {
        for (MetricsFile.Point point : points) queue(dir.resolve(day(point.t()) + ".bin"), point);
    }

    /** Points wait here and the writer takes them all in one go, a file at a time: a burst costs one open per file. */
    private void queue(Path file, MetricsFile.Point point) {
        pending.add(Map.entry(file, point));
        if (draining.compareAndSet(false, true)) writer.execute(this::drain);
    }

    private void drain() {
        draining.set(false);
        Map<Path, List<MetricsFile.Point>> byFile = new LinkedHashMap<>();
        for (Map.Entry<Path, MetricsFile.Point> next; (next = pending.poll()) != null; )
            byFile.computeIfAbsent(next.getKey(), k -> new ArrayList<>()).add(next.getValue());
        byFile.forEach(this::write);
    }

    private void write(Path file, List<MetricsFile.Point> points) {
        try {
            if (repaired.add(file)) {
                long cut = MetricsFile.repair(file);
                if (cut > 0) log.accept("метрики: " + file.getFileName() + " был повреждён, отрезано " + cut + " байт");
            }
            MetricsFile.append(file, points);
        } catch (IOException failure) { log.accept("метрики: не записались в " + file.getFileName() + ": " + failure.getMessage()); }
    }

    private void prune(long nowSeconds) {
        deleteOlder(dir.resolve("raw"), LocalDate.ofInstant(Instant.ofEpochSecond(nowSeconds - RAW_KEEP), ZoneOffset.UTC).minusDays(1));
        deleteOlder(dir, LocalDate.ofInstant(Instant.ofEpochSecond(nowSeconds - ROLLUP_KEEP), ZoneOffset.UTC));
    }

    private void deleteOlder(Path folder, LocalDate oldest) {
        try {
            for (Path file : files(folder)) {
                String name = file.getFileName().toString();
                try { if (LocalDate.parse(name.substring(0, 8), HistoryStore.DAY).isBefore(oldest)) Files.deleteIfExists(file); }
                catch (RuntimeException notADay) { /* Not ours. */ }
            }
        } catch (IOException failure) { log.accept("метрики: не удалось почистить: " + failure.getMessage()); }
    }

    private static List<Path> files(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(folder)) return files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.bin")) { for (Path file : stream) files.add(file); }
        files.sort(null);
        return files;
    }

    private static String day(long seconds) { return HistoryStore.DAY.format(Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC)); }

    private static JsonObject nowJson(MetricsFile.Point point, List<JsonObject> worlds) {
        JsonObject values = new JsonObject();
        for (MetricSeries series : MetricSeries.values()) {
            float value = point.values()[series.ordinal()];
            if (!Float.isNaN(value)) values.addProperty(series.id, round(value));
        }
        return Json.obj("t", point.t() * 1000, "values", values, "worlds", worlds == null ? List.of() : worlds);
    }

    static double round(float value) { return Math.round(value * 1000.0) / 1000.0; }
}
