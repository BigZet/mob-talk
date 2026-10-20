package io.github.alicespeaker.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hold-to-talk recording: 16 kHz mono PCM, at most 15 seconds, on its own thread. */
final class Microphone {
    static final int RATE = 16_000, MAX_SECONDS = 15;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    final CompletableFuture<byte[]> pcm = new CompletableFuture<>();
    private final AtomicBoolean stopped = new AtomicBoolean(), cancelled = new AtomicBoolean();

    static Microphone start() {
        Microphone mic = new Microphone();
        Thread thread = new Thread(mic::record, "alicespeaker-microphone");
        thread.setDaemon(true);
        thread.start();
        return mic;
    }

    void stop() { stopped.set(true); }
    void cancel() { cancelled.set(true); stopped.set(true); }

    private void record() {
        TargetDataLine line = null;
        try {
            line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
            line.open(FORMAT, 6_400);
            line.start();
            int max = RATE * 2 * MAX_SECONDS;
            ByteArrayOutputStream out = new ByteArrayOutputStream(max);
            byte[] buffer = new byte[3_200];
            while (!stopped.get() && out.size() < max) {
                int available = Math.min(Math.min(line.available(), buffer.length), max - out.size()) & ~1;
                if (available == 0) { Thread.sleep(10); continue; }
                int count = line.read(buffer, 0, available);
                if (count > 0) out.write(buffer, 0, count & ~1);
            }
            pcm.complete(cancelled.get() || out.size() < 6_400 ? null : out.toByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pcm.complete(null);
        } catch (LineUnavailableException | IllegalArgumentException unavailable) {
            pcm.completeExceptionally(new IllegalStateException("Микрофон недоступен"));
        } catch (RuntimeException unexpected) {
            pcm.completeExceptionally(unexpected);
        } finally {
            if (line != null) { try { line.stop(); line.close(); } catch (RuntimeException ignored) { } }
        }
    }
}
