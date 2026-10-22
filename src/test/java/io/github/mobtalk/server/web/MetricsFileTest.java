package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Ten-second points and five-minute roll-ups: written, read back, thinned, kept across a restart, and a damaged file is harmless. */
class MetricsFileTest {
    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_792_000_000_000L);
    private final List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static float[] values(float tps, float mspt) {
        float[] values = new float[MetricSeries.values().length];
        Arrays.fill(values, Float.NaN);
        values[MetricSeries.TPS.ordinal()] = tps;
        values[MetricSeries.MSPT.ordinal()] = mspt;
        return values;
    }

    @Test void fileRoundTripAndForeignSeries() throws IOException {
        Path file = dir.resolve("a.bin");
        MetricsFile.append(file, List.of(new MetricsFile.Point(10, values(20, 5)), new MetricsFile.Point(20, values(19, 7))));
        MetricsFile.append(file, List.of(new MetricsFile.Point(30, values(18, 9))));
        List<MetricsFile.Point> points = MetricsFile.read(file);
        assertEquals(List.of(10L, 20L, 30L), points.stream().map(MetricsFile.Point::t).toList());
        assertEquals(9f, points.get(2).values()[MetricSeries.MSPT.ordinal()]);
        assertTrue(Float.isNaN(points.get(0).values()[MetricSeries.ONLINE.ordinal()]));
        long header = 3 + MetricsFile.names().stream().mapToLong(name -> 2 + name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        assertEquals(4 + header + 3L * (1 + 8 + 4L * MetricSeries.values().length), Files.size(file), "второй append не повторяет заголовок");
    }

    @Test void tornTailIsCutAndBadFilesAreSkipped() throws IOException {
        Path file = dir.resolve("b.bin");
        MetricsFile.append(file, List.of(new MetricsFile.Point(10, values(20, 5)), new MetricsFile.Point(20, values(19, 6))));
        Files.write(file, new byte[]{'P', 0, 0, 0}, StandardOpenOption.APPEND);
        assertEquals(2, MetricsFile.read(file).size(), "оборванная точка не читается");
        assertEquals(4, MetricsFile.repair(file));
        MetricsFile.append(file, List.of(new MetricsFile.Point(30, values(18, 7))));
        assertEquals(3, MetricsFile.read(file).size());
        Path garbage = dir.resolve("c.bin");
        Files.write(garbage, new byte[]{1, 2, 3, 4, 5, 6, 7});
        assertTrue(MetricsFile.read(garbage).isEmpty());
        MetricsFile.repair(garbage);
        assertTrue(Files.exists(dir.resolve("c.bin.broken")));
    }

    private Metrics fresh() { return new Metrics(dir.resolve("metrics"), clock::get, log::add); }

    @Test void recordQueryThinRollUpAndSurviveRestart() {
        Metrics metrics = fresh();
        metrics.load();
        long start = clock.get() / 1000 - 3600;
        start -= Math.floorMod(start, 300);
        for (int i = 0; i < 360; i++) metrics.record(start + i * 10L, Map.of(MetricSeries.TPS, 20.0 - (i % 2), MetricSeries.MSPT_MAX, (double) i,
                MetricSeries.DIALOGUES, 1.0), List.of());
        JsonObject all = metrics.query((start) * 1000, (start + 3599) * 1000, 400, List.of(MetricSeries.TPS));
        assertEquals(10, all.get("step").getAsInt());
        assertEquals(360, all.getAsJsonArray("t").size());
        JsonObject thin = metrics.query(start * 1000, (start + 3599) * 1000, 12, List.of(MetricSeries.TPS, MetricSeries.MSPT_MAX, MetricSeries.DIALOGUES));
        assertEquals(300, thin.get("step").getAsInt());
        JsonArray tps = thin.getAsJsonObject("series").getAsJsonArray("tps");
        assertEquals(12, tps.size());
        assertEquals(19.5, tps.get(0).getAsDouble(), 1e-6, "среднее");
        assertEquals(29, thin.getAsJsonObject("series").getAsJsonArray("mspt_max").get(0).getAsInt(), "максимум");
        assertEquals(30, thin.getAsJsonObject("series").getAsJsonArray("dialogues").get(0).getAsInt(), "сумма");
        assertTrue(thin.getAsJsonObject("series").getAsJsonArray("tps").get(0).isJsonPrimitive());
        metrics.close();

        clock.addAndGet(3 * 86_400_000L);
        Metrics again = fresh();
        again.load();
        JsonObject old = again.query(start * 1000, (start + 3599) * 1000, 12, List.of(MetricSeries.TPS, MetricSeries.DIALOGUES));
        assertEquals(300, old.get("step").getAsInt(), "через три дня — из свёрток");
        assertEquals(19.5, old.getAsJsonObject("series").getAsJsonArray("tps").get(0).getAsDouble(), 1e-6);
        assertEquals(30, old.getAsJsonObject("series").getAsJsonArray("dialogues").get(0).getAsInt());
        assertEquals(12, old.getAsJsonObject("series").getAsJsonArray("tps").asList().stream().filter(e -> !e.isJsonNull()).count(),
                "последние открытые пять минут свёрнуты при загрузке");
        again.close();
    }

    @Test void gapsAreNullAndNowIsTheLastPoint() {
        Metrics metrics = fresh();
        long now = clock.get() / 1000;
        metrics.record(now - 100, Map.of(MetricSeries.TPS, 20.0), List.of());
        metrics.record(now - 10, Map.of(MetricSeries.TPS, 15.0, MetricSeries.ONLINE, 2.0), List.of(Json.obj("id", "minecraft:overworld", "chunks", 5)));
        JsonObject result = metrics.query((now - 120) * 1000, now * 1000, 300, List.of(MetricSeries.TPS));
        assertTrue(result.getAsJsonObject("series").getAsJsonArray("tps").asList().stream().anyMatch(e -> e.isJsonNull()));
        JsonObject last = metrics.now();
        assertEquals(15.0, last.getAsJsonObject("values").get("tps").getAsDouble());
        assertEquals(1, last.getAsJsonArray("worlds").size());
        metrics.close();
    }

    @Test void damagedFileOnDiskDoesNotStopLoading() throws IOException {
        Path raw = dir.resolve("metrics").resolve("raw");
        Files.createDirectories(raw);
        Files.write(raw.resolve("20260101.bin"), new byte[]{'M', 'T', 'M', '1', 'H', 0, (byte) 200, 1});
        Files.write(dir.resolve("metrics").resolve("20260102.bin"), "это не метрики".getBytes());
        Metrics metrics = fresh();
        assertDoesNotThrow(metrics::load);
        metrics.record(clock.get() / 1000, Map.of(MetricSeries.TPS, 20.0), List.of());
        metrics.close();
    }
}
