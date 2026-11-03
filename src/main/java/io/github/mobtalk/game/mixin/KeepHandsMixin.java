package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.ai.brain.task.HoldTradeOffersTask;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla's villager shows its wares to a player near it by holding them and then empties its hand: a fighter's axe or bow (and a
 * tower builder's block) blinked away and back each time (Dmitriy, 2026-10-25). Those villagers keep what is in their hand. */
@Mixin(HoldTradeOffersTask.class)
abstract class KeepHandsMixin {
    @Inject(method = "shouldRun(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/passive/VillagerEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void mobtalk$keepHands(ServerWorld world, VillagerEntity villager, CallbackInfoReturnable<Boolean> cir) {
        if (MobGameBridge.keepsHands(villager)) cir.setReturnValue(false);
    }
}
