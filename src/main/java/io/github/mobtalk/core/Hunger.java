package io.github.mobtalk.core;

import java.util.Set;

/** A villager's hunger. A meal keeps it full for a while — a hearty one (bread, a stew, anything cooked) for
 * {@link MobTalkConfig#moodHungerMinutes}, a treat (a pie, a cake, honey) half as long again, a meagre one (a raw carrot, an apple)
 * half as long; then it grows peckish, after one more such span hungry, after three it starves. The world watch feeds it from its
 * own pockets or a neighbour's when it is peckish; a player's food counts the same and a starving one remembers who fed it.
 * The minutes are real time, like the rest of the moods; 0 turns hunger off. */
public final class Hunger {
    private Hunger() { }
    private static final long MINUTE = 60_000L;

    public enum Level {
        FED(null), PECKISH(MoodFactor.PECKISH), HUNGRY(MoodFactor.HUNGRY), STARVING(MoodFactor.STARVING);
        public final MoodFactor factor;
        Level(MoodFactor factor) { this.factor = factor; }
        public boolean atLeast(Level other) { return ordinal() >= other.ordinal(); }
    }

    /** How a meal fills: the share of the usual time it keeps hunger away, and the push it gives. */
    public enum Meal {
        TREAT(150, MobState.Event.TREAT), HEARTY(100, MobState.Event.FED), MEAGER(50, MobState.Event.MEAGER_MEAL);
        public final int percent; public final MobState.Event event;
        Meal(int percent, MobState.Event event) { this.percent = percent; this.event = event; }
    }

    private static final Set<String> TREATS = Set.of("cake", "pumpkin_pie", "cookie", "honey_bottle", "golden_apple", "golden_carrot", "enchanted_golden_apple");
    private static final Set<String> HEARTY = Set.of("bread", "baked_potato", "mushroom_stew", "beetroot_soup", "rabbit_stew", "suspicious_stew");
    private static final Set<String> MEAGER = Set.of("potato", "carrot", "beetroot", "apple", "melon_slice", "sweet_berries", "glow_berries", "dried_kelp");

    /** What eating this item is (an id with or without the minecraft: namespace; other mods' cooked food is hearty); null: not food
     * a villager eats (wheat is for bread, raw meat is not eaten raw). */
    public static Meal meal(String itemId) {
        if (itemId == null) return null;
        String id = itemId.substring(itemId.indexOf(':') + 1);
        if (TREATS.contains(id)) return Meal.TREAT;
        if (HEARTY.contains(id) || id.startsWith("cooked_")) return Meal.HEARTY;
        if (MEAGER.contains(id)) return Meal.MEAGER;
        return null;
    }

    /** How long a meal keeps hunger away, with {@code minutes} the usual span. */
    public static long fullFor(Meal meal, int minutes) { return Math.round(Math.max(0, minutes) * MINUTE * (meal == null ? 1.0 : meal.percent / 100.0)); }

    /** The hunger now: full until {@code fedAt + fullMillis}, then peckish for one usual span, hungry for two more, then starving. */
    public static Level level(long fedAt, long fullMillis, long now, int minutes) {
        if (minutes <= 0 || fedAt <= 0) return Level.FED;
        long over = now - fedAt - fullMillis, span = minutes * MINUTE;
        return over < 0 ? Level.FED : over < span ? Level.PECKISH : over < 3 * span ? Level.HUNGRY : Level.STARVING;
    }
}
