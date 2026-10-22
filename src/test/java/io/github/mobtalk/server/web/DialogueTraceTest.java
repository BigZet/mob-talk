package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** A dialogue's trace: every step with its time, the sizes, latencies, and the prompt only with debug.keepPrompts. */
class DialogueTraceTest {
    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_792_000_000_000L);
    private final AtomicBoolean keepPrompts = new AtomicBoolean();

    private AdminJournal journal(HistoryStore history, Latency latency) { return new AdminJournal(history, latency, clock::get, keepPrompts::get); }

    private JsonObject run(AdminJournal journal, HistoryStore history) {
        AdminJournal.Run run = journal.dialogueStarted(Json.player(UUID.randomUUID(), "BigZet"), Json.obj("uuid", UUID.randomUUID().toString(), "name", "Тереза", "species", "villager"), "привет");
        assertEquals(1, journal.active());
        long[] at = {2, 15, 21, 900, 1700, 1704, 1710, 2400, 2460};
        List<String> steps = DialogueTrace.STEPS.subList(1, DialogueTrace.STEPS.size());
        long start = clock.get();
        for (int i = 0; i < steps.size(); i++) { clock.set(start + at[i]); run.step(steps.get(i)); }
        run.size("promptChars", 6120).size("tokensIn", 1830).size("tokensOut", 64).size("audioBytes", 48211);
        JsonObject record = run.finish("здравствуй", List.of("FOLLOW"), 0.002, 1830, 64, "openai/gpt-6-luna", "local", "ПОЛНЫЙ ПРОМПТ", "СЫРОЙ ОТВЕТ");
        assertEquals(0, journal.active());
        return history.get(record.get("id").getAsString());
    }

    @Test void everyStepWithTimesAndSizes() {
        HistoryStore history = new HistoryStore(dir, clock::get, ZoneId.systemDefault(), 90, s -> { });
        Latency latency = new Latency(clock::get);
        JsonObject record = run(journal(history, latency), history);
        JsonObject trace = record.getAsJsonObject("data").getAsJsonObject("trace");
        JsonArray steps = trace.getAsJsonArray("steps");
        assertEquals(DialogueTrace.STEPS, steps.asList().stream().map(s -> s.getAsJsonObject().get("step").getAsString()).toList());
        assertEquals(List.of(0L, 2L, 15L, 21L, 900L, 1700L, 1704L, 1710L, 2400L, 2460L), steps.asList().stream().map(s -> s.getAsJsonObject().get("ms").getAsLong()).toList());
        JsonObject sizes = trace.getAsJsonObject("sizes");
        assertEquals(6120, sizes.get("promptChars").getAsInt());
        assertEquals(48211, sizes.get("audioBytes").getAsInt());
        assertTrue(trace.get("error").isJsonNull());
        assertEquals(2460, record.getAsJsonObject("data").get("ms").getAsLong());
        assertFalse(record.getAsJsonObject("data").has("prompt"), "без debug.keepPrompts промпта нет");
        assertFalse(record.getAsJsonObject("data").has("raw"));
        assertEquals(879, latency.percentile("llm_first", 0, Long.MAX_VALUE, 0.5));
        assertEquals(1679, latency.percentile("llm", 0, Long.MAX_VALUE, 0.5));
        assertEquals(2460, latency.percentile("total", 0, Long.MAX_VALUE, 0.5));
        assertEquals(1, history.search(new HistoryStore.Query(java.util.Set.of("cost"), null, null, null, null, null, null, 10)).items().size(), "стоимость записана");
        history.close();
    }

    @Test void promptKeptOnlyWithTheFlag() {
        keepPrompts.set(true);
        HistoryStore history = new HistoryStore(dir, clock::get, ZoneId.systemDefault(), 90, s -> { });
        JsonObject record = run(journal(history, new Latency(clock::get)), history);
        assertEquals("ПОЛНЫЙ ПРОМПТ", record.getAsJsonObject("data").get("prompt").getAsString());
        assertEquals("СЫРОЙ ОТВЕТ", record.getAsJsonObject("data").get("raw").getAsString());
        history.close();
    }

    @Test void failureNamesItsStepAndSkippedStepsAreAbsent() {
        HistoryStore history = new HistoryStore(dir, clock::get, ZoneId.systemDefault(), 90, s -> { });
        AdminJournal journal = journal(history, new Latency(clock::get));
        AdminJournal.Run run = journal.dialogueStarted(Json.player(UUID.randomUUID(), "Тест2"), null, "эй");
        run.step("budget").step("context").step("prompt");
        clock.addAndGet(12_000);
        JsonObject record = history.get(run.fail("llm_first", "модель молчит 12 с").get("id").getAsString());
        JsonObject trace = record.getAsJsonObject("data").getAsJsonObject("trace");
        assertEquals("llm_first", trace.getAsJsonObject("error").get("step").getAsString());
        assertEquals("модель молчит 12 с", record.getAsJsonObject("data").get("error").getAsString());
        assertEquals(4, trace.getAsJsonArray("steps").size(), "нет шагов, которых не было");
        assertNull(run.finish("поздно", List.of(), 0, 0, 0, null, null, null, null), "закончить дважды нельзя");
        history.close();
    }

    @Test void liveSnapshotFollowsTheSteps() {
        HistoryStore history = new HistoryStore(dir, clock::get, ZoneId.systemDefault(), 90, s -> { });
        AdminJournal journal = journal(history, new Latency(clock::get));
        List<JsonObject> snapshots = new java.util.ArrayList<>();
        journal.listenLive(snapshots::add);
        AdminJournal.Run run = journal.dialogueStarted(Json.player(UUID.randomUUID(), "BigZet"), null, "как дела?");
        run.step("budget");
        JsonObject last = snapshots.get(snapshots.size() - 1).getAsJsonArray("dialogues").get(0).getAsJsonObject();
        assertEquals("budget", last.get("step").getAsString());
        assertEquals("как дела?", last.get("question").getAsString());
        run.fail("budget", "лимит");
        assertTrue(snapshots.get(snapshots.size() - 1).getAsJsonArray("dialogues").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new DialogueTrace(clock::get).mark("nope"));
        history.close();
    }
}
