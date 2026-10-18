package io.github.mobtalk.core;

/** Player-directed combat permission; physical reach, damage and invulnerability remain game rules. */
public final class CombatRules {
    /** From this relationship a hostile mob keeps a truce with the player: it neither hunts nor answers a conversation with an attack.
     * Any blow ends it: the first drops a stranger to −80, a later one leaves the mob below zero. */
    public static final int TRUCE = 20;
    /** From this relationship the mob counts the player as its own: it may follow and fight for them like a tamed one. */
    public static final int ALLY = 60;
    /** At or below this relationship a peaceful creature treats the player as an enemy on sight: it attacks when it can win,
     * flees (and a villager calls the golem) when it cannot, and refuses any concession. */
    public static final int HATRED = -40;
    private CombatRules() { }

    public static boolean mayAttackPlayer(boolean hostileSpecies, int relationship, boolean alreadyAggressive) {
        if (relationship >= TRUCE) return false;
        return hostileSpecies || alreadyAggressive || relationship <= HATRED;
    }
    /** A hostile species that earned the player's trust leaves them alone; peaceful species never needed a truce. */
    public static boolean truce(boolean hostileSpecies, int relationship) { return hostileSpecies && relationship >= TRUCE; }
}
