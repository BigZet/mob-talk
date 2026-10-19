package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VoiceGenderTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    @Test void theLibraryOffersAVoiceOfEachSexForVillagers() {
        UUID seed = UUID.randomUUID();
        SpeciesCatalog.Voice woman = SpeciesCatalog.voiceFor("minecraft:villager", Gender.FEMALE, seed), man = SpeciesCatalog.voiceFor("minecraft:villager", Gender.MALE, seed);
        assertEquals(Gender.FEMALE, woman.sex()); assertEquals(Gender.MALE, man.sex());
        assertEquals(woman, SpeciesCatalog.voiceFor("minecraft:villager", Gender.FEMALE, seed), "deterministic for the creature");
    }

    // Whatever archetype a villager drew, its voice matches the sex the social layer gave it.
    @Test void villagersSpeakWithAVoiceOfTheirSex() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID player = UUID.randomUUID();
        int checked = 0;
        for (int i = 0; i < 24; i++) {
            UUID id = UUID.randomUUID();
            store.observe(SocialLayerTest.villager(id, player, 0, false, "minecraft:air"));
            ConversationStore.Profile p = store.profile(SocialLayerTest.villager(id, player, store.revision(id), false, "minecraft:air"));
            SpeciesCatalog.Voice voice = SpeciesCatalog.voice(p.voiceId);
            if (voice.sex() == null) continue;
            assertEquals(p.social.gender(), voice.sex(), "villager " + i + " voice " + voice.name());
            checked++;
        }
        assertTrue(checked >= 12, "most villagers have a library voice with a known sex: " + checked);
    }
}
