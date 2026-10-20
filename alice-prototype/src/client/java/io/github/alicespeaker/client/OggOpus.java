package io.github.alicespeaker.client;

import io.github.jaredmdobson.concentus.OpusDecoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Alice's voice comes as Ogg/Opus; this turns it into mono 16-bit PCM at 48 kHz for OpenAL. */
final class OggOpus {
    static final int RATE = 48_000;

    private OggOpus() { }

    static boolean isOgg(byte[] data) {
        return data.length > 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S';
    }

    static byte[] toPcm(byte[] ogg) throws IOException {
        List<byte[]> packets = packets(ogg);
        if (packets.isEmpty() || !new String(packets.get(0), 0, Math.min(8, packets.get(0).length), StandardCharsets.US_ASCII).equals("OpusHead"))
            throw new IOException("В ответе Алисы не Opus");
        byte[] head = packets.get(0);
        int channels = Math.max(1, head[9] & 255);
        int preSkip = (head[10] & 255) | ((head[11] & 255) << 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(ogg.length * 12);
        try {
            OpusDecoder decoder = new OpusDecoder(RATE, channels);
            short[] pcm = new short[5_760 * channels];
            int skip = preSkip;
            // Packet 0 is OpusHead, packet 1 is OpusTags; audio starts after them.
            for (int i = 2; i < packets.size(); i++) {
                byte[] packet = packets.get(i);
                int samples = decoder.decode(packet, 0, packet.length, pcm, 0, 5_760, false);
                for (int s = 0; s < samples; s++) {
                    if (skip > 0) { skip--; continue; }
                    int mixed = 0;
                    for (int c = 0; c < channels; c++) mixed += pcm[s * channels + c];
                    short value = (short) (mixed / channels);
                    out.write(value & 255);
                    out.write((value >> 8) & 255);
                }
            }
        } catch (Exception broken) {
            throw new IOException("Не удалось декодировать голос Алисы: " + broken.getMessage(), broken);
        }
        return out.toByteArray();
    }

    private static List<byte[]> packets(byte[] data) {
        List<byte[]> packets = new ArrayList<>();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        int pos = 0;
        while (pos + 27 <= data.length) {
            if (data[pos] != 'O' || data[pos + 1] != 'g' || data[pos + 2] != 'g' || data[pos + 3] != 'S') {
                pos++;
                continue;
            }
            int segments = data[pos + 26] & 255;
            int body = pos + 27 + segments;
            if (body > data.length) break;
            int offset = body;
            for (int i = 0; i < segments; i++) {
                int lace = data[pos + 27 + i] & 255;
                if (offset + lace > data.length) return packets;
                packet.write(data, offset, lace);
                offset += lace;
                if (lace < 255) {
                    packets.add(packet.toByteArray());
                    packet.reset();
                }
            }
            pos = offset;
        }
        return packets;
    }
}
