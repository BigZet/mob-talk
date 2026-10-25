package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.CasinoLife;
import net.minecraft.entity.ai.brain.task.PanicTask;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The casino's bouncer does not switch to vanilla's panic at a monster or a blow; it fights (CasinoLife). */
@Mixin(PanicTask.class)
abstract class CasinoBouncerPanicMixin {
    @Inject(method = "run(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/passive/VillagerEntity;J)V", at = @At("HEAD"), cancellable = true)
    private void mobtalk$bouncerStandsGround(ServerWorld world, VillagerEntity villager, long time, CallbackInfo ci) {
        if (CasinoLife.standsGround(villager)) ci.cancel();
    }
}
