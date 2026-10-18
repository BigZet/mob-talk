package io.github.mobtalk.core;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {
    @TempDir Path directory;
    private final MobTalkConfig config = new MobTalkConfig();
    @BeforeEach void fixture() { TradeFixtures.install(); }
    @AfterEach void restoreBundle() { TradeFixtures.reset(); }

    private MobSnapshot cow(UUID id, UUID player, long revision, Map<String,Object> details) {
        return new MobSnapshot(id, "minecraft:cow", "Корова", 10, 10, "minecraft:overworld", "minecraft:plains",
                "day", "clear", 2, "minecraft:stick", List.of(), List.of(), player,
                Map.of("name", "Игрок"), details, Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }

    // Separate engines must see pre-selection damage and cannot commit a reply captured before that hit.
    @Test void sharesWorldStateAndRejectsStaleMemoryCommit() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore first = ConversationStore.open(directory, config);
        ConversationStore second = ConversationStore.open(directory.resolve("."), config);
        assertSame(first, second);
        first.recordDamage(mob, "minecraft:cow", "Корова", player, "Игрок", 1000);
        assertEquals(-80, second.card(mob, player).relationship());
        assertFalse(second.commit(cow(mob, player, 0, Map.of()), "привет", "ответ", "устарело", SocialIntent.NONE, ActionIntent.none(), 2000));
        assertTrue(second.card(mob, player).turns().isEmpty());
        assertTrue(second.commit(cow(mob, player, first.revision(mob), Map.of()), "привет", "ответ", "помню удар", SocialIntent.NONE, ActionIntent.none(), 2000));
        ConversationStore.closeWorld(directory);
        ConversationStore loaded = ConversationStore.open(directory, config);
        assertEquals(-80, loaded.card(mob, player).relationship());
        assertEquals("помню удар", loaded.card(mob, player).memory());
        ConversationStore.closeWorld(directory);
    }

    // Reported in game: nine insults and death threats left a golem at -20, so it could never fight back.
    @Test void twoSpacedDeathThreatsLetAPeacefulMobAttack() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, player, 0, Map.of()));
        assertTrue(store.commit(cow(mob, player, store.revision(mob), Map.of()), "Убью тебя", "Попробуй", "", SocialIntent.THREAT, ActionIntent.none(), 1000));
        assertTrue(store.commit(cow(mob, player, store.revision(mob), Map.of()), "Убью!", "Слышал", "", SocialIntent.THREAT, ActionIntent.none(), 5000));
        assertEquals(-25, store.card(mob, player).relationship());
        assertFalse(CombatRules.mayAttackPlayer(false, store.card(mob, player).relationship(), false));
        assertTrue(store.commit(cow(mob, player, store.revision(mob), Map.of()), "Всех вырежу", "Ну всё", "", SocialIntent.THREAT, ActionIntent.none(), 21_000));
        assertEquals(-50, store.card(mob, player).relationship());
        assertTrue(CombatRules.mayAttackPlayer(false, store.card(mob, player).relationship(), false));
        assertTrue(store.card(mob, player).events().getFirst().contains("угрожал убийством"));
        ConversationStore.closeWorld(directory);
    }

    // Dropping persisted timestamps lets repeated chat apologies or help inflate attitude without a cooldown.
    @Test void boundsAttitudeAndPersistsPerPlayerCooldowns() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID(), other = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.recordDamage(mob, "minecraft:cow", "Корова", player, "Игрок", 1000);
        store.recordDamage(mob, "minecraft:cow", "Корова", player, "Игрок", 1100);
        assertEquals(-100, store.card(mob, player).relationship());
        store.recordHelp(mob, "minecraft:cow", "Корова", player, "Игрок", 2000);
        store.recordHelp(mob, "minecraft:cow", "Корова", player, "Игрок", 2001);
        assertEquals(-80, store.card(mob, player).relationship());
        MobSnapshot snapshot = cow(mob, player, store.revision(mob), Map.of());
        assertTrue(store.commit(snapshot, "прости", "ладно", "", SocialIntent.APOLOGY, ActionIntent.none(), 3000));
        assertTrue(store.commit(snapshot, "прости ещё", "помню", "", SocialIntent.APOLOGY, ActionIntent.none(), 3001));
        assertEquals(-75, store.card(mob, player).relationship());
        assertTrue(store.commit(snapshot, "добрые слова", "слышу", "", SocialIntent.KIND, ActionIntent.none(), 4000));
        assertEquals(-75, store.card(mob, player).relationship());
        assertEquals(0, store.card(mob, other).relationship());
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        assertTrue(store.commit(cow(mob, player, store.revision(mob), Map.of()), "прости", "слышу", "", SocialIntent.APOLOGY, ActionIntent.none(), 4001));
        assertEquals(-75, store.card(mob, player).relationship());
        ConversationStore.closeWorld(directory);
    }

    // Durable per-player relation facts must outlive the rotating event log without accusing another player.
    @Test void keepsDislikeReasonsScopedToCurrentPlayerAfterEventRotation() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID(), other = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.recordDamage(mob, "minecraft:cow", "Корова", player, "Первый", 1000);
        store.recordDamage(mob, "minecraft:cow", "Корова", player, "Первый", 1100);
        store.recordHelp(mob, "minecraft:cow", "Корова", player, "Первый", 2000);
        store.observe(cow(mob, player, store.revision(mob), Map.of("ownerId", player.toString())));
        MobSnapshot snapshot = cow(mob, player, store.revision(mob), Map.of());
        assertTrue(store.commit(snapshot, "Прости", "Помню", "", SocialIntent.APOLOGY, ActionIntent.none(), 3000));
        assertTrue(store.commit(snapshot, "Глупая корова", "Обидно", "", SocialIntent.INSULT, ActionIntent.none(), 4000));
        for (int i = 0; i < 25; i++) store.recordEvent(mob, "minecraft:cow", "Корова", "Прошёл день " + i, 5000 + i);
        assertEquals(20, store.events(mob).size());
        assertTrue(store.events(mob).stream().noneMatch(text -> text.contains("ударил")));
        List<String> ownEvents = store.card(mob, player).events();
        String ownFacts = ownEvents.getFirst();
        assertTrue(ownFacts.contains(player.toString()));
        for (String fact : List.of("ударов от него: 2", "подтверждён как владелец", "реальная помощь", "реплика распознана как извинение", "реплика распознана как оскорбление"))
            assertTrue(ownFacts.contains(fact), fact);
        assertEquals(ownEvents, store.profile(cow(mob, player, store.revision(mob), Map.of())).recentEvents);
        List<String> otherEvents = store.observe(cow(mob, other, store.revision(mob), Map.of())).events();
        String otherFacts = otherEvents.getFirst();
        assertTrue(otherFacts.contains(other.toString()));
        assertTrue(otherFacts.contains("ударов от него: 0"));
        assertFalse(otherFacts.contains(player.toString()));
        assertFalse(otherFacts.contains("реальная помощь"));
        assertFalse(otherFacts.contains("оскорбление"));
        assertEquals(store.events(mob), otherEvents.subList(1, otherEvents.size()));
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        assertEquals(ownEvents, store.card(mob, player).events());
        assertEquals(otherEvents, store.card(mob, other).events());
        ConversationStore.closeWorld(directory);
    }

    // Allocation needs all three slots before reusing one, and hostility cannot become friendship through help.
    @Test void balancesVariantsAndKeepsHostileCeilingNegative() throws Exception {
        ConversationStore store = ConversationStore.open(directory, config);
        UUID player = UUID.randomUUID();
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            UUID mob = UUID.randomUUID();
            store.profile(cow(mob, player, 0, Map.of()));
            slots.add(store.card(mob, player).variantSlot());
        }
        assertEquals(java.util.Set.of(0, 1, 2), slots);
        UUID hostile = UUID.randomUUID();
        for (int i = 0; i < 12; i++) store.recordHelp(hostile, "minecraft:zombie", "Зомби", player, "Игрок", 1000 + i * 31_000L);
        assertTrue(store.card(hostile, player).relationship() < 0);
        ConversationStore.closeWorld(directory);
    }

    // A shared v1 voice must move once into disjoint species pools without losing either mob's past.
    @Test void migratesSharedLegacyVoicesIntoStableSpeciesSlotsAfterBackup() throws Exception {
        UUID mob = UUID.randomUUID(), mooshroom = UUID.randomUUID();
        Path file = directory.resolve("data/mobtalk/conversations.json");
        Files.createDirectories(file.getParent());
        String original = "{\"version\":1,\"profiles\":{\"" + mob + "\":{\"type\":\"minecraft:cow\",\"name\":\"Моя корова\",\"personality\":\"Старая общая личность\",\"voiceId\":\"LegacySharedVoice000001\",\"memory\":\"Знакомы\",\"turns\":[{\"user\":\"привет\",\"assistant\":\"помню\"}]},\"" + mooshroom + "\":{\"type\":\"minecraft:mooshroom\",\"name\":\"Грибочка\",\"personality\":\"Старая общая личность\",\"voiceId\":\"LegacySharedVoice000001\",\"memory\":\"Остров\",\"turns\":[]}}}";
        Files.writeString(file, original);
        ConversationStore store = ConversationStore.open(directory, config);
        MobCard card = store.card(mob, UUID.randomUUID());
        MobCard mushroomCard = store.card(mooshroom, UUID.randomUUID());
        assertEquals("Моя корова", card.name());
        assertTrue(card.variantSlot() >= 0 && card.variantSlot() < 3);
        assertTrue(mushroomCard.variantSlot() >= 0 && mushroomCard.variantSlot() < 3);
        assertEquals(SpeciesCatalog.get(card.type()).variants().get(card.variantSlot()).voice().id(), card.voiceId());
        assertEquals(SpeciesCatalog.get(mushroomCard.type()).variants().get(mushroomCard.variantSlot()).voice().id(), mushroomCard.voiceId());
        assertNotEquals(card.voiceId(), mushroomCard.voiceId());
        assertEquals("Знакомы", card.memory());
        assertEquals(1, card.turns().size());
        store.flush();
        assertEquals(original, Files.readString(file.resolveSibling("conversations.v1.backup.json")));
        assertEquals(2, JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt());
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        assertEquals(card.voiceId(), store.card(mob, null).voiceId());
        assertEquals(card.variantSlot(), store.card(mob, null).variantSlot());
        assertEquals(mushroomCard.voiceId(), store.card(mooshroom, null).voiceId());
        assertEquals(mushroomCard.variantSlot(), store.card(mooshroom, null).variantSlot());
        ConversationStore.closeWorld(directory);
    }

    // Four independent temperament axes and species concerns must survive reload, not be rerolled per request.
    @Test void persistsFourTemperamentAxesAndSpeciesPreferenceAndFear() throws Exception {
        UUID mob = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, UUID.randomUUID(), 0, Map.of()));
        store.flush();
        Path file = directory.resolve("data/mobtalk/conversations.json");
        var profile = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("profiles").getAsJsonObject(mob.toString());
        for (String axis : List.of("calm", "confidence", "curiosity", "sociability")) {
            assertTrue(profile.has(axis), axis);
            assertTrue(profile.get(axis).getAsInt() >= 0 && profile.get(axis).getAsInt() <= 100);
        }
        for (String concern : List.of("preference", "fear")) assertFalse(profile.get(concern).getAsString().isBlank());
        String preserved = profile.toString();
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        store.observe(cow(mob, null, store.revision(mob), Map.of()));
        store.flush();
        assertEquals(preserved, JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("profiles").getAsJsonObject(mob.toString()).toString());
        ConversationStore.closeWorld(directory);
    }

    // Personality reset must not forgive a hit, erase memory, or change this individual's voice/action.
    @Test void resetsOnlyPersonalityAndKeepsHistoryRelationshipsAndVoice() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, player, 0, Map.of()));
        store.updateCard(mob, "Моя корова", "Моя отдельная личность", null, "Помню дорогу домой");
        store.recordDamage(mob, "minecraft:cow", "Корова", player, "Игрок", 1000);
        ActionIntent following = new ActionIntent(ActionType.FOLLOW, player.toString());
        store.recordAction(mob, following);
        assertTrue(store.commit(cow(mob, player, store.revision(mob), Map.of()), "Привет", "Помню тебя", "Помню дорогу домой",
                SocialIntent.NONE, ActionIntent.none(), 2000));
        MobCard before = store.card(mob, player);
        store.resetPersonality(mob);
        MobCard after = store.card(mob, player);
        assertEquals(before.id(), after.id());
        assertEquals(before.name(), after.name());
        assertEquals(before.voiceId(), after.voiceId());
        assertEquals(before.variantSlot(), after.variantSlot());
        assertEquals(before.turns(), after.turns());
        assertEquals(before.memory(), after.memory());
        assertEquals(before.events(), after.events());
        assertEquals(before.relationship(), after.relationship());
        assertEquals(before.action(), after.action());
        assertEquals(before.revision() + 1, after.revision());
        assertEquals("", after.customPersonality());
        assertEquals(SpeciesCatalog.effective(after.type(), config).variants().get(after.variantSlot()).prompt(), after.personality());
        for (int axis : List.of(after.calm(), after.confidence(), after.curiosity(), after.sociability())) assertTrue(axis >= 0 && axis <= 100);
        assertTrue(SpeciesCatalog.get(after.type()).interests().contains(after.preference()));
        assertTrue(SpeciesCatalog.get(after.type()).fears().contains(after.fear()));
        assertEquals(List.of("Особенно ценит: " + after.preference(), "Лично тревожит: " + after.fear()),
                store.profile(cow(mob, player, after.revision(), Map.of())).traits);
        assertFalse(store.commit(cow(mob, player, before.revision(), Map.of()), "Старый вопрос", "Поздний ответ", "", SocialIntent.NONE, ActionIntent.none(), 3000));
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        assertEquals(after, store.card(mob, player));
        ConversationStore.closeWorld(directory);
    }

    // Owner affinity applies only to the real owner and must not erase that owner's previous attacks.
    @Test void ownerAffinityDoesNotForgiveDamageOrApplyToStrangers() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID(), other = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        assertEquals(65, store.observe(cow(mob, player, 0, Map.of("ownerId", player.toString()))).relationship());
        assertEquals(0, store.card(mob, other).relationship());
        store.recordDamage(mob, "minecraft:cow", "Корова", player, "Игрок", 1000);
        assertEquals(-80, store.observe(cow(mob, player, store.revision(mob), Map.of("ownerId", player.toString()))).relationship());
        ConversationStore.closeWorld(directory);
    }

    // Retention and prompt windows are independent: the 101st exchange only removes the oldest disk exchange.
    @Test void retainsAtMostOneHundredExchangesAndStableTraits() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        MobSnapshot snapshot = cow(mob, player, 0, Map.of());
        ConversationStore.Profile profile = store.profile(snapshot);
        assertEquals(2, profile.traits.size());
        for (int i = 0; i < 105; i++) assertTrue(store.commit(snapshot, "question " + i, "answer", "memory", SocialIntent.NONE, ActionIntent.none(), i));
        assertEquals(100, store.card(mob, player).turns().size());
        assertEquals("question 5", store.card(mob, player).turns().getFirst().user());
        List<String> traits = List.copyOf(profile.traits);
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        assertEquals(traits, store.profile(snapshot).traits);
        ConversationStore.closeWorld(directory);
    }

    // A conversational NONE or unrelated hit cannot overwrite an already validated persistent FOLLOW.
    @Test void keepsValidatedActionUntilAdapterExplicitlyChangesIt() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, player, 0, Map.of()));
        ActionIntent following = new ActionIntent(ActionType.FOLLOW, player.toString());
        store.recordAction(mob, following);
        assertTrue(store.commit(cow(mob, player, store.revision(mob), Map.of()), "привет", "иду", "", SocialIntent.NONE, ActionIntent.none(), 1000));
        assertEquals(following, store.action(mob));
        store.recordDamage(mob, "minecraft:cow", "Корова", UUID.randomUUID(), "Другой", 2000);
        assertEquals(following, store.action(mob));
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        assertEquals(following, store.action(mob));
        store.recordAction(mob, ActionIntent.none());
        assertEquals(ActionIntent.none(), store.action(mob));
        ConversationStore.closeWorld(directory);
    }

    // Later changes to nested capture containers must not change what a background request sees.
    @Test void detachesNestedSnapshotDetails() {
        java.util.ArrayList<String> equipment = new java.util.ArrayList<>(List.of("minecraft:stick"));
        Map<String,Object> details = new java.util.LinkedHashMap<>();
        details.put("equipment", equipment);
        MobSnapshot snapshot = cow(UUID.randomUUID(), UUID.randomUUID(), 0, details);
        equipment.add("minecraft:diamond_sword");
        details.put("ownerId", UUID.randomUUID().toString());
        assertEquals(List.of("minecraft:stick"), snapshot.mobDetails().get("equipment"));
        assertFalse(snapshot.mobDetails().containsKey("ownerId"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.mobDetails().put("new", true));
    }

    // Negotiated prices, opened secrets and quests belong to one player and one mob, survive reload, and bump the revision.
    @Test void dealsAndQuestsSurviveReloadWithoutLeakingToAnotherPlayerOrMob() throws Exception {
        UUID mob = UUID.randomUUID(), otherMob = UUID.randomUUID(), player = UUID.randomUUID(), other = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, player, 0, Map.of()));
        store.observe(cow(otherMob, player, 0, Map.of()));
        assertEquals(TradeState.initial(), store.tradeState(mob, player, "milk"));
        assertEquals(Map.of(), store.tradeStates(mob, player));
        assertEquals(Map.of(), store.quests(UUID.randomUUID(), player), "unknown mob reads as empty");
        long revision = store.revision(mob);
        store.setDealPrice(mob, player, "milk", 3, 1000);
        assertEquals(revision + 1, store.revision(mob));
        store.unlockDeal(mob, player, "leather", 2000);
        store.unlockDeal(mob, player, "leather", 2001);
        assertEquals(revision + 2, store.revision(mob), "a repeated unlock changes nothing");
        store.startQuest(mob, player, "fence", 3000);
        store.startQuest(mob, player, "fence", 3001);
        assertEquals(revision + 3, store.revision(mob), "re-offering the active quest is a no-op");
        assertThrows(IllegalStateException.class, () -> store.startQuest(mob, player, "wolves", 3002), "one active quest per mob and player");
        assertThrows(IllegalArgumentException.class, () -> store.setDealPrice(mob, null, "milk", 2, 3003));
        assertThrows(IllegalArgumentException.class, () -> store.setDealPrice(UUID.randomUUID(), player, "milk", 2, 3003), "no profile yet");
        Map<String, TradeState> deals = store.tradeStates(mob, player);
        assertEquals(new TradeState(3, false, 1, 1000, 0, -1), deals.get("milk"));
        assertEquals(new TradeState(0, true, 0, -1, 0, -1), deals.get("leather"));
        assertEquals(Map.of("fence", new QuestState(QuestState.Status.ACTIVE, 0, 3000)), store.quests(mob, player));
        assertThrows(UnsupportedOperationException.class, () -> store.tradeStates(mob, player).put("hay", TradeState.initial()));
        assertEquals(TradeState.initial(), store.tradeState(mob, other, "milk"));
        assertEquals(TradeState.initial(), store.tradeState(otherMob, player, "milk"));
        assertEquals(Map.of(), store.quests(mob, other));
        // The whole species shares the player's quests and opened secrets; only the negotiated price stays with one mob.
        assertEquals(Map.of("fence", new QuestState(QuestState.Status.ACTIVE, 0, 3000)), store.quests(otherMob, player));
        assertTrue(store.tradeState(otherMob, player, "leather").unlocked());
        assertEquals(0, store.tradeState(otherMob, player, "leather").price());
        assertThrows(IllegalStateException.class, () -> store.startQuest(otherMob, player, "wolves", 3500), "another cow knows the fence is pending");
        List<String> events = store.events(mob);
        assertTrue(events.stream().anyMatch(e -> e.contains("секретную сделку «Кожа с подружки»")), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.equals("Игрок взял задание: Почини загон")), events.toString());
        String facts = store.card(mob, player).events().getFirst();
        assertTrue(facts.contains("ему открыты секретные сделки: Кожа с подружки"), facts);
        assertTrue(facts.contains("выполняет задание «Почини загон»"), facts);
        assertFalse(store.card(mob, other).events().getFirst().contains("задание"));
        ConversationStore.closeWorld(directory);
        ConversationStore reopened = ConversationStore.open(directory, config);
        try {
            assertEquals(deals, reopened.tradeStates(mob, player));
            assertEquals(Map.of("fence", new QuestState(QuestState.Status.ACTIVE, 0, 3000)), reopened.quests(mob, player));
            assertEquals(Map.of("fence", new QuestState(QuestState.Status.ACTIVE, 0, 3000)), reopened.quests(otherMob, player));
            assertTrue(reopened.tradeState(otherMob, player, "leather").unlocked());
            assertEquals(Map.of(), reopened.tradeStates(mob, other));
            assertEquals(facts, reopened.card(mob, player).events().getFirst());
            assertEquals(revision + 3, reopened.revision(mob));
        } finally { ConversationStore.closeWorld(directory); }
    }

    // A finished quest pays out once: relationship (clamped), the secret deal opens, and the discount lowers the permanent price.
    @Test void completedQuestAppliesRewardOnceAndPermanently() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID(), zombie = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, player, 0, Map.of()));
        assertThrows(IllegalStateException.class, () -> store.completeQuest(mob, player, "fence", "Игрок", 100), "not started");
        store.startQuest(mob, player, "fence", 1000);
        store.setDealPrice(mob, player, "milk", 3, 1500);
        long revision = store.revision(mob);
        assertEquals(new QuestDefinition.Reward(30, "leather", "milk", 25), store.completeQuest(mob, player, "fence", "Игрок", 2000));
        assertEquals(revision + 1, store.revision(mob));
        assertEquals(30, store.card(mob, player).relationship());
        assertTrue(store.tradeState(mob, player, "leather").unlocked());
        assertEquals(new TradeState(2, false, 1, 1500, 0, -1), store.tradeState(mob, player, "milk"), "3 × 75% = 2, rounds untouched");
        assertEquals(QuestState.Status.DONE, store.quests(mob, player).get("fence").status());
        assertTrue(store.events(mob).stream().anyMatch(e -> e.startsWith("Игрок Игрок выполнил задание: Почини загон")), store.events(mob).toString());
        assertTrue(store.card(mob, player).events().getFirst().contains("выполнил задания (это знают все сородичи): Почини загон"));
        assertThrows(IllegalStateException.class, () -> store.completeQuest(mob, player, "fence", "Игрок", 2001), "no second payout");
        assertThrows(IllegalStateException.class, () -> store.startQuest(mob, player, "fence", 2002), "cannot restart a done quest");
        assertEquals(30, store.card(mob, player).relationship());
        store.startQuest(mob, player, "wolves", 2100);
        assertEquals(List.of("wolves"), store.quests(mob, player).entrySet().stream().filter(e -> e.getValue().status() == QuestState.Status.ACTIVE).map(Map.Entry::getKey).toList());
        // A second cow already knows the fence is done: it cannot ask again, shows the opened secret, but keeps its own full price.
        UUID second = UUID.randomUUID();
        store.observe(cow(second, player, 0, Map.of()));
        assertThrows(IllegalStateException.class, () -> store.startQuest(second, player, "fence", 3000));
        assertEquals(QuestState.Status.DONE, store.quests(second, player).get("fence").status());
        assertTrue(store.tradeState(second, player, "leather").unlocked());
        assertEquals(0, store.tradeState(second, player, "milk").price(), "the discount went to the giver only");
        assertEquals(0, store.card(second, player).relationship(), "so did the gratitude");
        assertTrue(store.card(second, player).events().getFirst().contains("выполнил задания (это знают все сородичи): Почини загон"));
        // Hostile species: the reward cannot lift the relationship above the hostile ceiling.
        store.observe(new MobSnapshot(zombie, "minecraft:zombie", "Зомби", 10, 10, "minecraft:overworld", "minecraft:plains",
                "night", "clear", 2, "minecraft:stick", List.of(), List.of(), player, Map.of("name", "Игрок"), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0));
        store.startQuest(zombie, player, "any", 4000);
        assertEquals(new QuestDefinition.Reward(0, "", "", 0), store.completeQuest(zombie, player, "any", "Игрок", 4100), "a quest unknown to the catalog pays nothing");
        assertTrue(store.card(zombie, player).relationship() < 0);
        ConversationStore.closeWorld(directory);
        ConversationStore reopened = ConversationStore.open(directory, config);
        try {
            assertEquals(30, reopened.card(mob, player).relationship());
            assertEquals(2, reopened.tradeState(mob, player, "milk").price());
            assertTrue(reopened.tradeState(mob, player, "leather").unlocked());
            assertEquals(QuestState.Status.DONE, reopened.quests(mob, player).get("fence").status());
        } finally { ConversationStore.closeWorld(directory); }
    }

    // Kills count for every active KILL quest of that player across mobs, saturate at the target, and ignore other players.
    @Test void killsAdvanceOnlyMatchingActiveQuestsOfThatPlayer() throws Exception {
        UUID mob = UUID.randomUUID(), otherMob = UUID.randomUUID(), player = UUID.randomUUID(), other = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, player, 0, Map.of()));
        store.observe(cow(otherMob, player, 0, Map.of()));
        store.observe(cow(otherMob, other, 0, Map.of()));
        assertEquals(0, store.recordKill(player, "minecraft:wolf"), "nothing active");
        store.startQuest(mob, player, "wolves", 1000);
        store.startQuest(otherMob, other, "wolves", 1000);
        long revision = store.revision(mob), otherRevision = store.revision(otherMob);
        assertEquals(0, store.recordKill(player, "minecraft:cow"));
        assertEquals(0, store.recordKill(null, "minecraft:wolf"));
        assertEquals(1, store.recordKill(player, "minecraft:wolf"));
        assertEquals(1, store.recordKill(player, "wolf"), "namespace defaults to minecraft");
        assertEquals(0, store.recordKill(player, "minecraft:wolf"), "saturated at count");
        assertEquals(2, store.quests(mob, player).get("wolves").progress());
        assertEquals(0, store.quests(otherMob, other).get("wolves").progress(), "another player's quest");
        assertEquals(2, store.quests(otherMob, player).get("wolves").progress(), "the whole species counts the same kills");
        assertEquals(revision + 2, store.revision(mob));
        assertEquals(otherRevision + 2, store.revision(otherMob), "the other cow knows this player too");
        assertTrue(Trading.canComplete(TradeCatalog.quest("minecraft:cow", "wolves"), store.quests(mob, player).get("wolves"), Map.of()));
        assertEquals(10, store.completeQuest(mob, player, "wolves", "Игрок", 5000).relationship());
        assertEquals(10, store.card(mob, player).relationship());
        assertThrows(IllegalStateException.class, () -> store.startQuest(otherMob, other, "fence", 6000), "the other player's wolves quest is still active");
        ConversationStore.closeWorld(directory);
    }

    // Buying is remembered as an event and warms the mob up a little, once a minute; daily stock is counted per deal.
    @Test void purchasesCountStockAndWarmRelationshipOncePerMinute() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(mob, player, 0, Map.of()));
        store.setDealPrice(mob, player, "milk", 4, 500);
        store.recordPurchase(mob, player, "milk", "Игрок", 7, 1000);
        store.recordPurchase(mob, player, "milk", "Игрок", 7, 1001);
        assertEquals(5, store.card(mob, player).relationship());
        store.recordPurchase(mob, player, "leather", "Игрок", 7, 61_000);
        assertEquals(10, store.card(mob, player).relationship());
        assertEquals(new TradeState(4, false, 1, 500, 2, 7), store.tradeState(mob, player, "milk"));
        assertEquals(new TradeState(0, false, 0, -1, 1, 7), store.tradeState(mob, player, "leather"));
        assertEquals(0, Trading.stockLeft(TradeCatalog.deal("minecraft:cow", "leather"), store.tradeState(mob, player, "leather"), 7));
        assertEquals(1, Trading.stockLeft(TradeCatalog.deal("minecraft:cow", "leather"), store.tradeState(mob, player, "leather"), 8));
        List<String> events = store.events(mob);
        assertTrue(events.contains("Игрок Игрок купил 1× Ведро молока за 4× Пшеница; отношение 5"), events.toString());
        assertTrue(events.contains("Игрок Игрок купил 3× Кожа за 2× Изумруд; отношение 10"), events.toString());
        assertTrue(store.card(mob, player).events().getFirst().contains("он уже покупал у моба"));
        store.recordPurchase(mob, player, "ghost", "Игрок", 7, 62_000);
        assertTrue(store.events(mob).contains("Игрок Игрок купил товар «ghost»; отношение 10"), "unknown deal ids still log");
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        try {
            assertEquals(10, store.card(mob, player).relationship());
            assertEquals(2, store.tradeState(mob, player, "milk").boughtToday());
            store.recordPurchase(mob, player, "milk", "Игрок", 7, 62_500);
            assertEquals(10, store.card(mob, player).relationship(), "the purchase cooldown survives reload");
        } finally { ConversationStore.closeWorld(directory); }
    }

    // A world saved before 0.3 has relations without deal/quest maps; it must load and start trading from scratch.
    @Test void loadsPreTradingFilesAndDropsDamagedTradeEntries() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        Path file = directory.resolve("data/mobtalk/conversations.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":2,\"profiles\":{\"" + mob + "\":{\"type\":\"minecraft:cow\",\"name\":\"Корова\",\"personality\":\"Личность\","
                + "\"voiceId\":\"LegacySharedVoice000001\",\"memory\":\"Знакомы\",\"variantSlot\":1,\"revision\":7,"
                + "\"relations\":{\"" + player + "\":{\"score\":35,\"hits\":0,\"lastHelp\":10,\"lastApology\":-1,\"lastInsult\":-1,\"lastThreat\":-1,\"ownerKnown\":false}}}},\"bargains\":{}}");
        ConversationStore store = ConversationStore.open(directory, config);
        assertEquals(35, store.card(mob, player).relationship());
        assertEquals(Map.of(), store.tradeStates(mob, player));
        assertEquals(Map.of(), store.quests(mob, player));
        assertEquals(TradeState.initial(), store.tradeState(mob, player, "milk"));
        store.setDealPrice(mob, player, "milk", 5, 100);
        assertEquals(5, store.tradeState(mob, player, "milk").price());
        ConversationStore.closeWorld(directory);
        // A hand-edited file with a null deal entry and a quest without status must not take the whole world down.
        String saved = Files.readString(file);
        Files.writeString(file, saved.replace("\"deals\": {", "\"deals\": {\"broken\": null,").replace("\"quests\": {}", "\"quests\": {\"fence\": {\"progress\": 1}, \"wolves\": {\"status\": \"DONE\", \"progress\": 2, \"startedMillis\": 5}}"));
        store = ConversationStore.open(directory, config);
        try {
            assertEquals(Map.of("milk", new TradeState(5, false, 1, 100, 0, -1)), store.tradeStates(mob, player));
            assertEquals(Map.of("wolves", new QuestState(QuestState.Status.DONE, 2, 5)), store.quests(mob, player));
        } finally { ConversationStore.closeWorld(directory); }
    }
}
