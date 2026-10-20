package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One decision of the god: the named reasons with their weights, what they add up to, what was chosen and by whom. Kept in
 * the world's chronicle so the god can retell it when asked "why?". Plain data for Gson. */
public final class Verdict {
    /** One named reason: how it moves the god toward mercy (favor, negative = wrath) and how strongly he intervenes (force). */
    public record Reason(String text, int favor, int force) {
        public String signed() {
            StringBuilder out = new StringBuilder(text);
            if (favor != 0) out.append(favor > 0 ? " +" : " ").append(favor);
            if (force != 0) out.append(" (сила ").append(force > 0 ? "+" : "").append(force).append(")");
            return out.toString();
        }
    }

    public String prayer = "";
    public List<Reason> reasons = new ArrayList<>();
    public int favor, force;
    public Miracle.Kind inclination = Miracle.Kind.OMEN;
    /** The strongest tier the reasons allow, already capped by the settings. */
    public int budget;
    public String miracle = Miracle.IGNORE.id();
    public int power;
    public boolean warned;
    /** Blasphemy: the god is in a fury, punishes at full strength and never stays silent. */
    public boolean fury;
    /** The mortal repented of earlier blasphemy and was forgiven: a sign of peace, nothing else. */
    public boolean forgiveness;
    /** How many times this mortal blasphemed in the last days before this prayer, and whether his soul is damned. */
    public int blasphemiesBefore;
    public boolean damned;
    /** The group the act was drawn from (К1…, Б1…, Ш1…), for the log and the self-test. */
    public String group = "";
    /** "jev" or "rules", and what Jev said, for the debug view. */
    public String decidedBy = "rules";
    public String decisionNote = "";
    public String speech = "";
    /** Kept for verdicts saved before the god stopped giving second chances; always false now. */
    public boolean forgiven;
    public long day;

    /** The acts the god used on this mortal lately (newest last), so he does not repeat himself; not saved. */
    public transient List<String> recentActs = List.of();

    /** The rung of the ladder (1..10) the reasons earned for the chosen side: wrath climbs with every point of insult, sin,
     * anger and pestering; mercy climbs with favour; jests with force. */
    public int level() {
        return switch (inclination) {
            case WRATH -> Math.max(fury ? 4 : 1, Math.min(10, (int) Math.round((-favor - 2) / 2.0))); // Fury starts above mere curses.
            case HELP -> Math.max(1, Math.min(5, (int) Math.round(favor / 2.0)));
            case JEST -> Math.max(1, Math.min(3, force));
            case OMEN -> 0;
        };
    }

    /** How cruel the fury is, 1..5: how many dragons or withers, how much land is cut away. Grows with the weight of the
     * reasons against the sinner (repeated blasphemy, sins, anger); 1 outside fury. */
    public int severity() { return fury ? Math.max(1, Math.min(5, 1 + (-favor - 10) / 3)) : 1; }

    public Miracle chosen() { Miracle m = Miracle.byId(miracle); return m == null ? Miracle.IGNORE : m; }

    /** The reasons that weigh most, for the god's speech: the strongest pull toward the chosen side first. */
    public List<Reason> mainReasons(int limit) {
        List<Reason> sorted = new ArrayList<>(reasons);
        int side = inclination == Miracle.Kind.WRATH ? -1 : inclination == Miracle.Kind.HELP ? 1 : 0;
        sorted.sort(Comparator.comparingInt((Reason r) -> side == 0 ? -(Math.abs(r.favor()) + Math.abs(r.force())) : -(r.favor() * side * 2 + Math.abs(r.force()))));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    /** The full breakdown in numbers for the debug view and the log. */
    public String breakdown() {
        StringBuilder out = new StringBuilder();
        for (Reason reason : reasons) out.append("• ").append(reason.signed()).append('\n');
        out.append("Итог: милость ").append(favor).append(", сила ").append(force).append(" → ").append(inclination.label)
                .append(", бюджет ").append(budget).append(", ступень ").append(level()).append("; выбрано ").append(miracle).append(" ×").append(power)
                .append(" (").append(decidedBy).append(decisionNote.isBlank() ? "" : ": " + decisionNote).append(")");
        return out.toString();
    }
}
