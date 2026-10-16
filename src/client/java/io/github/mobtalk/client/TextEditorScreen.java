package io.github.mobtalk.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** A native scrollable editor; parent drafts only change after Apply. */
class TextEditorScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> apply;
    private final String resetValue;
    private final int maximum;
    private String draftText;
    private EditBoxWidget editor;

    TextEditorScreen(Screen parent, Text title, String initial, int maximum, Consumer<String> apply, String resetValue) {
        super(title);
        this.parent = parent;
        this.draftText = initial == null ? "" : initial;
        this.maximum = Math.max(maximum, draftText.length());
        this.apply = apply;
        this.resetValue = resetValue;
    }

    @Override protected void init() {
        int panelWidth = Math.min(680, width - 32), x = (width - panelWidth) / 2;
        editor = addDrawableChild(new EditBoxWidget(textRenderer, x, 36, panelWidth, height - 88,
                Text.translatable("mobtalk.editor.placeholder"), title) {
            @Override public boolean charTyped(char chr, int modifiers) { return apply != null && super.charTyped(chr, modifiers); }
            @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
                if (apply == null && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
                        || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE || Screen.isPaste(key) || Screen.isCut(key))) return false;
                return super.keyPressed(key, scanCode, modifiers);
            }
        });
        editor.setMaxLength(maximum);
        editor.setText(draftText);
        editor.setChangeListener(value -> draftText = value);
        setInitialFocus(editor);
        int buttons = apply == null ? 1 : resetValue == null ? 2 : 3;
        int buttonWidth = Math.min(150, (panelWidth - (buttons - 1) * 6) / buttons);
        int start = (width - buttons * buttonWidth - (buttons - 1) * 6) / 2;
        if (apply == null) addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), ignored -> close())
                .dimensions(start, height - 28, buttonWidth, 20).build());
        else {
            addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.editor.apply"), ignored -> { apply.accept(draftText); close(); })
                    .dimensions(start, height - 28, buttonWidth, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), ignored -> close())
                    .dimensions(start + buttonWidth + 6, height - 28, buttonWidth, 20).build());
            if (resetValue != null) addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.settings.reset"), ignored -> editor.setText(resetValue))
                    .dimensions(start + 2 * (buttonWidth + 6), height - 28, buttonWidth, 20).build());
        }
    }

    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return true; }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        Text hint = apply == null ? Text.translatable("mobtalk.editor.readonly")
                : Text.translatable("mobtalk.editor.count", draftText.length(), maximum);
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(hint.getString(), width - 24), width / 2, height - 43, 0xBBBBBB);
    }
}
