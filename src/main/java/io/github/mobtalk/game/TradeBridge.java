package io.github.mobtalk.game;

import io.github.mobtalk.core.MobClock;
import io.github.mobtalk.game.mixin.MerchantScreenHandlerAccessor;
import io.github.mobtalk.core.BargainIntent;
import io.github.mobtalk.core.Bargaining;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.SpokenOffer;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A villager's spoken promise ({@link SpokenOffer}) as a real row of its vanilla trade window: the same items as the row it was
 * built on, the first one at the promised count, one use, ten minutes. The row lives only in this class and the villager's
 * in-memory offer list: it is taken out before the villager is saved and put back after, removed when bought, run out, unloaded
 * or on shutdown, so it never reaches the world file and never touches the vanilla rows, their prices or the villager's level.
 * The purchase itself is the ordinary vanilla exchange; {@link #traded} turns it into a kept bargain in the world memory.
 * Server thread only. */
public final class TradeBridge {
    private final MobGameBridge bridge;
    /** One promise per villager (the player it was made to is inside). */
    private final Map<UUID, Promise> promises = new HashMap<>();

    TradeBridge(MobGameBridge bridge) { this.bridge = bridge; }

    private static final class Promise {
        final VillagerEntity villager; final UUID player; final String playerName; final TradeOffer offer; final String what;
        SpokenOffer spoken; boolean bought, hidden;
        Promise(VillagerEntity villager, ServerPlayerEntity player, TradeOffer offer, SpokenOffer spoken, String what) {
            this.villager = villager; this.player = player.getUuid(); this.playerName = MobGameBridge.playerName(player); this.offer = offer; this.spoken = spoken; this.what = what;
        }
    }

    /** Whether the reply was given with the window shut and a spoken block in view: then the bargain is a promise, not a window price. */
    static boolean spoken(MobSnapshot observed) {
        return observed != null && observed.mobDetails().get("bargaining") instanceof Map<?,?> bargaining && !Boolean.TRUE.equals(bargaining.get("open"))
                && bargaining.get("spoken") instanceof Map<?,?> spoken && Boolean.TRUE.equals(spoken.get("available"));
    }

    private Promise promiseOf(VillagerEntity villager, ServerPlayerEntity player) {
        Promise promise = promises.get(villager.getUuid());
        return promise != null && promise.player.equals(player.getUuid()) ? promise : null;
    }
    private int relationship(VillagerEntity villager, ServerPlayerEntity player) {
        MobCard card = bridge.memory.card(villager.getUuid(), player.getUuid());
        return card == null ? 0 : card.relationship();
    }
    /** The row as the bargaining rules see it; with the window shut no overlay is installed, so the special price is the vanilla one. */
    private static Bargaining.Offer describe(VillagerEntity villager, int index, TradeOffer trade) {
        ItemStack original = trade.getOriginalFirstBuyItem();
        int vanilla = MathHelper.clamp(VillagerBargaining.rawPrice(trade) + trade.getSpecialPrice(), 1, original.getMaxCount());
        return new Bargaining.Offer(index, VillagerBargaining.fingerprint(villager, trade), original.getCount(), vanilla, original.getMaxCount(), !trade.isDisabled());
    }

    /** The {@code bargaining.spoken} block for a shut window: every vanilla row with the prices this villager may promise for it now. */
    Map<String, Object> capture(VillagerEntity villager, ServerPlayerEntity player, long tick) {
        if (!bridge.config.enabled) return Map.of("available", false);
        Promise standing = promiseOf(villager, player);
        SpokenOffer spoken = standing != null ? standing.spoken : null;
        int relationship = relationship(villager, player);
        Bargaining.Session session = bridge.memory.bargain(villager.getUuid(), player.getUuid());
        List<TradeOffer> offers = villager.getOffers();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < offers.size() && rows.size() < 12; i++) {
            TradeOffer trade = offers.get(i);
            if (standing != null && standing.offer == trade) continue; // Our own row is the promise, not something to promise again.
            Bargaining.Offer row = describe(villager, i, trade);
            int current = Bargaining.currentPrice(session, row);
            List<Integer> allowed = SpokenOffer.allowedPrices(spoken, row, relationship, current, tick);
            Map<String, Object> first = new LinkedHashMap<>(item(trade.getOriginalFirstBuyItem())); first.put("count", current);
            rows.add(SpokenOffer.row(i, row.key(), Map.copyOf(first), item(trade.getDisplayedSecondBuyItem()), item(trade.getSellItem()), current, allowed,
                    spoken != null && spoken.active(tick) && spoken.sameRow(i, row.key())));
        }
        boolean canOffer = rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("canOffer")));
        String reason = canOffer ? "Можно пообещать одну строку по цене из её allowedPrices: обещание встанет в окно торговли на 10 минут, на одну покупку"
                : relationship <= Bargaining.HOSTILE_RELATIONSHIP ? "Отношение слишком плохое: никаких уступок"
                : spoken != null && spoken.active(tick) ? "По обещанной строке ты уже назвал последнюю цену; остальные строки дальше не уступают"
                : "Уступать некуда: цены уже на пределе или товар распродан";
        return SpokenOffer.block(rows, spoken, tick, reason);
    }

    /** The model's bargain with the window shut: OFFER becomes a one-use row at the promised price; DECLINE only ends the talk. */
    String apply(VillagerEntity villager, ServerPlayerEntity player, BargainIntent intent, MobSnapshot observed) {
        if (intent == null || intent.type() == BargainIntent.Type.NONE) return "";
        if (!bridge.config.enabled) throw new IllegalStateException("Торг отключён вместе с Mob Talk");
        long tick = villager.getWorld().getTime();
        Promise standing = promiseOf(villager, player);
        if (intent.type() == BargainIntent.Type.DECLINE)
            return standing != null && standing.spoken.active(tick) ? "Торг окончен; обещанная сделка остаётся в окне торговли" : "Торг окончен";
        if (intent.type() != BargainIntent.Type.OFFER) throw new IllegalStateException("Без открытого окна торговли принимать нечего: житель должен сам назвать цену");
        Map<?,?> seen = observed == null || !(observed.mobDetails().get("bargaining") instanceof Map<?,?> bargaining) ? null
                : SpokenOffer.row(bargaining.get("spoken") instanceof Map<?,?> spoken ? spoken : null, intent.offerIndex());
        List<TradeOffer> offers = villager.getOffers();
        if (seen == null || intent.offerIndex() < 0 || intent.offerIndex() >= offers.size()) throw new IllegalStateException("Строка торговли изменилась: назовите условия ещё раз");
        TradeOffer trade = offers.get(intent.offerIndex());
        if (standing != null && standing.offer == trade) throw new IllegalStateException("Эта строка и есть обещанная сделка");
        Bargaining.Offer row = describe(villager, intent.offerIndex(), trade);
        int current = Bargaining.currentPrice(bridge.memory.bargain(villager.getUuid(), player.getUuid()), row);
        if (!row.key().equals(seen.get("key")) || !(seen.get("currentPrice") instanceof Number seenPrice) || seenPrice.intValue() != current)
            throw new IllegalStateException("Условия торговли изменились: обсудите текущие строки ещё раз");
        int price = current - intent.discount();
        SpokenOffer promised = SpokenOffer.promise(standing == null ? null : standing.spoken, row, relationship(villager, player), current, price, tick);
        if (promised == null) throw new IllegalStateException("Обещанная цена выходит за пределы: за раз цена падает не больше чем вдвое, всего не ниже "
                + Bargaining.floorPrice(row) + ", не больше " + SpokenOffer.MAX_ROUNDS + " шагов по одной строке");
        // The same goods, the first ingredient at the promised count, one use; no demand, no multiplier, so reputation leaves it alone.
        TradedItem first = trade.getFirstBuyItem();
        TradeOffer offer = new TradeOffer(new TradedItem(first.item(), price, first.components()), trade.getSecondBuyItem(), trade.getSellItem().copy(),
                0, 1, trade.getMerchantExperience(), 0f, 0);
        Promise previous = promises.get(villager.getUuid());
        if (previous != null) remove(previous, false);
        offers.add(offer);
        String what = what(offer);
        promises.put(villager.getUuid(), new Promise(villager, player, offer, promised, what));
        refresh(villager);
        return "Сделка в окне торговли: " + what + " — одна покупка, " + promised.minutesLeft(tick) + " мин (ПКМ по жителю)";
    }

    /** Игра в 21: выигранная скидка с кона — самая дорогая строка жителя за изумруды на {@code percent}% дешевле, одна покупка, как
     * обещание торга словами. "" — подходящей строки нет. */
    public static String prize(VillagerEntity villager, ServerPlayerEntity player, int percent) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        return bridge == null || !bridge.config.enabled ? "" : bridge.trades.prizeRow(villager, player, percent);
    }
    private String prizeRow(VillagerEntity villager, ServerPlayerEntity player, int percent) {
        long tick = villager.getWorld().getTime();
        Bargaining.Session session = bridge.memory.bargain(villager.getUuid(), player.getUuid());
        Promise standing = promiseOf(villager, player);
        List<TradeOffer> offers = villager.getOffers();
        int best = -1, bestPrice = 0;
        Bargaining.Offer bestRow = null;
        for (int i = 0; i < offers.size(); i++) {
            TradeOffer trade = offers.get(i);
            if (trade.isDisabled() || !trade.getOriginalFirstBuyItem().isOf(net.minecraft.item.Items.EMERALD)) continue;
            if (promises.values().stream().anyMatch(p -> p.offer == trade)) continue;
            Bargaining.Offer row = describe(villager, i, trade);
            int current = Bargaining.currentPrice(session, row);
            if (current > bestPrice) { best = i; bestPrice = current; bestRow = row; }
        }
        if (best < 0 || bestPrice < 2) return "";
        int price = Math.max(1, Math.min(bestPrice - 1, (int) Math.round(bestPrice * (100 - percent) / 100.0)));
        TradeOffer trade = offers.get(best);
        TradedItem first = trade.getFirstBuyItem();
        TradeOffer offer = new TradeOffer(new TradedItem(first.item(), price, first.components()), trade.getSecondBuyItem(), trade.getSellItem().copy(),
                0, 1, trade.getMerchantExperience(), 0f, 0);
        SpokenOffer won = new SpokenOffer(bestRow.index(), bestRow.key(), bestRow.vanillaCount(), price, 1, tick, tick + io.github.mobtalk.core.Tuning.get().spokenOfferMinutes * 1200L, false);
        if (standing != null) remove(standing, false);
        offers.add(offer);
        String what = what(offer);
        promises.put(villager.getUuid(), new Promise(villager, player, offer, won, what));
        refresh(villager);
        return what + " — одна покупка, " + won.minutesLeft(tick) + " мин (ПКМ по жителю)";
    }
    private static String what(TradeOffer offer) {
        ItemStack sell = offer.getSellItem(), first = offer.getOriginalFirstBuyItem(), second = offer.getDisplayedSecondBuyItem();
        return sell.getCount() + "× " + sell.getName().getString() + " за " + first.getCount() + "× " + first.getName().getString()
                + (second.isEmpty() ? "" : " + " + second.getCount() + "× " + second.getName().getString());
    }
    private static Map<String, Object> item(ItemStack stack) {
        return Map.of("id", Registries.ITEM.getId(stack.getItem()).toString(), "name", stack.getName().getString(), "count", stack.isEmpty() ? 0 : stack.getCount());
    }

    /** Runs out, dies, buys, or the mod is switched off: the row goes. A level-up appends vanilla rows after ours: ours moves back to the
     * end so the vanilla indices the bargaining remembers stay true. */
    void tick(ServerWorld world) {
        for (Promise promise : List.copyOf(promises.values())) {
            if (promise.villager.getWorld() != world) continue;
            long tick = world.getTime();
            if (!promise.bought && promise.offer.hasBeenUsed()) bought(promise, null); // The trade hook missed it: it still counts.
            if (promise.bought || promise.spoken.expired(tick) || !promise.villager.isAlive() || promise.villager.isRemoved() || !bridge.config.enabled) { remove(promise, true); continue; }
            List<TradeOffer> offers = promise.villager.getOffers();
            int index = indexOf(offers, promise.offer);
            if (index < 0) { if (!promise.hidden) promises.remove(promise.villager.getUuid()); continue; } // Vanilla replaced the whole list: nothing of ours is left.
            if (index != offers.size() - 1) { offers.remove(index); offers.add(promise.offer); refresh(promise.villager); }
        }
    }
    private static int indexOf(List<TradeOffer> offers, TradeOffer offer) { for (int i = 0; i < offers.size(); i++) if (offers.get(i) == offer) return i; return -1; }

    private void bought(Promise promise, ServerPlayerEntity buyer) {
        promise.bought = true; promise.spoken = promise.spoken.asBought();
        UUID player = buyer == null ? promise.player : buyer.getUuid();
        String name = buyer == null ? promise.playerName : MobGameBridge.playerName(buyer);
        if (bridge.memory.known(promise.villager.getUuid())) bridge.memory.recordTradeDone(promise.villager.getUuid(), player, name, promise.what, MobClock.now());
    }
    private void remove(Promise promise, boolean refresh) {
        promises.remove(promise.villager.getUuid());
        promise.villager.getOffers().removeIf(offer -> offer == promise.offer);
        if (refresh) refresh(promise.villager);
    }
    /** The player may be looking at the window: the rows it shows follow the list. */
    private static void refresh(VillagerEntity villager) {
        if (!(villager.getCustomer() instanceof ServerPlayerEntity customer) || !(customer.currentScreenHandler instanceof MerchantScreenHandler handler)
                || ((MerchantScreenHandlerAccessor) handler).mobtalk$merchant() != villager) return;
        ((MerchantScreenHandlerAccessor) handler).mobtalk$inventory().updateOffers();
        customer.sendTradeOffers(handler.syncId, villager.getOffers(), villager.getVillagerData().getLevel(), villager.getExperience(), villager.isLeveledMerchant(), villager.canRefreshTrades());
        handler.sendContentUpdates();
    }
    void unload(VillagerEntity villager) { Promise promise = promises.get(villager.getUuid()); if (promise != null) remove(promise, false); }
    void close() { for (Promise promise : List.copyOf(promises.values())) remove(promise, true); }

    /** The vanilla exchange went through on our row: a kept bargain. The row itself is taken out on the next tick, once the handler is done with it. */
    public static void traded(VillagerEntity villager, ServerPlayerEntity player, TradeOffer offer) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        Promise promise = bridge == null ? null : bridge.trades.promises.get(villager.getUuid());
        if (promise != null && promise.offer == offer && !promise.bought) bridge.trades.bought(promise, player);
    }
    /** writeCustomDataToNbt: the promise is not part of the world; it leaves the list for the write and comes back after. */
    public static void beforeSave(VillagerEntity villager) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        Promise promise = bridge == null ? null : bridge.trades.promises.get(villager.getUuid());
        if (promise != null && villager.getOffers().removeIf(offer -> offer == promise.offer)) promise.hidden = true;
    }
    public static void afterSave(VillagerEntity villager) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        Promise promise = bridge == null ? null : bridge.trades.promises.get(villager.getUuid());
        if (promise != null && promise.hidden) { promise.hidden = false; villager.getOffers().add(promise.offer); }
    }
}
