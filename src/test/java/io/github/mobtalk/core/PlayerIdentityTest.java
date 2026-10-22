package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Несколько игроков на сервере (трек C): у каждого свои обращения и пол, память, слухи и очевидцы называют конкретного игрока, слава
 * в деревне у каждого своя, житель — пара только одного игрока. */
class PlayerIdentityTest {
    @TempDir Path directory;
    private final UUID dima = UUID.randomUUID(), test2 = UUID.randomUUID();
    @AfterEach void close() throws Exception { PlayerIdentity.resolver(null); ConversationStore.closeWorld(directory); Tuning.apply(null); }

    private void twoPlayers() {
        MobTalkConfig mine = new MobTalkConfig(); mine.playerNamesNeutral = "Дима"; mine.playerNamesClose = "Димочка"; mine.playerGender = "male";
        MobTalkConfig theirs = new MobTalkConfig(); theirs.playerNamesNeutral = "Тестер"; theirs.playerNamesHostile = "Гадина"; theirs.playerGender = "female";
        PlayerIdentity.resolver(id -> id.equals(dima) ? Optional.of(mine) : id.equals(test2) ? Optional.of(theirs) : Optional.empty());
    }

    private static MobSnapshot villager(UUID id, UUID player, String nick) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains",
                "day", "clear", 2, "minecraft:air", List.of(), List.of(), player, Map.of("name", nick), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    @Test void eachPlayerHasTheirOwnNamesAndSex() {
        twoPlayers();
        assertEquals("Дима", PlayerIdentity.name(dima, "BigZet"));
        assertEquals("Тестер", PlayerIdentity.name(test2, "Тест2"));
        assertEquals("Чужак", PlayerIdentity.name(UUID.randomUUID(), "Чужак"), "a player who sent nothing goes by the nick");
        assertEquals(Gender.MALE, PlayerIdentity.gender(new MobTalkConfig(), dima));
        assertEquals(Gender.FEMALE, PlayerIdentity.gender(new MobTalkConfig(), test2));
        assertEquals(List.of("Димочка"), PlayerIdentity.forms(PlayerIdentity.configOf(dima), "BigZet", 90));
        assertEquals(List.of("Гадина"), PlayerIdentity.forms(PlayerIdentity.configOf(test2), "Тест2", -50));
        assertTrue(PlayerIdentity.promptLine(PlayerIdentity.configOf(test2), "Тест2", -50).contains("Тестер"));
    }

    @Test void singlePlayerFallsBackToTheSharedSettings() {
        MobTalkConfig shared = new MobTalkConfig(); shared.playerNamesNeutral = "Хозяин";
        Tuning.apply(shared);
        assertEquals("Хозяин", PlayerIdentity.name(dima, "BigZet"), "no lookup: the shared settings, as before");
        PlayerIdentity.resolver(id -> Optional.empty());
        assertEquals("Хозяин", PlayerIdentity.name(dima, "BigZet"), "unknown to the server: the shared settings too");
    }

    @Test void memoryAndRumoursNameTheRightPlayerAndFameIsPersonal() throws Exception {
        twoPlayers();
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID anna = UUID.randomUUID(), boris = UUID.randomUUID();
        ConversationStore.Village village = store.createVillage("minecraft:overworld", 0, 0, "Ольховка", "");
        store.observe(villager(anna, dima, "BigZet")); store.observe(villager(boris, test2, "Тест2"));
        store.setBackstory(anna, "Анна", "", "", "", village.id());
        store.setBackstory(boris, "Борис", "", "", "", village.id());
        store.recordDamage(anna, "minecraft:villager", "Житель", test2, PlayerIdentity.name(test2, "Тест2"), 1000);
        store.recordWitnessed(boris, test2, PlayerIdentity.name(test2, "Тест2"), "ударил Анну", -10, 1100);
        store.recordHelp(boris, "minecraft:villager", "Житель", dima, PlayerIdentity.name(dima, "BigZet"), 1200);
        String annaMemory = String.join("\n", store.card(anna, test2).events());
        assertTrue(annaMemory.contains("Игрок Тестер ударил"), annaMemory);
        assertEquals(Map.of("Тестер", test2.toString(), "Дима", dima.toString()), store.playerIdsByName());
        assertEquals(test2, store.playerByName("тестер"));

        VillageRumors.Input input = store.chronicleDue(village.id(), "Тестер", 1, System.currentTimeMillis());
        assertNotNull(input);
        assertEquals(List.of("Тестер", "Дима"), input.players(), "the chronicle knows every player by name, the speaker first");
        assertTrue(VillageRumors.instructions(input).contains("игроки: Тестер, Дима"));
        assertTrue(VillageRumors.format().toString().contains("\"player\""));
        assertEquals("Тестер", input.player("тестер", -1));
        assertEquals("", input.player("Кто-то", -1), "two players: an unnamed one is nobody");

        long now = System.currentTimeMillis();
        store.addRumors(village.id(), List.of(
                new VillageRumors.View("", "Тестер ударил Анну", "Анна", 5, -1, 0, 5, "Тестер"),
                new VillageRumors.View("", "Дима помог Борису", "Борис", 4, 1, 0, 4, "Дима")), now);
        VillageRumors.Reputation bad, good;
        synchronized (store) {
            bad = store.state.rumors.reputation(village.id(), test2, now);
            good = store.state.rumors.reputation(village.id(), dima, now);
        }
        assertTrue(bad.balance() < 0, "Тестер's fame is bad");
        assertTrue(good.balance() > 0, "Дима's fame stays good despite Тестер");
        assertTrue(bad.describe().contains("дурная") && good.describe().contains("добрая"));
    }

    @Test void villagerIsThePartnerOfOnePlayerOnly() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID anna = UUID.randomUUID();
        store.observe(villager(anna, dima, "BigZet"));
        store.recordHelp(anna, "minecraft:villager", "Житель", dima, "Дима", 1000);
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(anna.toString());
            assertNull(SocialLedger.takenBy(store.state, p, test2), "free");
            ConversationStore.Relation r = store.relation(p, dima);
            if (r.rapport == null) r.rapport = new Rapport();
            r.rapport.courtship(Courtship.DATING);
            String refusal = SocialLedger.takenBy(store.state, p, test2);
            assertNotNull(refusal);
            assertTrue(refusal.contains("Дима"), refusal);
            assertNull(SocialLedger.takenBy(store.state, p, dima), "her own partner is welcome");
        }
    }

    @Test void twoPlayersWithTheSameNameDifferByNick() {
        MobTalkConfig mine = new MobTalkConfig(); mine.playerNamesNeutral = "Дима";
        MobTalkConfig theirs = new MobTalkConfig(); theirs.playerNamesNeutral = "Дима";
        PlayerIdentity.resolver(id -> id.equals(dima) ? Optional.of(mine) : id.equals(test2) ? Optional.of(theirs) : Optional.empty());
        assertEquals("Дима", PlayerIdentity.name(dima, "BigZet"), "alone so far: just the name");
        assertEquals("Дима (Test2)", PlayerIdentity.name(test2, "Test2"));
        assertEquals("Дима (BigZet)", PlayerIdentity.name(dima, "BigZet"));
        assertTrue(MobPrompt.names(new MobTalkConfig(), villager(UUID.randomUUID(), test2, "Test2"), 0).contains("Игрока зовут Дима (Test2)"));
    }
}
