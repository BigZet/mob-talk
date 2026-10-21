package io.github.divineblock.client.mixin;

import io.github.divineblock.client.fx.FxEngine;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LightningEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LightningEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The god's own lightning is drawn by the effects engine; the vanilla bolt on the same spot is not drawn over it. */
@Mixin(LightningEntityRenderer.class)
abstract class GodBoltMixin {
    @Inject(method = "render(Lnet/minecraft/entity/LightningEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), cancellable = true)
    private void divineblock$hide(LightningEntity bolt, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light, CallbackInfo info) {
        if (FxEngine.hidesBolt(bolt)) info.cancel();
    }
}
