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
        // A reply captured before the blow is still committed (the player asked for no cancellations); the blow stays on the record.
        assertTrue(second.commit(cow(mob, player, 0, Map.of()), "привет", "ответ", "устарело", SocialIntent.NONE, ActionIntent.none(), 2000));
        assertEquals(1, second.card(mob, player).turns().size());
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

    // A friend or a truce ends with any blow, not only the first; a mob nobody talked to leaves no memory when it dies.
    @Test void anyBlowEndsAFriendshipAndDeadStrangersLeaveNoMemory() throws Exception {
        UUID mob = UUID.randomUUID(), stranger = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.recordDamage(mob, "minecraft:zombie", "Зомби", player, "Игрок", 1000);
        for (int i = 0; i < 12; i++) store.recordHelp(mob, "minecraft:zombie", "Зомби", player, "Игрок", 2000 + i * 31_000L);
        assertEquals(100, store.card(mob, player).relationship(), "won back all the way after the first blow");
        store.recordDamage(mob, "minecraft:zombie", "Зомби", player, "Игрок", 500_000);
        assertEquals(-20, store.card(mob, player).relationship(), "a later blow still ends the truce");
        assertFalse(CombatRules.truce(true, store.relationship(mob, player)));
        store.recordDamage(stranger, "minecraft:zombie", "Зомби", player, "Игрок", 1000);
        assertTrue(store.known(stranger)); assertFalse(store.known(UUID.randomUUID()));
        assertTrue(store.forgetStranger(stranger), "hit but never talked to: nothing worth keeping");
        assertFalse(store.known(stranger)); assertFalse(store.forgetStranger(stranger));
        // The retaliation lease of a killed stranger is stopped a tick after its profile went: that must not throw on the server thread.
        assertDoesNotThrow(() -> store.recordAction(stranger, ActionIntent.none()));
        assertEquals(ActionType.NONE, store.action(stranger).type()); assertFalse(store.known(stranger));
        assertTrue(store.commit(cow(mob, player, store.revision(mob), Map.of()), "привет", "ответ", "", SocialIntent.NONE, ActionIntent.none(), 600_000));
        assertFalse(store.forgetStranger(mob), "a conversation keeps the memory"); assertTrue(store.known(mob));
        ConversationStore.closeWorld(directory);
    }

    // What a mob sees happen to another sticks: a smaller drop, once per cooldown, with a memory; strangers notice nothing.
    @Test void witnessesTakeItPersonallyOncePerCooldown() throws Exception {
        UUID victim = UUID.randomUUID(), witness = UUID.randomUUID(), stranger = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(witness, player, 0, Map.of()));
        assertEquals("Корова", store.displayName(witness)); assertEquals("", store.displayName(stranger));
        store.setBackstory(witness, "Марфа", "Ты родилась у реки.", "", "", "");
        assertEquals("Марфа", store.displayName(witness));
        long before = store.revision(witness);
        store.recordWitnessed(witness, player, "Стив", "ударил Зорьку (корова)", -10, 1000);
        store.recordWitnessed(witness, player, "Стив", "ударил Зорьку (корова)", -10, 2000);
        assertEquals(-10, store.card(witness, player).relationship(), "the second blow within the cooldown is one event");
        store.recordWitnessed(witness, player, "Стив", "убил Зорьку (корова)", -25, 3000);
        assertEquals(-25, store.card(witness, player).relationship(), "the kill right after the blows counts in full, once");
        assertTrue(store.card(witness, player).events().stream().anyMatch(event -> event.equals("Видел, как игрок Стив убил Зорьку (корова); отношение -25")));
        assertEquals(before, store.revision(witness), "what was merely seen does not cancel a reply in flight");
        store.recordWitnessed(witness, player, "Стив", "ударил Борьку (свинья)", -10, 3000 + ConversationStore.WITNESS_COOLDOWN);
        assertEquals(-35, store.card(witness, player).relationship());
        UUID bumped = UUID.randomUUID();
        store.recordDamage(bumped, "minecraft:cow", "Корова", player, "Стив", 1000);
        assertTrue(store.known(bumped) && !store.acquainted(bumped), "a blow in passing makes no acquaintance");
        assertTrue(store.acquainted(witness), "a life story does");
        assertTrue(store.card(witness, player).events().getFirst().contains("ты видел, как он обходился с другими"));
        store.recordWitnessed(stranger, player, "Стив", "ударил Зорьку (корова)", -10, 9000);
        assertFalse(store.known(stranger), "a bystander without a profile gets none");
        assertEquals(0, store.profile(cow(witness, player, store.revision(witness), Map.of())).hits);
        store.recordDamage(witness, "minecraft:cow", "Корова", player, "Стив", 50_000);
        assertEquals(1, store.profile(cow(witness, player, store.revision(witness), Map.of())).hits, "hits travel into the detached profile");
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
        // Events go by importance × freshness (MemoryRanking): the days rotate out first, the blows stay.
        assertEquals(2, store.events(mob).stream().filter(text -> text.contains("ударил")).count());
        assertTrue(store.events(mob).stream().noneMatch(text -> text.contains("Прошёл день 0")));
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
        assertEquals(100, store.card(hostile, player).relationship(), "a hostile species can be won over all the way");
        assertEquals(100, store.relationship(hostile, player));
        assertEquals(0, store.relationship(UUID.randomUUID(), player), "an unknown mob reads as neutral");
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
        // A late reply with an older revision is still remembered (no cancellations on a changed relationship).
        assertTrue(store.commit(cow(mob, player, before.revision(), Map.of()), "Старый вопрос", "Поздний ответ", "", SocialIntent.NONE, ActionIntent.none(), 3000));
        MobCard afterLate = store.card(mob, player);
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        assertEquals(afterLate, store.card(mob, player));
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

    // A favour is the asking mob's own debt and gratitude: another cow neither collects it nor shares the reward, but a fellow
    // villager hears about it as gossip; the promised secret opens for the giver only.
    @Test void favoursBelongToTheMobThatAskedAndNeighboursOnlyHearOfThem() throws Exception {
        UUID giver = UUID.randomUUID(), other = UUID.randomUUID(), player = UUID.randomUUID(), stranger = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(giver, player, 0, Map.of()));
        store.observe(cow(other, player, 0, Map.of()));
        assertNull(store.favor(giver, player));
        assertThrows(IllegalStateException.class, () -> store.completeFavor(giver, player, "Игрок", 50), "nothing owed yet");
        Favor asked = new Favor("minecraft:bell", 1, "Колокол на площадь", "leather", 20, null, 0);
        store.startFavor(giver, player, asked, 1000);
        Favor pending = store.favor(giver, player);
        assertEquals("minecraft:bell", pending.item()); assertEquals(giver, pending.giver()); assertEquals(1000, pending.startedMillis());
        assertNull(store.favor(other, player), "the other cow did not ask for anything");
        assertNull(store.favor(giver, stranger));
        assertThrows(IllegalStateException.class, () -> store.startFavor(giver, player, asked, 1100), "one favour at a time per mob");
        store.startFavor(other, player, new Favor("minecraft:wheat", 64, "", "", 0, null, 0), 1200);
        assertThrows(IllegalArgumentException.class, () -> store.completeFavor(stranger, player, "Игрок", 1300), "no such mob");
        long revision = store.revision(giver);
        Favor done = store.completeFavor(giver, player, "Игрок", 2000);
        assertEquals("Колокол на площадь", done.title());
        assertEquals(revision + 1, store.revision(giver));
        assertEquals(Favor.RELATIONSHIP, store.card(giver, player).relationship());
        assertEquals(0, store.card(other, player).relationship(), "the other cow owes no gratitude");
        assertTrue(store.tradeState(giver, player, "leather").unlocked(), "the promised secret is open with the giver");
        assertFalse(store.tradeState(other, player, "leather").unlocked(), "and only with the giver");
        assertEquals(1, store.tradeState(giver, player, "leather").price(), "2 × 80% = 1, floor is 1");
        assertEquals(List.of("Колокол на площадь"), store.favorsDone(giver, player));
        assertEquals(List.of(), store.favorsDone(other, player));
        assertNull(store.favor(giver, player));
        assertEquals("64× wheat", store.favor(other, player).title(), "a blank title names the goods");
        assertTrue(store.events(giver).stream().anyMatch(e -> e.startsWith("Игрок Игрок выполнил услугу: Колокол на площадь")), store.events(giver).toString());
        assertTrue(store.card(giver, player).events().getFirst().contains("он оказал тебе услуги: Колокол на площадь"));
        assertFalse(store.card(other, player).events().getFirst().contains("оказал"));
        assertThrows(IllegalArgumentException.class, () -> new Favor("minecraft:wheat", 70, "", "", 0, null, 0), "a stack at most");
        // Gossip travels inside a village only.
        assertEquals(List.of(), store.neighbourFavors(giver, player), "cows have no village");
        ConversationStore.Village village = store.createVillage("minecraft:overworld", 0, 0, "Тихие Грядки", "Стоит на пепелище.");
        store.setBackstory(giver, "Марфа", "Ты родилась у реки.", "", "", village.id());
        store.setBackstory(other, "Зорька", "Ты пришла с севера.", "", "", village.id());
        assertEquals(List.of("Марфа уже получил от него: Колокол на площадь"), store.neighbourFavors(other, player));
        assertEquals(List.of("Зорька ждёт от него «64× wheat» (64× minecraft:wheat)"), store.neighbourFavors(giver, player));
        assertEquals(List.of(), store.neighbourFavors(giver, stranger));
        ConversationStore.closeWorld(directory);
        ConversationStore reopened = ConversationStore.open(directory, config);
        try {
            assertEquals("64× wheat", reopened.favor(other, player).title());
            assertEquals(List.of("Колокол на площадь"), reopened.favorsDone(giver, player));
            assertTrue(reopened.tradeState(giver, player, "leather").unlocked());
            assertEquals(1, reopened.neighbourFavors(other, player).size());
        } finally { ConversationStore.closeWorld(directory); }
    }

    // Stories survive reload; villages are found by distance in their own dimension; names are listed per species.
    @Test void keepsStoriesAndVillagesAcrossReload() throws Exception {
        UUID cow = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(cow(cow, player, 0, Map.of()));
        assertFalse(store.hasBackstory(cow));
        assertEquals(List.of(), store.givenNames("cow"));
        long revision = store.revision(cow);
        ConversationStore.Village village = store.createVillage("minecraft:overworld", 10, -20, "Тихие Грядки", "Стоит на пепелище.");
        store.setBackstory(cow, "Марфа", "Ты родилась у реки.", "Хочешь увидеть море.", "Боишься ведра.", village.id());
        assertEquals(revision, store.revision(cow), "a story does not cancel a running answer");
        assertTrue(store.hasBackstory(cow));
        assertEquals(List.of("Марфа"), store.givenNames("minecraft:cow"));
        assertEquals(List.of("Ты родилась у реки."), store.storyOpenings("cow"));
        assertSame(village, store.villageNear("minecraft:overworld", 100, 100));
        assertNull(store.villageNear("minecraft:overworld", 10 + ConversationStore.VILLAGE_RADIUS + 1, -20));
        assertNull(store.villageNear("minecraft:the_end", 10, -20));
        ConversationStore.closeWorld(directory);
        ConversationStore reopened = ConversationStore.open(directory, config);
        try {
            assertTrue(reopened.hasBackstory(cow));
            assertEquals(village, reopened.village(village.id()));
            assertEquals(List.of("Тихие Грядки"), reopened.villageNames());
            assertEquals("Безымянная деревня", reopened.createVillage("minecraft:overworld", 0, 0, " ", "").name());
        } finally { ConversationStore.closeWorld(directory); }
    }

    // Villages by their structure (CTOV and vanilla): neighbours within the radius stay apart, a large village stays whole, an old
    // village without a site takes the site of the structure its anchor lies in, and the site survives reload.
    @Test void villagesFollowTheirStructures() throws Exception {
        String world = "minecraft:overworld";
        ConversationStore store = ConversationStore.open(directory, config);
        ConversationStore.Site big = new ConversationStore.Site("ctov:large/village_plains@0,0", 0, 0, -80, -80, 80, 80);
        ConversationStore.Site next = new ConversationStore.Site("minecraft:village_plains@8,0", 140, 0, 110, -30, 170, 30);
        ConversationStore.Village ctov = store.createVillage(world, big.x(), big.z(), "Большие Луга", "Стоит у дороги.", big.key());
        assertEquals(big.key(), ctov.site());
        assertEquals(ctov, store.villageFor(world, 75, 75, big), "a resident at the far edge of a large village still belongs to it");
        assertNull(store.villageFor(world, 140, 0, next), "the neighbour within 160 blocks is another village");
        assertEquals(ctov, store.villageNear(world, 140, 0), "without a site the old rule still answers");
        ConversationStore.Village vanilla = store.createVillage(world, 140, 0, "Ручеёк", "Маленькая.", next.key());
        assertEquals(vanilla, store.villageFor(world, 120, 10, next));
        assertEquals(ctov, store.villageFor(world, 120, 10, big), "the structure decides, not the nearest anchor");
        ConversationStore.Village old = store.createVillage(world, 1000, 1000, "Старый Затон", "Давняя.");
        assertEquals("", old.site());
        ConversationStore.Site far = new ConversationStore.Site("ctov:small/village_taiga@70,70", 1200, 1200, 1150, 1150, 1250, 1250);
        assertNull(store.villageFor(world, 1150, 1150, far), "an old village outside this structure is not adopted");
        ConversationStore.Site home = new ConversationStore.Site("minecraft:village_taiga@62,62", 1000, 1000, 960, 960, 1040, 1040);
        assertEquals(old.id(), store.villageFor(world, 1010, 990, home).id(), "an old village inside this structure is adopted");
        assertEquals(home.key(), store.village(old.id()).site());
        assertEquals(old.id(), store.villageFor(world, 1000, 1000, null).id(), "no site: as before");
        ConversationStore.closeWorld(directory);
        ConversationStore reopened = ConversationStore.open(directory, config);
        try {
            assertEquals(big.key(), reopened.village(ctov.id()).site());
            assertEquals(home.key(), reopened.village(old.id()).site());
        } finally { ConversationStore.closeWorld(directory); }
        // A save from before sites: villages load with an empty site.
        Path file = directory.resolve("data/mobtalk/conversations.json");
        String saved = Files.readString(file).replaceAll(",?\\s*\"site\"\\s*:\\s*\"[^\"]*\"", "");
        Files.writeString(file, saved);
        ConversationStore legacy = ConversationStore.open(directory, config);
        try { assertEquals("", legacy.village(ctov.id()).site()); } finally { ConversationStore.closeWorld(directory); }
    }

    // The site is bookkeeping: the engine reads it back, the model never sees it.
    @Test void villageSiteStaysOutOfThePrompt() {
        MobSnapshot snapshot = cow(UUID.randomUUID(), UUID.randomUUID(), 0, Map.of("villageSite",
                Map.of("key", "ctov:large/village_plains@3,4", "x", 60, "z", 70, "minX", 0, "minZ", 10, "maxX", 120, "maxZ", 130), "tamed", false));
        ConversationStore.Site site = ConversationEngine.villageSite(snapshot);
        assertEquals(new ConversationStore.Site("ctov:large/village_plains@3,4", 60, 70, 0, 10, 120, 130), site);
        String data = MobPrompt.data(snapshot).toString();
        assertFalse(data.contains("villageSite") || data.contains("ctov:"), data);
        assertTrue(data.contains("tamed"));
        assertNull(ConversationEngine.villageSite(cow(UUID.randomUUID(), UUID.randomUUID(), 0, Map.of())));
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
        assertNull(store.favor(mob, player)); assertEquals(List.of(), store.favorsDone(mob, player));
        assertEquals(TradeState.initial(), store.tradeState(mob, player, "milk"));
        store.setDealPrice(mob, player, "milk", 5, 100);
        assertEquals(5, store.tradeState(mob, player, "milk").price());
        ConversationStore.closeWorld(directory);
        // A hand-edited file with a null deal entry, a 0.3.0 per-mob unlock and the old quest maps must not take the whole world down.
        String saved = Files.readString(file);
        Files.writeString(file, saved.replace("\"deals\": {", "\"deals\": {\"broken\": null, \"leather\": {\"price\": 0, \"unlocked\": true, \"rounds\": 0, \"sessionStartMillis\": -1, \"boughtToday\": 0, \"boughtDay\": -1},")
                .replace("\"relations\": {", "\"quests\": {\"fence\": {\"status\": \"DONE\"}}, \"relations\": {"));
        store = ConversationStore.open(directory, config);
        try {
            assertEquals(new TradeState(5, false, 1, 100, 0, -1), store.tradeStates(mob, player).get("milk"));
            assertTrue(store.tradeState(mob, player, "leather").unlocked(), "an unlock stays with its mob");
            UUID other = UUID.randomUUID();
            store.observe(cow(other, player, 0, Map.of()));
            assertFalse(store.tradeState(other, player, "leather").unlocked());
        } finally { ConversationStore.closeWorld(directory); }
    }
}
