package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.game.BarSeatEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager on a bar stool or chair sits: the vanilla model has no riding pose, so its legs are bent forward here (the human
 * model sits by itself, as a player does). */
@Mixin(VillagerResemblingModel.class)
abstract class VillagerSitPoseMixin {
    @Shadow @Final private ModelPart rightLeg;
    @Shadow @Final private ModelPart leftLeg;

    @Inject(method = "setAngles", at = @At("TAIL"))
    private void mobtalk$sit(Entity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch, CallbackInfo ci) {
        if (!(entity.getVehicle() instanceof BarSeatEntity)) return;
        rightLeg.pitch = -1.4137167F; rightLeg.yaw = 0.31415927F; rightLeg.roll = 0.07853982F;
        leftLeg.pitch = -1.4137167F; leftLeg.yaw = -0.31415927F; leftLeg.roll = -0.07853982F;
    }
}
