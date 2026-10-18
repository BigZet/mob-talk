package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SituationTest {
    private static Map<String,Object> item(String id, String name) { return Map.of("id", id, "name", name, "count", 1); }
    private static Map<String,Object> player(String weapon, String... armor) {
        Map<String,Object> equipment = new java.util.LinkedHashMap<>();
        String[] slots = {"head", "chest", "legs", "feet"};
        for (int i = 0; i < slots.length; i++) equipment.put(slots[i], item(i < armor.length ? armor[i] : "minecraft:air", "броня"));
        return Map.of("name", "Стив", "health", 20, "maxHealth", 20, "mainHand", item(weapon, "Алмазный меч"), "offHand", item("minecraft:air", ""), "equipment", equipment);
    }
    private static MobSnapshot snapshot(String type, float health, float max, boolean fighter, double damage, Map<String,Object> player, List<ObservedEntity> seen) {
        return new MobSnapshot(UUID.randomUUID(), type, "Моб", health, max, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                "minecraft:stick", List.of(), List.of(), UUID.randomUUID(), player, Map.of("fighter", fighter, "attackDamage", damage), Map.of(), seen,
                List.of(ActionType.NONE, ActionType.FLEE), 0);
    }
    private static ConversationStore.Profile profile(String type, int relationship, int hits, List<String> events) {
        ConversationStore.Profile p = new ConversationStore.Profile();
        p.type = type; p.relationship = relationship; p.hits = hits; p.recentEvents = events;
        return p;
    }

    // A villager in front of a diamond sword is doomed whatever its temper says; it must hear so before it answers.
    @Test void anUnarmedVillagerFacingASwordIsDoomed() {
        MobSnapshot snapshot = snapshot("minecraft:villager", 20, 20, false, 0, player("minecraft:diamond_sword", "minecraft:diamond_helmet", "minecraft:diamond_chestplate"), List.of());
        Situation situation = Situation.of(snapshot, profile("minecraft:villager", 0, 2, List.of("Видел, как игрок Стив убил Марфу (корова); отношение -25")));
        assertEquals(Situation.Verdict.DOOMED, situation.verdict());
        String text = situation.describe();
        assertTrue(text.contains("проси пощады") && text.contains("О ценах это не говорит"), "hit twice: a real threat: " + text);
        assertTrue(text.contains("вооружён: Алмазный меч") && text.contains("броня: 2 части (алмаз)"), text);
        assertTrue(text.contains("драться не умеешь") && text.contains("сородичей рядом нет"), text);
        assertTrue(text.contains("он бил тебя 2 раза") && text.contains("1 случай"), text);
        assertTrue(text.contains("не выжить") && text.contains("FLEE"), text);
        assertFalse(text.contains("ненавидишь"));
    }

    // A naked, empty-handed player is no threat even to a villager; a wolf pack outright outmatches them.
    @Test void gearDecidesTheVerdict() {
        Situation fists = Situation.of(snapshot("minecraft:villager", 20, 20, false, 0, player("minecraft:stick"), List.of()), profile("minecraft:villager", 40, 0, List.of()));
        assertEquals(Situation.Verdict.DOOMED, fists.verdict(), "bare fists still beat a villager that cannot fight back");
        assertTrue(fists.describe().contains("повода для драки нет") && !fists.describe().contains("проси пощады"), "no threat, no begging: " + fists.describe());
        assertEquals(Situation.Verdict.OUTMATCHED, Situation.of(snapshot("minecraft:horse", 30, 30, false, 0, player("minecraft:stick"), List.of()), null).verdict(), "a big body counts even without teeth");
        List<ObservedEntity> pack = List.of(
                new ObservedEntity("e1", UUID.randomUUID(), "minecraft:wolf", "Волк", 4, "north", true, Map.of()),
                new ObservedEntity("e2", UUID.randomUUID(), "minecraft:wolf", "Волк", 6, "north", true, Map.of()),
                new ObservedEntity("e3", UUID.randomUUID(), "minecraft:wolf", "Волк", 30, "north", true, Map.of()),
                new ObservedEntity("e4", UUID.randomUUID(), "minecraft:sheep", "Овца", 2, "south", true, Map.of()));
        Situation wolves = Situation.of(snapshot("minecraft:wolf", 8, 8, true, 4, player("minecraft:stick"), pack), profile("minecraft:wolf", -50, 0, List.of()));
        assertEquals(Situation.Verdict.STRONGER, wolves.verdict());
        assertTrue(wolves.describe().contains("рядом сородичей: 2") && wolves.describe().contains("ATTACK"), wolves.describe());
        Situation zombie = Situation.of(snapshot("minecraft:zombie", 20, 20, true, 3, player("minecraft:iron_sword", "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots"), List.of()),
                profile("minecraft:zombie", -80, 0, List.of()));
        assertTrue(zombie.verdict() == Situation.Verdict.OUTMATCHED || zombie.verdict() == Situation.Verdict.DOOMED, zombie.verdict().name());
        assertFalse(zombie.describe().contains("ненавидишь"), "hostile species need no hatred line");
        assertTrue(!zombie.describe().contains("проси пощады") && !zombie.describe().contains("беги (action FLEE)") && zombie.describe().contains("враг"), "a monster stays a monster: " + zombie.describe());
        Map<String,Object> creative = new java.util.LinkedHashMap<>(player("minecraft:stick")); creative.put("creative", true);
        Situation god = Situation.of(snapshot("minecraft:wolf", 8, 8, true, 4, creative, pack), profile("minecraft:wolf", -50, 1, List.of()));
        assertEquals(Situation.Verdict.DOOMED, god.verdict()); assertTrue(god.describe().contains("творческом режиме"), god.describe());
        assertEquals(Situation.Verdict.STRONGER, Situation.of(snapshot("minecraft:warden", 500, 500, true, 30, player("minecraft:netherite_sword"), List.of()), null).verdict());
    }

    @Test void scoresGearByMaterial() {
        assertEquals(0, Situation.weaponScore("minecraft:stick")); assertEquals(0, Situation.weaponScore(null));
        assertTrue(Situation.weaponScore("minecraft:wooden_sword") < Situation.weaponScore("minecraft:iron_axe"));
        assertTrue(Situation.weaponScore("minecraft:iron_axe") < Situation.weaponScore("minecraft:diamond_sword"));
        assertTrue(Situation.weaponScore("minecraft:diamond_sword") < Situation.weaponScore("minecraft:netherite_sword"));
        assertEquals(5, Situation.weaponScore("minecraft:mace")); assertEquals(3, Situation.weaponScore("minecraft:bow"));
        assertEquals(0, Situation.armorScore("minecraft:diamond_sword")); assertEquals(0.5, Situation.armorScore("minecraft:leather_boots"));
        assertEquals(2, Situation.armorScore("minecraft:netherite_chestplate")); assertEquals(0, Situation.armorScore("minecraft:elytra"));
        assertEquals("раз", Situation.plural(21, "раз", "раза", "раз")); assertEquals("раза", Situation.plural(3, "раз", "раза", "раз")); assertEquals("раз", Situation.plural(12, "раз", "раза", "раз"));
    }

    // A hated player close by: a fighter that can win attacks, anyone else keeps away.
    @Test void hatredLineFollowsTheOdds() {
        Situation cow = Situation.of(snapshot("minecraft:cow", 10, 10, false, 0, player("minecraft:stone_sword"), List.of()), profile("minecraft:cow", -60, 1, List.of()));
        assertTrue(cow.describe().contains("держись от него подальше"), cow.describe());
        Situation golem = Situation.of(snapshot("minecraft:iron_golem", 100, 100, true, 15, player("minecraft:stone_sword"), List.of()), profile("minecraft:iron_golem", -60, 0, List.of()));
        assertEquals(Situation.Verdict.STRONGER, golem.verdict());
        assertTrue(golem.describe().contains("можешь напасть (action ATTACK)"), golem.describe());
        assertTrue(CombatRules.mayAttackPlayer(false, -60, false) && !CombatRules.mayAttackPlayer(false, CombatRules.HATRED + 1, false));
    }
}
