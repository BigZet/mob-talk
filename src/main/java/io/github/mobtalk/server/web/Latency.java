package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** How long each step took (speech recognition, the model's first token, the whole answer, the voice): samples for 48 hours,
 * percentiles and a histogram over any window inside them. */
public final class Latency {
    public static final List<String> KINDS = List.of("stt", "llm_first", "llm", "tts", "total");
    static final long[] BOUNDS = {250, 500, 1000, 2000, 4000, 8000, 16000, 32000};
    private static final long KEEP = 48L * 3600 * 1000;
    private static final int MAX_SAMPLES = 200_000;

    private record Sample(long t, long ms) { }

    private final Map<String, Deque<Sample>> samples = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Latency(LongSupplier clock) { this.clock = clock; for (String kind : KINDS) samples.put(kind, new ArrayDeque<>()); }

    public void record(String kind, long ms) { record(kind, ms, clock.getAsLong()); }

    public void record(String kind, long ms, long t) {
        Deque<Sample> deque = samples.get(kind);
        if (deque == null || ms < 0) return;
        synchronized (deque) {
            deque.addLast(new Sample(t, ms));
            while (deque.size() > MAX_SAMPLES || (!deque.isEmpty() && deque.peekFirst().t() < t - KEEP)) deque.pollFirst();
        }
    }

    /** Milliseconds at the percentile over [from, to], or -1 with no samples. */
    public long percentile(String kind, long from, long to, double percentile) {
        long[] values = window(kind, from, to);
        return values.length == 0 ? -1 : at(values, percentile);
    }

    public JsonObject histogram(String kind, long from, long to) {
        if (!samples.containsKey(kind)) throw ApiException.badRequest("Неизвестный вид задержки: " + kind + ".");
        long[] values = window(kind, from, to);
        JsonArray buckets = new JsonArray();
        int index = 0;
        for (int b = 0; b <= BOUNDS.length; b++) {
            int n = 0;
            while (index < values.length && (b == BOUNDS.length || values[index] <= BOUNDS[b])) { n++; index++; }
            buckets.add(Json.obj("le", b == BOUNDS.length ? null : BOUNDS[b], "n", n));
        }
        boolean none = values.length == 0;
        return Json.obj("kind", kind, "count", values.length, "p50", none ? null : at(values, 0.50), "p95", none ? null : at(values, 0.95),
                "p99", none ? null : at(values, 0.99), "max", none ? null : values[values.length - 1], "buckets", buckets);
    }

    private long[] window(String kind, long from, long to) {
        Deque<Sample> deque = samples.get(kind);
        if (deque == null) return new long[0];
        long[] values;
        synchronized (deque) { values = deque.stream().filter(s -> s.t() >= from && s.t() <= to).mapToLong(Sample::ms).toArray(); }
        Arrays.sort(values);
        return values;
    }

    private static long at(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
