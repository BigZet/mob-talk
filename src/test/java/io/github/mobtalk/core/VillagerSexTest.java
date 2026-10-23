package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Имя жителя, его пол и голос согласованы: биография модели не переименовывает жителя, а старый мир с «Валентиной»-мужчиной
 * чинится — житель получает имя своего пола. */
class VillagerSexTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    private static MobSnapshot villager(UUID id) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains",
                "day", "clear", 2, "minecraft:air", List.of(), List.of(), UUID.randomUUID(), Map.of("name", "BigZet"), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    @Test void theBiographyKeepsTheSeededNameOfItsSex() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID man = Sexed.id(Gender.MALE);
        ConversationStore.Profile p = store.profile(villager(man));
        assertEquals(Gender.MALE, VillagerNames.sexOf(p.givenName), p.givenName);
        store.setBackstory(man, "Валентина", "Ты родилась у реки.", "", "", "");
        assertEquals(Gender.MALE, VillagerNames.sexOf(store.displayName(man)), store.displayName(man));
    }

    @Test void anOldWorldsWomansNameOnAManIsReplaced() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID man = Sexed.id(Gender.MALE);
        ConversationStore.Profile p = store.profile(villager(man));
        p.givenName = "Валентина"; p.backstory = "Ты родилась у реки."; store.scheduleSave();
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, new MobTalkConfig());
        p = store.profile(villager(man));
        assertEquals(Gender.MALE, VillagerNames.sexOf(p.givenName), p.givenName);
        assertTrue(p.backstory.isEmpty(), "история другого пола будет написана заново");
        assertEquals(Gender.MALE, SpeciesCatalog.voice(p.voiceId).sex(), "голос мужской");
    }
}
