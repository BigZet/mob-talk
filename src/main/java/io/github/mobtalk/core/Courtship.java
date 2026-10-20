package io.github.mobtalk.core;

/** Where a villager and a player stand romantically (spec §5.3, thresholds in {@link Hearts}): nothing, dating (a bouquet at 70),
 * engaged (a ring at 90, трек L: the wedding at the bell is still ahead), married, or widowed after the villager's partner died.
 * Kept with the villager's relation to the player. */
public enum Courtship {
    NONE(""), DATING("встречаетесь"), ENGAGED("помолвлены"), MARRIED("женаты"), WIDOWED("");
    public final String word;
    Courtship(String word) { this.word = word; }
    public static Courtship of(String name) {
        if (name == null || name.isBlank()) return NONE;
        try { return valueOf(name); } catch (IllegalArgumentException unknown) { return NONE; }
    }
    public boolean partners() { return this == DATING || this == ENGAGED || this == MARRIED; }
}
