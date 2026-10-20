package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The weights of each gesture's outcomes and what each does to the relationship (docs/mca-social-spec.md §4.2–4.3, own numbers
 * where MCA has none: the compliment). A weight is {@code base + Σ bonuses}; fatigue ({@link Rapport}) and repeating the same gesture
 * feed the cold outcomes and shrink the warm ones; a sensitive villager loses twice as much. Negative weights count as zero; when all
 * are zero the last option is taken. Pure Java. */
public final class GestureTable {
    private GestureTable() { }
    /** Each repeat of the same gesture in a row keeps this share of the warm effect. */
    public static final double REPEAT_DECAY = 0.6;
    /** Each repeat in a row adds this much to the cold outcomes' weight. */
    public static final int REPEAT_WEIGHT = 2;

    /** What the outcome depends on: character and marks, the mood axis of {@link MobState} (0..100), the relationship (−100..100),
     * fatigue and how many times this same gesture came right before, the couple's standing, whether the villager is grown up. */
    public record Context(Persona persona, Set<VillagerTrait> traits, int mood, int score, int fatigue, int repeats, Courtship courtship, boolean adult) {
        public Context { traits = traits == null ? Set.of() : Set.copyOf(traits); courtship = courtship == null ? Courtship.NONE : courtship; }
        boolean is(Persona p) { return persona == p; }
        /** MCA's two low mood levels on the 0..100 axis: a sad villager is easier to cheer up. */
        boolean depressed() { return mood < 20; }
        boolean sad() { return mood >= 20 && mood < 35; }
        boolean dating() { return courtship == Courtship.DATING || courtship == Courtship.ENGAGED; }
        boolean married() { return courtship == Courtship.MARRIED; }
        /** 0..1.5 from 10 up: affection that makes a hug or a kiss more welcome. */
        double affection() { return Math.min(1.5, Math.max(0, score / 100.0 - 0.1)); }
    }
    /** One possible outcome: its weight in the roll and its effect on the relationship. */
    public record Option(Gesture.Outcome outcome, double weight, int hearts) { }

    /** Every outcome of this gesture for this villager, the warm one first. Gifts are not here: see {@link GiftCatalog}. */
    public static List<Option> options(Gesture gesture, Context c) {
        double f = c.fatigue() + (double) REPEAT_WEIGHT * c.repeats();
        List<Option> list = new ArrayList<>();
        switch (gesture) {
            case JOKE -> {
                list.add(warm(c, 4 + b(c.is(Persona.CHEERFUL), 5) + b(c.is(Persona.FRIENDLY), 1) + b(c.is(Persona.GLOOMY), 7) + b(c.is(Persona.PLAYFUL), 3)
                        + b(c.depressed(), 7) + b(c.sad(), 5), 5));
                list.add(cold(c, Gesture.Outcome.FAIL, 2 + f + b(c.is(Persona.GRUMPY), 5), -2));
            }
            case COMPLIMENT -> {
                list.add(warm(c, 5 + b(c.is(Persona.FRIENDLY), 2) + b(c.is(Persona.FLIRTY), 3) + b(c.is(Persona.SENSITIVE), 2) + b(c.is(Persona.CHEERFUL), 1)
                        + 10 * Math.max(0, Math.min(1, c.score() / 100.0)), 3));
                list.add(cold(c, Gesture.Outcome.FAIL, 1 + f + b(c.is(Persona.ODD), 4) + b(c.is(Persona.GRUMPY), 3) + b(c.score() <= -20, 6), -2));
            }
            case HUG -> {
                list.add(warm(c, 5 + b(c.is(Persona.FRIENDLY), 5) + b(c.dating(), 3) + b(c.married(), 8) + 20 * c.affection(), 5));
                list.add(cold(c, Gesture.Outcome.FAIL, 3 + f + b(c.is(Persona.ODD), 5) + b(c.score() <= 0, 5), -3));
            }
            case FLIRT -> {
                list.add(warm(c, 5 + b(c.is(Persona.FLIRTY), 12) + b(c.dating(), 4) + b(c.married(), 6), 5));
                list.add(cold(c, Gesture.Outcome.FAIL, 3 + f + b(c.is(Persona.ODD), 5), -3));
                list.add(cold(c, Gesture.Outcome.SLAP, c.courtship().partners() ? 0 : 1 + f + b(c.is(Persona.ODD), 5) + b(c.score() <= 0, 20), -5));
            }
            case KISS -> {
                list.add(warm(c, 1 + b(c.is(Persona.FLIRTY), 10) + b(c.dating(), 5) + b(c.married(), 6) + 15 * c.affection(), 6));
                list.add(cold(c, Gesture.Outcome.FAIL, 5 + f + b(c.is(Persona.ODD), 5) + b(c.score() <= 10, 5), -4));
                list.add(cold(c, Gesture.Outcome.SLAP, c.courtship().partners() ? 0 : 1 + f + b(c.is(Persona.ODD), 5) + b(c.score() <= 0, 5), -5));
            }
            default -> { }
        }
        return list;
    }

    /** The weighted draw with {@code roll} in 0..1; all weights zero (or none above it) takes the last option, as MCA does. */
    public static Option roll(List<Option> options, double roll) {
        if (options == null || options.isEmpty()) return null;
        double total = 0;
        for (Option option : options) total += Math.max(0, option.weight());
        if (total <= 0) return options.getLast();
        double point = Math.max(0, Math.min(0.999999, roll)) * total;
        for (Option option : options) {
            point -= Math.max(0, option.weight());
            if (point < 0) return option;
        }
        return options.getLast();
    }
    /** The share of the draw each option has, for tests and the debug line. */
    public static double chance(List<Option> options, Gesture.Outcome outcome) {
        double total = 0, mine = 0;
        for (Option option : options) { double w = Math.max(0, option.weight()); total += w; if (option.outcome() == outcome) mine += w; }
        return total <= 0 ? (options.getLast().outcome() == outcome ? 1 : 0) : mine / total;
    }

    /** A warm effect shrinks with each repeat in a row. */
    static int warmHearts(int hearts, int repeats) { return (int) Math.round(hearts * Math.pow(REPEAT_DECAY, Math.max(0, repeats))); }
    /** A sensitive villager takes a failed gesture twice as hard. */
    static int coldHearts(int hearts, Persona persona) { return persona == Persona.SENSITIVE ? hearts * 2 : hearts; }
    private static Option warm(Context c, double weight, int hearts) { return new Option(Gesture.Outcome.SUCCESS, weight, warmHearts(hearts, c.repeats())); }
    private static Option cold(Context c, Gesture.Outcome outcome, double weight, int hearts) { return new Option(outcome, weight, coldHearts(hearts, c.persona())); }
    private static double b(boolean condition, double bonus) { return condition ? bonus : 0; }
}
