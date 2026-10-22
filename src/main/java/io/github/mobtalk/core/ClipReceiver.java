package io.github.mobtalk.core;

import io.github.mobtalk.audio.AudioDecode;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/** Клиентская сборка звука реплики из чанков {@code SpeechClipS2C} (раздел 12.1 плана): по {@code clipId} растёт
 * {@link SpeechStream}, который можно отдать на воспроизведение раньше, чем пришёл последний чанк. Сырой {@link ClipSlicer#PCM}
 * играет сразу; {@code mp3}/{@code wav} копятся и раскодируются, когда пришли все. Чанк не по порядку или сверх предела —
 * клип отброшен (поток закрыт), без исключений наружу. Забытые клипы чистит {@link #prune()}. */
public final class ClipReceiver {
    private final int maxBytes;
    private final long staleMillis;
    private final LongSupplier clock;
    private final Map<Integer, Entry> clips = new HashMap<>();

    private static final class Entry {
        final SpeechStream stream;
        ByteArrayOutputStream encoded;
        String format;
        int next;
        long touched;
        boolean taken, broken;
        Entry(SpeechStream stream, long now) { this.stream = stream; this.touched = now; }
    }

    public ClipReceiver(int maxBytes, long staleMillis, LongSupplier clock) {
        this.maxBytes = maxBytes;
        this.staleMillis = staleMillis;
        this.clock = clock;
    }

    /** Чанк от сервера. {@code count = 0} — будут ещё; иначе это общее число и чанк с {@code index = count - 1} последний. */
    public synchronized void accept(int clipId, int index, int count, String format, byte[] chunk) {
        if (clipId == 0) return;
        Entry entry = entry(clipId);
        entry.touched = clock.getAsLong();
        if (entry.broken) return;
        if (index != entry.next || (count > 0 && index >= count) || (entry.format != null && !entry.format.equals(format))) { drop(entry); return; }
        entry.format = format;
        entry.next++;
        byte[] data = chunk == null ? new byte[0] : chunk;
        boolean last = count > 0 && index == count - 1;
        if (ClipSlicer.PCM.equals(format)) {
            // Past maxBytes the stream ends itself: what came so far still plays, the rest is ignored.
            entry.stream.feed(data);
        } else {
            if (entry.encoded == null) entry.encoded = new ByteArrayOutputStream();
            if (entry.encoded.size() + data.length > maxBytes) { drop(entry); return; }
            entry.encoded.writeBytes(data);
            if (last) {
                try { entry.stream.feed(AudioDecode.toPcm24k(entry.encoded.toByteArray(), "wav16k".equals(format) ? "wav" : format)); }
                catch (java.io.IOException unreadable) { drop(entry); return; }
                entry.encoded = null;
            }
        }
        if (last) { entry.stream.end(); if (entry.taken) clips.remove(clipId); }
    }

    /** Поток клипа для воспроизведения; если чанки ещё не пришли, они дольются в него. 0 — без звука, null. */
    public synchronized SpeechStream take(int clipId) {
        if (clipId == 0) return null;
        Entry entry = entry(clipId);
        entry.taken = true;
        entry.touched = clock.getAsLong();
        // A broken clip stays until prune: its late chunks must not start a new one.
        if (!entry.broken && entry.stream.finished()) clips.remove(clipId);
        return entry.stream;
    }

    /** Клипы без чанков дольше {@code staleMillis}: невзятые закрываются, взятые заканчиваются тем, что успело прийти. */
    public synchronized void prune() {
        long now = clock.getAsLong();
        for (Iterator<Entry> it = clips.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (now - entry.touched < staleMillis) continue;
            if (entry.taken) entry.stream.end(); else entry.stream.close();
            it.remove();
        }
    }

    /** Выход из мира: всё закрыть. */
    public synchronized void clear() {
        clips.values().forEach(entry -> entry.stream.close());
        clips.clear();
    }

    public synchronized int pending() { return clips.size(); }

    private Entry entry(int clipId) { return clips.computeIfAbsent(clipId, id -> new Entry(SpeechStream.open(maxBytes), clock.getAsLong())); }

    private void drop(Entry entry) {
        entry.broken = true;
        entry.encoded = null;
        entry.stream.close();
    }
}
