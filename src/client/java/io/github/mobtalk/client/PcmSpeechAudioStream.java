package io.github.mobtalk.client;

import net.minecraft.client.sound.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/** ElevenLabs PCM 24 kHz is supplied directly to Minecraft's ordinary sound executor. */
final class PcmSpeechAudioStream implements AudioStream {
    private static final AudioFormat FORMAT = new AudioFormat(24_000, 16, 1, true, false);
    private byte[] pcm;
    private int offset;
    private ByteBuffer buffer;

    PcmSpeechAudioStream(byte[] pcm) { this.pcm = pcm; }
    @Override public AudioFormat getFormat() { return FORMAT; }

    @Override public ByteBuffer read(int size) {
        int amount = Math.min(size & ~1, pcm.length - offset);
        if (amount < 2) return null;
        if (buffer == null || buffer.capacity() < amount) buffer = ByteBuffer.allocateDirect(amount);
        buffer.clear();
        buffer.put(pcm, offset, amount);
        offset += amount;
        buffer.flip();
        return buffer;
    }

    @Override public void close() { pcm = new byte[0]; buffer = null; }
}
