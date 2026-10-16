package io.github.mobtalk.audio;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PcmWavTest {
    @Test void generatesReadableMono16BitLittleEndianWav() throws Exception {
        byte[] samples = {0, 0, (byte) 0xff, 0x7f, 0, (byte) 0x80, 42, 0};
        byte[] wav = PcmWav.encode(samples);
        try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            assertEquals(16_000f, stream.getFormat().getSampleRate());
            assertEquals(16, stream.getFormat().getSampleSizeInBits());
            assertEquals(1, stream.getFormat().getChannels());
            assertFalse(stream.getFormat().isBigEndian());
            assertEquals(4, stream.getFrameLength());
            assertArrayEquals(samples, stream.readAllBytes());
        }
    }

    @Test void capsConfiguredRecordingAtFifteenSeconds() {
        assertEquals(480_000, PcmWav.maxBytes(15));
        assertEquals(480_000, PcmWav.maxBytes(30));
        assertEquals(32_000, PcmWav.maxBytes(1));
    }

    @Test void refusesEmptyIncompleteAndOversizeAudio() {
        assertThrows(IllegalArgumentException.class, () -> PcmWav.encode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> PcmWav.encode(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> PcmWav.encode(new byte[480_002]));
        assertThrows(IllegalArgumentException.class, () -> PcmWav.maxBytes(0));
        assertThrows(IllegalArgumentException.class, () -> PcmWav.maxBytes(-1));
    }

    @Test void acceptsExactlyFifteenSecondsWithoutChangingSampleBytes() throws Exception {
        byte[] samples = new byte[480_000];
        samples[samples.length - 1] = 12;
        try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(PcmWav.encode(samples)))) {
            assertEquals(240_000, stream.getFrameLength());
            assertArrayEquals(samples, stream.readAllBytes());
        }
    }
}
