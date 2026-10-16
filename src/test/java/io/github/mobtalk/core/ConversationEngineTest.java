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
    private final AtomicInteger speechCalls = new AtomicInteger();
    private final AtomicReference<JsonObject> speechRequest = new AtomicReference<>();
    private final AtomicReference<Runnable> speechHook = new AtomicReference<>();
    private final AtomicReference<String> rejectedVoice = new AtomicReference<>("");
    private final AtomicInteger rejectedStatus = new AtomicInteger(404);
    private final MobTalkConfig config = new MobTalkConfig();

    @BeforeEach void startProvider() throws IOException {
        // Explicit voices exercise only the localhost fixture; this does not test real TTS availability.
        SpeciesCatalog.Override cowVoices = new SpeciesCatalog.Override();
        cowVoices.voiceIds = new String[]{"LocalCowFixtureVoice0001", "LocalCowFixtureVoice0002", "LocalCowFixtureVoice0003"};
        config.speciesOverrides.put("minecraft:cow", cowVoices);
        SpeciesCatalog.Override creeperVoices = new SpeciesCatalog.Override();
        creeperVoices.voiceIds = new String[]{"LocalCreeperFixtureVoice0001", "LocalCreeperFixtureVoice0002", "LocalCreeperFixtureVoice0003"};
        config.speciesOverrides.put("minecraft:creeper", creeperVoices);
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
            speechCalls.incrementAndGet();
            speechRequest.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            int status = speechRequest.get().get("voice").getAsString().equals(rejectedVoice.get()) ? rejectedStatus.get() : speechStatus.get();
            Runnable hook = speechHook.getAndSet(null);
            if (hook != null) hook.run();
            byte[] body = status == 200 ? speechBytes.get() : "secret-provider-detail unit-test-key".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        chatEndpoint = URI.create(base + "/chat");
        speechEndpoint = URI.create(base + "/speech");
    }

    @AfterEach void stopProvider() throws IOException { server.stop(0); ConversationStore.closeWorld(directory); }

    private ConversationEngine engine() throws IOException {
        return new ConversationEngine(config, directory,
                new OpenRouterConversationClient("unit-test-key", config, chatEndpoint),
                new OpenRouterSpeechClient("unit-test-key", config, speechEndpoint));
    }

    private ConversationEngine engineWithStatusCache(Path configDirectory) throws IOException {
        return new ConversationEngine(config, directory,
                new OpenRouterConversationClient("unit-test-key", config, chatEndpoint),
                new OpenRouterSpeechClient("unit-test-key", config, speechEndpoint), configDirectory, "unit-test-key");
    }

    private MobSnapshot cow(UUID id) {
        return new MobSnapshot(id, "minecraft:cow", "Бурёнка", 7, 10,
                "minecraft:overworld", "minecraft:meadow", "ночь", "дождь", 3.5,
                "minecraft:stick", List.of("minecraft:sheep"), List.of("Игрок подошёл к мобу"));
    }

    private MobSnapshot currentCow(UUID id, long revision) {
        MobSnapshot snapshot = cow(id);
        return new MobSnapshot(snapshot.id(), snapshot.type(), snapshot.name(), snapshot.health(), snapshot.maxHealth(),
                snapshot.dimension(), snapshot.biome(), snapshot.timeOfDay(), snapshot.weather(), snapshot.distance(),
                snapshot.heldItem(), snapshot.nearby(), snapshot.recentEvents(), snapshot.playerId(), snapshot.playerDetails(),
                snapshot.mobDetails(), snapshot.environmentDetails(), snapshot.observedEntities(), snapshot.availableActions(), revision);
    }

    // Removing the UUID key, trimming, or reload would mix or lose persistent conversations.
    @Test void keepsTwentyFourPromptExchangesAndOneHundredOnDiskWithoutMixingMobs() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String assignedVoice;
        summary.set("п".repeat(5000));
        try (ConversationEngine engine = engine()) {
            assignedVoice = engine.converse(cow(first), "секрет первого моба").voiceId();
            for (int i = 1; i < 30; i++) engine.converse(cow(first), "вопрос " + i);
            engine.converse(cow(second), "совсем другой вопрос");
            assertFalse(lastRequest.get().toString().contains("секрет первого моба"));
            engine.memory().flush();
        }
        JsonObject profiles = JsonParser.parseString(Files.readString(directory.resolve("data/mobtalk/conversations.json")))
                .getAsJsonObject().getAsJsonObject("profiles");
        assertEquals(30, profiles.getAsJsonObject(first.toString()).getAsJsonArray("turns").size());
        assertEquals(1, profiles.getAsJsonObject(second.toString()).getAsJsonArray("turns").size());
        assertEquals(4000, profiles.getAsJsonObject(first.toString()).get("memory").getAsString().length());
        String personality = profiles.getAsJsonObject(first.toString()).get("personality").getAsString();
        ConversationStore.closeWorld(directory);
        try (ConversationEngine reopened = engine()) {
            assertEquals(assignedVoice, reopened.converse(cow(first), "ты помнишь меня?").voiceId());
            assertTrue(lastRequest.get().toString().contains("вопрос 29"));
            assertFalse(lastRequest.get().toString().contains("секрет первого моба"));
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
            assertEquals(4096, request.get("max_tokens").getAsInt());
            assertEquals("latency", request.getAsJsonObject("provider").get("sort").getAsString());
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
            engine.memory().flush();
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
        config.defaultVoice = "rSfuQoQ3FY8SVKeraMAp";
        String assigned;
        try (ConversationEngine engine = engine()) {
            assigned = engine.converse(creeper, "Привет").voiceId();
        }
        config.defaultVoice = "7S3KNdLDL7aRgBVRQb1z";
        try (ConversationEngine reopened = engine()) {
            assertEquals(assigned, reopened.converse(creeper, "И снова привет").voiceId());
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

    // A hit while TTS is pending must cancel both delivery and durable history, not just audio playback.
    @Test void hitDuringSpeechPreventsStaleHistoryCommit() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        MobSnapshot snapshot = new MobSnapshot(mob, "minecraft:cow", "Корова", 10, 10,
                "minecraft:overworld", "minecraft:plains", "day", "clear", 2, "minecraft:stick", List.of(), List.of(),
                player, java.util.Map.of("name", "Игрок"), java.util.Map.of(), java.util.Map.of(), List.of(), List.of(ActionType.NONE), 0);
        try (ConversationEngine engine = engine()) {
            speechHook.set(() -> engine.memory().recordDamage(mob, "minecraft:cow", "Корова", player, "Игрок", 1000));
            assertThrows(InterruptedException.class, () -> engine.converse(snapshot, "Привет"));
            assertTrue(engine.memory().card(mob, player).turns().isEmpty());
            assertEquals(-80, engine.memory().card(mob, player).relationship());
        }
    }

    // An invalid v2 preference can use only its own species reserve and must save the successful replacement.
    @Test void invalidStoredVoiceUsesAndPersistsOnlyTheSpeciesReserve() throws Exception {
        UUID mob = UUID.randomUUID();
        Path file = directory.resolve("data/mobtalk/conversations.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":2,\"profiles\":{\"" + mob + "\":{\"type\":\"minecraft:cow\",\"variantSlot\":0,\"name\":\"Особенная\",\"personality\":\"Тихая\",\"voiceId\":\"bad-id\",\"memory\":\"\",\"turns\":[]}}}");
        try (ConversationEngine engine = engine()) {
            ConversationEngine.Reply answer = engine.converse(cow(mob), "Привет");
            String reserve = SpeciesCatalog.reserve("minecraft:cow").id();
            assertEquals(reserve, answer.voiceId());
            assertEquals(reserve, speechRequest.get().get("voice").getAsString());
            assertEquals(reserve, engine.memory().card(mob, UUID.randomUUID()).voiceId());
            assertEquals(0, engine.memory().revision(mob));
            assertEquals(1, speechCalls.get());
        }
        ConversationStore.closeWorld(directory);
        try (ConversationEngine engine = engine()) {
            assertEquals(SpeciesCatalog.reserve("minecraft:cow").id(), engine.memory().card(mob, null).voiceId());
        }
    }

    // Editing an archetype must change already existing mobs, without leaving their copied old default in the prompt.
    @Test void refreshesEffectiveArchetypeForExistingMobOnEveryRequest() throws Exception {
        UUID mob = UUID.randomUUID();
        int slot;
        String oldPrompt;
        try (ConversationEngine engine = engine()) {
            engine.converse(cow(mob), "Привет");
            slot = engine.memory().card(mob, null).variantSlot();
            oldPrompt = engine.memory().card(mob, null).personality();
        }
        config.speciesOverrides.get("minecraft:cow").archetypePrompts[slot] = "Новая настройка архетипа: обсуждай сад по существу.";
        try (ConversationEngine engine = engine()) {
            engine.converse(cow(mob), "Как сад?");
            String system = lastRequest.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            assertTrue(system.contains("Новая настройка архетипа"));
            assertFalse(system.contains(oldPrompt));
        }
    }

    // A permanent rejection may try exactly one species reserve; a successful replacement is stable for this UUID.
    @Test void permanentlyRejectedVoiceUsesOwnReserveAndDoesNotRetryItOnTheNextTurn() throws Exception {
        try (ConversationEngine engine = engine()) {
            for (int status : new int[]{400, 404, 422}) {
                UUID mob = UUID.randomUUID();
                engine.memory().observe(cow(mob));
                rejectedVoice.set(engine.memory().card(mob, null).voiceId());
                rejectedStatus.set(status);
                int before = speechCalls.get();
                var answer = engine.converse(cow(mob), "Привет");
                String reserve = SpeciesCatalog.reserve("minecraft:cow").id();
                assertEquals(reserve, answer.voiceId());
                assertEquals(reserve, engine.memory().card(mob, null).voiceId());
                assertEquals(0, engine.memory().revision(mob));
                assertEquals(before + 2, speechCalls.get());
                assertFalse(engine.memory().card(mob, null).turns().isEmpty());
                engine.converse(cow(mob), "И снова привет");
                assertEquals(before + 3, speechCalls.get());
                assertEquals(reserve, speechRequest.get().get("voice").getAsString());
            }
        }
    }

    // Rate limiting and provider outages must remain subtitles rather than charge a second voice attempt.
    @Test void temporarySpeechErrorsDoNotSwitchVoiceOrRetry() throws Exception {
        try (ConversationEngine engine = engine()) {
            for (int status : new int[]{429, 500, 503, 504}) {
                UUID mob = UUID.randomUUID();
                speechStatus.set(status);
                engine.memory().observe(cow(mob));
                String originalVoice = engine.memory().card(mob, null).voiceId();
                int before = speechCalls.get();
                var answer = engine.converse(cow(mob), "Привет");
                assertEquals(0, answer.pcm().length);
                assertEquals(originalVoice, engine.memory().card(mob, null).voiceId());
                assertEquals(before + 1, speechCalls.get());
                assertFalse(answer.responseText().contains("secret-provider-detail"));
            }
        }
    }

    // Explicit individual edits and all temperament values must remain while the shared archetype is refreshed.
    @Test void keepsIndividualCustomPromptAlongsideNewEffectiveArchetypeAfterReload() throws Exception {
        UUID mob = UUID.randomUUID();
        MobCard before;
        try (ConversationEngine engine = engine()) {
            engine.memory().observe(cow(mob));
            engine.memory().updateCard(mob, "Свой персонаж", "Индивидуально люблю считать облака.", "CustomCowFixtureVoice0001", "Помню игрока");
            before = engine.memory().card(mob, null);
        }
        ConversationStore.closeWorld(directory);
        config.speciesOverrides.get("minecraft:cow").archetypePrompts[before.variantSlot()] = "Новый общий архетип: люблю сад.";
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(currentCow(mob, before.revision()), "Как дела?");
            var after = engine.memory().card(mob, null);
            String system = lastRequest.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            assertTrue(system.contains("Новый общий архетип"));
            assertTrue(system.contains("Индивидуально люблю считать облака."));
            assertEquals("Индивидуально люблю считать облака.", after.customPersonality());
            assertEquals(before.personality(), after.personality());
            assertEquals(before.voiceId(), answer.voiceId());
            assertEquals(before.calm(), after.calm());
            assertEquals(before.confidence(), after.confidence());
            assertEquals(before.curiosity(), after.curiosity());
            assertEquals(before.sociability(), after.sociability());
            assertEquals(before.preference(), after.preference());
            assertEquals(before.fear(), after.fear());
            assertTrue(system.contains("спокойствие=" + after.calm()));
            assertTrue(system.contains(after.preference()));
            assertTrue(system.contains(after.fear()));
        }
    }

    // Successful PCM marks the precise voice/model/account/date, never the token itself or a failed request.
    @Test void marksOnlySuccessfulPcmInTheStatusCacheWithoutStoringTheKey() throws Exception {
        Path cache = directory.resolve("config");
        try (ConversationEngine engine = engineWithStatusCache(cache)) {
            speechStatus.set(503);
            engine.converse(cow(UUID.randomUUID()), "Привет");
            assertFalse(Files.exists(cache.resolve("mobtalk-voice-status.json")));
            speechStatus.set(200);
            var answer = engine.converse(cow(UUID.randomUUID()), "Привет ещё раз");
            var validations = JsonParser.parseString(Files.readString(cache.resolve("mobtalk-voice-status.json"))).getAsJsonObject();
            var validation = validations.getAsJsonObject(answer.voiceId());
            assertEquals("elevenlabs/eleven-v4-turbo", validation.get("model").getAsString());
            assertTrue(validation.get("account").getAsString().matches("[0-9a-f]{64}"));
            assertFalse(validation.get("date").getAsString().isBlank());
            assertFalse(validations.toString().contains("unit-test-key"));
        }
    }

    // The ordinary localhost fixture must never perform account loading or create a production validation cache.
    @Test void ordinaryFixtureDoesNotWriteVoiceValidation() throws Exception {
        try (ConversationEngine engine = engine()) { engine.converse(cow(UUID.randomUUID()), "Привет"); }
        assertFalse(Files.exists(directory.resolve("mobtalk-voice-status.json")));
    }

    // A read-only or invalid cache directory must not suppress good PCM or history.
    @Test void statusCacheIoFailureDoesNotDiscardSuccessfulAudio() throws Exception {
        Path unavailable = directory.resolve("config-is-a-file");
        Files.writeString(unavailable, "not a directory");
        try (ConversationEngine engine = engineWithStatusCache(unavailable)) {
            UUID mob = UUID.randomUUID();
            assertArrayEquals(speechBytes.get(), engine.converse(cow(mob), "Привет").pcm());
            assertEquals(1, engine.memory().card(mob, null).turns().size());
        }
    }

    // Model output cannot select hidden or invented entities or an action excluded by the captured game policy.
    @Test void acceptsOnlyAvailableActionsAndVisibleAliases() {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        MobSnapshot snapshot = new MobSnapshot(mob, "minecraft:horse", "Лошадь", 10, 10,
                "minecraft:overworld", "minecraft:plains", "day", "clear", 2, "minecraft:stick", List.of(), List.of(),
                player, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(new ObservedEntity("e1", UUID.randomUUID(), "minecraft:cow", "Корова", 3, "north", true, java.util.Map.of()),
                        new ObservedEntity("e2", UUID.randomUUID(), "minecraft:sheep", "Овца", 4, "south", false, java.util.Map.of())),
                List.of(ActionType.NONE, ActionType.FOLLOW, ActionType.FLEE, ActionType.STOP), 0);
        assertEquals(new ActionIntent(ActionType.FOLLOW, "player"), OpenRouterConversationClient.validateAction(JsonParser.parseString("{\"type\":\"FOLLOW\",\"target\":\"player\"}").getAsJsonObject(), snapshot));
        assertEquals(new ActionIntent(ActionType.FLEE, "e1"), OpenRouterConversationClient.validateAction(JsonParser.parseString("{\"type\":\"FLEE\",\"target\":\"e1\"}").getAsJsonObject(), snapshot));
        for (String invalid : List.of("{\"type\":\"ATTACK\",\"target\":\"player\"}", "{\"type\":\"FOLLOW\",\"target\":\"e1\"}", "{\"type\":\"FLEE\",\"target\":\"e2\"}", "{\"type\":\"FOLLOW\",\"target\":\"e99\"}"))
            assertEquals(ActionIntent.none(), OpenRouterConversationClient.validateAction(JsonParser.parseString(invalid).getAsJsonObject(), snapshot));
    }

    @Test void blindWardenMayTargetConfirmedSoundButNotUnknownHiddenEntity() {
        MobSnapshot snapshot = new MobSnapshot(UUID.randomUUID(), "minecraft:warden", "Варден", 500, 500,
                "minecraft:overworld", "unknown", "unknown", "unknown", -1, "unknown", List.of(), List.of(), UUID.randomUUID(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(new ObservedEntity("e1", UUID.randomUUID(), "unknown", "Источник звука", 4, "north", false, java.util.Map.of("audible", true)),
                        new ObservedEntity("e2", UUID.randomUUID(), "unknown", "Скрытая цель", 4, "south", false, java.util.Map.of())),
                List.of(ActionType.NONE, ActionType.ATTACK), 0);
        assertEquals(new ActionIntent(ActionType.ATTACK, "e1"), OpenRouterConversationClient.validateAction(JsonParser.parseString("{\"type\":\"ATTACK\",\"target\":\"e1\"}").getAsJsonObject(), snapshot));
        assertEquals(ActionIntent.none(), OpenRouterConversationClient.validateAction(JsonParser.parseString("{\"type\":\"ATTACK\",\"target\":\"e2\"}").getAsJsonObject(), snapshot));
    }

    // A catalog slot awaiting assignment must remain text-only, not silently reuse a species-wide fallback.
    @Test void unassignedVoiceDoesNotSendSpeechRequest() throws Exception {
        UUID mob = UUID.randomUUID();
        try (ConversationEngine engine = engine()) {
            engine.memory().observe(cow(mob));
            // Persist an explicitly unassigned slot, as supported by incomplete catalog metadata.
            ConversationStore.closeWorld(directory);
        }
        Path file = directory.resolve("data/mobtalk/conversations.json");
        JsonObject state = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        state.getAsJsonObject("profiles").getAsJsonObject(mob.toString()).addProperty("voiceId", "");
        Files.writeString(file, state.toString());
        try (ConversationEngine engine = engine()) {
            assertEquals(0, engine.converse(cow(mob), "Привет").pcm().length);
            assertEquals(0, speechCalls.get());
        }
    }
}
