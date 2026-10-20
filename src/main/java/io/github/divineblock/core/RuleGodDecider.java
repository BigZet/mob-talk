package io.github.divineblock.core;

import java.util.List;

/** The deterministic chooser, and the fallback when Jev is off or silent: the act the prayer asks for if the verdict allows
 * it, else the strongest act the budget pays for. Ties are broken by the prayer's words and the day, never by a die. */
public final class RuleGodDecider implements GodDecider {
    @Override public String name() { return "rules"; }

    @Override public Choice choose(Verdict verdict, DivineConfig config) {
        List<Miracle> candidates = GodDecider.candidates(verdict, config);
        if (verdict.inclination == Miracle.Kind.OMEN || candidates.stream().allMatch(m -> m.kind == Miracle.Kind.OMEN)) {
            Miracle omen = verdict.favor > 0 ? Miracle.OMEN_LIGHT : verdict.favor < 0 ? Miracle.OMEN_THUNDER : Miracle.OMEN_LAUGH;
            if (!candidates.contains(omen)) omen = candidates.isEmpty() ? Miracle.IGNORE : candidates.get(0);
            return new Choice(omen, 0, false, name(), "знамение");
        }
        long salt = (verdict.prayer.strip().toLowerCase(java.util.Locale.ROOT).hashCode() * 31L + verdict.day) & Long.MAX_VALUE;
        Miracle best = null; long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Miracle miracle = candidates.get(i);
            // The asked-for act first, then the one closest to the budget, then the words' own tie-break.
            long score = miracle.hintMatches(verdict.prayer) * 1000L - Math.abs(verdict.budget - miracle.minTier) * 100L + (salt + i * 7919L) % 97;
            if (score > bestScore) { bestScore = score; best = miracle; }
        }
        int power = GodDecider.power(best, verdict.budget, verdict.budget);
        String note = best.hintMatches(verdict.prayer) > 0 ? "подходит к просьбе" : "сильнейшее по бюджету";
        return new Choice(best, power, GodDecider.mustWarn(best, power), name(), note);
    }
}
