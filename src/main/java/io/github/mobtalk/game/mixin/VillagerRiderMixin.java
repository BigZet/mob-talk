package io.github.mobtalk.game.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Village defence's horseman: a villager has no seat of its own (it would stand on the horse's back, a block too high), so on a
 * horse it sits where a player sits. Both sides: the client places passengers itself. */
@Mixin(Entity.class)
abstract class VillagerRiderMixin {
    @Inject(method = "getVehicleAttachmentPos", at = @At("HEAD"), cancellable = true)
    private void mobtalk$seat(Entity vehicle, CallbackInfoReturnable<Vec3d> cir) {
        if ((Object) this instanceof VillagerEntity villager && vehicle instanceof AbstractHorseEntity)
            cir.setReturnValue(PlayerEntity.VEHICLE_ATTACHMENT_POS.multiply(villager.getScale()));
    }
}
