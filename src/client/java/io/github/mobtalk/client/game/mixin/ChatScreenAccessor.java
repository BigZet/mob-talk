package io.github.mobtalk.client.game.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Строка чата в кровати: разговор по V идёт, только пока она пуста, и буква клавиши в неё не попадает. */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
    @Accessor("chatField") TextFieldWidget mobtalk$chatField();
}
