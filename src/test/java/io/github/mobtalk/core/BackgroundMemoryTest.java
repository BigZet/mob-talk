package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Summaries at the end of a conversation, importance on facts, reflections in the background — against a local provider. */
class BackgroundMemoryTest {
    @TempDir Path directory;
    private HttpServer server;
    private URI chatEndpoint, speechEndpoint;
    private final MobTalkConfig config = new MobTalkConfig();
    private final AtomicReference<JsonObject> lastReplyRequest = new AtomicReference<>();
    private final List<JsonObject> backgroundRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger backgroundStatus = new AtomicInteger(200);
    private final AtomicReference<String> newFacts = new AtomicReference<>("");
    private final AtomicInteger importance = new AtomicInteger(2);
    private final AtomicBoolean reflectionWasEmpty = new AtomicBoolean();

    @BeforeEach void startProvider() throws IOException {
        SpeciesCatalog.Override cowVoices = new SpeciesCatalog.Override();
        cowVoices.voiceIds = new String[]{"LocalCowFixtureVoice0001", "LocalCowFixtureVoice0002", "LocalCowFixtureVoice0003"};
        config.speciesOverrides.put("minecraft:cow", cowVoices);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String schema = request.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("name").getAsString();
            JsonObject content = new JsonObject();
            int status = 200;
            switch (schema) {
                case "conversation_summary" -> { backgroundRequests.add(request); status = backgroundStatus.get(); content.addProperty("summary", "Мы говорили о пшенице, и он обещал принести семена; мне это по душе."); }
                case "player_reflection" -> {
                    backgroundRequests.add(request); status = backgroundStatus.get();
                    content.addProperty("first", reflectionWasEmpty.get() ? "" : "Он всегда платит."); content.addProperty("second", "");
                }
                default -> {
                    lastReplyRequest.set(request);
                    content.addProperty("assessment", "спокойно"); content.addProperty("reply", "Му. Слушаю тебя.");
                    content.addProperty("socialIntent", "NONE");
                    JsonObject action = new JsonObject(); action.addProperty("type", "NONE"); action.addProperty("target", ""); content.add("action", action);
                    JsonObject bargain = new JsonObject(); bargain.addProperty("type", "NONE"); bargain.addProperty("offerIndex", -1); bargain.addProperty("price", 0); content.add("bargain", bargain);
                    content.addProperty("newFacts", newFacts.get()); content.addProperty("importance", importance.get()); content.addProperty("thought", "");
                }
            }
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", content.toString());
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject response = new JsonObject(); response.add("choices", choices);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/speech", exchange -> {
            byte[] body = new byte[]{0, 0, 1, 0};
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        chatEndpoint = URI.create(base + "/chat"); speechEndpoint = URI.create(base + "/speech");
    }

    @AfterEach void stopProvider() throws Exception { BackgroundTasks.awaitIdle(10_000); server.stop(0); ConversationStore.closeWorld(directory); }

    private ConversationEngine engine() throws IOException {
        return new ConversationEngine(config, directory, new OpenRouterConversationClient("unit-test-key", config, chatEndpoint),
                new OpenRouterSpeechClient("unit-test-key", config, speechEndpoint));
    }

    private MobSnapshot cow(UUID mob, UUID player, long revision) {
        return new MobSnapshot(mob, "minecraft:cow", "Бурёнка", 10, 10, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                "minecraft:stick", List.of(), List.of(), player, Map.of("name", "Стив"), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }

    private void talk(ConversationEngine engine, UUID mob, UUID player, String... questions) throws Exception {
        for (String question : questions) engine.converse(cow(mob, player, engine.memory().revision(mob)), question);
    }

    private String systemPrompt() { return lastReplyRequest.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString(); }

    // The next conversation reads the summary in the system prompt instead of the old exchanges, and the request was the cheap kind.
    @Test void summarisesAnEndedConversationAndReplacesItsTailInTheNextPrompt() throws Exception {
        config.backgroundModel = "cheap/model"; config.reasoningEffort = "high";
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        try (ConversationEngine engine = engine()) {
            talk(engine, mob, player, "Привет, как пшеница?", "Принесу семена завтра", "Ну, бывай");
            assertEquals(6, lastReplyRequest.get().getAsJsonArray("messages").size(), "the tail of the current conversation is in the prompt");
            engine.endConversation(mob, player, "Стив");
            engine.awaitBackground(10_000);
            List<ConversationSummary.Summary> summaries = engine.memory().summaries(mob);
            assertEquals(1, summaries.size());
            assertEquals(3, summaries.getFirst().turns());
            assertTrue(summaries.getFirst().text().startsWith("Мы говорили о пшенице"));
            JsonObject request = backgroundRequests.getFirst();
            assertEquals("cheap/model", request.get("model").getAsString());
            assertEquals("minimal", request.getAsJsonObject("reasoning").get("effort").getAsString());
            assertEquals(400, request.get("max_tokens").getAsInt());
            assertFalse(request.has("stream"));
            String transcript = request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(transcript.contains("Игрок: Принесу семена завтра") && transcript.contains("Я: Му. Слушаю тебя."), transcript);
            assertTrue(request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("с игроком Стив"));
            // Nothing more to summarise: the same call again queues no request.
            engine.endConversation(mob, player, "Стив");
            engine.awaitBackground(10_000);
            assertEquals(1, backgroundRequests.size());
            talk(engine, mob, player, "Я вернулся");
            assertEquals(2, lastReplyRequest.get().getAsJsonArray("messages").size(), "only the system prompt and the new question: the old exchanges are summarised");
            assertTrue(systemPrompt().contains("Наши прошлые разговоры") && systemPrompt().contains("(последний разговор) Мы говорили о пшенице"), systemPrompt());
            talk(engine, mob, player, "И ещё вопрос");
            assertEquals(4, lastReplyRequest.get().getAsJsonArray("messages").size(), "the current conversation's own tail stays");
            engine.memory().flush();
        }
        ConversationStore.closeWorld(directory);
        try (ConversationEngine reopened = engine()) {
            assertEquals(1, reopened.memory().summaries(mob).size(), "summaries survive a reload");
            talk(reopened, mob, player, "Снова я");
            assertEquals(6, lastReplyRequest.get().getAsJsonArray("messages").size(), "two unsummarised exchanges plus the new question");
        }
    }

    @Test void aFailedSummaryChangesNothing() throws Exception {
        backgroundStatus.set(500);
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        try (ConversationEngine engine = engine()) {
            talk(engine, mob, player, "Привет", "Как дела?");
            engine.endConversation(mob, player, "Стив");
            engine.awaitBackground(10_000);
            assertEquals(1, backgroundRequests.size());
            assertTrue(engine.memory().summaries(mob).isEmpty());
            talk(engine, mob, player, "Ещё раз");
            assertEquals(6, lastReplyRequest.get().getAsJsonArray("messages").size(), "the tail stays in the prompt as before");
            assertFalse(systemPrompt().contains("Наши прошлые разговоры"));
        }
    }

    @Test void aSingleExchangeIsNotWorthASummary() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        try (ConversationEngine engine = engine()) {
            talk(engine, mob, player, "Привет");
            engine.endConversation(mob, player, "Стив");
            engine.awaitBackground(10_000);
            assertTrue(backgroundRequests.isEmpty());
            assertTrue(engine.memory().summaries(mob).isEmpty());
        }
    }

    // Facts carry the model's importance; after enough replies one reflection is requested and read in the prompt as its own line.
    @Test void keepsFactImportanceAndReflectsOnceEnoughHasHappened() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        try (ConversationEngine engine = engine()) {
            newFacts.set("Игрок назвался Стивом и заплатил вперёд."); importance.set(4);
            talk(engine, mob, player, "Я Стив, вот плата");
            List<MemoryFact> facts = engine.memory().facts(mob);
            assertEquals(1, facts.size());
            assertEquals(4, facts.getFirst().importance());
            assertFalse(facts.getFirst().reflection());
            assertEquals("Игрок назвался Стивом и заплатил вперёд.", engine.memory().card(mob, player).memory());
            newFacts.set(""); importance.set(1);
            talk(engine, mob, player, "Как погода?", "А урожай?");
            engine.awaitBackground(10_000);
            assertTrue(backgroundRequests.isEmpty(), "three replies: too early to reflect");
            talk(engine, mob, player, "А соседи?");
            engine.awaitBackground(10_000);
            assertEquals(1, backgroundRequests.size(), "the fourth reply crossed the threshold (4 + 2 + 2 + 2 > 8)");
            JsonObject request = backgroundRequests.getFirst();
            assertEquals("player_reflection", request.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("name").getAsString());
            String memoryJson = request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(memoryJson.contains("Игрок назвался Стивом") && memoryJson.contains("\"importance\":4"), memoryJson);
            facts = engine.memory().facts(mob);
            assertEquals(2, facts.size());
            MemoryFact conclusion = facts.getLast();
            assertTrue(conclusion.reflection());
            assertEquals(MemoryFact.REFLECTION, conclusion.importance());
            assertEquals("Он всегда платит.", conclusion.text());
            assertTrue(engine.memory().card(mob, player).memory().endsWith("[вывод] Он всегда платит."));
            talk(engine, mob, player, "Ну ладно");
            assertEquals(2, engine.memory().facts(mob).size(), "a reply without new facts leaves the conclusion in place");
            talk(engine, mob, player, "Что скажешь?");
            assertTrue(systemPrompt().contains("Что я понял об этом игроке: Он всегда платит."), systemPrompt());
            assertFalse(systemPrompt().contains("— Он всегда платит."), "a conclusion is not listed among the facts");
            engine.awaitBackground(10_000);
            assertEquals(1, backgroundRequests.size(), "the counters restarted: no second reflection right away");
            engine.memory().flush();
        }
        ConversationStore.closeWorld(directory);
        try (ConversationEngine reopened = engine()) {
            List<MemoryFact> facts = reopened.memory().facts(mob);
            assertEquals(2, facts.size());
            assertEquals(4, facts.getFirst().importance());
            assertTrue(facts.getLast().reflection());
        }
    }

    @Test void validatorClampsImportanceAndDefaultsToOrdinary() {
        assertEquals(5, ReplySchema.importance(JsonParser.parseString("{\"importance\":9}").getAsJsonObject()));
        assertEquals(1, ReplySchema.importance(JsonParser.parseString("{\"importance\":-3}").getAsJsonObject()));
        assertEquals(3, ReplySchema.importance(JsonParser.parseString("{\"importance\":3}").getAsJsonObject()));
        assertEquals(MemoryFact.ORDINARY, ReplySchema.importance(JsonParser.parseString("{}").getAsJsonObject()));
        assertEquals(MemoryFact.ORDINARY, ReplySchema.importance(JsonParser.parseString("{\"importance\":\"пять\"}").getAsJsonObject()));
        JsonObject schema = ReplySchema.replyFormat(cow(UUID.randomUUID(), UUID.randomUUID(), 0)).getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertEquals("integer", schema.getAsJsonObject("properties").getAsJsonObject("importance").get("type").getAsString());
        assertTrue(schema.getAsJsonArray("required").toString().contains("\"importance\""));
    }

    // A file written before importance existed: memory lines become facts of importance 3, events get 3, nothing is lost.
    @Test void migratesOldMemoryTextAndEvents() throws Exception {
        UUID mob = UUID.randomUUID();
        Path file = WorldMemoryFile.of(directory);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":2,\"profiles\":{\"" + mob + "\":{\"type\":\"minecraft:cow\",\"variantSlot\":0,\"name\":\"Старая\",\"personality\":\"Тихая\","
                + "\"voiceId\":\"LocalCowFixtureVoice0001\",\"memory\":\"Игрок назвался Стивом.\\nПринёс мне яблоко.\",\"turns\":[{\"user\":\"привет\",\"assistant\":\"му\"}],"
                + "\"events\":[{\"time\":1000,\"text\":\"Игрок ударил моба\"}]}}}");
        ConversationStore store = ConversationStore.open(directory, config);
        List<MemoryFact> facts = store.facts(mob);
        assertEquals(List.of("Игрок назвался Стивом.", "Принёс мне яблоко."), facts.stream().map(MemoryFact::text).toList());
        assertTrue(facts.stream().allMatch(f -> f.importance() == MemoryFact.MIGRATED && !f.reflection()));
        assertEquals("Игрок назвался Стивом.\nПринёс мне яблоко.", store.card(mob, null).memory());
        assertEquals(List.of("Игрок ударил моба"), store.events(mob));
        assertTrue(store.summaries(mob).isEmpty());
        store.flush();
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("profiles").getAsJsonObject(mob.toString());
        assertEquals(3, saved.getAsJsonArray("events").get(0).getAsJsonObject().get("importance").getAsInt());
        assertEquals(2, saved.getAsJsonArray("facts").size());
        ConversationStore.closeWorld(directory);
    }

    // Editing the memory in the card keeps the importance of the lines kept and forgets the rest.
    @Test void editedMemoryKeepsKnownImportance() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, config);
        MobSnapshot snapshot = cow(mob, player, 0);
        store.observe(snapshot);
        assertTrue(store.commit(cow(mob, player, store.revision(mob)), "вопрос", "ответ", "", SocialIntent.NONE, ActionIntent.none(), 1000, null, "", MemoryFact.of("Ударил меня палкой.", 5, 1000)));
        assertTrue(store.commit(cow(mob, player, store.revision(mob)), "вопрос", "ответ", "", SocialIntent.NONE, ActionIntent.none(), 2000, null, "", MemoryFact.of("Спросил про погоду.", 2, 2000)));
        store.updateCard(mob, null, null, null, "Ударил меня палкой.\nНовая заметка игрока");
        List<MemoryFact> facts = store.facts(mob);
        assertEquals(List.of("Ударил меня палкой.", "Новая заметка игрока"), facts.stream().map(MemoryFact::text).toList());
        assertEquals(5, facts.getFirst().importance());
        assertEquals(MemoryFact.MIGRATED, facts.getLast().importance());
        ConversationStore.closeWorld(directory);
    }
}
