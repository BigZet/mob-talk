package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.List;

/** Turns what the god knows about a prayer into named, weighted reasons and sums them. No hidden dice: the same deeds and
 * the same prayer give the same verdict, and every number has words the god can say. */
public final class VerdictBuilder {
    /** Everything a verdict depends on, gathered on the game thread. */
    public record Context(String prayer, PrayerTone tone, int offeringValue, String offeringText, boolean insultingOffering,
                          double righteousness, String righteousnessLabel, List<Righteousness.Memory> notableDeeds, long today,
                          int mood, int recentPrayers, boolean thundering, int moonPhase, boolean inDanger) { }

    private VerdictBuilder() { }

    public static Verdict build(Context context, DivineConfig config) {
        List<Verdict.Reason> reasons = new ArrayList<>();
        reasons.add(new Verdict.Reason("молитва услышана", 0, 1));

        double r = context.righteousness();
        if (r >= 60) reasons.add(new Verdict.Reason("ты святой", 3, 1));
        else if (r >= 20) reasons.add(new Verdict.Reason("ты праведник", 2, 0));
        else if (r <= -60) reasons.add(new Verdict.Reason("ты проклят за свои дела", -3, 2));
        else if (r <= -20) reasons.add(new Verdict.Reason("ты грешник", -2, 1));

        // Fresh deeds weigh on top of the score: the god remembers what you did yesterday.
        for (Righteousness.Memory memory : context.notableDeeds()) {
            int weight = memory.total >= 10 ? 2 : memory.total >= 1 ? 1 : memory.total <= -10 ? -2 : -1;
            reasons.add(new Verdict.Reason(memory.describe(context.today()), weight, Math.abs(weight) >= 2 ? 1 : 0));
        }

        if (context.mood() >= 30) reasons.add(new Verdict.Reason("бог к тебе благосклонен", 1, 0));
        else if (context.mood() <= -30) reasons.add(new Verdict.Reason("бог на тебя зол", -1, 1));

        if (context.insultingOffering()) reasons.add(new Verdict.Reason("подношение-оскорбление: " + context.offeringText(), -2, 1));
        else if (context.offeringValue() > 0) {
            int value = context.offeringValue();
            int favor = value >= 60 ? 3 : value >= 20 ? 2 : 1;
            reasons.add(new Verdict.Reason("подношение: " + context.offeringText(), favor, value >= 40 ? 1 : 0));
        }

        PrayerTone tone = context.tone();
        if (tone.rude()) reasons.add(new Verdict.Reason("хамство в молитве", -2, 1));
        else if (tone.polite()) reasons.add(new Verdict.Reason("смиренная молитва", 1, 0));
        if (tone.repents()) reasons.add(new Verdict.Reason("раскаяние", 1, 0));
        if (tone.urgent() || context.inDanger()) reasons.add(new Verdict.Reason(context.inDanger() ? "смертный в беде" : "мольба о срочной помощи", context.inDanger() ? 1 : 0, 1));

        if (context.recentPrayers() >= 2) {
            int extra = Math.min(3, context.recentPrayers() - 1);
            reasons.add(new Verdict.Reason("просишь уже " + (context.recentPrayers() + 1) + "-й раз подряд", -extra, 0));
        }

        DivineConfig.Personality personality = config.personality;
        if (personality.favor != 0 || personality.force != 0)
            reasons.add(new Verdict.Reason("нрав бога: " + personality.label, personality.favor, personality.force));

        int whim = config.whimWeight;
        if (whim > 0) {
            if (context.thundering()) reasons.add(new Verdict.Reason("в грозу бог гневлив", -whim, 1));
            else if (context.moonPhase() == 0) reasons.add(new Verdict.Reason("в полнолуние бог щедр", whim, 0));
            else if (context.moonPhase() == 4) reasons.add(new Verdict.Reason("в новолуние богу скучно", 0, whim));
        }

        Verdict verdict = new Verdict();
        verdict.prayer = context.prayer() == null ? "" : context.prayer();
        verdict.reasons = reasons;
        verdict.day = context.today();
        for (Verdict.Reason reason : reasons) { verdict.favor += reason.favor(); verdict.force += reason.force(); }
        verdict.inclination = inclination(verdict.favor, verdict.force, personality);
        verdict.budget = budget(verdict, r, config);
        return verdict;
    }

    static Miracle.Kind inclination(int favor, int force, DivineConfig.Personality personality) {
        if (favor >= 2) return Miracle.Kind.HELP;
        if (favor <= -2) return Miracle.Kind.WRATH;
        if (personality == DivineConfig.Personality.TRICKSTER || force >= 2 || favor != 0) return Miracle.Kind.JEST;
        return Miracle.Kind.OMEN;
    }

    /** The strongest tier the reasons earn: force sets it, the depth of mercy or wrath can raise it, and apocalyptic wrath needs
     * a truly damned soul. Capped by the settings. */
    static int budget(Verdict verdict, double righteousness, DivineConfig config) {
        int budget = verdict.force;
        if (verdict.inclination == Miracle.Kind.HELP && verdict.favor >= 5) budget++;
        if (verdict.inclination == Miracle.Kind.WRATH && verdict.favor <= -5) budget++;
        budget = Math.max(0, Math.min(4, budget));
        if (verdict.inclination == Miracle.Kind.WRATH) {
            if (budget >= 4 && righteousness > -60) budget = 3;
            if (budget >= 3 && verdict.favor > -4) budget = 2;
        }
        if (verdict.inclination == Miracle.Kind.JEST) budget = Math.min(budget, 3);
        if (verdict.inclination == Miracle.Kind.OMEN) budget = 0;
        return Math.min(budget, config.maxTier);
    }
}
