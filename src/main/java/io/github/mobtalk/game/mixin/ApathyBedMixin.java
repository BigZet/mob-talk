package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.passive.VillagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager in apathy stays in bed by day: its brain's morning wake-up is skipped (only that one; a blow or an alarm wake it). */
@Mixin(VillagerEntity.class)
abstract class ApathyBedMixin {
    @Inject(method = "wakeUp", at = @At("HEAD"), cancellable = true)
    private void mobtalk$stayInBed(CallbackInfo ci) {
        if (MobGameBridge.keepsInBed((VillagerEntity) (Object) this)) ci.cancel();
    }
}
