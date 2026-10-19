package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The social layer through the store and one engine round trip against a local fake provider: the outcomes decided before the
 * request reach the prompt and the schema, the model's gesture is mapped to its decided outcome, and the commit applies it. */
class SocialLayerTest {
    @TempDir Path directory;
    private HttpServer server;
    private URI chat, speech;
    private final AtomicReference<JsonObject> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> gesture = new AtomicReference<>("NONE");
    private final AtomicReference<String> reply = new AtomicReference<>("Ну ты и шутник.");
    private final MobTalkConfig config = new MobTalkConfig();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            lastRequest.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject content = new JsonObject();
            content.addProperty("assessment", "Игрок мирный."); content.addProperty("reply", reply.get());
            content.addProperty("socialIntent", "NONE"); content.addProperty("newFacts", ""); content.addProperty("importance", 1);
            content.addProperty("thought", "Чудак какой-то."); content.addProperty("gesture", gesture.get());
            JsonObject action = new JsonObject(); action.addProperty("type", "NONE"); action.addProperty("target", ""); content.add("action", action);
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", content.toString());
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject response = new JsonObject(); response.add("choices", choices);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/speech", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        chat = URI.create(base + "/chat"); speech = URI.create(base + "/speech");
    }
    @AfterEach void stop() throws Exception { server.stop(0); ConversationStore.closeWorld(directory); }

    static MobSnapshot villager(UUID id, UUID player, long revision, boolean baby, String held) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                held, List.of(), List.of(), player, Map.of("name", "Стив"), Map.of("baby", baby, "position", Map.of("x", 0, "y", 64, "z", 0)),
                Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }

    @Test void villagersGetACharacterAndCowsDoNot() throws Exception {
        ConversationStore store = ConversationStore.open(directory, config);
        UUID villager = UUID.randomUUID(), cow = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore.Profile p = store.profile(villager(villager, player, 0, false, "minecraft:air"));
        assertNotNull(p.social);
        assertNotNull(p.socialTurn);
        assertEquals(List.of("NONE", "JOKE", "COMPLIMENT", "HUG", "FLIRT", "KISS", "DATE", "PROPOSE"), p.socialTurn.schemaValues());
        String prompt = new MobPrompt(config).system(SpeciesCatalog.get("minecraft:villager"), p, villager(villager, player, 0, false, "minecraft:air"));
        assertTrue(prompt.contains("Нрав: " + p.social.persona().word), prompt);
        assertTrue(prompt.contains(SocialPrompt.GESTURE_HEADER));
        ConversationStore.Profile child = store.profile(villager(UUID.randomUUID(), player, 0, true, "minecraft:air"));
        assertFalse(child.socialTurn.schemaValues().contains("FLIRT"));
        assertFalse(child.socialTurn.schemaValues().contains("KISS"));
        assertTrue(child.socialTurn.closed().contains(SocialPrompt.CHILD_ROMANCE));
        MobSnapshot cowSnapshot = new MobSnapshot(cow, "minecraft:cow", "Корова", 10, 10, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                "minecraft:air", List.of(), List.of(), player, Map.of("name", "Стив"), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
        assertNull(store.profile(cowSnapshot).social);
        assertNull(store.profile(cowSnapshot).socialTurn);
        config.socialLayer = false;
        ConversationStore off = ConversationStore.open(directory, config);
        assertNull(off.profile(villager(UUID.randomUUID(), player, 0, false, "minecraft:air")).social);
        config.socialLayer = true;
    }

    // One reply: the schema offers the gestures, the model names JOKE, the commit applies exactly the outcome decided beforehand.
    @Test void theDecidedOutcomeOfTheNamedGestureIsApplied() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        try (ConversationEngine engine = new ConversationEngine(config, directory, new OpenRouterConversationClient("unit-test-key", config, chat),
                new OpenRouterSpeechClient("unit-test-key", config, speech))) {
            engine.memory().observe(villager(mob, player, 0, false, "minecraft:air"));
            engine.memory().setBackstory(mob, "Ладко", "Ты родился в Ольховке.", "", "", "");
            gesture.set("JOKE");
            ConversationEngine.PreparedReply prepared = engine.prepare(villager(mob, player, engine.memory().revision(mob), false, "minecraft:air"), "Знаешь анекдот про крипера?");
            JsonObject schema = lastRequest.get().getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema");
            assertTrue(schema.getAsJsonObject("properties").has("gesture"));
            assertTrue(schema.getAsJsonArray("required").toString().contains("gesture"));
            GestureIntent joke = prepared.gesture();
            assertEquals(Gesture.JOKE, joke.gesture());
            assertTrue(joke.outcome() == Gesture.Outcome.SUCCESS ? joke.hearts() > 0 : joke.hearts() < 0);
            int before = engine.memory().relationship(mob, player);
            assertTrue(engine.commitDelivered(prepared, engine.memory().revision(mob)));
            assertEquals(before + joke.hearts(), engine.memory().relationship(mob, player));
            assertTrue(engine.memory().events(mob).stream().anyMatch(event -> event.contains("пошутил")));
            assertTrue(engine.memory().social().describe(mob, player).contains("усталость 1"));
            // A gesture that was not on offer does nothing.
            gesture.set("GIFT");
            ConversationEngine.PreparedReply gift = engine.prepare(villager(mob, player, engine.memory().revision(mob), false, "minecraft:air"), "Держи!");
            assertFalse(gift.gesture().present());
        }
    }
}
