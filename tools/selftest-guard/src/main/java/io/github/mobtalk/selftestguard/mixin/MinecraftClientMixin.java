package io.github.mobtalk.selftestguard.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A window in the background must keep playing: no pause menu on lost focus. */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientMixin {
    @Shadow @Final public GameOptions options;

    @Inject(method = "tick", at = @At("HEAD"))
    private void selftestGuard$noPause(CallbackInfo ci) {
        if (options != null) options.pauseOnLostFocus = false;
    }
}
