package io.github.mobtalk.server.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** What counts as a moment for the montage list (идея З4): the part of {@code config/mobtalk-admin.json} under {@code moments}.
 * Weights go 0–3 (0 turns a kind off), pre-rolls are seconds before the event where watching should start. */
public final class MomentRules {
    public static final Set<String> SESSION_AUTO = Set.of("streamer_join", "any_join", "manual");

    public boolean enabled = true;
    /** The least weight a found moment needs to land in the list; manual marks always do. */
    public int threshold = 2;
    /** Events of one player closer than this merge into one moment. */
    public int mergeSeconds = 45;
    /** Without OBS, when a recording session opens by itself: when the streamer joins, when anyone joins, or only by hand. */
    public String sessionAuto = "streamer_join";
    /** Seconds a manual mark reaches back: people press after the funny part. */
    public int manualBackSeconds = 30;
    public Map<String, Integer> weights = defaultWeights();
    public Map<String, Integer> preroll = defaultPreroll();

    static Map<String, Integer> defaultWeights() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("wrath", 3); map.put("miracle", 3); map.put("omen", 1); map.put("jest", 1);
        map.put("boss_spawn", 3); map.put("boss_end", 3); map.put("ban", 3);
        map.put("wedding", 3); map.put("wedding_broken", 3);
        map.put("death", 2); map.put("attack", 2); map.put("flee", 2); map.put("insult", 1); map.put("threat", 2);
        map.put("kiss", 2); map.put("slap", 2); map.put("date", 2); map.put("propose", 3); map.put("divorce", 3); map.put("child", 3);
        map.put("trade_refused", 1);
        return map;
    }

    static Map<String, Integer> defaultPreroll() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("wrath", 20); map.put("miracle", 20); map.put("omen", 10); map.put("jest", 10);
        map.put("boss_spawn", 10); map.put("boss_end", 15); map.put("ban", 30);
        map.put("wedding", 30); map.put("wedding_broken", 30);
        map.put("death", 15); map.put("attack", 25); map.put("flee", 25); map.put("insult", 20); map.put("threat", 20);
        map.put("kiss", 20); map.put("slap", 20); map.put("date", 25); map.put("propose", 30); map.put("divorce", 30); map.put("child", 30);
        map.put("trade_refused", 20);
        return map;
    }

    public int weight(String key) { Integer value = weights == null ? null : weights.get(key); return value == null ? defaultWeights().getOrDefault(key, 0) : Math.max(0, Math.min(3, value)); }
    public int preroll(String key) { Integer value = preroll == null ? null : preroll.get(key); return value == null ? defaultPreroll().getOrDefault(key, 15) : Math.max(0, Math.min(600, value)); }

    MomentRules normalized() {
        threshold = Math.max(1, Math.min(3, threshold));
        mergeSeconds = Math.max(5, Math.min(600, mergeSeconds));
        manualBackSeconds = Math.max(0, Math.min(600, manualBackSeconds));
        if (sessionAuto == null || !SESSION_AUTO.contains(sessionAuto)) sessionAuto = "streamer_join";
        if (weights == null) weights = defaultWeights();
        if (preroll == null) preroll = defaultPreroll();
        // A file from an older build keeps its numbers and gets the new kinds.
        defaultWeights().forEach(weights::putIfAbsent);
        defaultPreroll().forEach(preroll::putIfAbsent);
        return this;
    }
}
