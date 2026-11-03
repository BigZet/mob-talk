package io.github.mobtalk.core;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The passport's paperwork (track «чиновники», stage 2): the round of offices, «приходите завтра», lunch, the queue, the rush. */
class PaperworkTest {
    static final Set<Officialdom.Rank> ALL = EnumSet.allOf(Officialdom.Rank.class);
    /** Pockets with plenty of everything, or nothing. */
    static Paperwork.Pockets rich() { return new Paperwork.Pockets() { public int count(String item) { return 64; } public int emeralds() { return 64; } }; }
    static Paperwork.Pockets empty() { return new Paperwork.Pockets() { public int count(String item) { return 0; } public int emeralds() { return 0; } }; }
    static final long MORNING = 2000; // 8:00

    static Paperwork.Case kase(List<Paperwork.Step> steps) {
        Officialdom ledger = new Officialdom();
        return ledger.open(UUID.randomUUID(), "Дима", "v", "Берёзовка", steps, 0);
    }

    @Test void theRoundStartsWithTheApplicationAndEndsWithThePassport() {
        UUID player = UUID.randomUUID();
        List<Paperwork.Step> chain = Paperwork.chain(player, 1, 2, ALL);
        assertEquals(4, chain.size());
        assertEquals(Paperwork.Step.APPLICATION, chain.getFirst()); assertEquals(Paperwork.Step.ISSUE, chain.getLast());
        assertEquals(chain, Paperwork.chain(player, 1, 2, ALL), "the same player and case give the same round");
        assertEquals(2, Paperwork.chain(player, 1, 0, ALL).size());
        assertEquals(7, Paperwork.chain(player, 1, 9, ALL).size(), "at most every paper once");
        for (Paperwork.Step s : Paperwork.chain(player, 1, 5, Set.of(Officialdom.Rank.CLERK))) assertEquals(Officialdom.Rank.CLERK, s.office, "only offices the village has");
    }

    @Test void theOfficeRound() {
        Paperwork.Case c = kase(List.of(Paperwork.Step.APPLICATION, Paperwork.Step.RESIDENCE, Paperwork.Step.DUTY, Paperwork.Step.ISSUE));
        // The application at the clerk needs a sheet of paper.
        assertEquals(Paperwork.Result.MISSING, Paperwork.visit(c, Officialdom.Rank.CLERK, 0, MORNING, true, empty(), 3, 1).result());
        Paperwork.Visit v = Paperwork.visit(c, Officialdom.Rank.CLERK, 0, MORNING, true, rich(), 3, 1);
        assertEquals(Paperwork.Result.DONE, v.result()); assertEquals("minecraft:paper", v.item()); assertEquals(1, v.count());
        assertEquals(Paperwork.Step.RESIDENCE, v.next());
        // The clerk has nothing for him now: the residence certificate is the head's.
        Paperwork.Visit wrong = Paperwork.visit(c, Officialdom.Rank.CLERK, 0, MORNING, true, rich(), 3, 1);
        assertEquals(Paperwork.Result.WRONG_OFFICE, wrong.result()); assertEquals(Officialdom.Rank.HEAD, wrong.step().office);
        // The head takes the papers: ready tomorrow.
        Paperwork.Visit accepted = Paperwork.visit(c, Officialdom.Rank.HEAD, 0, MORNING, true, rich(), 3, 1);
        assertEquals(Paperwork.Result.ACCEPTED, accepted.result()); assertEquals(1, accepted.readyDay());
        assertEquals(Paperwork.Result.NOT_READY, Paperwork.visit(c, Officialdom.Rank.HEAD, 0, MORNING, true, rich(), 3, 1).result());
        assertEquals(Paperwork.Result.DONE, Paperwork.visit(c, Officialdom.Rank.HEAD, 1, MORNING, true, rich(), 3, 1).result());
        // The state fee.
        assertEquals(Paperwork.Result.MISSING, Paperwork.visit(c, Officialdom.Rank.HEAD, 1, MORNING, true, empty(), 3, 1).result());
        Paperwork.Visit fee = Paperwork.visit(c, Officialdom.Rank.HEAD, 1, MORNING, true, rich(), 3, 1);
        assertEquals(Paperwork.Result.DONE, fee.result()); assertEquals(3, fee.fee()); assertEquals(Paperwork.Step.ISSUE, fee.next());
        assertEquals(2, fee.readyDay(), "the passport takes a day after the last paper");
        assertEquals(Paperwork.Result.NOT_READY, Paperwork.visit(c, Officialdom.Rank.CLERK, 1, MORNING, true, rich(), 3, 1).result());
        assertEquals(Paperwork.Result.ISSUED, Paperwork.visit(c, Officialdom.Rank.CLERK, 2, MORNING, true, rich(), 3, 1).result());
    }

    @Test void lunchAndNoCase() {
        assertEquals(13, Paperwork.hour(7000)); assertTrue(Paperwork.lunch(7500)); assertFalse(Paperwork.lunch(8000)); assertEquals(6, Paperwork.hour(0));
        assertEquals(Paperwork.Result.LUNCH, Paperwork.visit(null, Officialdom.Rank.CLERK, 0, 7200, true, rich(), 3, 1).result());
        assertEquals(Paperwork.Result.OPENED, Paperwork.visit(null, Officialdom.Rank.CLERK, 0, 7200, false, rich(), 3, 1).result(), "lunch off");
        assertEquals(Paperwork.Result.NO_CASE, Paperwork.visit(null, Officialdom.Rank.HEAD, 0, MORNING, true, rich(), 3, 1).result());
        assertNotEquals(Paperwork.Result.LUNCH, Paperwork.visit(null, Officialdom.Rank.INSPECTOR, 0, 7200, true, rich(), 3, 1).result(), "the inspector eats on the go");
    }

    @Test void passportAtOnceWithZeroDays() {
        Paperwork.Case c = kase(List.of(Paperwork.Step.APPLICATION, Paperwork.Step.ISSUE));
        assertEquals(Paperwork.Result.ISSUED, Paperwork.visit(c, Officialdom.Rank.CLERK, 0, MORNING, true, rich(), 3, 0).result());
    }

    @Test void theEnvelopeRushesAndTheSweetHurries() {
        Paperwork.Case c = kase(List.of(Paperwork.Step.APPLICATION, Paperwork.Step.STAMP, Paperwork.Step.ISSUE));
        Paperwork.visit(c, Officialdom.Rank.CLERK, 0, MORNING, true, rich(), 3, 1);
        Paperwork.visit(c, Officialdom.Rank.HEAD, 0, MORNING, true, rich(), 3, 1);
        assertTrue(c.waiting());
        assertTrue(Paperwork.hurry(c, 0)); assertFalse(Paperwork.hurry(c, 0), "nothing more to hurry");
        assertEquals(Paperwork.Result.DONE, Paperwork.visit(c, Officialdom.Rank.HEAD, 0, MORNING, true, rich(), 3, 1).result());
        Paperwork.rush(c);
        assertNull(c.current());
        assertEquals(Paperwork.Result.ISSUED, Paperwork.visit(c, Officialdom.Rank.CLERK, 0, MORNING, true, empty(), 3, 1).result());
    }

    @Test void theQueue() {
        UUID clerk = UUID.randomUUID();
        int q = Paperwork.queue(clerk, 5, 2);
        assertTrue(q >= 2 && q <= 4); assertEquals(q, Paperwork.queue(clerk, 5, 2), "the same all day");
        assertEquals(6, Paperwork.queue(clerk, 5, 40));
    }

    @Test void numbersAndPrompt() {
        String n = Paperwork.passportNumber("v", 3, 100001);
        assertTrue(n.matches("\\d{2} \\d{2} №100001"), n);
        Paperwork.Case c = kase(List.of(Paperwork.Step.APPLICATION, Paperwork.Step.HOUSING, Paperwork.Step.ISSUE));
        Paperwork.Case back = Paperwork.caseOf(c.data());
        assertEquals(c.number(), back.number()); assertEquals(Paperwork.Step.APPLICATION, back.current());
        String text = Paperwork.prompt(Officialdom.Rank.HEAD, back, 0);
        assertTrue(text.contains("заявление по форме №1П")); assertTrue(text.contains("в другой кабинет"));
        assertEquals("", Paperwork.prompt(null, back, 0));
        assertNull(Paperwork.caseOf(Map.of("steps", List.of())));
    }

    @Test void casesSurviveTheWorldMemory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try {
            ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
            UUID player = UUID.randomUUID();
            Paperwork.Case c = store.paperOpen(player, "Дима", "v", "Берёзовка", 2, ALL, 0);
            Paperwork.visit(c, Officialdom.Rank.CLERK, 0, MORNING, true, rich(), 3, 1);
            store.paperSave(c);
            ConversationStore.closeWorld(directory);
            ConversationStore reopened = ConversationStore.open(directory, new MobTalkConfig());
            Paperwork.Case back = reopened.paperCase(player);
            assertEquals(c.number(), back.number()); assertEquals(1, back.index()); assertEquals("Берёзовка", back.villageTitle());
            String number = reopened.paperIssue(player, "v", 0);
            assertTrue(number.endsWith("№100001"));
            assertNull(reopened.paperCase(player));
            assertTrue(reopened.paperIssue(UUID.randomUUID(), "v", 0).endsWith("№100002"));
        } finally { ConversationStore.closeWorld(directory); }
    }
}
