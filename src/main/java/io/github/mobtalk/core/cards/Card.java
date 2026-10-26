package io.github.mobtalk.core.cards;

/** One playing card. On the wire a card is its {@link #code()}: 0..51, suit by suit, two to ace. */
public record Card(Rank rank, Suit suit) {
    public Card {
        if (rank == null || suit == null) throw new IllegalArgumentException("a card needs a rank and a suit");
    }

    public int code() { return suit.ordinal() * 13 + rank.ordinal(); }

    /** The card for a code, or null when the code is out of range (a face-down card is sent as -1). */
    public static Card of(int code) {
        if (code < 0 || code >= 52) return null;
        return new Card(Rank.values()[code % 13], Suit.values()[code / 13]);
    }

    /** «Т♥», «10♦». */
    public String label() { return rank.label + suit.symbol; }

    @Override public String toString() { return label(); }
}
