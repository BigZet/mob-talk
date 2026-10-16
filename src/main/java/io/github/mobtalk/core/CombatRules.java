package io.github.mobtalk.core;

/** Player-directed combat permission; physical reach, damage and invulnerability remain game rules. */
public final class CombatRules {
    private CombatRules() { }

    public static boolean mayAttackPlayer(boolean hostileSpecies, int relationship, boolean alreadyAggressive) {
        return hostileSpecies || alreadyAggressive || relationship <= -40;
    }
}
