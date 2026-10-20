package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The player's accumulated life, −100..+100, apart from the god's passing mood. The god sees deeds himself; every kind of
 * deed has a weight and a daily cap so nobody farms sainthood, and the score drifts toward zero day by day. Plain data,
 * serialized with Gson inside the world's saved state. */
public final class Righteousness {
    public enum Deed {
        VILLAGER_KILLED(-15, 45, "убил жителя"),
        GOLEM_KILLED(-8, 24, "убил железного голема"),
        PET_KILLED(-10, 30, "убил прирученное животное"),
        SLAUGHTER(-1, 10, "бил мирных животных без меры"),
        VANDALISM(-3, 15, "громил деревню"),
        DEFENDED_VILLAGE(1, 10, "защищал жителей от чудовищ"),
        RAID_WON(20, 20, "отбил рейд на деревню"),
        ANIMAL_BRED(0.5, 5, "разводил животных"),
        TRADED(0.5, 5, "честно торговал с жителями"),
        PLANTED(0.25, 3, "сажал деревья и посевы"),
        RUDE_PRAYER(-2, 6, "хамил богу в молитве");

        public final double weight, dailyCap;
        public final String phrase;
        Deed(double weight, double dailyCap, String phrase) { this.weight = weight; this.dailyCap = Math.abs(dailyCap); this.phrase = phrase; }
    }

    /** One remembered deed: what, on which game day, how many times and how much it moved the score. */
    public static final class Memory {
        public Deed deed; public long day; public int count; public double total; public String where = "";
        public Memory() { }
        Memory(Deed deed, long day, String where) { this.deed = deed; this.day = day; this.where = where == null ? "" : where; }
        public String describe(long today) {
            long ago = Math.max(0, today - day);
            String when = ago == 0 ? "сегодня" : ago == 1 ? "вчера" : ago + " дн. назад";
            return deed.phrase + (count > 1 ? " (×" + count + ")" : "") + (where.isBlank() ? "" : " " + where) + ", " + when;
        }
    }

    public static final double LIMIT = 100, DAILY_DRIFT = 0.95;
    static final int REMEMBERED = 20;

    public double score;
    public long lastDriftDay = -1;
    public List<Memory> memories = new ArrayList<>();
    /** Today's sums per deed, for the daily caps. */
    public Map<Deed, Double> today = new EnumMap<>(Deed.class);
    public long todayDay = -1;

    /** Records a deed and returns how much it actually moved the score (zero once today's cap is reached). */
    public double record(Deed deed, long day, String where) {
        drift(day);
        if (todayDay != day) { today.clear(); todayDay = day; }
        double used = today.getOrDefault(deed, 0.0);
        double room = deed.dailyCap - Math.abs(used);
        double delta = room <= 0 ? 0 : Math.copySign(Math.min(Math.abs(deed.weight), room), deed.weight);
        today.put(deed, used + delta);
        double before = score;
        score = clamp(score + delta);
        Memory same = null;
        for (int i = memories.size() - 1; i >= 0 && i >= memories.size() - 5; i--)
            if (memories.get(i).deed == deed && memories.get(i).day == day) { same = memories.get(i); break; }
        if (same == null) { same = new Memory(deed, day, where); memories.add(same); }
        same.count++; same.total += score - before;
        while (memories.size() > REMEMBERED) memories.remove(0);
        return score - before;
    }

    /** Fades the score toward zero for each game day since the last call; fresh deeds weigh more than old ones. */
    public void drift(long day) {
        if (lastDriftDay < 0) { lastDriftDay = day; return; }
        long days = Math.min(365, day - lastDriftDay);
        if (days <= 0) return;
        score = clamp(score * Math.pow(DAILY_DRIFT, days));
        if (Math.abs(score) < 0.05) score = 0;
        lastDriftDay = day;
    }

    /** The status the player sees: words, not the number. */
    public String label() {
        if (score >= 60) return "святой";
        if (score >= 20) return "праведник";
        if (score > -20) return "обычный смертный";
        if (score > -60) return "грешник";
        return "проклятый";
    }

    /** The most notable deeds of the last days, strongest first, for the verdict and the god's speech. */
    public List<Memory> notable(long today, int days, int limit) {
        List<Memory> recent = new ArrayList<>();
        for (Memory memory : memories) if (today - memory.day <= days && Math.abs(memory.total) >= 1) recent.add(memory);
        recent.sort((a, b) -> Double.compare(Math.abs(b.total), Math.abs(a.total)));
        return recent.subList(0, Math.min(limit, recent.size()));
    }

    static double clamp(double value) { return Math.max(-LIMIT, Math.min(LIMIT, value)); }
}
