package io.github.alicespeaker.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Alice settings": the microphone she hears (the system default or a capture device, cycled by clicking) and the
 * Yandex login (QR login screen, log out). Devices are listed and files touched off the game thread.
 */
final class AliceSettingsScreen extends Screen {
    private static final int WIDTH = 300;
    private final Screen parent;
    private final CompletableFuture<List<String>> devices = CompletableFuture.supplyAsync(Microphone::devices);
    private boolean listed;
    /** Null until the saved account name has been read off the game thread. */
    private String account, message = "";

    AliceSettingsScreen(Screen parent) {
        super(Text.literal("Настройки Алисы"));
        this.parent = parent;
        refreshAccount();
    }

    /** Devices shown once the list has arrived; empty while it is still loading or when it failed. */
    List<String> listedDevices() { return listed && !devices.isCompletedExceptionally() ? devices.join() : List.of(); }
    boolean loaded() { return listed; }

    @Override protected void init() {
        int left = (width - WIDTH) / 2, y = height / 2 - 62;
        String current = Microphone.chosen();
        String label = current.isEmpty() ? "Системный по умолчанию" : current;
        addDrawableChild(ButtonWidget.builder(Text.literal(textRenderer.trimToWidth("Микрофон: " + label, WIDTH - 12)), button -> {
            List<String> choices = new ArrayList<>();
            choices.add("");
            choices.addAll(listedDevices());
            Microphone.choose(choices.get((choices.indexOf(Microphone.chosen()) + 1) % choices.size()));
            clearAndInit();
        }).dimensions(left, y, WIDTH, 20).build()).active = listed;
        y += 58;
        boolean in = account != null && !account.isBlank();
        addDrawableChild(ButtonWidget.builder(Text.literal(in ? "Войти заново" : "Войти в Яндекс"), button -> client.setScreen(new YandexLoginScreen(this)))
                .dimensions(left, y, in ? WIDTH / 2 - 2 : WIDTH, 20).build());
        if (in) addDrawableChild(ButtonWidget.builder(Text.literal("Выйти"), button -> logOut()).dimensions(left + WIDTH / 2 + 2, y, WIDTH / 2 - 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), button -> close()).dimensions(width / 2 - 50, height - 28, 100, 20).build());
    }

    private void logOut() {
        account = "";
        message = "Вы вышли (sync.json и music_token.txt, если есть, остались)";
        CompletableFuture.runAsync(() -> { try { YandexLogin.forget(); } catch (IOException ignored) { } });
        clearAndInit();
    }

    /** Back from the login screen: the account may have changed. */
    void refreshAccount() {
        message = "";
        CompletableFuture.supplyAsync(YandexLogin::savedAccount).thenAccept(name -> MinecraftClient.getInstance().execute(() -> {
            account = name;
            if (client != null) clearAndInit();
        }));
    }

    @Override public void tick() {
        if (!listed && devices.isDone()) { listed = true; clearAndInit(); }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int y = height / 2 - 62;
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y - 40, 0xFFFFFF);
        String mic = !listed ? "Ищу микрофоны…"
                : devices.isCompletedExceptionally() ? "Не удалось получить список микрофонов"
                : listedDevices().isEmpty() ? "Других микрофонов нет, запись идёт с системного"
                : "Нажмите, чтобы выбрать другой (" + listedDevices().size() + " шт.). Говорить: держите B";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Микрофон"), width / 2, y - 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(mic), width / 2, y + 24, 0xA0A0A0);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Яндекс"), width / 2, y + 44, 0xFFFFFF);
        String status = account == null ? "Проверяю вход…" : account.isBlank() ? "Вход не выполнен" : "Вход выполнен: " + account;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, y + 82, account != null && !account.isBlank() ? 0x80FF80 : 0xFF8080);
        if (!message.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, Text.literal(message), width / 2, y + 96, 0xA0A0A0);
    }

    @Override public void close() { client.setScreen(parent); }
}
