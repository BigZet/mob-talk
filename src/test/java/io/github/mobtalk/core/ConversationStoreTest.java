package io.github.mobtalk.core;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {
    @TempDir Path directory;
    private final MobTalkConfig config = new MobTalkConfig();

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
}
