package io.github.divineblock.client.mixin;

import io.github.divineblock.client.fx.FxCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The camera trembles under the blows of wrath ({@link FxCamera}): a small turn of the view, never of the player. */
@Mixin(Camera.class)
abstract class ShakeCameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"))
    private void divineblock$shake(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo info) {
        float[] offsets = FxCamera.offsets(tickDelta);
        if (offsets != null) setRotation(getYaw() + offsets[0], getPitch() + offsets[1]);
    }
}
