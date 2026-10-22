package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A punishment announced to a mortal who left the server before it fell. On a server a sinner must not escape by logging
 * out: the verdict waits for him up to one game day ({@link #WAIT_TICKS}, overworld time) and falls when he comes back; after
 * that it is written off. Mercy and jests are not kept: they are cancelled on the spot. Plain data for Gson, saved with the
 * ledger; only the latest punishment of each mortal waits. */
public final class DeferredWrath {
    public static final long WAIT_TICKS = 24_000;

    public static final class Held {
        public Verdict verdict;
        public String name = "", world = "";
        public int[] altar;
        public long heldAt;

        public boolean expired(long now) { return now - heldAt > WAIT_TICKS; }
    }

    /** Player UUID → his waiting punishment. */
    public Map<String, Held> waiting = new HashMap<>();

    /** Whether this verdict is kept for the mortal's return at all: only punishments are. */
    public static boolean keeps(Verdict verdict) { return verdict != null && verdict.chosen().kind == Miracle.Kind.WRATH; }

    /** Keeps the punishment; returns false (and keeps nothing) for mercy, jests and omens. */
    public boolean hold(UUID player, String name, Verdict verdict, String world, int[] altar, long now) {
        if (!keeps(verdict)) return false;
        Held held = new Held();
        held.verdict = verdict; held.name = name == null ? "" : name; held.world = world == null ? "" : world;
        held.altar = altar == null ? null : altar.clone(); held.heldAt = now;
        waiting.put(player.toString(), held);
        return true;
    }

    /** The mortal is back: his waiting punishment, taken out of the list, or null. One past its day is still returned, so
     * the caller can write it off instead of performing it ({@link Held#expired}). */
    public Held claim(UUID player) { return waiting.remove(player.toString()); }

    /** Whether something waits for this mortal (expired or not). */
    public boolean waits(UUID player) { return waiting.containsKey(player.toString()); }

    /** Punishments whose day has passed, removed from the list: the caller writes them off in the journal. */
    public List<Held> expire(long now) {
        List<Held> out = new ArrayList<>();
        waiting.values().removeIf(held -> { if (!held.expired(now)) return false; out.add(held); return true; });
        return out;
    }
}
