package io.github.mobtalk.core;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** What a creature does about the others it knows, decided from plain numbers so the game layer only moves it: friends with
 * time on their hands drift towards each other, enemies keep their distance, a friend comes to sit with a mourner once.
 * Only while the player is near enough to see it, never for a creature that is busy, fighting, talking or hostile by nature. */
public final class SocialRules {
    /** Beyond this distance from the player nothing social happens: nobody is watching. */
    public static final double PLAYER_RANGE = 32;
    /** Two creatures this close are "together" and warm to each other. */
    public static final double TOGETHER_RANGE = 8;
    /** A friend further than this (and nearer than {@link #APPROACH_MAX}) may be walked up to; closer, it already is near. */
    public static final double APPROACH_MIN = 6, APPROACH_MAX = 24;
    /** An enemy this close makes the creature step away. */
    public static final double RETREAT_RANGE = 4;
    /** The chance, per check, that a creature with a friend in sight actually walks over: once in a while, not a leash. */
    public static final double APPROACH_CHANCE = 1.0 / 8;
    /** How long a consoling friend stands with the mourner, in ticks (10–20 s), and how long the visit may take to arrive. */
    public static final int VISIT_MIN_TICKS = 200, VISIT_MAX_TICKS = 400, VISIT_DEADLINE_TICKS = 600;

    public enum Move { NONE, APPROACH, RETREAT }
    /** The creature deciding: {@code busy} covers a lease, a conversation, a fight, panic or disabled AI. */
    public record Actor(UUID id, boolean busy, boolean hostileSpecies, double playerDistance) {
        public boolean free() { return !busy && !hostileSpecies && playerDistance <= PLAYER_RANGE; }
    }
    public record Decision(Move move, UUID target) {
        public static final Decision NONE = new Decision(Move.NONE, null);
    }

    private SocialRules() { }

    /** The one move for this check: step away from the nearest enemy within {@link #RETREAT_RANGE}, else, with
     * {@link #APPROACH_CHANCE} ({@code roll} in 0..1), walk towards the strongest friend that is loaded and in
     * walking distance. {@code distanceTo} gives the distance to another creature, or null when it is not around. */
    public static Decision decide(Actor self, List<MobRelations.View> bonds, Function<UUID, Double> distanceTo, double roll) {
        if (self == null || !self.free() || bonds == null || bonds.isEmpty()) return Decision.NONE;
        MobRelations.View enemy = null; double nearest = Double.MAX_VALUE;
        for (MobRelations.View bond : bonds) {
            if (!bond.rival()) continue;
            Double distance = distanceTo.apply(bond.other());
            if (distance != null && distance < RETREAT_RANGE && distance < nearest) { nearest = distance; enemy = bond; }
        }
        if (enemy != null) return new Decision(Move.RETREAT, enemy.other());
        if (roll >= APPROACH_CHANCE) return Decision.NONE;
        for (MobRelations.View bond : bonds) { // Strongest first: the list is sorted.
            if (!bond.friend() || bond.mourns()) continue;
            Double distance = distanceTo.apply(bond.other());
            if (distance != null && distance > APPROACH_MIN && distance <= APPROACH_MAX) return new Decision(Move.APPROACH, bond.other());
        }
        return Decision.NONE;
    }

    /** Whether {@code candidate} should go and sit with a mourner: the mourner still grieves a loss nobody shared, and counts
     * the candidate among its friends. */
    public static boolean shouldVisit(List<MobRelations.View> mournerBonds, UUID candidate) {
        if (mournerBonds == null || candidate == null) return false;
        boolean grieving = false, friend = false;
        for (MobRelations.View bond : mournerBonds) {
            if (bond.mourns() && !bond.consoled()) grieving = true;
            if (bond.friend() && candidate.equals(bond.other())) friend = true;
        }
        return grieving && friend;
    }

    /** Whether two creatures this far apart count as together for the slow warming. */
    public static boolean together(double distance) { return distance >= 0 && distance <= TOGETHER_RANGE; }
}
