package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.TradeBridge;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A spoken promise is a row in memory only: it leaves the offer list while the villager is written and comes back after. */
@Mixin(VillagerEntity.class)
abstract class SpokenOfferSaveMixin {
    @Inject(method = "writeCustomDataToNbt", at = @At("HEAD"))
    private void mobtalk$hidePromise(NbtCompound nbt, CallbackInfo ci) {
        TradeBridge.beforeSave((VillagerEntity) (Object) this);
    }
    @Inject(method = "writeCustomDataToNbt", at = @At("RETURN"))
    private void mobtalk$restorePromise(NbtCompound nbt, CallbackInfo ci) {
        TradeBridge.afterSave((VillagerEntity) (Object) this);
    }
}
