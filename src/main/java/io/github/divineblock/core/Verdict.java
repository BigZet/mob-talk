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
    /** "jev" or "rules", and what Jev said, for the debug view. */
    public String decidedBy = "rules";
    public String decisionNote = "";
    public String speech = "";
    /** Set when the player repented in time and the punishment was called off. */
    /** Kept for verdicts saved before the god stopped giving second chances; always false now. */
    public boolean forgiven;
    public long day;

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
                .append(", бюджет ").append(budget).append("; выбрано ").append(miracle).append(" ×").append(power)
                .append(" (").append(decidedBy).append(decisionNote.isBlank() ? "" : ": " + decisionNote).append(")");
        return out.toString();
    }
}
