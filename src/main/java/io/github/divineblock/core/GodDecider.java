package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Picks the act for a verdict whose reasons are already summed: which group of acts ({@link MiracleGroup}), then one act
 * drawn from it at random without repeats. */
public interface GodDecider {
    record Choice(Miracle miracle, int power, boolean warn, String decidedBy, String note, String group) { }

    String name();
    Choice choose(Verdict verdict, DivineConfig config);

    Random RANDOM = new Random();

    /** The act drawn from {@code group}, at the power the budget allows. */
    static Choice drawn(Verdict verdict, DivineConfig config, MiracleGroup group, String by, String note) {
        Miracle miracle = group.draw(verdict, config, RANDOM);
        int power = power(miracle, verdict.budget, verdict.budget);
        return new Choice(miracle, power, mustWarn(miracle, power), by, note + ", " + group.id() + " «" + group.label + "»", group.id());
    }

    /** Every act the verdict could end in (all members of its open groups), for the self-test and the log. */
    static List<Miracle> candidates(Verdict verdict, DivineConfig config) {
        List<Miracle> out = new ArrayList<>();
        for (MiracleGroup group : MiracleGroup.open(verdict, config)) for (Miracle miracle : group.allowed(verdict, config)) if (!out.contains(miracle)) out.add(miracle);
        if (config.allows(Miracle.IGNORE) && !verdict.fury) out.add(Miracle.IGNORE);
        return out;
    }

    /** The power an act is performed at: as much of the budget as the act can take, never below its own minimum. */
    static int power(Miracle miracle, int wanted, int budget) {
        return Math.max(miracle.minTier, Math.min(Math.min(wanted, budget), miracle.maxTier));
    }

    /** Serious wrath comes with thunder and the title in the same breath. */
    static boolean mustWarn(Miracle miracle, int power) { return miracle.kind == Miracle.Kind.WRATH && power >= 2; }
}
