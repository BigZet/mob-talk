package io.github.divineblock.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One sin, one punishment. A wrath verdict lays one punishment, and while it runs (from the verdict until a few seconds after
 * the act fell, or longer while its curse, its boss, the god with the mace or the Sky Court is still on the sinner) a new
 * sin lays no second one: it makes the running punishment heavier or longer instead. One punishment also moves the sinner
 * at most once (cast out of the church, taken home, taken up to the Sky Court): relocations never chain. Kept in memory on
 * the server thread; plain Java, for the tests. */
public final class Penance {
    /** From the verdict until the act falls: the god's words, the walk out of the church, the way home. */
    public static final long IN_FLIGHT_TICKS = 20 * 60 * 3;
    /** After the act fell, the punishment still counts as running for this long: a sin said in the same breath joins it. A punishment
     * that is over (meteors fallen, nothing left on him) does not run: the next sin is a new punishment (Dmitriy'"'"'s test, 23 Oct). */
    public static final long AFTERMATH_TICKS = 20 * 10;
    /** What one more sin under a running punishment adds to its curses (within their twenty minutes, {@link CurseTerm}). */
    public static final long PROLONG_TICKS = 20 * 60 * 5;
    /** How many times one punishment can be made heavier. */
    public static final int MAX_STRENGTH = 3;

    /** What a verdict does to the mortal: a new punishment, a heavier running one, or nothing to do with wrath. */
    public enum Outcome { PUNISH, STRENGTHEN, NONE }

    /** The punishment running on one mortal. */
    public static final class Running {
        public String miracle = "";
        public long since, until;
        /** How many sins since it was laid made it heavier. */
        public int strengthened;
        /** Whether it already moved the mortal somewhere. */
        public boolean moved;
    }

    private final Map<UUID, Running> running = new HashMap<>();

    /** The rule: wrath under a running punishment strengthens it; wrath otherwise is a new punishment; forgiveness, mercy
     * and jests are not punishments at all. */
    public static Outcome decide(Verdict verdict, boolean punishmentRunning) {
        if (verdict == null || verdict.forgiveness || verdict.inclination != Miracle.Kind.WRATH) return Outcome.NONE;
        return punishmentRunning ? Outcome.STRENGTHEN : Outcome.PUNISH;
    }

    /** The punishment running on this mortal at {@code now}, or null (an ended one is forgotten). */
    public Running of(UUID player, long now) {
        Running one = running.get(player);
        if (one != null && now > one.until) { running.remove(player); return null; }
        return one;
    }

    /** Whether a punishment of this mortal still runs: in this book, or by what it left on him ({@code lasting}: a curse,
     * a boss after him, the god with the mace, the Sky Court). */
    public boolean running(UUID player, long now, boolean lasting) { return lasting || of(player, now) != null; }

    /** A wrath verdict was made: the punishment runs from now, while the god speaks and the act is on its way. */
    public Running begin(UUID player, String miracle, long now) {
        Running one = new Running();
        one.miracle = miracle == null ? "" : miracle; one.since = now; one.until = now + IN_FLIGHT_TICKS;
        running.put(player, one);
        return one;
    }

    /** The act fell (it may be another of its group than the one announced): it runs a few seconds more. */
    public void fell(UUID player, String miracle, long now) {
        Running one = of(player, now);
        if (one == null) one = begin(player, miracle, now);
        if (miracle != null && !miracle.isBlank()) one.miracle = miracle;
        one.until = now + AFTERMATH_TICKS;
    }

    /** One more sin under it: heavier (up to {@link #MAX_STRENGTH}), but not longer — sinning on does not keep a punishment going
     * forever. When none runs here (it ran only by what it left: a curse, a boss), the caller starts a quiet record of it. */
    public Running strengthen(UUID player, String miracle, long now) {
        Running one = of(player, now);
        if (one == null) { one = begin(player, miracle, now); one.until = now + AFTERMATH_TICKS; }
        one.strengthened = Math.min(MAX_STRENGTH, one.strengthened + 1);
        return one;
    }

    /** Whether this punishment may still move the mortal: once per punishment. Without a running punishment (a test command)
     * it may. */
    public boolean mayRelocate(UUID player, long now) {
        Running one = of(player, now);
        return one == null || !one.moved;
    }
    /** The punishment moved the mortal: no other move until it is over. */
    public void relocated(UUID player, long now) {
        Running one = of(player, now);
        if (one != null) one.moved = true;
    }

    /** Forgiven, dead, the act cancelled: nothing runs any more. */
    public void end(UUID player) { running.remove(player); }
    public void clear() { running.clear(); }

    private static final List<String> LINES = List.of(
            "Кара твоя ещё не окончена, а ты множишь грех. Она станет тяжелее.",
            "Мало тебе? Гнев мой ещё на тебе — и он лишь крепнет.",
            "Не новую кару пошлю тебе: прежняя продлится и отяжелеет.");

    /** What the god says when a sin makes the running punishment heavier: no new act, the old one grows. */
    public static String line(int strengthened) { return LINES.get(Math.floorMod(strengthened - 1, LINES.size())); }
}
