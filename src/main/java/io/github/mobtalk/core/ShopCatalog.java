package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a city supermarket sells and for how many emeralds (vanilla items, so everything bought is of use): goods by section,
 * each sold in lots ("3 bread for 1 emerald"), and the sum of a basket. Pure Java; the game reads it through
 * io.github.mobtalk.game.ShopController. Prices are a little dearer than a farmer's trades: a supermarket is convenient, not cheap. */
public final class ShopCatalog {
    private ShopCatalog() { }

    /** Which display a product lies on: the gondola (grocery, sweets), the bread rack, the refrigerated case (dairy, sausage, cooked
     * fish), the chest freezer (raw meat and fish) or the produce stand (fruit and vegetables). */
    public enum Place { SHELF, BREAD, FRIDGE, FREEZER, PRODUCE }

    /** One product: the item's id, its section, the lot (items for the price) and the price in emeralds. */
    public record Goods(String item, String section, int lot, int price, Place place) { }

    private static final Map<String, Goods> BY_ITEM = new LinkedHashMap<>();
    private static void add(String section, Place place, String item, int lot, int price) {
        String id = item.contains(":") ? item : "minecraft:" + item;
        BY_ITEM.put(id, new Goods(id, section, lot, price, place));
    }
    static {
        Place s = Place.SHELF, f = Place.FREEZER, b = Place.BREAD, c = Place.FRIDGE, v = Place.PRODUCE;
        add("bread", b, "bread", 3, 1);
        add("bread", b, "cookie", 8, 1);
        add("bread", b, "pumpkin_pie", 2, 1);
        add("bread", b, "cake", 1, 3);
        add("dairy", c, "milk_bucket", 1, 2);
        add("dairy", c, "egg", 6, 1);
        add("dairy", c, "honey_bottle", 1, 1);
        add("meat", c, "cooked_beef", 2, 1);
        add("meat", c, "cooked_porkchop", 2, 1);
        add("meat", c, "cooked_chicken", 2, 1);
        add("meat", c, "cooked_mutton", 2, 1);
        add("fish", c, "cooked_cod", 3, 1);
        add("fish", c, "cooked_salmon", 2, 1);
        add("fish", c, "dried_kelp", 10, 1);
        add("veg", v, "apple", 4, 1);
        add("veg", v, "carrot", 6, 1);
        add("veg", v, "potato", 6, 1);
        add("veg", v, "baked_potato", 4, 1);
        add("veg", v, "beetroot", 6, 1);
        add("veg", v, "melon_slice", 8, 1);
        add("veg", v, "sweet_berries", 8, 1);
        add("veg", v, "pumpkin", 1, 1);
        add("grocery", s, "sugar", 8, 1);
        add("grocery", s, "wheat", 8, 1);
        add("grocery", s, "cocoa_beans", 4, 1);
        add("grocery", s, "mushroom_stew", 1, 1);
        add("grocery", s, "beetroot_soup", 1, 1);
        add("grocery", s, "rabbit_stew", 1, 2);
        add("grocery", s, "glow_berries", 4, 1);
        add("grocery", s, "golden_carrot", 2, 3);
        add("frozen", f, "beef", 3, 1);
        add("frozen", f, "porkchop", 3, 1);
        add("frozen", f, "chicken", 3, 1);
        add("frozen", f, "mutton", 3, 1);
        add("frozen", f, "cod", 4, 1);
        add("frozen", f, "salmon", 3, 1);
        add("frozen", f, "rabbit", 3, 1);
        // the shop's own groceries (io.github.mobtalk.game.ShopFoods)
        add("bread", b, "mobtalk:baton", 2, 1);
        add("bread", b, "mobtalk:borodinsky", 2, 1);
        add("bread", b, "mobtalk:sushki", 6, 1);
        add("bread", b, "mobtalk:pryanik", 4, 1);
        add("dairy", c, "mobtalk:milk_carton", 2, 1);
        add("dairy", c, "mobtalk:kefir", 2, 1);
        add("dairy", c, "mobtalk:smetana", 2, 1);
        add("dairy", c, "mobtalk:tvorog", 2, 1);
        add("dairy", c, "mobtalk:cheese", 3, 1);
        add("meat", c, "mobtalk:doktorskaya", 1, 1);
        add("meat", c, "mobtalk:sosiski", 2, 1);
        add("frozen", f, "mobtalk:pelmeni", 2, 1);
        add("frozen", f, "mobtalk:plombir", 3, 1);
        add("grocery", s, "mobtalk:sgushchenka", 2, 1);
        add("grocery", s, "mobtalk:chocolate", 3, 1);
        add("grocery", s, "mobtalk:semechki", 6, 1);
        add("grocery", s, "mobtalk:pickles", 1, 1);
        add("grocery", s, "mobtalk:kvass", 2, 1);
        add("grocery", s, "mobtalk:noodles", 3, 1);
        add("veg", v, "mobtalk:cucumber", 6, 1);
        add("veg", v, "mobtalk:tomato", 5, 1);
        add("veg", v, "mobtalk:cabbage", 2, 1);
        add("veg", v, "mobtalk:onion", 8, 1);
    }

    /** The product of this item id, null when the shop does not stock it (an admin may still put it on a shelf by hand). */
    public static Goods of(String item) { return BY_ITEM.get(item); }
    public static Collection<Goods> all() { return BY_ITEM.values(); }

    /** The goods of one place, in catalogue order. */
    public static List<Goods> at(Place place) {
        List<Goods> out = new ArrayList<>();
        for (Goods goods : BY_ITEM.values()) if (goods.place() == place) out.add(goods);
        return out;
    }

    /** A product for a shelf the template left unassigned: steady for the spot (the same seed gives the same product). */
    public static Goods pick(Place place, long seed) {
        List<Goods> list = at(place);
        return list.get((int) Math.floorMod(mix(seed), (long) list.size()));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    /** A price after the settings' percentage, never below one emerald. */
    public static int scaled(int price, int percent) { return Math.max(1, Math.round(price * Math.max(1, percent) / 100f)); }

    /** One line of a basket: so many items of a product taken in lots of {@code lot} at {@code price} emeralds a lot. */
    public record Line(int count, int lot, int price) { }

    /** What a basket costs: every line at its share of a lot's price, the total rounded up (half a lot is not free). */
    public static int total(List<Line> basket) {
        long numerator = 0, denominator = 1;
        for (Line line : basket) {
            long lot = Math.max(1, line.lot());
            // numerator/denominator + count*price/lot
            numerator = numerator * lot + (long) line.count() * line.price() * denominator;
            denominator *= lot;
            long g = gcd(numerator, denominator);
            if (g > 1) { numerator /= g; denominator /= g; }
        }
        return (int) ((numerator + denominator - 1) / denominator);
    }

    private static long gcd(long a, long b) { while (b != 0) { long t = a % b; a = b; b = t; } return Math.max(1, Math.abs(a)); }

    /** How to pay {@code cost} from {@code emeralds} loose and {@code blocks} emerald blocks: loose ones first, then as few blocks as
     * needed, change back in emeralds. Null when the money is short. */
    public record Payment(int emeralds, int blocks, int change) { }

    public static Payment pay(int cost, int emeralds, int blocks) {
        if (cost <= 0) return new Payment(0, 0, 0);
        if (emeralds >= cost) return new Payment(cost, 0, 0);
        int rest = cost - emeralds;
        int needBlocks = (rest + 8) / 9;
        if (needBlocks > blocks) return null;
        return new Payment(emeralds, needBlocks, needBlocks * 9 - rest);
    }

    /** All the money in emeralds. */
    public static int wealth(int emeralds, int blocks) { return emeralds + blocks * 9; }
}
