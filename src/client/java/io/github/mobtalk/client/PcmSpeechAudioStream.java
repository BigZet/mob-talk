package io.github.mobtalk.client;

import io.github.mobtalk.core.SpeechStream;
import net.minecraft.client.sound.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/** ElevenLabs PCM 24 kHz played by Minecraft's sound executor while the rest of it still downloads. */
final class PcmSpeechAudioStream implements AudioStream {
    private static final AudioFormat FORMAT = new AudioFormat(24_000, 16, 1, true, false);
    // Small chunks keep several OpenAL buffers queued, so each tick refills them instead of one long buffer draining.
    private static final int CHUNK = 4_800, SILENCE = 960;
    private final SpeechStream speech;
    private int offset;
    private ByteBuffer buffer;

    PcmSpeechAudioStream(SpeechStream speech) { this.speech = speech; }
    @Override public AudioFormat getFormat() { return FORMAT; }

    @Override public ByteBuffer read(int size) {
        int max = Math.min(size, CHUNK) & ~1;
        if (max < 2) return null;
        if (buffer == null) buffer = ByteBuffer.allocateDirect(CHUNK);
        buffer.clear();
        int amount = speech.read(offset, buffer, max);
        if (amount < 0) return null;
        if (amount == 0) {
            // The download is behind playback: 20 ms of silence keeps the source alive instead of ending the speech.
            for (int i = 0; i < SILENCE; i++) buffer.put((byte) 0);
        }
        offset += amount;
        buffer.flip();
        return buffer;
    }

    @Override public void close() { speech.close(); buffer = null; }
}
