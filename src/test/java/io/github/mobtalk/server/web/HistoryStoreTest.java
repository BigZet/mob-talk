package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The append-only history: every filter, cursor paging without gaps or repeats, retention, export. */
class HistoryStoreTest {
    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_792_000_000_000L);
    private HistoryStore history;
    private final JsonObject dima = Json.player(UUID.fromString("461baa90-bc98-4f86-b889-50f67d759b9a"), "BigZet");
    private final JsonObject test2 = Json.player(UUID.fromString("2f83646d-2eeb-4a75-8358-03e02e2ef99f"), "Тест2");
    private final JsonObject tereza = Json.obj("uuid", "fb57b8eb-d3e2-4f1e-8da3-2101caf9f459", "name", "Тереза", "species", "villager");

    @BeforeEach void open() { history = new HistoryStore(dir, clock::get, ZoneId.of("Europe/Moscow"), 90, msg -> fail(msg)); }
    @AfterEach void close() { history.close(); }

    private HistoryStore.Query query(Set<String> kinds, String player, String mob, String text, Long from, Long to, int limit) {
        return new HistoryStore.Query(kinds, player, mob, text, from, to, null, limit);
    }

    @Test void filtersByKindPlayerMobTextAndTime() {
        long now = clock.get();
        history.append(now - 3 * 86_400_000L, "dialogue", dima, tereza, "BigZet → Тереза: «где кузнец?»", Json.obj("question", "где кузнец?", "answer", "За колодцем"));
        history.append(now - 2 * 86_400_000L, "gift", test2, tereza, "Тест2 подарил хлеб", Json.obj("item", "minecraft:bread"));
        history.append(now - 3_600_000L, "verdict", test2, null, "Молитва Тест2", Json.obj("prayer", "дай алмазов", "act", "wrath"));
        history.append(now - 60_000L, "dialogue", test2, tereza, "Тест2 → Тереза", Json.obj("question", "Продашь ПИРОГ?", "answer", "Нет"));

        assertEquals(4, history.search(HistoryStore.Query.all()).items().size());
        assertEquals(2, history.search(query(Set.of("dialogue"), null, null, null, null, null, 50)).items().size());
        assertEquals(3, history.search(query(Set.of(), "Тест2", null, null, null, null, 50)).items().size(), "по нику");
        assertEquals(1, history.search(query(Set.of(), "461baa90-bc98-4f86-b889-50f67d759b9a", null, null, null, null, 50)).items().size(), "по UUID");
        assertEquals(3, history.search(query(Set.of(), null, "Тереза", null, null, null, 50)).items().size());
        assertEquals(1, history.search(query(Set.of(), null, null, "пирог", null, null, 50)).items().size(), "подстрока в data без учёта регистра");
        assertEquals(1, history.search(query(Set.of(), null, null, "алмаз", null, null, 50)).items().size());
        assertEquals(2, history.search(query(Set.of(), null, null, null, now - 86_400_000L, now, 50)).items().size(), "период");
        assertEquals(1, history.search(query(Set.of(), null, null, null, now - 4 * 86_400_000L, now - 2 * 86_400_000L - 1, 50)).items().size());
        List<JsonObject> items = history.search(HistoryStore.Query.all()).items();
        for (int i = 1; i < items.size(); i++) assertTrue(items.get(i - 1).get("t").getAsLong() >= items.get(i).get("t").getAsLong(), "новые первыми");
    }

    @Test void cursorPagesWithoutGapsOrRepeats() {
        long now = clock.get();
        for (int i = 0; i < 57; i++) history.append(now - i * 1000L * (i % 3 == 0 ? 1 : 3700), i % 2 == 0 ? "dialogue" : "gift", dima, null, "запись " + i, null);
        history.append(now - 5000, "dialogue", dima, null, "та же секунда А", null);
        history.append(now - 5000, "dialogue", dima, null, "та же секунда Б", null);
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            HistoryStore.Page page = history.search(HistoryStore.Query.all().withLimit(10).withCursor(cursor));
            for (JsonObject item : page.items()) assertTrue(seen.add(item.get("id").getAsString()), "повтор " + item);
            cursor = page.next();
            pages++;
        } while (cursor != null);
        assertEquals(59, seen.size());
        assertEquals(6, pages);
    }

    @Test void heavyFieldsOnlyInTheFullRecord() {
        JsonObject record = history.append("dialogue", dima, tereza, "строка", Json.obj("question", "q", "trace", Json.obj("steps", List.of()), "prompt", "P", "raw", "R"));
        JsonObject listed = history.search(HistoryStore.Query.all()).items().get(0);
        assertFalse(listed.getAsJsonObject("data").has("trace") || listed.getAsJsonObject("data").has("prompt") || listed.getAsJsonObject("data").has("raw"));
        JsonObject full = history.get(record.get("id").getAsString());
        assertNotNull(full);
        assertTrue(full.getAsJsonObject("data").has("trace") && full.getAsJsonObject("data").has("prompt"));
        assertNull(history.get("dialogue.20000101.zz"));
        assertNull(history.get("../../etc.20000101.x"));
    }

    @Test void retentionRemovesOldDays() throws Exception {
        long now = clock.get();
        history.append(now - 100 * 86_400_000L, "gift", dima, null, "давно", null);
        history.append(now - 10 * 86_400_000L, "gift", dima, null, "недавно", null);
        history.flush();
        assertEquals(1, history.prune());
        try (var files = Files.list(dir.resolve("gift"))) { assertEquals(1, files.count()); }
        history.retentionDays(5);
        assertEquals(1, history.prune());
    }

    @Test void exportsCsvAndJsonl() {
        history.append("dialogue", dima, tereza, "BigZet → Тереза: «привет, \"друг\"»", null);
        history.append("gift", test2, tereza, "=HYPERLINK(\"x\")", null);
        String csv = new String(history.export(HistoryStore.Query.all(), "csv", 100), StandardCharsets.UTF_8);
        List<String> lines = csv.lines().toList();
        assertEquals("﻿t,kind,player,mob,text", lines.get(0));
        assertEquals(3, lines.size());
        assertTrue(csv.contains("\"BigZet\",\"Тереза\",\"BigZet → Тереза: «привет, \"\"друг\"\"»\""), csv);
        assertTrue(csv.contains("\"'=HYPERLINK"), "формула не исполнится в таблице");
        String jsonl = new String(history.export(HistoryStore.Query.all(), "jsonl", 1), StandardCharsets.UTF_8);
        assertEquals(1, jsonl.lines().count(), "не больше max");
    }

    @Test void statsCountPerDayAndField() {
        long now = clock.get();
        history.append(now - 86_400_000L, "verdict", dima, null, "a", Json.obj("act", "wrath"));
        history.append(now - 86_400_000L, "verdict", dima, null, "b", Json.obj("act", "miracle"));
        history.append(now, "verdict", test2, null, "c", Json.obj("act", "wrath"));
        JsonObject stats = history.stats(new HistoryStore.Query(Set.of("verdict"), null, null, null, now - 2 * 86_400_000L, now, null, 0), "day", "data.act");
        int wrath = stats.getAsJsonObject("series").getAsJsonArray("wrath").asList().stream().mapToInt(e -> e.getAsInt()).sum();
        assertEquals(2, wrath);
        assertEquals(stats.getAsJsonArray("t").size(), stats.getAsJsonObject("series").getAsJsonArray("miracle").size());
    }

    @Test void brokenLinesAreSkipped() throws Exception {
        history.append("gift", dima, null, "целая", null);
        history.flush();
        Path file;
        try (var files = Files.list(dir.resolve("gift"))) { file = files.findFirst().orElseThrow(); }
        Files.writeString(file, "{не json\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, history.search(HistoryStore.Query.all()).items().size());
        List<String> ids = new ArrayList<>();
        history.scan(HistoryStore.Query.all(), r -> ids.add(r.get("id").getAsString()));
        assertEquals(1, ids.size());
    }
}
