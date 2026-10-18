package io.github.mobtalk.client.game;

import io.github.mobtalk.client.game.mixin.MerchantInventoryAccessor;
import io.github.mobtalk.client.game.mixin.MerchantScreenHandlerAccessor;
import io.github.mobtalk.core.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.village.Merchant;
import net.minecraft.village.MerchantInventory;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** The vanilla merchant window over a mob's own deals (trades.json): not an entity, one instance per opening. Items change hands
 * through the ordinary MerchantScreenHandler; this class only builds the rows, records purchases and keeps the window alive
 * while the mob lives and the player stays within conversation range. Server thread only. */
final class MobMerchant implements Merchant {
    private static final int UNLIMITED = 1_000_000, LORE_WIDTH = 38;
    private static final Style PLAIN = Style.EMPTY.withItalic(false), NOTE = Style.EMPTY.withColor(Formatting.GRAY).withItalic(false);
    final MobEntity mob;
    final ServerPlayerEntity player;
    private final MobGameBridge bridge;
    private final TradeOfferList offers = new TradeOfferList();
    private final Map<TradeOffer, String> dealIds = new IdentityHashMap<>();
    private PlayerEntity customer;

    private MobMerchant(MobGameBridge bridge, MobEntity mob, ServerPlayerEntity player) { this.bridge = bridge; this.mob = mob; this.player = player; }

    /** Opens the window for this player; throws when the mob has nothing to show. */
    static void open(MobGameBridge bridge, MobEntity mob, ServerPlayerEntity player) {
        MobMerchant merchant = new MobMerchant(bridge, mob, player);
        merchant.rebuild();
        if (merchant.offers.isEmpty()) throw new IllegalStateException("Этот моб ничем не торгует");
        merchant.customer = player;
        merchant.sendOffers(player, merchant.title(), 0);
    }

    /** The window of this player's current screen when it shows a mob's own deals, else null. */
    static MobMerchant of(ServerPlayerEntity player) {
        if (!(player.currentScreenHandler instanceof MerchantScreenHandler handler)) return null;
        return ((MerchantScreenHandlerAccessor) handler).mobtalk$merchant() instanceof MobMerchant merchant && merchant.getCustomer() == player ? merchant : null;
    }
    static MobMerchant of(ServerPlayerEntity player, MobEntity mob) {
        MobMerchant merchant = of(player);
        return merchant != null && merchant.mob == mob ? merchant : null;
    }

    private MerchantScreenHandler handler() { return (MerchantScreenHandler) player.currentScreenHandler; }

    /** Deal id of the row selected in the window; "" for a teaser or quest row and before any selection. */
    String selectedDeal() {
        MerchantInventory inventory = ((MerchantScreenHandlerAccessor) handler()).mobtalk$inventory();
        int index = ((MerchantInventoryAccessor) inventory).mobtalk$offerIndex();
        return index < 0 || index >= offers.size() ? "" : dealIds.getOrDefault(offers.get(index), "");
    }

    private Text title() {
        return Text.literal(mob.getName().getString() + " · " + MobGameBridge.relationshipLabel(bridge.memory.card(mob.getUuid(), player.getUuid())));
    }

    /** Rebuilds the rows and pushes them into the open window the way a villager's level-up does; the selection survives. */
    void refresh() {
        rebuild();
        MerchantScreenHandler handler = handler();
        ((MerchantScreenHandlerAccessor) handler).mobtalk$inventory().updateOffers();
        player.sendTradeOffers(handler.syncId, offers, 0, 0, false, false);
        handler.sendContentUpdates();
    }

    /** Catalog order keeps row indices stable, so an unlocked secret replaces its own «???» row in place. */
    private void rebuild() {
        offers.clear(); dealIds.clear();
        String type = MobGameBridge.type(mob);
        Map<String, TradeState> states = bridge.memory.tradeStates(mob.getUuid(), player.getUuid());
        Map<String, Trading.Priced> priced = new java.util.HashMap<>();
        for (Trading.Priced row : Trading.priced(type, states, MobTrading.worldDay(mob))) priced.put(row.deal().id(), row);
        for (TradeDeal deal : TradeCatalog.deals(type)) {
            Trading.Priced row = priced.get(deal.id());
            if (row == null) { if (deal.secret()) offers.add(note(Items.BARRIER, "???", deal.hint())); continue; }
            Item priceItem = MobTrading.item(deal.price().id()), goods = MobTrading.item(deal.sell().id());
            if (priceItem == null || goods == null) continue;
            TradeOffer offer = new TradeOffer(new TradedItem(priceItem, deal.price().count()), new ItemStack(goods, deal.sell().count()),
                    row.stockLeft() < 0 ? UNLIMITED : row.stockLeft(), 0, 0f);
            // The window strikes the catalog price through and shows the negotiated one, like a villager's discount.
            offer.setSpecialPrice(row.currentPrice() - deal.price().count());
            offers.add(offer);
            dealIds.put(offer, deal.id());
        }
        Favor favor = bridge.memory.favor(mob.getUuid(), player.getUuid());
        if (favor != null) {
            Item wanted = MobTrading.item(favor.item());
            int have = wanted == null ? 0 : Math.min(favor.count(), MobTrading.inventoryCounts(player).getOrDefault(favor.item(), 0));
            offers.add(note(Items.PAPER, "Услуга: " + favor.title() + " (" + have + " из " + favor.count() + ")",
                    "Принести " + favor.count() + "× " + MobTrading.favorItemName(favor) + " и сказать об этом любому из сородичей"));
        }
    }

    /** A disabled row whose tooltip carries the text: the vanilla window has no other place for a teaser or a quest. */
    private static TradeOffer note(Item icon, String name, String details) {
        Text title = Text.literal(name).setStyle(PLAIN);
        LoreComponent lore = new LoreComponent(wrap(details));
        TradedItem buy = new TradedItem(icon, 1).withComponents(builder -> builder.add(DataComponentTypes.CUSTOM_NAME, title).add(DataComponentTypes.LORE, lore));
        ItemStack sell = new ItemStack(icon);
        sell.set(DataComponentTypes.CUSTOM_NAME, title);
        sell.set(DataComponentTypes.LORE, lore);
        TradeOffer offer = new TradeOffer(buy, sell, 1, 0, 0f);
        offer.disable();
        return offer;
    }
    private static List<Text> wrap(String text) {
        List<Text> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text).trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (line.length() > 0 && line.length() + 1 + word.length() > LORE_WIDTH) { lines.add(Text.literal(line.toString()).setStyle(NOTE)); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(Text.literal(line.toString()).setStyle(NOTE));
        return lines;
    }

    private boolean valid() {
        return customer != null && bridge.config.enabled && mob.isAlive() && !mob.isRemoved() && player.isAlive() && mob.getWorld() == player.getWorld()
                && mob.squaredDistanceTo(player) <= bridge.config.maxDistance * bridge.config.maxDistance;
    }

    /** The vanilla handler already moved the goods; a row without a deal id is a teaser or quest line and never trades. */
    @Override public void trade(TradeOffer offer) {
        offer.use();
        String dealId = dealIds.get(offer);
        if (dealId == null) return;
        bridge.memory.recordPurchase(mob.getUuid(), player.getUuid(), dealId, player.getName().getString(), MobTrading.worldDay(mob), System.currentTimeMillis());
    }
    @Override public void setCustomer(PlayerEntity customer) { this.customer = customer; }
    /** A null customer makes MerchantScreenHandler.canUse fail, and the server closes the window on its own. */
    @Override public PlayerEntity getCustomer() { return valid() ? customer : null; }
    @Override public TradeOfferList getOffers() { return offers; }
    @Override public void setOffersFromServer(TradeOfferList offers) { }
    @Override public void onSellingItem(ItemStack stack) { }
    @Override public int getExperience() { return 0; }
    @Override public void setExperienceFromServer(int experience) { }
    @Override public boolean isLeveledMerchant() { return false; }
    @Override public boolean canRefreshTrades() { return false; }
    @Override public SoundEvent getYesSound() { return SoundEvents.ENTITY_VILLAGER_YES; }
    @Override public boolean isClient() { return false; }
}
