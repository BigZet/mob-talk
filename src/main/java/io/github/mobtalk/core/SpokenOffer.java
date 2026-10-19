package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A deal a villager promised in conversation with its trade window shut: one of its vanilla rows at a lower price, good for one
 * purchase and {@link #LIFETIME_TICKS} of world time. The game layer appends a real {@code TradeOffer} to the villager's list and
 * takes it away again when it is bought, expires, or the villager unloads; the offer never reaches the saved world.
 * Pure rules: which prices may be promised, how many times the promise may be lowered, when it ends. {@code offerIndex} and
 * {@code key} name the vanilla row the promise is built on (the same index the model sees in {@code mobDetails.offers}). */
public record SpokenOffer(int offerIndex, String key, int vanillaCount, int price, int rounds, long createdTick, long expiresTick, boolean bought) {
    /** Ten minutes of world time (20 ticks a second). */
    public static final long LIFETIME_TICKS = 12_000;
    /** How many times one promise may be lowered before the villager stops haggling. */
    public static final int MAX_ROUNDS = Bargaining.MAX_ROUNDS;
    /** The bargain types the model may use with the window shut: no pending quote exists, so ACCEPT has nothing to accept. */
    public static final List<String> ALLOWED_TYPES = List.of("NONE", "OFFER", "DECLINE");

    public SpokenOffer {
        if (offerIndex < 0 || price < 1 || vanillaCount < 1) throw new IllegalArgumentException("offer");
        key = key == null ? "" : key;
    }

    /** Still standing in the window: not bought, not run out. */
    public boolean active(long tick) { return !bought && tick < expiresTick; }
    public boolean expired(long tick) { return !bought && tick >= expiresTick; }
    /** Whole minutes left, at least one while active. */
    public long minutesLeft(long tick) { return Math.max(1, (expiresTick - tick + 1199) / 1200); }
    public SpokenOffer asBought() { return new SpokenOffer(offerIndex, key, vanillaCount, price, rounds, createdTick, expiresTick, true); }
    /** Whether this promise concerns that very row (same index and the same items). */
    public boolean sameRow(int index, String rowKey) { return offerIndex == index && key.equals(rowKey == null ? "" : rowKey); }

    /** The prices the villager may promise for {@code row} now, highest first: below what the row charges this player at the moment
     * ({@code currentPrice}: the permanently bargained price or the vanilla one — or the standing promise on this same row), at most
     * halving it, never under {@link Bargaining#floorPrice}. Empty when the relationship forbids it, the row is sold out, or the
     * standing promise on this row has been lowered {@link #MAX_ROUNDS} times already. */
    public static List<Integer> allowedPrices(SpokenOffer standing, Bargaining.Offer row, int relationship, int currentPrice, long tick) {
        if (row == null || !row.available() || row.vanillaCount() < 1 || relationship <= Bargaining.HOSTILE_RELATIONSHIP) return List.of();
        int from = currentPrice;
        if (standing != null && standing.active(tick) && standing.sameRow(row.index(), row.key())) {
            if (standing.rounds() >= MAX_ROUNDS) return List.of();
            from = Math.min(from, standing.price());
        }
        int lowest = Math.max(Bargaining.floorPrice(row), from / 2);
        List<Integer> prices = new ArrayList<>();
        for (int price = from - 1; price >= lowest; price--) prices.add(price);
        return List.copyOf(prices);
    }

    /** The promise after the villager named {@code price} for {@code row}: a lower price on the standing promise counts as another round,
     * any other row replaces it. Returns null when the price is not one of {@link #allowedPrices}. */
    public static SpokenOffer promise(SpokenOffer standing, Bargaining.Offer row, int relationship, int currentPrice, int price, long tick) {
        if (!allowedPrices(standing, row, relationship, currentPrice, tick).contains(price)) return null;
        boolean same = standing != null && standing.active(tick) && standing.sameRow(row.index(), row.key());
        return new SpokenOffer(row.index(), row.key(), row.vanillaCount(), price, same ? standing.rounds() + 1 : 1,
                same ? standing.createdTick() : tick, tick + LIFETIME_TICKS, false);
    }

    // ---- What the model reads and what it may answer: {@code mobDetails.bargaining.spoken} is built by the game layer from the
    // rows below; the validator and the prompt read it back through these helpers, so both agree.

    /** One row of {@code spoken.offers}: the vanilla row by index with the prices the villager may promise for it. */
    public static Map<String, Object> row(int index, String key, Map<String, Object> firstBuy, Map<String, Object> secondBuy, Map<String, Object> sell,
                                          int currentPrice, List<Integer> allowed, boolean promised) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("index", index); result.put("key", key);
        result.put("firstBuy", firstBuy); result.put("secondBuy", secondBuy); result.put("sell", sell);
        result.put("currentPrice", currentPrice);
        result.put("allowedPrices", List.copyOf(allowed));
        result.put("minimumPrice", allowed.isEmpty() ? currentPrice : allowed.getLast());
        result.put("canOffer", !allowed.isEmpty());
        result.put("promised", promised);
        return Map.copyOf(result);
    }

    /** The whole {@code spoken} block: the rows, the standing promise (or null) and the types the model may answer with. */
    public static Map<String, Object> block(List<Map<String, Object>> rows, SpokenOffer standing, long tick, String reason) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        boolean canOffer = rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("canOffer")));
        result.put("available", true);
        result.put("offers", List.copyOf(rows));
        if (standing != null && standing.active(tick)) {
            Map<String, Object> active = new java.util.LinkedHashMap<>();
            active.put("offerIndex", standing.offerIndex()); active.put("price", standing.price());
            active.put("rounds", standing.rounds()); active.put("minutesLeft", standing.minutesLeft(tick));
            result.put("active", Map.copyOf(active));
        }
        List<String> types = new ArrayList<>(List.of("NONE"));
        if (canOffer) types.add("OFFER");
        types.add("DECLINE");
        result.put("allowedTypes", List.copyOf(types));
        result.put("reason", reason == null ? "" : reason);
        return Map.copyOf(result);
    }

    /** The row of the block by index, or null. */
    public static Map<?,?> row(Map<?,?> spoken, int index) {
        if (spoken == null || !(spoken.get("offers") instanceof List<?> rows)) return null;
        for (Object value : rows) if (value instanceof Map<?,?> row && row.get("index") instanceof Number n && n.intValue() == index) return row;
        return null;
    }
    public static List<String> allowedTypes(Map<?,?> spoken) {
        if (spoken == null || !(spoken.get("allowedTypes") instanceof List<?> allowed)) return List.of("NONE");
        return allowed.stream().map(String::valueOf).toList();
    }

    /** The model's bargain with the window shut: OFFER on a listed row at one of its allowedPrices becomes a {@link BargainIntent}
     * whose discount counts from that row's currentPrice (so {@code currentPrice - discount} is the promised price); DECLINE is
     * always allowed; anything else is NONE. */
    public static BargainIntent validate(Map<?,?> spoken, String type, int offerIndex, int price) {
        if (spoken == null || type == null) return BargainIntent.none();
        String upper = type.toUpperCase(java.util.Locale.ROOT);
        if ("DECLINE".equals(upper)) return new BargainIntent(BargainIntent.Type.DECLINE, Math.max(-1, offerIndex), 0);
        if (!"OFFER".equals(upper) || !allowedTypes(spoken).contains("OFFER")) return BargainIntent.none();
        Map<?,?> row = row(spoken, offerIndex);
        if (row == null || !Boolean.TRUE.equals(row.get("canOffer")) || !TradingView.allowedPrice(row, price)) return BargainIntent.none();
        int current = TradingView.integer(row.get("currentPrice"));
        return new BargainIntent(BargainIntent.Type.OFFER, offerIndex, current - price);
    }

    /** Why an OFFER was dropped, for the correction the model receives. */
    public static String rejection(Map<?,?> spoken, int offerIndex, Object price) {
        Map<?,?> row = row(spoken, offerIndex);
        if (row == null) return "Строки " + offerIndex + " в твоих товарах нет: offerIndex — номер строки из bargaining.spoken.offers. Если игрок хочет то, чего у тебя нет, скажи это по характеру (bargain NONE).";
        if (!Boolean.TRUE.equals(row.get("canOffer"))) return "По строке " + offerIndex + " уступить сейчас нельзя (цена " + row.get("currentPrice") + "): верни NONE или DECLINE и не обещай скидку.";
        return "Цена " + price + " по строке " + offerIndex + " недопустима: разрешены только allowedPrices " + row.get("allowedPrices")
                + " (сейчас " + row.get("currentPrice") + ", не ниже " + row.get("minimumPrice") + "). Назови одну из них через OFFER или откажи через DECLINE.";
    }

    /** Numbers the villager may say aloud without the window contradicting it: the promised price, every row's current price. */
    public static List<Integer> honestPrices(Map<?,?> spoken, BargainIntent bargain) {
        List<Integer> prices = new ArrayList<>();
        if (spoken == null) return prices;
        if (spoken.get("offers") instanceof List<?> rows) for (Object value : rows) if (value instanceof Map<?,?> row) {
            if (row.get("currentPrice") instanceof Number current) {
                prices.add(current.intValue());
                if (bargain != null && bargain.type() == BargainIntent.Type.OFFER && row.get("index") instanceof Number index && index.intValue() == bargain.offerIndex())
                    prices.add(current.intValue() - bargain.discount());
            }
            for (String field : List.of("firstBuy", "secondBuy", "sell"))
                if (row.get(field) instanceof Map<?,?> item && item.get("count") instanceof Number count) prices.add(count.intValue());
        }
        if (spoken.get("active") instanceof Map<?,?> active && active.get("price") instanceof Number price) prices.add(price.intValue());
        return prices;
    }

    /** The plain-words summary the model reads under the snapshot: every row with the prices it may promise. */
    public static String summary(Map<?,?> spoken) {
        if (spoken == null || !(spoken.get("offers") instanceof List<?> rows) || rows.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n\nТорг словами (окно торговли закрыто). Твои строки и что по ним можно пообещать:");
        for (Object value : rows) if (value instanceof Map<?,?> row) {
            String unit = row.get("firstBuy") instanceof Map<?,?> first ? String.valueOf(first.get("name")) : "предмет";
            String result = row.get("sell") instanceof Map<?,?> sell ? sell.get("count") + "× " + sell.get("name") : "товар";
            String second = row.get("secondBuy") instanceof Map<?,?> extra && extra.get("count") instanceof Number count && count.intValue() > 0
                    ? " + " + count + "× " + extra.get("name") : "";
            text.append("\n— строка ").append(row.get("index")).append(": ").append(row.get("currentPrice")).append("× ").append(unit).append(second)
                    .append(" → ").append(result);
            if (Boolean.TRUE.equals(row.get("promised"))) text.append(" — по ней уже стоит твоё обещание");
            if (Boolean.TRUE.equals(row.get("canOffer")) && row.get("allowedPrices") instanceof List<?> allowed && !allowed.isEmpty())
                text.append("; пообещать можно ").append(allowed.size() == 1 ? "только " + allowed.getFirst() : "от " + allowed.getLast() + " до " + allowed.getFirst())
                        .append("× ").append(unit).append(" — только через bargain OFFER (offerIndex ").append(row.get("index")).append(", price — названное число)");
            else text.append("; новую цену по ней назвать нельзя");
            text.append('.');
        }
        if (spoken.get("active") instanceof Map<?,?> active)
            text.append("\n— Твоё обещание уже ждёт игрока в окне торговли: строка ").append(active.get("offerIndex")).append(" за ").append(active.get("price"))
                    .append(", ещё ").append(active.get("minutesLeft")).append(" мин, на одну покупку. Напомни о нём, если к слову; снизить можно только ещё ниже.");
        text.append("\nОбещанная цена появляется отдельной строкой в твоём окне торговли (игрок открывает его сам правым кликом) на 10 минут и на одну покупку. ")
            .append("Сколько уступать — по характеру и отношению, не обязательно сразу минимум.");
        return text.toString();
    }
}
