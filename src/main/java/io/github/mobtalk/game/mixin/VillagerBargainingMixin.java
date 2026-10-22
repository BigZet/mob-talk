package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.VillagerBargaining;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerEntity.class)
abstract class VillagerBargainingMixin {
    @Inject(method = "prepareOffersFor", at = @At("HEAD"))
    private void mobtalk$beforePrices(PlayerEntity player, CallbackInfo ci) {
        VillagerBargaining.beforePrepare((VillagerEntity) (Object) this);
    }
    @Inject(method = "prepareOffersFor", at = @At("RETURN"))
    private void mobtalk$quotedPrices(PlayerEntity player, CallbackInfo ci) {
        VillagerBargaining.prepared((VillagerEntity) (Object) this, player);
    }
    @Inject(method = "setCustomer", at = @At("HEAD"))
    private void mobtalk$restorePrices(PlayerEntity player, CallbackInfo ci) {
        VillagerBargaining.changingCustomer((VillagerEntity) (Object) this, player);
    }
    @Inject(method = "writeCustomDataToNbt", at = @At("HEAD"))
    private void mobtalk$saveVanillaPrice(NbtCompound nbt, CallbackInfo ci) {
        VillagerBargaining.beforeSave((VillagerEntity) (Object) this);
    }
    @Inject(method = "writeCustomDataToNbt", at = @At("RETURN"))
    private void mobtalk$resumeQuote(NbtCompound nbt, CallbackInfo ci) {
        VillagerBargaining.afterSave((VillagerEntity) (Object) this);
    }
}
