package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.MobLabels;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Над мобом с подписью Mob Talk ванильная табличка (CustomName под прицелом) не рисуется: одна подпись, а не две наложением
 * (настоятель храма носит CustomName «отец …» от Божественного блока). Рендер людей-жителей наследует тот же {@code hasLabel}. */
@Mixin(LivingEntityRenderer.class)
public abstract class MobNameTagMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void mobtalk$oneLabel(LivingEntity entity, CallbackInfoReturnable<Boolean> result) {
        if (MobLabels.draws(entity.getUuid())) result.setReturnValue(false);
    }
}
