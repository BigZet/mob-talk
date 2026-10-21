package io.github.divineblock.client.mixin;

import io.github.divineblock.client.SkyTint;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Бог с булавой»: while his own cloud bank fills the sky, the vanilla cloud layer is not drawn, so its flat slabs never
 * cut through his nimbus or his clouds. */
@Mixin(WorldRenderer.class)
abstract class GodHidesCloudsMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void divineblock$godClouds(MatrixStack matrices, Matrix4f view, Matrix4f projection, float tickDelta, double x, double y, double z, CallbackInfo info) {
        if (SkyTint.godAmount() > 0.3f) info.cancel();
    }
}
