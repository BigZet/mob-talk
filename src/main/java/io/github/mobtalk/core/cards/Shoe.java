package io.github.mobtalk.core.cards;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/** The cards still to be dealt. It lives only on the server: no client ever sees what is left in it, only how many. Ochko reshuffles
 * the whole deck before every deal; blackjack reshuffles once half of the shoe is gone. The random source is the server's
 * {@code SecureRandom}, or a seeded one for the self-test and {@code /mobtalk cards deck <seed>}. */
public final class Shoe {
    private final Variant variant;
    private final RandomGenerator random;
    private final Deque<Card> cards = new ArrayDeque<>();
    private int full;

    public Shoe(Variant variant, RandomGenerator random) {
        this.variant = variant;
        this.random = random;
        shuffle();
    }

    /** A shoe dealt in exactly this order, then refilled by shuffling: for tests that need a known deal. */
    public static Shoe stacked(Variant variant, RandomGenerator random, List<Card> order) {
        Shoe shoe = new Shoe(variant, random);
        shoe.cards.clear();
        shoe.cards.addAll(order);
        shoe.full = order.size();
        return shoe;
    }

    /** All cards back in, in a fresh random order (Fisher–Yates). */
    public void shuffle() {
        List<Card> all = variant.deck();
        for (int i = all.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Card swap = all.get(i); all.set(i, all.get(j)); all.set(j, swap);
        }
        cards.clear();
        cards.addAll(all);
        full = all.size();
    }

    /** Whether the next deal should start from a fresh shoe. */
    public boolean wantsShuffle() {
        return variant == Variant.OCHKO ? cards.size() < full : cards.size() < full / 2;
    }

    /** The top card; an empty shoe is reshuffled first (never happens with four seats, but a deal must not fail). */
    public Card draw() {
        if (cards.isEmpty()) shuffle();
        return cards.pop();
    }

    /** What the top card would be, without dealing it. */
    public Card peek() {
        if (cards.isEmpty()) shuffle();
        return cards.peek();
    }

    /** Pulls the first card that fits out of the shoe (the trickster's sleeve), or null when none does. */
    public Card take(Predicate<Card> fits) {
        for (Iterator<Card> it = cards.iterator(); it.hasNext(); ) {
            Card card = it.next();
            if (fits.test(card)) { it.remove(); return card; }
        }
        return null;
    }

    public int remaining() { return cards.size(); }

    /** A copy of what is left, top first: for tests only. */
    List<Card> left() { return new ArrayList<>(cards); }
}
