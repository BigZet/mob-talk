package io.github.mobtalk.core;

import java.util.Locale;

/** What the player does to a villager with words (docs/mca-social-spec.md §4.3, §7.2): the model names the gesture in its answer,
 * the game decided its outcome before the request ({@link GestureTable}, {@link SocialTurn}) and applies the effects after. */
public enum Gesture {
    NONE("", false),
    JOKE("шутка", false), COMPLIMENT("комплимент", false), HUG("объятие", false),
    FLIRT("флирт", true), KISS("поцелуй", true),
    GIFT("подарок", false);

    /** The word for the prompt. */
    public final String word;
    /** Romance: only between adults who are not blood relatives. */
    public final boolean romantic;
    Gesture(String word, boolean romantic) { this.word = word; this.romantic = romantic; }

    /** The model's string back to a gesture; anything unknown is NONE. */
    public static Gesture of(String name) {
        if (name == null || name.isBlank()) return NONE;
        try { return valueOf(name.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return NONE; }
    }

    /** How a gesture went, decided by the game: a plain success or failure, a slap, a gift's tier, an answer to a proposal. */
    public enum Outcome {
        SUCCESS, FAIL, SLAP,
        /** Gifts (spec §3.4): refused, given before too often, good, better, best. */
        REFUSED, ALREADY, GOOD, BETTER, BEST,
        /** A proposal accepted; a gift or proposal without the needed thing in hand. */
        ACCEPTED, NO_ITEM;
        /** Whether the villager takes it well. */
        public boolean warm() { return this == SUCCESS || this == GOOD || this == BETTER || this == BEST || this == ACCEPTED; }
    }
}
