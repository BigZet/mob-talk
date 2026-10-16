package io.github.mobtalk.client;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.WhisperClient;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** A local microphone/STT probe with its own cancellable resources; no LLM or speech requests. */
final class MicrophoneTestScreen extends Screen {
    private final Screen parent;
    private final MobTalkConfig config;
    private final int keyCode;
    private final Text keyName;
    private final MicrophoneRecorder microphone = new MicrophoneRecorder();
    private final WhisperProcess helper = new WhisperProcess();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-microphone-test");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long generation;
    private volatile boolean closed;
    private volatile Thread workerThread;
    private MicrophoneRecorder.Capture capture;
    private byte[] capturedWav;
    private Throwable captureError;
    private boolean captureFinished;
    private boolean busy;
    private boolean keyHeld;
    private boolean armed;
    private boolean wasFocused = true;
    private long startedAt;
    private Text status = Text.translatable("mobtalk.test.ready");
    private Text transcript = Text.empty();
    private boolean error;
    private int firstLine;
    private int contentWidth;
    private int left;
    private ButtonWidget previous;
    private ButtonWidget next;

    MicrophoneTestScreen(Screen parent, MobTalkConfig config, int keyCode) {
        super(Text.translatable("mobtalk.test.title"));
        this.parent = parent;
        this.config = config.copy();
        this.keyCode = keyCode >= GLFW.GLFW_KEY_SPACE && keyCode <= GLFW.GLFW_KEY_LAST ? keyCode : GLFW.GLFW_KEY_V;
        this.keyName = InputUtil.fromKeyCode(this.keyCode, 0).getLocalizedText();
    }

    @Override protected void init() {
        contentWidth = Math.min(520, width - 32);
        left = (width - contentWidth) / 2;
        previous = addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.test.previous"),
                ignored -> scroll(-visibleLines())).dimensions(left, height - 52, 90, 20).build());
        next = addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.test.next"),
                ignored -> scroll(visibleLines())).dimensions(left + contentWidth - 90, height - 52, 90, 20).build());
        previous.setTooltip(Tooltip.of(Text.translatable("mobtalk.test.scroll.tooltip")));
        next.setTooltip(Tooltip.of(Text.translatable("mobtalk.test.scroll.tooltip")));
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), ignored -> close())
                .dimensions(width / 2 - Math.min(100, contentWidth / 2), height - 28, Math.min(200, contentWidth), 20).build());
    }

    @Override public void tick() {
        if (closed) return;
        if (!client.isWindowFocused()) {
            if (wasFocused) cancel(Text.translatable("mobtalk.test.focus_lost"));
            wasFocused = false;
            armed = false;
            keyHeld = true;
            return;
        }
        wasFocused = true;
        boolean held = InputUtil.isKeyPressed(client.getWindow().getHandle(), keyCode);
        // Opening the screen or regaining focus with an already held key never records.
        if (!held) armed = true;
        if (held && !keyHeld && armed && !busy) start();
        if (!held && keyHeld && capture != null) {
            capture.stop();
            status = Text.translatable("mobtalk.test.finishing");
            if (captureFinished) finishCapture();
        } else if (held && capture != null && !captureFinished) {
            long seconds = Math.min(recordingLimit(), (System.nanoTime() - startedAt) / 1_000_000_000L);
            status = Text.translatable("mobtalk.test.recording", seconds, recordingLimit(), keyName);
        }
        keyHeld = held;
    }

    private int recordingLimit() { return Math.max(1, Math.min(15, config.maxRecordingSeconds)); }

    private void start() {
        long token = ++generation;
        armed = false;
        busy = true;
        error = false;
        capturedWav = null;
        captureError = null;
        captureFinished = false;
        startedAt = System.nanoTime();
        try {
            MicrophoneRecorder.Capture started = microphone.start(config.microphoneName, recordingLimit());
            capture = started;
            status = Text.translatable("mobtalk.test.recording", 0, recordingLimit(), keyName);
            started.wav.whenComplete((wav, failure) -> client.execute(() -> {
                if (!current(token) || capture != started) return;
                capturedWav = wav;
                captureError = failure;
                captureFinished = true;
                if (!keyHeld || failure != null) finishCapture();
                else status = Text.translatable("mobtalk.test.limit", keyName);
            }));
        } catch (RuntimeException unavailable) {
            busy = false;
            error = true;
            status = Text.translatable("mobtalk.test.microphone_busy");
        }
    }

    private void finishCapture() {
        capture = null;
        if (captureError != null) {
            busy = false;
            error = true;
            status = Text.literal(failureMessage(captureError));
            return;
        }
        byte[] wav = capturedWav;
        capturedWav = null;
        if (wav == null) {
            busy = false;
            status = Text.translatable("mobtalk.test.too_short", keyName);
            return;
        }
        long token = generation;
        status = Text.translatable("mobtalk.test.whisper");
        worker.execute(() -> transcribe(token, wav));
    }

    private void transcribe(long token, byte[] wav) {
        workerThread = Thread.currentThread();
        try {
            checkCurrent(token);
            helper.ensureAvailable(config, () -> !current(token));
            checkCurrent(token);
            String text;
            // HTTP creation, send and close all stay on this worker.
            try (WhisperClient whisper = new WhisperClient(config)) {
                checkCurrent(token);
                text = whisper.transcribe(wav);
            }
            checkCurrent(token);
            client.execute(() -> {
                if (!current(token)) return;
                busy = false;
                firstLine = 0;
                transcript = Text.literal(text == null ? "" : text);
                status = text == null || text.isBlank() ? Text.translatable("mobtalk.test.no_speech", keyName)
                        : Text.translatable("mobtalk.test.success", keyName);
            });
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            client.execute(() -> {
                if (!current(token)) return;
                busy = false;
                error = true;
                status = Text.literal(failureMessage(failure));
            });
        } finally {
            workerThread = null;
            // The executor's next request must not inherit a cancelled request's interrupt.
            Thread.interrupted();
        }
    }

    private boolean current(long token) { return !closed && generation == token; }

    private void checkCurrent(long token) throws InterruptedException {
        if (!current(token) || Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
    }

    private void cancel(Text message) {
        generation++;
        if (capture != null) capture.cancel();
        capture = null;
        capturedWav = null;
        captureError = null;
        captureFinished = false;
        busy = false;
        error = false;
        Thread running = workerThread;
        if (running != null) running.interrupt();
        // Stop only the helper owned by this test after the current operation has unwound.
        worker.execute(helper::stopOwned);
        status = message;
    }

    @Override public void removed() {
        if (closed) return;
        closed = true;
        cancel(Text.translatable("mobtalk.test.cancelled"));
        microphone.close();
        worker.shutdown();
    }

    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return true; }

    private int visibleLines() { return Math.max(1, (height - 184) / 10); }

    private List<OrderedText> transcriptLines() { return textRenderer.wrapLines(transcript, contentWidth - 16); }

    private void scroll(int amount) {
        int last = Math.max(0, transcriptLines().size() - visibleLines());
        firstLine = Math.max(0, Math.min(last, firstLine + amount));
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY >= 112 && mouseY < height - 56) {
            scroll(vertical > 0 ? -3 : 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        List<OrderedText> lines = transcriptLines();
        firstLine = Math.min(firstLine, Math.max(0, lines.size() - visibleLines()));
        previous.active = firstLine > 0;
        next.active = firstLine + visibleLines() < lines.size();
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        Text instructions = Text.translatable("mobtalk.test.instructions", keyName, recordingLimit());
        drawLines(context, instructions, left, 38, contentWidth, 3, 0xD5D5D5);
        String microphoneName = config.microphoneName == null || config.microphoneName.isBlank()
                ? Text.translatable("mobtalk.settings.microphone.default").getString() : config.microphoneName;
        Text microphoneLabel = Text.translatable("mobtalk.test.microphone", microphoneName);
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(microphoneLabel.getString(), contentWidth), left, 73, 0xBBBBBB);
        drawLines(context, status, left, 89, contentWidth, 2, error ? 0xFF9999 : 0xDDDDDD);
        context.fill(left, 112, left + contentWidth, height - 60, 0x80000000);
        for (int i = 0; i < visibleLines() && firstLine + i < lines.size(); i++)
            context.drawTextWithShadow(textRenderer, lines.get(firstLine + i), left + 8, 119 + i * 10, 0xFFFFFF);
        if (mouseY >= 87 && mouseY < 109 && textRenderer.wrapLines(status, contentWidth).size() > 2)
            context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(status, Math.min(300, width - 24)), mouseX, mouseY);
        else if (mouseY >= 71 && mouseY < 84 && textRenderer.getWidth(microphoneLabel) > contentWidth)
            context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(microphoneLabel, Math.min(300, width - 24)), mouseX, mouseY);
    }

    private void drawLines(DrawContext context, Text text, int x, int y, int wrapWidth, int limit, int color) {
        List<OrderedText> lines = textRenderer.wrapLines(text, wrapWidth);
        for (int i = 0; i < Math.min(limit, lines.size()); i++)
            context.drawTextWithShadow(textRenderer, lines.get(i), x, y + i * 10, color);
    }

    private static String failureMessage(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)) failure = failure.getCause();
        String message = MobTalkSettingsScreen.cleanMessage(failure.getMessage());
        return message.isBlank() ? Text.translatable("mobtalk.test.failed").getString() : message;
    }
}
