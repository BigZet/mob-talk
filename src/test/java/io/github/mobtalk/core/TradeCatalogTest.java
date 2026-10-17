package io.github.mobtalk.core;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeCatalogTest {
    @AfterEach void restoreBundle() { TradeFixtures.reset(); }

    private static String species(String body) { return "{\"species\":{\"minecraft:cow\":" + body + "}}"; }
    private static String deal(String id, String extra) {
        return "{\"id\":\"" + id + "\",\"name\":\"Сделка\",\"sell\":{\"id\":\"minecraft:leather\",\"name\":\"Кожа\",\"count\":1},"
                + "\"price\":{\"id\":\"minecraft:wheat\",\"name\":\"Пшеница\",\"count\":6}" + extra + "}";
    }
    private static String quest(String id, String kind, String target, String extra) {
        return "{\"id\":\"" + id + "\",\"title\":\"Задание\",\"kind\":\"" + kind + "\",\"target\":\"" + target + "\",\"count\":2" + extra + "}";
    }
    private static String failure(String json) { return assertThrows(IllegalStateException.class, () -> TradeCatalog.parse(json)).getMessage(); }

    // A parsed file answers by canonical species id, keeps file order, and applies the record defaults.
    @Test void parsesFixtureWithDefaultsAndNormalizedSpeciesIds() {
        TradeFixtures.install();
        assertEquals(List.of("minecraft:cow", "minecraft:pig"), TradeCatalog.tradingSpecies());
        assertEquals(List.of("milk", "hay", "bulk", "leather"), TradeCatalog.deals("cow").stream().map(TradeDeal::id).toList());
        TradeDeal milk = TradeCatalog.deal("minecraft:cow", "milk");
        assertFalse(milk.secret()); assertEquals("", milk.hint()); assertEquals("", milk.unlockQuest()); assertEquals(0, milk.maxPerDay());
        assertEquals(6, milk.price().count()); assertEquals(1, milk.floorPrice());
        TradeDeal leather = TradeCatalog.deal("COW", "leather");
        assertTrue(leather.secret()); assertEquals(40, leather.unlockRelationship()); assertEquals("fence", leather.unlockQuest()); assertEquals(1, leather.maxPerDay());
        assertEquals(6, TradeCatalog.deal("cow", "bulk").floorPrice());
        QuestDefinition wolves = TradeCatalog.quest("minecraft:cow", "wolves");
        assertEquals(QuestDefinition.Kind.KILL, wolves.kind()); assertEquals("minecraft:wolf", wolves.target());
        assertEquals(new QuestDefinition.Reward(10, "", "", 0), wolves.reward());
        assertEquals(new QuestDefinition.Reward(30, "leather", "milk", 25), TradeCatalog.quest("cow", "fence").reward());
        assertEquals(List.of("truffle"), TradeCatalog.deals("minecraft:pig").stream().map(TradeDeal::id).toList());
        assertTrue(TradeCatalog.quests("pig").isEmpty());
        assertNull(TradeCatalog.deal("cow", "nothing")); assertNull(TradeCatalog.quest("cow", null));
        assertTrue(TradeCatalog.deals("minecraft:sheep").isEmpty());
    }

    // Each cross-reference and id rule fails loudly with the species and offending id in the message.
    @Test void rejectsBrokenReferencesDuplicatesAndBadIds() {
        assertTrue(failure(species("{\"deals\":[" + deal("a", "") + "," + deal("a", "") + "]}")).contains("duplicate deal a"));
        assertTrue(failure(species("{\"deals\":[" + deal("a", ",\"unlockQuest\":\"ghost\"") + "]}")).contains("unknown unlockQuest of a"));
        assertTrue(failure(species("{\"quests\":[" + quest("q", "BRING", "minecraft:stick", ",\"reward\":{\"unlockDeal\":\"ghost\"}") + "]}")).contains("unknown unlockDeal of q"));
        assertTrue(failure(species("{\"quests\":[" + quest("q", "BRING", "minecraft:stick", ",\"reward\":{\"discountDeal\":\"ghost\"}") + "]}")).contains("unknown discountDeal of q"));
        assertTrue(failure(species("{\"quests\":[" + quest("q", "BRING", "minecraft:stick", "") + "," + quest("q", "KILL", "minecraft:wolf", "") + "]}")).contains("duplicate quest q"));
        assertTrue(failure(species("{\"quests\":[" + quest("q", "FETCH", "minecraft:stick", "") + "]}")).contains("incomplete quest q"));
        assertTrue(failure(species("{\"quests\":[" + quest("q", "BRING", "Stick", "") + "]}")).contains("bad item id Stick"));
        assertTrue(failure(species("{\"deals\":[{\"id\":\"a\",\"name\":\"x\",\"price\":{\"id\":\"minecraft:wheat\",\"name\":\"w\"}}]}")).contains("missing item"));
        assertTrue(failure(species("{\"deals\":[{\"id\":\"\",\"name\":\"x\",\"sell\":{\"id\":\"minecraft:a\"},\"price\":{\"id\":\"minecraft:b\"}}]}")).contains("incomplete deal"));
        assertTrue(failure("{\"species\":{\"minecraft:cow\":{\"deals\":[null]}}}").contains("empty deal"));
        assertTrue(failure("").contains("empty trades.json"));
        assertTrue(failure("{not json").contains("cannot read"));
        assertTrue(TradeCatalog.parse("{\"species\":{\"minecraft:cow\":null}}").deals().isEmpty());
    }

    // The bundled placeholder (filled by the content branch) must answer empty, never throw.
    @Test void bundledFileAnswersEmptyForEverySpecies() {
        TradeFixtures.reset();
        assertTrue(TradeCatalog.deals("minecraft:cow").isEmpty());
        assertTrue(TradeCatalog.quests("cow").isEmpty());
        assertNull(TradeCatalog.deal("minecraft:cow", "milk"));
        assertNull(TradeCatalog.quest("minecraft:cow", "fence"));
        assertEquals(List.of(), TradeCatalog.tradingSpecies());
        assertEquals(java.util.Map.of(), Trading.view("minecraft:cow", java.util.Map.of(), java.util.Map.of(), 0, 0, 0, java.util.Map.of(), java.util.Map.of()));
        assertEquals(List.of(), Trading.priced("minecraft:cow", java.util.Map.of(), 0));
    }
}
