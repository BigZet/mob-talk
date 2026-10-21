package io.github.divineblock.client.mixin;

import io.github.divineblock.client.boss.BossClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Звон в ушах»: the camera trembles (the view only; the aim stays where the player points). */
@Mixin(Camera.class)
abstract class CameraShakeMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"))
    private void divineblock$shake(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo info) {
        if (BossClient.ringing) setRotation(getYaw() + BossClient.shakeYaw(), getPitch() + BossClient.shakePitch());
    }
}
