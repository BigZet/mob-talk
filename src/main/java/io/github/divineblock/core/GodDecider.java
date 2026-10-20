package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.List;

/** Picks the act within a verdict's budget. The reasons are already summed; the decider only chooses which act fits. */
public interface GodDecider {
    record Choice(Miracle miracle, int power, boolean warn, String decidedBy, String note) { }

    String name();
    Choice choose(Verdict verdict, DivineConfig config);

    /** The acts a verdict allows: its own kind within the budget and the settings, on the rung of the ladder its reasons
     * earned (see {@link Verdict#level()}), and not the acts the god used on this mortal just now. Omens when nothing fits. */
    static List<Miracle> candidates(Verdict verdict, DivineConfig config) {
        List<Miracle> allowed = new ArrayList<>();
        for (Miracle miracle : Miracle.values())
            if (miracle.kind == verdict.inclination && miracle.kind != Miracle.Kind.OMEN && miracle.minTier <= verdict.budget && config.allows(miracle))
                allowed.add(miracle);
        List<Miracle> out = new ArrayList<>();
        if (!allowed.isEmpty()) {
            int level = verdict.level();
            // The rung and its neighbours; widened if the settings left too little there.
            for (int widen = 0; widen <= 10 && out.size() < 3; widen++) {
                out.clear();
                for (Miracle miracle : allowed) if (miracle.gravity() >= level - 2 - widen && miracle.gravity() <= level + 2 + widen) out.add(miracle);
            }
            // Whatever the mortal asked for by name stays possible when mercy or a jest answers him.
            if (verdict.inclination != Miracle.Kind.WRATH)
                for (Miracle miracle : allowed) if (!out.contains(miracle) && miracle.hintMatches(verdict.prayer) > 0) out.add(miracle);
            // No two alike in a row: what was just done to this mortal is set aside while anything else remains.
            List<Miracle> fresh = new ArrayList<>(out);
            if (verdict.recentActs != null) fresh.removeIf(miracle -> verdict.recentActs.contains(miracle.id()));
            if (!fresh.isEmpty()) out = fresh;
        }
        if (out.isEmpty()) for (Miracle miracle : Miracle.values()) if (miracle.kind == Miracle.Kind.OMEN && miracle != Miracle.IGNORE && config.allows(miracle)) out.add(miracle);
        // The god may always choose silence: a prayer he hears but does not deign to answer.
        if (config.allows(Miracle.IGNORE) && !out.contains(Miracle.IGNORE) && !verdict.fury) out.add(Miracle.IGNORE);
        return out;
    }

    /** The power an act is performed at: as much of the budget as the act can take, never below its own minimum. */
    static int power(Miracle miracle, int wanted, int budget) {
        return Math.max(miracle.minTier, Math.min(Math.min(wanted, budget), miracle.maxTier));
    }

    /** A serious punishment always comes with an omen first: time to repent or run. */
    static boolean mustWarn(Miracle miracle, int power) { return miracle.kind == Miracle.Kind.WRATH && power >= 2; }
}
