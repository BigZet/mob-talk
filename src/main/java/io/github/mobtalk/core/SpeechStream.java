package io.github.mobtalk.core;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** 16-bit mono PCM that keeps arriving while it already plays. Written by the HTTP client, read by the sound executor. */
public final class SpeechStream implements AutoCloseable {
    private final int maxBytes;
    private byte[] data = new byte[48_000];
    private int size;
    private boolean finished, closed;
    private Runnable cancel = () -> { };

    SpeechStream(int maxBytes) { this.maxBytes = maxBytes; }

    /** A stream that is already complete, for audio that was synthesised in one piece. */
    public static SpeechStream of(byte[] pcm) {
        SpeechStream stream = new SpeechStream(Math.max(1, pcm.length));
        stream.append(ByteBuffer.wrap(pcm));
        stream.finish();
        return stream;
    }

    /** An empty stream that something outside this package fills: the client feeds it the chunks of a clip from the server. */
    public static SpeechStream open(int maxBytes) { return new SpeechStream(Math.max(2, maxBytes)); }
    /** Appends received audio; false once the stream was closed or ended. */
    public boolean feed(byte[] pcm) { return pcm == null || append(ByteBuffer.wrap(pcm)); }
    /** No more audio will come. */
    public void end() { finish(); }
    /** Bytes received so far (the server slices the growing stream into chunks). */
    public synchronized int size() { return size & ~1; }

    synchronized void onCancel(Runnable action) { cancel = action; if (closed) action.run(); }

    /** Returns false when the stream was closed or grew past its limit, telling the download to stop. */
    synchronized boolean append(ByteBuffer bytes) {
        if (closed || finished) return false;
        int amount = bytes.remaining();
        if (amount > maxBytes - size) { finished = true; notifyAll(); return false; }
        if (size + amount > data.length) data = Arrays.copyOf(data, Math.min(maxBytes, Math.max(size + amount, data.length * 2)));
        bytes.get(data, size, amount);
        size += amount;
        notifyAll();
        return true;
    }

    /** Ends the stream normally or after a failure; audio received so far stays playable. */
    synchronized void finish() {
        // An odd trailing byte would misalign the last 16-bit sample.
        size &= ~1;
        finished = true;
        notifyAll();
    }

    /** Waits until at least the given number of bytes arrived or the stream ended; true when any audio is playable. */
    public synchronized boolean awaitPrebuffer(int bytes, long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (!finished && !closed && size < bytes) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return !closed && (size & ~1) >= 2;
    }

    /** Zero crossings per second of 24 kHz 16-bit mono PCM: speech is well over a thousand, a plain tone is twice its pitch (the
     * self-test tone gives ~440), silence is 0. For logs that tell what kind of audio really went through. */
    public static long crossingsPerSecond(byte[] pcm) {
        int samples = pcm == null ? 0 : pcm.length / 2;
        if (samples < 2) return 0;
        int crossings = 0; boolean negative = false;
        for (int i = 0; i < samples; i++) {
            boolean now = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)) < 0;
            if (i > 0 && now != negative) crossings++;
            negative = now;
        }
        return Math.round(crossings * 24_000.0 / samples);
    }

    /** The whole audio once the download ends; for callers that cannot play a growing stream. */
    public synchronized byte[] awaitAll(long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (!finished && !closed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return Arrays.copyOf(data, size & ~1);
    }

    /** Copies up to max bytes from the given offset; -1 at the end of a finished stream, 0 while more audio is still coming. */
    public synchronized int read(int offset, ByteBuffer target, int max) {
        int available = (size & ~1) - offset;
        if (available <= 0) return finished || closed ? -1 : 0;
        int amount = Math.min(available, max) & ~1;
        target.put(data, offset, amount);
        return amount;
    }

    public synchronized boolean finished() { return finished || closed; }

    /** Stops the download; safe from any thread and more than once. */
    @Override public void close() {
        Runnable action;
        synchronized (this) {
            if (closed) return;
            closed = true; notifyAll(); action = cancel;
        }
        action.run();
    }
}
