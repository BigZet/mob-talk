package io.github.mobtalk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Во что обошлись вызовы модели этого потока (учёт OpenRouter: {@code usage.cost} и токены в ответе): сервер открывает счёт перед
 * ответом моба и читает его после — расход идёт в журнал админки и в бюджет игрока. Без открытого счёта ничего не копится. */
public final class LlmSpend {
    private LlmSpend() { }
    public record Tally(double usd, long tokensIn, long tokensOut) {
        public static final Tally NONE = new Tally(0, 0, 0);
    }
    private static final ThreadLocal<double[]> OPEN = new ThreadLocal<>();

    public static void begin() { OPEN.set(new double[3]); }
    /** Счёт с {@link #begin} и закрытие его; без открытого — нули. */
    public static Tally take() {
        double[] tally = OPEN.get();
        OPEN.remove();
        return tally == null ? Tally.NONE : new Tally(tally[0], (long) tally[1], (long) tally[2]);
    }
    /** {@code usage} ответа OpenRouter (или null) — в открытый счёт этого потока. */
    static void add(JsonObject usage) {
        double[] tally = OPEN.get();
        if (tally == null || usage == null) return;
        tally[0] += number(usage.get("cost"));
        tally[1] += number(usage.get("prompt_tokens"));
        tally[2] += number(usage.get("completion_tokens"));
    }
    private static double number(JsonElement value) {
        try { return value == null || value.isJsonNull() ? 0 : value.getAsDouble(); } catch (RuntimeException notNumber) { return 0; }
    }
}
