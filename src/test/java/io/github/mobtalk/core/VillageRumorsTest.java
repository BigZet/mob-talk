package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VillageRumorsTest {
    private static final String VILLAGE = "village-test";
    private final UUID ladko = UUID.randomUUID(), aksinya = UUID.randomUUID(), prokhor = UUID.randomUUID(), trader = UUID.randomUUID();
    private final Map<String, String> ids = Map.of("Ладко", ladko.toString(), "Аксинья", aksinya.toString());
    @TempDir Path directory;
    @BeforeEach void fixture() { TradeFixtures.install(); }
    @AfterEach void restoreBundle() { TradeFixtures.reset(); }

    private static VillageRumors.View rumor(String text, String source, int importance, int sign) { return new VillageRumors.View("", text, source, importance, sign, 0, importance); }

    // The teller knows its own rumour at once; nobody else does until they meet. A village keeps ten, a villager six.
    @Test void addsRumoursAndTheTellerKnowsThemFirst() {
        VillageRumors rumors = new VillageRumors();
        assertEquals(2, rumors.add(VILLAGE, List.of(rumor("игрок Стив ударил корову Марфу", "Ладко", 5, -1), rumor("Стив принёс Аксинье пшеницу", "Аксинья", 4, 1),
                rumor("игрок Стив ударил корову Марфу", "Ладко", 5, -1)), ids, 1000), "an exact repeat is not a second rumour");
        assertEquals(1, rumors.heardBy(ladko, VILLAGE, 1000).size()); assertEquals(1, rumors.heardBy(aksinya, VILLAGE, 1000).size());
        assertTrue(rumors.heardBy(prokhor, VILLAGE, 1000).isEmpty());
        assertEquals("Ладко говорит, что игрок Стив ударил корову Марфу", rumors.heardBy(ladko, VILLAGE, 1000).getFirst().line());
        for (int i = 0; i < 12; i++) rumors.add(VILLAGE, List.of(rumor("слух номер " + i, "Ладко", 3, 0)), ids, 2000 + i);
        assertEquals(VillageRumors.MAX_PER_VILLAGE, rumors.village(VILLAGE, 3000).size(), "ten per village: the lightest go");
        assertTrue(rumors.village(VILLAGE, 3000).stream().anyMatch(v -> v.text().contains("ударил корову")), "the weightiest stays");
        assertEquals(VillageRumors.MAX_PER_VILLAGER, rumors.heardBy(ladko, VILLAGE, 3000).size(), "six per villager");
        assertEquals(Set.of(), Set.copyOf(VillageRumors.lines(List.of())));
        assertEquals(3, VillageRumors.lines(rumors.village(VILLAGE, 3000)).size(), "the prompt reads three at most");
    }

    // Standing together: a rumour crosses with a chance from its importance, higher between family and friends; never twice.
    @Test void spreadsBetweenNeighboursByChance() {
        VillageRumors rumors = new VillageRumors();
        rumors.add(VILLAGE, List.of(rumor("Стив убил Прохора", "Ладко", 5, -1), rumor("Стив купил у Ладко хлеб", "Ладко", 2, 1)), ids, 0);
        assertEquals(0.45, VillageRumors.chance(5, false), 1e-9); assertEquals(0.72, VillageRumors.chance(5, true), 1e-9);
        assertEquals(0.18, VillageRumors.chance(2, false), 1e-9); assertTrue(VillageRumors.chance(5, true) <= VillageRumors.MAX_CHANCE);
        assertEquals(0, rumors.spread(VILLAGE, ladko, aksinya, false, () -> 0.99, 1000), "bad luck: nothing crosses");
        assertEquals(1, rumors.spread(VILLAGE, ladko, aksinya, false, () -> 0.3, 1000), "the killing crosses, the bread does not (0.3 ≥ 0.18)");
        assertEquals("Стив убил Прохора", rumors.heardBy(aksinya, VILLAGE, 1000).getFirst().text());
        assertEquals(1, rumors.spread(VILLAGE, ladko, aksinya, true, () -> 0.2, 2000), "close: the bread crosses too (0.2 < 0.288)");
        assertEquals(0, rumors.spread(VILLAGE, aksinya, ladko, true, () -> 0.0, 3000), "both know everything: nothing left to tell");
        assertEquals(0, rumors.spread("village-other", ladko, aksinya, true, () -> 0.0, 3000), "another village has no chronicle");
        assertEquals(0, rumors.spread(VILLAGE, prokhor, UUID.randomUUID(), true, () -> 0.0, 3000), "two villagers that heard nothing have nothing to tell");
        assertEquals(2, rumors.spread(VILLAGE, prokhor, aksinya, true, () -> 0.0, 3000), "meeting goes both ways: Аксинья tells Прохор");
    }

    // A rumour weighs its importance when born, half of it after two and a half days, nothing on the fifth, when it is forgotten.
    @Test void gameNewsLivesOneDayAndEveryHearerKnowsIt() {
        VillageRumors rumors = new VillageRumors();
        long now = 1_000_000;
        rumors.news(VILLAGE, "cage-x-in", "стример посадил в клетку попугая зрителя Кеша", 3, VillageRumors.DAY_MILLIS, List.of(ladko, aksinya), now);
        assertEquals(1, rumors.heardBy(ladko, VILLAGE, now).size());
        assertEquals(1, rumors.heardBy(aksinya, VILLAGE, now).size());
        assertNotNull(rumors.untold(VILLAGE, ladko, prokhor, now), "small talk can pass the news on");
        assertTrue(rumors.village(VILLAGE, now + VillageRumors.DAY_MILLIS / 2).getFirst().weight() < 3);
        assertTrue(rumors.village(VILLAGE, now + VillageRumors.DAY_MILLIS).isEmpty(), "forgotten after one game day, not five");
        rumors.news(VILLAGE, "cage-x-in", "снова", 3, VillageRumors.DAY_MILLIS, List.of(), now);
        rumors.dropNews("cage-x-");
        assertTrue(rumors.village(VILLAGE, now).isEmpty(), "the release drops the jail news");
    }

    @Test void rumoursLoseWeightAndAreForgotten() {
        VillageRumors rumors = new VillageRumors();
        rumors.add(VILLAGE, List.of(rumor("Стив сломал колокол", "Ладко", 4, -1)), ids, 0);
        long halfway = VillageRumors.DAY_MILLIS * VillageRumors.FORGET_DAYS / 2;
        assertEquals(4, rumors.village(VILLAGE, 0).getFirst().weight(), 1e-9);
        assertEquals(2, rumors.village(VILLAGE, halfway).getFirst().weight(), 1e-9);
        assertTrue(rumors.village(VILLAGE, VillageRumors.DAY_MILLIS * VillageRumors.FORGET_DAYS).isEmpty(), "forgotten on the fifth day");
        assertTrue(rumors.heardBy(ladko, VILLAGE, VillageRumors.DAY_MILLIS * VillageRumors.FORGET_DAYS).isEmpty());
        rumors.add(VILLAGE, List.of(rumor("новый слух", "Ладко", 2, 0)), ids, VillageRumors.DAY_MILLIS * VillageRumors.FORGET_DAYS + 1);
        assertEquals(1, rumors.village(VILLAGE, VillageRumors.DAY_MILLIS * VillageRumors.FORGET_DAYS + 1).size(), "the old one was pruned on the add");
        // The chronicle is due on a new day, or when importance piles up; never without a fact to tell.
        assertTrue(rumors.due("village-new", 3, 0, true, 0), "no chronicle yet");
        assertFalse(rumors.due("village-new", 3, 100, false, 0), "nothing happened");
        rumors.started(VILLAGE, 3, 1000);
        assertFalse(rumors.due(VILLAGE, 3, 5, true, 2000)); assertTrue(rumors.due(VILLAGE, 4, 5, true, 2000), "a new day");
        assertTrue(rumors.due(VILLAGE, 3, VillageRumors.IMPORTANCE_THRESHOLD, true, 2000), "enough happened");
        assertEquals(1000, rumors.since(VILLAGE));
    }

    // A wandering trader takes the three weightiest rumours along and tells them in the next village, as its own.
    @Test void traderCarriesRumoursToTheNextVillage() {
        VillageRumors rumors = new VillageRumors();
        rumors.add(VILLAGE, List.of(rumor("Стив убил Прохора", "Ладко", 5, -1), rumor("Стив купил хлеб", "Ладко", 2, 1), rumor("Стив починил загон", "Аксинья", 4, 1),
                rumor("Стив топтал грядки", "Ладко", 3, -1)), ids, 0);
        assertEquals(3, rumors.carry(trader, VILLAGE, 100));
        assertEquals(0, rumors.carry(trader, VILLAGE, 200), "already full, same village");
        List<VillageRumors.View> bag = rumors.carriedBy(trader, 200);
        assertEquals(List.of("Стив убил Прохора", "Стив починил загон", "Стив топтал грядки"), bag.stream().map(VillageRumors.View::text).toList());
        assertEquals(0, rumors.deliver(trader, VILLAGE, prokhor, 300), "not back where it came from");
        UUID stranger = UUID.randomUUID();
        assertEquals(3, rumors.deliver(trader, "village-far", stranger, 400));
        assertTrue(rumors.carriedBy(trader, 400).isEmpty(), "the bag is empty");
        List<VillageRumors.View> far = rumors.village("village-far", 400);
        assertEquals(3, far.size()); assertTrue(far.stream().allMatch(v -> v.source().equals(VillageRumors.TRADER_SOURCE)), far.toString());
        assertEquals(3, rumors.heardBy(stranger, "village-far", 400).size(), "the villager it stood with heard them");
        assertTrue(rumors.heardBy(stranger, "village-far", 400).getFirst().line().startsWith(VillageRumors.TRADER_SOURCE + " говорит"));
        assertEquals(3, rumors.carry(trader, "village-far", 500), "and takes the far village's talk along to the next one: " + rumors.carriedBy(trader, 500));
        assertEquals(0, rumors.deliver(trader, "village-far", stranger, 600), "not back into the village it came from");
    }

    // A newcomer picks up the loudest rumours; the village's opinion of the player is the balance of signs by weight.
    @Test void newcomersKnowTheTalkAndTheVillageHasAnOpinion() {
        VillageRumors rumors = new VillageRumors();
        assertEquals(VillageRumors.Reputation.NONE, rumors.reputation(VILLAGE, 0));
        rumors.add(VILLAGE, List.of(rumor("Стив убил Прохора", "Ладко", 5, -1), rumor("Стив купил хлеб", "Ладко", 2, 1), rumor("Стив починил загон", "Аксинья", 4, 1),
                rumor("Стив топтал грядки", "Ладко", 3, -1)), ids, 0);
        List<VillageRumors.View> known = rumors.newcomer(prokhor, VILLAGE, 0);
        assertEquals(List.of("Стив убил Прохора", "Стив починил загон", "Стив топтал грядки"), known.stream().map(VillageRumors.View::text).toList());
        VillageRumors.Reputation fame = rumors.reputation(VILLAGE, 0);
        assertEquals((-5 + 2 + 4 - 3) / 14.0, fame.balance(), 1e-9); assertEquals(14, fame.weight(), 1e-9);
        assertTrue(fame.known() && fame.describe().startsWith("По слухам в деревне о нём идёт смешанная слава, и о нём говорят много"), fame.describe());
        VillageRumors bad = new VillageRumors();
        bad.add(VILLAGE, List.of(rumor("Стив убил Прохора", "Ладко", 5, -1)), ids, 0);
        assertTrue(bad.reputation(VILLAGE, 0).describe().startsWith("По слухам в деревне о нём идёт дурная слава, но говорят о нём мало"));
        assertEquals("Ладко: Стив убил Прохора (5−)", bad.village(VILLAGE, 0).getFirst().label());
    }

    // Through the world memory: the chronicle's input from the villagers' events, the request against a local provider, the rumours in a
    // newcomer's prompt with the player's fame, and everything back from disk.
    @Test void chronicleFromEventsToPromptAndDisk() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject content = new JsonObject(); JsonArray rumors = new JsonArray();
            JsonObject first = new JsonObject(); first.addProperty("text", "Стив ударил Аксинью прямо у колодца"); first.addProperty("source", "Аксинья"); first.addProperty("importance", 5); first.addProperty("sign", -1); rumors.add(first);
            JsonObject second = new JsonObject(); second.addProperty("text", "Стив купил у кого-то хлеб"); second.addProperty("source", "Никто"); second.addProperty("importance", 2); second.addProperty("sign", 1); rumors.add(second);
            JsonObject blank = new JsonObject(); blank.addProperty("text", "…"); blank.addProperty("source", "Аксинья"); blank.addProperty("importance", 1); blank.addProperty("sign", 0); rumors.add(blank);
            content.add("rumors", rumors);
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", content.toString());
            JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject response = new JsonObject(); response.add("choices", choices);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            MobTalkConfig config = new MobTalkConfig(); config.backgroundModel = "test/cheap";
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConversationStore store = ConversationStore.open(directory, config);
            ConversationStore.Village village = store.createVillage("minecraft:overworld", 100, 100, "Ольховка", "Деревня у реки.");
            UUID player = UUID.randomUUID();
            store.observe(villager(aksinya, player, 102, 100)); store.setBackstory(aksinya, "Аксинья", "Ты родилась здесь.", "", "", village.id());
            store.observe(villager(ladko, player, 104, 100)); store.setBackstory(ladko, "Ладко", "Ты пришёл сюда.", "", "", village.id());
            assertNull(store.chronicleDue(village.id(), "Стив", 1, 1000), "nothing happened yet");
            store.recordDamage(aksinya, "minecraft:villager", "Житель", player, "Стив", 2000);
            VillageRumors.Input input = store.chronicleDue(village.id(), "Стив", 1, 3000);
            assertNotNull(input); assertEquals(List.of("Аксинья", "Ладко"), input.villagers());
            assertEquals(1, input.facts().size()); assertEquals("Аксинья", input.facts().getFirst().who()); assertEquals(5, input.importance());
            assertNull(store.chronicleDue(village.id(), "Стив", 1, 4000), "started: the same facts are not told twice");
            assertNull(store.chronicleDue(village.id(), "Стив", 2, 5000), "a new day with nothing new");
            ConversationEngine engine = new ConversationEngine(config, directory, new OpenRouterConversationClient("unit-test-key", config, URI.create(base + "/chat")),
                    new OpenRouterSpeechClient("unit-test-key", config, URI.create(base + "/speech")));
            // The engine checks and queues; the facts since the last (started) chronicle are the blow again, as a new day began.
            store.recordDamage(aksinya, "minecraft:villager", "Житель", player, "Стив", 6000);
            engine.chronicleIfDue(village.id(), "Стив", 2);
            engine.awaitBackground(5000);
            assertNotNull(request.get(), "one background request");
            assertEquals("test/cheap", request.get().get("model").getAsString());
            assertEquals("village_chronicle", request.get().getAsJsonObject("response_format").getAsJsonObject("json_schema").get("name").getAsString());
            assertTrue(request.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("Аксинья, Ладко"));
            long now = System.currentTimeMillis();
            List<VillageRumors.View> told = store.rumorsOf(aksinya, now);
            assertEquals(2, told.size(), "the teller knows her own; the unknown teller's rumour went to the first villager too: " + told);
            assertEquals("Стив ударил Аксинью прямо у колодца", told.getFirst().text());
            assertTrue(store.rumorsOf(ladko, now).isEmpty(), "Ладко told nothing and has met nobody since");
            // A villager nobody talked to, standing in the village, knows the talk and meets the player by his fame.
            ConversationStore.Profile newcomer = store.profile(villager(prokhor, player, 110, 100));
            assertEquals(2, newcomer.rumors.size(), newcomer.rumors.toString());
            assertTrue(newcomer.repute.known() && newcomer.repute.balance() < 0, String.valueOf(newcomer.repute));
            String system = new MobPrompt(config).system(SpeciesCatalog.get("minecraft:villager"), newcomer, villager(prokhor, player, 110, 100));
            assertTrue(system.contains(PromptTexts.RUMOR_HEADER) && system.contains("— Аксинья говорит, что Стив ударил Аксинью прямо у колодца") && system.contains(PromptTexts.RUMOR_RULE), system);
            assertTrue(system.contains("дурная слава"), system);
            engine.close();
            ConversationStore.closeWorld(directory);
            store = ConversationStore.open(directory, config);
            assertEquals(2, store.rumorsOf(prokhor, now).size(), "the newcomer's knowledge survived the disk");
            assertEquals(2, store.rumorsOf(aksinya, now).size());
            assertTrue(store.forgetStranger(prokhor)); assertTrue(store.rumorsOf(prokhor, now).isEmpty());
            ConversationStore.closeWorld(directory);
        } finally { server.stop(0); }
    }

    private static MobSnapshot villager(UUID id, UUID player, int x, int z) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                "minecraft:stick", List.of(), List.of(), player, Map.of("name", "Стив"), Map.of("position", Map.of("x", x, "y", 64, "z", z)), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }
}
