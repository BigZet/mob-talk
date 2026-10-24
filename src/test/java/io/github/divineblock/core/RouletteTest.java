package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The punishment roulette: every punishment has its symbol (the seven only for the end of the world), every screen draws the
 * same strips from one seed, the reels stop exactly on the result, and the act falls exactly when the spin ends — unless a
 * pardon jams it. */
class RouletteTest {
    private static final UUID SINNER = UUID.fromString("00000000-0000-0000-0000-000000000007"), OTHER = UUID.fromString("00000000-0000-0000-0000-000000000008");

    /** Every punishment of the catalogue and every act of a wrath group. */
    private static List<Miracle> punishments() {
        List<Miracle> out = new ArrayList<>();
        for (Miracle miracle : Miracle.values()) if (miracle.kind == Miracle.Kind.WRATH || Roulette.groupOf(miracle) != null) out.add(miracle);
        return out;
    }

    @Test void everyPunishmentHasItsOwnSymbol() {
        for (Miracle miracle : punishments()) {
            assertNotNull(RouletteSymbol.own(miracle), miracle + " needs a symbol in RouletteSymbol.own");
            RouletteSymbol result = RouletteSymbol.result(miracle, null);
            assertNotNull(result, miracle.id());
            assertNotEquals(RouletteSymbol.DOVE, result, "the dove is only for a pardon");
            assertTrue(Roulette.severity(miracle, null) >= 1 && Roulette.severity(miracle, null) <= 6, miracle.id());
        }
        for (Miracle miracle : Miracle.values()) if (miracle.kind == Miracle.Kind.HELP || miracle.kind == Miracle.Kind.OMEN) assertNull(Roulette.groupOf(miracle));
    }

    @Test void theSevenIsOnlyForTheEndOfTheWorld() {
        for (Miracle miracle : punishments()) {
            MiracleGroup group = Roulette.groupOf(miracle);
            boolean doom = group == MiracleGroup.K5 || RouletteSymbol.doomsday(miracle);
            if (miracle == Miracle.ETERNAL_BAN) assertEquals(RouletteSymbol.GATE, RouletteSymbol.result(miracle, null), "the ban: three locked gates");
            else assertEquals(doom, RouletteSymbol.result(miracle, null) == RouletteSymbol.SEVEN, miracle.id());
        }
        for (Miracle boss : List.of(Miracle.BOSS_ARCHANGEL, Miracle.BOSS_HORSEMEN, Miracle.BOSS_BEAST, Miracle.GOD_MACE)) {
            assertEquals(RouletteSymbol.SEVEN, RouletteSymbol.result(boss, MiracleGroup.KS), "the end of the world wherever it comes from: " + boss.id());
            assertEquals(5, Roulette.severity(boss, null));
        }
        // The chosen group rules: a К5 punishment is a seven, a К6 one the gates.
        assertEquals(RouletteSymbol.SEVEN, RouletteSymbol.result(Miracle.FLOOD, MiracleGroup.K5));
        assertEquals(RouletteSymbol.GATE, RouletteSymbol.result(Miracle.ETERNAL_BAN, MiracleGroup.K6));
        assertEquals(RouletteSymbol.LIGHTNING, RouletteSymbol.result(Miracle.LIGHTNING, MiracleGroup.K1));
        assertEquals(RouletteSymbol.WAVE, RouletteSymbol.result(Miracle.ACID_RAIN, MiracleGroup.K2));
        assertEquals(RouletteSymbol.SKULL, RouletteSymbol.result(Miracle.UNDEAD_HORDE, MiracleGroup.K4));
        assertEquals(RouletteSymbol.TOWER, RouletteSymbol.result(Miracle.BABEL, MiracleGroup.K3), "a lighter group than its own keeps its symbol");
        // A punishment without a symbol of its own falls back on its group.
        assertEquals(RouletteSymbol.CRACK, RouletteSymbol.of(MiracleGroup.K3));
        assertEquals(RouletteSymbol.SEVEN, RouletteSymbol.of(MiracleGroup.K5));
        assertEquals(RouletteSymbol.GATE, RouletteSymbol.of(MiracleGroup.K6));
        assertEquals(1, Roulette.severity(Miracle.LIGHTNING, MiracleGroup.K1));
        assertEquals(4, Roulette.severity(Miracle.BOSS_GOLIATH, MiracleGroup.KS));
        assertEquals(6, Roulette.severity(Miracle.ETERNAL_BAN, null));
    }

    @Test void theChatLineStandsInForThePicture() {
        assertEquals("✦ ⚡⚡⚡ Молния", Roulette.chatLine(RouletteSymbol.LIGHTNING, "Молния"));
        assertEquals("✦ 777 Бог с булавой", Roulette.chatLine(RouletteSymbol.SEVEN, "Бог с булавой"));
    }

    @Test void theChatGlyphsAreInTheBasicPlane() {
        for (RouletteSymbol symbol : RouletteSymbol.values())
            assertTrue(symbol.glyph.codePoints().allMatch(c -> c < 0x10000), symbol + ": the game's font has no emoji above the basic plane");
    }

    @Test void oneSeedOneStripOnEveryScreen() {
        for (RouletteSymbol target : List.of(RouletteSymbol.LIGHTNING, RouletteSymbol.SEVEN, RouletteSymbol.GATE, RouletteSymbol.TOWER))
            for (int reel = 0; reel < 3; reel++) {
                List<RouletteSymbol> strip = Roulette.strip(42L, reel, target, false);
                assertEquals(12, strip.size());
                assertEquals(12, strip.stream().distinct().count(), "each symbol once");
                assertTrue(strip.contains(target), target + " is on the strip");
                assertEquals(strip, Roulette.strip(42L, reel, target, false), "the same seed draws the same strip");
            }
        assertNotEquals(Roulette.strip(1L, 0, RouletteSymbol.SKULL, false), Roulette.strip(2L, 0, RouletteSymbol.SKULL, false));
        assertFalse(Roulette.strip(9L, 1, RouletteSymbol.GATE, false).contains(RouletteSymbol.SEVEN), "the ban has no seven: gates in its place");
        for (long seed = 0; seed < 50; seed++) {
            List<RouletteSymbol> tease = Roulette.strip(seed, 2, RouletteSymbol.CRACK, true);
            int at = tease.indexOf(RouletteSymbol.CRACK);
            assertEquals(RouletteSymbol.SEVEN, tease.get(Math.floorMod(at - 1, 12)), "the teasing reel has the seven right before its target");
        }
    }

    @Test void theReelsStopExactlyOnTheResult() {
        for (Roulette.Mode mode : Roulette.Mode.values())
            for (int severity = 1; severity <= 6; severity++) {
                Roulette.Timing timing = Roulette.Timing.of(mode, severity);
                for (boolean tease : List.of(false, true))
                    for (int reel = 0; reel < timing.reels(); reel++)
                        for (int landing = 0; landing < 12; landing += 5) {
                            double stop = timing.stops()[reel];
                            assertEquals(landing, Roulette.position(timing, reel, tease, landing, stop), 1e-9, mode + " reel " + reel);
                            assertEquals(landing, Roulette.position(timing, reel, tease, landing, stop + 40), 1e-9, "settled after the bounce");
                            double before = Double.NEGATIVE_INFINITY;
                            for (double t = 0; t <= stop; t += 0.25) {
                                double p = Roulette.position(timing, reel, tease, landing, t);
                                assertTrue(p >= before - 1e-9, "a reel never turns back before it stops");
                                before = p;
                            }
                            assertTrue(stop - Roulette.position(timing, reel, tease, landing, 0) > 0, "it spins");
                            assertTrue(landing - Roulette.position(timing, reel, tease, landing, 0) > 6, mode + ": a good run of symbols");
                        }
            }
        // The teasing third reel crawls the last cells: the seven passes the line slowly, not in a blur.
        Roulette.Timing normal = Roulette.Timing.of(Roulette.Mode.NORMAL, 3);
        double stop = normal.stops()[2], mid = stop - normal.crawl() / 2.0;
        double speed = Roulette.position(normal, 2, true, 0, mid + 0.5) - Roulette.position(normal, 2, true, 0, mid - 0.5);
        assertTrue(speed < 0.1, "a walking pace past the seven: " + speed);
        double cell = Roulette.position(normal, 2, true, 0, stop - normal.crawl() * 0.6);
        assertEquals(-1, Math.round(cell), "the cell before the target (the seven) is under the line during the crawl");
    }

    @Test void timingByModeAndSeverity() {
        Roulette.Timing light = Roulette.Timing.of(Roulette.Mode.NORMAL, 1), heavy = Roulette.Timing.of(Roulette.Mode.NORMAL, 5);
        assertEquals(40, light.stops()[0], "the first reel stands at 2 s");
        assertEquals(60, light.stops()[1], "the second at 3 s");
        assertEquals(84, light.stops()[2], "the third at 4.2 s for the lightest");
        assertEquals(104, heavy.stops()[2], "and 5.2 s for the end of the world");
        assertEquals(134, heavy.act(), "the act at 6.7 s");
        assertEquals(heavy.fin() + 30, heavy.act(), "the ribbon holds 1.5 s");
        assertEquals(heavy.act() + Roulette.Timing.EXIT, heavy.end(), "and leaves in 0.5 s while the act begins");
        assertTrue(Roulette.Timing.of(Roulette.Mode.SHORT, 5).act() <= 64, "short: about three seconds");
        assertTrue(Roulette.Timing.of(Roulette.Mode.DRAMATIC, 1).act() >= 180, "dramatic: about nine seconds");
        assertEquals(40, Roulette.Timing.of(Roulette.Mode.MINI, 4).act(), "the mini roulette of a tail: two seconds");
        assertEquals(1, Roulette.Timing.of(Roulette.Mode.MINI, 4).reels());
        for (Roulette.Mode mode : Roulette.Mode.values()) {
            Roulette.Timing t = Roulette.Timing.of(mode, 3);
            for (int i = 1; i < t.reels(); i++) assertTrue(t.stops()[i] > t.stops()[i - 1], "the reels stop one after another");
        }
        assertFalse(Roulette.tease(1, true, 100, new Random(1)), "no tease on light punishments");
        assertFalse(Roulette.tease(5, true, 100, new Random(1)), "nor on the end of the world");
        assertTrue(Roulette.tease(3, true, 100, new Random(1)));
        assertFalse(Roulette.tease(4, false, 100, new Random(1)), "switched off");
        int teased = 0;
        Random random = new Random(5);
        for (int i = 0; i < 4000; i++) if (Roulette.tease(4, true, 25, random)) teased++;
        assertTrue(teased > 850 && teased < 1150, "about a quarter: " + teased);
    }

    /** What the queue's hooks did, in order, with the tick. */
    private static final class Log implements RouletteQueue.Hooks {
        final List<String> events = new ArrayList<>();
        long tick;
        final String name;
        Log(String name) { this.name = name; }
        @Override public void started(RouletteQueue.Spin spin) { events.add(name + " start@" + spin.start); }
        @Override public void fin(RouletteQueue.Spin spin) { events.add(name + " fin@" + tick); }
        @Override public void act(RouletteQueue.Spin spin) { events.add(name + " act@" + tick); }
        @Override public void cancelled(RouletteQueue.Spin spin, RouletteQueue.Cancel why) { events.add(name + " " + why + "@" + tick); }
    }

    private static void run(RouletteQueue queue, long from, long to, Log... logs) {
        for (long t = from; t <= to; t++) { for (Log log : logs) log.tick = t; queue.tick(t); }
    }

    @Test void thePunishmentFallsExactlyWhenTheSpinEnds() {
        for (Roulette.Mode mode : Roulette.Mode.values()) {
            RouletteQueue queue = new RouletteQueue();
            Roulette.Timing timing = Roulette.Timing.of(mode, 4);
            Log log = new Log("a");
            RouletteQueue.Spin spin = queue.offer(SINNER, 1000, timing, log);
            assertTrue(queue.spinning(SINNER));
            run(queue, 1000, 1000 + timing.act() - 1, log);
            assertEquals(List.of("a start@1000", "a fin@" + (1000 + timing.fin())), log.events, "nothing falls before the end");
            run(queue, 1000 + timing.act(), 1000 + timing.act() + 100, log);
            assertEquals("a act@" + (1000 + timing.act()), log.events.get(2), mode + ": exactly start + duration");
            assertEquals(3, log.events.size(), "once");
            assertEquals(1000 + timing.act(), spin.actTick());
            assertFalse(queue.spinning(SINNER));
        }
    }

    @Test void aPardonJamsTheReelsAndNothingFalls() {
        RouletteQueue queue = new RouletteQueue();
        Roulette.Timing timing = Roulette.Timing.of(Roulette.Mode.NORMAL, 3);
        Log log = new Log("a"), other = new Log("b"), waiting = new Log("c");
        queue.offer(SINNER, 0, timing, log);
        queue.offer(OTHER, 0, timing, other);
        queue.offer(SINNER, 0, Roulette.Timing.of(Roulette.Mode.MINI, 3), waiting);
        run(queue, 0, 50, log, other, waiting);
        log.tick = 50; waiting.tick = 50;
        assertNotNull(queue.cancel(SINNER, RouletteQueue.Cancel.PARDON));
        run(queue, 51, 400, log, other, waiting);
        assertEquals(List.of("a start@0", "a PARDON@50"), log.events, "repented while spinning: no final, no act");
        assertEquals(List.of("c PARDON@50"), waiting.events, "the spin waiting after it is forgiven too");
        assertTrue(other.events.contains("b act@" + timing.act()), "another sinner's punishment still falls");
        assertNull(queue.cancel(SINNER, RouletteQueue.Cancel.PARDON), "nothing left to pardon");
    }

    @Test void aSecondSpinWaitsForTheFirst() {
        RouletteQueue queue = new RouletteQueue();
        Roulette.Timing first = Roulette.Timing.of(Roulette.Mode.NORMAL, 2), second = Roulette.Timing.of(Roulette.Mode.MINI, 2);
        Log a = new Log("a"), b = new Log("b");
        queue.offer(SINNER, 10, first, a);
        RouletteQueue.Spin later = queue.offer(SINNER, 12, second, b);
        assertTrue(b.events.isEmpty(), "not shown while the first spins");
        run(queue, 10, 400, a, b);
        long firstAct = 10 + first.act();
        assertEquals("a act@" + firstAct, a.events.get(2));
        assertEquals("b start@" + firstAct, b.events.get(0), "starts when the first one's act falls");
        assertEquals("b act@" + (firstAct + second.act()), b.events.get(2));
        assertEquals(firstAct + second.act(), later.actTick());
    }

    @Test void leavingOrDyingStopsTheSpin() {
        RouletteQueue queue = new RouletteQueue();
        Log log = new Log("a");
        queue.offer(SINNER, 0, Roulette.Timing.of(Roulette.Mode.SHORT, 1), log);
        run(queue, 0, 10, log);
        queue.cancel(SINNER, RouletteQueue.Cancel.DIED);
        run(queue, 11, 200, log);
        assertEquals(List.of("a start@0", "a DIED@10"), log.events);
    }

    @Test void mercyByChanceFollowsItsChanceAndSparesNoBan() {
        Random random = new Random(3);
        for (int i = 0; i < 2000; i++) assertFalse(Roulette.mercy(Miracle.LIGHTNING, MiracleGroup.K1, 0, random), "chance 0: never");
        for (int i = 0; i < 2000; i++) assertTrue(Roulette.mercy(Miracle.METEOR, MiracleGroup.K3, 100, random), "chance 100: always");
        for (int i = 0; i < 2000; i++) {
            assertFalse(Roulette.mercy(Miracle.ETERNAL_BAN, null, 100, random), "the eternal ban has no mercy");
            assertFalse(Roulette.mercy(Miracle.ETERNAL_BAN, MiracleGroup.K6, 100, random));
        }
        int spared = 0;
        for (int i = 0; i < 20000; i++) if (Roulette.mercy(Miracle.SWARM, MiracleGroup.K2, 5, random)) spared++;
        assertTrue(spared > 800 && spared < 1200, "about one in twenty: " + spared);
        DivineConfig config = new DivineConfig();
        assertEquals(5, config.rouletteMercyChance, "5% by default");
        config.rouletteMercyChance = 90;
        assertEquals(25, config.sanitized().rouletteMercyChance, "at most 25%");
        assertNotNull(DivineSettings.setting("rouletteMercyChance"));
    }

    @Test void theThirdReelSlipsPastTheMatchOntoADove() {
        Roulette.Timing timing = Roulette.Timing.of(Roulette.Mode.NORMAL, 3);
        for (long seed = 0; seed < 50; seed++)
            for (RouletteSymbol target : List.of(RouletteSymbol.LIGHTNING, RouletteSymbol.SEVEN, RouletteSymbol.SKULL)) {
                List<RouletteSymbol> strip = Roulette.strip(seed, 2, target, false, true);
                assertEquals(RouletteSymbol.DOVE, Roulette.landing(target, 2, 3, true));
                assertEquals(target, Roulette.landing(target, 1, 3, true), "the first two reels stop on the punishment");
                int dove = strip.indexOf(RouletteSymbol.DOVE);
                assertTrue(dove >= 0);
                assertEquals(target, strip.get(Math.floorMod(dove - 1, 12)), "the match passes the line right before the dove");
                assertEquals(Roulette.strip(seed, 2, target, false, true), strip, "one seed, the same reel everywhere");
            }
        // The crawl: at its middle the punishment's own symbol is under the line, then the reel slips on to the dove.
        double stop = timing.stops()[2];
        assertEquals(-1, Math.round(Roulette.position(timing, 2, true, 0, stop - timing.crawl() * 0.6)));
        assertEquals(0, Roulette.position(timing, 2, true, 0, stop), 1e-9);
        assertEquals("✦ ⚡⚡☮ Помилован", Roulette.mercyLine(RouletteSymbol.LIGHTNING));
    }

    @Test void aSpinOfMercyLaysNoPunishment() {
        RouletteQueue queue = new RouletteQueue();
        Roulette.Timing timing = Roulette.Timing.of(Roulette.Mode.NORMAL, 4);
        List<String> events = new ArrayList<>();
        RouletteQueue.Hooks hooks = new RouletteQueue.Hooks() {
            @Override public void fin(RouletteQueue.Spin spin) { events.add("fin"); }
            @Override public void act(RouletteQueue.Spin spin) { events.add("act"); }
            @Override public void spared(RouletteQueue.Spin spin) { events.add("spared@" + spin.actTick()); }
        };
        RouletteQueue.Spin spin = queue.offer(SINNER, 100, timing, hooks, true);
        assertTrue(spin.mercy);
        for (long t = 100; t < 400; t++) queue.tick(t);
        assertEquals(List.of("fin", "spared@" + (100 + timing.act())), events, "the reels end, nothing falls");
        assertFalse(queue.spinning(SINNER));
    }

    @Test void theSettingsKnowTheRoulette() {
        DivineConfig config = new DivineConfig();
        assertTrue(config.rouletteEnabled);
        assertEquals(Roulette.Mode.NORMAL, config.rouletteMode);
        assertEquals(64, config.rouletteRadius);
        assertEquals(25, config.rouletteTeaseChance);
        assertEquals(Roulette.Tails.MINI, config.rouletteTails);
        config.rouletteRadius = 5; config.rouletteVolume = 900; config.rouletteMode = Roulette.Mode.MINI;
        config.sanitized();
        assertEquals(16, config.rouletteRadius);
        assertEquals(200, config.rouletteVolume);
        assertEquals(Roulette.Mode.NORMAL, config.rouletteMode, "the mini spin is only for tails");
        assertTrue(DivineSettings.personal("rouletteShow") && DivineSettings.personal("rouletteVolume") && DivineSettings.personal("rouletteJackpotFx")
                && DivineSettings.personal("rouletteFx"));
        assertNotNull(DivineSettings.setting("rouletteEnabled"));
        assertNotNull(DivineSettings.setting("rouletteMode"));
        var patched = DivineSettings.patch(config, com.google.gson.JsonParser.parseString("{\"rouletteMode\":\"MINI\",\"rouletteRadius\":300,\"rouletteAudience\":\"ALL\"}").getAsJsonObject());
        assertEquals(List.of("rouletteAudience"), patched.applied(), "MINI and out-of-bounds radius refused");
        assertEquals(Roulette.Audience.ALL, config.rouletteAudience);
    }
}
