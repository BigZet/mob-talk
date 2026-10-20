package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.List;

/** Picks the act within a verdict's budget. The reasons are already summed; the decider only chooses which act fits. */
public interface GodDecider {
    record Choice(Miracle miracle, int power, boolean warn, String decidedBy, String note) { }

    String name();
    Choice choose(Verdict verdict, DivineConfig config);

    /** The acts a verdict allows: its own kind within the budget and the settings, or omens when nothing else fits. */
    static List<Miracle> candidates(Verdict verdict, DivineConfig config) {
        List<Miracle> out = new ArrayList<>();
        for (Miracle miracle : Miracle.values())
            if (miracle.kind == verdict.inclination && miracle.kind != Miracle.Kind.OMEN && miracle.minTier <= verdict.budget && config.allows(miracle)) out.add(miracle);
        if (out.isEmpty()) for (Miracle miracle : Miracle.values()) if (miracle.kind == Miracle.Kind.OMEN && miracle != Miracle.IGNORE && config.allows(miracle)) out.add(miracle);
        // The god may always choose silence: a prayer he hears but does not deign to answer.
        if (config.allows(Miracle.IGNORE) && !out.contains(Miracle.IGNORE)) out.add(Miracle.IGNORE);
        return out;
    }

    /** The power an act is performed at: as much of the budget as the act can take, never below its own minimum. */
    static int power(Miracle miracle, int wanted, int budget) {
        return Math.max(miracle.minTier, Math.min(Math.min(wanted, budget), miracle.maxTier));
    }

    /** A serious punishment always comes with an omen first: time to repent or run. */
    static boolean mustWarn(Miracle miracle, int power) { return miracle.kind == Miracle.Kind.WRATH && power >= 2; }
}
