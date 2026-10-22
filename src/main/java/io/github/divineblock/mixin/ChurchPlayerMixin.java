package io.github.divineblock.mixin;

import io.github.divineblock.game.ChurchGuard;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player's own hands may change a church: breaking, placing and using items. */
@Mixin(ServerPlayerInteractionManager.class)
abstract class ChurchPlayerMixin {
    @Inject(method = "tryBreakBlock", at = @At("HEAD"))
    private void divineblock$breakBegins(CallbackInfoReturnable<Boolean> info) { ChurchGuard.playerBegins(); }
    @Inject(method = "tryBreakBlock", at = @At("RETURN"))
    private void divineblock$breakEnds(CallbackInfoReturnable<Boolean> info) { ChurchGuard.playerEnds(); }

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void divineblock$useBegins(CallbackInfoReturnable<?> info) { ChurchGuard.playerBegins(); }
    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void divineblock$useEnds(CallbackInfoReturnable<?> info) { ChurchGuard.playerEnds(); }

    @Inject(method = "interactItem", at = @At("HEAD"))
    private void divineblock$itemBegins(CallbackInfoReturnable<?> info) { ChurchGuard.playerBegins(); }
    @Inject(method = "interactItem", at = @At("RETURN"))
    private void divineblock$itemEnds(CallbackInfoReturnable<?> info) { ChurchGuard.playerEnds(); }
}
