package io.github.mobtalk.server.web;

import java.util.Locale;

/** Every metric the admin page can chart (docs/admin-web.md, «Метрики»), with how ten-second points fold into longer ones. */
public enum MetricSeries {
    MSPT(Fold.AVG), MSPT_P95(Fold.AVG), MSPT_MAX(Fold.MAX), TPS(Fold.AVG),
    MEM_USED(Fold.AVG), MEM_MAX(Fold.MAX), ONLINE(Fold.MAX), CHUNKS(Fold.AVG), ENTITIES(Fold.AVG), MOBS_PROFILED(Fold.AVG),
    DIALOGUES_ACTIVE(Fold.MAX), LLM_QUEUE(Fold.MAX), LLM_INFLIGHT(Fold.MAX), DIALOGUES(Fold.SUM), COST_USD(Fold.SUM), ERRORS(Fold.SUM),
    HOGWEED(Fold.AVG), BOSSES(Fold.MAX), CURSES(Fold.MAX),
    LAT_STT_P50(Fold.MAX), LAT_STT_P95(Fold.MAX), LAT_LLM_FIRST_P50(Fold.MAX), LAT_LLM_FIRST_P95(Fold.MAX),
    LAT_LLM_P50(Fold.MAX), LAT_LLM_P95(Fold.MAX), LAT_TTS_P50(Fold.MAX), LAT_TTS_P95(Fold.MAX),
    LAT_TOTAL_P50(Fold.MAX), LAT_TOTAL_P95(Fold.MAX);

    public enum Fold { AVG, MAX, SUM }

    public final Fold fold;
    public final String id;

    MetricSeries(Fold fold) { this.fold = fold; this.id = name().toLowerCase(Locale.ROOT); }

    public static MetricSeries byId(String id) {
        for (MetricSeries series : values()) if (series.id.equals(id)) return series;
        return null;
    }

    /** {@code lat_<kind>_p50} or {@code _p95} for a latency kind ({@code stt}, {@code llm_first}, {@code llm}, {@code tts}, {@code total}). */
    public static MetricSeries latency(String kind, boolean p95) { return byId("lat_" + kind + (p95 ? "_p95" : "_p50")); }

    /** Folds the values of one series over several points; NaN means «no value» and is skipped. */
    public static float fold(Fold fold, float[] values, int count) {
        double sum = 0, max = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (int i = 0; i < count; i++) {
            float value = values[i];
            if (Float.isNaN(value)) continue;
            sum += value; max = Math.max(max, value); n++;
        }
        if (n == 0) return Float.NaN;
        return (float) switch (fold) { case AVG -> sum / n; case MAX -> max; case SUM -> sum; };
    }
}
