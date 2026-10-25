package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Village defence: a villager a zombie kills dies instead of turning into a zombie villager (a guard or an archer always). True is
 * what the zombie's ancestors answer: the death goes on as usual, with its drops and its events. */
@Mixin(ZombieEntity.class)
abstract class ZombieConversionMixin {
    @Inject(method = "onKilledOther", at = @At("HEAD"), cancellable = true)
    private void mobtalk$noConversion(ServerWorld world, LivingEntity other, CallbackInfoReturnable<Boolean> cir) {
        if (other instanceof VillagerEntity villager && MobGameBridge.blocksZombieConversion(villager)) cir.setReturnValue(true);
    }
}
