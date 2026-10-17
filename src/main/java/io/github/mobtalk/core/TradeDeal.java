package io.github.mobtalk.core;

/** One thing a mob of this species sells, from trades.json. A secret deal is invisible until the mob opens it in conversation.
 * Counts are per exchange; {@code price} is what the player hands over, {@code sell} what the player gets. */
public record TradeDeal(String id, String name, Item sell, Item price, boolean secret, String hint,
                        int unlockRelationship, String unlockQuest, int maxPerDay) {
    /** An item stack by vanilla id; {@code name} is the Russian name the mob says aloud. */
    public record Item(String id, String name, int count) {
        public Item { if (count < 1) throw new IllegalArgumentException("count"); }
    }
    public TradeDeal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        if (sell == null || price == null) throw new IllegalArgumentException("items");
        hint = hint == null ? "" : hint;
        unlockQuest = unlockQuest == null ? "" : unlockQuest;
        maxPerDay = Math.max(0, maxPerDay);
    }
    /** The lowest price any bargaining may reach: 90% off, rounded down, never below one item. */
    public int floorPrice() { return Math.max(1, price.count() / 10); }
}
