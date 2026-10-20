package io.github.divineblock.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** The microphone/Whisper boundary: signed 16-bit mono PCM at 16 kHz, a prayer is 15 seconds at most. From Mob Talk. */
public final class PcmWav {
    public static final int SAMPLE_RATE = 16_000;
    public static final int MAX_SECONDS = 15;
    public static final int MAX_PCM_BYTES = MAX_SECONDS * SAMPLE_RATE * 2;

    private PcmWav() { }

    public static int maxBytes(int seconds) {
        if (seconds < 1) throw new IllegalArgumentException("Recording duration must be positive");
        return Math.min(MAX_SECONDS, seconds) * SAMPLE_RATE * 2;
    }

    /** 24 kHz speech (the god's voice) down to Whisper's 16 kHz, cut to the 15-second limit; linear interpolation. */
    public static byte[] from24k(byte[] pcm) {
        int n = pcm.length / 2, m = (int) Math.min((long) n * 2 / 3, (long) MAX_SECONDS * SAMPLE_RATE);
        byte[] out = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            double source = i * 1.5; int k = (int) source; double f = source - k;
            int s0 = (short) ((pcm[2 * k] & 255) | (pcm[2 * k + 1] << 8));
            int s1 = k + 1 < n ? (short) ((pcm[2 * k + 2] & 255) | (pcm[2 * k + 3] << 8)) : s0;
            int v = (int) Math.round(s0 + (s1 - s0) * f);
            out[2 * i] = (byte) v; out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    public static byte[] encode(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > MAX_PCM_BYTES)
            throw new IllegalArgumentException("Audio must contain up to 15 seconds of complete PCM samples");
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return wav.array();
    }
}
