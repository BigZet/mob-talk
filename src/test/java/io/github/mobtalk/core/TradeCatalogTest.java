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
    private static String failure(String json) { return assertThrows(IllegalStateException.class, () -> TradeCatalog.parse(json)).getMessage(); }

    // A parsed file answers by canonical species id, keeps file order, and applies the record defaults.
    @Test void parsesFixtureWithDefaultsAndNormalizedSpeciesIds() {
        TradeFixtures.install();
        assertEquals(List.of("minecraft:cow", "minecraft:pig"), TradeCatalog.tradingSpecies());
        assertEquals(List.of("milk", "hay", "bulk", "leather"), TradeCatalog.deals("cow").stream().map(TradeDeal::id).toList());
        TradeDeal milk = TradeCatalog.deal("minecraft:cow", "milk");
        assertFalse(milk.secret()); assertEquals("", milk.hint()); assertFalse(milk.requiresFavor()); assertEquals(0, milk.maxPerDay());
        assertEquals(6, milk.price().count()); assertEquals(1, milk.floorPrice());
        TradeDeal leather = TradeCatalog.deal("COW", "leather");
        assertTrue(leather.secret()); assertEquals(40, leather.unlockRelationship()); assertTrue(leather.requiresFavor()); assertEquals(1, leather.maxPerDay());
        assertEquals(6, TradeCatalog.deal("cow", "bulk").floorPrice());
        assertEquals(List.of(new TradeCatalog.Idea("minecraft:oak_fence", 8, "Крипер снёс половину загона"), new TradeCatalog.Idea("minecraft:bell", 1, "")),
                TradeCatalog.ideas("cow"));
        assertEquals(List.of("truffle"), TradeCatalog.deals("minecraft:pig").stream().map(TradeDeal::id).toList());
        assertTrue(TradeCatalog.ideas("pig").isEmpty());
        assertNull(TradeCatalog.deal("cow", "nothing"));
        assertTrue(TradeCatalog.deals("minecraft:sheep").isEmpty());
    }

    // Each cross-reference and id rule fails loudly with the species and offending id in the message.
    @Test void rejectsBrokenReferencesDuplicatesAndBadIds() {
        assertTrue(failure(species("{\"deals\":[" + deal("a", "") + "," + deal("a", "") + "]}")).contains("duplicate deal a"));
        // A 0.3.0 file named a catalog quest; that now simply means "after a favour".
        assertTrue(TradeCatalog.parse(species("{\"deals\":[" + deal("a", ",\"secret\":true,\"unlockQuest\":\"ghost\"") + "]}")).deals().get("minecraft:cow").get(0).requiresFavor());
        assertTrue(failure(species("{\"ideas\":[{\"item\":\"Stick\",\"count\":1}]}")).contains("bad item id Stick"));
        assertTrue(failure(species("{\"ideas\":[{\"item\":\"minecraft:stick\",\"count\":0}]}")).contains("bad idea"));
        assertTrue(failure(species("{\"ideas\":[{\"item\":\"minecraft:stick\",\"count\":65}]}")).contains("bad idea"));
        assertTrue(failure(species("{\"deals\":[{\"id\":\"a\",\"name\":\"x\",\"price\":{\"id\":\"minecraft:wheat\",\"name\":\"w\"}}]}")).contains("missing item"));
        assertTrue(failure(species("{\"deals\":[{\"id\":\"\",\"name\":\"x\",\"sell\":{\"id\":\"minecraft:a\"},\"price\":{\"id\":\"minecraft:b\"}}]}")).contains("incomplete deal"));
        assertTrue(failure("{\"species\":{\"minecraft:cow\":{\"deals\":[null]}}}").contains("empty deal"));
        assertTrue(failure("").contains("empty trades.json"));
        assertTrue(failure("{not json").contains("cannot read"));
        assertTrue(TradeCatalog.parse("{\"species\":{\"minecraft:cow\":null}}").deals().isEmpty());
    }

    // The bundled placeholder (filled by the content branch) must answer empty, never throw.
    // The bundled file covers every vanilla species; a modded species the file does not know trades nothing and never throws.
    @Test void bundledFileCoversVanillaAndAnswersEmptyForUnknownSpecies() {
        TradeFixtures.reset();
        assertEquals(82, TradeCatalog.tradingSpecies().size());
        assertFalse(TradeCatalog.deals("minecraft:cow").isEmpty());
        assertFalse(TradeCatalog.ideas("cow").isEmpty());
        String unknown = "somemod:gremlin";
        assertTrue(TradeCatalog.deals(unknown).isEmpty());
        assertNull(TradeCatalog.deal(unknown, "milk"));
        assertEquals(java.util.Map.of(), Trading.view(unknown, java.util.Map.of(), null, List.of(), 0, 0, 0, java.util.Map.of(), java.util.Map.of()));
        assertEquals(List.of(), Trading.priced(unknown, java.util.Map.of(), 0));
    }
}
