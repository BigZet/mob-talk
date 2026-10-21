package io.github.divineblock.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.divineblock.client.SkyTint;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The horizon and the fog redden with the sky of wrath, so the distance does not stay blue under a red dome. */
@Mixin(BackgroundRenderer.class)
abstract class WrathFogMixin {
    @Shadow private static float red;
    @Shadow private static float green;
    @Shadow private static float blue;

    @Inject(method = "render", at = @At("TAIL"))
    private static void divineblock$fog(Camera camera, float tickDelta, ClientWorld world, int viewDistance, float skyDarkness, CallbackInfo info) {
        Vec3d tinted = SkyTint.tint(new Vec3d(red, green, blue), tickDelta, 0.8);
        red = (float) tinted.x; green = (float) tinted.y; blue = (float) tinted.z;
        RenderSystem.clearColor(red, green, blue, 0.0f);
    }
}
