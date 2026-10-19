package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SocialRulesTest {
    private final UUID self = UUID.randomUUID(), friend = UUID.randomUUID(), enemy = UUID.randomUUID(), dead = UUID.randomUUID();
    private static MobRelations.View view(UUID other, int score, MobRelations.Tag... tags) {
        return new MobRelations.View(other, "Кто-то", "minecraft:villager", score, 0, Set.of(tags), false);
    }
    private final List<MobRelations.View> bonds = List.of(view(friend, 60, MobRelations.Tag.FRIEND), view(enemy, -60, MobRelations.Tag.RIVAL));
    private static SocialRules.Actor free(UUID id) { return new SocialRules.Actor(id, false, false, 10); }

    // A free villager with a friend across the square walks over now and then, and never when the friend is already at hand
    // or out of reach; an enemy within four blocks is stepped away from first, whatever the dice say.
    @Test void approachesFriendsAndRetreatsFromEnemies() {
        Map<UUID, Double> distances = Map.of(friend, 12.0, enemy, 20.0);
        assertEquals(new SocialRules.Decision(SocialRules.Move.APPROACH, friend), SocialRules.decide(free(self), bonds, distances::get, 0.0));
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(free(self), bonds, distances::get, 0.9), "most checks do nothing");
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(free(self), bonds, Map.of(friend, 3.0)::get, 0.0), "already together");
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(free(self), bonds, Map.of(friend, 30.0)::get, 0.0), "too far to bother");
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(free(self), bonds, Map.<UUID, Double>of()::get, 0.0), "the friend is not around");
        assertEquals(new SocialRules.Decision(SocialRules.Move.RETREAT, enemy), SocialRules.decide(free(self), bonds, Map.of(friend, 12.0, enemy, 3.0)::get, 0.9));
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(free(self), bonds, Map.of(enemy, 5.0)::get, 0.9), "an enemy at five blocks is tolerated");
        // Several enemies: the nearest is the one to get away from; a mere dislike (above RIVAL) moves nobody.
        UUID nearer = UUID.randomUUID();
        List<MobRelations.View> crowd = List.of(view(enemy, -60, MobRelations.Tag.RIVAL), view(nearer, -45, MobRelations.Tag.RIVAL), view(friend, -20));
        assertEquals(new SocialRules.Decision(SocialRules.Move.RETREAT, nearer), SocialRules.decide(free(self), crowd, Map.of(enemy, 3.5, nearer, 2.0, friend, 1.0)::get, 0.9));
        // The strongest friend comes first in a sorted list; a friend being mourned is dead and not walked to.
        List<MobRelations.View> two = List.of(view(dead, 80, MobRelations.Tag.FRIEND, MobRelations.Tag.MOURNS), view(friend, 50, MobRelations.Tag.FRIEND));
        assertEquals(new SocialRules.Decision(SocialRules.Move.APPROACH, friend), SocialRules.decide(free(self), two, Map.of(dead, 10.0, friend, 10.0)::get, 0.0));
    }

    // Nobody social: a mob under a lease or in a conversation, a fighter in a fight, a hostile species, a mob far from the player.
    @Test void busyHostileOrUnwatchedMobsDoNothing() {
        Map<UUID, Double> distances = Map.of(friend, 12.0, enemy, 2.0);
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(new SocialRules.Actor(self, true, false, 10), bonds, distances::get, 0.0), "busy");
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(new SocialRules.Actor(self, false, true, 10), bonds, distances::get, 0.0), "hostile by nature");
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(new SocialRules.Actor(self, false, false, SocialRules.PLAYER_RANGE + 1), bonds, distances::get, 0.0), "nobody watching");
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(free(self), List.of(), distances::get, 0.0), "knows nobody");
        assertEquals(SocialRules.Decision.NONE, SocialRules.decide(null, bonds, distances::get, 0.0));
    }

    // A mourner's friend goes to sit with it, once: not a stranger, not after someone already came, not when nobody died.
    @Test void aFriendVisitsTheMournerOnce() {
        List<MobRelations.View> grieving = List.of(view(dead, 30, MobRelations.Tag.MOURNS), view(friend, 55, MobRelations.Tag.FRIEND), view(enemy, -50, MobRelations.Tag.RIVAL));
        assertTrue(SocialRules.shouldVisit(grieving, friend));
        assertFalse(SocialRules.shouldVisit(grieving, enemy), "an enemy does not come");
        assertFalse(SocialRules.shouldVisit(grieving, UUID.randomUUID()), "a stranger does not come");
        List<MobRelations.View> consoled = List.of(new MobRelations.View(dead, "Прохор", "minecraft:villager", 30, 0, Set.of(MobRelations.Tag.MOURNS), true), view(friend, 55, MobRelations.Tag.FRIEND));
        assertFalse(SocialRules.shouldVisit(consoled, friend), "someone already came");
        assertFalse(SocialRules.shouldVisit(List.of(view(friend, 55, MobRelations.Tag.FRIEND)), friend), "nothing to console");
        assertFalse(SocialRules.shouldVisit(null, friend) || SocialRules.shouldVisit(grieving, null));
        assertTrue(SocialRules.together(7.9) && !SocialRules.together(8.1) && !SocialRules.together(-1));
    }
}
