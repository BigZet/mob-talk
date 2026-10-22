package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractHorseEntity.class)
abstract class HorseTamingMixin {
    @Inject(method = "bondWithPlayer", at = @At("RETURN"))
    private void mobtalk$tamed(PlayerEntity player, CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValueZ() && player instanceof ServerPlayerEntity serverPlayer)
            MobGameBridge.helped((AbstractHorseEntity) (Object) this, serverPlayer, "The player successfully tamed the horse");
    }
}
