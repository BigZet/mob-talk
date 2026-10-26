package io.github.mobtalk.core.cards;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Many simulated deals against each villager: a player who has learned how this villager plays should come out roughly even, never
 * far ahead (emeralds from thin air) and never far behind (nobody would sit down twice). Cheating is off here; the sleeve is
 * {@link CardTableTest}'s business. */
class CardBalanceTest {
    private static final int DEALS = 40_000;

    // Ochko: the best fixed stop against each style leaves the player between 8% behind and 4% ahead.
    @Test void ochkoIsRoughlyFairToAPlayerWhoReadsTheVillager() {
        for (DealerStyle style : DealerStyle.values()) {
            double best = -1;
            for (int stop = 14; stop <= 18; stop++) best = Math.max(best, ochkoEdge(style, stop));
            assertTrue(best > -0.08 && best < 0.04, style + " best edge " + best);
        }
    }

    // Blackjack: a simple strategy loses about a percent, as in a casino.
    @Test void blackjackKeepsAThinHouseEdge() {
        double edge = blackjackEdge();
        assertTrue(edge > -0.04 && edge < 0.01, "edge " + edge);
    }

    private static double ochkoEdge(DealerStyle style, int stop) {
        CardTable table = table(Variant.OCHKO, style);
        long staked = 0, returned = 0;
        for (int i = 0; i < DEALS; i++) {
            table.bet(0, 2);
            table.deal();
            while (table.phase() == CardTable.Phase.PLAYERS) {
                int total = table.hands(0).get(table.turnHand()).score(Variant.OCHKO).total();
                table.act(0, total < stop ? Action.HIT : Action.STAND, 0);
            }
            for (CardTable.Settlement settled : table.settlements()) { staked += settled.staked(); returned += settled.returned(); }
            table.nextRound();
        }
        return (returned - staked) / (double) staked;
    }

    private static double blackjackEdge() {
        CardTable table = table(Variant.BLACKJACK, DealerStyle.CALCULATING);
        long staked = 0, returned = 0;
        for (int i = 0; i < DEALS; i++) {
            table.bet(0, 2);
            table.deal();
            while (table.phase() == CardTable.Phase.PLAYERS) {
                Hand hand = table.hands(0).get(table.turnHand());
                Variant.Score score = hand.score(Variant.BLACKJACK);
                int up = Variant.BLACKJACK.value(table.dealerCards().get(0).rank());
                if ((score.total() == 10 || score.total() == 11) && hand.cards().size() == 2 && up < 10) {
                    table.act(0, Action.DOUBLE, 100);
                    continue;
                }
                boolean hit = score.total() <= 11 || (score.total() < 17 && up >= 7) || (score.soft() && score.total() < 18);
                table.act(0, hit ? Action.HIT : Action.STAND, 0);
            }
            for (CardTable.Settlement settled : table.settlements()) { staked += settled.staked(); returned += settled.returned(); }
            table.nextRound();
        }
        return (returned - staked) / (double) staked;
    }

    private static CardTable table(Variant variant, DealerStyle style) {
        SplittableRandom random = new SplittableRandom(42);
        CardTable table = new CardTable(variant, style, random, null, new CardTable.Limits(1, 1, 16, false), 10_000_000);
        table.sit(0, "p");
        return table;
    }
}
