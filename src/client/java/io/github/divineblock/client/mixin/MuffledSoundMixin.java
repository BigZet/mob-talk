package io.github.divineblock.client.mixin;

import io.github.divineblock.client.boss.BossClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** «Звон в ушах»: every new sound comes muffled, except the ringing itself. */
@Mixin(SoundSystem.class)
abstract class MuffledSoundMixin {
    @Inject(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void divineblock$muffle(SoundInstance sound, CallbackInfoReturnable<Float> info) {
        if (BossClient.ringing && !BossClient.isTinnitus(sound)) info.setReturnValue(info.getReturnValue() * 0.25f);
    }
}
