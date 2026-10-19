package io.github.mobtalk.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.text.Text;

/** «Моб и деревня»: one scrolling read-only text built on the server side by {@link io.github.mobtalk.core.MobInfo}; buttons
 * refresh it and unfold the mob's wish and secret. Opened by /mobtalk info and from the F7 menu. */
final class InfoScreen extends Screen {
    private final Screen parent;
    /** secrets → the report; completes off the client thread. */
    private final Function<Boolean, CompletableFuture<String>> loader;
    private String text;
    private boolean secrets, loading, disposed;
    private ReadOnlyBox box;
    private ButtonWidget secretsButton;

    InfoScreen(Screen parent, Function<Boolean, CompletableFuture<String>> loader) {
        super(Text.translatable("mobtalk.info.title"));
        this.parent = parent; this.loader = loader;
        text = Text.translatable("mobtalk.info.loading").getString();
    }

    @Override protected void init() {
        int panel = Math.min(680, width - 32), x = (width - panel) / 2;
        double scroll = box == null ? 0 : box.scroll();
        box = addDrawableChild(new ReadOnlyBox(textRenderer, x, 32, panel, height - 70, title));
        box.setMaxLength(Math.max(1, text.length()));
        box.setText(text);
        box.scrollTo(scroll);
        int buttons = 3, w = Math.min(150, (panel - 12) / buttons), start = (width - buttons * w - 12) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.info.refresh"), ignored -> load()).dimensions(start, height - 28, w, 20).build());
        secretsButton = addDrawableChild(ButtonWidget.builder(Text.translatable(secrets ? "mobtalk.info.hideSecrets" : "mobtalk.info.showSecrets"),
                ignored -> { secrets = !secrets; load(); }).dimensions(start + w + 6, height - 28, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), ignored -> close()).dimensions(start + 2 * (w + 6), height - 28, w, 20).build());
        if (!loading && text.equals(Text.translatable("mobtalk.info.loading").getString())) load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        CompletableFuture<String> future;
        try { future = loader.apply(secrets); }
        catch (RuntimeException failure) { future = CompletableFuture.failedFuture(failure); }
        future.whenComplete((report, failure) -> client.execute(() -> {
            loading = false;
            if (disposed) return;
            Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            text = failure == null ? report : Text.translatable("mobtalk.info.failed", MobTalkSettingsScreen.cleanMessage(cause.getMessage())).getString();
            if (client.currentScreen == this) clearAndInit();
        }));
    }

    /** The text box without editing: arrows, page keys and copying work; the reader's place survives a refresh. */
    private static final class ReadOnlyBox extends EditBoxWidget {
        ReadOnlyBox(net.minecraft.client.font.TextRenderer renderer, int x, int y, int width, int height, Text title) { super(renderer, x, y, width, height, Text.empty(), title); }
        double scroll() { return getScrollY(); }
        void scrollTo(double amount) { setScrollY(amount); }
        @Override public boolean charTyped(char chr, int modifiers) { return false; }
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
                    || key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE || Screen.isPaste(key) || Screen.isCut(key)) return false;
            return super.keyPressed(key, scanCode, modifiers);
        }
    }

    @Override public void close() { client.setScreen(parent); }
    @Override public void removed() { disposed = true; }
    @Override public boolean shouldPause() { return true; }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        if (loading) context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mobtalk.info.loading"), width / 2, height - 40, 0xBBBBBB);
        if (secretsButton != null) secretsButton.active = !loading;
    }
}
