package io.github.divineblock.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** The prayers the god heard and what he answered, for the web admin's «Бог» tab (op {@code prayers}): the words and their
 * tone, the gifts, the god's reply, his choice and the named reasons of the verdict. Kept in memory, the last {@link #LIMIT}
 * (the world's own memory stays in {@link GodLedger}); texts pass {@link GodJournal#redact}. Thread-safe: the game thread
 * writes, the admin reads from its own threads. */
public final class PrayerLog {
    public static final int LIMIT = 300;

    public record Reason(String title, int mercy, int power) { }
    /** {@code kind}: miracle (mercy), wrath, jest, warning (an omen) or none (silence). {@code decidedBy}: rules or jev. */
    public record Choice(String kind, String deed, String deedTitle, int power, String decidedBy, String note) { }
    /** How the god weighed it: the reasons summed ({@code favor} toward mercy, {@code force} of the answer), the side and its
     * rung (1..10), the power budget, punishments in a row, and the draw of the group: Jev's probabilities, the ladder's, the
     * final weights ({@code odds}) with each group's label. */
    public record Decision(int favor, int force, String inclination, int level, int budget, int escalation, boolean fury, boolean forgiveness,
                           String group, java.util.Map<String, Double> jev, java.util.Map<String, Double> ladder, java.util.Map<String, Double> odds,
                           java.util.Map<String, String> labels) {
        static Decision of(Verdict v) {
            java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
            for (java.util.Map<String, Double> odds : List.of(v.oddsJev, v.oddsLadder, v.odds))
                for (String id : odds.keySet()) { MiracleGroup g = MiracleGroup.byId(id); labels.put(id, g == null ? "молчание" : g.label); }
            return new Decision(v.favor, v.force, v.inclination == null ? "" : v.inclination.label, v.level(), v.budget, v.escalation, v.fury, v.forgiveness,
                    v.group == null ? "" : v.group, v.oddsJev, v.oddsLadder, v.odds, labels);
        }
    }
    public record Entry(long t, String uuid, String name, String text, String tone, String toneTitle, String offering, int offeringValue,
                        String answer, Choice choice, List<Reason> reasons, Decision decision) { }

    private static final PrayerLog SHARED = new PrayerLog();
    private final Deque<Entry> entries = new ArrayDeque<>();

    /** The log of this game (one server per JVM). */
    public static PrayerLog shared() { return SHARED; }

    /** A heard prayer and the verdict on it. {@code insult}: Jev took the words for an insult the rules did not see. */
    public static Entry of(long millis, String uuid, String name, Verdict verdict, PrayerTone tone, boolean insult, String offering, int offeringValue) {
        String toneId = tone == null ? "plain" : tone.id();
        if (insult && !toneId.equals("profane") && !toneId.equals("rude")) toneId = "rude";
        Miracle chosen = verdict.chosen();
        Choice choice = new Choice(kind(chosen), chosen.id(), chosen.title(), verdict.power, verdict.decidedBy == null ? "rules" : verdict.decidedBy,
                GodJournal.redact(verdict.decisionNote));
        List<Reason> reasons = new ArrayList<>();
        for (Verdict.Reason reason : verdict.reasons) reasons.add(new Reason(GodJournal.redact(reason.text()), reason.favor(), reason.force()));
        return new Entry(millis, uuid == null ? "" : uuid, name == null ? "" : name, GodJournal.redact(verdict.prayer), toneId, PrayerTone.title(toneId),
                offering == null ? "" : offering, Math.max(0, offeringValue), GodJournal.redact(verdict.speech), choice, List.copyOf(reasons), Decision.of(verdict));
    }

    /** The admin's word for what the god did. */
    public static String kind(Miracle miracle) {
        if (miracle == null || miracle == Miracle.IGNORE) return "none";
        return switch (miracle.kind) { case HELP -> "miracle"; case WRATH -> "wrath"; case JEST -> "jest"; case OMEN -> "warning"; };
    }

    public synchronized void add(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > LIMIT) entries.removeFirst();
    }

    /** Entries after {@code since} (millis), newest first, at most {@code limit}; {@code player} (a name or a UUID, any case)
     * keeps only his, empty keeps all. */
    public synchronized List<Entry> query(long since, int limit, String player) {
        String who = player == null ? "" : player.strip().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Iterator<Entry> it = entries.descendingIterator(); it.hasNext() && out.size() < Math.max(0, limit); ) {
            Entry entry = it.next();
            if (entry.t() <= since) continue;
            if (!who.isEmpty() && !entry.uuid().toLowerCase(Locale.ROOT).equals(who) && !entry.name().toLowerCase(Locale.ROOT).equals(who)) continue;
            out.add(entry);
        }
        return out;
    }

    public synchronized void clear() { entries.clear(); }
}
