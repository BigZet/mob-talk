package io.github.mobtalk.core;

import java.util.Locale;

/** What around a creature keeps its mood shifted while it lasts, unlike an {@link MobState.Event} that pushes once and fades:
 * the rain, a raid, hunger, a curse over the village, a friend standing near. Each moves the rest point of the axes
 * (mood, calm, energy) by its weight scaled by {@link MobTalkConfig#moodWorldPercent}; when the cause is gone its weight
 * fades out over {@code lingerMinutes}. A {@code sudden} factor (an alarm, a monster) moves the axis at once, a slow one (the
 * rain, hunger) lets it drift there with the usual half-life. The short Russian {@code text} is what the mob knows as the
 * reason and what the player reads on the card; it is a noun phrase, so it never needs the creature's gender. */
public enum MoodFactor {
    RAIN("дождь", -6, 0, -3, 3, false),
    STORM("гроза", -5, -15, 0, 5, true),
    NIGHT_OUT("ночь под открытым небом", 0, -10, -5, 2, false),
    SLEEPLESS("бессонная ночь", -5, -3, -25, 2, false),
    MONSTERS("монстры рядом", -3, -20, 0, 3, true),
    ALARM("тревога в деревне", -5, -35, 0, 15, true),
    RAID("набег на деревню", -10, -45, -5, 20, true),
    WOUNDED("рана", -5, -12, -15, 5, false),
    HUNGRY("голод", -15, -3, -12, 5, false),
    HOMELESS("нет своей кровати", -10, -5, 0, 10, false),
    JOBLESS("нет работы", -8, 0, 0, 10, false),
    CURSE("Божья кара над деревней", -15, -20, 0, 20, true),
    FRIEND_NEAR("друг рядом", 6, 3, 0, 2, false),
    FAMILY_NEAR("семья рядом", 4, 5, 0, 2, false),
    GUARDED("под защитой голема", 0, 8, 0, 2, false),
    SUNNY("ясный день", 3, 0, 2, 2, false);

    public final String text;
    public final int mood, calm, energy, lingerMinutes;
    public final boolean sudden;

    MoodFactor(String text, int mood, int calm, int energy, int lingerMinutes, boolean sudden) {
        this.text = text; this.mood = mood; this.calm = calm; this.energy = energy; this.lingerMinutes = lingerMinutes; this.sudden = sudden;
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }
    public int sign() { int sum = mood + calm + energy; return sum > 0 ? 1 : sum < 0 ? -1 : 0; }
    int delta(MobState.Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case ENERGY -> energy; default -> 0; }; }

    static MoodFactor of(String key) {
        if (key == null) return null;
        try { return valueOf(key.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return null; }
    }
}
