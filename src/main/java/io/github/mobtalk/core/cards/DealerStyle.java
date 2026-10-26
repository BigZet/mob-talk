package io.github.mobtalk.core.cards;

import io.github.mobtalk.core.Persona;
import java.util.random.RandomGenerator;

/** How a villager plays when he holds the bank, fixed by his character so a player can learn to read him. The threshold is where he
 * stops drawing in ochko; in blackjack every dealer draws to 16 by the rule and the style shows only in talk, bets and the sleeve. */
public enum DealerStyle {
    CAUTIOUS("осторожный", 15, 15, 0),
    CALCULATING("расчётливый", 17, 17, 0),
    RECKLESS("азартный", 17, 19, 0.02),
    TRICKSTER("хитрец", 16, 18, 0.125);

    /** The word for the prompt and the admin card. */
    public final String word;
    /** The lowest and highest total at which he stops drawing in ochko; a trickster picks anew each deal. */
    public final int stopLow, stopHigh;
    /** The chance per deal that he reaches for his sleeve when the next card would bust him (when cheating is allowed). */
    public final double cheatChance;
    DealerStyle(String word, int stopLow, int stopHigh, double cheatChance) {
        this.word = word; this.stopLow = stopLow; this.stopHigh = stopHigh; this.cheatChance = cheatChance;
    }

    /** The total he stops at this deal. */
    public int stopAt(RandomGenerator random) { return stopLow + (stopHigh > stopLow ? random.nextInt(stopHigh - stopLow + 1) : 0); }

    /** A villager's style from his character; no character (a mob without a profile) plays the cautious way. */
    public static DealerStyle of(Persona persona) {
        if (persona == null) return CAUTIOUS;
        return switch (persona) {
            case GREEDY, PLAYFUL -> TRICKSTER;
            case EXTROVERT, CHEERFUL, FLIRTY -> RECKLESS;
            case FRIENDLY, UNFLAPPABLE, GRUMPY, ODD -> CALCULATING;
            case ANXIOUS, SENSITIVE, GLOOMY, PEACEFUL, INTROVERT -> CAUTIOUS;
        };
    }

    /** The saved name back to a style; null for blank or unknown. */
    public static DealerStyle named(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name); } catch (IllegalArgumentException unknown) { return null; }
    }
}
