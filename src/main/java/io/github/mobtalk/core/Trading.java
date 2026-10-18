package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *   "allowedDealTypes": ["NONE","OFFER","DECLINE"],   // OFFER present only when some visible deal has canOffer; DECLINE only with a visible deal
 *   "quests": { "available": [ { "id", "title", "kind", "target", "targetName", "count", "pitch",
 *                                 "reward": {"relationship","unlockDeal","discountDeal","discountPercent"} } ... ],
 *               "active":    [ { "id", "title", "kind", "target", "targetName", "count", "progress", "canComplete": bool } ... ],
 *               "done":      [ "id" ... ] }
 * }
 * </pre>
 * {@code reason} is "" when the move is possible, else a Russian explanation the mob may voice. {@code allowedPrices} lists every
 * price the next OFFER may set. For an active BRING quest {@code progress} is how many target items the player carries now.
 * A species with neither deals nor quests gets an empty map (the LLM client then says the mob trades nothing). */
public final class Trading {
    public static final int MAX_ROUNDS = 3;
    public static final long SESSION_MILLIS = 20 * 60_000L;
    public static final int HOSTILE_RELATIONSHIP = -40;
    private static volatile java.util.function.Predicate<String> itemChecker = id -> true;
    private Trading() { }

    /** The game layer installs a registry lookup so a favour can only ask for items that exist. */
    public static void setItemChecker(java.util.function.Predicate<String> checker) { itemChecker = checker == null ? id -> true : checker; }
    /** Whether {@code id} is a well-formed item id that the game knows (shape only until the game installs its check). */
    public static boolean itemExists(String id) {
        return id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && itemChecker.test(id);
    }

    /** The price this player pays now: the negotiated one, else the catalog price. */
    public static int currentPrice(TradeDeal deal, TradeState state) {
        int base = deal.price().count();
        if (state == null || state.price() <= 0) return base;
        return Math.max(deal.floorPrice(), Math.min(base, state.price()));
    }
    /** Largest discount from currentPrice the next OFFER may set now, or 0 when no concession is allowed. */
    public static int maximumDiscount(TradeDeal deal, TradeState state, int relationship, long nowMillis) {
        if (relationship <= HOSTILE_RELATIONSHIP || roundsExhausted(state, nowMillis)) return 0;
        return priceDiscount(deal, state);
    }
    /** The halving-and-floor limit alone, ignoring relationship and session. */
    private static int priceDiscount(TradeDeal deal, TradeState state) {
        int current = currentPrice(deal, state);
        return Math.max(0, current - Math.max(deal.floorPrice(), current / 2));
    }
    /** Whether a negotiated price is acceptable for this player: within one halving of the current price and not below floor. */
    public static boolean permitted(TradeDeal deal, TradeState state, int relationship, int price, long nowMillis) {
        int maximum = maximumDiscount(deal, state, relationship, nowMillis);
        int current = currentPrice(deal, state);
        return maximum > 0 && price >= current - maximum && price < current;
    }
    /** The state after an accepted OFFER at {@code price}; starts a new session when the previous one ended. */
    public static TradeState offered(TradeState state, int price, long nowMillis) {
        if (state == null) state = TradeState.initial();
        boolean continuing = sessionActive(state, nowMillis);
        return new TradeState(price, state.unlocked(), continuing ? state.rounds() + 1 : 1,
                continuing ? state.sessionStartMillis() : nowMillis, state.boughtToday(), state.boughtDay());
    }
    private static boolean sessionActive(TradeState state, long nowMillis) {
        return state != null && state.rounds() > 0 && state.sessionStartMillis() >= 0 && nowMillis - state.sessionStartMillis() < SESSION_MILLIS;
    }
    private static boolean roundsExhausted(TradeState state, long nowMillis) { return sessionActive(state, nowMillis) && state.rounds() >= MAX_ROUNDS; }
    /** Whole minutes until a new session may start, at least one. */
    private static long minutesLeft(TradeState state, long nowMillis) {
        return Math.max(1, (state.sessionStartMillis() + SESSION_MILLIS - nowMillis + 59_999) / 60_000);
    }
    /** Whether the player may see and buy this deal now. */
    public static boolean visible(TradeDeal deal, TradeState state) { return !deal.secret() || state != null && state.unlocked(); }
    /** Whether the mob may open this secret deal for the player right now. */
    public static boolean canUnlock(TradeDeal deal, TradeState state, int relationship, Map<String, QuestState> quests) {
        return deal.secret() && !(state != null && state.unlocked()) && relationship >= deal.unlockRelationship()
                && (deal.unlockQuest().isEmpty() || done(quests, deal.unlockQuest()));
    }
    private static boolean done(Map<String, QuestState> quests, String questId) {
        QuestState state = quests == null ? null : quests.get(questId);
        return state != null && state.status() == QuestState.Status.DONE;
    }
    /** Exchanges left today, or -1 when unlimited. */
    public static int stockLeft(TradeDeal deal, TradeState state, long worldDay) {
        if (deal.maxPerDay() <= 0) return -1;
        if (state == null || state.boughtDay() != worldDay) return deal.maxPerDay();
        return Math.max(0, deal.maxPerDay() - state.boughtToday());
    }
    /** The state after one purchase on {@code worldDay}. */
    public static TradeState bought(TradeState state, long worldDay) {
        if (state == null) state = TradeState.initial();
        int today = state.boughtDay() == worldDay ? state.boughtToday() + 1 : 1;
        return new TradeState(state.price(), state.unlocked(), state.rounds(), state.sessionStartMillis(), today, worldDay);
    }
    /** Whether the active quest can be completed with what the player has: BRING needs {@code inventory} (item id → count),
     * KILL needs progress ≥ count. A quest that is not ACTIVE can never be completed. */
    public static boolean canComplete(QuestDefinition quest, QuestState state, Map<String, Integer> inventory) {
        if (state == null || state.status() != QuestState.Status.ACTIVE) return false;
        return quest.kind() == QuestDefinition.Kind.KILL ? state.progress() >= quest.count() : carried(quest, inventory) >= quest.count();
    }
    private static int carried(QuestDefinition quest, Map<String, Integer> inventory) {
        Integer count = inventory == null ? null : inventory.get(quest.target());
        return count == null ? 0 : count;
    }
    /** The model's view described in the class comment. {@code states}/{@code quests} may be empty maps, never null;
     * {@code itemNames} maps item ids to display names when the game knows them (else the catalog name is used). */
    public static Map<String, Object> view(String type, Map<String, TradeState> states, Map<String, QuestState> quests,
                                           int relationship, long nowMillis, long worldDay, Map<String, Integer> inventory,
                                           Map<String, String> itemNames) {
        List<TradeDeal> deals = TradeCatalog.deals(type);
        List<QuestDefinition> definitions = TradeCatalog.quests(type);
        if (deals.isEmpty() && definitions.isEmpty()) return Map.of();
        if (states == null) states = Map.of();
        if (quests == null) quests = Map.of();
        if (itemNames == null) itemNames = Map.of();
        List<Map<String, Object>> visible = new ArrayList<>(), secret = new ArrayList<>();
        boolean anyOffer = false;
        for (TradeDeal deal : deals) {
            TradeState state = states.getOrDefault(deal.id(), TradeState.initial());
            if (!visible(deal, state)) { secret.add(secretView(deal, state, relationship, quests)); continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            int current = currentPrice(deal, state), maximum = maximumDiscount(deal, state, relationship, nowMillis);
            List<Integer> allowed = new ArrayList<>();
            for (int price = current - maximum; price < current; price++) allowed.add(price);
            row.put("id", deal.id()); row.put("name", deal.name());
            row.put("sell", item(deal.sell(), itemNames)); row.put("price", item(deal.price(), itemNames));
            row.put("basePrice", deal.price().count()); row.put("currentPrice", current); row.put("minPrice", deal.floorPrice());
            row.put("maxDiscountNow", maximum); row.put("allowedPrices", allowed); row.put("canOffer", maximum > 0);
            row.put("reason", maximum > 0 ? "" : offerReason(deal, state, relationship, nowMillis));
            row.put("stockLeft", stockLeft(deal, state, worldDay));
            anyOffer |= maximum > 0;
            visible.add(row);
        }
        List<String> types = new ArrayList<>(List.of(DealIntent.Type.NONE.name()));
        if (anyOffer) types.add(DealIntent.Type.OFFER.name());
        if (!visible.isEmpty()) types.add(DealIntent.Type.DECLINE.name());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deals", visible); result.put("secret", secret); result.put("allowedDealTypes", types);
        result.put("quests", questsView(definitions, quests, inventory, itemNames));
        return result;
    }
    private static Map<String, Object> item(TradeDeal.Item item, Map<String, String> itemNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.id()); row.put("name", itemNames.getOrDefault(item.id(), item.name())); row.put("count", item.count());
        return row;
    }
    private static String offerReason(TradeDeal deal, TradeState state, int relationship, long nowMillis) {
        if (relationship <= HOSTILE_RELATIONSHIP) return "торг с этим игроком закрыт: отношение слишком плохое";
        if (priceDiscount(deal, state) == 0) return "цена уже на минимуме";
        if (roundsExhausted(state, nowMillis)) return "три уступки подряд уже сделаны, следующий торг через " + minutesLeft(state, nowMillis) + " мин";
        return "";
    }
    private static Map<String, Object> secretView(TradeDeal deal, TradeState state, int relationship, Map<String, QuestState> quests) {
        Map<String, Object> row = new LinkedHashMap<>();
        boolean can = canUnlock(deal, state, relationship, quests);
        List<String> blockers = new ArrayList<>();
        if (relationship < deal.unlockRelationship()) blockers.add("нужно отношение не ниже " + deal.unlockRelationship() + " (сейчас " + relationship + ")");
        if (!deal.unlockQuest().isEmpty() && !done(quests, deal.unlockQuest())) blockers.add("сначала игрок должен выполнить задание " + deal.unlockQuest());
        row.put("id", deal.id()); row.put("hint", deal.hint()); row.put("unlockRelationship", deal.unlockRelationship());
        row.put("unlockQuest", deal.unlockQuest()); row.put("canUnlockNow", can);
        row.put("reason", can ? "" : String.join("; ", blockers));
        return row;
    }
    private static Map<String, Object> questsView(List<QuestDefinition> definitions, Map<String, QuestState> states,
                                                  Map<String, Integer> inventory, Map<String, String> itemNames) {
        List<Map<String, Object>> available = new ArrayList<>(), active = new ArrayList<>();
        List<String> done = new ArrayList<>();
        for (QuestDefinition quest : definitions) {
            QuestState state = states.get(quest.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", quest.id()); row.put("title", quest.title()); row.put("kind", quest.kind().name());
            row.put("target", quest.target()); row.put("targetName", itemNames.getOrDefault(quest.target(), quest.target()));
            row.put("count", quest.count());
            if (state == null) {
                row.put("pitch", quest.pitch());
                Map<String, Object> reward = new LinkedHashMap<>();
                reward.put("relationship", quest.reward().relationship()); reward.put("unlockDeal", quest.reward().unlockDeal());
                reward.put("discountDeal", quest.reward().discountDeal()); reward.put("discountPercent", quest.reward().discountPercent());
                row.put("reward", reward);
                available.add(row);
            } else if (state.status() == QuestState.Status.ACTIVE) {
                int progress = quest.kind() == QuestDefinition.Kind.KILL ? state.progress() : Math.min(quest.count(), carried(quest, inventory));
                row.put("progress", progress); row.put("canComplete", canComplete(quest, state, inventory));
                active.add(row);
            } else done.add(quest.id());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", active.isEmpty() ? available : List.of()); result.put("active", active); result.put("done", done);
        return result;
    }
    /** Visible deals for a trade screen, with the player's current price for each. */
    public record Priced(TradeDeal deal, int currentPrice, int stockLeft) { }
    public static List<Priced> priced(String type, Map<String, TradeState> states, long worldDay) {
        List<Priced> result = new ArrayList<>();
        for (TradeDeal deal : TradeCatalog.deals(type)) {
            TradeState state = states == null ? null : states.get(deal.id());
            if (visible(deal, state)) result.add(new Priced(deal, currentPrice(deal, state), stockLeft(deal, state, worldDay)));
        }
        return List.copyOf(result);
    }
}
