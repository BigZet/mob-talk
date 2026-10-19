package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionTest {
    // A reflection needs both enough replies and enough that mattered, and never twice within a minute.
    @Test void dueOnlyAfterEnoughRepliesAndImportanceAndCooldown() {
        Reflection.Counters counters = new Reflection.Counters();
        for (int i = 0; i < Reflection.REPLIES_BETWEEN; i++) counters.reply(MemoryFact.ORDINARY);
        assertFalse(counters.due(1000), "four ordinary replies total 8, not more than the threshold");
        counters.reply(MemoryFact.ORDINARY);
        assertTrue(counters.due(1000), "the fifth ordinary reply crosses the threshold");
        counters.started(1000);
        assertFalse(counters.due(1000));
        counters.add(MemoryRanking.HIT); counters.add(MemoryRanking.HIT);
        assertFalse(counters.due(5000), "importance alone is not enough: too few replies since");
        for (int i = 0; i < Reflection.REPLIES_BETWEEN; i++) counters.reply(MemoryFact.ORDINARY);
        assertFalse(counters.due(1000 + Reflection.COOLDOWN_MILLIS - 1), "not within the cooldown");
        assertTrue(counters.due(1000 + Reflection.COOLDOWN_MILLIS));
        Reflection.Counters copy = counters.copy();
        counters.started(70_000);
        assertTrue(copy.due(70_000), "the copy is detached");
        assertEquals(0, counters.replies); assertEquals(0, counters.importance);
    }

    @Test void repliesOfLowImportanceStillCountAsOrdinary() {
        Reflection.Counters counters = new Reflection.Counters();
        for (int i = 0; i < 3; i++) counters.reply(1);
        counters.reply(5);
        assertEquals(8, counters.importance);
        assertFalse(counters.due(0));
        counters.reply(1);
        assertTrue(counters.due(0));
    }
}
