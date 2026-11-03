package io.github.mobtalk.core.cards;

/** The four suits, in the order their codes run ({@link Card#code()}). */
public enum Suit {
    SPADES("♠", false), HEARTS("♥", true), DIAMONDS("♦", true), CLUBS("♣", false);

    /** The sign printed on the card and in the debug line. */
    public final String symbol;
    /** Hearts and diamonds are printed red. */
    public final boolean red;
    Suit(String symbol, boolean red) { this.symbol = symbol; this.red = red; }
}
