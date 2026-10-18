package io.github.mobtalk.core;

/** Player-directed combat permission; physical reach, damage and invulnerability remain game rules. */
public final class CombatRules {
    /** From this relationship a hostile mob keeps a truce with the player: it neither hunts nor answers a conversation with an attack.
     * The first blow drops the relationship to −80 and ends it. */
    public static final int TRUCE = 20;
    /** From this relationship the mob counts the player as its own: it may follow and fight for them like a tamed one. */
    public static final int ALLY = 60;
    private CombatRules() { }

    public static boolean mayAttackPlayer(boolean hostileSpecies, int relationship, boolean alreadyAggressive) {
        if (relationship >= TRUCE) return false;
        return hostileSpecies || alreadyAggressive || relationship <= -40;
    }
    /** A hostile species that earned the player's trust leaves them alone; peaceful species never needed a truce. */
    public static boolean truce(boolean hostileSpecies, int relationship) { return hostileSpecies && relationship >= TRUCE; }
}
