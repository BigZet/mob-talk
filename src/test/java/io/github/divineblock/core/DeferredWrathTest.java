package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A sinner who logs out before the god's last word: the punishment waits for him one game day, then is written off. */
class DeferredWrathTest {
    private static final UUID SINNER = UUID.fromString("00000000-0000-0000-0000-000000000001"), OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Verdict verdict(Miracle miracle) { Verdict verdict = new Verdict(); verdict.miracle = miracle.id(); verdict.power = 3; return verdict; }

    @Test void aPunishmentWaitsForTheSinnersReturnWithinADay() {
        DeferredWrath deferred = new DeferredWrath();
        assertTrue(deferred.hold(SINNER, "Дима", verdict(Miracle.LIGHTNING), "minecraft:overworld", new int[] { 1, 64, 2 }, 1000));
        assertTrue(deferred.waits(SINNER));
        assertFalse(deferred.waits(OTHER), "nobody else is punished for him");
        assertNull(deferred.claim(OTHER));
        DeferredWrath.Held back = deferred.claim(SINNER);
        assertNotNull(back);
        assertFalse(back.expired(1000 + DeferredWrath.WAIT_TICKS), "still within the day");
        assertEquals("lightning", back.verdict.miracle);
        assertEquals("Дима", back.name);
        assertArrayEquals(new int[] { 1, 64, 2 }, back.altar);
        assertFalse(deferred.waits(SINNER), "taken out once claimed: it falls only once");
    }

    @Test void afterADayItIsWrittenOff() {
        DeferredWrath deferred = new DeferredWrath();
        deferred.hold(SINNER, "Дима", verdict(Miracle.FLOOD), "minecraft:overworld", null, 0);
        assertTrue(deferred.expire(DeferredWrath.WAIT_TICKS).isEmpty(), "exactly one day still waits");
        var over = deferred.expire(DeferredWrath.WAIT_TICKS + 1);
        assertEquals(1, over.size());
        assertEquals("flood", over.get(0).verdict.miracle);
        assertFalse(deferred.waits(SINNER));
        // Coming back too late: the claim returns it marked expired, so the game writes it off instead of striking.
        deferred.hold(SINNER, "Дима", verdict(Miracle.FLOOD), "minecraft:overworld", null, 0);
        assertTrue(deferred.claim(SINNER).expired(DeferredWrath.WAIT_TICKS + 5));
    }

    @Test void mercyAndJestsAreNotKept() {
        DeferredWrath deferred = new DeferredWrath();
        assertFalse(deferred.hold(SINNER, "Дима", verdict(Miracle.HEAL), "", null, 0));
        assertFalse(deferred.hold(SINNER, "Дима", verdict(Miracle.CHICKEN_RAIN), "", null, 0));
        assertFalse(deferred.hold(SINNER, "Дима", verdict(Miracle.OMEN_LIGHT), "", null, 0));
        assertFalse(deferred.waits(SINNER));
        assertTrue(deferred.hold(SINNER, "Дима", verdict(Miracle.ETERNAL_BAN), "", null, 0), "the ban waits too");
    }

    @Test void itIsSavedWithTheLedger() {
        GodLedger ledger = new GodLedger();
        ledger.deferred.hold(SINNER, "Дима", verdict(Miracle.METEOR), "minecraft:overworld", new int[] { 5, 70, -3 }, 77);
        GodLedger again = GodLedger.fromJson(ledger.toJson());
        DeferredWrath.Held held = again.deferred.claim(SINNER);
        assertEquals("meteor", held.verdict.miracle);
        assertEquals(77, held.heldAt);
        assertNotNull(GodLedger.fromJson("{\"souls\":{}}").deferred, "an old ledger without the list loads");
    }
}
