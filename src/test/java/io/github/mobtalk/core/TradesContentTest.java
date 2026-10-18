package io.github.mobtalk.core;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Sanity checks of the bundled trades.json content: every species trades, ids look vanilla, references resolve. */
class TradesContentTest {
    private static final Pattern ITEM_ID = Pattern.compile("minecraft:[a-z0-9_]+");

    // Every one of the 82 species must have an agenda of its own: a visible deal plus at least one quest.
    @Test void everySpeciesTradesWithVisibleDealsAndQuests() {
        List<String> species = TradeCatalog.tradingSpecies();
        assertEquals(82, species.size());
        assertEquals(SpeciesCatalog.all().stream().map(SpeciesCatalog.Species::id).toList(), species);
        for (String type : species) {
            List<TradeDeal> deals = TradeCatalog.deals(type);
            List<QuestDefinition> quests = TradeCatalog.quests(type);
            assertTrue(deals.size() >= 2 && deals.size() <= 4, type);
            assertTrue(quests.size() >= 1 && quests.size() <= 2, type);
            assertTrue(deals.stream().anyMatch(deal -> !deal.secret()), type + " has no visible deal");
            assertTrue(deals.stream().anyMatch(TradeDeal::secret), type + " has no secret deal");
            var pairs = new HashSet<String>();
            for (TradeDeal deal : deals) {
                assertTrue(ITEM_ID.matcher(deal.sell().id()).matches(), type + " " + deal.sell().id());
                assertTrue(ITEM_ID.matcher(deal.price().id()).matches(), type + " " + deal.price().id());
                assertTrue(deal.sell().count() >= 1 && deal.price().count() >= 1, type);
                assertFalse(deal.name().isBlank(), type);
                assertFalse(deal.sell().name().isBlank(), type);
                assertFalse(deal.price().name().isBlank(), type);
                assertTrue(pairs.add(deal.sell().id() + "->" + deal.price().id()), type + " repeats " + deal.id());
                if (deal.secret()) {
                    assertFalse(deal.hint().isBlank(), type + " " + deal.id());
                    assertTrue(deal.unlockRelationship() > 0 || !deal.unlockQuest().isEmpty(), type + " " + deal.id());
                }
                if (!deal.unlockQuest().isEmpty()) assertNotNull(TradeCatalog.quest(type, deal.unlockQuest()), type + " " + deal.id());
                assertSame(deal, TradeCatalog.deal(type, deal.id()));
            }
            for (QuestDefinition quest : quests) {
                assertNotNull(quest.kind(), type);
                assertTrue(ITEM_ID.matcher(quest.target()).matches(), type + " " + quest.target());
                assertTrue(quest.count() >= 1 && quest.count() <= 32, type);
                assertFalse(quest.pitch().isBlank(), type);
                assertFalse(quest.title().isBlank(), type);
                assertTrue(quest.reward().relationship() > 0, type);
                assertTrue(!quest.reward().unlockDeal().isEmpty() || !quest.reward().discountDeal().isEmpty(), type + " " + quest.id());
                if (!quest.reward().unlockDeal().isEmpty()) assertNotNull(TradeCatalog.deal(type, quest.reward().unlockDeal()), type);
                if (!quest.reward().discountDeal().isEmpty()) {
                    assertNotNull(TradeCatalog.deal(type, quest.reward().discountDeal()), type);
                    assertTrue(quest.reward().discountPercent() >= 10 && quest.reward().discountPercent() <= 50, type);
                }
                assertSame(quest, TradeCatalog.quest(type, quest.id()));
            }
        }
        assertTrue(TradeCatalog.deals("example:custom_mob").isEmpty());
        assertNull(TradeCatalog.deal("cow", "no_such_deal"));
    }
}
