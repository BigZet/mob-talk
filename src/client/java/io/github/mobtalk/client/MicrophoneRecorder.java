package io.github.mobtalk.client;

import io.github.mobtalk.audio.PcmWav;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opening, reading and closing Java Sound lines never happens on the game thread. */
final class MicrophoneRecorder implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(PcmWav.SAMPLE_RATE, 16, 1, true, false);
    private static final DataLine.Info LINE_INFO = new DataLine.Info(TargetDataLine.class, FORMAT);
    private final AtomicBoolean occupied = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-microphone");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Capture active;

    Capture start(String microphoneName, int seconds) {
        if (!occupied.compareAndSet(false, true)) throw new IllegalStateException("Микрофон завершает предыдущую запись");
        Capture capture = new Capture();
        active = capture;
        try {
            worker.execute(() -> record(capture, microphoneName, seconds));
        } catch (RuntimeException failure) {
            occupied.set(false);
            active = null;
            throw failure;
        }
        return capture;
    }

    private void record(Capture capture, String microphoneName, int seconds) {
        TargetDataLine line = null;
        byte[] result = null;
        Throwable failure = null;
        try {
            if (capture.cancelled.get() || capture.stopped.get()) return;
            line = selectLine(microphoneName);
            line.open(FORMAT, 6_400);
            if (capture.cancelled.get() || capture.stopped.get()) return;
            line.start();
            int maxBytes = PcmWav.maxBytes(seconds);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(PcmWav.MAX_SECONDS, seconds));
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(maxBytes);
            byte[] buffer = new byte[3_200];
            while (!capture.stopped.get() && !capture.cancelled.get()
                    && System.nanoTime() < deadline && pcm.size() < maxBytes) {
                // Reading only available complete frames keeps stop/cancel independent of blocking read().
                int available = Math.min(Math.min(line.available(), buffer.length), maxBytes - pcm.size()) & ~1;
                if (available == 0) {
                    Thread.sleep(10);
                    continue;
                }
                int count = line.read(buffer, 0, available);
                if (count > 0) pcm.write(buffer, 0, count & ~1);
            }
            if (!capture.cancelled.get() && pcm.size() >= 3_200) result = PcmWav.encode(pcm.toByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (LineUnavailableException | IllegalArgumentException unavailable) {
            failure = new IOException("Микрофон недоступен: проверьте доступ Windows и /mobtalk mic list", unavailable);
        } catch (Exception unexpected) {
            failure = unexpected;
        } finally {
            if (line != null) {
                try { line.stop(); } catch (RuntimeException ignored) { }
                try { line.close(); } catch (RuntimeException ignored) { }
            }
            active = null;
            occupied.set(false);
            // Publish only after the native line has closed; a new recording cannot overlap this one.
            if (failure != null) capture.wav.completeExceptionally(failure);
            else capture.wav.complete(result);
        }
    }

    private static TargetDataLine selectLine(String name) throws LineUnavailableException, IOException {
        if (name == null || name.isBlank()) return (TargetDataLine) AudioSystem.getLine(LINE_INFO);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equalsIgnoreCase(name)) {
                Mixer mixer = AudioSystem.getMixer(info);
                if (!mixer.isLineSupported(LINE_INFO))
                    throw new IOException("Выбранный микрофон не поддерживает mono 16 кГц: /mobtalk mic list");
                return (TargetDataLine) mixer.getLine(LINE_INFO);
            }
        }
        throw new IOException("Микрофон из microphoneName не найден: /mobtalk mic list");
    }

    static List<String> devices() {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(LINE_INFO)) names.add(info.getName());
        }
        return List.copyOf(names);
    }

    @Override public void close() {
        Capture capture = active;
        if (capture != null) capture.cancel();
        worker.shutdownNow();
    }

    static final class Capture {
        final CompletableFuture<byte[]> wav = new CompletableFuture<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        void stop() { stopped.set(true); }
        void cancel() { cancelled.set(true); stopped.set(true); }
    }
}
