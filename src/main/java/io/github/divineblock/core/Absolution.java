package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** What a priest's absolution does to a soul (Mob Talk's confession, op {@code absolve} of {@link AdminProtocol}). Only what the
 * penitent named is forgiven: a light sin at once (all of it in a church, three quarters by a village priest, by the settings), a
 * heavy one in part, its rest after the penance and the return to a priest ({@link #absolveRest}). A sin kept back stays, marked
 * for the god's verdicts; a mocking confession forgives nothing and counts as a small insult to the god; a sincere one forgives a
 * quarter more. Under a punishment still running at most half goes before the penance. No dice: the same words and the same
 * soul give the same numbers. Plain Java over {@link Righteousness}, for the tests. */
public final class Absolution {
    private Absolution() { }

    /** Under a running punishment, no more than this many percent of a sin before the penance. */
    public static final int UNDER_PUNISHMENT = 50;

    /** One sin on the soul as the priest's classifier sees it: its id, kind, words, the score it still weighs, heavy or light,
     * and whether it is a relapse. */
    public record Open(long id, String kind, String text, double weight, boolean heavy, boolean relapse) { }

    /** A sin the absolution touched: how many percent went now, how much of the score came back, what waits for the penance. */
    public record Item(long id, String kind, String text, boolean heavy, int percent, double restored, double remaining) { }

    /** What one absolution did: forgiven now, waiting for the penance, kept back; refused for a mocking confession. */
    public record Result(List<Item> absolved, List<Item> deferred, List<Long> concealed, boolean refused, double before, double after) { }

    /** Whether this sin is heavy: its kind weighs at least the threshold, or it is a relapse. */
    public static boolean heavy(Righteousness.Memory memory, DivineConfig config) {
        return memory.relapse || Math.abs(memory.deed.weight) >= config.confessHeavyWeight;
    }

    /** The sins still to confess, oldest first: open and not already confessed with a rest waiting for the penance (a deed is
     * confessed once). */
    public static List<Open> open(Righteousness righteousness, long today, DivineConfig config) {
        List<Open> out = new ArrayList<>();
        for (Righteousness.Memory m : righteousness.memories)
            if (m.open() && m.awaiting <= 0) out.add(new Open(m.id, m.deed.name().toLowerCase(java.util.Locale.ROOT), m.describe(today), round(-m.effective()), heavy(m, config), m.relapse));
        return out;
    }

    /** The kinds of sin absolved within {@link Righteousness#RELAPSE_DAYS}: the priest notices a fresh fall into one of them. */
    public static boolean recentlyAbsolved(Righteousness righteousness, Righteousness.Deed deed, long today) {
        Long day = righteousness.absolvedDay.get(deed);
        return day != null && today >= day && today - day <= Righteousness.RELAPSE_DAYS;
    }

    /** The absolution of a confession: {@code named} the ids the penitent named, {@code sincerity} 0 (mocking), 1 or 2 (sincere),
     * {@code temple} in a church by its rector or a village priest, {@code punished} a punishment still runs on him. */
    public static Result absolve(Righteousness righteousness, long today, Set<Long> named, int sincerity, boolean temple, boolean punished, DivineConfig config) {
        righteousness.drift(today);
        double before = righteousness.score;
        List<Item> absolved = new ArrayList<>(), deferred = new ArrayList<>();
        List<Long> concealed = new ArrayList<>();
        if (sincerity <= 0) {
            // Mockery at the analoy: nothing is forgiven, and the god counts a small insult.
            righteousness.record(Righteousness.Deed.RUDE_PRAYER, today, "на исповеди", config.confessRelapseMultiplier);
            return new Result(absolved, deferred, concealed, true, round(before), round(righteousness.score));
        }
        for (Righteousness.Memory m : new ArrayList<>(righteousness.memories)) {
            if (!m.open() || m.awaiting > 0) continue;
            if (!named.contains(m.id)) {
                if (config.confessRemembersHidden) { m.concealed = true; concealed.add(m.id); }
                continue;
            }
            boolean heavy = heavy(m, config);
            int percent = heavy ? (temple ? config.confessHeavyTemple : config.confessHeavyVillage) : (temple ? config.confessLightTemple : config.confessLightVillage);
            if (sincerity >= 2) percent += config.confessSincerityBonus;
            percent = Math.max(0, Math.min(100, percent));
            if (punished) percent = Math.min(percent, UNDER_PUNISHMENT);
            double weight = Math.max(0, -m.effective()), restored = weight * percent / 100.0;
            // A heavy sin, or any sin under a punishment, waits for the penance with its rest; a light sin's rest stays lost.
            double remaining = heavy || punished ? weight - restored : 0;
            m.absolved += restored;
            m.awaiting = remaining;
            m.concealed = false;
            righteousness.score = Righteousness.clamp(righteousness.score + restored);
            Item item = new Item(m.id, m.deed.name().toLowerCase(java.util.Locale.ROOT), m.describe(today), heavy, percent, round(restored), round(remaining));
            if (remaining > 0.0001) deferred.add(item);
            else { settle(righteousness, m, today); absolved.add(item); }
        }
        return new Result(absolved, deferred, concealed, false, round(before), round(righteousness.score));
    }

    /** The penance done and the penitent back: the rest of these sins is forgiven and they are settled. Ids without a rest waiting
     * are passed over. */
    public static Result absolveRest(Righteousness righteousness, long today, Collection<Long> ids) {
        righteousness.drift(today);
        double before = righteousness.score;
        List<Item> absolved = new ArrayList<>();
        for (long id : ids) {
            Righteousness.Memory m = righteousness.memory(id);
            if (m == null || !m.open() || m.awaiting <= 0) continue;
            double restored = Math.min(m.awaiting, Math.max(0, -m.effective()));
            m.absolved += restored;
            m.awaiting = 0;
            righteousness.score = Righteousness.clamp(righteousness.score + restored);
            settle(righteousness, m, today);
            absolved.add(new Item(m.id, m.deed.name().toLowerCase(java.util.Locale.ROOT), m.describe(today), true, 100, round(restored), 0));
        }
        return new Result(absolved, List.of(), List.of(), false, round(before), round(righteousness.score));
    }

    /** Whether every sin committed by {@code day} (inclusive) is settled: then a personal curse laid that day may end. */
    public static boolean clearUpTo(Righteousness righteousness, long day) {
        for (Righteousness.Memory m : righteousness.memories) if (m.open() && m.day <= day) return false;
        return true;
    }

    private static void settle(Righteousness righteousness, Righteousness.Memory m, long today) {
        m.settled = true; m.concealed = false; m.awaiting = 0;
        righteousness.absolvedDay.put(m.deed, today);
    }

    private static double round(double value) { return Math.round(value * 10) / 10.0; }
}
