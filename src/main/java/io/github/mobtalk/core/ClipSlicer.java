package io.github.mobtalk.core;

import java.nio.ByteBuffer;

/** Режет растущую озвучку на чанки {@code SpeechClipS2C}, пока она ещё скачивается: сервер шлёт первый чанк, как только пришло
 * {@code minChunk} байт, и игроки слышат моба почти так же быстро, как в одиночке. Чанк с {@code count = 0} значит «будут ещё»;
 * последний несёт настоящее число чанков ({@code index + 1}), так клиент узнаёт конец. Целый клип режется так же. */
public final class ClipSlicer {
    /** Сырой звук мода: 16 бит, моно, 24 кГц, little-endian. */
    public static final String PCM = "pcm24k";
    /** Пятая часть секунды: чанк не меньше, пока звук ещё идёт. */
    public static final int MIN_CHUNK = 9_600;

    public record Piece(int index, int count, byte[] data) {
        public boolean last() { return count > 0; }
    }

    private final SpeechStream audio;
    private final int minChunk, maxChunk;
    private int offset, index;
    private boolean done;

    public ClipSlicer(SpeechStream audio, int minChunk, int maxChunk) {
        this.audio = audio;
        this.maxChunk = Math.max(2, maxChunk) & ~1;
        this.minChunk = Math.max(2, Math.min(minChunk, this.maxChunk));
    }

    /** Следующий готовый чанк или null, если ждать дальше (или всё отдано). Не блокирует. */
    public Piece next() {
        if (done) return null;
        boolean finished = audio.finished();
        int available = audio.size() - offset;
        if (!finished && available < minChunk) return null;
        int amount = Math.min(Math.max(0, available), maxChunk) & ~1;
        byte[] data = new byte[amount];
        if (amount > 0) {
            ByteBuffer target = ByteBuffer.wrap(data);
            int read = audio.read(offset, target, amount);
            if (read != amount) { data = java.util.Arrays.copyOf(data, Math.max(0, read)); amount = Math.max(0, read); }
        }
        offset += amount;
        boolean last = finished && offset >= audio.size();
        if (last) done = true;
        int position = index++;
        return new Piece(position, last ? position + 1 : 0, data);
    }

    public boolean done() { return done; }
    /** Байт отдано (журнал и тесты). */
    public int sent() { return offset; }
}
