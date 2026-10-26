package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.LiftCabin;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player riding a lift's cabin does not get off with Shift: there is no floor under the cabin between landings. The cabin lets
 * its passengers off itself at every stop. */
@Mixin(PlayerEntity.class)
abstract class LiftRiderMixin {
    @Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
    private void mobtalk$stayInLift(CallbackInfoReturnable<Boolean> cir) {
        if (((PlayerEntity) (Object) this).getVehicle() instanceof LiftCabin) cir.setReturnValue(false);
    }
}
