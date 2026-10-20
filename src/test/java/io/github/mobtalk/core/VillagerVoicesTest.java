package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VillagerVoicesTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    @Test void thePoolHasEverySexAndAgeAndNoSpeciesVoice() {
        Set<String> speciesVoices = new HashSet<>();
        for (SpeciesCatalog.Species species : SpeciesCatalog.all())
            for (SpeciesCatalog.Archetype archetype : species.variants()) speciesVoices.add(archetype.voice().id());
        for (VoiceAge age : VoiceAge.values())
            for (Gender gender : Gender.values()) {
                List<SpeciesCatalog.Voice> pool = SpeciesCatalog.villagerPool(age, gender);
                assertTrue(pool.size() >= 4, age + " " + gender + ": " + pool.size());
                for (SpeciesCatalog.Voice voice : pool) {
                    assertEquals(gender, voice.sex());
                    assertFalse(speciesVoices.contains(voice.id()), voice.name());
                }
            }
    }

    @Test void agesFollowThirtyTenSixty() {
        int[] counts = new int[3];
        Random random = new Random(7);
        for (int i = 0; i < 20000; i++) counts[VillagerVoices.age(new UUID(random.nextLong(), random.nextLong()), null).ordinal()]++;
        assertEquals(0.30, counts[VoiceAge.YOUNG.ordinal()] / 20000.0, 0.02);
        assertEquals(0.10, counts[VoiceAge.OLD.ordinal()] / 20000.0, 0.02);
        assertEquals(0.60, counts[VoiceAge.MIDDLE.ordinal()] / 20000.0, 0.02);
    }

    // A voice repeats only after every voice of that sex and age is taken, and then the least used one comes back.
    @Test void voicesRepeatOnlyOnceThePoolIsExhausted() {
        List<SpeciesCatalog.Voice> pool = SpeciesCatalog.villagerPool(VoiceAge.MIDDLE, Gender.FEMALE);
        List<ConversationStore.Profile> village = new ArrayList<>();
        Set<String> first = new HashSet<>();
        for (int i = 0; i < pool.size(); i++) first.add(cast(village, Gender.FEMALE));
        assertEquals(pool.size(), first.size(), "every pool voice once before any repeat");
        Map<String, Integer> uses = new HashMap<>();
        for (int i = 0; i < pool.size(); i++) uses.merge(cast(village, Gender.FEMALE), 1, Integer::sum);
        assertEquals(pool.size(), uses.size(), "the second round again uses each voice once");
    }

    @Test void aVillagerGetsAnAgeAndAPoolVoiceOnceAndKeepsAPickedVoice() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID player = UUID.randomUUID(), id = UUID.randomUUID();
        store.observe(SocialLayerTest.villager(id, player, 0, false, "minecraft:air"));
        ConversationStore.Profile p = store.profile(SocialLayerTest.villager(id, player, store.revision(id), false, "minecraft:air"));
        VoiceAge age = p.social.age();
        assertNotNull(age);
        assertTrue(VillagerVoices.fits(p.voiceId, p.social.gender(), age), "voice from the pool: " + p.voiceId);
        assertFalse(store.card(id, player).age().isBlank(), "the menu shows the age");

        // A catalog voice of the same sex picked later in the menu survives the next meeting; the age never changes.
        String picked = SpeciesCatalog.villagerPool(VoiceAge.OLD, p.social.gender()).getLast().id();
        store.updateCard(id, null, null, picked, null);
        ConversationStore.Profile again = store.profile(SocialLayerTest.villager(id, player, store.revision(id), false, "minecraft:air"));
        assertEquals(picked, again.voiceId);
        assertEquals(age, again.social.age());
    }

    @Test void aTypedInVoiceSurvivesTheFirstCasting() {
        ConversationStore.Profile p = villager(UUID.randomUUID());
        p.voiceId = "CustomVoiceId1234567890";
        p.social = SocialProfile.of(p.id, false);
        SocialLedger.adopt(p, config(), List.of());
        assertEquals("CustomVoiceId1234567890", p.voiceId);
        assertNotNull(p.social.age());
    }

    private static String cast(List<ConversationStore.Profile> village, Gender gender) {
        ConversationStore.Profile p = villager(UUID.randomUUID());
        p.voiceId = VillagerVoices.pick(gender, VoiceAge.MIDDLE, p.id, village);
        village.add(p);
        return p.voiceId;
    }
    private static ConversationStore.Profile villager(UUID id) {
        ConversationStore.Profile p = new ConversationStore.Profile(); p.id = id; p.type = "minecraft:villager"; p.voiceId = "";
        return p;
    }
    private static MobTalkConfig config() { MobTalkConfig c = new MobTalkConfig(); c.socialLayer = true; return c; }
}
