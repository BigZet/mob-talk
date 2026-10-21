package io.github.divineblock.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the god remembers about each player, saved with the world: righteousness, his mood toward them, recent prayers and
 * the chronicle of verdicts. Plain data; the game wraps it in a PersistentState as one JSON string. */
public final class GodLedger {
    public static final int CHRONICLE = 12;
    /** Prayers within this many game ticks count as "asking again" (ten real minutes). */
    public static final long REPEAT_WINDOW_TICKS = 12_000;

    public static final class Soul {
        public Righteousness righteousness = new Righteousness();
        /** The god's passing mood toward this player, −100..+100, drifting to zero. */
        public double mood;
        public long moodTick = -1;
        public List<Long> prayerTicks = new ArrayList<>();
        public long lastPrayerTick = Long.MIN_VALUE / 2;
        public List<Verdict> chronicle = new ArrayList<>();
        /** Last seen values of the player's statistics (breeding, trading, raids, planting), for the deed watcher. */
        public Map<String, Integer> statBaselines = new HashMap<>();
        /** Punishments in a row not yet forgiven, and the game day of the last one: a week of peace lets the streak go. */
        public int wrathStreak;
        public long lastWrathDay = -100;

        public int wrathStreak(long day) { if (day - lastWrathDay > 7) wrathStreak = 0; return wrathStreak; }
        public void punished(long day) { wrathStreak(day); wrathStreak++; lastWrathDay = day; }
        public void forgiven() { wrathStreak = 0; }

        public int recentPrayers(long now) {
            prayerTicks.removeIf(tick -> now - tick > REPEAT_WINDOW_TICKS || tick > now);
            return prayerTicks.size();
        }
        public void prayed(long now) { recentPrayers(now); prayerTicks.add(now); lastPrayerTick = now; }

        public double mood(long now) {
            if (moodTick >= 0 && now > moodTick) mood *= Math.pow(0.5, (now - moodTick) / 24_000.0); // Half a mood per game day.
            moodTick = now;
            return mood;
        }
        public void shiftMood(long now, double delta) { mood = Math.max(-100, Math.min(100, mood(now) + delta)); }

        public void remember(Verdict verdict) {
            chronicle.add(verdict);
            while (chronicle.size() > CHRONICLE) chronicle.remove(0);
        }
        public Verdict lastVerdict() { return chronicle.isEmpty() ? null : chronicle.get(chronicle.size() - 1); }
    }

    public Map<String, Soul> souls = new HashMap<>();

    public Soul soul(java.util.UUID player) { return souls.computeIfAbsent(player.toString(), id -> new Soul()); }

    private static final Gson GSON = new GsonBuilder().create();
    public String toJson() { return GSON.toJson(this); }
    public static GodLedger fromJson(String json) {
        try {
            GodLedger ledger = json == null || json.isBlank() ? null : GSON.fromJson(json, GodLedger.class);
            if (ledger == null) return new GodLedger();
            if (ledger.souls == null) ledger.souls = new HashMap<>();
            return ledger;
        } catch (RuntimeException broken) { return new GodLedger(); }
    }
}
