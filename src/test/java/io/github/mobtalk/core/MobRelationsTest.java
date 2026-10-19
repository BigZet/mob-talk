package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MobRelationsTest {
    private static final long SECOND = 1_000L, MINUTE = 60 * SECOND;
    private final UUID aksinya = UUID.randomUUID(), prokhor = UUID.randomUUID(), wolf = UUID.randomUUID();
    @TempDir Path directory;
    @BeforeEach void fixture() { TradeFixtures.install(); }
    @AfterEach void restoreBundle() { TradeFixtures.reset(); }

    private static MobRelations.View bond(List<MobRelations.View> views, UUID other) {
        return views.stream().filter(view -> view.other().equals(other)).findFirst().orElse(null);
    }
    private static boolean neighbours(MobRelations relations, UUID a, UUID b, long now) {
        return relations.together(a, "Аксинья", "minecraft:villager", b, "Прохор", "minecraft:villager", MobRelations.Kinship.FAMILY,
                MobRelations.TOGETHER_GAIN, MobRelations.TOGETHER_INTERVAL, now);
    }

    // Two villagers of one village start as family and warm up one point per half minute, never past the sympathy cap; a wolf
    // of another kind starts from nothing. Friendship and rivalry are read off the score, kinship is a stored fact.
    @Test void standingTogetherGrowsSlowlyIntoFriendship() {
        MobRelations relations = new MobRelations();
        assertTrue(neighbours(relations, aksinya, prokhor, 0));
        assertFalse(neighbours(relations, aksinya, prokhor, 10 * SECOND), "within the interval nothing changes");
        MobRelations.View view = bond(relations.views(aksinya, 10 * SECOND), prokhor);
        assertEquals(MobRelations.FAMILY_START, view.score()); assertEquals("Прохор", view.name());
        assertEquals(Set.of(MobRelations.Tag.FAMILY), view.tags());
        assertFalse(view.friend());
        long now = 0;
        for (int i = 0; i < 40; i++) { now += MobRelations.TOGETHER_INTERVAL; neighbours(relations, aksinya, prokhor, now); }
        view = bond(relations.views(aksinya, now), prokhor);
        assertEquals(MobRelations.FAMILY_START + 40, view.score());
        assertTrue(view.friend(), "forty half-minutes side by side make a friend: " + view);
        assertTrue(bond(relations.views(prokhor, now), aksinya).friend(), "the bond is kept on both sides");
        for (int i = 0; i < 100; i++) { now += MobRelations.TOGETHER_INTERVAL; neighbours(relations, aksinya, prokhor, now); }
        assertEquals(MobRelations.SYMPATHY_CAP, bond(relations.views(aksinya, now), prokhor).score(), "proximity alone stops at the cap");
        assertTrue(relations.together(aksinya, "Аксинья", "minecraft:villager", wolf, "Серый", "minecraft:wolf", MobRelations.Kinship.NONE,
                MobRelations.MET_GAIN, MobRelations.MET_INTERVAL, now));
        MobRelations.View stranger = bond(relations.views(aksinya, now), wolf);
        assertEquals(0, stranger.score()); assertTrue(stranger.tags().isEmpty(), "no kinship, no tag: " + stranger);
        assertEquals(MobRelations.Kinship.FAMILY, MobRelations.kinship("minecraft:villager", "village-1", "minecraft:iron_golem", "village-1"));
        assertEquals(MobRelations.Kinship.KIN, MobRelations.kinship("minecraft:villager", "village-1", "minecraft:villager", "village-2"));
        assertEquals(MobRelations.Kinship.KIN, MobRelations.kinship("minecraft:villager", "", "minecraft:villager", ""), "no village is not one village, but one species still");
        assertEquals(MobRelations.Kinship.NONE, MobRelations.kinship("minecraft:iron_golem", "", "minecraft:villager", ""));
        assertEquals(MobRelations.Kinship.NONE, MobRelations.kinship("minecraft:wolf", "", "minecraft:sheep", ""));
    }

    // A blow makes an enemy of the attacker at once and sours the attacker a little; the feud fades by halves with time and
    // standing together does not mend it. A healed feud lets the two start over.
    @Test void aBlowMakesEnemiesAndTimeWearsTheFeudDown() {
        MobRelations relations = new MobRelations();
        assertTrue(relations.attacked(wolf, "Серый", "minecraft:wolf", aksinya, "Аксинья", "minecraft:villager", 0));
        MobRelations.View victim = bond(relations.views(aksinya, 0), wolf), attacker = bond(relations.views(wolf, 0), aksinya);
        assertEquals(MobRelations.ATTACKED_DROP, victim.score()); assertTrue(victim.rival(), victim.toString());
        assertEquals(MobRelations.ATTACKER_DROP, attacker.score()); assertFalse(attacker.rival(), "the attacker merely dislikes its prey");
        assertTrue(victim.describe(false).contains("Серый (волк) — враг, ты его сторонишься"), victim.describe(false));
        assertFalse(relations.together(aksinya, "Аксинья", "minecraft:villager", wolf, "Серый", "minecraft:wolf", MobRelations.Kinship.NONE,
                MobRelations.TOGETHER_GAIN, MobRelations.TOGETHER_INTERVAL, MINUTE), "standing next to an enemy warms nothing");
        assertEquals(MobRelations.ATTACKED_DROP, bond(relations.views(aksinya, MINUTE), wolf).score());
        assertEquals(MobRelations.ATTACKED_DROP / 2, bond(relations.views(aksinya, MobRelations.BOND_HALF_LIFE), wolf).score(), 1, "half gone after one half-life");
        assertFalse(bond(relations.views(aksinya, MobRelations.BOND_HALF_LIFE), wolf).rival());
        assertEquals(0, bond(relations.views(aksinya, 10 * MobRelations.BOND_HALF_LIFE), wolf).score(), "forgotten after a few days");
        assertEquals(MobRelations.ATTACKED_DROP, bond(relations.views(aksinya, 0), wolf).score(), "reading moves nothing");
        // Hit again long after: the drop lands on the faded score, so it never piles up past the floor in one go.
        relations.attacked(wolf, "Серый", "minecraft:wolf", aksinya, "Аксинья", "minecraft:villager", 10 * MobRelations.BOND_HALF_LIFE);
        assertEquals(MobRelations.ATTACKED_DROP, bond(relations.views(aksinya, 10 * MobRelations.BOND_HALF_LIFE), wolf).score());
        relations.attacked(wolf, "Серый", "minecraft:wolf", aksinya, "Аксинья", "minecraft:villager", 10 * MobRelations.BOND_HALF_LIFE + SECOND);
        assertEquals(2 * MobRelations.ATTACKED_DROP, bond(relations.views(aksinya, 10 * MobRelations.BOND_HALF_LIFE + SECOND), wolf).score());
    }

    // Twelve bonds at most: a stronger newcomer pushes the weakest acquaintance out, but never a loss still mourned.
    @Test void keepsTwelveBondsAndEvictsTheWeakest() {
        MobRelations relations = new MobRelations();
        UUID dead = UUID.randomUUID();
        relations.together(aksinya, "Аксинья", "minecraft:villager", dead, "Прохор", "minecraft:villager", MobRelations.Kinship.NONE, 1, 0, 0);
        assertTrue(relations.mourn(aksinya, dead, "Прохор", "minecraft:villager", false, SECOND));
        UUID[] others = new UUID[MobRelations.MAX_BONDS - 1];
        for (int i = 0; i < others.length; i++) {
            others[i] = UUID.randomUUID();
            relations.together(aksinya, "Аксинья", "minecraft:villager", others[i], "Сосед " + i, "minecraft:villager", MobRelations.Kinship.KIN, 1, 0, SECOND);
            // Each later neighbour a little closer than the one before, so the first is the weakest.
            for (int j = 0; j < i; j++) relations.together(aksinya, "Аксинья", "minecraft:villager", others[i], "Сосед " + i, "minecraft:villager", MobRelations.Kinship.KIN, 1, 0, SECOND + j);
        }
        assertEquals(MobRelations.MAX_BONDS, relations.views(aksinya, SECOND).size());
        assertFalse(relations.together(aksinya, "Аксинья", "minecraft:villager", wolf, "Серый", "minecraft:wolf", MobRelations.Kinship.NONE, 1, 0, 2 * SECOND)
                && bond(relations.views(aksinya, 2 * SECOND), wolf) != null && bond(relations.views(aksinya, 2 * SECOND), others[0]) == null,
                "a nobody does not push out a neighbour that already matters");
        assertTrue(relations.attacked(wolf, "Серый", "minecraft:wolf", aksinya, "Аксинья", "minecraft:villager", 3 * SECOND));
        List<MobRelations.View> views = relations.views(aksinya, 3 * SECOND);
        assertEquals(MobRelations.MAX_BONDS, views.size());
        assertNotNull(bond(views, wolf), "an enemy is worth remembering");
        assertNull(bond(views, others[0]), "the weakest neighbour made room");
        assertNotNull(bond(views, dead), "grief is never the weakest");
        assertEquals(dead, views.getFirst().other(), "strongest first, grief ahead of everything");
    }

    // A death seen is mourned by name for three Minecraft days, once per loss; a friend's visit is remembered with it. Only
    // someone the witness knew is mourned: a stranger with no story leaves nothing, a known enemy is not grieved.
    @Test void mournsTheDeadByNameUntilTheGriefFades() {
        MobRelations relations = new MobRelations();
        assertFalse(relations.mourn(aksinya, prokhor, "Прохор", "minecraft:villager", false, 0), "never met and no story: a stranger");
        assertTrue(relations.mourn(aksinya, prokhor, "Прохор", "minecraft:villager", true, 0), "a creature with a life story is mourned by anyone who saw it die");
        assertFalse(relations.mourn(aksinya, prokhor, "Прохор", "minecraft:villager", true, SECOND), "one loss, one grief");
        MobRelations.View grief = bond(relations.views(aksinya, MINUTE), prokhor);
        assertTrue(grief.mourns() && !grief.consoled(), grief.toString());
        assertEquals("Прохор (житель) погиб на твоих глазах; ты скорбишь по нему", grief.describe(false));
        assertEquals(grief, relations.unconsoledGrief(aksinya, MINUTE));
        assertTrue(relations.consoled(aksinya, prokhor, 2 * MINUTE));
        assertFalse(relations.consoled(aksinya, prokhor, 3 * MINUTE), "consoled once");
        assertNull(relations.unconsoledGrief(aksinya, 3 * MINUTE));
        assertTrue(bond(relations.views(aksinya, 3 * MINUTE), prokhor).describe(false).endsWith("друг приходил посидеть с тобой"));
        assertFalse(bond(relations.views(aksinya, MobRelations.GRIEF_DURATION + MINUTE), prokhor).mourns(), "three days later the grief has settled");
        assertTrue(relations.mourn(aksinya, prokhor, "Прохор", "minecraft:villager", true, MobRelations.GRIEF_DURATION + MINUTE), "a fresh loss can be mourned again");
        relations.attacked(wolf, "Серый", "minecraft:wolf", aksinya, "Аксинья", "minecraft:villager", 0);
        assertFalse(relations.mourn(aksinya, wolf, "Серый", "minecraft:wolf", false, SECOND), "an enemy is not mourned");
        assertEquals("Серый +0", new MobRelations.View(wolf, "Серый", "minecraft:wolf", 0, 0, Set.of(), false).label());
        assertEquals("Прохор -45 [соперник, семья, скорбит]", new MobRelations.View(prokhor, "Прохор", "minecraft:villager", -45, 0,
                Set.of(MobRelations.Tag.RIVAL, MobRelations.Tag.FAMILY, MobRelations.Tag.MOURNS), false).label());
    }

    // The prompt reads the three strongest bonds in words, those standing nearby marked; a loner gets no block at all.
    @Test void promptNamesTheThreeStrongestBonds() throws Exception {
        UUID golem = UUID.randomUUID(), cow = UUID.randomUUID();
        List<MobRelations.View> views = List.of(
                new MobRelations.View(prokhor, "Прохор", "minecraft:villager", 20, 0, Set.of(MobRelations.Tag.MOURNS, MobRelations.Tag.FAMILY), false),
                new MobRelations.View(golem, "Кузьма", "minecraft:iron_golem", 72, 0, Set.of(MobRelations.Tag.FRIEND, MobRelations.Tag.FAMILY), false),
                new MobRelations.View(wolf, "Серый", "minecraft:wolf", -50, 0, Set.of(MobRelations.Tag.RIVAL), false),
                new MobRelations.View(cow, "Марфа", "minecraft:cow", 3, 0, Set.of(), false));
        List<String> lines = MobRelations.lines(views, Set.of(golem), 3);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals("Прохор (житель) погиб на твоих глазах; ты скорбишь по нему", lines.get(0));
        assertEquals("Кузьма (железный голем) — близкий друг, сосед по деревне (сейчас рядом)", lines.get(1));
        assertEquals("Серый (волк) — враг, ты его сторонишься", lines.get(2));
        assertTrue(MobRelations.lines(List.of(), Set.of(), 3).isEmpty());
        // Through the world memory into the system prompt: the block, its rule, and nothing of it for a loner.
        MobTalkConfig config = new MobTalkConfig();
        ConversationStore store = ConversationStore.open(directory, config);
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis(); // The store reads the bonds at the real time of the profile.
        store.observe(villager(aksinya, player, List.of()));
        store.observe(villager(prokhor, player, List.of()));
        store.updateRelations(r -> r.together(aksinya, "Аксинья", "minecraft:villager", prokhor, "Прохор", "minecraft:villager", MobRelations.Kinship.FAMILY,
                MobRelations.TOGETHER_GAIN, MobRelations.TOGETHER_INTERVAL, now - 2000));
        store.updateRelations(r -> r.mourn(aksinya, prokhor, "Прохор", "minecraft:villager", false, now - 1000));
        ObservedEntity seen = new ObservedEntity("e1", prokhor, "minecraft:villager", "Житель", 3, "north", true, Map.of());
        ConversationStore.Profile profile = store.profile(villager(aksinya, player, List.of(seen)));
        assertEquals(1, profile.bonds.size());
        String system = new MobPrompt(config).system(SpeciesCatalog.get("minecraft:villager"), profile, villager(aksinya, player, List.of(seen)));
        assertTrue(system.contains(PromptTexts.KIN_HEADER) && system.contains("— Прохор (житель) погиб на твоих глазах; ты скорбишь по нему (сейчас рядом)")
                && system.contains(PromptTexts.KIN_RULE), system);
        ConversationStore.Profile loner = store.profile(villager(prokhor, player, List.of()));
        assertTrue(loner.bonds.stream().noneMatch(MobRelations.View::mourns), "the dead do not mourn themselves");
        ConversationStore.closeWorld(directory);
        // Reopened from disk: the grief, the name and the family tag are still there; a forgotten stranger takes its bonds along.
        store = ConversationStore.open(directory, config);
        MobRelations.View loaded = bond(store.bonds(aksinya, now), prokhor);
        assertNotNull(loaded); assertTrue(loaded.mourns() && loaded.tags().contains(MobRelations.Tag.FAMILY), loaded.toString());
        assertEquals("Прохор", loaded.name());
        assertEquals(MobRelations.Kinship.KIN, store.kinship(aksinya, prokhor), "no village yet: the same species only");
        assertTrue(store.forgetStranger(prokhor));
        assertTrue(store.bonds(prokhor, now).isEmpty());
        assertEquals("Прохор", bond(store.bonds(aksinya, now), prokhor).name(), "the mourner keeps the name of the dead");
        ConversationStore.closeWorld(directory);
    }

    // A friend of another kind standing close counts as an ally in the sober estimate, next to the kin of the same species.
    @Test void aFriendNearbyIsAnAlly() {
        UUID golem = UUID.randomUUID();
        ConversationStore.Profile profile = new ConversationStore.Profile();
        profile.type = "minecraft:villager"; profile.relationship = 0;
        profile.bonds = List.of(new MobRelations.View(golem, "Кузьма", "minecraft:iron_golem", 72, 0, Set.of(MobRelations.Tag.FRIEND), false));
        List<ObservedEntity> seen = List.of(new ObservedEntity("e1", golem, "minecraft:iron_golem", "Железный голем", 4, "north", true, Map.of()),
                new ObservedEntity("e2", UUID.randomUUID(), "minecraft:villager", "Житель", 5, "south", true, Map.of()),
                new ObservedEntity("e3", UUID.randomUUID(), "minecraft:iron_golem", "Железный голем", 6, "east", true, Map.of()));
        MobSnapshot snapshot = new MobSnapshot(aksinya, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                "minecraft:stick", List.of(), List.of(), UUID.randomUUID(), Map.of("name", "Стив", "health", 20, "maxHealth", 20),
                Map.of("fighter", false, "attackDamage", 0), Map.of(), seen, List.of(ActionType.NONE), 0);
        String text = Situation.of(snapshot, profile).describe();
        assertTrue(text.contains("рядом сородичей: 1") && text.contains("рядом друзей другого рода: 1"), "the strange golem is nobody's ally: " + text);
    }

    private static MobSnapshot villager(UUID id, UUID player, List<ObservedEntity> seen) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                "minecraft:stick", List.of(), List.of(), player, Map.of("name", "Игрок"), Map.of(), Map.of(), seen, List.of(ActionType.NONE), 0);
    }
}
