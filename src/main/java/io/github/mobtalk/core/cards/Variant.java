package io.github.mobtalk.core.cards;

import java.util.ArrayList;
import java.util.List;

/** The two games one table can run. «Очко» is the Russian yard game of 21: a 36-card deck shuffled every deal, picture cards worth
 * 2, 3 and 4, an ace always 11, two aces at once (golden ochko) beat everything; the banker draws by his own judgement. Blackjack is
 * the casino game: two 52-card decks, pictures worth 10, an ace 11 or 1, a two-card 21 pays 3:2, the dealer draws to 16 and stands on
 * every 17. Docs: the design doc «Игра в 21 с жителем», section «Правила». */
public enum Variant {
    OCHKO("Очко", 1), BLACKJACK("Блэкджек", 2);

    /** The name the table and F7 print. */
    public final String title;
    /** How many full decks go into the shoe. */
    public final int decks;
    Variant(String title, int decks) { this.title = title; this.decks = decks; }

    /** Every card of the shoe, in order (the shoe shuffles them). */
    public List<Card> deck() {
        List<Card> cards = new ArrayList<>();
        for (int copy = 0; copy < decks; copy++)
            for (Suit suit : Suit.values())
                for (Rank rank : Rank.values())
                    if (this == BLACKJACK || rank.ordinal() >= Rank.SIX.ordinal()) cards.add(new Card(rank, suit));
        return cards;
    }

    /** What one card adds, counting an ace as 11. */
    public int value(Rank rank) {
        if (this == BLACKJACK) return rank.blackjack;
        return switch (rank) {
            case JACK -> 2;
            case QUEEN -> 3;
            case KING -> 4;
            case ACE -> 11;
            default -> rank.blackjack;
        };
    }

    /** The points of a hand. In blackjack aces drop from 11 to 1 while the hand would go over 21, and {@code soft} says an ace is
     * still counted as 11. Golden ochko scores 21 here; {@link #golden} tells it apart. */
    public Score score(List<Card> cards) {
        if (golden(cards)) return new Score(21, false);
        int total = 0, softAces = 0;
        for (Card card : cards) {
            total += value(card.rank());
            if (card.rank() == Rank.ACE) softAces++;
        }
        if (this == BLACKJACK) while (total > 21 && softAces > 0) { total -= 10; softAces--; }
        return new Score(total, this == BLACKJACK && softAces > 0);
    }

    /** Two aces as the first two cards of an ochko hand: an instant win. */
    public boolean golden(List<Card> cards) {
        return this == OCHKO && cards.size() == 2 && cards.get(0).rank() == Rank.ACE && cards.get(1).rank() == Rank.ACE;
    }

    /** The best hand there is, made with the first two cards: golden ochko, or a blackjack (not after a split). */
    public boolean natural(List<Card> cards, boolean fromSplit) {
        if (this == OCHKO) return golden(cards);
        return !fromSplit && cards.size() == 2 && score(cards).total() == 21;
    }

    /** The saved name back to a variant; ochko for blank or unknown. */
    public static Variant of(String name) {
        if (name == null || name.isBlank()) return OCHKO;
        try { return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException unknown) { return OCHKO; }
    }

    /** Points and whether an ace is still counted high. */
    public record Score(int total, boolean soft) {
        public boolean bust() { return total > 21; }
    }
}
