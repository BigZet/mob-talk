package io.github.divineblock.client.mixin;

import io.github.divineblock.client.SkyTint;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The red sky of wrath over the sky dome and the clouds. */
@Mixin(ClientWorld.class)
abstract class WrathSkyMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void divineblock$sky(Vec3d camera, float tickDelta, CallbackInfoReturnable<Vec3d> result) {
        result.setReturnValue(SkyTint.tint(result.getReturnValue(), tickDelta, 1.0));
    }

    @Inject(method = "getCloudsColor", at = @At("RETURN"), cancellable = true)
    private void divineblock$clouds(float tickDelta, CallbackInfoReturnable<Vec3d> result) {
        result.setReturnValue(SkyTint.tint(result.getReturnValue(), tickDelta, 0.9));
    }
}
