package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRankingTest {
    private static final long DAY = 24 * 60 * 60 * 1000L;

    // Importance × freshness: a fresh small fact beats a stale one of the same weight, but a blow outlasts a month of small talk.
    @Test void scoresImportanceWithDecayAndAFloor() {
        long now = 100 * DAY;
        assertEquals(2.0, MemoryRanking.score(2, now, now), 1e-9);
        assertEquals(2 * (0.3 + 0.7 * 0.5), MemoryRanking.score(2, now - 7 * DAY, now), 1e-9);
        assertTrue(MemoryRanking.score(5, now - 30 * DAY, now) > MemoryRanking.score(1, now, now), "an old blow outranks fresh trivia");
        assertTrue(MemoryRanking.score(5, now - 365 * DAY, now) >= 5 * MemoryRanking.FLOOR - 1e-9, "freshness never drops below the floor");
        assertEquals(MemoryRanking.score(5, now, now), MemoryRanking.score(9, now, now), "importance is clamped to 5");
    }

    @Test void ranksImportantFirstAndEvictsLowAndOldFirst() {
        long now = 100 * DAY;
        List<MemoryFact> facts = new ArrayList<>(List.of(
                MemoryFact.of("старая мелочь", 1, now - 20 * DAY), MemoryFact.of("удар палкой", 5, now - 3 * DAY),
                MemoryFact.of("купил хлеб", 4, now - DAY), MemoryFact.of("болтали о погоде", 2, now)));
        assertEquals(List.of("удар палкой", "купил хлеб", "болтали о погоде", "старая мелочь"),
                MemoryRanking.ranked(facts, now).stream().map(MemoryFact::text).toList());
        MemoryRanking.remember(facts, MemoryFact.of("спросил дорогу", 2, now), 60, now);
        assertFalse(facts.stream().anyMatch(f -> f.text().equals("старая мелочь")), "the lowest score goes first");
        assertTrue(facts.stream().anyMatch(f -> f.text().equals("удар палкой")), "the blow stays");
        assertEquals("удар палкой\nкупил хлеб\nболтали о погоде\nспросил дорогу", MemoryRanking.render(facts), "the editable text keeps the order learned");
        MemoryRanking.remember(facts, MemoryFact.of("купил хлеб", 4, now), 4000, now);
        assertEquals(1, facts.stream().filter(f -> f.text().equals("купил хлеб")).count(), "a repeated fact is refreshed, not duplicated");
    }

    @Test void capsReflectionsSeparatelyFromEvents() {
        long now = 100 * DAY;
        List<MemoryFact> facts = new ArrayList<>();
        for (int i = 0; i < MemoryRanking.MAX_REFLECTIONS + 2; i++) facts.add(new MemoryFact("вывод " + i, MemoryFact.REFLECTION, now - i * DAY, true));
        for (int i = 0; i < 5; i++) facts.add(MemoryFact.of("событие " + i, 1, now - 300 * DAY));
        MemoryRanking.evictFacts(facts, 4000, now);
        assertEquals(MemoryRanking.MAX_REFLECTIONS, facts.stream().filter(MemoryFact::reflection).count());
        assertEquals(5, facts.stream().filter(f -> !f.reflection()).count(), "capping conclusions does not evict events");
        assertFalse(facts.stream().anyMatch(f -> f.text().equals("вывод " + (MemoryRanking.MAX_REFLECTIONS + 1))), "the oldest conclusion went");
    }

    // Saves made before importance existed hold one memory text: every line becomes a fact of importance 3.
    @Test void migratesPlainMemoryTextAndKeepsEditedLines() {
        long now = 100 * DAY;
        List<MemoryFact> migrated = MemoryRanking.parse("Игрок назвался Стивом.\n\nПринёс мне яблоко.\n[вывод] Он держит слово.", now);
        assertEquals(3, migrated.size());
        assertEquals(MemoryFact.MIGRATED, migrated.get(0).importance());
        assertFalse(migrated.get(0).reflection());
        assertTrue(migrated.get(2).reflection());
        assertEquals("Он держит слово.", migrated.get(2).text());
        assertEquals(MemoryFact.REFLECTION, migrated.get(2).importance());
        List<MemoryFact> known = List.of(MemoryFact.of("Игрок назвался Стивом.", 5, now - DAY), MemoryFact.of("Принёс мне яблоко.", 4, now - DAY));
        List<MemoryFact> edited = MemoryRanking.edited(known, "принёс мне яблоко.\nНовая строка", now);
        assertEquals(2, edited.size());
        assertEquals(4, edited.get(0).importance(), "a kept line keeps its importance");
        assertEquals(now - DAY, edited.get(0).time());
        assertEquals(MemoryFact.MIGRATED, edited.get(1).importance(), "a typed line is middling");
        assertFalse(edited.stream().anyMatch(f -> f.text().startsWith("Игрок назвался")), "a removed line is forgotten");
    }

    @Test void promptListsFactsByScoreAndConclusionsOnTheirOwnLine() {
        long now = 100 * DAY;
        List<MemoryFact> facts = List.of(MemoryFact.of("болтали о погоде", 2, now), MemoryFact.of("ударил меня", 5, now - 2 * DAY),
                new MemoryFact("он всегда платит", MemoryFact.REFLECTION, now, true));
        String block = MemoryRanking.promptBlock(facts, "", now);
        assertTrue(block.indexOf("ударил меня") < block.indexOf("болтали о погоде"), block);
        assertTrue(block.contains("\nЧто я понял об этом игроке: он всегда платит"), block);
        assertFalse(block.substring(0, block.indexOf("Что я понял")).contains("он всегда платит"), "a conclusion is not listed among the events");
        assertEquals("\nПамять о нём (важное первым): пока ничего.", MemoryRanking.promptBlock(List.of(), "", now));
        assertTrue(MemoryRanking.promptBlock(List.of(), "старая память", now).contains("— старая память"), "a hand-built profile's text still shows");
        String summaries = ConversationSummary.promptBlock(List.of(new ConversationSummary.Summary(1, "Говорили о пшенице.", 3), new ConversationSummary.Summary(2, "Он обещал принести семена.", 2)));
        assertTrue(summaries.contains("— Говорили о пшенице.\n— (последний разговор) Он обещал принести семена."), summaries);
        assertEquals("", ConversationSummary.promptBlock(List.of()));
    }

    @Test void memoryCharacterLimitStillHolds() {
        long now = 100 * DAY;
        List<MemoryFact> facts = new ArrayList<>();
        for (int i = 0; i < 30; i++) MemoryRanking.remember(facts, MemoryFact.of("факт номер " + i + " " + "x".repeat(20), 2, now + i), 200, now);
        assertTrue(MemoryRanking.render(facts).length() <= 200);
        assertTrue(facts.size() >= 5);
        assertEquals("факт номер 29 " + "x".repeat(20), facts.getLast().text(), "the newest fact survives");
        List<MemoryFact> single = new ArrayList<>(List.of(MemoryFact.of("п".repeat(500), 2, now)));
        MemoryRanking.evictFacts(single, 200, now);
        assertEquals(200, single.getFirst().text().length(), "one oversized fact is trimmed rather than dropped");
    }
}
