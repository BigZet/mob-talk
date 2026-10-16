package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.VillagerBargaining;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MerchantScreenHandler.class)
abstract class MerchantBargainingScreenHandlerMixin {
    @Inject(method = "quickMove", at = @At("HEAD"), cancellable = true)
    private void mobtalk$beforeQuickTrade(PlayerEntity player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (index == 2 && !VillagerBargaining.beforeTake(((MerchantScreenHandlerAccessor) this).mobtalk$merchant(), player))
            cir.setReturnValue(ItemStack.EMPTY);
    }
}
