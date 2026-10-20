package io.github.divineblock.client.mixin;

import io.github.divineblock.client.Flipped;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The god's jest: entities he turned over are drawn upside down, as Dinnerbone is. */
@Mixin(LivingEntityRenderer.class)
abstract class UpsideDownMixin {
    @Inject(method = "shouldFlipUpsideDown", at = @At("HEAD"), cancellable = true)
    private static void divineblock$flip(LivingEntity entity, CallbackInfoReturnable<Boolean> result) {
        if (Flipped.is(entity)) result.setReturnValue(true);
    }
}
