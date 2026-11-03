package io.github.mobtalk.core.cards;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardTableTest {
    private static Card c(Rank rank) { return new Card(rank, Suit.HEARTS); }
    private static Card c(Rank rank, Suit suit) { return new Card(rank, suit); }

    private static CardTable table(Variant variant, DealerStyle style, int purse, boolean cheating, Card... order) {
        SplittableRandom random = new SplittableRandom(7);
        Shoe shoe = Shoe.stacked(variant, random, List.of(order));
        return new CardTable(variant, style, random, shoe, new CardTable.Limits(4, 1, 16, cheating), purse);
    }

    // Ochko: 36 cards from six to ace, pictures worth 2, 3 and 4, an ace always 11; two aces are golden and score as 21.
    @Test void ochkoCountsTheYardWay() {
        assertEquals(36, Variant.OCHKO.deck().size());
        assertEquals(104, Variant.BLACKJACK.deck().size());
        assertEquals(9, Variant.OCHKO.score(List.of(c(Rank.JACK), c(Rank.QUEEN), c(Rank.KING))).total());
        assertEquals(32, Variant.OCHKO.score(List.of(c(Rank.ACE), c(Rank.TEN), c(Rank.ACE))).total(), "an ace never drops to 1 in ochko");
        assertTrue(Variant.OCHKO.golden(List.of(c(Rank.ACE), c(Rank.ACE, Suit.SPADES))));
        assertEquals(21, Variant.OCHKO.score(List.of(c(Rank.ACE), c(Rank.ACE, Suit.SPADES))).total());
        assertFalse(Variant.OCHKO.natural(List.of(c(Rank.ACE), c(Rank.TEN)), false), "ace and ten is 21 but not golden");
    }

    // Blackjack: aces drop from 11 to 1, soft says one is still high; a two-card 21 is natural except after a split.
    @Test void blackjackCountsAcesSoftAndHard() {
        Variant.Score soft = Variant.BLACKJACK.score(List.of(c(Rank.ACE), c(Rank.SIX)));
        assertEquals(17, soft.total()); assertTrue(soft.soft());
        Variant.Score hard = Variant.BLACKJACK.score(List.of(c(Rank.ACE), c(Rank.SIX), c(Rank.KING)));
        assertEquals(17, hard.total()); assertFalse(hard.soft());
        assertEquals(12, Variant.BLACKJACK.score(List.of(c(Rank.ACE), c(Rank.ACE, Suit.CLUBS))).total());
        assertTrue(Variant.BLACKJACK.natural(List.of(c(Rank.ACE), c(Rank.QUEEN)), false));
        assertFalse(Variant.BLACKJACK.natural(List.of(c(Rank.ACE), c(Rank.QUEEN)), true));
    }

    // Card codes survive the wire; a face-down card is -1 and comes back as null.
    @Test void cardCodesRoundTrip() {
        for (int code = 0; code < 52; code++) assertEquals(code, Card.of(code).code());
        assertNull(Card.of(-1));
        assertEquals("Т♥", c(Rank.ACE).label());
    }

    // An ochko deal: one card each, the villager's face down; the player draws to 19, the cautious villager stops at 15 and pays.
    @Test void ochkoDealPaysTheHigherHand() {
        CardTable table = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 20, false,
                c(Rank.TEN), c(Rank.NINE), c(Rank.NINE, Suit.SPADES), c(Rank.SIX));
        assertEquals(CardTable.Refusal.NONE, table.sit(0, "Дмитрий"));
        assertEquals(CardTable.Refusal.NONE, table.bet(0, 5));
        assertEquals(CardTable.Refusal.NONE, table.deal());
        assertEquals(CardTable.Phase.PLAYERS, table.phase());
        assertEquals(-1, (int) table.view().dealer().get(0), "the villager's card is hidden");
        assertTrue(table.act(0, Action.HIT, 0).ok());
        assertEquals(19, table.hands(0).get(0).score(Variant.OCHKO).total());
        assertTrue(table.act(0, Action.STAND, 0).ok());
        assertEquals(CardTable.Phase.SETTLED, table.phase());
        assertEquals(15, Variant.OCHKO.score(table.dealerCards()).total(), "nine and six: the cautious one stops at 15");
        assertEquals(new CardTable.Settlement(0, 5, 10), table.settlements().get(0));
        assertEquals(15, table.purse(), "the bank paid five");
    }

    // Going over 21 loses at once, before the villager plays; with nobody left he does not draw.
    @Test void bustLosesBeforeTheVillagerPlays() {
        CardTable table = table(Variant.OCHKO, DealerStyle.RECKLESS, 20, false,
                c(Rank.TEN), c(Rank.SEVEN), c(Rank.NINE), c(Rank.SIX));
        table.sit(0, "A"); table.bet(0, 4); table.deal();
        table.act(0, Action.HIT, 0);
        table.act(0, Action.HIT, 0);
        assertTrue(table.hands(0).get(0).bust(Variant.OCHKO));
        assertEquals(CardTable.Phase.SETTLED, table.phase());
        assertEquals(1, table.dealerCards().size(), "nobody left to beat");
        assertEquals(0, table.settlements().get(0).returned());
        assertEquals(24, table.purse());
    }

    // Golden ochko stands by itself and wins even against the villager's 21.
    @Test void goldenOchkoWins() {
        CardTable table = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 20, false,
                c(Rank.ACE), c(Rank.TEN), c(Rank.ACE, Suit.SPADES), c(Rank.ACE, Suit.CLUBS));
        table.sit(0, "A"); table.bet(0, 3); table.deal();
        table.act(0, Action.HIT, 0);
        List<TableEvent> events = table.drainEvents();
        assertTrue(events.stream().anyMatch(e -> e.kind() == TableEvent.Kind.NATURAL && e.seat() == 0));
        assertEquals(CardTable.Phase.SETTLED, table.phase());
        assertEquals(6, table.settlements().get(0).returned());
    }

    // Va-bank raises the stake to all the bank still covers, limited by what the player has; only on the first card.
    @Test void vaBankTakesTheWholeBank() {
        CardTable table = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 20, false,
                c(Rank.TEN), c(Rank.TEN, Suit.SPADES), c(Rank.SEVEN), c(Rank.SIX));
        table.sit(0, "A"); table.bet(0, 2); table.deal();
        assertEquals(18, table.cost(0, Action.VA_BANK));
        CardTable.Outcome raised = table.act(0, Action.VA_BANK, 50);
        assertTrue(raised.ok()); assertEquals(18, raised.paid());
        assertEquals(20, table.hands(0).get(0).bet());
        assertEquals(CardTable.Refusal.NOT_ALLOWED, table.act(0, Action.VA_BANK, 50).refusal(), "only once");
        table.act(0, Action.HIT, 0);
        assertEquals(CardTable.Refusal.NOT_ALLOWED, table.act(0, Action.DOUBLE, 50).refusal(), "no doubling in ochko");
        table.act(0, Action.STAND, 0);
        assertEquals(CardTable.Phase.SETTLED, table.phase());
        assertEquals(40, table.settlements().get(0).returned(), "17 against the villager's 16: the whole bank");
        assertEquals(0, table.purse());
    }

    // The bank never promises more than it holds: stakes past it are refused, a poor player's va-bank stops at his wallet.
    @Test void stakesStayWithinTheBank() {
        CardTable table = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 6, false);
        table.sit(0, "A"); table.sit(1, "B");
        assertEquals(CardTable.Refusal.NONE, table.bet(0, 4));
        assertEquals(CardTable.Refusal.BANK, table.bet(1, 3));
        assertEquals(CardTable.Refusal.NONE, table.bet(1, 2));
        assertEquals(CardTable.Refusal.LIMIT, table.bet(0, 13), "over the stake limit");
        assertEquals(2, table.unbet(1, 5));
        assertEquals(CardTable.Refusal.PHASE, table.nextRound());
    }

    // Blackjack: two cards each, the second of the villager face down; a natural pays 3:2 rounded down.
    @Test void blackjackNaturalPaysThreeToTwo() {
        CardTable table = table(Variant.BLACKJACK, DealerStyle.CALCULATING, 40, false,
                c(Rank.ACE), c(Rank.NINE), c(Rank.KING), c(Rank.SEVEN));
        table.sit(0, "A"); table.bet(0, 5); table.deal();
        assertEquals(CardTable.Phase.SETTLED, table.phase(), "a natural needs no decision and nobody else is playing");
        assertEquals(12, table.settlements().get(0).returned(), "5 back plus 7 (3:2 of 5, rounded down)");
    }

    // Blackjack dealer: draws to 16, stands on 17, including a soft 17.
    @Test void blackjackDealerStandsOnSeventeen() {
        CardTable table = table(Variant.BLACKJACK, DealerStyle.RECKLESS, 40, false,
                c(Rank.TEN), c(Rank.ACE), c(Rank.EIGHT), c(Rank.SIX), c(Rank.FIVE));
        table.sit(0, "A"); table.bet(0, 2); table.deal();
        CardTable.View view = table.view();
        assertEquals(List.of(c(Rank.ACE).code(), -1), view.dealer());
        assertEquals(11, view.dealerTotal(), "only the open card counts while the other is hidden");
        table.act(0, Action.STAND, 0);
        assertEquals(2, table.dealerCards().size(), "ace and six is a soft 17: he stands");
        assertEquals(4, table.settlements().get(0).returned(), "18 beats 17");
    }

    // Double: the stake again, one card, the hand stands. Split: two hands of the same stake, aces get one card each.
    @Test void blackjackDoubleAndSplit() {
        CardTable doubled = table(Variant.BLACKJACK, DealerStyle.CAUTIOUS, 40, false,
                c(Rank.SIX), c(Rank.TEN), c(Rank.FIVE), c(Rank.SEVEN), c(Rank.KING));
        doubled.sit(0, "A"); doubled.bet(0, 4); doubled.deal();
        assertEquals(CardTable.Refusal.FUNDS, doubled.act(0, Action.DOUBLE, 3).refusal());
        CardTable.Outcome outcome = doubled.act(0, Action.DOUBLE, 10);
        assertEquals(4, outcome.paid());
        assertEquals(8, doubled.hands(0).get(0).bet());
        assertEquals(3, doubled.hands(0).get(0).cards().size());
        assertEquals(CardTable.Phase.SETTLED, doubled.phase());
        assertEquals(16, doubled.settlements().get(0).returned(), "21 against 17");

        CardTable split = table(Variant.BLACKJACK, DealerStyle.CAUTIOUS, 40, false,
                c(Rank.ACE), c(Rank.TEN), c(Rank.ACE, Suit.SPADES), c(Rank.SEVEN), c(Rank.KING), c(Rank.NINE));
        split.sit(0, "A"); split.bet(0, 3); split.deal();
        CardTable.Outcome halves = split.act(0, Action.SPLIT, 10);
        assertEquals(3, halves.paid());
        assertEquals(2, split.hands(0).size());
        assertTrue(split.hands(0).stream().allMatch(Hand::stood), "split aces take one card each");
        assertFalse(split.hands(0).get(0).natural(Variant.BLACKJACK), "21 after a split is not a blackjack");
        assertEquals(CardTable.Phase.SETTLED, split.phase());
        assertEquals(new CardTable.Settlement(0, 6, 12), split.settlements().get(0), "21 and 20 both beat 17");
    }

    // Seats play in order; the villager waits for the last of them.
    @Test void seatsPlayInTurn() {
        CardTable table = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 30, false,
                c(Rank.TEN), c(Rank.NINE), c(Rank.EIGHT), c(Rank.NINE, Suit.SPADES), c(Rank.SIX));
        table.sit(0, "A"); table.sit(2, "C");
        table.bet(0, 2); table.bet(2, 3); table.deal();
        assertEquals(0, table.turnSeat());
        assertEquals(CardTable.Refusal.TURN, table.act(2, Action.STAND, 0).refusal());
        table.act(0, Action.STAND, 0);
        assertEquals(2, table.turnSeat());
        table.act(2, Action.STAND, 0);
        assertEquals(CardTable.Phase.SETTLED, table.phase());
        assertEquals(2, table.settlements().size());
    }

    // The trickster's sleeve: the card that would bust him is swapped for the best one in the shoe, once; «Ловлю!» then wins the
    // deal outright, and an accusation for nothing changes nothing.
    @Test void tricksterCheatsAndCanBeCaught() {
        List<Card> order = new ArrayList<>(List.of(c(Rank.TEN), c(Rank.TEN, Suit.SPADES), c(Rank.NINE), c(Rank.EIGHT),
                c(Rank.SIX), c(Rank.TEN, Suit.CLUBS), c(Rank.SIX, Suit.SPADES)));
        CardTable table = new CardTable(Variant.OCHKO, DealerStyle.TRICKSTER, new AlwaysLow(), Shoe.stacked(Variant.OCHKO, new SplittableRandom(1), order),
                new CardTable.Limits(4, 1, 16, true), 20);
        table.sit(0, "A"); table.sit(1, "B");
        table.bet(0, 4); table.bet(1, 4); table.deal();
        table.act(0, Action.HIT, 0); table.act(0, Action.STAND, 0);
        table.act(1, Action.STAND, 0);
        assertTrue(table.cheated());
        List<TableEvent> events = table.drainEvents();
        assertTrue(events.stream().anyMatch(e -> e.kind() == TableEvent.Kind.CHEAT));
        assertFalse(Variant.OCHKO.score(table.dealerCards()).bust());
        CardTable.Accusation caught = table.accuse(0);
        assertTrue(caught.caught());
        assertEquals(8, table.settlements().get(0).returned(), "the accuser's deal counts as won");
        assertEquals(CardTable.Refusal.NOT_ALLOWED, table.accuse(0).refusal(), "once per deal");

        CardTable honest = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 20, true, c(Rank.TEN), c(Rank.NINE), c(Rank.SIX), c(Rank.EIGHT));
        honest.sit(0, "A"); honest.bet(0, 2); honest.deal(); honest.act(0, Action.STAND, 0);
        CardTable.Accusation wrong = honest.accuse(0);
        assertFalse(wrong.caught());
        assertEquals(0, wrong.extra());
    }

    // Cancelling gives every stake back untouched; a player who walks away mid-deal leaves his stake to the bank.
    @Test void cancelRefundsAndLeavingForfeits() {
        CardTable table = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 20, false, c(Rank.TEN), c(Rank.NINE), c(Rank.SIX));
        table.sit(0, "A"); table.sit(1, "B"); table.bet(0, 3); table.bet(1, 2); table.deal();
        List<CardTable.Settlement> back = table.cancel();
        assertEquals(List.of(new CardTable.Settlement(0, 3, 3), new CardTable.Settlement(1, 2, 2)), back);
        assertEquals(20, table.purse());
        assertEquals(CardTable.Phase.BETTING, table.phase());

        table.bet(0, 3); table.bet(1, 2); table.deal();
        assertEquals(0, table.leave(0, false));
        assertEquals(23, table.purse(), "his stake stays with the bank");
        assertEquals(1, table.turnSeat());
        assertEquals(2, table.leave(1, true), "a disconnect gets the stake back");
    }

    // Purse: start by trade level, two a day up to the start, never below zero.
    @Test void purseRegrowsSlowly() {
        assertEquals(8, Purse.start(1)); assertEquals(24, Purse.start(5)); assertEquals(24, Purse.start(9));
        Purse purse = new Purse(3, 10);
        purse.regenerate(12, 2, 12);
        assertEquals(7, purse.balance());
        purse.regenerate(40, 2, 12);
        assertEquals(12, purse.balance());
        purse.add(-50);
        assertEquals(0, purse.balance());
    }

    // Style from character: greedy and playful villagers keep a card up the sleeve, anxious ones stop early.
    @Test void styleFollowsCharacter() {
        assertEquals(DealerStyle.TRICKSTER, DealerStyle.of(io.github.mobtalk.core.Persona.GREEDY));
        assertEquals(DealerStyle.CAUTIOUS, DealerStyle.of(io.github.mobtalk.core.Persona.ANXIOUS));
        assertEquals(DealerStyle.CAUTIOUS, DealerStyle.of(null));
        for (io.github.mobtalk.core.Persona persona : io.github.mobtalk.core.Persona.values()) assertNotNull(DealerStyle.of(persona));
    }

    /** Every roll lands low: the trickster always reaches for his sleeve. */
    private static final class AlwaysLow implements java.util.random.RandomGenerator {
        @Override public long nextLong() { return 0; }
        @Override public double nextDouble() { return 0; }
        @Override public int nextInt(int bound) { return 0; }
    }
    // A stake that was not emeralds (a game for interest, a favour) leaves the bank as it was: the game layer adjusts it, never below zero.
    @Test void bankAdjustsAndNeverGoesNegative() {
        CardTable table = table(Variant.OCHKO, DealerStyle.CAUTIOUS, 5, false, c(Rank.TEN));
        table.adjust(3);
        assertEquals(8, table.purse());
        table.adjust(-20);
        assertEquals(0, table.purse());
    }
}
