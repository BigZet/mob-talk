package io.github.divineblock.core;

import java.util.List;

/** The fallback when Jev is off or silent: a group drawn by the escalation ladder alone, an act drawn from it at random
 * without repeats; a pestering mortal who earned no mercy gets silence, and a
 * verdict with no group gets an omen. */
public final class RuleGodDecider implements GodDecider {
    @Override public String name() { return "rules"; }

    @Override public Choice choose(Verdict verdict, DivineConfig config) {
        if (config.allows(Miracle.IGNORE) && !verdict.fury && verdict.inclination != Miracle.Kind.HELP && pestering(verdict))
            return new Choice(Miracle.IGNORE, 0, false, name(), "наскучил просьбами: молчание", "");
        List<MiracleGroup> open = MiracleGroup.open(verdict, config);
        if (verdict.inclination == Miracle.Kind.OMEN || open.isEmpty()) return omen(verdict, config, name());
        // Without Jev the ladder alone decides, by weighted draw: escalation without sameness.
        MiracleGroup group = MiracleGroup.byId(GodDecider.sample(GodDecider.ladder(verdict, open)));
        // Mercy answers what was asked: the group holding the act named in the prayer, if one is open.
        if (verdict.inclination != Miracle.Kind.WRATH)
            for (MiracleGroup candidate : open)
                if (candidate.allowed(verdict, config).stream().anyMatch(m -> m.hintMatches(verdict.prayer) > 0)) group = candidate;
        return GodDecider.drawn(verdict, config, group, name(), "лестница " + GodDecider.percent(GodDecider.ladder(verdict, open)));
    }

    static Choice omen(Verdict verdict, DivineConfig config, String by) {
        Miracle omen = verdict.forgiveness || verdict.favor > 0 ? Miracle.OMEN_LIGHT : verdict.favor < 0 ? Miracle.OMEN_THUNDER : Miracle.OMEN_LAUGH;
        if (!config.allows(omen)) omen = Miracle.OMEN_LIGHT;
        return new Choice(omen, 0, false, by, verdict.forgiveness ? "прощение" : "знамение", "");
    }

    /** Asking again and again weighs as a named reason; two or more points of it make the god turn away. */
    static boolean pestering(Verdict verdict) {
        return verdict.reasons.stream().anyMatch(r -> r.text().startsWith("просишь уже") && r.favor() <= -2);
    }
}
