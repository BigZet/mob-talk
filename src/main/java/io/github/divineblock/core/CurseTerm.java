package io.github.divineblock.core;

/** How long a curse lasts, in game ticks (20 a second while the world runs). A curse never outlasts twenty real minutes
 * ({@link #MAX_TICKS}), whatever laid it and however it was prolonged; curses saved by an older version with a longer term
 * are cut down to it ({@link #trim}). Plain Java, for the tests. */
public final class CurseTerm {
    public static final long TICKS_PER_MINUTE = 1200;
    /** The settings' bounds in minutes; the default is the longest. */
    public static final int MIN_MINUTES = 1, MAX_MINUTES = 20;
    /** Twenty real minutes: the hard ceiling for every curse. */
    public static final long MAX_TICKS = MAX_MINUTES * TICKS_PER_MINUTE;

    private CurseTerm() { }

    /** The term the settings ask for, in ticks: {@code minutes} (clamped to 1..20) when curses linger, else {@code shortTicks}. */
    public static long ticks(DivineConfig config, long shortTicks) {
        return cap(config.lingeringCurses ? minutes(config.curseMinutes) * TICKS_PER_MINUTE : shortTicks);
    }

    public static int minutes(int minutes) { return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes)); }

    /** A term in ticks within 1 tick..{@link #MAX_TICKS}. */
    public static long cap(long ticks) { return Math.max(1, Math.min(MAX_TICKS, ticks)); }

    /** The end of a curse as it may stand at {@code now}: never later than twenty minutes ahead. */
    public static long trim(long until, long now) { return Math.min(until, now + MAX_TICKS); }

    /** A curse prolonged by {@code extra} ticks from its end (or from now, if it already ran out), still within the ceiling. */
    public static long prolong(long until, long now, long extra) { return trim(Math.max(until, now) + Math.max(0, extra), now); }
}
