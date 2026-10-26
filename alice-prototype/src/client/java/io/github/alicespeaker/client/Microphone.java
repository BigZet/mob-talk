package io.github.alicespeaker.client;

import net.fabricmc.loader.api.FabricLoader;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hold-to-talk recording: 16 kHz mono PCM, at most 15 seconds, on its own thread. The device is the one picked in the
 * microphone menu (config/alicespeaker.properties, "microphone"), or the system default when none is picked or it is gone.
 */
final class Microphone {
    static final int RATE = 16_000, MAX_SECONDS = 15;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final DataLine.Info LINE = new DataLine.Info(TargetDataLine.class, FORMAT);
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("alicespeaker.properties");
    private static volatile String chosen;

    final CompletableFuture<byte[]> pcm = new CompletableFuture<>();
    /** The picked device was not found and the system default recorded instead. */
    volatile boolean fellBack;
    private final String device;
    private final AtomicBoolean stopped = new AtomicBoolean(), cancelled = new AtomicBoolean();

    private Microphone(String device) { this.device = device; }

    static Microphone start() {
        Microphone mic = new Microphone(chosen());
        Thread thread = new Thread(mic::record, "alicespeaker-microphone");
        thread.setDaemon(true);
        thread.start();
        return mic;
    }

    void stop() { stopped.set(true); }
    void cancel() { cancelled.set(true); stopped.set(true); }

    /** Picked device name, "" for the system default. */
    static String chosen() {
        String value = chosen;
        if (value == null) {
            Properties config = new Properties();
            try (Reader in = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) { config.load(in); } catch (IOException missing) { }
            chosen = value = config.getProperty("microphone", "");
        }
        return value;
    }

    /** Remembers the device for the next recordings; the file is written off the game thread. */
    static void choose(String device) {
        chosen = device;
        CompletableFuture.runAsync(() -> {
            Properties config = new Properties();
            config.setProperty("microphone", device);
            try {
                Files.createDirectories(CONFIG.getParent());
                try (Writer out = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) { config.store(out, "Alice Speaker"); }
            } catch (IOException ignored) { }
        });
    }

    /** Capture devices that can record 16 kHz mono; slow on some systems, so call it off the game thread. */
    static List<String> devices() {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            // Windows' "Primary Sound Capture Driver" is the system default, already the first menu entry.
            if (info.getName().startsWith("Primary Sound Capture")) continue;
            try {
                if (AudioSystem.getMixer(info).isLineSupported(LINE) && !names.contains(info.getName())) names.add(info.getName());
            } catch (RuntimeException broken) { }
        }
        return names;
    }

    private TargetDataLine open() throws LineUnavailableException {
        if (!device.isEmpty()) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (!info.getName().equals(device)) continue;
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.isLineSupported(LINE)) return (TargetDataLine) mixer.getLine(LINE);
            }
            fellBack = true;
        }
        return (TargetDataLine) AudioSystem.getLine(LINE);
    }

    private void record() {
        TargetDataLine line = null;
        try {
            line = open();
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
            pcm.completeExceptionally(new IllegalStateException("Микрофон недоступен: выберите другой в меню (клавиша N или /alicemic)"));
        } catch (RuntimeException unexpected) {
            pcm.completeExceptionally(unexpected);
        } finally {
            if (line != null) { try { line.stop(); line.close(); } catch (RuntimeException ignored) { } }
        }
    }
}
