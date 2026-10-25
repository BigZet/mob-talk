package io.github.alicespeaker.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Picks the microphone Alice hears: the system default or one of the capture devices, listed off the game thread. */
final class MicrophoneScreen extends Screen {
    private static final int TOP = 48, ROW = 22, WIDTH = 300;
    private final Screen parent;
    private final CompletableFuture<List<String>> devices = CompletableFuture.supplyAsync(Microphone::devices);
    private boolean listed;
    private int page;

    MicrophoneScreen(Screen parent) {
        super(Text.literal("Микрофон для Алисы"));
        this.parent = parent;
    }

    /** Devices shown once the list has arrived; empty while it is still loading or when it failed. */
    List<String> listedDevices() { return listed && !devices.isCompletedExceptionally() ? devices.join() : List.of(); }
    boolean loaded() { return listed; }

    @Override protected void init() {
        List<String> choices = new ArrayList<>();
        choices.add("");
        choices.addAll(listedDevices());
        int left = (width - WIDTH) / 2, perPage = Math.max(1, (height - TOP - 60) / ROW);
        int pages = (choices.size() + perPage - 1) / perPage;
        page = Math.min(page, pages - 1);
        String current = Microphone.chosen();
        for (int i = page * perPage, row = 0; i < choices.size() && row < perPage; i++, row++) {
            String device = choices.get(i);
            String label = (device.equals(current) ? "● " : "") + (device.isEmpty() ? "Системный по умолчанию" : device);
            addDrawableChild(ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, WIDTH - 12)), button -> {
                Microphone.choose(device);
                clearAndInit();
            }).dimensions(left, TOP + row * ROW, WIDTH, 20).build());
        }
        int bottom = height - 28;
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("◀"), button -> { page = (page + pages - 1) % pages; clearAndInit(); }).dimensions(left, bottom, 40, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("▶"), button -> { page = (page + 1) % pages; clearAndInit(); }).dimensions(left + WIDTH - 40, bottom, 40, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), button -> close()).dimensions(width / 2 - 50, bottom, 100, 20).build());
    }

    @Override public void tick() {
        if (!listed && devices.isDone()) { listed = true; clearAndInit(); }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);
        String status = !listed ? "Ищу микрофоны…"
                : devices.isCompletedExceptionally() ? "Не удалось получить список микрофонов"
                : listedDevices().isEmpty() ? "Микрофоны не найдены, запись пойдёт с системного"
                : "Выберите, через какой микрофон говорить (кнопка B)";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, 30, 0xA0A0A0);
    }

    @Override public void close() { client.setScreen(parent); }
}
