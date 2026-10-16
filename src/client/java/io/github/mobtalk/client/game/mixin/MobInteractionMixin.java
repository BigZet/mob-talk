package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.MobInteractionObserver;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
abstract class MobInteractionMixin {
    @Unique private MobInteractionObserver.Before mobtalk$before;
    @Inject(method = "interact", at = @At("HEAD"))
    private void mobtalk$beforeInteract(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> ci) {
        mobtalk$before = MobInteractionObserver.before((MobEntity) (Object) this, player, hand);
    }
    @Inject(method = "interact", at = @At("RETURN"))
    private void mobtalk$afterInteract(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> ci) {
        MobInteractionObserver.after((MobEntity) (Object) this, player, hand, ci.getReturnValue(), mobtalk$before);
        mobtalk$before = null;
    }
}
