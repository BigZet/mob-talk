package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.VillagerBargaining;
import net.minecraft.item.ItemStack;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TradeOffer.class)
abstract class TradeOfferBargainingMixin {
    @Inject(method = "depleteBuyItems", at = @At("RETURN"))
    private void mobtalk$paid(ItemStack first, ItemStack second, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) VillagerBargaining.paid((TradeOffer) (Object) this);
    }
}
