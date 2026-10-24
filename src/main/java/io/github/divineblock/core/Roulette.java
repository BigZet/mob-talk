package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** The punishment roulette: pure theatre between the verdict and the act. The god (Jev or the rules) has already chosen the
 * punishment and its power; three reels spin on the screen and stop on its symbol ({@link RouletteSymbol}), the seven for the
 * end of the world. The server knows the result, the seed of the strips and the start tick, and lays the act at start +
 * {@link Timing#act}; every client draws the same reels from the same seed. This class is the shared arithmetic: timing by
 * mode and severity, the strips, where a reel stands at a given tick. Plain Java, for the tests and both sides. */
public final class Roulette {
    private Roulette() { }

    /** How long the spin takes. {@link #MINI} is the one-reel spin before a punishment's tail. */
    public enum Mode {
        SHORT("короткая"), NORMAL("обычная"), DRAMATIC("драматичная"), MINI("мини");
        public final String label;
        Mode(String label) { this.label = label; }
        public static Mode byName(String name) {
            for (Mode mode : values()) if (mode.name().equalsIgnoreCase(name == null ? "" : name.strip())) return mode;
            return null;
        }
    }
    /** Who else sees a sinner's roulette. */
    public enum Audience {
        SINNER("только грешнику"), RADIUS("в радиусе"), ALL("всем на сервере");
        public final String label;
        Audience(String label) { this.label = label; }
    }
    /** The roulette before a punishment's tail (the second wave, the aftershock): none, one reel, or the whole machine. */
    public enum Tails {
        OFF("нет"), MINI("мини"), FULL("полная");
        public final String label;
        Tails(String label) { this.label = label; }
    }

    /** The group a punishment belongs to (the first that lists it), or null. */
    public static MiracleGroup groupOf(Miracle miracle) {
        if (miracle == null) return null;
        for (MiracleGroup group : MiracleGroup.values()) if (group.kind == Miracle.Kind.WRATH && group.members.contains(miracle)) return group;
        return null;
    }

    /** How heavy, 1..6: the group's number (the sacred guards count as К4), else by the act's place on the ladder. Five lamps
     * light up to it; the frame is silver for 1–2, gold for 3–4, white-hot for 5 and black for 6. */
    public static int severity(Miracle miracle, MiracleGroup group) {
        MiracleGroup at = group != null && group.kind == Miracle.Kind.WRATH ? group : groupOf(miracle);
        if (miracle == Miracle.ETERNAL_BAN) return 6;
        if (at != null) return switch (at) { case K1 -> 1; case K2 -> 2; case K3 -> 3; case K4, KS -> 4; case K5 -> 5; case K6 -> 6; default -> 1; };
        int gravity = miracle == null ? 1 : miracle.gravity();
        return gravity <= 2 ? 1 : gravity <= 3 ? 2 : gravity <= 5 ? 3 : gravity <= 7 ? 4 : 5;
    }

    /** «Почти 777»: the third reel crawls past the seven before it stops — only for К3–К4, never on lighter or the heaviest. */
    public static boolean tease(int severity, boolean enabled, int chancePercent, Random random) {
        return enabled && (severity == 3 || severity == 4) && random.nextInt(100) < Math.max(0, Math.min(100, chancePercent));
    }

    /** The spin's moments in ticks from its start. {@code stops[i]}: reel i stands still; {@code decel[i]}: how long it brakes;
     * {@code fin}: the line flashes and the ribbon with the punishment's name rolls out (the god starts to speak);
     * {@code act}: the machine leaves and the punishment falls; {@code end}: it is gone from the screen. */
    public record Timing(Mode mode, int pull, int spinUp, int[] stops, int[] decel, int crawl, int fin, int act, int end) {
        public int reels() { return stops.length; }
        public static final int EXIT = 10;

        public static Timing of(Mode mode, int severity) {
            int s = Math.max(1, Math.min(5, severity)) - 1; // 0..4: heavier punishments keep the third reel spinning longer
            return switch (mode) {
                case SHORT -> make(mode, 4, 4, new int[] { 20, 28, 36 + Math.round(8 * s / 4f) }, new int[] { 10, 10, 12 }, 14, 20);
                case DRAMATIC -> make(mode, 8, 6, new int[] { 50, 80, 140 + Math.round(10 * s / 4f) }, new int[] { 24, 24, 60 }, 50, 40);
                case MINI -> make(mode, 0, 4, new int[] { 28 }, new int[] { 12 }, 0, 12);
                default -> make(mode, 8, 6, new int[] { 40, 60, 84 + Math.round(20 * s / 4f) }, new int[] { 20, 20, 24 }, 30, 30);
            };
        }
        private static Timing make(Mode mode, int pull, int spinUp, int[] stops, int[] decel, int crawl, int hold) {
            int fin = stops[stops.length - 1];
            return new Timing(mode, pull, spinUp, stops, decel, crawl, fin, fin + hold, fin + hold + EXIT);
        }
    }

    /** Cells a second at full speed: a blur. */
    public static final double SPEED = 1.0;
    /** Cells the teasing reel crawls in its last moments: the seven passes the line at a walking pace. */
    public static final int CRAWL_CELLS = 2;

    /** One reel's strip of twelve: the ordinary symbols shuffled by the seed. The ban's strip has the gates where the seven was;
     * a teasing reel has the seven right before its target, so it crawls past it. Same seed, same strip on every screen. */
    public static List<RouletteSymbol> strip(long seed, int reel, RouletteSymbol target, boolean tease) {
        List<RouletteSymbol> strip = new ArrayList<>(RouletteSymbol.STRIP);
        Collections.shuffle(strip, new Random(seed * 31 + reel * 7919L + 17));
        if (target == RouletteSymbol.GATE) strip.set(strip.indexOf(RouletteSymbol.SEVEN), RouletteSymbol.GATE);
        if (tease && target != RouletteSymbol.SEVEN && strip.contains(target)) {
            int at = strip.indexOf(target), seven = strip.indexOf(RouletteSymbol.SEVEN), before = Math.floorMod(at - 1, strip.size());
            if (seven >= 0) Collections.swap(strip, seven, before);
        }
        return strip;
    }

    /** Distance a reel travelled by {@code t} ticks (fractions allowed) since the spin began, before its stop. */
    static double travelled(Timing timing, int reel, boolean tease, double t) {
        double pull = timing.pull(), up = timing.spinUp(), stop = timing.stops()[reel];
        boolean crawling = tease && reel == timing.reels() - 1 && timing.crawl() > 0;
        double crawl = crawling ? timing.crawl() : 0, crawlSpeed = crawling ? 2.0 * CRAWL_CELLS / crawl : 0;
        double decelEnd = stop - crawl, decelStart = decelEnd - timing.decel()[reel];
        t = Math.min(t, stop);
        double d = 0;
        if (t <= pull) return 0;
        // Spinning up.
        double a = Math.min(t, pull + up) - pull;
        d += SPEED * a * a / (2 * up);
        if (t <= pull + up) return d;
        // Full speed.
        d += SPEED * (Math.min(t, decelStart) - pull - up);
        if (t <= decelStart) return d;
        // Braking from full speed down to the crawl: v = c + (V - c)(1 - u)^2.
        double length = decelEnd - decelStart, u = (Math.min(t, decelEnd) - decelStart) / length;
        d += length * (crawlSpeed * u + (SPEED - crawlSpeed) * (1 - Math.pow(1 - u, 3)) / 3);
        if (t <= decelEnd) return d;
        // The crawl: from the crawl speed down to nothing, CRAWL_CELLS cells.
        double w = (t - decelEnd) / crawl;
        return d + crawlSpeed * crawl * (w - w * w / 2);
    }

    /** Where reel {@code reel} stands at {@code t} ticks since the start, in cells of its strip (the cell under the line is
     * {@code round(position) mod 12}); at its stop it is exactly on {@code landing}, then bounces a little and settles. */
    public static double position(Timing timing, int reel, boolean tease, int landing, double t) {
        double stop = timing.stops()[reel];
        double start = landing - travelled(timing, reel, tease, stop);
        if (t < stop) return start + travelled(timing, reel, tease, t);
        double x = t - stop;
        return landing + (x < 8 ? 0.16 * Math.sin(Math.PI * x / 8) * (1 - x / 8) : 0);
    }

    /** The line in chat that stands in for the picture (and goes into the history): «✦ ⚡⚡⚡ Молния». */
    public static String chatLine(RouletteSymbol symbol, String punishment) {
        return "✦ " + symbol.glyph.repeat(3) + " " + punishment;
    }
}
