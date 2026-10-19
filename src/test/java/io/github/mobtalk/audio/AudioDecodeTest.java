package io.github.mobtalk.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioDecodeTest {
    private static byte[] wav(int rate, int channels, short[] interleaved) {
        ByteBuffer out = ByteBuffer.allocate(44 + interleaved.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes()).putInt(36 + interleaved.length * 2).put("WAVE".getBytes());
        out.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        out.put("data".getBytes()).putInt(interleaved.length * 2);
        for (short sample : interleaved) out.putShort(sample);
        return out.array();
    }

    // A 48 kHz stereo WAV becomes 24 kHz mono of half the frames, channels averaged.
    @Test void wavIsDownmixedAndResampled() throws IOException {
        short[] stereo = new short[48_000 * 2];
        for (int i = 0; i < 48_000; i++) { stereo[i * 2] = 1000; stereo[i * 2 + 1] = 3000; }
        byte[] pcm = AudioDecode.toPcm24k(wav(48_000, 2, stereo), "wav");
        assertEquals(24_000 * 2, pcm.length, "one second at 24 kHz mono");
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2000, samples.getShort(0)); assertEquals(2000, samples.getShort(pcm.length - 2));
    }

    @Test void pcmPassesThroughAndJunkIsRefused() throws IOException {
        byte[] pcm = {1, 0, 2, 0};
        assertSame(pcm, AudioDecode.toPcm24k(pcm, "pcm"));
        assertThrows(IOException.class, () -> AudioDecode.toPcm24k(new byte[]{1, 2, 3}, "wav"));
        assertThrows(IOException.class, () -> AudioDecode.toPcm24k(new byte[]{1, 2, 3}, "mp3"));
        assertThrows(IOException.class, () -> AudioDecode.toPcm24k(new byte[0], "pcm"));
        assertThrows(IOException.class, () -> AudioDecode.toPcm24k(pcm, "ogg"));
    }

    // 8 kHz mono up to 24 kHz: three output samples per input one, interpolated between neighbours.
    @Test void upsamplesByInterpolation() throws IOException {
        byte[] pcm = AudioDecode.toPcm24k(wav(8_000, 1, new short[]{0, 300, 600, 900}), "wav");
        assertEquals(12 * 2, pcm.length);
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, samples.getShort(0)); assertEquals(100, samples.getShort(2)); assertEquals(200, samples.getShort(4)); assertEquals(300, samples.getShort(6));
    }
}
