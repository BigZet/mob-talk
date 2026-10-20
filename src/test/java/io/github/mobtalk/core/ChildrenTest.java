package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Children: stages, chances, inheritance, the player's child from agreement to birth, the tree and the prompt. No Minecraft. */
class ChildrenTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    @Test void stagesSpanTheConfiguredDays() {
        assertEquals(ChildGrowth.Stage.INFANT, ChildGrowth.stage(0, 4));
        assertEquals(ChildGrowth.Stage.INFANT, ChildGrowth.stage(23_999, 4));
        assertEquals(ChildGrowth.Stage.TODDLER, ChildGrowth.stage(24_000, 4));
        assertEquals(ChildGrowth.Stage.CHILD, ChildGrowth.stage(48_000, 4));
        assertEquals(ChildGrowth.Stage.TEEN, ChildGrowth.stage(72_000, 4));
        assertEquals(ChildGrowth.Stage.ADULT, ChildGrowth.stage(96_000, 4));
        assertEquals(ChildGrowth.Stage.ADULT, ChildGrowth.stage(24_000, 1), "one game day: grown after a day");
        assertTrue(ChildGrowth.stage(23_999, 1) != ChildGrowth.Stage.ADULT, "vanilla's twenty minutes are not enough");
    }

    @Test void birthChanceFallsWithEachChild() {
        assertEquals(1.0, ChildGrowth.birthChance(0), 1e-9);
        assertEquals(1 / 1.1, ChildGrowth.birthChance(1), 1e-9);
        assertEquals(1 / 3.1, ChildGrowth.birthChance(3), 1e-9);
        assertEquals(0, ChildGrowth.birthChance(ChildGrowth.MAX_CHILDREN), 1e-9);
    }

    @Test void temperamentAndMarksComeFromTheParents() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            ChildGrowth.Newborn child = ChildGrowth.born(Set.of(), new int[]{20, 20, 20, 20}, Set.of(), new int[]{60, 60, 60, 60}, random);
            for (int axis : new int[]{child.calm(), child.confidence(), child.curiosity(), child.sociability()})
                assertTrue(axis >= 20 - ChildGrowth.NOISE && axis <= 60 + ChildGrowth.NOISE, String.valueOf(axis));
            assertNotEquals(Persona.FLIRTY, child.persona());
        }
        ChildGrowth.Newborn withPlayer = ChildGrowth.born(Set.of(), new int[]{80, 80, 80, 80}, Set.of(), null, new Random(1));
        assertTrue(withPlayer.calm() >= 70, "a player parent passes nothing; the villager's temperament stays");
        int diabetic = 0, bisexual = 0;
        for (int i = 0; i < 2000; i++) {
            List<VillagerTrait> marks = VillagerTrait.inherit(Set.of(VillagerTrait.DIABETIC, VillagerTrait.BISEXUAL), Set.of(), random);
            if (marks.contains(VillagerTrait.DIABETIC)) diabetic++;
            if (marks.contains(VillagerTrait.BISEXUAL)) bisexual++;
        }
        assertTrue(diabetic > 850 && diabetic < 1150, "inheritance 1.0 × 0.5: about half, was " + diabetic);
        assertEquals(0, bisexual, "orientation is not inherited");
    }

    // Married at 95: the spouse agrees to a child, the game sets the day, the baby is born into the tree loving its player parent.
    @Test void thePlayersChildFromAgreementToBirth() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male"; config.weddingCeremony = false; // Кольцо — сразу брак (без службы трека L).
        ConversationStore store = ConversationStore.open(directory, config);
        UUID wife = UUID.randomUUID(), player = UUID.randomUUID(), baby = UUID.randomUUID();
        store.observe(SocialLayerTest.villager(wife, player, 0, false, "minecraft:air"));
        ConversationStore.Profile p = store.state.profiles.get(wife.toString());
        p.social.gender = Gender.FEMALE.name(); p.social.traits.clear(); p.relations.get(player.toString()).score = 90;
        store.setBackstory(wife, "Марфа", "Ты родилась в Ольховке.", "", "", "");
        store.social().applyGesture(wife, player, "Стив", store.profile(SocialLayerTest.villager(wife, player, store.revision(wife), false, "minecraft:gold_nugget"))
                .socialTurn.intent(Gesture.PROPOSE), 1000);
        ConversationStore.Profile married = store.profile(SocialLayerTest.villager(wife, player, store.revision(wife), false, "minecraft:air"));
        GestureIntent child = married.socialTurn.intent(Gesture.CHILD);
        assertEquals(Gesture.Outcome.ACCEPTED, child.outcome(), child.note());
        store.social().applyGesture(wife, player, "Стив", child, 2000);
        assertEquals(List.of(new SocialLedger.Expecting(wife, player, 0)), store.social().expecting());
        // Asked again at once: already on the way.
        assertEquals(Gesture.Outcome.REFUSED, store.profile(SocialLayerTest.villager(wife, player, store.revision(wife), false, "minecraft:air")).socialTurn.intent(Gesture.CHILD).outcome());
        store.social().due(wife, 50_000);
        assertEquals(50_000, store.social().expecting().getFirst().dueTick());
        Gender sex = store.social().born(baby, wife, player, "Стив", 50_000, 3000);
        assertTrue(store.social().expecting().isEmpty());
        assertEquals(Hearts.OWN_CHILD, store.relationship(baby, player));
        assertTrue(store.state.family.parents(baby).containsAll(List.of(wife, player)));
        assertTrue(store.state.family.related(baby, player), "the player's own child: no romance ever");
        ConversationStore.Profile kid = store.profile(SocialLayerTest.villager(baby, player, store.revision(baby), false, "minecraft:air"));
        assertEquals(sex, kid.social.gender());
        assertFalse(kid.socialTurn.schemaValues().contains("FLIRT"));
        assertFalse(kid.socialTurn.schemaValues().contains("PROPOSE"));
        assertTrue(SocialPrompt.gestures(kid).contains("твой отец"), SocialPrompt.gestures(kid));
        String prompt = new MobPrompt(config).system(SpeciesCatalog.get("minecraft:villager"), kid, SocialLayerTest.villager(baby, player, 0, false, "minecraft:air"));
        assertTrue(prompt.contains(ChildGrowth.Stage.INFANT.speech), "the infant babbles");
        assertTrue(prompt.contains("мать Марфа"), "the first story knows the mother");
        assertTrue(prompt.contains("Марфа — твоя мать"), "«Свои» name the mother");
        // Growing up: stages follow world time; an adult is free of the stage line.
        assertEquals(ChildGrowth.Stage.CHILD, store.social().grow(List.of(baby), 50_000 + 48_000).get(baby));
        assertEquals(ChildGrowth.Stage.ADULT, store.social().grow(List.of(baby), 50_000 + 96_000).get(baby));
        assertTrue(store.events(wife).stream().anyMatch(e -> e.contains("родился")));
    }

    // Two villagers married by the village rule have a child: both are its parents, the child is a sibling of the next.
    @Test void villageCouplesHaveChildren() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID mother = UUID.randomUUID(), father = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        store.state.family.marry(mother, "Марфа", Gender.FEMALE, false, father, "Пётр", Gender.MALE, false);
        store.social().born(first, mother, father, "", 0, 1);
        store.social().born(second, mother, father, "", 10, 2);
        assertEquals(2, store.social().household(mother).children());
        assertTrue(store.state.family.kin(first).stream().anyMatch(k -> k.id().equals(second)));
        assertTrue(store.state.family.related(first, second));
        assertEquals(0, store.relationship(first, UUID.randomUUID()), "no player parent, no head start");
    }
}
