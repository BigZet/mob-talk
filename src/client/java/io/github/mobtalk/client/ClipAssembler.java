package io.github.mobtalk.client;

import io.github.mobtalk.audio.AudioDecode;
import io.github.mobtalk.net.SpeechClipS2C;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Собирает звук реплики из чанков {@link SpeechClipS2C} (трек A): когда пришли все {@code count} частей, отдаёт PCM 24 кГц 16 бит моно,
 * в котором играет {@link MobSpeechSound}; {@code pcm24k} берётся как есть, {@code mp3}/{@code wav} декодируются. Клип, у которого
 * части перестали приходить дольше {@link #STALE_MILLIS}, выбрасывается без краша. Только поток клиента. */
final class ClipAssembler {
    static final long STALE_MILLIS = 30_000;
    private static final class Partial {
        final byte[][] chunks; final String format; int received; long touched;
        Partial(int count, String format) { chunks = new byte[count][]; this.format = format; }
    }
    private final Map<Integer, Partial> partials = new HashMap<>();

    /** PCM клипа, когда он собран целиком, иначе null. Мусор (чанк не по счёту, нулевой номер) пропускается. */
    byte[] offer(SpeechClipS2C chunk) {
        if (chunk.clipId() == 0 || chunk.count() <= 0 || chunk.index() < 0 || chunk.index() >= chunk.count()) return null;
        Partial partial = partials.get(chunk.clipId());
        if (partial == null || partial.chunks.length != chunk.count()) { partial = new Partial(chunk.count(), chunk.format()); partials.put(chunk.clipId(), partial); }
        if (partial.chunks[chunk.index()] == null) partial.received++;
        partial.chunks[chunk.index()] = chunk.chunk();
        partial.touched = System.currentTimeMillis();
        if (partial.received < partial.chunks.length) return null;
        partials.remove(chunk.clipId());
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        for (byte[] part : partial.chunks) whole.writeBytes(part);
        byte[] data = whole.toByteArray();
        if (io.github.mobtalk.server.DialogueService.PCM_FORMAT.equals(partial.format) || "pcm".equals(partial.format)) return data;
        try { return AudioDecode.toPcm24k(data, partial.format); }
        catch (IOException | RuntimeException failure) { return null; }
    }
    void prune() {
        long now = System.currentTimeMillis();
        partials.values().removeIf(partial -> now - partial.touched > STALE_MILLIS);
    }
    void clear() { partials.clear(); }
    int pending() { return partials.size(); }
}
