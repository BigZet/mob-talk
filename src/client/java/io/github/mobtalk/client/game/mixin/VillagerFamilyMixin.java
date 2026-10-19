package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.MobGameBridge;
import net.minecraft.entity.passive.VillagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While villager families are on, children are born only to couples (FamilyController): vanilla breeding by beds and food is off,
 * because the breeding task asks canBreed first. */
@Mixin(VillagerEntity.class)
abstract class VillagerFamilyMixin {
    @Inject(method = "canBreed", at = @At("HEAD"), cancellable = true)
    private void mobtalk$onlyCouples(CallbackInfoReturnable<Boolean> ci) {
        if (MobGameBridge.familiesOwnBreeding((VillagerEntity) (Object) this)) ci.setReturnValue(false);
    }
}
