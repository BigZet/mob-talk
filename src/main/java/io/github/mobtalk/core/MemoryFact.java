package io.github.mobtalk.core;

/** One line of what a mob remembers about a player, with how much it matters (1–5) and when it was learned.
 * {@code reflection}: not an event but a conclusion the mob drew about the player ("он всегда платит"). */
public record MemoryFact(String text, int importance, long time, boolean reflection) {
    /** The importance a fact gets when nothing says otherwise: an ordinary exchange. */
    public static final int ORDINARY = 2;
    /** The importance of a fact saved before importance existed. */
    public static final int MIGRATED = 3;
    /** What the mob concludes about the player weighs like a deal. */
    public static final int REFLECTION = 4;

    public MemoryFact {
        text = TextBounds.characters(text, 500);
        importance = clamp(importance);
    }

    public static MemoryFact of(String text, int importance, long time) { return new MemoryFact(text, importance, time, false); }

    public static int clamp(int importance) { return Math.max(1, Math.min(5, importance)); }
}
