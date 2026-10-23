package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One sin, one punishment: a sin under a running punishment strengthens it instead of laying another, and one punishment
 * moves the sinner at most once. */
class PenanceTest {
    private static final UUID SINNER = UUID.fromString("00000000-0000-0000-0000-000000000001"), OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Verdict wrath() { Verdict verdict = new Verdict(); verdict.inclination = Miracle.Kind.WRATH; verdict.fury = true; return verdict; }

    @Test void aSinUnderARunningPunishmentStrengthensIt() {
        assertEquals(Penance.Outcome.PUNISH, Penance.decide(wrath(), false));
        assertEquals(Penance.Outcome.STRENGTHEN, Penance.decide(wrath(), true), "no second punishment while the first runs");
        Verdict mercy = new Verdict(); mercy.inclination = Miracle.Kind.HELP;
        assertEquals(Penance.Outcome.NONE, Penance.decide(mercy, true), "mercy is no punishment");
        Verdict forgiven = wrath(); forgiven.forgiveness = true;
        assertEquals(Penance.Outcome.NONE, Penance.decide(forgiven, true));
    }

    @Test void aPunishmentRunsFromTheVerdictUntilAMinuteAfterItFell() {
        Penance penance = new Penance();
        assertFalse(penance.running(SINNER, 0, false));
        assertTrue(penance.running(SINNER, 0, true), "a curse or a boss still on him keeps it running");
        penance.begin(SINNER, "", 1000);
        assertTrue(penance.running(SINNER, 1000 + Penance.IN_FLIGHT_TICKS, false), "while the god speaks and the act is on its way");
        assertFalse(penance.running(OTHER, 1000, false), "only his");
        penance.fell(SINNER, "anvil_rain", 2000);
        assertTrue(penance.running(SINNER, 2000 + Penance.AFTERMATH_TICKS, false));
        assertFalse(penance.running(SINNER, 2001 + Penance.AFTERMATH_TICKS, false), "over a few seconds after it fell: the next sin is a new punishment");
        assertNull(penance.of(SINNER, 2001 + Penance.AFTERMATH_TICKS));
    }

    @Test void strengtheningIsBoundedAndForgivenessEndsIt() {
        Penance penance = new Penance();
        penance.begin(SINNER, "", 0);
        penance.fell(SINNER, "lightning", 10);
        Penance.Running one = null;
        for (int i = 0; i < 5; i++) one = penance.strengthen(SINNER, "", 20 + i);
        assertEquals(Penance.MAX_STRENGTH, one.strengthened);
        assertEquals("lightning", one.miracle, "the same punishment, not a new one");
        assertEquals(10 + Penance.AFTERMATH_TICKS, one.until, "sinning on makes it heavier, not longer: it ends as it would have");
        assertFalse(Penance.line(one.strengthened).isBlank());
        penance.end(SINNER);
        assertFalse(penance.running(SINNER, 30, false));
        // A punishment that ran only by its curse: the first sin under it starts a record of its own.
        Penance.Running quiet = penance.strengthen(OTHER, "", 100);
        assertEquals(1, quiet.strengthened);
        assertTrue(penance.running(OTHER, 100 + Penance.AFTERMATH_TICKS, false));
    }

    @Test void onePunishmentMovesTheSinnerOnce() {
        Penance penance = new Penance();
        assertTrue(penance.mayRelocate(SINNER, 0), "no punishment (a test command): it may move him");
        penance.begin(SINNER, "", 0);
        assertTrue(penance.mayRelocate(SINNER, 10));
        penance.relocated(SINNER, 10); // «Изыди из храма»
        assertFalse(penance.mayRelocate(SINNER, 20), "not carried home after the churchyard");
        penance.fell(SINNER, "raid", 30);
        assertFalse(penance.mayRelocate(SINNER, 40), "still the same punishment");
        assertTrue(penance.mayRelocate(SINNER, 41 + Penance.AFTERMATH_TICKS), "a later punishment is a new one");
        penance.begin(SINNER, "", 100_000);
        assertTrue(penance.mayRelocate(SINNER, 100_001));
    }
}
