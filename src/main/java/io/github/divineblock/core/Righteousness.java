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
        RUDE_PRAYER(-2, 6, "хамил богу в молитве"),
        BLASPHEMY(-8, 32, "хулил бога бранью"),
        CHILD_KILLED(-20, 60, "убил ребёнка-жителя"),
        PRIEST_KILLED(-25, 50, "убил священника"),
        DESECRATION(-12, 36, "осквернил святое место");

        public final double weight, dailyCap;
        public final String phrase;
        Deed(double weight, double dailyCap, String phrase) { this.weight = weight; this.dailyCap = Math.abs(dailyCap); this.phrase = phrase; }
    }

    /** One remembered deed: what, on which game day, how many times and how much it moved the score. A sin also keeps what
     * confession did to it ({@link Absolution}): its id for the priest, how much of it is forgiven, the part waiting for the
     * penance, whether it is settled, kept back at confession, or a relapse into a sin just forgiven. */
    public static final class Memory {
        public Deed deed; public long day; public int count; public double total; public String where = "";
        /** Stable within the soul (0 in an old save until {@link Righteousness#number} gives one). */
        public long id;
        /** How much of what this sin took from the score absolution gave back (0..−total). */
        public double absolved;
        /** The part of a heavy sin forgiven only after the penance and the return to a priest. */
        public double awaiting;
        /** Named at confession and forgiven as far as it goes: no longer on the soul (a light sin's lost rest of mercy stays lost). */
        public boolean settled;
        /** Kept back at the last confession: the god remembers it. */
        public boolean concealed;
        /** The same kind of sin within days of its absolution: heavier, and always heavy. */
        public boolean relapse;
        public Memory() { }
        Memory(Deed deed, long day, String where) { this.deed = deed; this.day = day; this.where = where == null ? "" : where; }
        public String describe(long today) {
            long ago = Math.max(0, today - day);
            String when = ago == 0 ? "сегодня" : ago == 1 ? "вчера" : ago + " дн. назад";
            return deed.phrase + (count > 1 ? " (×" + count + ")" : "") + (where.isBlank() ? "" : " " + where) + ", " + when;
        }
        public boolean sin() { return deed != null && deed.weight < 0; }
        /** What the deed still moves the score by, after absolution. */
        public double effective() { return total + absolved; }
        /** A sin still on the soul: not settled at confession. */
        public boolean open() { return sin() && !settled; }
    }

    public static final double LIMIT = 100, DAILY_DRIFT = 0.95;
    static final int REMEMBERED = 20;
    /** A sin of the same kind within this many game days of its absolution is a relapse. */
    public static final long RELAPSE_DAYS = 2;

    public double score;
    public long lastDriftDay = -1;
    public List<Memory> memories = new ArrayList<>();
    /** Today's sums per deed, for the daily caps. */
    public Map<Deed, Double> today = new EnumMap<>(Deed.class);
    public long todayDay = -1;
    /** The next id of a remembered deed. */
    public long nextId = 1;
    /** The game day each kind of sin was last absolved, for relapses. */
    public Map<Deed, Long> absolvedDay = new EnumMap<>(Deed.class);

    /** The admin moves the score by hand (by {@code delta}), within ±{@link #LIMIT}; returns the new score. */
    public double adjust(double delta) { score = clamp(score + delta); return score; }

    /** Records a deed and returns how much it actually moved the score (zero once today's cap is reached). */
    public double record(Deed deed, long day, String where) { return record(deed, day, where, 1); }

    /** {@code relapseMultiplier}: a sin of a kind absolved within {@link #RELAPSE_DAYS} weighs this many times more (its daily cap
     * too) and is remembered apart as a relapse. A sin never joins a memory confession already touched. */
    public double record(Deed deed, long day, String where, double relapseMultiplier) {
        drift(day);
        if (todayDay != day) { today.clear(); todayDay = day; }
        Long forgiven = absolvedDay == null ? null : absolvedDay.get(deed);
        boolean relapse = deed.weight < 0 && forgiven != null && day >= forgiven && day - forgiven <= RELAPSE_DAYS;
        double times = relapse ? Math.max(1, relapseMultiplier) : 1;
        double used = today.getOrDefault(deed, 0.0);
        double room = deed.dailyCap * times - Math.abs(used);
        double delta = room <= 0 ? 0 : Math.copySign(Math.min(Math.abs(deed.weight) * times, room), deed.weight);
        today.put(deed, used + delta);
        double before = score;
        score = clamp(score + delta);
        Memory same = null;
        for (int i = memories.size() - 1; i >= 0 && i >= memories.size() - 5; i--) {
            Memory m = memories.get(i);
            if (m.deed == deed && m.day == day && m.relapse == relapse && !m.settled && !m.concealed && m.absolved == 0 && m.awaiting == 0) { same = m; break; }
        }
        if (same == null) { same = new Memory(deed, day, where); same.relapse = relapse; same.id = nextId++; memories.add(same); }
        same.count++; same.total += score - before;
        while (memories.size() > REMEMBERED) memories.remove(0);
        return score - before;
    }

    /** Old saves: every remembered deed gets its id, missing lists come back. */
    public void number() {
        if (memories == null) memories = new ArrayList<>();
        if (today == null) today = new EnumMap<>(Deed.class);
        if (absolvedDay == null) absolvedDay = new EnumMap<>(Deed.class);
        memories.removeIf(m -> m == null || m.deed == null);
        for (Memory m : memories) { if (m.where == null) m.where = ""; if (m.id > 0) nextId = Math.max(nextId, m.id + 1); }
        for (Memory m : memories) if (m.id <= 0) m.id = nextId++;
    }

    /** The remembered deed with this id, or null. */
    public Memory memory(long id) {
        for (Memory m : memories) if (m.id == id) return m;
        return null;
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

    /** The most notable deeds of the last days, strongest first, for the verdict and the god's speech. A sin settled at confession
     * no longer counts, and a forgiven part no longer weighs. */
    public List<Memory> notable(long today, int days, int limit) {
        List<Memory> recent = new ArrayList<>();
        for (Memory memory : memories) if (today - memory.day <= days && !memory.settled && Math.abs(memory.effective()) >= 1) recent.add(memory);
        recent.sort((a, b) -> Double.compare(Math.abs(b.effective()), Math.abs(a.effective())));
        return recent.subList(0, Math.min(limit, recent.size()));
    }

    /** What the god holds against the soul beyond its deeds: sins kept back at confession and relapses still open (up to
     * {@code limit}, newest first), for the verdict's named reasons. */
    public List<Memory> conscience(int limit) {
        List<Memory> out = new ArrayList<>();
        for (int i = memories.size() - 1; i >= 0 && out.size() < limit; i--) {
            Memory m = memories.get(i);
            if (m.open() && (m.concealed || m.relapse)) out.add(m);
        }
        return out;
    }

    static double clamp(double value) { return Math.max(-LIMIT, Math.min(LIMIT, value)); }
}
