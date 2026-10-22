package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Where the game reports to the admin page: a dialogue as it runs (live list, trace, latencies, history, cost), money spent,
 * and any other event. Everything is memory plus {@link HistoryStore#append}, which only queues: safe from the game thread. */
public final class AdminJournal {
    /** One dialogue in progress; DialogueService keeps it from the accepted line to delivery. */
    public final class Run {
        public final String id;
        final JsonObject player, mob;
        final String question;
        public final DialogueTrace trace;
        private volatile boolean done;

        private Run(String id, JsonObject player, JsonObject mob, String question) {
            this.id = id; this.player = player; this.mob = mob; this.question = question;
            this.trace = new DialogueTrace(clock);
        }

        public Run step(String step) { if (!done) { trace.mark(step); changed(); } return this; }
        public Run size(String name, long value) { trace.size(name, value); return this; }

        /** The answer reached the players: history, latencies, live list. {@code prompt} and {@code raw} are kept only with
         * {@code debug.keepPrompts}. */
        public JsonObject finish(String answer, List<String> actions, double usd, long tokensIn, long tokensOut, String model, String stt,
                                 String prompt, String raw) {
            return end(answer, actions, usd, tokensIn, tokensOut, model, stt, prompt, raw);
        }

        public JsonObject fail(String step, String message) {
            trace.fail(step, message);
            return end(null, List.of(), 0, 0, 0, null, null, null, null);
        }

        private JsonObject end(String answer, List<String> actions, double usd, long tokensIn, long tokensOut, String model, String stt,
                               String prompt, String raw) {
            if (done) return null;
            done = true;
            live.remove(id);
            changed();
            long llmStart = Math.max(trace.at("prompt"), 0);
            if (trace.at("llm_first") >= 0) latency.record("llm_first", trace.at("llm_first") - llmStart);
            if (trace.at("llm_done") >= 0) latency.record("llm", trace.at("llm_done") - llmStart);
            if (trace.at("tts") >= 0) latency.record("tts", trace.at("tts") - Math.max(Math.max(trace.at("actions"), trace.at("parsed")), trace.at("llm_done")));
            if (trace.at("delivered") >= 0) latency.record("total", trace.at("delivered"));
            JsonObject data = Json.obj("question", question, "answer", answer, "actions", actions, "usd", usd, "tokensIn", tokensIn,
                    "tokensOut", tokensOut, "model", model, "stt", stt, "ms", trace.elapsed(), "trace", trace.toJson());
            com.google.gson.JsonElement traced = trace.toJson().get("error");
            JsonObject error = traced != null && traced.isJsonObject() ? traced.getAsJsonObject() : null;
            data.add("error", error == null ? null : error.get("message"));
            if (keepPrompts.getAsBoolean()) { data.addProperty("prompt", prompt); data.addProperty("raw", raw); }
            String text = HistoryStore.nameOf(player) + " → " + HistoryStore.nameOf(mob) + ": «" + clip(question) + "» — "
                    + (answer == null ? "ошибка: " + Json.string(error, "message") : "«" + clip(answer) + "»");
            if (usd > 0) cost(player, "llm", model, usd, (int) (tokensIn + tokensOut));
            return history.append("dialogue", player, mob, text, data);
        }
    }

    private final HistoryStore history;
    private final Latency latency;
    private final LongSupplier clock;
    private final BooleanSupplier keepPrompts;
    private final Map<String, Run> live = new ConcurrentHashMap<>();
    private final List<Consumer<JsonObject>> liveListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong dialoguesStarted = new AtomicLong();
    private final DoubleAdder spent = new DoubleAdder();
    private volatile JsonArray activities = new JsonArray();

    public AdminJournal(HistoryStore history, Latency latency, LongSupplier clock, BooleanSupplier keepPrompts) {
        this.history = history; this.latency = latency; this.clock = clock; this.keepPrompts = keepPrompts;
    }

    public Run dialogueStarted(JsonObject player, JsonObject mob, String question) {
        Run run = new Run("d" + Long.toString(sequence.incrementAndGet(), 36), player, mob, question);
        live.put(run.id, run);
        dialoguesStarted.incrementAndGet();
        changed();
        return run;
    }

    public Run running(String id) { return live.get(id); }

    /** Money spent on a provider; {@code kind}: stt, llm, tts, jev, witness, village_talk. */
    public void cost(JsonObject player, String kind, String model, double usd, int units) {
        if (usd <= 0 && units <= 0) return;
        usd = Math.round(Math.max(0, usd) * 1e8) / 1e8;
        spent.add(usd);
        history.append("cost", player, null, String.format(java.util.Locale.ROOT, "%s %s: $%.4f", kind, model == null ? "" : model, usd),
                Json.obj("kind", kind, "model", model, "usd", usd, "units", units));
    }

    /** Recognition time for the latency charts; the dialogue records its own steps. */
    public void transcribed(long ms) { latency.record("stt", ms); }

    public JsonObject event(String kind, JsonObject player, JsonObject mob, String text, JsonObject data) {
        return history.append(kind, player, mob, text, data);
    }

    /** The «Сейчас» list's second half, refreshed by the live game view when someone starts or stops talking, trading, praying. */
    public void activities(JsonArray now) { activities = now == null ? new JsonArray() : now; changed(); }

    public void listenLive(Consumer<JsonObject> listener) { liveListeners.add(listener); }

    public JsonObject liveSnapshot() {
        List<JsonObject> dialogues = new ArrayList<>();
        for (Run run : live.values())
            dialogues.add(Json.obj("id", run.id, "player", run.player, "mob", run.mob, "step", run.trace.lastStep(),
                    "stepSince", run.trace.startedAt() + Math.max(0, run.trace.at(run.trace.lastStep())), "startedAt", run.trace.startedAt(),
                    "question", Redact.text(run.question)));
        dialogues.sort((a, b) -> Long.compare(a.get("startedAt").getAsLong(), b.get("startedAt").getAsLong()));
        return Json.obj("dialogues", dialogues, "activities", activities);
    }

    public int active() { return live.size(); }
    /** Dialogues started since the last call, for the {@code dialogues} metric. */
    public long drainStarted() { return dialoguesStarted.getAndSet(0); }
    /** Money spent since the last call, for the {@code cost_usd} metric. */
    public double drainSpent() { return spent.sumThenReset(); }

    private void changed() {
        if (liveListeners.isEmpty()) return;
        JsonObject snapshot = liveSnapshot();
        for (Consumer<JsonObject> listener : liveListeners) {
            try { listener.accept(snapshot); } catch (RuntimeException ignored) { /* The dialogue goes on. */ }
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 119) + "…";
    }
}
