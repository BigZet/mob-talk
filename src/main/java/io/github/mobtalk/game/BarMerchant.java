package io.github.mobtalk.game;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.village.Merchant;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;

/** The bartender's menu ({@link BarEvenings}): while the bar is open, a right click on him opens the vanilla merchant window with
 * the bar's drinks for emeralds, instead of his own trade. One instance per opening, server thread only. */
public final class BarMerchant implements Merchant {
    private static final int STOCK = 1_000_000;
    private final VillagerEntity bartender;
    private final TradeOfferList offers = new TradeOfferList();
    private PlayerEntity customer;

    private BarMerchant(VillagerEntity bartender) {
        this.bartender = bartender;
        offers.add(drink(1, BarContent.ALE));
        offers.add(drink(1, BarContent.KVASS));
        offers.add(drink(2, BarContent.WINE));
        offers.add(drink(2, BarContent.MEAD));
    }

    private static TradeOffer drink(int emeralds, Item drink) {
        return new TradeOffer(new TradedItem(Items.EMERALD, emeralds), new ItemStack(drink), STOCK, 0, 0f);
    }

    /** Opens the menu for the player; the bartender looks at him while he chooses. */
    static void open(VillagerEntity bartender, ServerPlayerEntity player) {
        BarMerchant merchant = new BarMerchant(bartender);
        merchant.customer = player;
        bartender.getLookControl().lookAt(player);
        merchant.sendOffers(player, Text.translatable("mobtalk.bar.menu", bartender.getName()), 0);
    }

    private boolean valid() {
        return customer != null && bartender.isAlive() && !bartender.isRemoved() && customer.isAlive() && bartender.getWorld() == customer.getWorld()
                && bartender.squaredDistanceTo(customer) <= 8 * 8;
    }

    @Override public void trade(TradeOffer offer) {
        offer.use();
        bartender.getWorld().playSoundFromEntity(null, bartender, SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
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
