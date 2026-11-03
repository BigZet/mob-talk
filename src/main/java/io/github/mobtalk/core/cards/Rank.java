package io.github.mobtalk.core.cards;

/** Card ranks from two to ace; what a rank is worth depends on the game ({@link Variant#value(Rank)}). Labels are the Russian
 * ones the table prints: В(алет), Д(ама), К(ороль), Т(уз). */
public enum Rank {
    TWO("2", 2), THREE("3", 3), FOUR("4", 4), FIVE("5", 5), SIX("6", 6), SEVEN("7", 7), EIGHT("8", 8), NINE("9", 9), TEN("10", 10),
    JACK("В", 10), QUEEN("Д", 10), KING("К", 10), ACE("Т", 11);

    public final String label;
    /** The blackjack value; an ace is 11 here and drops to 1 in {@link Variant#score}. */
    final int blackjack;
    Rank(String label, int blackjack) { this.label = label; this.blackjack = blackjack; }
}
