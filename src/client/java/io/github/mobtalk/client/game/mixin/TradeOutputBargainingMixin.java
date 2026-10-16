package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.VillagerBargaining;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.TradeOutputSlot;
import net.minecraft.village.Merchant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TradeOutputSlot.class)
abstract class TradeOutputBargainingMixin extends Slot {
    @Shadow @Final private Merchant merchant;

    private TradeOutputBargainingMixin(Inventory inventory, int index, int x, int y) { super(inventory, index, x, y); }

    // An inherited Slot method: this override validates before normal pickup, hotbar swap or throw removes output.
    @Override public boolean canTakeItems(PlayerEntity player) {
        return super.canTakeItems(player) && VillagerBargaining.beforeTake(merchant, player);
    }
}
