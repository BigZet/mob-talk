package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Holds the roulette back until the god has said his word: a punishment with a roulette waits for the sinner's client to
 * report that the voice ended (or failed), then for a short pause while the temple's echo dies away, and only then the reels
 * start. A client that never answers is not waited for beyond a deadline: the estimate of the words plus two seconds, fifteen
 * seconds at most. A pardon while the god still speaks closes the gate: nothing follows. Server thread; plain Java, for the
 * tests. */
public final class SpeechGate {
    /** Ticks between the last word and the first turn of the reels: the echo fades. */
    public static final int TAIL_TICKS = 10;
    /** The longest the roulette waits for the voice, whatever the words. */
    public static final int MAX_WAIT_TICKS = 20 * 15;

    private static final class Waiting {
        final Runnable then, cancelled;
        final long deadline;
        long releaseAt = Long.MAX_VALUE;
        Waiting(Runnable then, Runnable cancelled, long deadline) { this.then = then; this.cancelled = cancelled; this.deadline = deadline; }
    }

    private final Map<UUID, Waiting> waiting = new HashMap<>();

    /** How long to wait for a line of {@code words} words: its spoken length (about 0.4 s a word, 1.5 s at least) plus two
     * seconds, fifteen seconds at most. */
    public static int timeoutTicks(int words) {
        double seconds = Math.max(1.5, words * 0.4) + 2;
        return (int) Math.min(MAX_WAIT_TICKS, Math.round(seconds * 20));
    }

    public static int words(String line) {
        if (line == null || line.isBlank()) return 0;
        return line.strip().split("\\s+").length;
    }

    /** {@code then} runs once the god's voice for {@code player} is done (or at the deadline from {@code now}). */
    public void open(UUID player, long now, int timeoutTicks, Runnable then) { open(player, now, timeoutTicks, then, () -> { }); }

    /** As above; {@code cancelled} runs if the gate is closed before (a pardon while the god speaks). */
    public void open(UUID player, long now, int timeoutTicks, Runnable then, Runnable cancelled) {
        waiting.put(player, new Waiting(then, cancelled, now + Math.max(1, Math.min(MAX_WAIT_TICKS, timeoutTicks))));
    }

    /** The client reports the voice ended (or could not play): the roulette follows after the echo's pause. */
    public void done(UUID player, long now) {
        Waiting one = waiting.get(player);
        if (one != null && one.releaseAt == Long.MAX_VALUE) one.releaseAt = now + TAIL_TICKS;
    }

    /** Runs what is released at {@code now}: by the voice's end and its pause, or by the deadline. */
    public void tick(long now) {
        if (waiting.isEmpty()) return;
        List<Runnable> due = new ArrayList<>();
        waiting.values().removeIf(one -> {
            if (now >= one.releaseAt || now >= one.deadline) { due.add(one.then); return true; }
            return false;
        });
        due.forEach(Runnable::run);
    }

    /** A pardon (or death, leaving) while the god speaks: nothing follows. Whether something was waiting. */
    public boolean cancel(UUID player) {
        Waiting one = waiting.remove(player);
        if (one == null) return false;
        one.cancelled.run();
        return true;
    }

    public boolean waiting(UUID player) { return waiting.containsKey(player); }

    public void clear() { waiting.clear(); }
}
