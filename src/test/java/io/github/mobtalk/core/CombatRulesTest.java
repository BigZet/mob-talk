package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CombatRulesTest {
    @TempDir Path directory;

    // Self-defence must use the victim's grudge against this player, not any player's hit.
    @Test void aHitAllowsThePassiveVictimToRetaliateOnlyAgainstItsAttacker() throws Exception {
        UUID cow = UUID.randomUUID(), attacker = UUID.randomUUID(), other = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        try {
            store.recordDamage(cow, "minecraft:cow", "Корова", attacker, "Игрок", 1000);
            assertTrue(CombatRules.mayAttackPlayer(false, store.card(cow, attacker).relationship(), false));
            assertFalse(CombatRules.mayAttackPlayer(false, store.card(cow, other).relationship(), false));
        } finally { ConversationStore.closeWorld(directory); }
    }

    // Removing the anger gate would turn a harmless request into an unprovoked passive attack.
    @Test void aNeutralOrFriendlyMobNeedsRealAngerOrExistingAggression() {
        assertFalse(CombatRules.mayAttackPlayer(false, 70, false));
        assertFalse(CombatRules.mayAttackPlayer(false, 0, false));
        assertFalse(CombatRules.mayAttackPlayer(false, -10, false));
        assertTrue(CombatRules.mayAttackPlayer(false, -40, false));
        assertTrue(CombatRules.mayAttackPlayer(false, 0, true));
    }

    // Existing hostile combat must not depend on having first talked to or hit that mob.
    @Test void aNaturallyHostileMobDoesNotNeedARecordedPersonalGrudge() {
        assertTrue(CombatRules.mayAttackPlayer(true, 0, false));
        assertTrue(CombatRules.mayAttackPlayer(true, CombatRules.TRUCE - 1, true));
        assertFalse(CombatRules.mayAttackPlayer(true, CombatRules.TRUCE, false), "a truce holds even for a hostile species");
        assertFalse(CombatRules.mayAttackPlayer(true, CombatRules.TRUCE, true), "and even while the mob is already aggressive");
        assertTrue(CombatRules.truce(true, CombatRules.TRUCE)); assertFalse(CombatRules.truce(true, CombatRules.TRUCE - 1)); assertFalse(CombatRules.truce(false, 100));
    }
}
