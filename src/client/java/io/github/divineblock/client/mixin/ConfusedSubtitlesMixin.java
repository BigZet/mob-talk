package io.github.divineblock.client.mixin;

import io.github.divineblock.client.boss.NimrodConfusion;
import net.minecraft.client.gui.hud.SubtitlesHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** «Смешение языков»: sound subtitles are scrambled while the player is confused (the same sound, the same nonsense). */
@Mixin(SubtitlesHud.class)
abstract class ConfusedSubtitlesMixin {
    @ModifyVariable(method = "onSoundPlayed", at = @At("STORE"), ordinal = 0)
    private Text divineblock$confuse(Text subtitle) { return NimrodConfusion.scrambleIfActive(subtitle); }
}
