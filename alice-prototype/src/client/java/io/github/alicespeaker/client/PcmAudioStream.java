package io.github.alicespeaker.client;

import net.minecraft.client.sound.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/** Plays a finished mono 16-bit PCM buffer through Minecraft's sound engine. */
final class PcmAudioStream implements AudioStream {
    private final AudioFormat format;
    private final byte[] pcm;
    private int offset;

    PcmAudioStream(byte[] pcm, int rate) {
        this.pcm = pcm;
        this.format = new AudioFormat(rate, 16, 1, true, false);
    }

    @Override public AudioFormat getFormat() { return format; }

    @Override public ByteBuffer read(int size) {
        int amount = Math.min(Math.min(size, 9_600), pcm.length - offset) & ~1;
        if (amount <= 0) return null;
        ByteBuffer buffer = ByteBuffer.allocateDirect(amount);
        buffer.put(pcm, offset, amount).flip();
        offset += amount;
        return buffer;
    }

    @Override public void close() { offset = pcm.length; }
}
