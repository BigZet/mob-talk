package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.Roads;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A villager riding with a road trip is drawn along the road's line, and where the line passes through a block the road left
 * standing it would be smothered in it (villagers in the carts stuck in blocks and died, Dmitriy 2026-10-25): the trip's
 * villagers and horses take no harm from a wall. */
@Mixin(LivingEntity.class)
abstract class RoadTripSuffocationMixin {
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void mobtalk$roadTrip(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source.isOf(DamageTypes.IN_WALL) && Roads.inRoadTrip((LivingEntity) (Object) this)) cir.setReturnValue(false);
    }
}
