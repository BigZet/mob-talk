package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpokenOfferTest {
    private static Bargaining.Offer row(int index, int vanilla) { return new Bargaining.Offer(index, "row-" + index, vanilla, vanilla, 64, true); }

    // A promise may at most halve the current price, never go under the floor, and only ever lower itself; another row replaces it.
    @Test void promisesHalveAtMostAndOnlyGoDown() {
        Bargaining.Offer carrots = row(0, 22);
        assertEquals(List.of(21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11), SpokenOffer.allowedPrices(null, carrots, 0, 22, 100));
        assertNull(SpokenOffer.promise(null, carrots, 0, 22, 10, 100), "below half");
        assertNull(SpokenOffer.promise(null, carrots, 0, 22, 22, 100), "not a concession");
        SpokenOffer first = SpokenOffer.promise(null, carrots, 0, 22, 15, 100);
        assertNotNull(first);
        assertEquals(1, first.rounds()); assertEquals(100 + SpokenOffer.LIFETIME_TICKS, first.expiresTick());
        assertTrue(first.active(100) && first.active(first.expiresTick() - 1) && first.expired(first.expiresTick()));
        // The next round counts from the promise, not from the vanilla price, and keeps the first round's clock start.
        assertEquals(List.of(14, 13, 12, 11, 10, 9, 8, 7), SpokenOffer.allowedPrices(first, carrots, 0, 22, 200));
        assertNull(SpokenOffer.promise(first, carrots, 0, 22, 16, 200), "a promise never goes back up");
        SpokenOffer second = SpokenOffer.promise(first, carrots, 0, 22, 8, 200);
        assertEquals(2, second.rounds()); assertEquals(100, second.createdTick()); assertEquals(200 + SpokenOffer.LIFETIME_TICKS, second.expiresTick());
        SpokenOffer third = SpokenOffer.promise(second, carrots, 0, 22, 4, 300);
        assertEquals(3, third.rounds());
        assertEquals(List.of(), SpokenOffer.allowedPrices(third, carrots, 0, 22, 400), "three rounds and the villager stops");
        assertEquals(2, Bargaining.floorPrice(carrots));
        // Another row starts a fresh promise; the old one is gone (one promise per villager and player).
        SpokenOffer wheat = SpokenOffer.promise(third, row(1, 20), 0, 20, 12, 500);
        assertEquals(1, wheat.rounds()); assertEquals(1, wheat.offerIndex());
        // Once the promise ran out, the same row starts over from its current price.
        assertEquals(11, SpokenOffer.allowedPrices(third, carrots, 0, 22, third.expiresTick()).getLast());
        // A permanently bargained price lowers the starting point.
        assertEquals(List.of(10, 9, 8, 7, 6, 5), SpokenOffer.allowedPrices(null, carrots, 0, 11, 100));
    }

    // No promise to a hated player, on a sold-out row, or past the floor; a bought promise is over.
    @Test void refusesWhereTheWindowWould() {
        assertEquals(List.of(), SpokenOffer.allowedPrices(null, row(0, 22), Bargaining.HOSTILE_RELATIONSHIP, 22, 0));
        assertEquals(List.of(), SpokenOffer.allowedPrices(null, new Bargaining.Offer(0, "k", 22, 22, 64, false), 50, 22, 0));
        assertEquals(List.of(), SpokenOffer.allowedPrices(null, row(0, 1), 50, 1, 0));
        SpokenOffer bought = SpokenOffer.promise(null, row(0, 22), 50, 22, 15, 0).asBought();
        assertFalse(bought.active(1)); assertFalse(bought.expired(1_000_000));
        assertEquals(1, SpokenOffer.promise(null, row(0, 22), 50, 22, 15, 0).minutesLeft(0 + SpokenOffer.LIFETIME_TICKS - 1));
        assertEquals(10, SpokenOffer.promise(null, row(0, 22), 50, 22, 15, 0).minutesLeft(0));
    }

    // The block the model reads validates its answer back: OFFER on a listed row at an allowed price, DECLINE always, the rest NONE.
    @Test void validatesTheModelsAnswerAgainstTheBlock() {
        Map<String, Object> carrots = SpokenOffer.row(0, "row-0", Map.of("id", "minecraft:carrot", "name", "Морковь", "count", 22), Map.of("count", 0),
                Map.of("id", "minecraft:emerald", "name", "Изумруд", "count", 1), 22, List.of(21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11), false);
        Map<String, Object> wheat = SpokenOffer.row(1, "row-1", Map.of("id", "minecraft:wheat", "name", "Пшеница", "count", 20), Map.of("count", 0),
                Map.of("id", "minecraft:emerald", "name", "Изумруд", "count", 1), 20, List.of(), false);
        Map<String, Object> spoken = SpokenOffer.block(List.of(carrots, wheat), null, 0, "");
        assertEquals(List.of("NONE", "OFFER", "DECLINE"), SpokenOffer.allowedTypes(spoken));
        assertEquals(11, SpokenOffer.row(spoken, 0).get("minimumPrice"));
        assertEquals(new BargainIntent(BargainIntent.Type.OFFER, 0, 4), SpokenOffer.validate(spoken, "OFFER", 0, 18));
        assertEquals(BargainIntent.none(), SpokenOffer.validate(spoken, "OFFER", 0, 10), "under the floor");
        assertEquals(BargainIntent.none(), SpokenOffer.validate(spoken, "OFFER", 1, 15), "no concession on that row");
        assertEquals(BargainIntent.none(), SpokenOffer.validate(spoken, "OFFER", 7, 15), "no such row");
        assertEquals(BargainIntent.none(), SpokenOffer.validate(spoken, "ACCEPT", 0, 0), "nothing pending to accept");
        assertEquals(BargainIntent.Type.DECLINE, SpokenOffer.validate(spoken, "DECLINE", 0, 0).type());
        assertTrue(SpokenOffer.rejection(spoken, 0, 10).contains("allowedPrices"));
        assertTrue(SpokenOffer.rejection(spoken, 1, 15).contains("нельзя"));
        assertTrue(SpokenOffer.rejection(spoken, 7, 15).contains("нет"));
        // Without a row that can take an offer, OFFER leaves the schema altogether.
        Map<String, Object> shut = SpokenOffer.block(List.of(wheat), null, 0, "цены не меняются");
        assertEquals(List.of("NONE", "DECLINE"), SpokenOffer.allowedTypes(shut));
        assertEquals(BargainIntent.none(), SpokenOffer.validate(shut, "OFFER", 1, 15));
        // The standing promise and every row's price are numbers the villager may say aloud.
        SpokenOffer standing = SpokenOffer.promise(null, row(0, 22), 50, 22, 15, 0);
        Map<String, Object> withActive = SpokenOffer.block(List.of(carrots, wheat), standing, 100, "");
        assertEquals(15, ((Map<?,?>) withActive.get("active")).get("price"));
        List<Integer> honest = SpokenOffer.honestPrices(withActive, new BargainIntent(BargainIntent.Type.OFFER, 0, 10));
        assertTrue(honest.containsAll(List.of(22, 20, 15, 12, 1)), honest.toString());
        String summary = SpokenOffer.summary(withActive);
        assertTrue(summary.contains("от 11 до 21") && summary.contains("строка 1") && summary.contains("ещё 10 мин"), summary);
    }
}
