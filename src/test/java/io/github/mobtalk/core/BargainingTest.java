package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import static io.github.mobtalk.core.BargainIntent.Type.*;
import static org.junit.jupiter.api.Assertions.*;

class BargainingTest {
    private Bargaining.Offer offer(int base, int price) { return new Bargaining.Offer(2, "sticks-for-emerald", base, price, 64, true); }
    private BargainIntent intent(BargainIntent.Type type, int discount) { return new BargainIntent(type, 2, discount); }

    // The first ingredient may be sticks or emeralds; integer rounding must never exceed ten percent.
    @Test void capsAnyInputQuantityWithoutRoundingUpCheapTrades() {
        assertEquals(3, Bargaining.maximumDiscount(offer(32, 32), 60));
        assertEquals(2, Bargaining.maximumDiscount(offer(20, 20), 60));
        assertEquals(1, Bargaining.maximumDiscount(offer(10, 10), 60));
        assertEquals(0, Bargaining.maximumDiscount(offer(9, 9), 60));
        assertEquals(0, Bargaining.maximumDiscount(offer(1, 1), 60));
        assertEquals(3, Bargaining.maximumDiscount(offer(64, 64), 60));
        assertEquals(1, Bargaining.maximumDiscount(offer(32, 32), 0));
        assertEquals(0, Bargaining.maximumDiscount(offer(32, 32), -10));
    }

    // Existing vanilla discounts consume the same ten-percent floor rather than stacking a new ten percent.
    @Test void respectsVanillaDiscountsAndDoesNotResetDemand() {
        assertEquals(1, Bargaining.maximumDiscount(offer(32, 30), 60));
        assertEquals(0, Bargaining.maximumDiscount(offer(32, 29), 60));
        assertEquals(0, Bargaining.maximumDiscount(offer(40, 30), 60));
        assertEquals(3, Bargaining.maximumDiscount(offer(32, 45), 60));
    }

    @Test void aQuoteRequiresSpokenAcceptanceAndPaysOnlyOnce() {
        var trade = offer(32, 32);
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        assertNotNull(pending);
        assertEquals(Bargaining.Status.PENDING, pending.status());
        assertEquals(29, pending.price());
        assertEquals(-1, Bargaining.acceptedPrice(pending, trade, 101));
        var accepted = Bargaining.negotiate(pending, trade, 60, intent(ACCEPT, 0), 200).session();
        assertEquals(29, Bargaining.acceptedPrice(accepted, trade, 201));
        assertEquals(-1, Bargaining.acceptedPrice(Bargaining.consumed(accepted), trade, 202));
    }

    @Test void repeatedCounteroffersHaveThreeRoundsAndNeverRefreshTheExpiry() {
        var trade = offer(32, 32);
        var first = Bargaining.negotiate(null, trade, 60, intent(OFFER, 1), 100).session();
        var second = Bargaining.negotiate(first, trade, 60, intent(OFFER, 2), 300).session();
        var third = Bargaining.negotiate(second, trade, 60, intent(OFFER, 3), 500).session();
        assertEquals(3, third.rounds());
        assertEquals(6100, third.expiresTick());
        var fourth = Bargaining.negotiate(third, trade, 60, intent(OFFER, 3), 600);
        assertFalse(fourth.changed());
        assertEquals(third, fourth.session());
        assertEquals(29, Bargaining.acceptedPrice(Bargaining.negotiate(third, trade, 60, intent(ACCEPT, 0), 700).session(), trade, 701));
    }

    @Test void priceChangesOrRestockedDifferentItemsInvalidateTheQuotedTerms() {
        var trade = offer(32, 32);
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var accepted = Bargaining.negotiate(pending, trade, 60, intent(ACCEPT, 0), 200).session();
        assertEquals(-1, Bargaining.acceptedPrice(accepted, offer(32, 40), 201));
        assertEquals(-1, Bargaining.acceptedPrice(accepted, new Bargaining.Offer(2, "different-enchantment", 32, 32, 64, true), 201));
        assertEquals(-1, Bargaining.acceptedPrice(accepted, new Bargaining.Offer(2, trade.key(), 32, 32, 64, false), 201));
        assertEquals(-1, Bargaining.acceptedPrice(accepted, trade, 6100));
    }

    @Test void refusedOrExpiredNegotiationsKeepTheirCooldown() {
        var trade = offer(32, 32);
        var declined = Bargaining.negotiate(null, trade, 60, intent(DECLINE, 0), 100).session();
        assertEquals(Bargaining.Status.DECLINED, declined.status());
        assertFalse(Bargaining.negotiate(declined, trade, 60, intent(OFFER, 1), 200).changed());
        assertNotNull(Bargaining.negotiate(declined, trade, 60, intent(OFFER, 1), 24100).session());
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 1), 100).session();
        assertFalse(Bargaining.negotiate(Bargaining.invalidate(pending, Bargaining.Status.EXPIRED), trade, 60, intent(OFFER, 1), 6200).changed());
    }

    @Test void aModelCannotCreateAFreeTradeOrMoveTheQuoteToAnotherOffer() {
        var trade = offer(32, 32);
        assertFalse(Bargaining.negotiate(null, trade, 60, intent(OFFER, 32), 100).changed());
        assertFalse(Bargaining.negotiate(null, trade, 60, intent(OFFER, -1), 100).changed());
        assertFalse(Bargaining.negotiate(null, trade, 60, new BargainIntent(OFFER, 7, 1), 100).changed());
        assertFalse(Bargaining.negotiate(null, trade, 60, intent(ACCEPT, 0), 100).changed());
        assertFalse(Bargaining.negotiate(null, offer(9, 9), 60, intent(OFFER, 1), 100).changed());
    }

    @Test void anAngryVillagerCanRefuseAnExistingProposalWithoutLeavingItUsable() {
        var trade = offer(32, 32);
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var refused = Bargaining.negotiate(pending, trade, -10, intent(DECLINE, 0), 200);
        assertTrue(refused.changed());
        assertEquals(Bargaining.Status.DECLINED, refused.session().status());
        assertEquals(-1, Bargaining.acceptedPrice(refused.session(), trade, 201));
        assertEquals(24100, refused.session().cooldownUntil());
    }

    @Test void availabilityKeepsAcceptanceAfterTheThirdRound() {
        var trade = offer(32, 32);
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 1), 100).session();
        pending = Bargaining.negotiate(pending, trade, 60, intent(OFFER, 2), 200).session();
        pending = Bargaining.negotiate(pending, trade, 60, intent(OFFER, 3), 300).session();
        var availability = Bargaining.availability(pending, trade, 60, 400);
        assertEquals(java.util.List.of(NONE, ACCEPT, DECLINE), availability.allowedTypes());
        assertFalse(availability.reason().isBlank());
        assertEquals(3, pending.rounds());
        assertEquals(6100, pending.expiresTick());
        assertEquals(Bargaining.Status.ACCEPTED, Bargaining.negotiate(pending, trade, 60, intent(ACCEPT, 0), 400).session().status());
    }

    @Test void availabilityBlocksNewPricesDuringCooldownAndAfterAcceptance() {
        var trade = offer(32, 32);
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var accepted = Bargaining.negotiate(pending, trade, 60, intent(ACCEPT, 0), 200).session();
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(accepted, trade, 60, 300).allowedTypes());
        for (var terminal : java.util.List.of(Bargaining.Status.CONSUMED, Bargaining.Status.DECLINED, Bargaining.Status.EXPIRED)) {
            var finished = Bargaining.invalidate(pending, terminal);
            var availability = Bargaining.availability(finished, trade, 60, 6200);
            assertEquals(java.util.List.of(NONE, DECLINE), availability.allowedTypes());
            assertFalse(availability.reason().isBlank());
        }
    }

    @Test void availabilityPermitsRefusalWithoutAnyDiscountCapacity() {
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(null, offer(9, 9), 60, 100).allowedTypes());
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(null, offer(32, 32), -10, 100).allowedTypes());
        var pending = Bargaining.negotiate(null, offer(32, 32), 60, intent(OFFER, 3), 100).session();
        assertEquals(java.util.List.of(NONE, DECLINE), Bargaining.availability(pending, offer(32, 32), -10, 200).allowedTypes());
    }

    @Test void availabilityExpiresActiveTermsAndAllowsANewQuoteOnlyAfterCooldown() {
        var trade = offer(32, 32);
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        var expired = Bargaining.availability(pending, trade, 60, pending.expiresTick());
        assertEquals(java.util.List.of(NONE), expired.allowedTypes());
        assertFalse(expired.reason().isBlank());
        assertEquals(Bargaining.Status.PENDING, pending.status()); // Pure inspection cannot expire or extend the stored session.
        for (var previous : java.util.List.of(pending, Bargaining.invalidate(pending, Bargaining.Status.EXPIRED))) {
            var available = Bargaining.availability(previous, trade, 60, pending.cooldownUntil());
            assertEquals(java.util.List.of(NONE, OFFER, DECLINE), available.allowedTypes());
            var next = Bargaining.negotiate(previous, trade, 60, intent(OFFER, 1), pending.cooldownUntil()).session();
            assertEquals(1, next.rounds());
            assertEquals(pending.cooldownUntil(), next.openedTick());
        }
    }

    @Test void availabilityRejectsChangedTradeAndVanillaPriceWithoutMutatingTheQuote() {
        var trade = offer(32, 32);
        var pending = Bargaining.negotiate(null, trade, 60, intent(OFFER, 3), 100).session();
        for (var changed : java.util.List.of(offer(32, 40), new Bargaining.Offer(2, "different-result", 32, 32, 64, true))) {
            var availability = Bargaining.availability(pending, changed, 60, 200);
            assertEquals(java.util.List.of(NONE), availability.allowedTypes());
            assertFalse(availability.reason().isBlank());
        }
        assertEquals(Bargaining.Status.PENDING, pending.status());
        assertEquals(29, pending.price());
    }
}
