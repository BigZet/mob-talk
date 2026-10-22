package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Players' paths: the 5 s / 4 blocks rule, one world at a time, thinning, seven days, a torn file. */
class TracksTest {
    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_792_000_000_000L);
    private final UUID dima = UUID.fromString("461baa90-bc98-4f86-b889-50f67d759b9a"), test2 = UUID.fromString("2f83646d-2eeb-4a75-8358-03e02e2ef99f");

    private Tracks tracks() { return new Tracks(dir, clock::get, 7, message -> fail(message)); }

    private static JsonArray points(JsonObject answer, int track) { return answer.getAsJsonArray("tracks").get(track).getAsJsonObject().getAsJsonArray("points"); }

    @Test void keepsEveryFiveSecondsOrAfterFourBlocks() {
        Tracks tracks = tracks();
        long t = clock.get();
        tracks.record(dima, "BigZet", t, "minecraft:overworld", 0, 64, 0);
        tracks.record(dima, "BigZet", t + 2000, "minecraft:overworld", 1, 64, 0);      // 2 s, 1 block: dropped
        tracks.record(dima, "BigZet", t + 3000, "minecraft:overworld", 6, 64, 0);      // 5 blocks: kept at once
        tracks.record(dima, "BigZet", t + 9000, "minecraft:overworld", 7, 64, 0);      // 6 s later, moved: kept
        tracks.record(dima, "BigZet", t + 15000, "minecraft:overworld", 7.1, 64, 0);   // standing still: dropped
        tracks.record(dima, "BigZet", t + 80000, "minecraft:overworld", 7.1, 64, 0);   // still, but a minute passed: kept
        tracks.record(dima, "BigZet", t + 81000, "minecraft:the_nether", 1, 70, 1);    // another world: kept
        JsonObject over = tracks.query("minecraft:overworld", t - 1, t + 100_000, null, 100);
        assertEquals(List.of(0.0, 6.0, 7.0, 7.1), points(over, 0).asList().stream().map(p -> p.getAsJsonArray().get(1).getAsDouble()).toList());
        assertEquals("BigZet", over.getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonObject("player").get("name").getAsString());
        assertEquals(1, points(tracks.query("minecraft:the_nether", t - 1, t + 100_000, null, 100), 0).size());
        assertEquals(0, tracks.query("minecraft:the_end", t - 1, t + 100_000, null, 100).getAsJsonArray("tracks").size());
        tracks.close();
    }

    @Test void filtersByPlayerAndTimeAndThins() {
        Tracks tracks = tracks();
        long t = clock.get();
        for (int i = 0; i < 1000; i++) {
            tracks.record(dima, "BigZet", t + i * 5000L, "minecraft:overworld", i * 5, 64, 0);
            tracks.record(test2, "Тест2", t + i * 5000L, "minecraft:overworld", 0, 64, i * 5);
        }
        assertEquals(2, tracks.query("minecraft:overworld", t, t + 5_000_000, null, 2000).getAsJsonArray("tracks").size());
        JsonObject one = tracks.query("minecraft:overworld", t, t + 5_000_000, test2, 50);
        assertEquals(1, one.getAsJsonArray("tracks").size());
        JsonArray thin = points(one, 0);
        assertTrue(thin.size() <= 51 && thin.size() >= 49, "прорежено до ~50: " + thin.size());
        assertEquals(t, thin.get(0).getAsJsonArray().get(0).getAsLong(), "первая точка на месте");
        assertEquals(t + 999 * 5000L, thin.get(thin.size() - 1).getAsJsonArray().get(0).getAsLong(), "последняя тоже");
        assertEquals(10, points(tracks.query("minecraft:overworld", t + 100_000, t + 145_000, dima, 100), 0).size(), "период");
        tracks.close();
    }

    @Test void positionsAnswerFeedsThePaths() {
        Tracks tracks = tracks();
        tracks.observe(Json.obj("t", clock.get(), "players", List.of(
                Json.obj("player", Json.player(dima, "BigZet"), "online", true, "world", "minecraft:overworld", "x", 1, "y", 64, "z", 2),
                Json.obj("player", Json.player(test2, "Тест2"), "online", false, "world", "minecraft:overworld", "x", 5, "y", 64, "z", 5))));
        JsonObject answer = tracks.query("minecraft:overworld", 0, Long.MAX_VALUE, null, 10);
        assertEquals(1, answer.getAsJsonArray("tracks").size(), "офлайн не пишется");
        tracks.close();
    }

    @Test void survivesRestartPrunesAndCutsATornTail() throws Exception {
        Tracks first = tracks();
        long t = clock.get();
        first.record(dima, "BigZet", t - 10 * 86_400_000L, "minecraft:overworld", 1, 64, 1);
        first.record(dima, "BigZet", t, "minecraft:overworld", 100, 64, 1);
        first.close();
        Path today;
        try (var files = Files.list(dir)) { today = files.filter(f -> f.toString().endsWith(".bin")).sorted().reduce((a, b) -> b).orElseThrow(); }
        Files.write(today, new byte[]{1, 2, 3}, StandardOpenOption.APPEND);
        Tracks second = tracks();
        second.record(dima, "BigZet", t + 60_000, "minecraft:overworld", 200, 64, 1);
        JsonArray points = points(second.query("minecraft:overworld", t - 1, t + 100_000, null, 10), 0);
        assertEquals(List.of(100.0, 200.0), points.asList().stream().map(p -> p.getAsJsonArray().get(1).getAsDouble()).toList(), "хвост обрезан, записи не сдвинуты");
        assertEquals("BigZet", second.query("minecraft:overworld", t - 1, t + 100_000, null, 10).getAsJsonArray("tracks").get(0)
                .getAsJsonObject().getAsJsonObject("player").get("name").getAsString(), "ник пережил перезапуск");
        assertEquals(1, second.prune(), "старше семи дней удалено");
        second.close();
    }
}
