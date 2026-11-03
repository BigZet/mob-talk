package io.github.mobtalk.game;

import java.util.List;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The supermarket's loyalty card («Карта покупателя»): the cashier issues one free with the first purchase at a staffed till;
 * every emerald spent earns a point on it; paying with sneak spends the points, ten to an emerald off the bill. The points
 * live on the card itself (lose the card, lose the points); its tooltip shows the owner and the balance. */
public final class ShopCard {
    private ShopCard() { }

    static final String KEY = "mobtalk_card";
    public static final int POINTS_PER_EMERALD = 10;

    /** The player's own card in the inventory, or EMPTY. */
    public static ItemStack of(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(ShopContent.CARD)) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static int points(ItemStack card) {
        NbtComponent data = card.get(DataComponentTypes.CUSTOM_DATA);
        return data == null ? 0 : data.copyNbt().getCompound(KEY).getInt("points");
    }

    public static void set(ItemStack card, String owner, int points) {
        NbtCompound root = card.get(DataComponentTypes.CUSTOM_DATA) == null ? new NbtCompound() : card.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        NbtCompound mark = root.getCompound(KEY);
        if (owner != null) mark.putString("owner", owner);
        mark.putInt("points", Math.max(0, points));
        root.put(KEY, mark);
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, card, root);
        card.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.translatable("mobtalk.shop.card.owner", mark.getString("owner")).styled(s -> s.withItalic(false).withColor(Formatting.GRAY)),
                Text.translatable("mobtalk.shop.card.points", mark.getInt("points"), POINTS_PER_EMERALD).styled(s -> s.withItalic(false).withColor(Formatting.GOLD)))));
    }

    /** A new card in this player's name. */
    static ItemStack issue(ServerPlayerEntity player) {
        ItemStack card = new ItemStack(ShopContent.CARD);
        set(card, player.getName().getString(), 0);
        return card;
    }

    /** How many emeralds off a bill of {@code cost} the card's points pay (all of it at most). */
    public static int discount(int points, int cost) { return Math.max(0, Math.min(cost, points / POINTS_PER_EMERALD)); }
}
