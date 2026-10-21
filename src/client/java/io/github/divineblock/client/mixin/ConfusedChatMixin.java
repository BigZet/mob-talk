package io.github.divineblock.client.mixin;

import io.github.divineblock.client.boss.NimrodConfusion;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** «Смешение языков»: chat lines that arrive while the player is confused are scrambled. */
@Mixin(ChatHud.class)
abstract class ConfusedChatMixin {
    @ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), argsOnly = true)
    private Text divineblock$confuse(Text message) { return NimrodConfusion.scrambleIfActive(message); }
}
