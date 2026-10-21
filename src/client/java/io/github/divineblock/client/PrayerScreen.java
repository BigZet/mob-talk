package io.github.divineblock.client;

import io.github.divineblock.game.PrayPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/** The words of a prayer, typed at the altar. */
public final class PrayerScreen extends Screen {
    private final BlockPos altar;
    private TextFieldWidget field;

    public PrayerScreen(BlockPos altar) { super(Text.literal("Молитва")); this.altar = altar; }

    @Override protected void init() {
        int width = Math.min(360, this.width - 40), left = (this.width - width) / 2, top = this.height / 2 - 20;
        field = new TextFieldWidget(textRenderer, left, top, width, 20, Text.literal("Молитва"));
        field.setMaxLength(PrayPayload.MAX_TEXT);
        field.setPlaceholder(Text.literal("О великий, …").formatted(Formatting.DARK_GRAY));
        addDrawableChild(field);
        setInitialFocus(field);
        addDrawableChild(ButtonWidget.builder(Text.literal("Молиться"), button -> send()).dimensions(left, top + 28, width / 2 - 4, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Почему?"), button -> {
                    if (ClientPlayNetworking.canSend(PrayPayload.ID)) ClientPlayNetworking.send(new PrayPayload(altar, "Почему?", true));
                    close();
                })
                .dimensions(left + width / 2 + 4, top + 28, width / 2 - 4, 20).build());
    }

    private void send() {
        String text = field.getText().strip();
        if (!text.isEmpty() && ClientPlayNetworking.canSend(PrayPayload.ID)) ClientPlayNetworking.send(new PrayPayload(altar, text));
        close();
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { send(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int top = this.height / 2 - 20;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Обратись к богу").formatted(Formatting.GOLD), this.width / 2, top - 34, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Дары брось на алтарь заранее. Кнопка «Почему?» — и бог объяснит прошлое решение."),
                this.width / 2, top - 18, 0xAAAAAA);
    }

    @Override public boolean shouldPause() { return false; }
}
