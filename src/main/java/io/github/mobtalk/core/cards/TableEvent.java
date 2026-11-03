package io.github.mobtalk.core.cards;

/** Something that happened at the table, in order, for the client to animate and the villager to talk about. {@code seat} is
 * {@link CardTable#DEALER} for the villager; {@code card} is null for a card dealt face down; {@code amount} is the emeralds
 * involved (a bet, a payout) or 0. */
public record TableEvent(Kind kind, int seat, int hand, Card card, int amount) {
    public enum Kind {
        /** The shoe was shuffled before this deal. */
        SHUFFLE,
        /** A stake went down or was raised (double, va-bank). */
        BET,
        /** A card dealt to a hand ({@code card} null: face down). */
        DEAL,
        /** The villager's hidden card turned over. */
        REVEAL,
        STAND, DOUBLE, SPLIT, VA_BANK,
        /** A hand went over 21. */
        BUST,
        /** Golden ochko or a blackjack. */
        NATURAL,
        /** The villager swapped a card from his sleeve: the client shows the tell and lights «Ловлю!». */
        CHEAT,
        /** A player caught him at it. */
        CAUGHT,
        /** A player accused him for nothing. */
        FALSE_ACCUSE,
        /** One hand settled: {@code amount} is what came back to the player (0 lost, the stake back on a push, more on a win). */
        SETTLE,
        /** The deal was called off and every stake went back. */
        CANCEL
    }

    static TableEvent of(Kind kind, int seat, int hand) { return new TableEvent(kind, seat, hand, null, 0); }
}
