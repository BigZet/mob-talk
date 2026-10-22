package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A village that exiled the player does not trade with them: the right click gets a shake of the head (VillageController). */
@Mixin(VillagerEntity.class)
abstract class VillageTradeRefusalMixin {
    @Inject(method = "interactMob", at = @At("HEAD"), cancellable = true)
    private void mobtalk$refuseExile(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> ci) {
        if (player instanceof ServerPlayerEntity serverPlayer && MobGameBridge.refusesTrade((VillagerEntity) (Object) this, serverPlayer))
            ci.setReturnValue(ActionResult.SUCCESS);
    }
}
