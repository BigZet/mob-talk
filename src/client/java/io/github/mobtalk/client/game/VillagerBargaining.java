package io.github.mobtalk.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.mobtalk.client.game.mixin.MerchantInventoryAccessor;
import io.github.mobtalk.client.game.mixin.MerchantScreenHandlerAccessor;
import io.github.mobtalk.core.BargainIntent;
import io.github.mobtalk.core.Bargaining;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.Merchant;
import net.minecraft.village.MerchantInventory;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread-only permanent price overlays. The ordinary merchant inventory remains the sole payment mechanism. */
public final class VillagerBargaining {
    private final MobGameBridge bridge;
    private final Map<TradeOffer, Overlay> overlays = new IdentityHashMap<>();
    private final Map<UUID, VillagerEntity> refreshAfterTrade = new HashMap<>();

    VillagerBargaining(MobGameBridge bridge) { this.bridge = bridge; }

    private record Window(VillagerEntity villager, ServerPlayerEntity player, MerchantScreenHandler handler,
                          MerchantInventory inventory, int index, TradeOffer offer) { }
    private record Overlay(VillagerEntity villager, ServerPlayerEntity player, int index, TradeOffer offer,
                           int originalSpecial, int appliedSpecial, int price) {
        int delta() { return originalSpecial - appliedSpecial; }
    }

    /** Resolves the actual server merchant; a client UUID or client-side selected row is never trusted. */
    static VillagerEntity selected(ServerPlayerEntity player, int syncId) {
        if (!(player.currentScreenHandler instanceof MerchantScreenHandler handler) || handler.syncId != syncId)
            throw new IllegalStateException("Откройте торговлю с жителем");
        Merchant merchant = ((MerchantScreenHandlerAccessor) handler).mobtalk$merchant();
        if (!(merchant instanceof VillagerEntity villager) || villager.getCustomer() != player
                || !villager.isAlive() || villager.isRemoved() || !player.isAlive() || villager.getWorld() != player.getWorld())
            throw new IllegalStateException("Это окно не принадлежит доступному жителю");
        return villager;
    }

    private Window window(VillagerEntity villager, ServerPlayerEntity player) {
        if (!(player.currentScreenHandler instanceof MerchantScreenHandler handler) || villager.getCustomer() != player
                || ((MerchantScreenHandlerAccessor) handler).mobtalk$merchant() != villager
                || !player.isAlive() || !villager.isAlive() || villager.isRemoved() || player.getWorld() != villager.getWorld()) return null;
        MerchantInventory inventory = ((MerchantScreenHandlerAccessor) handler).mobtalk$inventory();
        int index = ((MerchantInventoryAccessor) inventory).mobtalk$offerIndex();
        List<TradeOffer> offers = villager.getOffers();
        return index < 0 || index >= offers.size() ? null : new Window(villager, player, handler, inventory, index, offers.get(index));
    }

    Map<String, Object> capture(VillagerEntity villager, ServerPlayerEntity player) {
        long tick = villager.getWorld().getTime();
        Bargaining.Session session = session(villager, player);
        Window window = window(villager, player);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("open", window != null);
        result.put("selectedOffer", window == null ? -1 : window.index());
        result.put("selectedIndex", window == null ? -1 : window.index());
        result.put("syncId", window == null ? -1 : window.handler().syncId);
        result.put("serverTick", tick);
        result.put("state", state(session));
        if (window == null) {
            result.put("allowedTypes", List.of(BargainIntent.Type.NONE.name()));
            result.put("reason", "Торг доступен только в открытом окне торговли этого жителя");
            result.put("spoken", bridge.trades.capture(villager, player, tick)); // …or as a promise the villager puts into the window itself.
            return Map.copyOf(result);
        }
        Bargaining.Offer offer = describe(villager, window.index(), window.offer());
        int attitude = relationship(villager, player);
        int current = Bargaining.currentPrice(session, offer);
        int maximum = bridge.config.enabled ? Bargaining.maximumDiscount(offer, attitude, current) : 0;
        Bargaining.Availability availability = bridge.config.enabled ? Bargaining.availability(session, offer, attitude, tick)
                : new Bargaining.Availability(List.of(BargainIntent.Type.NONE), "Торг отключён вместе с Mob Talk");
        List<Integer> allowed = new ArrayList<>();
        // Only prices below the one already granted: a negotiated price never goes back up.
        for (int price = current - 1; price >= offer.vanillaCount() - maximum; price--) allowed.add(price);
        result.put("fingerprint", offer.key());
        result.put("baseCount", offer.baseCount());
        result.put("vanillaCount", offer.vanillaCount());
        result.put("currentPrice", window.offer().getDisplayedFirstBuyItem().getCount());
        result.put("permanentPrice", Bargaining.acceptedPrice(session, offer, tick) > 0);
        result.put("maximumDiscount", maximum);
        // Price options alone do not permit another OFFER after the round limit, acceptance, or cooldown.
        result.put("allowedPrices", List.copyOf(allowed));
        result.put("minimumPrice", offer.vanillaCount() - maximum);
        result.put("floorPrice", Bargaining.floorPrice(offer));
        result.put("allowedTypes", availability.allowedTypes().stream().map(Enum::name).toList());
        result.put("reason", availability.reason());
        result.put("firstBuy", item(window.offer().getOriginalFirstBuyItem()));
        result.put("secondBuy", item(window.offer().getDisplayedSecondBuyItem()));
        result.put("sell", item(window.offer().getSellItem()));
        result.put("available", offer.available() && bridge.config.enabled);
        return Map.copyOf(result);
    }

    String apply(VillagerEntity villager, ServerPlayerEntity player, BargainIntent intent, MobSnapshot observed) {
        if (intent == null || intent.type() == BargainIntent.Type.NONE) return "";
        if (!bridge.config.enabled) throw new IllegalStateException("Торг отключён вместе с Mob Talk");
        Window window = window(villager, player);
        if (window == null) throw new IllegalStateException("Для торга откройте торговое окно именно этого жителя");
        if (intent.offerIndex() != window.index()) throw new IllegalStateException("Выбранная сделка изменилась: назовите условия ещё раз");
        Bargaining.Offer offer = describe(villager, window.index(), window.offer());
        if (!matchesObservation(observed, window, offer)) throw new IllegalStateException("Условия торговли изменились: обсудите текущую выбранную сделку");
        long tick = villager.getWorld().getTime();
        Bargaining.Session previous = session(villager, player);
        Bargaining.Decision decision = Bargaining.negotiate(previous, offer, relationship(villager, player), intent, tick);
        if (!decision.changed() && intent.type() == BargainIntent.Type.DECLINE) {
            remove(villager, true); // A refusal can be voiced even when no concession is available; it creates no new quote.
            return decision.message();
        }
        if (!decision.changed()) throw new IllegalStateException(decision.message());
        if (decision.changed()) bridge.memory.setBargain(villager.getUuid(), player.getUuid(), decision.session());
        // A DECLINE keeps the granted price in the window; only a lost price removes the overlay.
        if (Bargaining.acceptedPrice(decision.session(), offer, tick) > 0) {
            if (!install(villager, player, decision.session(), tick))
                throw new IllegalStateException("Не удалось применить цену в окне торговли; условия сделки изменились");
        }
        else remove(villager, true);
        Bargaining.Status expected = switch (intent.type()) {
            case ACCEPT, OFFER -> Bargaining.Status.ACCEPTED;
            case DECLINE -> Bargaining.Status.DECLINED;
            default -> throw new IllegalStateException("Неизвестный шаг переговоров");
        };
        if (decision.session() == null || decision.session().status() != expected)
            throw new IllegalStateException(decision.message());
        return decision.message();
    }

    private boolean matchesObservation(MobSnapshot snapshot, Window window, Bargaining.Offer offer) {
        if (snapshot == null || !(snapshot.mobDetails().get("bargaining") instanceof Map<?, ?> seen)) return false;
        return Boolean.TRUE.equals(seen.get("open")) && number(seen.get("selectedIndex")) == window.index()
                && number(seen.get("syncId")) == window.handler().syncId && offer.key().equals(seen.get("fingerprint"))
                && number(seen.get("vanillaCount")) == offer.vanillaCount();
    }
    private static int number(Object value) { return value instanceof Number n ? n.intValue() : -1; }

    /** A granted price never expires with time; only a hurt, a changed trade or a hostile player (expire) removes it. */
    private Bargaining.Session session(VillagerEntity villager, ServerPlayerEntity player) {
        return bridge.memory.bargain(villager.getUuid(), player.getUuid());
    }

    private static boolean active(Bargaining.Session session) {
        return session != null && (session.status() == Bargaining.Status.PENDING || session.status() == Bargaining.Status.ACCEPTED);
    }
    private int relationship(VillagerEntity villager, ServerPlayerEntity player) {
        MobCard card = bridge.memory.card(villager.getUuid(), player.getUuid());
        return card == null ? 0 : card.relationship();
    }

    private Bargaining.Offer describe(VillagerEntity villager, int index, TradeOffer trade) {
        Overlay overlay = overlays.get(trade);
        int special = trade.getSpecialPrice() + (overlay == null ? 0 : overlay.delta());
        ItemStack original = trade.getOriginalFirstBuyItem();
        int vanilla = MathHelper.clamp(rawPrice(trade) + special, 1, original.getMaxCount());
        return new Bargaining.Offer(index, fingerprint(villager, trade), original.getCount(), vanilla,
                original.getMaxCount(), !trade.isDisabled());
    }
    static int rawPrice(TradeOffer offer) {
        int count = offer.getOriginalFirstBuyItem().getCount();
        return count + Math.max(0, MathHelper.floor((float) (count * offer.getDemandBonus()) * offer.getPriceMultiplier()));
    }

    private boolean valid(Overlay overlay, long tick) {
        if (!bridge.config.enabled || !overlay.villager().isAlive() || overlay.villager().isRemoved()
                || overlay.villager().getCustomer() != overlay.player() || window(overlay.villager(), overlay.player()) == null) return false;
        List<TradeOffer> offers = overlay.villager().getOffers();
        if (overlay.index() >= offers.size() || offers.get(overlay.index()) != overlay.offer()) return false;
        Bargaining.Session current = session(overlay.villager(), overlay.player());
        Bargaining.Offer offer = describe(overlay.villager(), overlay.index(), overlay.offer());
        return current != null && Bargaining.acceptedPrice(current, offer, tick) == overlay.price()
                && Bargaining.permitted(offer, relationship(overlay.villager(), overlay.player()), current.discount())
                && overlay.offer().getDisplayedFirstBuyItem().getCount() == overlay.price();
    }

    /** Returns whether the agreed price is now what the trade window charges. */
    private boolean install(VillagerEntity villager, ServerPlayerEntity player, Bargaining.Session session, long tick) {
        remove(villager, false);
        if (!bridge.config.enabled || session.offerIndex() < 0 || session.offerIndex() >= villager.getOffers().size()) return false;
        TradeOffer trade = villager.getOffers().get(session.offerIndex());
        if (trade.isDisabled()) return false; // Sold out until restock: the price waits for the next opening.
        Bargaining.Offer offer = describe(villager, session.offerIndex(), trade);
        int price = Bargaining.acceptedPrice(session, offer, tick);
        if (price < 1 || !Bargaining.permitted(offer, relationship(villager, player), session.discount())) {
            expire(villager, player); refresh(villager, player); return false;
        }
        int special = trade.getSpecialPrice();
        // Subtracting discount alone fails when a vanilla price is clamped at the item's stack limit.
        int applied = price - rawPrice(trade);
        Overlay overlay = new Overlay(villager, player, session.offerIndex(), trade, special, applied, price);
        overlays.put(trade, overlay);
        trade.setSpecialPrice(applied);
        refresh(villager, player);
        return trade.getDisplayedFirstBuyItem().getCount() == price;
    }

    private void expire(VillagerEntity villager, ServerPlayerEntity player) {
        Bargaining.Session session = bridge.memory.bargain(villager.getUuid(), player.getUuid());
        if (active(session) || Bargaining.holdsPrice(session)) bridge.memory.setBargain(villager.getUuid(), player.getUuid(),
                Bargaining.invalidate(session, Bargaining.Status.EXPIRED));
    }
    void hurtBy(VillagerEntity villager, ServerPlayerEntity player) {
        expire(villager, player);
        Overlay overlay = overlays.values().stream().filter(value -> value.villager() == villager && value.player() == player).findFirst().orElse(null);
        if (overlay != null) remove(villager, true);
    }
    void tick(ServerWorld world) {
        for (Overlay overlay : List.copyOf(overlays.values())) {
            if (overlay.villager().getWorld() != world) continue;
            if (!valid(overlay, world.getTime())) {
                // Closing the window, disabling the mod or a sold-out row only removes the overlay; the granted price stays.
                if (window(overlay.villager(), overlay.player()) != null && bridge.config.enabled && !overlay.offer().isDisabled())
                    expire(overlay.villager(), overlay.player());
                remove(overlay.villager(), true);
            }
        }
    }
    void unload(VillagerEntity villager) { remove(villager, false); }
    void close() {
        for (Overlay overlay : List.copyOf(overlays.values())) remove(overlay.villager(), true);
        refreshAfterTrade.clear();
    }

    private void remove(VillagerEntity villager, boolean refresh) {
        for (Overlay overlay : List.copyOf(overlays.values())) if (overlay.villager() == villager) {
            overlays.remove(overlay.offer());
            // Preserve any intervening native special-price adjustment while removing our exact delta.
            overlay.offer().setSpecialPrice(overlay.offer().getSpecialPrice() + overlay.delta());
            if (refresh) refresh(villager, overlay.player());
        }
    }
    private void refresh(VillagerEntity villager, ServerPlayerEntity player) {
        Window window = window(villager, player);
        if (window == null) return;
        window.inventory().updateOffers();
        player.sendTradeOffers(window.handler().syncId, villager.getOffers(), villager.getVillagerData().getLevel(),
                villager.getExperience(), villager.isLeveledMerchant(), villager.canRefreshTrades());
        window.handler().sendContentUpdates();
    }

    /** prepareOffersFor runs before setCustomer/sendOffers, so the first opening packet also has the quote. */
    public static void beforePrepare(VillagerEntity villager) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        if (bridge != null) bridge.bargaining.remove(villager, false);
    }
    public static void prepared(VillagerEntity villager, PlayerEntity customer) {
        if (!(customer instanceof ServerPlayerEntity player)) return;
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        if (bridge == null) return;
        Bargaining.Session session = bridge.bargaining.session(villager, player);
        if (Bargaining.holdsPrice(session)) bridge.bargaining.install(villager, player, session, villager.getWorld().getTime());
    }
    public static void changingCustomer(VillagerEntity villager, PlayerEntity customer) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        if (bridge != null && (customer == null || bridge.bargaining.overlays.values().stream()
                .anyMatch(overlay -> overlay.villager() == villager && overlay.player() != customer)))
            bridge.bargaining.remove(villager, false);
    }
    public static void beforeSave(VillagerEntity villager) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        if (bridge != null) for (Overlay overlay : bridge.bargaining.overlays.values()) if (overlay.villager() == villager)
            overlay.offer().setSpecialPrice(overlay.offer().getSpecialPrice() + overlay.delta());
    }
    public static void afterSave(VillagerEntity villager) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        if (bridge != null) for (Overlay overlay : bridge.bargaining.overlays.values()) if (overlay.villager() == villager)
            overlay.offer().setSpecialPrice(overlay.offer().getSpecialPrice() - overlay.delta());
    }

    /** Called before the result leaves its slot; returning false safely requires a fresh click at the new price. */
    public static boolean beforeTake(Merchant merchant, PlayerEntity customer) {
        if (!(merchant instanceof VillagerEntity villager) || !(customer instanceof ServerPlayerEntity player)) return true;
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        if (bridge == null) return true;
        VillagerBargaining bargains = bridge.bargaining;
        Window window = bargains.window(villager, player);
        if (window == null) return false;
        TradeOffer payment = window.inventory().getTradeOffer();
        Overlay overlay = payment == null ? null : bargains.overlays.get(payment);
        if (overlay == null) return true;
        if (overlay.player() != player || overlay.index() != window.index() || !bargains.valid(overlay, villager.getWorld().getTime())) {
            bargains.expire(villager, overlay.player());
            bargains.remove(villager, true);
            return false;
        }
        return true;
    }

    /** A paid exchange keeps the overlay: the granted price is permanent, so the next (shift-click) iteration pays it too. */
    public static void paid(TradeOffer trade) {
        MobGameBridge bridge = MobGameBridge.currentTradeBridge();
        if (bridge == null) return;
        Overlay overlay = bridge.bargaining.overlays.get(trade);
        if (overlay != null) bridge.bargaining.refreshAfterTrade.put(overlay.villager().getUuid(), overlay.villager());
    }
    public static void traded(VillagerEntity villager, ServerPlayerEntity player) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        if (bridge != null && bridge.bargaining.refreshAfterTrade.remove(villager.getUuid()) != null)
            bridge.bargaining.refresh(villager, player);
    }

    private static Map<String, Object> state(Bargaining.Session session) {
        if (session == null) return Map.of("status", "NONE");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", session.status().name()); result.put("key", session.key());
        result.put("offerIndex", session.offerIndex()); result.put("vanillaCount", session.vanillaCount());
        result.put("price", session.price()); result.put("discount", session.discount()); result.put("rounds", session.rounds());
        result.put("openedTick", session.openedTick()); result.put("expiresTick", session.expiresTick());
        result.put("cooldownUntil", session.cooldownUntil());
        return Map.copyOf(result);
    }
    private static Map<String, Object> item(ItemStack stack) {
        return Map.of("id", Registries.ITEM.getId(stack.getItem()).toString(), "name", stack.getName().getString(), "count", stack.getCount());
    }
    static String fingerprint(VillagerEntity villager, TradeOffer offer) {
        var ops = villager.getRegistryManager().getOps(JsonOps.INSTANCE);
        JsonObject data = new JsonObject();
        data.add("first", TradedItem.CODEC.encodeStart(ops, offer.getFirstBuyItem()).getOrThrow());
        data.add("second", offer.getSecondBuyItem().<JsonElement>map(value -> TradedItem.CODEC.encodeStart(ops, value).getOrThrow()).orElse(JsonNull.INSTANCE));
        data.add("sell", ItemStack.CODEC.encodeStart(ops, offer.getSellItem()).getOrThrow());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(data).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            value.getAsJsonObject().entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> sorted.add(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            value.getAsJsonArray().forEach(element -> array.add(canonical(element)));
            return array;
        }
        return value;
    }
}
