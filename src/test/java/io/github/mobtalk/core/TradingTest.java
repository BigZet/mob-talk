package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingTest {
    private static final String COW = "minecraft:cow";
    private TradeDeal milk, hay, bulk, leather;

    @BeforeEach void fixture() {
        TradeFixtures.install();
        milk = TradeCatalog.deal(COW, "milk"); hay = TradeCatalog.deal(COW, "hay"); bulk = TradeCatalog.deal(COW, "bulk"); leather = TradeCatalog.deal(COW, "leather");
    }
    @AfterEach void restoreBundle() { TradeFixtures.reset(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deal(Map<String, Object> view, String id) {
        return ((List<Map<String, Object>>) view.get("deals")).stream().filter(d -> id.equals(d.get("id"))).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> secret(Map<String, Object> view, String id) {
        return ((List<Map<String, Object>>) view.get("secret")).stream().filter(d -> id.equals(d.get("id"))).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> favor(Map<String, Object> view) { return (Map<String, Object>) view.get("favor"); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> active(Map<String, Object> view) { return (Map<String, Object>) favor(view).get("active"); }
    private static Map<String, Object> view(Map<String, TradeState> states, List<String> done, int relationship, long now) {
        return Trading.view(COW, states, null, done, relationship, now, 0, Map.of(), Map.of());
    }
    private static Map<String, Object> view(Map<String, TradeState> states, Map<String, ?> ignored, int relationship, long now) {
        return view(states, List.of(), relationship, now);
    }

    // One OFFER halves at most, the floor is 10%, and a negotiated price is permanent: later rounds only go lower.
    @Test void halvesAtMostAndNeverBelowFloorAndNeverBackUp() {
        TradeState state = TradeState.initial();
        assertEquals(6, Trading.currentPrice(milk, state));
        assertEquals(3, Trading.maximumDiscount(milk, state, 0, 1000));
        assertEquals(List.of(3, 4, 5), deal(view(Map.of(), Map.of(), 0, 1000), "milk").get("allowedPrices"));
        assertTrue(Trading.permitted(milk, state, 0, 3, 1000));
        assertTrue(Trading.permitted(milk, state, 0, 5, 1000));
        assertFalse(Trading.permitted(milk, state, 0, 2, 1000));
        assertFalse(Trading.permitted(milk, state, 0, 6, 1000));
        assertFalse(Trading.permitted(milk, state, 0, 0, 1000));
        state = Trading.offered(state, 3, 1000);
        assertEquals(new TradeState(3, false, 1, 1000, 0, -1), state);
        assertEquals(3, Trading.currentPrice(milk, state));
        assertEquals(2, Trading.maximumDiscount(milk, state, 0, 1001));
        assertEquals(List.of(1, 2), deal(view(Map.of("milk", state), Map.of(), 0, 1001), "milk").get("allowedPrices"));
        state = Trading.offered(state, 1, 1001);
        assertEquals(2, state.rounds());
        assertEquals(0, Trading.maximumDiscount(milk, state, 100, 1002));
        Map<String, Object> row = deal(view(Map.of("milk", state), Map.of(), 100, 1002), "milk");
        assertEquals(false, row.get("canOffer")); assertEquals("цена уже на минимуме", row.get("reason")); assertEquals(List.of(), row.get("allowedPrices"));
        assertEquals(6, Trading.currentPrice(milk, new TradeState(9, false, 0, -1, 0, -1)), "a stored price above catalog never raises the price");
        assertEquals(6, bulk.floorPrice(), "60 wheat: floor is 6");
        assertEquals(30, Trading.maximumDiscount(bulk, TradeState.initial(), 0, 0));
        TradeState low = Trading.offered(Trading.offered(Trading.offered(TradeState.initial(), 30, 0), 15, 1), 7, 2);
        assertEquals(1, Trading.maximumDiscount(bulk, low, 0, 3 + Trading.SESSION_MILLIS), "7 → 6 only: the floor, not the halving, limits the step");
        assertEquals(0, Trading.maximumDiscount(hay, TradeState.initial(), 100, 0), "a one-item price cannot drop");
        assertEquals("цена уже на минимуме", deal(view(Map.of(), Map.of(), 100, 0), "hay").get("reason"));
    }

    // Three OFFERs close the session for twenty minutes; then rounds start over from the permanent price.
    @Test void threeRoundsThenTwentyMinutesThenNewSession() {
        long start = 500_000;
        TradeState state = Trading.offered(TradeState.initial(), 59, start);
        state = Trading.offered(state, 58, start + 60_000);
        state = Trading.offered(state, 57, start + 120_000);
        assertEquals(new TradeState(57, false, 3, start, 0, -1), state);
        long later = start + 5 * 60_000;
        assertEquals(0, Trading.maximumDiscount(bulk, state, 100, later));
        assertFalse(Trading.permitted(bulk, state, 100, 56, later));
        Map<String, Object> row = deal(view(Map.of("bulk", state), Map.of(), 100, later), "bulk");
        assertEquals("три уступки подряд уже сделаны, следующий торг через 15 мин", row.get("reason"));
        assertEquals(false, row.get("canOffer")); assertEquals(57, row.get("currentPrice")); assertEquals(60, row.get("basePrice")); assertEquals(6, row.get("minPrice"));
        assertEquals(List.of("NONE", "OFFER", "DECLINE"), view(Map.of("bulk", state), Map.of(), 100, later).get("allowedDealTypes"), "milk is still negotiable");
        long reopened = start + Trading.SESSION_MILLIS;
        assertEquals(57 - 28, Trading.maximumDiscount(bulk, state, 100, reopened));
        assertTrue(Trading.permitted(bulk, state, 100, 40, reopened));
        TradeState fresh = Trading.offered(state, 40, reopened);
        assertEquals(new TradeState(40, false, 1, reopened, 0, -1), fresh);
    }

    // At -40 the mob stops conceding on every deal, so OFFER leaves allowedDealTypes.
    @Test void hostilePlayersGetNoConcession() {
        TradeState negotiated = Trading.offered(TradeState.initial(), 4, 0);
        Map<String, Object> hostile = view(Map.of("milk", negotiated), Map.of(), Trading.HOSTILE_RELATIONSHIP, 10);
        assertEquals(List.of("NONE", "DECLINE"), hostile.get("allowedDealTypes"));
        Map<String, Object> row = deal(hostile, "milk");
        assertEquals("торг с этим игроком закрыт: отношение слишком плохое", row.get("reason"));
        assertEquals(0, row.get("maxDiscountNow")); assertEquals(4, row.get("currentPrice"), "the earlier concession stays");
        assertFalse(Trading.permitted(milk, negotiated, Trading.HOSTILE_RELATIONSHIP, 3, 10));
        assertEquals(List.of("NONE", "OFFER", "DECLINE"), view(Map.of("milk", negotiated), Map.of(), Trading.HOSTILE_RELATIONSHIP + 1, 10).get("allowedDealTypes"));
    }

    // A secret deal stays a hint until relationship and a favour allow the mob to open it; then it trades like any other.
    @Test void secretDealsHideUntilUnlockedByRelationshipAndFavour() {
        Map<String, Object> locked = view(Map.of(), List.of(), 10, 0);
        assertEquals(List.of("milk", "hay", "bulk"), ((List<?>) locked.get("deals")).stream().map(d -> ((Map<?, ?>) d).get("id")).toList());
        Map<String, Object> row = secret(locked, "leather");
        assertEquals("Знает, где лежит кожа пропавшей подруги", row.get("hint"));
        assertEquals(40, row.get("unlockRelationship")); assertEquals(true, row.get("requiresFavor")); assertEquals(false, row.get("canUnlockNow"));
        assertEquals("нужно отношение не ниже 40 (сейчас 10); сначала игрок должен оказать тебе хоть одну услугу", row.get("reason"));
        assertFalse(Trading.canUnlock(leather, null, 100, 0));
        assertFalse(Trading.canUnlock(leather, TradeState.initial(), 39, 1));
        assertTrue(Trading.canUnlock(leather, TradeState.initial(), 40, 1));
        List<String> done = List.of("Колокол на площадь");
        assertEquals("", secret(view(Map.of(), done, 40, 0), "leather").get("reason"));
        assertEquals(true, secret(view(Map.of(), done, 40, 0), "leather").get("canUnlockNow"));
        assertFalse(Trading.canUnlock(milk, TradeState.initial(), 100, 1), "a public deal is never unlocked");
        TradeState opened = new TradeState(0, true, 0, -1, 0, -1);
        assertFalse(Trading.canUnlock(leather, opened, 100, 1), "already open");
        Map<String, Object> unlocked = view(Map.of("leather", opened), done, 40, 0);
        assertTrue(((List<?>) unlocked.get("secret")).isEmpty());
        assertEquals(List.of(1), deal(unlocked, "leather").get("allowedPrices"));
        assertEquals(1, deal(unlocked, "leather").get("stockLeft"));
        List<Trading.Priced> priced = Trading.priced(COW, Map.of("leather", opened, "milk", Trading.offered(TradeState.initial(), 3, 0)), 0);
        assertEquals(List.of("milk", "hay", "bulk", "leather"), priced.stream().map(p -> p.deal().id()).toList());
        assertEquals(new Trading.Priced(milk, 3, -1), priced.get(0));
        assertEquals(new Trading.Priced(leather, 2, 1), priced.get(3));
        assertEquals(3, Trading.priced(COW, Map.of(), 0).size());
        // Pig: no favour needed, only relationship.
        assertEquals(true, secret(Trading.view("pig", Map.of(), null, List.of(), 20, 0, 0, Map.of(), Map.of()), "truffle").get("canUnlockNow"));
        assertEquals("нужно отношение не ниже 20 (сейчас 19)", secret(Trading.view("pig", Map.of(), null, List.of(), 19, 0, 0, Map.of(), Map.of()), "truffle").get("reason"));
        assertEquals(List.of("NONE"), Trading.view("pig", Map.of(), null, List.of(), 19, 0, 0, Map.of(), Map.of()).get("allowedDealTypes"), "nothing visible to decline");
    }

    // Daily stock counts purchases per world day and resets with the day, without touching the price.
    @Test void dailyStockResetsWithTheWorldDay() {
        TradeState state = Trading.offered(new TradeState(0, true, 0, -1, 0, -1), 1, 0);
        assertEquals(1, Trading.stockLeft(leather, state, 5));
        state = Trading.bought(state, 5);
        assertEquals(new TradeState(1, true, 1, 0, 1, 5), state);
        assertEquals(0, Trading.stockLeft(leather, state, 5));
        assertEquals(1, Trading.stockLeft(leather, state, 6));
        state = Trading.bought(state, 6);
        assertEquals(1, state.boughtToday()); assertEquals(6, state.boughtDay());
        assertEquals(-1, Trading.stockLeft(milk, Trading.bought(Trading.bought(null, 1), 1), 1));
        assertEquals(-1, deal(view(Map.of(), Map.of(), 0, 0), "milk").get("stockLeft"));
    }

    // The favour view: ideas while nothing is owed, the pending favour with what the player carries, titles already done.
    @Test void favourViewFollowsTheInventory() {
        Map<String, String> names = Map.of("minecraft:oak_fence", "Дубовый забор");
        Map<String, Object> fresh = Trading.view(COW, Map.of(), null, List.of(), 0, 0, 0, Map.of(), names);
        assertEquals(true, favor(fresh).get("canAsk")); assertNull(active(fresh)); assertEquals(List.of(), favor(fresh).get("done"));
        List<?> ideas = (List<?>) favor(fresh).get("ideas");
        assertEquals(2, ideas.size());
        Map<?, ?> idea = (Map<?, ?>) ideas.get(0);
        assertEquals("minecraft:oak_fence", idea.get("item")); assertEquals("Дубовый забор", idea.get("itemName")); assertEquals(8, idea.get("count"));
        assertEquals("Крипер снёс половину загона", idea.get("why"));
        assertEquals("minecraft:bell", ((Map<?, ?>) ideas.get(1)).get("itemName"), "unknown names stay raw");
        Favor fence = new Favor("minecraft:oak_fence", 8, "Почини загон", "leather", 20, null, 0);
        Map<String, Object> carrying = Trading.view(COW, Map.of(), fence, List.of("Колокол"), 0, 0, 0, Map.of("minecraft:oak_fence", 5), names);
        assertEquals(false, favor(carrying).get("canAsk"), "one favour at a time");
        Map<String, Object> row = active(carrying);
        assertEquals("Почини загон", row.get("title")); assertEquals("Дубовый забор", row.get("itemName")); assertEquals(8, row.get("count"));
        assertEquals(5, row.get("have")); assertEquals(false, row.get("canComplete")); assertEquals("leather", row.get("rewardDeal")); assertEquals(20, row.get("rewardPercent"));
        assertEquals(List.of("Колокол"), favor(carrying).get("done"));
        assertFalse(Trading.canComplete(fence, Map.of("minecraft:oak_fence", 7)));
        assertTrue(Trading.canComplete(fence, Map.of("minecraft:oak_fence", 8)));
        assertFalse(Trading.canComplete(fence, Map.of()));
        assertFalse(Trading.canComplete(null, Map.of("minecraft:oak_fence", 80)), "nothing owed");
        Map<String, Object> enough = Trading.view(COW, Map.of(), fence, List.of(), 0, 0, 0, Map.of("minecraft:oak_fence", 12), names);
        assertEquals(8, active(enough).get("have")); assertEquals(true, active(enough).get("canComplete"));
    }

    // Species outside the catalog get an empty map, and a state for an unknown deal is ignored rather than failing.
    @Test void unknownSpeciesTradesNothing() {
        assertEquals(Map.of(), Trading.view("minecraft:sheep", Map.of("milk", TradeState.initial()), null, List.of(), 100, 0, 0, Map.of(), Map.of()));
        assertEquals(List.of(), Trading.priced("minecraft:sheep", Map.of(), 0));
        Map<String, Object> cow = Trading.view(COW, Map.of("ghost", Trading.offered(null, 1, 0)), null, null, 0, 0, 0, null, null);
        assertEquals(3, ((List<?>) cow.get("deals")).size());
        assertEquals(true, favor(cow).get("canAsk"));
    }
}
