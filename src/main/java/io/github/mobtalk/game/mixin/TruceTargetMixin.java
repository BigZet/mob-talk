package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Goal-driven hostiles (zombies, skeletons, creepers, spiders…) pick the player through setTarget; a player the mob has a truce
 * with is never taken as a target, nor is anyone the mob promised to spare. Brain-driven ones are handled in
 * MobActionController.afterBrain. */
@Mixin(MobEntity.class)
abstract class TruceTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void mobtalk$keepTruce(LivingEntity target, CallbackInfo ci) {
        if (target instanceof ServerPlayerEntity player && MobGameBridge.truce((MobEntity) (Object) this, player)) ci.cancel();
        else if (target != null && MobGameBridge.spared((MobEntity) (Object) this, target)) ci.cancel();
    }
}
