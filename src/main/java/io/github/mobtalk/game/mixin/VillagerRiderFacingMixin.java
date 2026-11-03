package io.github.mobtalk.game.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager on a horse faces where the horse goes: its body turns with the horse's (vanilla does this only for a rider that
 * steers its mount); the head still looks where the villager looks. */
@Mixin(LivingEntity.class)
abstract class VillagerRiderFacingMixin {
    @Inject(method = "tickRiding", at = @At("TAIL"))
    private void mobtalk$faceWithHorse(CallbackInfo ci) {
        if ((Object) this instanceof VillagerEntity villager && villager.getVehicle() instanceof AbstractHorseEntity horse) {
            villager.prevBodyYaw = horse.prevBodyYaw;
            villager.bodyYaw = horse.bodyYaw;
        }
    }
}
