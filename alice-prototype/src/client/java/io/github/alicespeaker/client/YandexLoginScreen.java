package io.github.alicespeaker.client;

import io.nayuki.qrcodegen.QrCode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QR login to Yandex: a pixel QR code of the passport link; the phone camera or the Yandex app confirms it, the screen
 * polls every two seconds and then saves the x_token, account name and music token. All network off the game thread.
 */
final class YandexLoginScreen extends Screen {
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "alicespeaker-login");
        thread.setDaemon(true);
        return thread;
    });
    private final Screen parent;
    private YandexLogin login;
    private volatile QrCode qr;
    private volatile String link, status = "Получаю QR-код…", account;
    private volatile boolean failed, polling, closed;
    private int attempt, ticks, nextPoll;

    YandexLoginScreen(Screen parent) {
        super(Text.literal("Вход в Яндекс"));
        this.parent = parent;
        restart();
    }

    boolean qrShown() { return qr != null; }
    String status() { return status; }

    private void restart() {
        int mine = ++attempt;
        qr = null; link = null; failed = false; account = null;
        status = "Получаю QR-код…";
        YandexLogin fresh = new YandexLogin();
        CompletableFuture.supplyAsync(() -> {
            try { return fresh.qrLink(); } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, NET).whenComplete((url, failure) -> MinecraftClient.getInstance().execute(() -> {
            if (mine != attempt || closed) return;
            if (failure != null) { fail(failure); return; }
            login = fresh;
            link = url;
            qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
            status = "Наведи камеру телефона или приложение Яндекс и подтверди вход";
            nextPoll = ticks + 40;
        }));
    }

    private void fail(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        failed = true;
        status = "Не вышло: " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    @Override public void tick() {
        ticks++;
        if (qr == null || failed || polling || account != null || ticks < nextPoll) return;
        polling = true;
        int mine = attempt;
        YandexLogin current = login;
        CompletableFuture.supplyAsync(() -> {
            try {
                String xToken = current.poll();
                if (xToken == null) return null;
                String name = current.accountName(xToken);
                String music = "";
                try { music = current.musicToken(xToken); } catch (IOException noMusic) { }
                YandexLogin.save(xToken, name.isBlank() ? "аккаунт Яндекса" : name, music);
                return name.isBlank() ? "аккаунт Яндекса" : name;
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, NET).whenComplete((name, failure) -> MinecraftClient.getInstance().execute(() -> {
            polling = false;
            nextPoll = ticks + 40;
            if (mine != attempt || closed) return;
            if (failure != null) { fail(failure); return; }
            if (name == null) return;
            account = name;
            status = "Вход выполнен: " + name;
            clearAndInit();
        }));
    }

    @Override protected void init() {
        int bottom = height - 28;
        if (account != null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), button -> close()).dimensions(width / 2 - 50, bottom, 100, 20).build());
            return;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Обновить QR"), button -> restart()).dimensions(width / 2 - 184, bottom, 120, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Открыть в браузере"), button -> {
            if (link != null) Util.getOperatingSystem().open(URI.create(link));
        }).dimensions(width / 2 - 60, bottom, 120, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), button -> close()).dimensions(width / 2 + 64, bottom, 120, 20).build());
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        int color = failed ? 0xFF8080 : account != null ? 0x80FF80 : 0xE0E0E0;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status, width - 20)), width / 2, 26, color);
        QrCode code = qr;
        if (code == null || account != null) return;
        // A quiet zone of 2 modules; each module a square of whole pixels so the code stays sharp.
        int modules = code.size + 4, room = Math.min(width - 40, height - 80);
        int scale = Math.max(1, room / modules), side = modules * scale;
        int x0 = (width - side) / 2, y0 = 40 + (height - 80 - side) / 2;
        context.fill(x0, y0, x0 + side, y0 + side, 0xFFFFFFFF);
        for (int y = 0; y < code.size; y++)
            for (int x = 0; x < code.size; x++)
                if (code.getModule(x, y)) {
                    int px = x0 + (x + 2) * scale, py = y0 + (y + 2) * scale;
                    context.fill(px, py, px + scale, py + scale, 0xFF000000);
                }
    }

    @Override public void close() {
        closed = true;
        attempt++;
        if (parent instanceof AliceSettingsScreen settings) settings.refreshAccount();
        client.setScreen(parent);
    }
}
