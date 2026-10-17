package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;

/** Pure rules for a mob's own deals (trades.json) and quests. Nothing here touches Minecraft or disk.
 *
 * <p>Price rules, for every species: one OFFER may at most halve the price currently charged (half rounded down);
 * all rounds together never go below {@link TradeDeal#floorPrice()} (10% of the catalog price); a negotiated price is
 * permanent for this player and this mob — it never comes back up and later rounds may lower it further.
 * A bargaining session allows {@link #MAX_ROUNDS} OFFERs; {@link #SESSION_MILLIS} after its first OFFER a new session may start.
 * At or below {@link #HOSTILE_RELATIONSHIP} the mob refuses any concession.
 *
 * <p>Secret deals: hidden from the player's trade screen and from the mob's "deals" list until unlocked. The mob may unlock one
 * in conversation ({@code unlock} field of the answer) only when the player's relationship reaches {@code unlockRelationship}
 * and, if {@code unlockQuest} is set, that quest is DONE. Quest rewards may unlock a deal directly.
 *
 * <p>{@link #view} builds the map the model sees as {@code mobDetails.trading}; the LLM client validates answers against the
 * same keys, so both sides must agree on this shape:
 * <pre>
 * {
 *   "deals": [ { "id", "name", "sell": {"id","name","count"}, "price": {"id","name","count"},
 *                "basePrice": int, "currentPrice": int, "minPrice": int, "maxDiscountNow": int,
 *                "allowedPrices": [int...], "canOffer": bool, "reason": str, "stockLeft": int (-1 = unlimited) } ... ],   // visible deals only
 *   "secret": [ { "id", "hint", "unlockRelationship": int, "unlockQuest": str, "canUnlockNow": bool, "reason": str } ... ], // locked secret deals
 *   "allowedDealTypes": ["NONE","OFFER","DECLINE"],   // OFFER present only when some visible deal has canOffer
 *   "quests": { "available": [ { "id", "title", "kind", "target", "targetName", "count", "pitch",
 *                                 "reward": {"relationship","unlockDeal","discountDeal","discountPercent"} } ... ],
 *               "active":    [ { "id", "title", "kind", "target", "count", "progress", "canComplete": bool } ... ],
 *               "done":      [ "id" ... ] }
 * }
 * </pre>
 * A species with neither deals nor quests gets an empty map (the LLM client then says the mob trades nothing). */
public final class Trading {
    public static final int MAX_ROUNDS = 3;
    public static final long SESSION_MILLIS = 20 * 60_000L;
    public static final int HOSTILE_RELATIONSHIP = -40;
    private Trading() { }

    /** The price this player pays now: the negotiated one, else the catalog price. */
    public static int currentPrice(TradeDeal deal, TradeState state) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** Largest discount from currentPrice the next OFFER may set now, or 0 when no concession is allowed. */
    public static int maximumDiscount(TradeDeal deal, TradeState state, int relationship, long nowMillis) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** Whether a negotiated price is acceptable for this player: within one halving of the current price and not below floor. */
    public static boolean permitted(TradeDeal deal, TradeState state, int relationship, int price, long nowMillis) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** The state after an accepted OFFER at {@code price}; starts a new session when the previous one ended. */
    public static TradeState offered(TradeState state, int price, long nowMillis) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** Whether the player may see and buy this deal now. */
    public static boolean visible(TradeDeal deal, TradeState state) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** Whether the mob may open this secret deal for the player right now. */
    public static boolean canUnlock(TradeDeal deal, TradeState state, int relationship, Map<String, QuestState> quests) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** Exchanges left today, or -1 when unlimited. */
    public static int stockLeft(TradeDeal deal, TradeState state, long worldDay) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** The state after one purchase on {@code worldDay}. */
    public static TradeState bought(TradeState state, long worldDay) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** Whether the active quest can be completed with what the player has: BRING needs {@code inventory} (item id → count),
     * KILL needs progress ≥ count. */
    public static boolean canComplete(QuestDefinition quest, QuestState state, Map<String, Integer> inventory) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** The model's view described in the class comment. {@code states}/{@code quests} may be empty maps, never null;
     * {@code itemNames} maps item ids to display names when the game knows them (else the catalog name is used). */
    public static Map<String, Object> view(String type, Map<String, TradeState> states, Map<String, QuestState> quests,
                                           int relationship, long nowMillis, long worldDay, Map<String, Integer> inventory,
                                           Map<String, String> itemNames) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
    /** Visible deals for a trade screen, with the player's current price for each. */
    public record Priced(TradeDeal deal, int currentPrice, int stockLeft) { }
    public static List<Priced> priced(String type, Map<String, TradeState> states, long worldDay) {
        throw new UnsupportedOperationException("implemented by the core agent");
    }
}
