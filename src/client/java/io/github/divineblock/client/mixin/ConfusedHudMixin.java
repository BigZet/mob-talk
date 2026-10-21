package io.github.divineblock.client.mixin;

import io.github.divineblock.client.boss.NimrodConfusion;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** «Смешение языков»: the action bar, the title and the subtitle come scrambled while the player is confused. */
@Mixin(InGameHud.class)
abstract class ConfusedHudMixin {
    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Text divineblock$overlay(Text message) { return NimrodConfusion.scrambleIfActive(message); }

    @ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true)
    private Text divineblock$title(Text title) { return NimrodConfusion.scrambleIfActive(title); }

    @ModifyVariable(method = "setSubtitle", at = @At("HEAD"), argsOnly = true)
    private Text divineblock$subtitle(Text subtitle) { return NimrodConfusion.scrambleIfActive(subtitle); }
}
