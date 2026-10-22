package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** The steps of one dialogue with their times from the moment the line was accepted, and its sizes; DialogueService marks each
 * step as it passes (wave 1). Thread-safe: steps come from the game thread and from model callbacks. */
public final class DialogueTrace {
    /** The steps in their order (docs/admin-web.md, «Трасса диалога»). */
    public static final List<String> STEPS = List.of("accepted", "budget", "context", "prompt", "llm_first", "llm_done", "parsed", "actions", "tts", "delivered");

    private record Mark(String step, long ms) { }

    private final LongSupplier clock;
    private final long start;
    private final List<Mark> marks = new ArrayList<>();
    private final Map<String, Long> sizes = new LinkedHashMap<>();
    private String error, errorStep;

    public DialogueTrace(LongSupplier clock) {
        this.clock = clock; this.start = clock.getAsLong();
        marks.add(new Mark("accepted", 0));
    }

    public synchronized DialogueTrace mark(String step) {
        if (!STEPS.contains(step)) throw new IllegalArgumentException("шаг трассы: " + step);
        marks.removeIf(mark -> mark.step().equals(step));
        marks.add(new Mark(step, clock.getAsLong() - start));
        return this;
    }

    /** {@code promptChars}, {@code tokensIn}, {@code tokensOut}, {@code audioBytes}. */
    public synchronized DialogueTrace size(String name, long value) { sizes.put(name, value); return this; }

    public synchronized DialogueTrace fail(String step, String message) { errorStep = step; error = message; return this; }

    public synchronized String lastStep() { return marks.get(marks.size() - 1).step(); }
    public synchronized long elapsed() { return clock.getAsLong() - start; }
    public long startedAt() { return start; }
    public synchronized long at(String step) {
        for (Mark mark : marks) if (mark.step().equals(step)) return mark.ms();
        return -1;
    }

    public synchronized JsonObject toJson() {
        List<Mark> ordered = new ArrayList<>(marks);
        ordered.sort((a, b) -> Integer.compare(STEPS.indexOf(a.step()), STEPS.indexOf(b.step())));
        JsonArray steps = new JsonArray();
        for (Mark mark : ordered) steps.add(Json.obj("step", mark.step(), "ms", mark.ms()));
        JsonObject sizeJson = new JsonObject();
        sizes.forEach(sizeJson::addProperty);
        return Json.obj("steps", steps, "sizes", sizeJson, "error", error == null ? null : Json.obj("step", errorStep, "message", error));
    }
}
