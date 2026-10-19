package io.github.mobtalk.core;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/** The default decider: plain rules with a little chance. A sociable, interested creature speaks up more readily; a return or
 * an open deal nearly always gets a word; the next speaker in a scene is the one closest to the last; a bystander answers
 * small talk half the time. Deterministic given its {@link Random}. */
public final class RuleDecider implements Decider {
    /** How often a bystander is asked about an exchange with nothing in it. */
    public static final double SMALL_TALK_CHANCE = 0.5;
    private final Random random;

    public RuleDecider() { this(new Random()); }
    public RuleDecider(Random random) { this.random = random; }

    @Override public String name() { return "rules"; }

    /** The chance to speak: from the occasion, lifted by sociability and interest, lowered by a gloomy mood or distrust. */
    public static double speakChance(DecisionState state) {
        if (state.trigger() == null) return 0;
        double base = switch (state.trigger()) { case RETURN -> 0.9; case CONCERN -> 0.7; case BUILDING -> 0.6; case WEATHER -> 0.45; case FRIENDS -> 0.5; case GIFT -> 1.0; };
        double temper = (state.sociability() - 50) / 200.0 + (state.state().interest() - 50) / 400.0;
        double mood = state.state().mood() < 30 ? -0.1 : 0;
        double trust = state.state().trust() < Tuning.get().waryTrust && state.trigger() != Initiative.Trigger.FRIENDS ? -0.2 : 0;
        return Math.max(0.05, Math.min(0.95, base + temper + mood + trust));
    }
    /** The manner the occasion calls for, coloured by the mood. */
    public static Initiative.Reaction reaction(DecisionState state) {
        boolean gloomy = state.state().mood() < 40, wary = state.state().trust() < 40;
        return switch (state.trigger()) {
            case RETURN -> gloomy ? Initiative.Reaction.COMPLAIN : Initiative.Reaction.GREET;
            case CONCERN -> Initiative.Reaction.ASK;
            case BUILDING -> wary ? Initiative.Reaction.COMPLAIN : Initiative.Reaction.REMARK;
            case WEATHER -> gloomy || wary ? Initiative.Reaction.COMPLAIN : Initiative.Reaction.REMARK;
            case FRIENDS, GIFT -> Initiative.Reaction.REMARK;
        };
    }
    @Override public Initiative.Verdict speaksUp(DecisionState state) {
        if (state == null || state.trigger() == null || random.nextDouble() >= speakChance(state)) return Initiative.Verdict.QUIET;
        int level = state.sociability() >= 65 && state.state().mood() >= 50 ? 2 : state.sociability() < 35 ? 0 : 1;
        return new Initiative.Verdict(true, reaction(state), level);
    }

    /** The nearest candidate answers, with a chance that falls as the scene goes on (every line past the first is a coin toss
     * weighted by the bond); null when nobody feels like it. */
    @Override public UUID nextSpeaker(DecisionState state, List<Initiative.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        Initiative.Candidate best = null;
        for (Initiative.Candidate candidate : candidates) if (best == null || candidate.distance() < best.distance()) best = candidate;
        double chance = 0.6 + Math.max(-0.3, Math.min(0.3, best.bond() / 200.0));
        return random.nextDouble() < chance ? best.id() : null;
    }

    @Override public boolean bystanderAnswers(DecisionState state) {
        return state != null && state.eventful() || random.nextDouble() * 100 < Tuning.get().smallTalkChance;
    }
}
