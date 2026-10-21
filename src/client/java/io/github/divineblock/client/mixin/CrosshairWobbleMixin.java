package io.github.divineblock.client.mixin;

import io.github.divineblock.client.boss.BossClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Звон в ушах»: the crosshair wanders around the centre of the screen. */
@Mixin(InGameHud.class)
abstract class CrosshairWobbleMixin {
    @Unique private boolean divineblock$pushed;

    @Inject(method = "renderCrosshair", at = @At("HEAD"))
    private void divineblock$wobble(DrawContext context, RenderTickCounter counter, CallbackInfo info) {
        divineblock$pushed = BossClient.ringing;
        if (!divineblock$pushed) return;
        context.getMatrices().push();
        context.getMatrices().translate(BossClient.wobbleX(), BossClient.wobbleY(), 0);
    }

    @Inject(method = "renderCrosshair", at = @At("RETURN"))
    private void divineblock$steady(DrawContext context, RenderTickCounter counter, CallbackInfo info) {
        if (divineblock$pushed) context.getMatrices().pop();
        divineblock$pushed = false;
    }
}
