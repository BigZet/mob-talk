package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The family tree, courtship rules, village weddings, deaths and mourning: pure logic and the world memory, no Minecraft. */
class FamilyTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    @Test void bloodIsACommonAncestorAndMarriageIsNot() {
        FamilyTree tree = new FamilyTree();
        UUID mother = UUID.randomUUID(), father = UUID.randomUUID(), son = UUID.randomUUID(), daughter = UUID.randomUUID(), grandson = UUID.randomUUID(), stranger = UUID.randomUUID();
        tree.marry(mother, "Марфа", Gender.FEMALE, false, father, "Пётр", Gender.MALE, false);
        tree.addChild(son, "Ваня", Gender.MALE, mother, father);
        tree.addChild(daughter, "Аня", Gender.FEMALE, mother, father);
        tree.addChild(grandson, "Митя", Gender.MALE, null, son);
        assertTrue(tree.related(son, daughter)); assertTrue(tree.related(mother, son)); assertTrue(tree.related(grandson, mother));
        assertTrue(tree.related(grandson, daughter), "aunt and nephew share grandparents");
        assertFalse(tree.related(mother, father), "spouses are not blood");
        assertFalse(tree.related(stranger, son));
        assertEquals(father, tree.spouse(mother));
        List<String> words = tree.kin(son).stream().map(k -> k.word() + " " + k.name()).toList();
        assertTrue(words.containsAll(List.of("мать Марфа", "отец Пётр", "сестра Аня", "сын Митя")), words.toString());
        var grieving = tree.died(father);
        assertEquals(FamilyTree.Link.SPOUSE, grieving.get(mother)); assertEquals(FamilyTree.Link.CHILD, grieving.get(son));
        assertNull(tree.spouse(mother), "a dead spouse is no spouse"); assertTrue(tree.widowed(mother));
    }

    @Test void attractionNeedsBothSides() {
        assertTrue(Romance.compatible(Gender.MALE, Set.of(), Gender.FEMALE, Set.of()));
        assertFalse(Romance.compatible(Gender.MALE, Set.of(), Gender.MALE, Set.of()));
        assertTrue(Romance.compatible(Gender.MALE, Set.of(VillagerTrait.HOMOSEXUAL), Gender.MALE, Set.of(VillagerTrait.HOMOSEXUAL)));
        assertFalse(Romance.compatible(Gender.MALE, Set.of(VillagerTrait.HOMOSEXUAL), Gender.MALE, Set.of()), "one-sided attraction is not enough");
        assertTrue(Romance.compatible(Gender.FEMALE, Set.of(VillagerTrait.BISEXUAL), Gender.FEMALE, Set.of(VillagerTrait.BISEXUAL)));
        assertFalse(Romance.drawn(Gender.FEMALE, Set.of(VillagerTrait.ASEXUAL), Gender.MALE));
        assertTrue(Romance.drawn(Gender.FEMALE, Set.of(), null), "a player of no counted sex");
    }

    @Test void courtshipChecksComeInOrder() {
        UUID villager = UUID.randomUUID();
        Romance.Suit ready = new Romance.Suit(true, false, Courtship.NONE, villager, null, null, 95, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:gold_nugget");
        assertNull(Romance.refusePropose(ready));
        assertNotNull(Romance.refuseDate(ready), "a ring is not a bouquet");
        assertNull(Romance.refuseDate(new Romance.Suit(true, false, Courtship.NONE, villager, null, null, 75, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:poppy")));
        assertTrue(Romance.refusePropose(new Romance.Suit(true, false, Courtship.NONE, villager, null, null, 80, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:gold_ingot")).contains("рано"));
        assertTrue(Romance.refusePropose(new Romance.Suit(false, false, Courtship.NONE, villager, null, null, 95, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:gold_nugget")).contains("ребёнок"));
        assertTrue(Romance.refusePropose(new Romance.Suit(true, true, Courtship.NONE, villager, null, null, 95, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:gold_nugget")).contains("родня"));
        assertNotNull(Romance.refusePropose(new Romance.Suit(true, false, Courtship.NONE, villager, UUID.randomUUID(), null, 95, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:gold_nugget")));
        assertNotNull(Romance.refusePropose(new Romance.Suit(true, false, Courtship.NONE, villager, null, UUID.randomUUID(), 95, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:gold_nugget")));
        assertNotNull(Romance.refusePropose(new Romance.Suit(true, false, Courtship.NONE, villager, null, null, 95, Gender.FEMALE, Set.of(), Gender.FEMALE, "minecraft:gold_nugget")));
        assertNotNull(Romance.refusePropose(new Romance.Suit(true, false, Courtship.NONE, villager, null, null, 95, Gender.FEMALE, Set.of(), Gender.MALE, "minecraft:poppy")));
    }

    @Test void villageWeddingsNeedFreeMajorityAndNoBlood() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        List<Romance.Single> village = List.of(new Romance.Single(a, Gender.MALE, Set.of(), 50, true, true), new Romance.Single(b, Gender.MALE, Set.of(), 0, true, true),
                new Romance.Single(c, Gender.FEMALE, Set.of(), 10, true, true), new Romance.Single(d, Gender.FEMALE, Set.of(), 0, false, true));
        UUID[] pair = Romance.wedding(village, (x, y) -> false);
        assertArrayEquals(new UUID[]{b, c}, pair, "the least liked free villager marries first");
        assertNull(Romance.wedding(village, (x, y) -> true), "relatives never marry");
        List<Romance.Single> mostlyMarried = List.of(new Romance.Single(a, Gender.MALE, Set.of(), 0, true, true), new Romance.Single(c, Gender.FEMALE, Set.of(), 0, true, true),
                new Romance.Single(b, Gender.MALE, Set.of(), 0, false, true), new Romance.Single(d, Gender.FEMALE, Set.of(), 0, false, true));
        assertNull(Romance.wedding(mostlyMarried, (x, y) -> false), "half free is not more than half");
    }

    @Test void oldVillageBondsBecomeNeighbours() {
        MobRelations relations = new MobRelations();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        relations.together(a, "А", "minecraft:villager", b, "Б", "minecraft:villager", MobRelations.Kinship.NEIGHBOR, 1, 0, 0);
        relations.bonds.get(a.toString()).getFirst().tags.clear();
        relations.bonds.get(a.toString()).getFirst().tags.add("FAMILY");
        relations.sanitize();
        assertEquals(Set.of(MobRelations.Tag.NEIGHBOR), relations.views(a, 0).getFirst().tags());
        assertTrue(relations.views(a, 0).getFirst().describe(false).contains("сосед по деревне"));
    }

    private static MobSnapshot villager(UUID id, UUID player, long revision, String held) { return SocialLayerTest.villager(id, player, revision, false, held); }

    // A ring at 95 is accepted: the reply's commit makes the two spouses in the tree, and the prompt then says so.
    @Test void aProposalWithARingMarriesThePlayer() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male";
        ConversationStore store = ConversationStore.open(directory, config);
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        store.observe(villager(mob, player, 0, "minecraft:air"));
        boolean female = store.state.profiles.get(mob.toString()).social.gender() == Gender.FEMALE;
        if (!female) store.state.profiles.get(mob.toString()).social.gender = Gender.FEMALE.name();
        store.state.profiles.get(mob.toString()).social.traits.clear();
        store.state.profiles.get(mob.toString()).relations.get(player.toString()).score = 90;
        ConversationStore.Profile p = store.profile(villager(mob, player, store.revision(mob), "minecraft:gold_nugget"));
        GestureIntent proposal = p.socialTurn.intent(Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.ACCEPTED, proposal.outcome(), proposal.note());
        assertTrue(proposal.takesItem());
        store.social().applyGesture(mob, player, "Стив", proposal, System.currentTimeMillis());
        assertEquals(mob, store.state.family.spouse(player));
        ConversationStore.Profile married = store.profile(villager(mob, player, store.revision(mob), "minecraft:air"));
        assertFalse(married.socialTurn.schemaValues().contains("PROPOSE"));
        assertTrue(married.socialTurn.schemaValues().contains("DIVORCE"));
        assertTrue(SocialPrompt.gestures(married).contains("твой муж"), SocialPrompt.gestures(married));
        // A second villager cannot be courted while the player is married.
        UUID other = UUID.randomUUID();
        store.observe(villager(other, player, 0, "minecraft:air"));
        store.state.profiles.get(other.toString()).relations.get(player.toString()).score = 95;
        assertEquals(Gesture.Outcome.REFUSED, store.profile(villager(other, player, store.revision(other), "minecraft:gold_nugget")).socialTurn.intent(Gesture.PROPOSE).outcome());
        // Divorce: the tree forgets the marriage, the relationship drops.
        store.social().applyGesture(mob, player, "Стив", married.socialTurn.intent(Gesture.DIVORCE), System.currentTimeMillis());
        assertNull(store.state.family.spouse(player));
        assertEquals(90 + SocialLedger.WEDDING_HEARTS + SocialLedger.DIVORCE_HEARTS, store.relationship(mob, player));
    }

    @Test void aReplyThatSaysYesToARefusedProposalIsCorrected() {
        GestureIntent refused = new GestureIntent(Gesture.PROPOSE, Gesture.Outcome.REFUSED, 0, "", "ты пока недостаточно его любишь");
        assertThrows(AnswerValidator.Rejected.class, () -> AnswerValidator.checkGesture("Ох… да! Я согласна!", refused));
        assertDoesNotThrow(() -> AnswerValidator.checkGesture("Я не согласна, рано ещё.", refused));
        assertDoesNotThrow(() -> AnswerValidator.checkGesture("Я согласна!", new GestureIntent(Gesture.PROPOSE, Gesture.Outcome.ACCEPTED, 10, "minecraft:gold_nugget")));
    }

    // A wedding in the village, then the husband is killed by the player: the widow mourns unseen and turns on the killer.
    @Test void weddingsDeathsAndMourning() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID player = UUID.randomUUID();
        UUID man = null, woman = null;
        while (man == null || woman == null) {
            UUID id = UUID.randomUUID();
            SocialProfile social = SocialProfile.of(id, false);
            if (!social.traits().isEmpty()) continue;
            if (social.gender() == Gender.MALE && man == null) man = id; else if (social.gender() == Gender.FEMALE && woman == null) woman = id;
        }
        UUID[] pair = store.social().villageWedding(List.of(new SocialLedger.Resident(man, "Пётр", true), new SocialLedger.Resident(woman, "Марфа", true)), player, 1000);
        assertNotNull(pair);
        assertEquals(woman, store.social().spouse(man));
        assertTrue(store.events(woman).stream().anyMatch(e -> e.contains("брак")));
        ConversationStore.Profile wife = store.profile(villager(woman, player, store.revision(woman), "minecraft:air"));
        // The husband carries the name the social layer seeded for him (the observed «Пётр» is only what the game showed).
        assertTrue(MobPrompt.kin(wife, villager(woman, player, 0, "minecraft:air")).contains(store.displayName(man) + " — твой муж"), MobPrompt.kin(wife, villager(woman, player, 0, "minecraft:air")));
        int before = store.relationship(woman, player);
        List<SocialLedger.Mourner> mourners = store.social().died(man, "Пётр", "minecraft:villager", player, "Стив", 2000);
        assertEquals(1, mourners.size()); assertEquals("муж", mourners.getFirst().word());
        assertEquals(before + SocialLedger.KIN_KILLED, store.relationship(woman, player));
        assertTrue(store.bonds(woman, 2000).stream().anyMatch(MobRelations.View::mourns));
        assertNull(store.social().spouse(woman));
        assertTrue(store.events(woman).stream().anyMatch(e -> e.contains("овдовел")));
        // The tree survives a reload.
        ConversationStore.closeWorld(directory);
        ConversationStore loaded = ConversationStore.open(directory, new MobTalkConfig());
        assertTrue(loaded.state.family.widowed(woman));
    }
}
