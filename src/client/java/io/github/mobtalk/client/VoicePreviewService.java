package io.github.mobtalk.client;

import io.github.mobtalk.core.*;
import javazoom.jl.decoder.*;
import javax.sound.sampled.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One bounded preview operation; download, MP3 decode, key access, TTS and playback are worker-only. */
final class VoicePreviewService implements AutoCloseable {
    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-voice-preview"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean occupied = new AtomicBoolean();
    private volatile long generation;
    private volatile boolean closed;
    private volatile SourceDataLine line;
    private volatile HttpClient activeHttp;
    private volatile Thread operationThread;
    VoicePreviewService(Path directory) { this.directory = directory; }

    CompletableFuture<String> preview(SpeciesCatalog.Voice voice) {
        return operation(() -> {
            if (voice == null || voice.id().isBlank() || voice.previewUrl().isBlank()) return "Для этого голоса публичное превью недоступно";
            URI uri = URI.create(voice.previewUrl());
            if (!"https".equals(uri.getScheme())) return "Превью должно использовать HTTPS";
            long token = generation;
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
                activeHttp = http;
                HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) return "Превью недоступно: HTTP " + response.statusCode();
                byte[] compressed;
                try (InputStream stream = response.body()) { compressed = stream.readNBytes(2 * 1024 * 1024 + 1); }
                if (compressed.length > 2 * 1024 * 1024) return "Превью превышает допустимый размер";
                playMp3(compressed, token);
                return "Публичное превью: " + voice.name() + ". Генерация через OpenRouter ещё не проверена.";
            } finally { activeHttp = null; }
        });
    }

    CompletableFuture<String> test(MobTalkConfig config, String voice, String text) {
        MobTalkConfig immutable = config.copy();
        return operation(() -> {
            long token = generation;
            byte[] pcm = SpeechPreview.synthesize(immutable, directory, voice, text);
            playPcm(pcm, 24_000, 1, token);
            return "Русская фраза успешно озвучена через Eleven v4 Turbo";
        });
    }

    CompletableFuture<String> status(String voice) {
        if (closed) return CompletableFuture.completedFuture("");
        return CompletableFuture.supplyAsync(() -> SpeechPreview.status(directory, voice), worker);
    }
    CompletableFuture<String> connection(MobTalkConfig config) {
        MobTalkConfig immutable = config.copy();
        return operation(() -> SpeechPreview.connectionStatus(directory, immutable));
    }

    private CompletableFuture<String> operation(ThrowingSupplier action) {
        if (closed || !occupied.compareAndSet(false, true)) return CompletableFuture.completedFuture("Сначала остановите текущее прослушивание");
        long token = generation;
        return CompletableFuture.supplyAsync(() -> {
            operationThread = Thread.currentThread();
            try {
                if (closed || generation != token) return "Прослушивание отменено";
                String result = action.get();
                return generation == token ? result : "Прослушивание отменено";
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt(); return "Прослушивание отменено";
            } catch (Exception failure) {
                String message = MobTalkSettingsScreen.cleanMessage(failure.getMessage());
                return message.isBlank() ? "Не удалось воспроизвести голос" : message;
            } finally {
                closeLine(); operationThread = null; occupied.set(false); Thread.interrupted();
            }
        }, worker);
    }

    private void playMp3(byte[] mp3, long token) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3));
        Decoder decoder = new Decoder();
        int rate = 24_000, channels = 1;
        try {
            Header header;
            while ((header = bitstream.readFrame()) != null && generation == token && !closed) {
                SampleBuffer sample = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                rate = sample.getSampleFrequency(); channels = sample.getChannelCount();
                short[] samples = sample.getBuffer();
                for (int i = 0; i < sample.getBufferLength(); i++) { pcm.write(samples[i] & 255); pcm.write((samples[i] >>> 8) & 255); }
                bitstream.closeFrame();
                if (pcm.size() >= rate * channels * 2 * 30) break;
            }
        } finally { bitstream.close(); }
        playPcm(pcm.toByteArray(), rate, channels, token);
    }

    private void playPcm(byte[] pcm, int rate, int channels, long token) throws Exception {
        if (pcm.length == 0 || generation != token || closed) return;
        AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
        SourceDataLine output = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
        line = output; output.open(format); output.start();
        int position = 0;
        while (position < pcm.length && generation == token && !closed && !Thread.currentThread().isInterrupted()) {
            int written = output.write(pcm, position, Math.min(4096, pcm.length - position));
            if (written <= 0) break;
            position += written;
        }
        if (generation == token && !closed) output.drain();
        closeLine();
    }

    void stop() {
        generation++;
        Thread running = operationThread;
        SourceDataLine stoppedLine = line;
        HttpClient stoppedHttp = activeHttp;
        if (running != null) running.interrupt();
        CompletableFuture.runAsync(() -> { closeOutput(stoppedLine); if (stoppedHttp != null) stoppedHttp.shutdownNow(); });
    }
    private void closeLine() { SourceDataLine output = line; line = null; closeOutput(output); }
    private static void closeOutput(SourceDataLine output) { if (output != null) { try { output.stop(); output.close(); } catch (RuntimeException ignored) { } } }
    @Override public void close() { closed = true; stop(); worker.shutdownNow(); }
    private interface ThrowingSupplier { String get() throws Exception; }
}
