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

    /** The ladder's wish over the open groups (lightest first): a bell around the group the verdict's level points at.
     * Wrath: a first insult (level 4) centres on the second group, the third blasphemy (level 7–8) on the fourth, the
     * worst (level 10) on the fifth. Mercy and jests: their level picks the group directly. */
    static java.util.Map<String, Double> ladder(Verdict verdict, List<MiracleGroup> open) {
        double target = verdict.inclination == Miracle.Kind.WRATH ? (verdict.level() - 2) / 2.0 : verdict.level() - 1;
        target = Math.max(0, Math.min(open.size() - 1, target));
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        double total = 0;
        for (int i = 0; i < open.size(); i++) { double w = Math.exp(-(i - target) * (i - target)); out.put(open.get(i).id(), w); total += w; }
        for (var entry : out.entrySet()) entry.setValue(entry.getValue() / total);
        return out;
    }

    /** One key drawn by weight. */
    static String sample(java.util.Map<String, Double> weights) {
        double total = 0;
        for (double w : weights.values()) total += Math.max(0, w);
        double roll = RANDOM.nextDouble() * total;
        String last = null;
        for (var entry : weights.entrySet()) { last = entry.getKey(); roll -= Math.max(0, entry.getValue()); if (roll <= 0) return entry.getKey(); }
        return last;
    }

    static String percent(java.util.Map<String, Double> weights) {
        StringBuilder out = new StringBuilder("{");
        for (var entry : weights.entrySet()) {
            if (out.length() > 1) out.append(' ');
            out.append(entry.getKey()).append(' ').append(Math.round(entry.getValue() * 100)).append('%');
        }
        return out.append('}').toString();
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
