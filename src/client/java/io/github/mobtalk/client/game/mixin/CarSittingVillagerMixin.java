package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.game.city.CarEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager in a car sits with its legs straight forward under the dashboard: the vanilla riding pose splays them out
 * through the doors. (CarRiderScaleMixin makes it a little smaller, to fit under a real car's roof.) */
@Mixin(VillagerResemblingModel.class)
abstract class CarSittingVillagerMixin {
    @Shadow @Final private ModelPart rightLeg;
    @Shadow @Final private ModelPart leftLeg;

    @Inject(method = "setAngles(Lnet/minecraft/entity/Entity;FFFFF)V", at = @At("TAIL"))
    private void mobtalk$sitInCar(Entity entity, float limbAngle, float limbDistance, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (!(entity.getVehicle() instanceof CarEntity)) return;
        rightLeg.pitch = -1.4137167F;
        rightLeg.yaw = 0.05F;
        rightLeg.roll = 0;
        leftLeg.pitch = -1.4137167F;
        leftLeg.yaw = -0.05F;
        leftLeg.roll = 0;
    }
}
