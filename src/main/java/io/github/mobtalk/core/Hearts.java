package io.github.mobtalk.core;

/** «Сердечки» are the relationship score itself (−100..100), not a second scale (docs/mca-social-spec.md §3.1, §8): from 40 the villager
 * counts the player a friend, from 70 it agrees to court (a bouquet), from 90 to marry (a ring). Pure Java. */
public final class Hearts {
    private Hearts() { }
    public static final int FRIEND = 40, DATING = 70, MARRIAGE = 90;
    /** A child born to the player starts at this towards that parent. */
    public static final int OWN_CHILD = 100;

    /** How many hearts the HUD shows: none below 40, one, two from 70, three from 90; a black one below zero. */
    public static String symbols(int score) {
        if (score < 0) return "♡";
        return score >= MARRIAGE ? "♥♥♥" : score >= DATING ? "♥♥" : score >= FRIEND ? "♥" : "";
    }
    /** The step in words: «друг», «можно ухаживать», «можно свататься»; "" below friendship. */
    public static String step(int score) {
        return score >= MARRIAGE ? "можно свататься" : score >= DATING ? "можно ухаживать" : score >= FRIEND ? "друг" : "";
    }
}
