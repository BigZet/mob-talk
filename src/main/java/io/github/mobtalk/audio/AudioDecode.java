package io.github.mobtalk.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Turns what a speech provider returned into the one thing the mod plays: signed 16-bit mono PCM at 24 kHz.
 * Some Venice models only speak MP3 or WAV; the decode happens on the conversation worker, never the game thread. */
public final class AudioDecode {
    public static final int RATE = 24_000;
    private AudioDecode() { }

    /** {@code format}: "pcm" (returned as is), "mp3" (JLayer) or "wav" (header parsed); anything else is an error. */
    public static byte[] toPcm24k(byte[] data, String format) throws IOException {
        if (data == null || data.length == 0) throw new IOException("Озвучка пришла пустой.");
        return switch (format) {
            case "pcm" -> data;
            case "mp3" -> mp3(data);
            case "wav" -> wav(data);
            default -> throw new IOException("Неизвестный формат озвучки: " + format);
        };
    }

    static byte[] mp3(byte[] mp3) throws IOException {
        try {
            Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3));
            Decoder decoder = new Decoder();
            ByteArrayOutputStream out = new ByteArrayOutputStream(mp3.length * 12);
            Resampler resampler = null;
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (resampler == null) resampler = new Resampler(frame.getSampleFrequency(), frame.getChannelCount());
                resampler.push(frame.getBuffer(), frame.getBufferLength(), out);
                bitstream.closeFrame();
            }
            bitstream.close();
            if (out.size() < 2) throw new IOException("MP3 без звука.");
            return out.toByteArray();
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Не удалось раскодировать MP3 озвучку."); }
    }

    static byte[] wav(byte[] wav) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 44 || in.getInt(0) != 0x46464952 || in.getInt(8) != 0x45564157) throw new IOException("Это не WAV.");
        int position = 12, channels = 1, rate = RATE, bits = 16;
        while (position + 8 <= wav.length) {
            int id = in.getInt(position), size = in.getInt(position + 4);
            int body = position + 8;
            if (id == 0x20746d66) { // "fmt "
                if (in.getShort(body) != 1) throw new IOException("WAV не в PCM.");
                channels = in.getShort(body + 2); rate = in.getInt(body + 4); bits = in.getShort(body + 14);
            } else if (id == 0x61746164) { // "data"
                int length = Math.min(size, wav.length - body);
                if (bits != 16) throw new IOException("WAV не 16-битный.");
                short[] samples = new short[length / 2];
                for (int i = 0; i < samples.length; i++) samples[i] = in.getShort(body + i * 2);
                ByteArrayOutputStream out = new ByteArrayOutputStream(length);
                new Resampler(rate, channels).push(samples, samples.length, out);
                return out.toByteArray();
            }
            position = body + size + (size & 1);
        }
        throw new IOException("В WAV нет данных.");
    }

    /** Interleaved 16-bit samples at any rate and channel count → mono 24 kHz, linear interpolation. */
    static final class Resampler {
        private final int rate, channels;
        private double cursor;

        Resampler(int rate, int channels) { this.rate = Math.max(8000, rate); this.channels = Math.max(1, channels); }

        void push(short[] interleaved, int length, ByteArrayOutputStream out) {
            int frames = length / channels;
            if (frames == 0) return;
            double step = (double) rate / RATE;
            // Position is kept in input frames; a frame's mono value is the channel average.
            while (cursor < frames) {
                int index = (int) cursor;
                double fraction = cursor - index;
                short a = mono(interleaved, index);
                short b = index + 1 < frames ? mono(interleaved, index + 1) : a;
                int value = (int) Math.round(a + (b - a) * fraction);
                out.write(value & 0xFF); out.write((value >> 8) & 0xFF);
                cursor += step;
            }
            cursor -= frames;
        }
        private short mono(short[] interleaved, int frame) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += interleaved[frame * channels + c];
            return (short) (sum / channels);
        }
    }
}
