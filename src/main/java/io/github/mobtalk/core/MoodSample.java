package io.github.mobtalk.core;

import java.util.Set;
import java.util.UUID;

/** What the world watch measured around one mob, for {@link ConversationStore#surroundings}: the lasting factors the game sees
 * directly (weather, night outdoors, monsters, an alarm, a wound, no bed or work), the creatures standing near (friends and
 * family are looked up in the store), and for a villager its sleep and whether its village has food in store ({@code larder}:
 * one meal a resident among the neighbours' pockets, or more). Its own hunger the store keeps ({@link Hunger}). {@code night}
 * is the index of the last night (it stays the same through the following day), {@code dark} whether it is night now. */
public record MoodSample(Set<MoodFactor> factors, Set<UUID> near, boolean villager, boolean sleeping, boolean larder, long night, boolean dark) {
    public MoodSample {
        factors = factors == null ? Set.of() : Set.copyOf(factors);
        near = near == null ? Set.of() : Set.copyOf(near);
    }
    /** The night index of a world time in ticks: from dusk of one day through the next day. */
    public static long nightOf(long timeOfDay) { return Math.floorDiv(timeOfDay - 12_000L, 24_000L); }
}
