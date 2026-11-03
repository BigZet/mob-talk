package io.github.mobtalk.core.cards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One hand on the table: its cards, the emeralds staked on it and how it stands. A seat has one hand, two after a split. */
public final class Hand {
    final List<Card> cards = new ArrayList<>();
    int bet;
    boolean stood, doubled, fromSplit, vaBank;

    Hand(int bet) { this.bet = bet; }

    public List<Card> cards() { return Collections.unmodifiableList(cards); }
    public int bet() { return bet; }
    public boolean stood() { return stood; }
    public boolean doubled() { return doubled; }
    public boolean fromSplit() { return fromSplit; }
    public boolean vaBank() { return vaBank; }

    public Variant.Score score(Variant variant) { return variant.score(cards); }
    public boolean bust(Variant variant) { return score(variant).bust(); }
    public boolean natural(Variant variant) { return variant.natural(cards, fromSplit); }
    /** Nothing more to decide on this hand. */
    public boolean done(Variant variant) { return stood || bust(variant); }

    /** The most this hand can take from the bank: a blackjack pays one and a half (rounded down), anything else the stake. Before
     * the deal a blackjack hand is counted as a possible natural, rounded up, so the bank never promises more than it holds. */
    int maxWin(Variant variant) {
        if (variant != Variant.BLACKJACK) return bet;
        if (cards.size() < 2 && !fromSplit) return (bet * 3 + 1) / 2;
        return natural(variant) ? bet * 3 / 2 : bet;
    }
}
