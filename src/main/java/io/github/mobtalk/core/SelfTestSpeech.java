package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Self-test speech without paid TTS ({@code -Dmobtalk.selftest.notts=true}): every reply is "voiced" with a quiet tone as long
 * as the line would be, so playback, positions and overlaps are still exercised. The few phrases a self-test must say to
 * Whisper (a question, a vow) are voiced for real once and kept on disk, so later runs cost nothing. */
public final class SelfTestSpeech {
    /** 24 kHz signed 16-bit mono, the format every speech path plays. */
    private static final int RATE = 24_000;
    private static final ThreadLocal<Boolean> REAL = ThreadLocal.withInitial(() -> false);

    private SelfTestSpeech() { }

    /** True when speech must not reach a paid provider on this thread. */
    public static boolean stubbed() { return Boolean.getBoolean("mobtalk.selftest.notts") && !REAL.get(); }

    /** A soft 220 Hz tone, about 55 ms per character (0.6–8 s), faded in and out. */
    public static byte[] tone(String text) {
        int length = text == null ? 0 : text.length();
        int samples = (int) (RATE * Math.max(0.6, Math.min(8.0, length * 0.055)));
        byte[] pcm = new byte[samples * 2];
        int fade = RATE / 20;
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1.0, Math.min(i, samples - 1 - i) / (double) fade);
            int value = (int) (Math.sin(2 * Math.PI * 220 * i / RATE) * 3000 * envelope);
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }

    public interface Voice { byte[] synthesize() throws IOException, InterruptedException; }

    /** The real voicing of {@code text}, cached in {@code directory} by text and voice; synthesized only on the first run. */
    public static byte[] fixture(Path directory, String voice, String text, Voice real) throws IOException, InterruptedException {
        Path file = directory.resolve(digest(voice + "\n" + text) + ".pcm");
        if (Files.isRegularFile(file)) return Files.readAllBytes(file);
        byte[] pcm;
        REAL.set(true);
        try { pcm = real.synthesize(); } finally { REAL.set(false); }
        Files.createDirectories(directory);
        Files.write(file, pcm);
        return pcm;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
