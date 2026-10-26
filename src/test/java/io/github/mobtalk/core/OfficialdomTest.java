package io.github.mobtalk.core;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Officials and bribes (track «чиновники»): honesty from the UUID, the price of a favour, what an envelope does, the inspector's orders. */
class OfficialdomTest {
    /** The first UUID (counting up from {@code seed}) for which {@code want} holds. */
    private static UUID find(long seed, java.util.function.Predicate<UUID> want) {
        for (long i = seed; i < seed + 100_000; i++) { UUID id = new UUID(i * 7919, i * 104729); if (want.test(id)) return id; }
        throw new AssertionError("no such UUID");
    }

    @Test void ranksComeFromTheProfession() {
        assertEquals(Officialdom.Rank.HEAD, Officialdom.Rank.of("mobtalk:official_head"));
        assertEquals(Officialdom.Rank.CLERK, Officialdom.Rank.of("clerk"));
        assertEquals(Officialdom.Rank.INSPECTOR, Officialdom.Rank.of("mobtalk:inspector"));
        assertNull(Officialdom.Rank.of("minecraft:librarian"));
        assertNull(Officialdom.Rank.of(null));
    }

    @Test void aboutOneInFiveIsHonest() {
        int honest = 0;
        for (int i = 0; i < 5000; i++) if (Officialdom.honest(UUID.randomUUID(), 20)) honest++;
        assertTrue(honest > 800 && honest < 1200, "honest " + honest);
        UUID id = UUID.randomUUID();
        assertEquals(Officialdom.honesty(id), Officialdom.honesty(new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits())), "stable");
        assertFalse(Officialdom.honest(id, 0)); assertTrue(Officialdom.honest(id, 100));
    }

    @Test void greedRaisesThePriceAndFriendsPayLess() {
        UUID mild = find(1, id -> !Officialdom.honest(id, 20) && Officialdom.greed(id, 20) < 0.1);
        UUID greedy = find(1, id -> Officialdom.greed(id, 20) > 0.9);
        assertTrue(Officialdom.price(Officialdom.Rank.HEAD, greedy, 0, 20, 100, 0) > Officialdom.price(Officialdom.Rank.HEAD, mild, 0, 20, 100, 0));
        assertTrue(Officialdom.price(Officialdom.Rank.HEAD, mild, 80, 20, 100, 0) < Officialdom.price(Officialdom.Rank.HEAD, mild, 0, 20, 100, 0));
        assertTrue(Officialdom.price(Officialdom.Rank.HEAD, mild, -50, 20, 100, 0) > Officialdom.price(Officialdom.Rank.HEAD, mild, 0, 20, 100, 0));
        assertEquals(24, Officialdom.price(Officialdom.Rank.HEAD, find(1, id -> !Officialdom.honest(id, 20) && Officialdom.greed(id, 20) < 0.04), 0, 20, 200, 0));
        assertEquals(4, Officialdom.price(Officialdom.Rank.INSPECTOR, mild, 0, 20, 100, 8), "a corrupt inspector drops an order for half the fine");
        UUID honest = find(1, id -> Officialdom.honest(id, 20));
        assertEquals(8, Officialdom.price(Officialdom.Rank.INSPECTOR, honest, 0, 20, 100, 8), "an honest one wants the whole fine");
    }

    @Test void whatAnEnvelopeDoes() {
        UUID mild = find(1, id -> !Officialdom.honest(id, 20) && Officialdom.greed(id, 20) < 0.3);
        UUID brazen = find(1, id -> Officialdom.greed(id, 20) >= 0.6 && Officialdom.greed(id, 20) < 0.85);
        UUID shameless = find(1, id -> Officialdom.greed(id, 20) >= 0.85);
        UUID honest = find(1, id -> Officialdom.honest(id, 20) && Officialdom.honesty(id) < 97);
        UUID saint = find(1, id -> Officialdom.honesty(id) >= 97);
        int price = Officialdom.price(Officialdom.Rank.HEAD, mild, 0, 20, 100, 0);
        Officialdom.Verdict taken = Officialdom.judge(Officialdom.Rank.HEAD, mild, price, 0, 0, 20, 100, 0);
        assertEquals(Officialdom.Outcome.TAKEN, taken.outcome()); assertTrue(taken.keeps()); assertTrue(taken.relationshipDelta() > 0);
        assertTrue(taken.cue().contains("закрываешь вопрос"));
        assertEquals(Officialdom.Outcome.TOO_LITTLE, Officialdom.judge(Officialdom.Rank.HEAD, mild, price - 1, 0, 0, 20, 100, 0).outcome());
        assertFalse(Officialdom.judge(Officialdom.Rank.HEAD, mild, price - 1, 0, 0, 20, 100, 0).keeps());
        assertEquals(Officialdom.Outcome.KEPT, Officialdom.judge(Officialdom.Rank.HEAD, shameless, 1, 0, 0, 20, 100, 0).outcome());
        assertEquals(Officialdom.Outcome.NOT_HERE, Officialdom.judge(Officialdom.Rank.HEAD, mild, 99, 2, 0, 20, 100, 0).outcome(), "a cautious one, witnesses");
        assertEquals(Officialdom.Outcome.TAKEN, Officialdom.judge(Officialdom.Rank.HEAD, brazen, 99, 2, 0, 20, 100, 0).outcome(), "a brazen one does not care");
        assertEquals(Officialdom.Outcome.EMPTY, Officialdom.judge(Officialdom.Rank.CLERK, mild, 0, 0, 0, 20, 100, 0).outcome());
        assertEquals(Officialdom.Outcome.REFUSED, Officialdom.judge(Officialdom.Rank.CLERK, honest, 50, 0, 0, 20, 100, 0).outcome());
        assertTrue(Officialdom.judge(Officialdom.Rank.CLERK, honest, 50, 0, 0, 20, 100, 0).relationshipDelta() < 0);
        assertEquals(Officialdom.Outcome.REPORTED, Officialdom.judge(Officialdom.Rank.CLERK, saint, 50, 0, 0, 20, 100, 0).outcome());
        assertEquals(Officialdom.Outcome.PAID, Officialdom.judge(Officialdom.Rank.INSPECTOR, honest, 8, 3, 0, 20, 100, 8).outcome(), "paying a fine is no bribe");
        assertEquals(Officialdom.Outcome.REFUSED, Officialdom.judge(Officialdom.Rank.INSPECTOR, honest, 4, 0, 0, 20, 100, 8).outcome());
        assertEquals(Officialdom.Outcome.TAKEN, Officialdom.judge(Officialdom.Rank.INSPECTOR, mild, 4, 0, 0, 20, 100, 8).outcome());
    }

    @Test void everyOfficialHasTwoOwnBureaucraticHabits() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            UUID id = UUID.randomUUID();
            java.util.List<String> q = Officialdom.quirks(id);
            assertEquals(2, q.size()); assertNotEquals(q.get(0), q.get(1)); assertEquals(q, Officialdom.quirks(id), "the same official keeps its habits");
            seen.addAll(q);
            String text = Officialdom.prompt(Officialdom.Rank.CLERK, id, 0, 20, 100, null);
            assertTrue(text.contains(q.get(0)) && text.contains(q.get(1)) && text.contains("по существу"));
        }
        assertEquals(Officialdom.QUIRKS.size(), seen.size(), "every habit turns up");
    }

    @Test void thePromptHintsAndStaysSatire() {
        UUID corrupt = find(1, id -> !Officialdom.honest(id, 20));
        UUID honest = find(1, id -> Officialdom.honest(id, 20));
        String text = Officialdom.prompt(Officialdom.Rank.HEAD, corrupt, 0, 20, 100, null);
        assertTrue(text.contains("глава администрации")); assertTrue(text.contains("намёки")); assertTrue(text.contains("сатира"));
        assertFalse(Officialdom.prompt(Officialdom.Rank.CLERK, honest, 0, 20, 100, null).contains("Цена вопроса"));
        Officialdom ledger = new Officialdom();
        Officialdom.Fine fine = ledger.issue("v", UUID.randomUUID(), "Дима", UUID.randomUUID(), "Пётр", "самовольная постройка без разрешения", 8, 10, 1);
        assertTrue(Officialdom.prompt(Officialdom.Rank.INSPECTOR, corrupt, 0, 20, 100, Officialdom.fineOf(fine.data())).contains("№" + fine.number()));
        assertEquals("", Officialdom.prompt(null, corrupt, 0, 20, 100, null));
    }

    @Test void villagersGrumbleAboutOfficialsAndHintAtBribes() {
        assertEquals("", Officialdom.gossip(null));
        assertEquals("", Officialdom.gossip(java.util.List.of()));
        String text = Officialdom.gossip(java.util.List.of(
                Map.of("rank", "HEAD", "name", "Пётр", "honest", false, "taken", 4, "dacha", 5, "dung", true),
                Map.of("rank", "CLERK", "name", "Ольга", "honest", true, "taken", 0),
                Map.of("rank", "INSPECTOR", "name", "Иван", "honest", false, "taken", 0),
                Map.of("rank", "REMOVED", "name", "Семён", "dacha", 3)));
        assertTrue(text.contains("недолюбливаешь")); assertTrue(text.contains("мне тут ещё жить"));
        assertTrue(text.contains("Глава администрации Пётр")); assertTrue(text.contains("и не раз")); assertTrue(text.contains("золотой унитаз"));
        assertTrue(text.contains("Бог всё видит")); assertTrue(text.contains("Ольга: говорят, честный"));
        assertTrue(text.contains("Иван: толком ничего не известно")); assertTrue(text.contains("Семён снял ревизор"));
        assertEquals("", Officialdom.gossip(java.util.List.of(Map.of("rank", "NOBODY", "name", "x"))));
    }

    @Test void staleProceduresAreRecognised() {
        assertTrue(Officialdom.STALE.matcher("Сверка записи ещё не завершена, приходите позже.").find());
        assertTrue(Officialdom.STALE.matcher("Направлен запрос в архив.").find());
        assertFalse(Officialdom.STALE.matcher("Снег сверкает на солнце.").find());
        assertFalse(Officialdom.STALE.matcher("Ваше свидетельство о браке готово.").find());
    }

    @Test void officialsSpeakRealOfficialese() {
        String text = Officialdom.prompt(Officialdom.Rank.CLERK, UUID.randomUUID(), 0, 20, 100, null);
        assertTrue(text.contains("факты не подтвердились")); assertTrue(text.contains("направлено по принадлежности"));
    }

    @Test void ordersDoubleThenEscalate() {
        Officialdom ledger = new Officialdom();
        UUID player = UUID.randomUUID();
        assertTrue(ledger.mayFine("v", player, 5));
        Officialdom.Fine fine = ledger.issue("v", player, "Дима", null, "Пётр", Officialdom.reason(false, 0.3), 8, 5, 1);
        assertEquals(6, fine.dueDay()); assertFalse(ledger.mayFine("v", player, 5));
        assertTrue(ledger.overdue(6, 1).isEmpty(), "due day itself is fine");
        Map<Officialdom.Fine, Officialdom.Overdue> first = ledger.overdue(7, 1);
        assertEquals(Officialdom.Overdue.DOUBLED, first.values().iterator().next());
        assertEquals(16, ledger.fine("v", player).amount()); assertEquals(8, ledger.fine("v", player).dueDay());
        assertEquals(Officialdom.Overdue.ESCALATED, ledger.overdue(9, 1).values().iterator().next());
        assertNull(ledger.fine("v", player));
        ledger.favour("v", player, 9, 3);
        assertFalse(ledger.mayFine("v", player, 12)); assertTrue(ledger.mayFine("v", player, 13));
        Officialdom copy = ledger.copy(); copy.sanitize();
        assertEquals(ledger.open().size(), copy.open().size());
    }

    @Test void ordersSurviveTheWorldMemory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try {
            ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
            UUID player = UUID.randomUUID();
            Officialdom.Fine fine = store.officialIssue("v", player, "Дима", UUID.randomUUID(), "Пётр", "нарушение красной линии застройки", 8, 3, 1);
            assertEquals(fine.number(), store.officialFine("v", player).number());
            store.officialTook(UUID.randomUUID());
            ConversationStore.closeWorld(directory);
            ConversationStore reopened = ConversationStore.open(directory, new MobTalkConfig());
            assertEquals(fine.number(), reopened.officialFine("v", player).number());
            reopened.officialClose("v", player);
            assertNull(reopened.officialFine("v", player));
        } finally { ConversationStore.closeWorld(directory); }
    }
}
