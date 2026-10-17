package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import static io.github.mobtalk.core.BargainIntent.Type.*;
import static org.junit.jupiter.api.Assertions.*;

class BargainingTest {
    private Bargaining.Offer offer(int base, int price) { return new Bargaining.Offer(2, "sticks-for-emerald", base, price, 64, true); }
    private BargainIntent intent(BargainIntent.Type type, int discount) { return new BargainIntent(type, 2, discount); }

    // One step at most halves the price (rounded down for the buyer); a negative but not hostile relationship still bargains.
    @Test void oneStepAtMostHalvesThePriceInTheBuyersFavour() {
        assertEquals(16, Bargaining.maximumDiscount(offer(32, 32), 60));
        assertEquals(11, Bargaining.maximumDiscount(offer(22, 22), 0));
        assertEquals(8, Bargaining.maximumDiscount(offer(15, 15), -10));
        assertEquals(5, Bargaining.maximumDiscount(offer(9, 9), 60));
        assertEquals(0, Bargaining.maximumDiscount(offer(1, 1), 60));
        assertEquals(0, Bargaining.maximumDiscount(offer(32, 32), Bargaining.HOSTILE_RELATIONSHIP));
    }

    // Reported limits: 22 carrots go 11 -> 5 -> 2, never below 10% of the original quantity rounded down.
    @Test void stepsTogetherStopAtNinetyPercentOff() {
        var trade = offer(22, 22);
        assertFalse(Bargaining.negotiate(null, trade, 0, intent(OFFER, 12), 100).changed());
        var first = Bargaining.negotiate(null, trade, 0, intent(OFFER, 11), 100).session();
        assertEquals(11, Bargaining.acceptedPrice(first, trade, 101));
        assertFalse(Bargaining.negotiate(first, trade, 0, intent(OFFER, 18), 200).changed());
        var second = Bargaining.negotiate(first, trade, 0, intent(OFFER, 17), 200).session();
        assertEquals(5, second.price());
        var third = Bargaining.negotiate(second, trade, 0, intent(OFFER, 20), 300).session();
        assertEquals(2, Bargaining.acceptedPrice(third, trade, 301));
        assertEquals(2, Bargaining.floorPrice(trade));
        assertEquals(3, Bargaining.floorPrice(offer(32, 32)));
        assertEquals(1, Bargaining.floorPrice(offer(9, 9)));
    }

    // Existing vanilla discounts and demand change the starting point, never the floor of the original quantity.
    @Test void respectsVanillaDiscountsAndDemand() {
        assertEquals(15, Bargaining.maximumDiscount(offer(32, 30), 60));
        assertEquals(23, Bargaining.maximumDiscount(offer(32, 45), 60));
        assertEquals(1, Bargaining.maximumDiscount(offer(40, 5), 60));
        assertEquals(0, Bargaining.maximumDiscount(offer(40, 4), 60));
    }

    // The villager agreeing changes the window at once and for good: no expiry, no second spoken "yes", no reset after paying.
    @Test void aVillagerConcessionAppliesAtOnceAndStaysForever() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        assertNotNull(applied);
        assertEquals(Bargaining.Status.ACCEPTED, applied.status());
        assertTrue(Bargaining.holdsPrice(applied));
        assertEquals(29, Bargaining.acceptedPrice(applied, trade, 101));
        assertEquals(29, Bargaining.acceptedPrice(applied, trade, 6100));
        assertEquals(29, Bargaining.acceptedPrice(applied, trade, 10_000_000));
        assertEquals(29, Bargaining.currentPrice(applied, trade));
        assertFalse(Bargaining.negotiate(applied, trade, 60, intent(ACCEPT, 0), 200).changed());
    }

    // Within a session the price only goes down: a smaller discount than the granted one is refused.
    @Test void aGrantedPriceNeverGoesBackUp() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var raised = Bargaining.negotiate(applied, trade, 60, intent(OFFER, 2), 200);
        assertFalse(raised.changed());
        assertEquals(applied, raised.session());
        assertFalse(Bargaining.negotiate(applied, trade, 60, intent(OFFER, 3), 200).changed());
        assertEquals(28, Bargaining.negotiate(applied, trade, 60, intent(OFFER, 4), 200).session().price());
    }

    // Quotes saved by 0.2.5 still wait for the player's spoken acceptance.
    @Test void aLegacyPendingQuoteCanStillBeAccepted() {
        var trade = offer(32, 32);
        var pending = Bargaining.invalidate(Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session(), Bargaining.Status.PENDING);
        assertEquals(-1, Bargaining.acceptedPrice(pending, trade, 101));
        assertEquals(java.util.List.of(NONE, OFFER, ACCEPT, DECLINE), Bargaining.availability(pending, trade, 60, 150).allowedTypes());
        var accepted = Bargaining.negotiate(pending, trade, 60, intent(ACCEPT, 0), 200).session();
        assertEquals(29, Bargaining.acceptedPrice(accepted, trade, 201));
    }

    // Three rounds per session; after the cooldown a new session restarts the count from the price already granted.
    @Test void threeRoundsThenANewSessionContinuesFromTheGrantedPrice() {
        var trade = offer(32, 32);
        var first = Bargaining.negotiate(null, trade, 60, intent(OFFER, 1), 100).session();
        var second = Bargaining.negotiate(first, trade, 60, intent(OFFER, 2), 300).session();
        var third = Bargaining.negotiate(second, trade, 60, intent(OFFER, 3), 500).session();
        assertEquals(3, third.rounds());
        assertEquals(24100, third.cooldownUntil());
        var fourth = Bargaining.negotiate(third, trade, 60, intent(OFFER, 4), 600);
        assertFalse(fourth.changed());
        assertEquals(third, fourth.session());
        assertEquals(29, Bargaining.acceptedPrice(third, trade, 24099));
        // The new session starts at 29: one step may go down to 14 (half, rounded down), never back up.
        assertFalse(Bargaining.negotiate(third, trade, 60, intent(OFFER, 3), 24100).changed());
        assertFalse(Bargaining.negotiate(third, trade, 60, intent(OFFER, 19), 24100).changed());
        var next = Bargaining.negotiate(third, trade, 60, intent(OFFER, 18), 24100).session();
        assertEquals(1, next.rounds());
        assertEquals(24100, next.openedTick());
        assertEquals(48100, next.cooldownUntil());
        assertEquals(14, Bargaining.acceptedPrice(next, trade, 24101));
        // Rounds only cap the session: with rounds left, a session still ends by cooldown.
        var stale = Bargaining.negotiate(first, trade, 60, intent(OFFER, 2), 24100).session();
        assertEquals(1, stale.rounds());
        assertEquals(30, stale.price());
    }

    @Test void priceChangesOrRestockedDifferentItemsInvalidateTheGrantedTerms() {
        var trade = offer(32, 32);
        var accepted = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        assertEquals(-1, Bargaining.acceptedPrice(accepted, offer(32, 40), 201));
        assertEquals(-1, Bargaining.acceptedPrice(accepted, new Bargaining.Offer(2, "different-enchantment", 32, 32, 64, true), 201));
        assertEquals(-1, Bargaining.acceptedPrice(accepted, new Bargaining.Offer(2, trade.key(), 32, 32, 64, false), 201));
        var changed = Bargaining.negotiate(accepted, offer(32, 40), 60, intent(OFFER, 5), 300);
        assertTrue(changed.changed());
        assertEquals(Bargaining.Status.EXPIRED, changed.session().status());
        assertFalse(Bargaining.holdsPrice(changed.session()));
    }

    @Test void refusedOrExpiredNegotiationsKeepTheirCooldown() {
        var trade = offer(32, 32);
        var declined = Bargaining.negotiate(null, trade, 60, intent(DECLINE, 0), 100).session();
        assertEquals(Bargaining.Status.DECLINED, declined.status());
        assertFalse(Bargaining.holdsPrice(declined));
        assertFalse(Bargaining.negotiate(declined, trade, 60, intent(OFFER, 1), 200).changed());
        assertNotNull(Bargaining.negotiate(declined, trade, 60, intent(OFFER, 1), 24100).session());
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 1), 100).session();
        assertFalse(Bargaining.negotiate(Bargaining.invalidate(applied, Bargaining.Status.EXPIRED), trade, 60, intent(OFFER, 1), 6200).changed());
        // An expired session lost its price: the next session starts again from the vanilla price.
        var fresh = Bargaining.negotiate(Bargaining.invalidate(applied, Bargaining.Status.EXPIRED), trade, 60, intent(OFFER, 1), 24100).session();
        assertEquals(31, fresh.price());
        assertEquals(1, fresh.rounds());
    }

    // The villager ending the talk keeps what it already granted; the next session (after cooldown) may lower it further.
    @Test void aDeclineEndsTheSessionButKeepsTheGrantedPrice() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var refused = Bargaining.negotiate(applied, trade, 60, intent(DECLINE, 0), 200);
        assertTrue(refused.changed());
        assertEquals(Bargaining.Status.DECLINED, refused.session().status());
        assertTrue(Bargaining.holdsPrice(refused.session()));
        assertEquals(29, Bargaining.acceptedPrice(refused.session(), trade, 201));
        assertEquals(24100, refused.session().cooldownUntil());
        assertFalse(Bargaining.negotiate(refused.session(), trade, 60, intent(OFFER, 5), 300).changed());
        assertFalse(Bargaining.negotiate(refused.session(), trade, 60, intent(OFFER, 2), 24100).changed());
        var lowered = Bargaining.negotiate(refused.session(), trade, 60, intent(OFFER, 5), 24100).session();
        assertEquals(27, lowered.price());
        assertEquals(1, lowered.rounds());
        // A refusal after the session ran out by itself also keeps the price and opens a fresh cooldown.
        var late = Bargaining.negotiate(applied, trade, 60, intent(DECLINE, 0), 30000).session();
        assertEquals(29, Bargaining.acceptedPrice(late, trade, 30001));
        assertEquals(54000, late.cooldownUntil());
    }

    @Test void aModelCannotCreateAFreeTradeOrMoveTheQuoteToAnotherOffer() {
        var trade = offer(32, 32);
        assertFalse(Bargaining.negotiate(null, trade, 60, intent(OFFER, 32), 100).changed());
        assertFalse(Bargaining.negotiate(null, trade, 60, intent(OFFER, -1), 100).changed());
        assertFalse(Bargaining.negotiate(null, trade, 60, new BargainIntent(OFFER, 7, 1), 100).changed());
        assertFalse(Bargaining.negotiate(null, trade, 60, intent(ACCEPT, 0), 100).changed());
        assertFalse(Bargaining.negotiate(null, offer(1, 1), 60, intent(OFFER, 1), 100).changed());
    }

    // Pure rules keep the price on an angry refusal; the game layer drops the overlay for a hostile player via permitted().
    @Test void anAngryVillagerCanRefuseWithoutTheRulesGrantingAnythingNew() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var refused = Bargaining.negotiate(applied, trade, -50, intent(DECLINE, 0), 200);
        assertTrue(refused.changed());
        assertEquals(Bargaining.Status.DECLINED, refused.session().status());
        assertEquals(24100, refused.session().cooldownUntil());
        assertFalse(Bargaining.permitted(trade, -50, refused.session().discount()));
        assertFalse(Bargaining.negotiate(refused.session(), trade, -50, intent(OFFER, 4), 24100).changed());
    }

    @Test void availabilityStopsNewPricesAfterTheThirdRoundUntilTheCooldown() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 1), 100).session();
        applied = Bargaining.negotiate(applied, trade, 60, intent(OFFER, 2), 200).session();
        applied = Bargaining.negotiate(applied, trade, 60, intent(OFFER, 3), 300).session();
        var availability = Bargaining.availability(applied, trade, 60, 400);
        assertEquals(java.util.List.of(NONE, DECLINE), availability.allowedTypes());
        assertFalse(availability.reason().isBlank());
        assertEquals(3, applied.rounds());
        assertEquals(29, Bargaining.acceptedPrice(applied, trade, 400));
        assertEquals(java.util.List.of(NONE, OFFER, DECLINE), Bargaining.availability(applied, trade, 60, applied.cooldownUntil()).allowedTypes());
    }

    @Test void availabilityBlocksNewPricesDuringCooldown() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        // An applied concession may still be lowered within the remaining rounds.
        assertEquals(java.util.List.of(NONE, OFFER, DECLINE), Bargaining.availability(applied, trade, 60, 300).allowedTypes());
        for (var terminal : java.util.List.of(Bargaining.Status.CONSUMED, Bargaining.Status.DECLINED, Bargaining.Status.EXPIRED)) {
            var finished = Bargaining.invalidate(applied, terminal);
            var availability = Bargaining.availability(finished, trade, 60, 6200);
            assertEquals(java.util.List.of(NONE, DECLINE), availability.allowedTypes());
            assertFalse(availability.reason().isBlank());
        }
    }

    @Test void availabilityPermitsRefusalWithoutAnyDiscountCapacity() {
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(null, offer(1, 1), 60, 100).allowedTypes());
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(null, offer(32, 32), -50, 100).allowedTypes());
        var applied = Bargaining.negotiate(null, offer(32, 32), 60, intent(OFFER, 3), 100).session();
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(applied, offer(32, 32), -50, 200).allowedTypes());
        // At the floor nothing lower exists, so only a refusal remains.
        var floored = Bargaining.negotiate(null, offer(32, 32), 60, intent(OFFER, 16), 100).session();
        floored = Bargaining.negotiate(floored, offer(32, 32), 60, intent(OFFER, 24), 200).session();
        floored = Bargaining.negotiate(floored, offer(32, 32), 60, intent(OFFER, 28), 300).session();
        floored = Bargaining.negotiate(floored, offer(32, 32), 60, intent(OFFER, 29), 24100).session();
        assertEquals(3, floored.price());
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(floored, offer(32, 32), 60, 24200).allowedTypes());
    }

    @Test void availabilityKeepsThePriceAndOffersANewSessionAfterCooldown() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var later = Bargaining.availability(applied, trade, 60, 6100);
        assertEquals(java.util.List.of(NONE, OFFER, DECLINE), later.allowedTypes());
        assertEquals(29, Bargaining.acceptedPrice(applied, trade, 6100));
        assertEquals(Bargaining.Status.ACCEPTED, applied.status()); // Pure inspection never changes the stored session.
        var available = Bargaining.availability(applied, trade, 60, applied.cooldownUntil());
        assertEquals(java.util.List.of(NONE, OFFER, DECLINE), available.allowedTypes());
        var next = Bargaining.negotiate(applied, trade, 60, intent(OFFER, 4), applied.cooldownUntil()).session();
        assertEquals(1, next.rounds());
        assertEquals(applied.cooldownUntil(), next.openedTick());
        assertEquals(28, next.price());
        var expired = Bargaining.invalidate(applied, Bargaining.Status.EXPIRED);
        assertEquals(java.util.List.of(NONE, OFFER, DECLINE), Bargaining.availability(expired, trade, 60, applied.cooldownUntil()).allowedTypes());
        assertEquals(31, Bargaining.negotiate(expired, trade, 60, intent(OFFER, 1), applied.cooldownUntil()).session().price());
    }

    @Test void availabilityRejectsChangedTradeAndVanillaPriceWithoutMutatingTheQuote() {
        var trade = offer(32, 32);
        var applied = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        for (var changed : java.util.List.of(offer(32, 40), new Bargaining.Offer(2, "different-result", 32, 32, 64, true))) {
            var availability = Bargaining.availability(applied, changed, 60, 200);
            assertEquals(java.util.List.of(NONE), availability.allowedTypes());
            assertFalse(availability.reason().isBlank());
        }
        assertEquals(Bargaining.Status.ACCEPTED, applied.status());
        assertEquals(29, applied.price());
    }
}
