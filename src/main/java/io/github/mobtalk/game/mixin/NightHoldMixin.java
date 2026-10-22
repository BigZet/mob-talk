package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Трек J: пока игрок лежит рядом с парой, ночь не пропускается ванильные 5 секунд, а ждёт настройку coSleepNightDelay. */
@Mixin(PlayerEntity.class)
abstract class NightHoldMixin {
    @Inject(method = "canResetTimeBySleeping", at = @At("HEAD"), cancellable = true)
    private void mobtalk$holdNight(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayerEntity player && MobGameBridge.holdsNight(player)) cir.setReturnValue(false);
    }
}
