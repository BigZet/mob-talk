package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Idea Б1: the villages learn of the god's deeds — the event, the reaction table, the rumour's words, the daily cap and the floor,
 * the curse and its end, penance, and the bell assembly that alone may exile. */
class DivineTidingsTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID(), player = UUID.randomUUID();
    @TempDir Path directory;
    @AfterEach void reset() { Tuning.apply(new MobTalkConfig()); }

    private String event(String id, String kind, int group, boolean named) {
        return "{\"v\":1,\"id\":\"" + id + "\",\"t\":" + System.currentTimeMillis() + ",\"kind\":\"" + kind + "\",\"deed\":\"zombies\",\"deedTitle\":\"Нашествие мертвецов\","
                + "\"group\":" + group + ",\"power\":0,\"scope\":\"personal\",\"culprit\":{\"uuid\":\"" + player + "\",\"name\":\"Стив\",\"reason\":\"ударил жителя\"},"
                + "\"named\":" + named + ",\"dim\":\"" + OVERWORLD + "\",\"x\":100,\"y\":64,\"z\":100,\"radius\":0,\"seconds\":300,\"reasons\":[\"ударил жителя\"]}";
    }

    @Test void parsesTheEventAndRefusesJunk() {
        DivineTidings.Deed deed = DivineTidings.parse(event("e1", "wrath", 4, true));
        assertEquals(DivineTidings.Kind.WRATH, deed.kind());
        assertEquals("Нашествие мертвецов", deed.title());
        assertEquals(player, deed.culprit().id()); assertEquals("Стив", deed.culprit().name());
        assertTrue(deed.named()); assertFalse(deed.world()); assertEquals(300_000L, deed.curseMillis());
        assertTrue(deed.alarm()); assertTrue(deed.golem()); assertFalse(deed.everywhere());
        assertThrows(IllegalArgumentException.class, () -> DivineTidings.parse("{\"kind\":\"wrath\"}"), "no id");
        assertThrows(IllegalArgumentException.class, () -> DivineTidings.parse("{\"id\":\"x\",\"kind\":\"dance\"}"), "unknown kind");
        assertThrows(IllegalArgumentException.class, () -> DivineTidings.parse("[1,2]"));
    }

    @Test void theReactionFollowsTheTableAndTheStrength() {
        DivineTidings.Deed light = DivineTidings.parse(event("a", "wrath", 1, true)), heavy = DivineTidings.parse(event("b", "wrath", 5, true));
        assertEquals(new DivineTidings.Reaction(2, -1, -5, -5), DivineTidings.reaction(light, 100));
        assertEquals(new DivineTidings.Reaction(5, -1, -30, -15), DivineTidings.reaction(heavy, 100));
        assertEquals(new DivineTidings.Reaction(5, -1, -60, -30), DivineTidings.reaction(heavy, 200));
        assertEquals(0, DivineTidings.reaction(heavy, 0).sufferer(), "strength 0: rumours only");
        assertTrue(heavy.everywhere());
        assertEquals(DivineTidings.Reaction.NONE, DivineTidings.reaction(DivineTidings.parse(event("c", "curse_end", 0, true)), 100));
        assertEquals(-30, DivineTidings.stronger(-30, -5)); assertEquals(-30, DivineTidings.stronger(-5, -30));
    }

    @Test void theRumourNamesTheSinnerOnlyWhenKnown() {
        DivineTidings.Deed deed = DivineTidings.parse(event("e", "wrath", 3, true));
        assertTrue(DivineTidings.rumorText(deed, "Стив").contains("Стив"));
        assertTrue(DivineTidings.rumorText(deed, "Стив").contains("Нашествие мертвецов"));
        assertFalse(DivineTidings.rumorText(deed, "").contains("Стив"));
        assertTrue(DivineTidings.rumorText(deed, "").contains("за чьи-то грехи"));
    }

    @Test void theVillageBlamesWithinTheCapAndOnlyTheAssemblyExiles() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        Tuning.apply(config);
        ConversationStore store = ConversationStore.open(directory, config);
        ConversationStore.Village village = store.createVillage(OVERWORLD, 100, 100, "Ольховка", "Деревня у реки.");
        long now = System.currentTimeMillis();
        store.chatterInput(new VillageChatter.Seat(first, "minecraft:villager", "Фермер"), new VillageChatter.Seat(second, "minecraft:villager", "Библиотекарь"),
                village.id(), village.name(), "Стив", "день", () -> 0.5, now);
        assertEquals(village.id(), store.villageOf(first));
        Map<UUID, String> witnesses = Map.of(first, "Фермер", second, "Библиотекарь");

        assertTrue(store.divineFirst("boss1|BOSS", now)); assertFalse(store.divineFirst("boss1|BOSS", now), "counted once");
        ConversationStore.DivineOutcome outcome = ConversationStore.DivineOutcome.NONE;
        for (int i = 1; i <= 3; i++) {
            DivineTidings.Deed deed = DivineTidings.parse(event("boss" + i, "boss", 0, true));
            outcome = store.divineVillage(deed, village.id(), true, false, Map.of(), witnesses, now + i);
            store.divineSettle(deed, List.of(village.id()), now + i);
        }
        assertEquals("Стив", outcome.blamed());
        assertEquals(3, outcome.wraths(), "three punishments for him fell on the village today");
        int a = store.relationship(first, player), b = store.relationship(second, player);
        assertTrue(a >= DivineTidings.RELATION_FLOOR && b >= DivineTidings.RELATION_FLOOR, a + " " + b);
        assertTrue(a + b >= -config.divineDailyCap, "the daily cap: " + (a + b));
        assertTrue(a < 0 && b < 0);
        assertTrue(store.rumorsOf(first, now + 5).stream().anyMatch(v -> v.divine() && v.text().contains("Стив")), "everyone heard it");
        assertNotNull(store.divineCurse(village.id(), now + 5));

        VillageStanding.View view = store.social().standing(village.id(), player, now + 5);
        assertNotEquals(VillageStanding.Rank.EXILE, view.rank(), "the god alone does not exile");

        assertTrue(store.divineAssemble(village.id(), player, "Стив", now + 10));
        assertFalse(store.divineAssemble(village.id(), player, "Стив", now + 10), "one assembly at a time");
        store.divineExile(village.id(), player);
        assertTrue(store.divineAssemblies().isEmpty());
        assertEquals(VillageStanding.Rank.EXILE, store.social().standing(village.id(), player, now + 11).rank(), "the assembly drove him out");

        // Penance: half the losses come back, the curses lift.
        DivineTidings.Deed forgiven = DivineTidings.parse(event("f1", "forgiven", 0, true));
        List<String> lifted = store.divineSettle(forgiven, List.of(village.id()), now + 20);
        assertEquals(List.of(village.id()), lifted);
        assertNull(store.divineCurse(village.id(), now + 21));
        assertTrue(store.relationship(first, player) > a || a == 0);
        assertTrue(store.divineExiled(village.id(), player), "penance before the god does not undo the village's decision");
        store.forgiveVillage(village, player);
        assertFalse(store.divineExiled(village.id(), player), "the village forgave");
        ConversationStore.closeWorld(directory);

        store = ConversationStore.open(directory, config);
        assertFalse(store.divineFirst("boss1|BOSS", now), "the seen events survive the disk");
        ConversationStore.closeWorld(directory);
    }

    @Test void anUnknownSinnerChangesNobody() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        Tuning.apply(config);
        ConversationStore store = ConversationStore.open(directory, config);
        ConversationStore.Village village = store.createVillage(OVERWORLD, 100, 100, "Ольховка", "");
        long now = System.currentTimeMillis();
        store.chatterInput(new VillageChatter.Seat(first, "minecraft:villager", "Фермер"), new VillageChatter.Seat(second, "minecraft:villager", "Фермер"),
                village.id(), village.name(), "Стив", "день", () -> 0.5, now);
        ConversationStore.DivineOutcome outcome = store.divineVillage(DivineTidings.parse(event("w", "wrath", 4, false)), village.id(), false, false,
                Map.of(first, "Фермер"), Map.of(), now);
        assertFalse(outcome.rumorId().isBlank());
        assertEquals("", outcome.blamed()); assertEquals(0, outcome.change());
        assertEquals(0, store.relationship(first, player));
        assertFalse(store.social().standing(village.id(), player, now).rank() == VillageStanding.Rank.SUSPECT, "a rumour about nobody blames nobody");
        ConversationStore.closeWorld(directory);
    }
}
