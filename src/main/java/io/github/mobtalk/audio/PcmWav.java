package io.github.mobtalk.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** The microphone/Whisper boundary is signed 16-bit mono PCM at 16 kHz. */
public final class PcmWav {
    public static final int SAMPLE_RATE = 16_000;
    public static final int MAX_SECONDS = 15;
    public static final int MAX_PCM_BYTES = 480_000;

    private PcmWav() { }

    public static int maxBytes(int seconds) {
        if (seconds < 1) throw new IllegalArgumentException("Recording duration must be positive");
        return Math.min(MAX_SECONDS, seconds) * SAMPLE_RATE * 2;
    }

    public static byte[] encode(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > MAX_PCM_BYTES)
            throw new IllegalArgumentException("Audio must contain 1 to 15 seconds of complete PCM samples");
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return wav.array();
    }
}
