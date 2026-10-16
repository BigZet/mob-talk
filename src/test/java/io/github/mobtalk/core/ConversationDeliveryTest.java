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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConversationDeliveryTest {
    @TempDir Path directory;
    private HttpServer provider;
    private URI chat;
    private URI speech;
    private final MobTalkConfig config = new MobTalkConfig();
    private final UUID mob = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    @BeforeEach void startProvider() throws IOException {
        SpeciesCatalog.Override voices = new SpeciesCatalog.Override();
        voices.voiceIds = new String[] { "LocalDeliveryVoice00001", "LocalDeliveryVoice00002", "LocalDeliveryVoice00003" };
        config.speciesOverrides.put("minecraft:villager", voices);
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            JsonObject answer = JsonParser.parseString("""
                    {"reply":"За такие слова и торговаться не стану.","memory":"Игрок грубо потребовал скидку.",
                     "socialIntent":"INSULT","action":{"type":"NONE","target":""},
                     "bargain":{"type":"NONE","offerIndex":-1,"discount":0}}
                    """).getAsJsonObject();
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", answer.toString());
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject body = new JsonObject();
            body.add("choices", choices);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.createContext("/speech", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = { 0, 0, 1, 0 };
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.start();
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        chat = URI.create(base + "/chat");
        speech = URI.create(base + "/speech");
    }

    @AfterEach void closeProvider() throws IOException {
        provider.stop(0);
        ConversationStore.closeWorld(directory);
    }

    private ConversationEngine engine() throws IOException {
        return new ConversationEngine(config, directory,
                new OpenRouterConversationClient("unit-test-key", config, chat),
                new OpenRouterSpeechClient("unit-test-key", config, speech));
    }

    private MobSnapshot snapshot() {
        return new MobSnapshot(mob, "minecraft:villager", "Торговец", 20, 20,
                "minecraft:overworld", "minecraft:plains", "день", "ясно", 2,
                "minecraft:stick", List.of(), List.of(), player, Map.of(), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    @Test void cancelledOrRejectedDeliveryLeavesNoReplyMemoryOrSocialChange() throws Exception {
        try (ConversationEngine engine = engine()) {
            ConversationEngine.PreparedReply prepared = engine.prepare(snapshot(), "Дай скидку, жадина");
            assertFalse(prepared.reply().responseText().isBlank());
            assertTrue(prepared.reply().pcm().length > 0);
            // A rejected native quote or a closed merchant screen never calls commitDelivered.
            MobCard card = engine.memory().card(mob, player);
            assertTrue(card.turns().isEmpty());
            assertTrue(card.memory().isBlank());
            assertEquals(0, card.relationship());
        }
    }

    @Test void successfulAuthoritativeDeliveryRemembersOnceAtItsNewRevision() throws Exception {
        try (ConversationEngine engine = engine()) {
            ConversationEngine.PreparedReply prepared = engine.prepare(snapshot(), "Дай скидку, жадина");
            // Applying a valid native action/quote can itself advance the profile revision.
            engine.memory().recordAction(mob, ActionIntent.none());
            long deliveredRevision = engine.memory().revision(mob);
            assertTrue(engine.commitDelivered(prepared, deliveredRevision));
            assertFalse(engine.commitDelivered(prepared, deliveredRevision));
            MobCard card = engine.memory().card(mob, player);
            assertEquals(1, card.turns().size());
            assertEquals("Игрок грубо потребовал скидку.", card.memory());
            assertEquals(-10, card.relationship());
        }
    }

    @Test void hitAfterGameApplicationPreventsStaleReplyFromBeingRemembered() throws Exception {
        try (ConversationEngine engine = engine()) {
            ConversationEngine.PreparedReply prepared = engine.prepare(snapshot(), "Дай скидку, жадина");
            engine.memory().recordAction(mob, ActionIntent.none());
            long deliveredRevision = engine.memory().revision(mob);
            engine.memory().recordDamage(mob, "minecraft:villager", "Торговец", player, "Игрок", System.currentTimeMillis());
            assertFalse(engine.commitDelivered(prepared, deliveredRevision));
            // A stale delivery token cannot be retried against a newer revision.
            assertFalse(engine.commitDelivered(prepared, engine.memory().revision(mob)));
            MobCard card = engine.memory().card(mob, player);
            assertTrue(card.turns().isEmpty());
            assertTrue(card.memory().isBlank());
            assertEquals(-80, card.relationship());
        }
    }
}
