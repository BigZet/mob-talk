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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConversationEngineTest {
    @TempDir Path directory;
    private HttpServer server;
    private URI chatEndpoint;
    private URI speechEndpoint;
    private final AtomicReference<JsonObject> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> reply = new AtomicReference<>("Я мирно пасусь здесь.");
    private final AtomicReference<String> summary = new AtomicReference<>("Игрок спросил о жизни на лугу.");
    private final AtomicReference<String> rawChat = new AtomicReference<>();
    private final AtomicInteger chatStatus = new AtomicInteger(200);
    private final AtomicInteger speechStatus = new AtomicInteger(200);
    private final AtomicReference<byte[]> speechBytes = new AtomicReference<>(new byte[] { 0, 0, 1, 0 });
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final MobTalkConfig config = new MobTalkConfig();

    @BeforeEach void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            chatCalls.incrementAndGet();
            lastRequest.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject content = new JsonObject();
            content.addProperty("reply", reply.get());
            content.addProperty("memory", summary.get());
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", content.toString());
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject response = new JsonObject();
            response.add("choices", choices);
            byte[] body = (rawChat.get() == null ? response.toString() : rawChat.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(chatStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/speech", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = speechStatus.get() == 200 ? speechBytes.get() : "secret-provider-detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(speechStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        chatEndpoint = URI.create(base + "/chat");
        speechEndpoint = URI.create(base + "/speech");
    }

    @AfterEach void stopProvider() { server.stop(0); }

    private ConversationEngine engine() throws IOException {
        return new ConversationEngine(config, directory,
                new OpenRouterConversationClient("unit-test-key", config, chatEndpoint),
                new OpenRouterSpeechClient("unit-test-key", config, speechEndpoint));
    }

    private MobSnapshot cow(UUID id) {
        return new MobSnapshot(id, "minecraft:cow", "Бурёнка", 7, 10,
                "minecraft:overworld", "minecraft:meadow", "ночь", "дождь", 3.5,
                "minecraft:stick", List.of("minecraft:sheep"), List.of("Игрок подошёл к мобу"));
    }

    // Removing the UUID key, trimming, or reload would mix or lose persistent conversations.
    @Test void keepsSixExchangesAndReloadsIndividualIdentityWithoutMixingMobs() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String assignedVoice;
        summary.set("п".repeat(1500));
        try (ConversationEngine engine = engine()) {
            assignedVoice = engine.converse(cow(first), "секрет первого моба").voiceId();
            for (int i = 1; i < 8; i++) engine.converse(cow(first), "вопрос " + i);
            engine.converse(cow(second), "совсем другой вопрос");
            assertFalse(lastRequest.get().toString().contains("секрет первого моба"));
        }
        JsonObject profiles = JsonParser.parseString(Files.readString(directory.resolve("data/mobtalk/conversations.json")))
                .getAsJsonObject().getAsJsonObject("profiles");
        assertEquals(6, profiles.getAsJsonObject(first.toString()).getAsJsonArray("turns").size());
        assertEquals(1, profiles.getAsJsonObject(second.toString()).getAsJsonArray("turns").size());
        assertEquals(1200, profiles.getAsJsonObject(first.toString()).get("memory").getAsString().length());
        String personality = profiles.getAsJsonObject(first.toString()).get("personality").getAsString();
        try (ConversationEngine reopened = engine()) {
            assertEquals(assignedVoice, reopened.converse(cow(first), "ты помнишь меня?").voiceId());
            assertTrue(lastRequest.get().toString().contains("вопрос 7"));
            assertTrue(lastRequest.get().toString().contains(personality));
            assertFalse(lastRequest.get().toString().contains("совсем другой вопрос"));
        }
    }

    // Omitting a snapshot fact or structured response format would leave the LLM ungrounded.
    @Test void suppliesObservedWorldFactsAndRequestsStructuredReply() throws Exception {
        try (ConversationEngine engine = engine()) {
            ConversationEngine.Reply result = engine.converse(cow(UUID.randomUUID()), "Как тебе здесь?");
            assertEquals("Как тебе здесь?", result.recognizedText());
            assertEquals("Бурёнка", result.speakerName());
            assertArrayEquals(new byte[] { 0, 0, 1, 0 }, result.pcm());
            JsonObject request = lastRequest.get();
            assertEquals("json_schema", request.getAsJsonObject("response_format").get("type").getAsString());
            String prompt = request.getAsJsonArray("messages").toString();
            for (String fact : List.of("minecraft:cow", "minecraft:meadow", "ночь", "дождь", "3.5",
                    "minecraft:stick", "minecraft:sheep", "Игрок подошёл к мобу", "7.0", "10.0")) {
                assertTrue(prompt.contains(fact), "Missing observed fact: " + fact);
            }
        }
    }

    // Passing provider text straight through would let speech exceed the requested sentence/word bound.
    @Test void boundsReplyToTwoSentencesAndFortyFiveWords() throws Exception {
        reply.set("Один. Два. Три лишних предложения.");
        try (ConversationEngine engine = engine()) {
            assertEquals("Один. Два.", engine.converse(cow(UUID.randomUUID()), "Привет").responseText());
            reply.set("слово ".repeat(60));
            String text = engine.converse(cow(UUID.randomUUID()), "Привет ещё раз").responseText();
            assertEquals(45, text.split("\\s+").length);
        }
    }

    // Treating malformed/provider errors as replies could save diagnostic bodies as dialogue.
    @Test void rejectsMalformedAndFailedProviderWithoutLeakingBody() throws Exception {
        try (ConversationEngine engine = engine()) {
            rawChat.set("{\"choices\":[]}");
            IOException malformed = assertThrows(IOException.class, () -> engine.converse(cow(UUID.randomUUID()), "Привет"));
            assertFalse(malformed.toString().contains("choices"));
            chatStatus.set(401);
            rawChat.set("secret-provider-detail unit-test-key");
            IOException denied = assertThrows(IOException.class, () -> engine.converse(cow(UUID.randomUUID()), "Привет"));
            assertTrue(denied.getMessage().contains("401"));
            assertFalse(denied.toString().contains("secret-provider-detail"));
            assertFalse(denied.toString().contains("unit-test-key"));
        }
    }

    // Throwing away a good LLM response when audio fails would lose subtitles and history.
    @Test void preservesDialogueAndMemoryWhenSpeechProviderFails() throws Exception {
        UUID id = UUID.randomUUID();
        speechStatus.set(503);
        try (ConversationEngine engine = engine()) {
            ConversationEngine.Reply result = engine.converse(cow(id), "Привет");
            assertEquals("Я мирно пасусь здесь.", result.responseText());
            assertEquals(0, result.pcm().length);
        }
        JsonObject profile = JsonParser.parseString(Files.readString(directory.resolve("data/mobtalk/conversations.json")))
                .getAsJsonObject().getAsJsonObject("profiles").getAsJsonObject(id.toString());
        assertEquals("Привет", profile.getAsJsonArray("turns").get(0).getAsJsonObject().get("user").getAsString());
    }

    // Sending empty transcript text would incur a paid request for silence.
    @Test void silenceDoesNotCallProvider() throws Exception {
        try (ConversationEngine engine = engine()) {
            assertThrows(IOException.class, () -> engine.converse(cow(UUID.randomUUID()), " \n "));
            assertEquals(0, chatCalls.get());
        }
    }

    // Reassigning a voice after a config change would change this individual across world reloads.
    @Test void keepsAssignedVoiceAfterDefaultVoiceChanges() throws Exception {
        UUID id = UUID.randomUUID();
        MobSnapshot creeper = new MobSnapshot(id, "minecraft:creeper", "Крипер", 20, 20,
                "minecraft:overworld", "minecraft:plains", "день", "ясно", 2,
                "minecraft:stick", List.of(), List.of());
        config.defaultVoice = "first-voice";
        try (ConversationEngine engine = engine()) {
            assertEquals("first-voice", engine.converse(creeper, "Привет").voiceId());
        }
        config.defaultVoice = "new-voice";
        try (ConversationEngine reopened = engine()) {
            assertEquals("first-voice", reopened.converse(creeper, "И снова привет").voiceId());
        }
    }

    // Accepting odd or oversized PCM would feed malformed or unbounded audio into the game player.
    @Test void rejectsInvalidAndOversizedPcmWhilePreservingText() throws Exception {
        try (ConversationEngine engine = engine()) {
            speechBytes.set(new byte[] { 0 });
            assertEquals(0, engine.converse(cow(UUID.randomUUID()), "Привет").pcm().length);
            speechBytes.set(new byte[2 * 1024 * 1024 + 2]);
            assertEquals(0, engine.converse(cow(UUID.randomUUID()), "Привет ещё раз").pcm().length);
        }
    }

    // Coercing nonstring memory or missing reply would accept a broken structured provider contract.
    @Test void rejectsWrongStructuredFields() throws Exception {
        rawChat.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"reply\\\":\\\"Привет\\\",\\\"memory\\\":42}\"}}]}");
        try (ConversationEngine engine = engine()) {
            assertThrows(IOException.class, () -> engine.converse(cow(UUID.randomUUID()), "Привет"));
        }
    }
}
