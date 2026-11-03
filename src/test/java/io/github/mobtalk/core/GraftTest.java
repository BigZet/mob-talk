package io.github.mobtalk.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The head's graft (track «чиновники», stages 3–4): the dacha's growth, heaven's punishments, the black ledger, «особенно наглые». */
class GraftTest {
    @Test void theDachaGrowsWithDaysAndEnvelopes() {
        Graft.Estate e = new Graft.Estate();
        Graft.days(e, 10);
        assertEquals(0, e.points(), "the first day only starts the count");
        Graft.days(e, 11); Graft.days(e, 11);
        assertEquals(1, e.points(), "a day is counted once");
        Graft.days(e, 30);
        assertEquals(4, e.points(), "a long absence catches up three days at most");
        assertEquals(Graft.Stage.FENCE, Graft.stage(e.points(), 4));
        Graft.envelope(e); Graft.envelope(e);
        assertEquals(Graft.Stage.COTTAGE, Graft.stage(e.points(), 4));
        assertEquals(Graft.Stage.TOILET, Graft.stage(999, 4), "the golden toilet is the end");
        e.confiscated(true); Graft.envelope(e);
        assertEquals(8, e.points(), "a confiscated dacha grows no more");
    }

    @Test void heavenStrikes() {
        Graft.Estate e = new Graft.Estate();
        assertEquals(Graft.Smite.NONE, Graft.smite(e, 0, 1, 100, 0, 0), "no envelopes, no wrath");
        assertEquals(Graft.Smite.NONE, Graft.smite(e, 3, 1, 30, 0.5, 0), "the chance missed");
        assertEquals(Graft.Smite.TOADS, Graft.smite(e, 3, 1, 30, 0.1, 0), "no dacha yet: toads in the office");
        e.built(Graft.Stage.COTTAGE.ordinal());
        assertEquals(Graft.Smite.LIGHTNING, Graft.smite(e, 3, 1, 30, 0.1, 0.5));
        e.built(Graft.Stage.TOILET.ordinal());
        assertEquals(Graft.Smite.DUNG, Graft.smite(e, 3, 1, 30, 0.1, 0.1));
        e.dung(true);
        assertNotEquals(Graft.Smite.DUNG, Graft.smite(e, 3, 1, 30, 0.1, 0.1), "dung only once");
        e.smitten(1);
        assertEquals(Graft.Smite.NONE, Graft.smite(e, 3, 1, 100, 0, 0), "once a day");
    }

    @Test void theBlackLedger() {
        assertEquals(Graft.Ledger.BLACKMAIL, Graft.ledger(true, false, true, false));
        assertEquals(Graft.Ledger.REVIZOR, Graft.ledger(true, true, false, false));
        assertEquals(Graft.Ledger.ALREADY, Graft.ledger(true, true, false, true));
        assertEquals(Graft.Ledger.RUMOR, Graft.ledger(false, false, false, false));
        assertEquals(Graft.Ledger.RUMOR, Graft.ledger(true, false, false, false), "a corrupt clerk only gossips");
        assertEquals(28, Graft.hushMoney(3)); assertEquals(64, Graft.hushMoney(100));
        assertTrue(Graft.rumor(Graft.Stage.TOILET, "Пётр").contains("золотой унитаз"));
    }

    @Test void shamelessOfficialsTakeBeforeWitnessesAndAskMore() {
        UUID cautious = null;
        for (long i = 1; i < 100_000 && cautious == null; i++) { UUID id = new UUID(i * 7919, i * 104729); if (!Officialdom.honest(id, 20) && !Officialdom.brazen(id, 20)) cautious = id; }
        int price = Officialdom.price(Officialdom.Rank.HEAD, cautious, 0, 20, 100, 0);
        assertEquals(Officialdom.Outcome.NOT_HERE, Officialdom.judge(Officialdom.Rank.HEAD, cautious, 99, 3, 0, 20, 100, 0).outcome());
        assertEquals(Officialdom.Outcome.TAKEN, Officialdom.judge(Officialdom.Rank.HEAD, cautious, 99, 3, 0, 20, 100, 0, true).outcome());
        assertTrue(Officialdom.judge(Officialdom.Rank.HEAD, cautious, 99, 0, 0, 20, 100, 0, true).price() > price);
        assertTrue(Officialdom.prompt(Officialdom.Rank.HEAD, cautious, 0, 20, 100, null, true).contains("особенно наглый"));
    }

    @Test void estatesSurviveTheWorldMemory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try {
            ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
            UUID head = UUID.randomUUID();
            store.estate(head, "Пётр", "v");
            store.estateUpdate(head, e -> { e.place(10, 64, -20, 2); e.points(9); e.built(2); });
            store.revizorSchedule("v", 5);
            ConversationStore.closeWorld(directory);
            ConversationStore reopened = ConversationStore.open(directory, new MobTalkConfig());
            Graft.Estate e = reopened.estate(head, null, null);
            assertEquals(64, e.y()); assertEquals(2, e.facing()); assertEquals(2, e.built()); assertEquals("Пётр", e.headName());
            assertEquals(5, reopened.revizorDay("v"));
            reopened.revizorSchedule("v", -1);
            assertEquals(-1, reopened.revizorDay("v"));
        } finally { ConversationStore.closeWorld(directory); }
    }
}
