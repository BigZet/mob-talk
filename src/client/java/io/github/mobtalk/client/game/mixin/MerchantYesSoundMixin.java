package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.MobMerchant;
import net.minecraft.screen.MerchantScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla casts the merchant to an Entity to play its "yes" sound on a shift-click; a mob's own deal window has no entity merchant. */
@Mixin(MerchantScreenHandler.class)
abstract class MerchantYesSoundMixin {
    @Inject(method = "playYesSound", at = @At("HEAD"), cancellable = true)
    private void mobtalk$ownMerchantSound(CallbackInfo ci) {
        if (((MerchantScreenHandlerAccessor) this).mobtalk$merchant() instanceof MobMerchant own) { own.playYesSound(); ci.cancel(); }
    }
}
