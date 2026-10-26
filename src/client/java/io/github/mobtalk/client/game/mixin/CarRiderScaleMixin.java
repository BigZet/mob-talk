package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.game.city.CarEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.MerchantEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager is too tall to sit upright in a real 1.44 m car: in a car it is drawn a little smaller, from its feet
 * (CarEntity puts the feet where the hips land on the cushion). */
@Mixin(LivingEntityRenderer.class)
abstract class CarRiderScaleMixin {
    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/LivingEntityRenderer;scale(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/util/math/MatrixStack;F)V", shift = At.Shift.AFTER))
    private void mobtalk$smallerInCar(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light, CallbackInfo ci) {
        if (entity instanceof MerchantEntity && entity.getVehicle() instanceof CarEntity) matrices.scale(CarEntity.SITTER_SCALE, CarEntity.SITTER_SCALE, CarEntity.SITTER_SCALE);
    }
}
