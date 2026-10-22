package io.github.mobtalk.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Звук реплики по сети: сервер режет растущую озвучку на чанки, клиент собирает их в исходные байты; потеря чанка отбрасывает
 * клип без исключений (трек B). */
class ClipChunkingTest {
    private static final int MAX_CHUNK = 512 * 1024;
    private final AtomicLong clock = new AtomicLong();

    private static byte[] pcm(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) (i * 31);
        return data;
    }

    private static byte[] drain(SpeechStream stream) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        for (int offset = 0; ; ) {
            buffer.clear();
            int read = stream.read(offset, buffer, buffer.capacity());
            if (read < 0) return out.toByteArray();
            if (read == 0) throw new AssertionError("поток не закончен");
            out.write(buffer.array(), 0, read);
            offset += read;
        }
    }

    private static List<ClipSlicer.Piece> slice(SpeechStream audio, int min, int max) {
        ClipSlicer slicer = new ClipSlicer(audio, min, max);
        List<ClipSlicer.Piece> pieces = new ArrayList<>();
        for (ClipSlicer.Piece piece; (piece = slicer.next()) != null; ) pieces.add(piece);
        return pieces;
    }

    @Test void wholeClipIsCutIntoChunksAndReassembled() {
        byte[] data = pcm(1_300_000);
        List<ClipSlicer.Piece> pieces = slice(SpeechStream.of(data), ClipSlicer.MIN_CHUNK, MAX_CHUNK);
        assertEquals(3, pieces.size());
        pieces.forEach(piece -> assertTrue(piece.data().length <= MAX_CHUNK));
        assertEquals(0, pieces.get(0).count());
        assertEquals(3, pieces.get(2).count());
        ClipReceiver receiver = new ClipReceiver(6 << 20, 15_000, clock::get);
        for (ClipSlicer.Piece piece : pieces) receiver.accept(7, piece.index(), piece.count(), ClipSlicer.PCM, piece.data());
        assertArrayEquals(data, drain(receiver.take(7)));
        assertEquals(0, receiver.pending());
    }

    @Test void growingAudioIsSentAsItArrivesAndPlaysBeforeTheEnd() {
        SpeechStream audio = SpeechStream.open(1 << 20);
        ClipSlicer slicer = new ClipSlicer(audio, ClipSlicer.MIN_CHUNK, MAX_CHUNK);
        ClipReceiver receiver = new ClipReceiver(6 << 20, 15_000, clock::get);
        SpeechStream playing = receiver.take(3); // MobSpeechS2C пришёл раньше звука
        audio.feed(pcm(4_000));
        assertNull(slicer.next(), "меньше пятой части секунды — ждём");
        audio.feed(pcm(10_000));
        ClipSlicer.Piece first = slicer.next();
        assertEquals(14_000, first.data().length);
        assertFalse(first.last());
        receiver.accept(3, first.index(), first.count(), ClipSlicer.PCM, first.data());
        assertEquals(14_000, playing.size());
        assertFalse(playing.finished(), "звук играет, пока скачивается остальное");
        audio.feed(pcm(100));
        audio.end();
        ClipSlicer.Piece last = slicer.next();
        assertTrue(last.last());
        assertEquals(2, last.count());
        receiver.accept(3, last.index(), last.count(), ClipSlicer.PCM, last.data());
        assertTrue(playing.finished());
        assertEquals(14_100, drain(playing).length);
        assertNull(slicer.next());
        assertTrue(slicer.done());
    }

    @Test void emptyEndStillClosesTheClip() {
        SpeechStream audio = SpeechStream.open(1 << 20);
        audio.feed(pcm(20_000));
        ClipSlicer slicer = new ClipSlicer(audio, ClipSlicer.MIN_CHUNK, MAX_CHUNK);
        assertEquals(0, slicer.next().count());
        audio.end();
        ClipSlicer.Piece end = slicer.next();
        assertEquals(0, end.data().length);
        assertEquals(2, end.count());
    }

    @Test void lostOrForeignChunkDropsTheClipWithoutCrash() {
        ClipReceiver receiver = new ClipReceiver(6 << 20, 15_000, clock::get);
        receiver.accept(5, 0, 0, ClipSlicer.PCM, pcm(100));
        receiver.accept(5, 2, 3, ClipSlicer.PCM, pcm(100)); // чанк 1 потерян
        SpeechStream broken = receiver.take(5);
        assertTrue(broken.finished(), "дальше не ждём: звук обрывается на пришедшем");
        assertEquals(1, receiver.pending(), "сломанный клип не принимает новых чанков");
        receiver.accept(5, 1, 3, ClipSlicer.PCM, pcm(100));
        assertEquals(100, broken.size());
        receiver.accept(6, 0, 1, "flac", pcm(10));
        assertTrue(receiver.take(6).finished());
        assertNull(receiver.take(0), "0 — реплика без звука");
    }

    @Test void encodedClipIsDecodedWhenComplete() throws Exception {
        byte[] wav = io.github.mobtalk.audio.PcmWav.encode(pcm(32_000)); // 1 с 16 кГц
        ClipReceiver receiver = new ClipReceiver(6 << 20, 15_000, clock::get);
        receiver.accept(9, 0, 2, "wav", java.util.Arrays.copyOfRange(wav, 0, 1000));
        SpeechStream stream = receiver.take(9);
        assertFalse(stream.finished());
        receiver.accept(9, 1, 2, "wav", java.util.Arrays.copyOfRange(wav, 1000, wav.length));
        assertTrue(stream.finished());
        assertEquals(48_000, drain(stream).length, 2, "1 с в 24 кГц");
    }

    @Test void staleClipsAreForgotten() {
        ClipReceiver receiver = new ClipReceiver(6 << 20, 15_000, clock::get);
        receiver.accept(1, 0, 0, ClipSlicer.PCM, pcm(100));
        SpeechStream taken = receiver.take(2);
        receiver.accept(2, 0, 0, ClipSlicer.PCM, pcm(100));
        clock.set(16_000);
        receiver.prune();
        assertEquals(0, receiver.pending());
        assertTrue(taken.finished(), "взятый клип доигрывает пришедшее");
        assertEquals(100, drain(taken).length);
    }
}
