package io.github.divineblock.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The spins of the punishment roulette by server tick: one at a time for each sinner (a second one waits for the first),
 * the god's word at the final, the act exactly at start + {@link Roulette.Timing#act}, and cancelling — by a pardon, the sinner
 * leaving, dying or going to another world. The client never moves these moments. Server thread; plain Java, for the tests. */
public final class RouletteQueue {
    public enum Cancel { PARDON, LEFT, DIED, WORLD, STOPPED }

    /** What the game does at each moment of a spin. */
    public interface Hooks {
        /** The spin starts now (at once or after the one before it): show it. */
        default void started(Spin spin) { }
        /** The third reel stood still: the ribbon, the chat line, the god's word. */
        default void fin(Spin spin) { }
        /** The machine leaves: the punishment falls. */
        void act(Spin spin);
        /** The machine leaves on a dove: the sinner is spared by chance, nothing falls. */
        default void spared(Spin spin) { }
        /** It will not fall from here: what to do instead. */
        default void cancelled(Spin spin, Cancel why) { }
    }

    public static final class Spin {
        public final int id;
        public final UUID player;
        public final Roulette.Timing timing;
        public final Hooks hooks;
        /** Spared by chance: at its end nothing falls ({@link Hooks#spared} instead of {@link Hooks#act}). */
        public final boolean mercy;
        public long start;
        boolean finished;
        Spin(int id, UUID player, Roulette.Timing timing, Hooks hooks, boolean mercy) {
            this.id = id; this.player = player; this.timing = timing; this.hooks = hooks; this.mercy = mercy;
        }
        public long actTick() { return start + timing.act(); }
    }

    private final Map<UUID, Spin> active = new HashMap<>();
    private final Map<UUID, Deque<Spin>> waiting = new HashMap<>();
    private int nextId = 1;

    /** A new spin for {@code player}: it starts at {@code now}, or after the ones already spinning or waiting for him. */
    public Spin offer(UUID player, long now, Roulette.Timing timing, Hooks hooks) { return offer(player, now, timing, hooks, false); }

    /** As above; a spin with {@code mercy} ends in sparing the sinner, its punishment is never laid. */
    public Spin offer(UUID player, long now, Roulette.Timing timing, Hooks hooks, boolean mercy) {
        Spin spin = new Spin(nextId++, player, timing, hooks, mercy);
        if (active.containsKey(player)) waiting.computeIfAbsent(player, p -> new ArrayDeque<>()).add(spin);
        else begin(spin, now);
        return spin;
    }

    private void begin(Spin spin, long now) {
        spin.start = now;
        active.put(spin.player, spin);
        spin.hooks.started(spin);
    }

    /** Runs what is due at {@code now}: the finals, the acts, and the next spin of a sinner whose spin ended. */
    public void tick(long now) {
        List<Spin> due = new ArrayList<>(active.values());
        for (Spin spin : due) {
            if (active.get(spin.player) != spin) continue;
            if (!spin.finished && now >= spin.start + spin.timing.fin()) { spin.finished = true; spin.hooks.fin(spin); }
            if (now < spin.actTick() || active.get(spin.player) != spin) continue;
            active.remove(spin.player);
            if (spin.mercy) spin.hooks.spared(spin); else spin.hooks.act(spin);
            Deque<Spin> next = waiting.get(spin.player);
            if (next != null && !next.isEmpty() && !active.containsKey(spin.player)) {
                Spin following = next.poll();
                if (next.isEmpty()) waiting.remove(spin.player);
                begin(following, now);
            }
        }
    }

    /** Stops his spin and those waiting: none of them falls from here. The spin that was showing, or null. */
    public Spin cancel(UUID player, Cancel why) {
        Spin spin = active.remove(player);
        Deque<Spin> queued = waiting.remove(player);
        if (spin != null) spin.hooks.cancelled(spin, why);
        if (queued != null) for (Spin one : queued) one.hooks.cancelled(one, why);
        return spin;
    }

    public boolean spinning(UUID player) { return active.containsKey(player); }
    public Spin of(UUID player) { return active.get(player); }
    public List<Spin> all() { return new ArrayList<>(active.values()); }
    public void clear() { active.clear(); waiting.clear(); }
}
