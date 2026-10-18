package io.github.mobtalk.core;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Typed reading of the trading data a snapshot carries: {@code mobDetails.trading} (the mob's own deals and favours, shaped by
 * {@link Trading#view}) and {@code mobDetails.bargaining} (the villager's vanilla window). Prompt and validator read through the
 * same accessors, so both agree on what the model was shown. */
record TradingView(MobSnapshot snapshot) {
    static TradingView of(MobSnapshot snapshot) { return new TradingView(snapshot); }

    // ---- The mob's own catalog
    /** The Trading.view map, or null when the species trades nothing. */
    Map<?,?> trading() { return snapshot.mobDetails().get("trading") instanceof Map<?,?> map && !map.isEmpty() ? map : null; }
    List<?> deals() { return items(trading(), "deals"); }
    List<?> secrets() { return items(trading(), "secret"); }
    Map<?,?> favor() { Map<?,?> trading = trading(); return trading != null && trading.get("favor") instanceof Map<?,?> map ? map : null; }
    Map<?,?> activeFavor() { Map<?,?> favor = favor(); return favor != null && favor.get("active") instanceof Map<?,?> map ? map : null; }
    List<?> candidates() { return items(favor(), "candidates"); }
    boolean ownDeals() { return !deals().isEmpty(); }
    boolean ownTrading() { return ownDeals() || !secrets().isEmpty(); }
    boolean sellsAnything() { return ownTrading() || villagerOffers(); }
    /** A deal of the mob's own catalog by id, or null. */
    Map<?,?> ownDeal(String id) { return entry(deals(), id); }
    Map<?,?> secret(String id) { return entry(secrets(), id); }
    boolean ownWindowOpen() { Map<?,?> trading = trading(); return trading != null && Boolean.TRUE.equals(trading.get("windowOpen")); }
    /** The deal row the player looks at in the mob's own window, or null. */
    Map<?,?> selectedOwnDeal() { Map<?,?> trading = trading(); return trading != null && trading.get("selectedDeal") instanceof String id && !id.isBlank() ? ownDeal(id) : null; }

    List<String> permittedDealTypes() {
        Map<?,?> trading = trading();
        if (trading == null || deals().isEmpty()) return List.of("NONE");
        if (trading.get("allowedDealTypes") instanceof List<?> allowed)
            return Arrays.stream(DealIntent.Type.values()).map(Enum::name).filter(value -> "NONE".equals(value) || allowed.contains(value)).toList();
        return List.of("NONE", "OFFER", "DECLINE");
    }
    List<String> permittedQuestTypes() {
        Map<?,?> favor = favor();
        List<String> types = new java.util.ArrayList<>(List.of("NONE"));
        if (favor != null && Boolean.TRUE.equals(favor.get("canAsk"))) types.add("OFFER");
        if (activeFavor() != null) types.add("COMPLETE");
        return List.copyOf(types);
    }

    // ---- The villager's vanilla window
    boolean villager() { return "minecraft:villager".equals(snapshot.type()); }
    boolean villagerOffers() { return villager() && snapshot.mobDetails().get("offers") instanceof List<?> offers && !offers.isEmpty(); }
    List<?> villagerOfferRows() { return snapshot.mobDetails().get("offers") instanceof List<?> offers ? offers : List.of(); }
    Map<?,?> bargaining() { return snapshot.mobDetails().get("bargaining") instanceof Map<?,?> context ? context : null; }
    boolean windowOpen() { return villager() && snapshot.playerId() != null && bargaining() != null && Boolean.TRUE.equals(bargaining().get("open")); }
    List<String> permittedBargainTypes() {
        if (!windowOpen()) return List.of("NONE");
        if (bargaining().get("allowedTypes") instanceof List<?> allowed)
            return Arrays.stream(BargainIntent.Type.values()).map(Enum::name).filter(value -> "NONE".equals(value) || allowed.contains(value)).toList();
        // Older captures may not expose the server transition list; semantic checks still validate prices.
        return List.of("NONE", "OFFER", "ACCEPT", "DECLINE");
    }

    // ---- Map helpers shared by prompt and validator
    static List<?> items(Map<?,?> source, String field) { return source != null && source.get(field) instanceof List<?> list ? list : List.of(); }
    static Map<?,?> entry(List<?> list, String id) {
        if (id == null) return null;
        for (Object value : list) if (value instanceof Map<?,?> item && id.equals(item.get("id"))) return item;
        return null;
    }
    static List<String> ids(List<?> list) {
        return list.stream().filter(value -> value instanceof Map<?,?> item && item.get("id") != null).map(value -> String.valueOf(((Map<?,?>) value).get("id"))).toList();
    }
    static boolean allowedPrice(Map<?,?> context, int price) {
        return price >= 1 && context.get("allowedPrices") instanceof List<?> prices && prices.stream().anyMatch(value -> integer(value) == price);
    }
    static int integer(Object value) { return Math.toIntExact(wholeNumber(value)); }
    static long wholeNumber(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException();
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }
}
