package io.github.mobtalk.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

final class ReadOnlyTextScreen extends TextEditorScreen {
    ReadOnlyTextScreen(Screen parent, Text title, String text) { super(parent, title, text, Math.max(1, text.length()), null, null); }
}
