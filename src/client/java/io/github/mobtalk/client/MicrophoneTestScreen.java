package io.github.mobtalk.client;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.WhisperClient;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** One hold-only WAV, reusable for sequential local model comparisons; no LLM or TTS. */
final class MicrophoneTestScreen extends Screen {
    private final Screen parent;
    private final MobTalkConfig config;
    private final Consumer<String> applyModel;
    private final int keyCode;
    private final Text keyName;
    private final MicrophoneRecorder microphone = new MicrophoneRecorder();
    private final WhisperProcess helper = new WhisperProcess();
    private final ExecutorService worker = executor("mobtalk-microphone-test");
    private final ExecutorService audioWorker = executor("mobtalk-recording-playback");
    private final Map<String,ResultView> results = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>(MobTalkSettingsScreen.WHISPER_MODELS);
    private volatile long generation, audioGeneration;
    private volatile boolean closed;
    private volatile Thread workerThread, audioThread;
    private MicrophoneRecorder.Capture capture;
    private byte[] capturedWav, lastWav;
    private Throwable captureError;
    private boolean captureFinished, busy, audioBusy, keyHeld, armed, openingChild;
    private boolean wasFocused = true, refreshOnTick;
    private long startedAt;
    private Text status = Text.translatable("mobtalk.test.ready");
    private boolean error;
    private int page, contentWidth, left;
    private String resultModel;

    MicrophoneTestScreen(Screen parent, MobTalkConfig config, int keyCode) { this(parent, config, keyCode, ignored -> { }); }
    MicrophoneTestScreen(Screen parent, MobTalkConfig config, int keyCode, Consumer<String> applyModel) {
        super(Text.translatable("mobtalk.test.title")); this.parent = parent; this.config = config.copy(); this.applyModel = applyModel;
        this.keyCode = keyCode >= GLFW.GLFW_KEY_SPACE && keyCode <= GLFW.GLFW_KEY_LAST ? keyCode : GLFW.GLFW_KEY_V;
        keyName = InputUtil.fromKeyCode(this.keyCode, 0).getLocalizedText(); resultModel = config.whisperModel;
    }
    private static ExecutorService executor(String name) { return Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }); }
    private int limit() { return Math.max(1, Math.min(15, config.maxRecordingSeconds)); }

    @Override protected void init() {
        contentWidth = Math.min(600, width - 24); left = (width - contentWidth) / 2;
        int third = (contentWidth - 8) / 3;
        String[] tabs = {"record", "compare", "results"};
        for (int i = 0; i < 3; i++) { int index = i;
            ButtonWidget button = button("mobtalk.test.tab." + tabs[i], left + i * (third + 4), 32, third, ignored -> {
                if (capture != null) cancel(Text.translatable("mobtalk.test.cancelled")); page = index; clearAndInit();
            }); button.active = page != i;
        }
        if (page == 0) recordingPage(); else if (page == 1) comparePage(); else resultsPage();
        button("gui.back", width / 2 - Math.min(100, contentWidth / 2), height - 28, Math.min(200, contentWidth), ignored -> close());
    }
    private void recordingPage() {
        int half = (contentWidth - 6) / 2;
        ButtonWidget play = button(audioBusy ? "mobtalk.test.play.stop" : "mobtalk.test.play", left, 120, half, ignored -> { if (audioBusy) stopPlayback(); else play(); });
        play.active = lastWav != null && capture == null;
        button("mobtalk.test.tab.results", left + half + 6, 120, half, ignored -> { page = 2; clearAndInit(); });
        ButtonWidget cancel = button("mobtalk.test.cancel", left, 144, contentWidth, ignored -> cancel(Text.translatable("mobtalk.test.cancelled"))); cancel.active = busy || audioBusy;
    }
    private void comparePage() {
        int half = (contentWidth - 6) / 2;
        List<String> models = MobTalkSettingsScreen.WHISPER_MODELS;
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i); int x = i == 4 ? left : left + (i % 2) * (half + 6), y = 62 + (i / 2) * 24, size = i == 4 ? contentWidth : half;
            var toggle = addDrawableChild(CyclingButtonWidget.onOffBuilder(selected.contains(model)).build(x, y, size, 20, Text.literal(model),
                    (button, value) -> { if (value) selected.add(model); else selected.remove(model); refreshOnTick = true; })); toggle.active = !busy;
        }
        ButtonWidget compare = button("mobtalk.test.compare.start", left, 138, half, ignored -> compare()); compare.active = lastWav != null && !busy && !selected.isEmpty();
        compare.setTooltip(Tooltip.of(Text.translatable("mobtalk.test.compare.tooltip")));
        ButtonWidget cancel = button("mobtalk.test.cancel", left + half + 6, 138, half, ignored -> cancel(Text.translatable("mobtalk.test.cancelled"))); cancel.active = busy || audioBusy;
    }
    private void resultsPage() {
        List<String> values = new ArrayList<>(results.keySet()); if (values.isEmpty()) values.add(resultModel);
        if (!values.contains(resultModel)) resultModel = values.getFirst();
        addDrawableChild(CyclingButtonWidget.<String>builder(Text::literal).values(values).initially(resultModel).omitKeyText()
                .build(left, 62, contentWidth, 20, Text.translatable("mobtalk.settings.whisper.model"), (button, value) -> { resultModel = value; clearAndInit(); }));
        ResultView view = results.get(resultModel); int half = (contentWidth - 6) / 2;
        ButtonWidget transcript = button("mobtalk.test.transcript", left, 120, half, ignored -> {
            ResultView result = results.get(resultModel); if (result == null) return; stopPlayback(); worker.execute(helper::stopOwned); openingChild = true;
            client.setScreen(new ReadOnlyTextScreen(this, Text.translatable("mobtalk.test.transcript"), result.error().isBlank() ? result.text() : result.error()));
        }); transcript.active = view != null && !busy;
        ButtonWidget summary = button("mobtalk.test.summary", left + half + 6, 120, half, ignored -> {
            stopPlayback(); worker.execute(helper::stopOwned); openingChild = true; client.setScreen(new ReadOnlyTextScreen(this, Text.translatable("mobtalk.test.summary"), comparisonText()));
        }); summary.active = !results.isEmpty() && !busy;
        ButtonWidget use = button("mobtalk.test.use", left, 144, half, ignored -> { applyModel.accept(resultModel); close(); });
        use.active = view != null && view.error().isBlank() && !busy;
        ButtonWidget play = button(audioBusy ? "mobtalk.test.play.stop" : "mobtalk.test.play", left + half + 6, 144, half, ignored -> { if (audioBusy) stopPlayback(); else play(); }); play.active = lastWav != null && capture == null;
    }

    @Override public void tick() {
        if (closed) return;
        if (refreshOnTick) { refreshOnTick = false; clearAndInit(); }
        if (!client.isWindowFocused()) {
            if (wasFocused) cancel(Text.translatable("mobtalk.test.focus_lost")); wasFocused = false; armed = false; keyHeld = true; return;
        }
        wasFocused = true; boolean held = InputUtil.isKeyPressed(client.getWindow().getHandle(), keyCode);
        if (!held) armed = true;
        if (page == 0 && held && !keyHeld && armed && !busy && !audioBusy && audioThread == null) start();
        if (!held && keyHeld && capture != null) { capture.stop(); status = Text.translatable("mobtalk.test.finishing"); if (captureFinished) finishCapture(); }
        else if (held && capture != null && !captureFinished) status = Text.translatable("mobtalk.test.recording", Math.min(limit(), (System.nanoTime() - startedAt) / 1_000_000_000L), limit(), keyName);
        keyHeld = held;
    }
    private void start() {
        long token = ++generation; armed = false; busy = true; error = false; capturedWav = null; captureError = null; captureFinished = false; startedAt = System.nanoTime();
        try {
            MicrophoneRecorder.Capture started = microphone.start(config.microphoneName, limit()); capture = started;
            status = Text.translatable("mobtalk.test.recording", 0, limit(), keyName); refreshOnTick = true;
            started.wav.whenComplete((wav, failure) -> client.execute(() -> {
                if (!current(token) || capture != started) return; capturedWav = wav; captureError = failure; captureFinished = true;
                if (!keyHeld || failure != null) finishCapture(); else status = Text.translatable("mobtalk.test.limit", keyName);
            }));
        } catch (RuntimeException failure) { busy = false; error = true; status = Text.translatable("mobtalk.test.microphone_busy"); refreshOnTick = true; }
    }
    private void finishCapture() {
        capture = null;
        if (captureError != null) { busy = false; error = true; status = Text.literal(failureMessage(captureError)); refreshOnTick = true; return; }
        byte[] wav = capturedWav; capturedWav = null;
        if (wav == null) { busy = false; status = Text.translatable("mobtalk.test.too_short", keyName); refreshOnTick = true; return; }
        lastWav = wav; results.clear(); resultModel = config.whisperModel; status = Text.translatable("mobtalk.test.whisper");
        long token = generation; worker.execute(() -> runModels(token, wav, List.of(config.whisperModel)));
    }
    private void compare() {
        if (lastWav == null || busy || selected.isEmpty()) return; stopPlayback();
        List<String> models = MobTalkSettingsScreen.WHISPER_MODELS.stream().filter(selected::contains).toList();
        long token = ++generation; byte[] wav = lastWav; busy = true; error = false; results.clear(); status = Text.translatable("mobtalk.test.compare.running");
        refreshOnTick = true; worker.execute(() -> runModels(token, wav, models));
    }
    private void runModels(long token, byte[] wav, List<String> models) {
        workerThread = Thread.currentThread();
        try {
            for (int i = 0; i < models.size(); i++) {
                String model = models.get(i); int currentIndex = i + 1; checkCurrent(token);
                MobTalkConfig run = config.copy(); run.whisperModel = model;
                long loadingStart = System.nanoTime(); double loading = 0;
                try {
                    update(token, Text.translatable("mobtalk.test.compare.model", currentIndex, models.size(), model));
                    helper.ensureAvailable(run, () -> !current(token), progress -> update(token, Text.literal(currentIndex + "/" + models.size() + " · " + progress)));
                    loading = (System.nanoTime() - loadingStart) / 1_000_000_000.0; checkCurrent(token);
                    update(token, Text.translatable("mobtalk.test.recognizing", model)); long recognitionStart = System.nanoTime(); WhisperClient.Result recognized;
                    try (WhisperClient whisper = new WhisperClient(run)) { checkCurrent(token); recognized = whisper.transcribeDetailed(wav, config.whisperHotwords); }
                    checkCurrent(token); double recognition = recognized.elapsedSeconds() >= 0 ? recognized.elapsedSeconds() : (System.nanoTime() - recognitionStart) / 1_000_000_000.0;
                    ResultView result = new ResultView(model, recognized.device(), loading, recognition, recognized.text(), "");
                    client.execute(() -> { if (current(token)) { results.put(model, result); resultModel = model; refreshOnTick = true; } });
                } catch (InterruptedException cancelled) { throw cancelled; }
                catch (Exception failure) {
                    checkCurrent(token); double elapsedLoading = loading > 0 ? loading : (System.nanoTime() - loadingStart) / 1_000_000_000.0;
                    ResultView result = new ResultView(model, "unknown", elapsedLoading, -1, "", failureMessage(failure));
                    client.execute(() -> { if (current(token)) { results.put(model, result); resultModel = model; refreshOnTick = true; } });
                }
            }
            client.execute(() -> { if (current(token)) { busy = false; status = Text.translatable("mobtalk.test.compare.done", models.size()); refreshOnTick = true; } });
        } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        finally { workerThread = null; Thread.interrupted(); }
    }
    private void update(long token, Text text) { client.execute(() -> { if (current(token)) status = text; }); }
    private String comparisonText() {
        StringBuilder text = new StringBuilder();
        for (ResultView result : results.values()) {
            text.append(result.model()).append(" · ").append(result.device()).append('\n')
                    .append(Text.translatable("mobtalk.test.results.timing", String.format(Locale.ROOT, "%.1f", result.loadingSeconds()), String.format(Locale.ROOT, "%.1f", result.recognitionSeconds())).getString())
                    .append("\n").append(result.error().isBlank() ? result.text() : result.error()).append("\n\n");
        }
        return text.toString();
    }
    private boolean current(long token) { return !closed && generation == token; }
    private void checkCurrent(long token) throws InterruptedException { if (!current(token) || Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled"); }

    private void play() {
        if (lastWav == null || audioBusy || capture != null) return;
        long token = ++audioGeneration; byte[] wav = lastWav; audioBusy = true; refreshOnTick = true;
        audioWorker.execute(() -> {
            audioThread = Thread.currentThread();
            try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
                if (closed || audioGeneration != token) return; Clip clip = AudioSystem.getClip();
                try { clip.open(stream); if (closed || audioGeneration != token) return; clip.start();
                    while (!closed && audioGeneration == token && clip.isRunning()) Thread.sleep(20);
                } finally { clip.stop(); clip.close(); }
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            catch (Exception failure) { client.execute(() -> { if (!closed && audioGeneration == token) { status = Text.translatable("mobtalk.test.play.failed"); error = true; } }); }
            finally { audioThread = null; Thread.interrupted(); client.execute(() -> { if (!closed && audioGeneration == token) { audioBusy = false; refreshOnTick = true; } }); }
        });
    }
    private void stopPlayback() { audioGeneration++; Thread running = audioThread; if (running != null) running.interrupt(); audioBusy = false; refreshOnTick = true; }
    private void cancel(Text message) {
        generation++; if (capture != null) capture.cancel(); capture = null; capturedWav = null; captureError = null; captureFinished = false; busy = false; error = false;
        Thread running = workerThread; if (running != null) running.interrupt();
        worker.execute(() -> { helper.cancelLoading(config); helper.stopOwned(); }); stopPlayback(); status = message; refreshOnTick = true;
    }
    @Override public void removed() {
        if (openingChild) { openingChild = false; return; }
        if (closed) return; closed = true; cancel(Text.translatable("mobtalk.test.cancelled")); microphone.close(); worker.shutdown(); audioWorker.shutdown();
    }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return true; }
    private ButtonWidget button(String key, int x, int y, int size, ButtonWidget.PressAction action) { return addDrawableChild(ButtonWidget.builder(Text.translatable(key), action).dimensions(x, y, size, 20).build()); }
    private void lines(DrawContext context, Text text, int y, int maximum, int color) { var lines = textRenderer.wrapLines(text, contentWidth); for (int i = 0; i < Math.min(maximum, lines.size()); i++) context.drawTextWithShadow(textRenderer, lines.get(i), left, y + i * 10, color); }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 13, 0xFFFFFF);
        if (page == 0) {
            lines(context, Text.translatable("mobtalk.test.instructions", keyName, limit()), 62, 2, 0xD5D5D5);
            String name = config.microphoneName == null || config.microphoneName.isBlank() ? Text.translatable("mobtalk.settings.microphone.default").getString() : config.microphoneName;
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(Text.translatable("mobtalk.test.microphone", name).getString(), contentWidth), left, 89, 0xBBBBBB);
            context.fill(left, 103, left + contentWidth, 113, 0xFF303030); double level = capture == null ? 0 : capture.level;
            context.fill(left, 103, left + (int) (contentWidth * Math.max(0, Math.min(1, level))), 113, level > 0.9 ? 0xFFDD6644 : 0xFF55AA66);
        } else if (page == 2) {
            ResultView result = results.get(resultModel);
            if (result == null) lines(context, Text.translatable("mobtalk.test.results.empty"), 88, 2, 0xBBBBBB);
            else if (!result.error().isBlank()) lines(context, Text.literal(result.error()), 88, 3, 0xFF9999);
            else {
                lines(context, Text.translatable("mobtalk.test.results.device", result.device()), 88, 1, 0xD5D5D5);
                lines(context, Text.translatable("mobtalk.test.results.timing", String.format(Locale.ROOT, "%.1f", result.loadingSeconds()), String.format(Locale.ROOT, "%.1f", result.recognitionSeconds())), 102, 1, 0xBBBBBB);
            }
        }
        lines(context, status, height - 48, 2, error ? 0xFF9999 : 0xCCCCCC);
        if (mouseY >= height - 50 && mouseY < height - 28) context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(status, Math.min(360, width - 24)), mouseX, mouseY);
    }
    private static String failureMessage(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof CompletionException || failure instanceof ExecutionException)) failure = failure.getCause();
        String message = MobTalkSettingsScreen.cleanMessage(failure.getMessage()); return message.isBlank() ? Text.translatable("mobtalk.test.failed").getString() : message;
    }
    private record ResultView(String model, String device, double loadingSeconds, double recognitionSeconds, String text, String error) { }
}
