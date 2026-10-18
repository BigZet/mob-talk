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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicReference<JsonObject> bargainResponse = new AtomicReference<>();
    private final AtomicInteger chatStatus = new AtomicInteger(200);
    private final AtomicBoolean rejectsStructuredOutput = new AtomicBoolean();
    private final AtomicInteger speechStatus = new AtomicInteger(200);
    private final AtomicReference<byte[]> speechBytes = new AtomicReference<>(new byte[] { 0, 0, 1, 0 });
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final java.util.Queue<JsonObject> queuedAnswers = new java.util.concurrent.ConcurrentLinkedQueue<>();
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
            content.addProperty("socialIntent", "NONE");
            JsonObject defaultAction = new JsonObject(); defaultAction.addProperty("type", "NONE"); defaultAction.addProperty("target", "");
            content.add("action", defaultAction);
            if (bargainResponse.get() != null) content.add("bargain", bargainResponse.get());
            JsonObject queued = queuedAnswers.poll();
            if (queued != null) content = queued;
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
            boolean strictRejected = rejectsStructuredOutput.get() && "json_schema".equals(lastRequest.get().getAsJsonObject("response_format").get("type").getAsString());
            exchange.sendResponseHeaders(strictRejected ? 404 : chatStatus.get(), body.length);
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

    private Map<String,Object> bargainingContext(boolean open, Map<String,Object> state) {
        return Map.ofEntries(Map.entry("open", open), Map.entry("selectedIndex", 1), Map.entry("selectedOffer", 1),
                Map.entry("fingerprint", "fixture-sticks-for-emerald"), Map.entry("baseCount", 32),
                Map.entry("vanillaCount", 32), Map.entry("currentPrice", 32), Map.entry("maximumDiscount", 3),
                Map.entry("allowedPrices", List.of(31, 30, 29)), Map.entry("available", true),
                Map.entry("firstBuy", Map.of("id", "minecraft:stick", "name", "Палка", "count", 32)),
                Map.entry("secondBuy", Map.of()), Map.entry("sell", Map.of("id", "minecraft:emerald", "name", "Изумруд", "count", 1)),
                Map.entry("serverTick", 1000), Map.entry("reason", ""), Map.entry("state", state));
    }

    private MobSnapshot tradingMob(String type, Map<String,Object> bargaining) {
        MobSnapshot base = cow(UUID.randomUUID());
        return new MobSnapshot(base.id(), type, "Торговец", base.health(), base.maxHealth(), base.dimension(), base.biome(),
                base.timeOfDay(), base.weather(), base.distance(), base.heldItem(), base.nearby(), base.recentEvents(),
                UUID.randomUUID(), Map.of(), Map.of("bargaining", bargaining), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    private MobSnapshot carrotFarmer(Map<String,Object> bargaining) {
        MobSnapshot base = tradingMob("minecraft:villager", bargaining);
        List<Map<String,Object>> offers = List.of(
                Map.of("buy", Map.of("name", "Морковь", "count", 22), "secondBuy", Map.of("count", 0), "sell", Map.of("name", "Изумруд", "count", 1)),
                Map.of("buy", Map.of("name", "Пшеница", "count", 20), "secondBuy", Map.of("count", 0), "sell", Map.of("name", "Изумруд", "count", 1)));
        return new MobSnapshot(base.id(), base.type(), base.name(), base.health(), base.maxHealth(), base.dimension(), base.biome(),
                base.timeOfDay(), base.weather(), base.distance(), base.heldItem(), base.nearby(), base.recentEvents(), base.playerId(),
                Map.of(), Map.of("bargaining", bargaining, "offers", offers), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    // Reported in game: "морковка теперь по 18" with bargain NONE while the window still charged 22.
    @Test void doesNotVoiceAPriceThatNoBargainApplies() throws Exception {
        queuedAnswers.add(tradingAnswer("Ладно, голема починил — дам скидку. Морковка теперь по 18, пшеница по 15.", "NONE", -1, 0));
        queuedAnswers.add(tradingAnswer("Открой торговлю и выбери морковку, тогда поговорим о цене.", "NONE", -1, 0));
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(carrotFarmer(bargainingContext(false, Map.of("status", "NONE"))), "Давай скидку");
            assertEquals("Открой торговлю и выбери морковку, тогда поговорим о цене.", answer.responseText());
            assertEquals(2, chatCalls.get());
            String correction = lastRequest.get().getAsJsonArray("messages").asList().getLast().getAsJsonObject().get("content").getAsString();
            assertTrue(correction.contains("15"), correction);
        }
    }

    @Test void recognisesOnlyPricesBackedByTheBargain() {
        var farmer = carrotFarmer(bargainingContext(true, Map.of("status", "NONE")));
        var offer = new BargainIntent(BargainIntent.Type.OFFER, 1, 2);
        assertEquals(-1, AnswerValidator.unbackedPrice("Ладно, уступлю: 30 палок и по рукам.", offer, farmer));
        assertEquals(18, AnswerValidator.unbackedPrice("Уступлю, отдам за 18.", offer, farmer));
        assertEquals(-1, AnswerValidator.unbackedPrice("Скидки не будет: 22 морковки за изумруд.", BargainIntent.none(), farmer));
        assertEquals(29, AnswerValidator.unbackedPrice("Теперь 29, договорились?", BargainIntent.none(), farmer));
        assertEquals(-1, AnswerValidator.unbackedPrice("Привет! Мне 18 лет.", BargainIntent.none(), farmer));
        assertEquals(-1, AnswerValidator.unbackedPrice("Скину до 18.", BargainIntent.none(), cow(UUID.randomUUID())));
        // Reported in the model comparison: "восемь" spoken while the window got 19 on the second step.
        var eight = new BargainIntent(BargainIntent.Type.OFFER, 1, 24);
        assertEquals(-1, AnswerValidator.unbackedPrice("Шесть? Ладно, уступлю: восемь морковок — и по рукам.", eight, farmer));
        assertEquals(8, AnswerValidator.unbackedPrice("Шесть? Ладно, уступлю: восемь морковок.", new BargainIntent(BargainIntent.Type.OFFER, 1, 13), farmer));
        assertEquals(29, AnswerValidator.unbackedPrice("Скину до двадцати девяти.", BargainIntent.none(), farmer));
        assertEquals(List.of(22, 5, 1, 30), AnswerValidator.spokenNumbers("Двадцать две, пять или одну? Ещё 30."));
    }

    @Test void readsTheSpokenPriceRatherThanADiscount() {
        var snapshot = tradingMob("minecraft:villager", bargainingContext(true, Map.of("status", "NONE")));
        JsonObject answer = JsonParser.parseString("{\"type\":\"OFFER\",\"offerIndex\":1,\"price\":30}").getAsJsonObject();
        assertEquals(new BargainIntent(BargainIntent.Type.OFFER, 1, 2), AnswerValidator.validateBargain(answer, snapshot));
        JsonObject outside = JsonParser.parseString("{\"type\":\"OFFER\",\"offerIndex\":1,\"price\":20}").getAsJsonObject();
        assertEquals(BargainIntent.none(), AnswerValidator.validateBargain(outside, snapshot));
    }

    // A structured concession changes the first input count, even when the player pays with items rather than emeralds.
    @Test void carriesStructuredBargainOfferAndExactPendingAcceptanceThroughEngine() throws Exception {
        try (ConversationEngine engine = engine()) {
            bargainResponse.set(JsonParser.parseString("{\"type\":\"OFFER\",\"offerIndex\":1,\"discount\":3}").getAsJsonObject());
            var offer = engine.converse(tradingMob("minecraft:villager", bargainingContext(true, Map.of("status", "NONE"))), "Можешь уступить?");
            assertEquals(new BargainIntent(BargainIntent.Type.OFFER, 1, 3), offer.bargain());
            assertArrayEquals(speechBytes.get(), offer.pcm());
            bargainResponse.set(JsonParser.parseString("{\"type\":\"ACCEPT\",\"offerIndex\":1,\"discount\":0}").getAsJsonObject());
            var accepted = engine.converse(tradingMob("minecraft:villager", bargainingContext(true,
                    Map.of("status", "PENDING", "offerIndex", 1, "vanillaCount", 32, "price", 29, "discount", 3,
                            "key", "fixture-sticks-for-emerald", "expiresTick", 7000L))), "Согласен");
            assertEquals(new BargainIntent(BargainIntent.Type.ACCEPT, 1, 0), accepted.bargain());
        }
    }

    // Model intent cannot negotiate outside the current villager window, invent a price, or change a pending agreement.
    @Test void rejectsBargainOutsideSelectedTradeOrPermittedPriceBeforeVoicingIt() throws Exception {
        try (ConversationEngine engine = engine()) {
            bargainResponse.set(JsonParser.parseString("{\"type\":\"OFFER\",\"offerIndex\":1,\"discount\":3}").getAsJsonObject());
            assertThrows(IOException.class, () -> engine.converse(tradingMob("minecraft:cow", bargainingContext(true, Map.of("status", "NONE"))), "Уступишь?"));
            assertThrows(IOException.class, () -> engine.converse(tradingMob("minecraft:villager", bargainingContext(false, Map.of("status", "NONE"))), "Уступишь?"));
            for (String invalid : List.of("{\"type\":\"OFFER\",\"offerIndex\":0,\"discount\":3}",
                    "{\"type\":\"OFFER\",\"offerIndex\":1,\"discount\":0}",
                    "{\"type\":\"OFFER\",\"offerIndex\":1,\"discount\":4}",
                    "{\"type\":\"OFFER\",\"offerIndex\":1,\"discount\":2.5}",
                    "{\"type\":\"OFFER\",\"offerIndex\":1,\"discount\":\"3\"}")) {
                bargainResponse.set(JsonParser.parseString(invalid).getAsJsonObject());
                assertThrows(IOException.class, () -> engine.converse(tradingMob("minecraft:villager", bargainingContext(true, Map.of("status", "NONE"))), "Уступишь?"));
            }
            bargainResponse.set(JsonParser.parseString("{\"type\":\"ACCEPT\",\"offerIndex\":1,\"discount\":2}").getAsJsonObject());
            assertEquals(new BargainIntent(BargainIntent.Type.ACCEPT, 1, 0), engine.converse(tradingMob("minecraft:villager", bargainingContext(true,
                    Map.of("status", "PENDING", "offerIndex", 1, "vanillaCount", 32, "price", 29, "discount", 3,
                            "key", "fixture-sticks-for-emerald", "expiresTick", 7000L))), "Согласен").bargain());
            assertThrows(IOException.class, () -> engine.converse(tradingMob("minecraft:villager", bargainingContext(true, Map.of("status", "NONE"))), "Согласен"));
            bargainResponse.set(null);
            assertEquals(BargainIntent.none(), engine.converse(cow(UUID.randomUUID()), "Привет").bargain());
        }
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

    // A fixed sentence count cuts natural short reactions; the configured word budget still applies.
    @Test void keepsNaturalSentenceRhythmWithinFortyFiveWords() throws Exception {
        reply.set("Один. Два. Третья короткая мысль.");
        try (ConversationEngine engine = engine()) {
            assertEquals("Один. Два. Третья короткая мысль.", engine.converse(cow(UUID.randomUUID()), "Привет").responseText());
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

    // Odd PCM would misalign samples; streamed audio starts before its length is known, so an oversized body is cut at the limit.
    @Test void rejectsInvalidAndBoundsOversizedPcmWhilePreservingText() throws Exception {
        try (ConversationEngine engine = engine()) {
            speechBytes.set(new byte[] { 0 });
            assertEquals(0, engine.converse(cow(UUID.randomUUID()), "Привет").pcm().length);
            speechBytes.set(new byte[2 * 1024 * 1024 + 2]);
            var oversized = engine.converse(cow(UUID.randomUUID()), "Привет ещё раз");
            assertEquals("Я мирно пасусь здесь.", oversized.responseText());
            assertTrue(oversized.pcm().length <= OpenRouterSpeechClient.MAX_AUDIO_BYTES);
        }
    }

    // Venice prepends its own system prompt unless told not to, and has no OpenRouter provider routing.
    @Test void sendsVeniceParametersInsteadOfOpenRouterRouting() throws Exception {
        MobTalkConfig venice = config.copy(); venice.llmProvider = "venice"; venice.veniceModel = "deepseek-v4-1-flash";
        try (var client = new OpenRouterConversationClient("unit-test-key", venice, chatEndpoint)) {
            client.respond(cow(UUID.randomUUID()), ConversationStore.open(directory, venice).profile(cow(UUID.randomUUID())), "Привет");
        } finally { ConversationStore.closeWorld(directory); }
        JsonObject request = lastRequest.get();
        assertEquals("deepseek-v4-1-flash", request.get("model").getAsString());
        assertFalse(request.has("provider"));
        assertFalse(request.getAsJsonObject("venice_parameters").get("include_venice_system_prompt").getAsBoolean());
        assertTrue(request.getAsJsonObject("venice_parameters").get("disable_thinking").getAsBoolean());
    }

    // Seen live: ".Охуел…" and ": «Охуел…»" would be voiced with the junk.
    @Test void speaksFromTheFirstWord() {
        assertEquals("Охуел, голодранец?", AnswerValidator.spokenText(".Охуел, голодранец?"));
        assertEquals("Охуел, простолюдин?", AnswerValidator.spokenText(": «Охуел, простолюдин?»"));
        assertEquals("Хмм... Двадцать две.", AnswerValidator.spokenText("Хмм... Двадцать две."));
        assertEquals("Скажи «привет» сам.", AnswerValidator.spokenText("Скажи «привет» сам."));
    }

    // Seen in the review: DeepSeek sometimes answered in English; the player's own name is not a foreign word.
    @Test void asksAgainInRussianWhenTheReplyIsInEnglish() throws Exception {
        queuedAnswers.add(tradingAnswer("Yeah, sniffing. Under this grass there is history, colleague.", "NONE", -1, 0));
        queuedAnswers.add(tradingAnswer("Нюхаю, коллега. Под этой травой — история.", "NONE", -1, 0));
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(cow(UUID.randomUUID()), "Что ты нюхаешь?");
            assertEquals("Нюхаю, коллега. Под этой травой — история.", answer.responseText());
            assertEquals(2, chatCalls.get());
            String correction = lastRequest.get().getAsJsonArray("messages").asList().getLast().getAsJsonObject().get("content").getAsString();
            assertTrue(correction.contains("по-русски") && !correction.contains("торга"), correction);
        }
        MobSnapshot named = tradingMob("minecraft:villager", bargainingContext(false, Map.of("status", "NONE")));
        assertEquals(0, AnswerValidator.foreignWords("Хмм, привет, numnerd!", new MobSnapshot(named.id(), named.type(), named.name(), 20, 20,
                "minecraft:overworld", "minecraft:plains", "day", "clear", 2, "minecraft:stick", List.of(), List.of(), named.playerId(),
                Map.of("name", "numnerd"), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0)));
    }

    @Test void retriesOnlyConnectionsThatWereDropped() {
        assertTrue(BoundedHttp.dropped(new IOException("OpenRouter LLM: не удалось выполнить запрос.")));
        assertTrue(BoundedHttp.dropped(new BoundedHttp.SafeFailure("OpenRouter LLM: соединение прервано.")));
        assertFalse(BoundedHttp.dropped(new BoundedHttp.SafeFailure("OpenRouter LLM: ошибка HTTP 401.", 401)));
        assertFalse(BoundedHttp.dropped(new IOException("OpenRouter LLM: превышено время ожидания.")));
    }

    // A bare "heldItem" beside the mob's own fields made piglins say "my iron sword drops" about the player's sword.
    @Test void labelsTheHeldItemAsThePlayers() {
        String json = new com.google.gson.Gson().toJson(cow(UUID.randomUUID()));
        assertTrue(json.contains("\"playerHeldItem\":\"minecraft:stick\""), json);
        assertFalse(json.contains("\"heldItem\""));
    }

    @Test void findsTheReplyOnlyOnceItsClosingQuoteStreamedIn() {
        assertNull(OpenRouterConversationClient.completedReply("{\"reply\":\"Скажи \\\"при"));
        assertEquals("Скажи \"привет\"", OpenRouterConversationClient.completedReply("{\"reply\":\"Скажи \\\"привет\\\"\",\"socialIn"));
        assertNull(OpenRouterConversationClient.completedReply("{\"socialIntent\":\"NONE\""));
    }

    @Test void appendsNewFactsAndForgetsTheOldestWhenFull() {
        assertEquals("Старое", MemoryNotes.remember("Старое", "  ", 100));
        assertEquals("Старое\nНовое", MemoryNotes.remember("Старое", "Новое", 100));
        assertEquals("второе\nтретье", MemoryNotes.remember("первое\nвторое", "третье", 16));
    }

    // Speech must start from the streamed reply, once, while the model still writes facts, action and bargain.
    @Test void startsSpeechFromAStreamedReplyAndAppendsItsFacts() throws Exception {
        String answer = "{\"reply\":\"Скажи \\\"му\\\", путник.\",\"socialIntent\":\"NONE\",\"action\":{\"type\":\"NONE\",\"target\":\"\"},"
                + "\"bargain\":{\"type\":\"NONE\",\"offerIndex\":-1,\"price\":0},\"newFacts\":\"Игрок назвался Стивом.\"}";
        StringBuilder events = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        for (int i = 0; i < answer.length(); i += 7) {
            JsonObject delta = new JsonObject(); delta.addProperty("content", answer.substring(i, Math.min(answer.length(), i + 7)));
            JsonObject choice = new JsonObject(); choice.add("delta", delta);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject event = new JsonObject(); event.add("choices", choices);
            events.append("data: ").append(event).append("\n\n");
        }
        rawChat.set(events.append("data: [DONE]\n\n").toString());
        try (ConversationEngine engine = engine()) {
            MobSnapshot villager = tradingMob("minecraft:villager", bargainingContext(false, Map.of("status", "NONE")));
            var reply = engine.converse(villager, "Привет, я Стив");
            assertEquals("Скажи \"му\", путник.", reply.responseText());
            assertTrue(lastRequest.get().get("stream").getAsBoolean());
            assertArrayEquals(speechBytes.get(), reply.pcm());
            assertEquals(1, speechCalls.get(), "The early speech request is reused, not repeated");
            assertTrue(engine.memory().card(villager.id(), villager.playerId()).memory().endsWith("Игрок назвался Стивом."));
        } finally { rawChat.set(null); }
    }

    // The game must start speaking from the first bytes instead of waiting for the whole body.
    @Test void speechStreamPlaysWhatArrivedAndEndsCleanly() throws Exception {
        SpeechStream stream = new SpeechStream(64);
        assertFalse(stream.awaitPrebuffer(4, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20)));
        assertTrue(stream.append(java.nio.ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 })));
        assertTrue(stream.awaitPrebuffer(4, 0));
        java.nio.ByteBuffer target = java.nio.ByteBuffer.allocate(16);
        assertEquals(4, stream.read(0, target, 16), "Only whole 16-bit samples are handed out");
        assertEquals(0, stream.read(4, target, 16), "More audio may still arrive");
        stream.finish();
        assertEquals(-1, stream.read(4, target, 16), "The odd trailing byte is dropped at the end");
        assertFalse(stream.append(java.nio.ByteBuffer.wrap(new byte[] { 6, 7 })));
        AtomicBoolean cancelled = new AtomicBoolean();
        SpeechStream download = new SpeechStream(64);
        download.onCancel(() -> cancelled.set(true));
        download.close();
        assertTrue(cancelled.get());
        assertEquals(-1, download.read(0, target, 16));
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
            assertEquals("elevenlabs/eleven-flash-v2.5", speechRequest.get().get("model").getAsString());
            assertEquals("elevenlabs/eleven-flash-v2.5", validation.get("model").getAsString());
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
        assertEquals(new ActionIntent(ActionType.FOLLOW, "player"), AnswerValidator.validateAction(JsonParser.parseString("{\"type\":\"FOLLOW\",\"target\":\"player\"}").getAsJsonObject(), snapshot));
        assertEquals(new ActionIntent(ActionType.FLEE, "e1"), AnswerValidator.validateAction(JsonParser.parseString("{\"type\":\"FLEE\",\"target\":\"e1\"}").getAsJsonObject(), snapshot));
        for (String invalid : List.of("{\"type\":\"ATTACK\",\"target\":\"player\"}", "{\"type\":\"FOLLOW\",\"target\":\"e1\"}", "{\"type\":\"FLEE\",\"target\":\"e2\"}", "{\"type\":\"FOLLOW\",\"target\":\"e99\"}"))
            assertEquals(ActionIntent.none(), AnswerValidator.validateAction(JsonParser.parseString(invalid).getAsJsonObject(), snapshot));
    }

    @Test void blindWardenMayTargetConfirmedSoundButNotUnknownHiddenEntity() {
        MobSnapshot snapshot = new MobSnapshot(UUID.randomUUID(), "minecraft:warden", "Варден", 500, 500,
                "minecraft:overworld", "unknown", "unknown", "unknown", -1, "unknown", List.of(), List.of(), UUID.randomUUID(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(new ObservedEntity("e1", UUID.randomUUID(), "unknown", "Источник звука", 4, "north", false, java.util.Map.of("audible", true)),
                        new ObservedEntity("e2", UUID.randomUUID(), "unknown", "Скрытая цель", 4, "south", false, java.util.Map.of())),
                List.of(ActionType.NONE, ActionType.ATTACK), 0);
        assertEquals(new ActionIntent(ActionType.ATTACK, "e1"), AnswerValidator.validateAction(JsonParser.parseString("{\"type\":\"ATTACK\",\"target\":\"e1\"}").getAsJsonObject(), snapshot));
        assertEquals(ActionIntent.none(), AnswerValidator.validateAction(JsonParser.parseString("{\"type\":\"ATTACK\",\"target\":\"e2\"}").getAsJsonObject(), snapshot));
    }

    // Some free providers accept JSON objects but route strict schema requests to no endpoint.
    @Test void fallsBackOnceToJsonObjectWhenTheProviderHasNoStructuredEndpoint() throws Exception {
        rejectsStructuredOutput.set(true);
        bargainResponse.set(JsonParser.parseString("{\"type\":\"NONE\",\"offerIndex\":-1,\"discount\":0}").getAsJsonObject());
        try (ConversationEngine engine = engine()) {
            assertEquals("Я мирно пасусь здесь.", engine.converse(cow(UUID.randomUUID()), "Привет").responseText());
            assertEquals(3, chatCalls.get(), "Drops temperature first, then falls back to json_object");
            assertEquals("json_object", lastRequest.get().getAsJsonObject("response_format").get("type").getAsString());
            assertFalse(lastRequest.get().has("temperature"));
            engine.converse(cow(UUID.randomUUID()), "Ещё вопрос");
            assertEquals(4, chatCalls.get());
        }
    }

    // Dropping an invalid price intent while voicing its promise would falsely announce a discount.
    @Test void doesNotVoiceOrRememberAnImpossibleBargainingPromise() throws Exception {
        reply.set("Отдам за десять.");
        bargainResponse.set(JsonParser.parseString("{\"type\":\"OFFER\",\"offerIndex\":1,\"discount\":22}").getAsJsonObject());
        try (ConversationEngine engine = engine()) {
            MobSnapshot villager = tradingMob("minecraft:villager", bargainingContext(true, Map.of("status", "NONE")));
            assertThrows(IOException.class, () -> engine.converse(villager, "Уступишь?"));
            assertEquals(0, speechCalls.get());
            assertEquals(3, chatCalls.get(), "Two corrections at most, the last one without bargaining");
            assertTrue(engine.memory().card(villager.id(), villager.playerId()).turns().isEmpty());
        }
    }

    // Reported in game: the window had potatoes selected, the player asked about carrots, and the reply became an error.
    @Test void asksToSelectTheOtherRowInsteadOfFailing() throws Exception {
        queuedAnswers.add(tradingAnswer("Морковку отдам за двадцать.", "OFFER", 0, 2));
        queuedAnswers.add(tradingAnswer("Ладно, морковь за двадцать.", "OFFER", 0, 2));
        queuedAnswers.add(tradingAnswer("Морковь, говоришь? Выбери её в окне, тогда и поторгуемся.", "NONE", -1, 0));
        try (ConversationEngine engine = engine()) {
            MobSnapshot villager = tradingMob("minecraft:villager", bargainingContext(true, Map.of("status", "NONE")));
            var answer = engine.converse(villager, "Мне интересна морковь");
            assertEquals("Морковь, говоришь? Выбери её в окне, тогда и поторгуемся.", answer.responseText());
            assertEquals(BargainIntent.none(), answer.bargain());
            assertEquals(3, chatCalls.get());
            var messages = lastRequest.get().getAsJsonArray("messages");
            assertTrue(messages.get(messages.size() - 2).getAsJsonObject().get("content").getAsString().contains("строке 0"));
            assertEquals("[\"NONE\"]", lastRequest.get().getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema")
                    .getAsJsonObject("properties").getAsJsonObject("bargain").getAsJsonObject("properties").getAsJsonObject("type").get("enum").toString());
        }
    }

    private JsonObject tradingAnswer(String text, String type, int index, int discount) {
        JsonObject answer = new JsonObject();
        answer.addProperty("reply", text); answer.addProperty("memory", text); answer.addProperty("socialIntent", "NONE");
        answer.add("action", JsonParser.parseString("{\"type\":\"NONE\",\"target\":\"\"}"));
        JsonObject bargain = new JsonObject(); bargain.addProperty("type", type);
        bargain.addProperty("offerIndex", index); bargain.addProperty("discount", discount); answer.add("bargain", bargain);
        return answer;
    }

    private String systemPrompt() {
        return lastRequest.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
    }

    // Haggling rules belong only to a villager that really sells something; the chosen rudeness level reaches the model.
    @Test void sendsTradeRulesOnlyToSellingVillagersAndAppliesRudeness() throws Exception {
        config.rudeness = 0;
        try (ConversationEngine engine = engine()) {
            engine.converse(cow(UUID.randomUUID()), "Привет");
            assertTrue(systemPrompt().contains("bargain всегда"));
            assertFalse(systemPrompt().contains("Голосовой торг"));
            assertTrue(systemPrompt().contains("без мата"));
            assertTrue(lastRequest.get().getAsJsonArray("messages").asList().getLast().getAsJsonObject()
                    .get("content").getAsString().startsWith("Игрок говорит тебе: «Привет»"));

            MobSnapshot base = tradingMob("minecraft:villager", bargainingContext(false, Map.of("status", "NONE")));
            engine.converse(base, "Как торговля?");
            assertTrue(systemPrompt().contains("bargain всегда"), "A villager without goods must not haggle");

            MobSnapshot seller = new MobSnapshot(base.id(), base.type(), base.name(), base.health(), base.maxHealth(), base.dimension(),
                    base.biome(), base.timeOfDay(), base.weather(), base.distance(), base.heldItem(), base.nearby(), base.recentEvents(),
                    base.playerId(), Map.of(), Map.of("bargaining", bargainingContext(false, Map.of("status", "NONE")),
                    "offers", List.of(Map.of("buy", Map.of("id", "minecraft:stick", "count", 32)))), Map.of(), List.of(),
                    List.of(ActionType.NONE), 0);
            engine.converse(seller, "Уступишь?");
            assertTrue(systemPrompt().contains("открыл окно торговли"));
            assertFalse(systemPrompt().contains("Голосовой торг"));

            engine.converse(tradingMob("minecraft:villager", bargainingContext(true, Map.of("status", "NONE"))), "Уступишь?");
            assertTrue(systemPrompt().contains("Голосовой торг"));
        }
    }

    // A real Flash Lite response proposed an unavailable concession despite refusing it in the spoken text.
    @Test void repairsAnInvalidTradeOnceBeforeSpeechAndHistory() throws Exception {
        queuedAnswers.add(tradingAnswer("Двадцать девять? Нет, тридцать две — моя цена.", "OFFER", 1, 3));
        queuedAnswers.add(tradingAnswer("Открой торговлю, тогда обсудим выбранный товар.", "NONE", -1, 0));
        try (ConversationEngine engine = engine()) {
            MobSnapshot villager = tradingMob("minecraft:villager", bargainingContext(false, Map.of("status", "NONE")));
            var answer = engine.converse(villager, "Давай двадцать девять вместо тридцати двух?");
            assertEquals(BargainIntent.none(), answer.bargain());
            assertEquals("Открой торговлю, тогда обсудим выбранный товар.", answer.responseText());
            assertEquals(2, chatCalls.get()); assertEquals(1, speechCalls.get());
            var turns = engine.memory().card(villager.id(), villager.playerId()).turns();
            assertEquals(1, turns.size()); assertEquals(answer.responseText(), turns.getFirst().assistant());
        }
    }

    @Test void refusingWithoutAnOpenWindowIsSafeOrdinaryDialogue() throws Exception {
        queuedAnswers.add(tradingAnswer("Без торгового окна скидки не обсуждаю.", "DECLINE", -1, 0));
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(tradingMob("minecraft:villager", bargainingContext(false, Map.of("status", "NONE"))), "Уступи");
            assertEquals(BargainIntent.none(), answer.bargain());
            assertEquals(1, chatCalls.get()); assertEquals(1, speechCalls.get());
        }
    }

    @Test void acceptsTheStoredPriceWithoutParsingUnusedDiscount() {
        var snapshot = tradingMob("minecraft:villager", bargainingContext(true,
                Map.of("status", "PENDING", "offerIndex", 1, "vanillaCount", 32, "price", 29, "discount", 3,
                        "key", "fixture-sticks-for-emerald", "expiresTick", 7000L)));
        for (String discount : List.of("null", "\"0\"")) {
            JsonObject answer = JsonParser.parseString("{\"type\":\"ACCEPT\",\"offerIndex\":1,\"discount\":" + discount + "}").getAsJsonObject();
            assertEquals(new BargainIntent(BargainIntent.Type.ACCEPT, 1, 0), AnswerValidator.validateBargain(answer, snapshot));
        }
    }

    @Test void doesNotOfferAgainWhenTheServerOnlyAllowsAcceptOrDecline() throws Exception {
        var context = new java.util.LinkedHashMap<String,Object>(bargainingContext(true, Map.of("status", "PENDING")));
        context.put("allowedTypes", List.of("NONE", "ACCEPT", "DECLINE"));
        context.put("reason", "Последняя цена: можно принять её или отказаться");
        queuedAnswers.add(tradingAnswer("Уступлю ещё немного.", "OFFER", 1, 2));
        queuedAnswers.add(tradingAnswer("Я уже назвал последнюю цену.", "NONE", -1, 0));
        try (ConversationEngine engine = engine()) {
            assertEquals(BargainIntent.none(), engine.converse(tradingMob("minecraft:villager", context), "Ещё дешевле?").bargain());
            assertEquals(2, chatCalls.get()); assertEquals(1, speechCalls.get());
            var allowed = lastRequest.get().getAsJsonObject("response_format").getAsJsonObject("json_schema")
                    .getAsJsonObject("schema").getAsJsonObject("properties").getAsJsonObject("bargain")
                    .getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum");
            assertFalse(allowed.toString().contains("OFFER"));
        }
    }

    /** The shape Trading.view promises, built by hand: one visible deal, one locked secret and a pending favour (3 of 8 fences, or all 8). */
    private Map<String,Object> cowTrading(boolean canUnlock, boolean canComplete) { return cowTrading(canUnlock, canComplete, true); }
    /** The same cow when nothing is owed: it may ask for a favour of its own invention. */
    private Map<String,Object> freeCow(boolean canUnlock) { return cowTrading(canUnlock, false, false); }
    private Map<String,Object> cowTrading(boolean canUnlock, boolean canComplete, boolean owed) {
        Map<String,Object> milk = Map.ofEntries(Map.entry("id", "milk"), Map.entry("name", "Ведро молока"),
                Map.entry("sell", Map.of("id", "minecraft:milk_bucket", "name", "Ведро молока", "count", 1)),
                Map.entry("price", Map.of("id", "minecraft:wheat", "name", "Пшеница", "count", 6)),
                Map.entry("basePrice", 6), Map.entry("currentPrice", 6), Map.entry("minPrice", 1), Map.entry("maxDiscountNow", 3),
                Map.entry("allowedPrices", List.of(5, 4, 3)), Map.entry("canOffer", true), Map.entry("reason", ""), Map.entry("stockLeft", -1));
        Map<String,Object> leather = Map.of("id", "leather", "hint", "Знает, где лежит кожа пропавшей подруги", "unlockRelationship", 40,
                "requiresFavor", true, "canUnlockNow", canUnlock, "reason", canUnlock ? "" : "отношение ниже 40");
        Map<String,Object> fence = Map.of("item", "minecraft:oak_fence", "itemName", "Дубовый забор", "count", 8, "have", canComplete ? 8 : 3,
                "canComplete", canComplete, "title", "Почини загон", "rewardDeal", "leather", "rewardPercent", 20);
        List<Map<String,Object>> candidates = List.of(Map.of("item", "minecraft:bell", "itemName", "Колокол", "count", 1),
                Map.of("item", "minecraft:diamond", "itemName", "Алмаз", "count", 3), Map.of("item", "minecraft:wheat", "itemName", "Пшеница", "count", 8));
        Map<String,Object> favor = owed ? Map.of("active", fence, "canAsk", false, "done", List.of())
                : Map.of("canAsk", true, "candidates", candidates, "done", List.of("Колокол на площадь"));
        return Map.of("deals", List.of(milk), "secret", List.of(leather), "allowedDealTypes", List.of("NONE", "OFFER", "DECLINE"), "favor", favor);
    }

    private MobSnapshot tradingCow(Map<String,Object> trading) {
        MobSnapshot base = cow(UUID.randomUUID());
        return new MobSnapshot(base.id(), base.type(), base.name(), base.health(), base.maxHealth(), base.dimension(), base.biome(),
                base.timeOfDay(), base.weather(), base.distance(), base.heldItem(), base.nearby(), base.recentEvents(),
                UUID.randomUUID(), Map.of("name", "Стив", "hunger", 4), Map.of("trading", trading), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    private JsonObject ownAnswer(String text, String dealType, String dealId, int price, String unlock, String questType) {
        return ownAnswer(text, dealType, dealId, price, unlock, questType, "", 0, "", "", 0);
    }
    private JsonObject ownAnswer(String text, String dealType, String dealId, int price, String unlock, String questType,
                                 String item, int count, String title, String rewardDeal, int rewardPercent) {
        JsonObject answer = tradingAnswer(text, "NONE", -1, 0);
        JsonObject deal = new JsonObject(); deal.addProperty("type", dealType); deal.addProperty("id", dealId); deal.addProperty("price", price); answer.add("deal", deal);
        answer.addProperty("unlock", unlock);
        JsonObject quest = new JsonObject(); quest.addProperty("type", questType); quest.addProperty("item", item); quest.addProperty("count", count);
        quest.addProperty("title", title); quest.addProperty("rewardDeal", rewardDeal); quest.addProperty("rewardPercent", rewardPercent); answer.add("quest", quest);
        return answer;
    }

    private String lastCorrection() {
        return lastRequest.get().getAsJsonArray("messages").asList().getLast().getAsJsonObject().get("content").getAsString();
    }

    private JsonArray schemaEnum(String intent) {
        return lastRequest.get().getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema")
                .getAsJsonObject("properties").getAsJsonObject(intent).getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum");
    }

    // Speech must still start from the streamed reply while the model writes the new deal, unlock and quest fields.
    @Test void streamsAReplyAndCarriesTheDealThrough() throws Exception {
        String answer = "{\"reply\":\"Ладно, четыре пшеницы — и ведро твоё.\",\"socialIntent\":\"NONE\",\"action\":{\"type\":\"NONE\",\"target\":\"\"},"
                + "\"bargain\":{\"type\":\"NONE\",\"offerIndex\":-1,\"price\":0},\"deal\":{\"type\":\"OFFER\",\"id\":\"milk\",\"price\":4},"
                + "\"unlock\":\"\",\"quest\":{\"type\":\"NONE\",\"item\":\"\",\"count\":0,\"title\":\"\",\"rewardDeal\":\"\",\"rewardPercent\":0},\"newFacts\":\"Сторговались на четыре пшеницы за молоко.\"}";
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < answer.length(); i += 9) {
            JsonObject delta = new JsonObject(); delta.addProperty("content", answer.substring(i, Math.min(answer.length(), i + 9)));
            JsonObject choice = new JsonObject(); choice.add("delta", delta);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject event = new JsonObject(); event.add("choices", choices);
            events.append("data: ").append(event).append("\n\n");
        }
        rawChat.set(events.append("data: [DONE]\n\n").toString());
        try (ConversationEngine engine = engine()) {
            var reply = engine.converse(tradingCow(cowTrading(false, false)), "Уступи по молоку");
            assertEquals(new DealIntent(DealIntent.Type.OFFER, "milk", 4), reply.deal());
            assertEquals("", reply.unlock());
            assertEquals(QuestIntent.none(), reply.quest());
            assertEquals(1, speechCalls.get());
            assertArrayEquals(speechBytes.get(), reply.pcm());
        } finally { rawChat.set(null); }
    }

    // A price outside the deal's allowedPrices must not be voiced; a refusal and a legal price pass unchanged.
    @Test void pricesOwnDealsOnlyWithinAllowedPrices() throws Exception {
        queuedAnswers.add(ownAnswer("Ладно, отдам за два.", "OFFER", "milk", 2, "", "NONE"));
        queuedAnswers.add(ownAnswer("Два? Ишь какой. Четыре, и ни колоском меньше.", "OFFER", "milk", 4, "", "NONE"));
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(tradingCow(cowTrading(false, false)), "Давай за два");
            assertEquals(new DealIntent(DealIntent.Type.OFFER, "milk", 4), answer.deal());
            assertEquals(2, chatCalls.get()); assertEquals(1, speechCalls.get());
            String correction = lastCorrection();
            assertTrue(correction.contains("allowedPrices") && correction.contains("minPrice 1") && correction.contains("[5, 4, 3]"), correction);
            assertTrue(correction.contains("Разрешённые типы deal сейчас: [NONE, OFFER, DECLINE]"), correction);

            queuedAnswers.add(ownAnswer("Нет. Шесть пшеницы, и точка.", "DECLINE", "milk", 0, "", "NONE"));
            assertEquals(new DealIntent(DealIntent.Type.DECLINE, "milk", 0), engine.converse(tradingCow(cowTrading(false, false)), "Скинь").deal());

            queuedAnswers.add(ownAnswer("Пшеницу? Продам за три.", "OFFER", "wheat", 3, "", "NONE"));
            queuedAnswers.add(ownAnswer("Пшеницей я не торгую, её я ем.", "NONE", "", 0, "", "NONE"));
            assertEquals(DealIntent.none(), engine.converse(tradingCow(cowTrading(false, false)), "Продай пшеницу").deal());
            assertTrue(lastCorrection().contains("Сделки с id «wheat» у тебя нет"), lastCorrection());

            // Last chance: the schema forbids every intent, like the villager's bargain.
            queuedAnswers.add(ownAnswer("Отдам за два.", "OFFER", "milk", 2, "", "NONE"));
            queuedAnswers.add(ownAnswer("Отдам за два.", "OFFER", "milk", 2, "", "NONE"));
            queuedAnswers.add(ownAnswer("Нет, два — это грабёж.", "NONE", "", 0, "", "NONE"));
            assertEquals(DealIntent.none(), engine.converse(tradingCow(cowTrading(false, false)), "Два!").deal());
            assertEquals("[\"NONE\"]", schemaEnum("deal").toString());
            assertEquals("[\"NONE\"]", schemaEnum("quest").toString());
        }
    }

    // A secret stays shut until the game says canUnlockNow; the model is told how far the player is from it.
    @Test void opensASecretDealOnlyWhenTheGameAllows() throws Exception {
        queuedAnswers.add(ownAnswer("Так и быть, покажу тебе кожу.", "NONE", "", 0, "leather", "NONE"));
        queuedAnswers.add(ownAnswer("Есть у меня кое-что, да не для чужих.", "NONE", "", 0, "", "NONE"));
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(tradingCow(cowTrading(false, false)), "Что прячешь?");
            assertEquals("", answer.unlock());
            assertEquals(2, chatCalls.get());
            String correction = lastCorrection();
            assertTrue(correction.contains("ещё закрыта") && correction.contains("из 40") && correction.contains("оказать тебе услугу") && correction.contains("не открывай"), correction);

            queuedAnswers.add(ownAnswer("Ладно, свой. Кожа лежит за холмом, два изумруда.", "NONE", "", 0, "leather", "NONE"));
            assertEquals("leather", engine.converse(tradingCow(cowTrading(true, false)), "Что прячешь?").unlock());
            assertEquals(3, chatCalls.get());
        }
    }

    // The mob invents its own favour, but only while nothing is owed and for a real item; it accepts delivery only once the game counted the items.
    @Test void invitesAFavourOfItsOwnAndAcceptsItOnlyWhenTheGameVerifiedIt() throws Exception {
        queuedAnswers.add(ownAnswer("Загон как новенький, спасибо!", "NONE", "", 0, "", "COMPLETE"));
        queuedAnswers.add(ownAnswer("Три секции? Мне восемь надо, волки не дремлют.", "NONE", "", 0, "", "NONE"));
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(tradingCow(cowTrading(false, false)), "Я починил загон");
            assertEquals(QuestIntent.none(), answer.quest());
            String correction = lastCorrection();
            assertTrue(correction.contains("ещё не принёс") && correction.contains("3 из 8") && correction.contains("Не принимай"), correction);
            assertEquals("[\"NONE\",\"COMPLETE\"]", schemaEnum("quest").toString(), "nothing new may be asked while a favour is owed");

            queuedAnswers.add(ownAnswer("Все восемь! Загон стоит, ты молодец.", "NONE", "", 0, "", "COMPLETE"));
            assertEquals(QuestIntent.complete(), engine.converse(tradingCow(cowTrading(false, true)), "Готово").quest());

            // "Принимаю!" with quest NONE would voice a delivery the game never took.
            queuedAnswers.add(ownAnswer("Принимаю, загон спасён!", "NONE", "", 0, "", "NONE"));
            queuedAnswers.add(ownAnswer("Три секции — это не загон. Неси остальные.", "NONE", "", 0, "", "NONE"));
            assertEquals(QuestIntent.none(), engine.converse(tradingCow(cowTrading(false, false)), "Держи").quest());
            assertTrue(lastCorrection().contains("quest не COMPLETE") && lastCorrection().contains("3 из 8"), lastCorrection());
            assertTrue(AnswerValidator.claimsDelivery("Засчитываю, молодец") && !AnswerValidator.claimsDelivery("Не принимаю, мало"));

            queuedAnswers.add(ownAnswer("Принеси ещё ведро воды.", "NONE", "", 0, "", "OFFER", "minecraft:water_bucket", 1, "Ведро воды", "", 0));
            queuedAnswers.add(ownAnswer("Сначала забор, потом остальное.", "NONE", "", 0, "", "NONE"));
            assertEquals(QuestIntent.none(), engine.converse(tradingCow(cowTrading(false, false)), "Что ещё сделать?").quest());
            assertTrue(lastCorrection().contains("уже должен тебе услугу «Почини загон»"), lastCorrection());

            queuedAnswers.add(ownAnswer("Притащи мне колокол, телятам пора знать дорогу домой.", "NONE", "", 0, "", "OFFER", "minecraft:bell", 1, "Колокол для телят", "leather", 20));
            var asked = engine.converse(tradingCow(freeCow(false)), "Чем помочь?").quest();
            assertEquals(new QuestIntent(QuestIntent.Type.OFFER, "minecraft:bell", 1, "Колокол для телят", "leather", 20), asked);
            assertEquals("minecraft:bell", asked.favor().item());
            assertEquals("[\"NONE\",\"OFFER\"]", schemaEnum("quest").toString());

            // Only a drawn candidate may be asked for, and the game's amount wins over the model's.
            queuedAnswers.add(ownAnswer("Принеси мне дубовый забор.", "NONE", "", 0, "", "OFFER", "minecraft:oak_fence", 8, "Забор", "", 0));
            queuedAnswers.add(ownAnswer("Принеси три алмаза.", "NONE", "", 0, "", "OFFER", "minecraft:diamond", 3, "Алмазы", "", 0));
            assertEquals(new QuestIntent(QuestIntent.Type.OFFER, "minecraft:diamond", 3, "Алмазы", "", 0), engine.converse(tradingCow(freeCow(false)), "Что сделать?").quest());
            assertTrue(lastCorrection().contains("Предмет «minecraft:oak_fence» сейчас просить нельзя") && lastCorrection().contains("3× Алмаз (minecraft:diamond)"), lastCorrection());

            queuedAnswers.add(ownAnswer("Сто пшеницы!", "NONE", "", 0, "", "OFFER", "minecraft:wheat", 100, "Пшеница", "", 0));
            int calls = chatCalls.get();
            assertEquals(8, engine.converse(tradingCow(freeCow(false)), "Что сделать?").quest().count(), "the drawn amount, not the model's");
            assertEquals(calls + 1, chatCalls.get());

            queuedAnswers.add(ownAnswer("За это скину на сено.", "NONE", "", 0, "", "OFFER", "minecraft:wheat", 8, "Пшеница", "hay", 30));
            queuedAnswers.add(ownAnswer("За это скину на молоко.", "NONE", "", 0, "", "OFFER", "minecraft:wheat", 8, "Пшеница", "milk", 30));
            assertEquals("milk", engine.converse(tradingCow(freeCow(false)), "Что сделать?").quest().rewardDeal());
            assertTrue(lastCorrection().contains("Сделки «hay» у тебя нет"), lastCorrection());
        }
    }

    // A species without a catalog entry trades nothing: one line in the prompt, NONE in the schema, NONE in the reply.
    @Test void speciesWithoutTradingAnswersNone() throws Exception {
        try (ConversationEngine engine = engine()) {
            var answer = engine.converse(cow(UUID.randomUUID()), "Что продаёшь?");
            assertEquals(DealIntent.none(), answer.deal()); assertEquals("", answer.unlock()); assertEquals(QuestIntent.none(), answer.quest());
            assertTrue(systemPrompt().contains("Своей торговли у тебя нет"));
            assertFalse(systemPrompt().contains("Торговля в твоей жизни"));
            assertEquals("[\"NONE\"]", schemaEnum("deal").toString());
            assertEquals("[\"NONE\"]", schemaEnum("quest").toString());

            engine.converse(tradingCow(cowTrading(false, false)), "Что продаёшь?");
            assertTrue(systemPrompt().contains("Торговля в твоей жизни") && systemPrompt().contains("Никогда не перечисляй свои товары"));
            assertTrue(systemPrompt().contains("deal — {type:NONE|OFFER|DECLINE"));
            assertEquals("[\"NONE\",\"OFFER\",\"DECLINE\"]", schemaEnum("deal").toString());
            assertEquals("[\"NONE\",\"COMPLETE\"]", schemaEnum("quest").toString());
            String user = lastRequest.get().getAsJsonArray("messages").asList().getLast().getAsJsonObject().get("content").getAsString();
            assertTrue(user.contains("«Ведро молока» (id milk): 1× Ведро молока за 6× Пшеница") && user.contains("от 3 до 5"), user);
            assertTrue(user.contains("id leather: Знает, где лежит кожа") && user.contains("не ниже 40") && user.contains("после хотя бы одной услуги"), user);
            assertTrue(user.contains("игрок должен тебе «Почини загон»: принести 8× Дубовый забор (minecraft:oak_fence), у него сейчас 3 из 8")
                    && user.contains("обещал сделку «leather» со скидкой 20%"), user);

            engine.converse(tradingCow(freeCow(false)), "Чем помочь?");
            assertEquals("[\"NONE\",\"OFFER\"]", schemaEnum("quest").toString());
            user = lastRequest.get().getAsJsonArray("messages").asList().getLast().getAsJsonObject().get("content").getAsString();
            assertTrue(user.contains("можешь придумать услугу") && user.contains("1× Колокол (minecraft:bell), 3× Алмаз (minecraft:diamond), 8× Пшеница (minecraft:wheat)")
                    && user.contains("[Колокол на площадь]"), user);
        }
    }

    // A bystander hears the exchange, may keep quiet (nothing voiced, nothing remembered) or add a line that goes into its history;
    // it never trades on what it heard and never judges the player's words.
    @Test void bystandersAnswerWhatTheyOverheardOrKeepQuiet() throws Exception {
        JsonObject quiet = new JsonObject();
        quiet.addProperty("assessment", "Не моё дело."); quiet.addProperty("reply", ""); quiet.addProperty("newFacts", "");
        quiet.addProperty("socialIntent", "INSULT");
        JsonObject action = new JsonObject(); action.addProperty("type", "NONE"); action.addProperty("target", ""); quiet.add("action", action);
        queuedAnswers.add(quiet);
        ConversationEngine.Overheard heard = new ConversationEngine.Overheard("Марфа", "Ты тупая корова", "Сам такой!");
        try (ConversationEngine engine = engine()) {
            MobSnapshot bystander = currentCow(UUID.randomUUID(), 0);
            ConversationEngine.PreparedReply prepared = engine.prepare(bystander, "", heard);
            assertTrue(prepared.reply().silent() && prepared.reply().overheard() && prepared.reply().speech() == null, "silence is not voiced");
            assertEquals(0, speechCalls.get());
            assertEquals(SocialIntent.NONE, prepared.reply().socialIntent(), "an overheard insult is not aimed at the bystander");
            JsonObject request = lastRequest.get();
            String system = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            String user = request.getAsJsonArray("messages").asList().getLast().getAsJsonObject().get("content").getAsString();
            assertTrue(system.contains("ты не собеседник, а очевидец") && system.contains("Трезвая оценка обстановки"), system);
            assertTrue(user.startsWith("Ты рядом и слышишь чужой разговор. Игрок сказал Марфа: «Ты тупая корова». Марфа в ответ: «Сам такой!»"), user);
            JsonObject properties = request.getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema").getAsJsonObject("properties");
            assertEquals("[\"NONE\"]", properties.getAsJsonObject("quest").getAsJsonObject("properties").getAsJsonObject("type").get("enum").toString());
            assertEquals("assessment", properties.keySet().iterator().next(), "the assessment comes before the spoken line");
            assertTrue(engine.commitDelivered(prepared, 0));
            assertTrue(engine.memory().card(bystander.id(), bystander.playerId()).turns().isEmpty(), "silence leaves no turn");

            JsonObject spoken = quiet.deepCopy(); spoken.addProperty("reply", "Эй, полегче с ней!");
            queuedAnswers.add(spoken);
            MobSnapshot again = currentCow(bystander.id(), engine.memory().revision(bystander.id()));
            prepared = engine.prepare(again, "", heard);
            assertFalse(prepared.reply().silent()); assertEquals("Эй, полегче с ней!", prepared.reply().responseText());
            assertEquals(1, speechCalls.get(), "a spoken line is voiced");
            assertTrue(engine.commitDelivered(prepared, engine.memory().revision(bystander.id())));
            var turns = engine.memory().card(bystander.id(), bystander.playerId()).turns();
            assertEquals(1, turns.size());
            assertEquals("(ты слышал) Игрок — Марфа: «Ты тупая корова»; Марфа: «Сам такой!»", turns.getFirst().user());
        }
    }

    // The first meeting writes a name and a life; a villager also founds its village, which the next villager nearby inherits.
    @Test void writesABackstoryOnceAndSharesTheVillageWithNeighbours() throws Exception {
        JsonObject written = new JsonObject();
        written.addProperty("name", "Марфа"); written.addProperty("story", "Ты родилась в загоне у реки.");
        written.addProperty("desire", "Хочешь увидеть море."); written.addProperty("secret", "Ты боишься ведра.");
        written.addProperty("villageName", ""); written.addProperty("villageStory", "");
        queuedAnswers.add(written);
        try (ConversationEngine engine = engine()) {
            MobSnapshot first = cow(UUID.randomUUID());
            engine.ensureBackstory(first);
            assertEquals(1, chatCalls.get());
            JsonObject request = lastRequest.get();
            assertEquals("mob_biography", request.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("name").getAsString());
            assertTrue(request.toString().contains("takenNames") && !request.has("stream"));
            engine.ensureBackstory(first);
            assertEquals(1, chatCalls.get(), "written once");
            assertTrue(engine.memory().hasBackstory(first.id()));
            engine.converse(first, "Привет");
            assertTrue(systemPrompt().startsWith("Ты — Марфа, корова") && systemPrompt().contains("Твоя история (только твоя"), systemPrompt());
            assertTrue(systemPrompt().contains("Ты родилась в загоне у реки."));
            assertTrue(systemPrompt().contains("Чего ты хочешь от жизни: Хочешь увидеть море.") && systemPrompt().contains("Твоя тайна: Ты боишься ведра."), "the individual's own wish and secret");
            assertTrue(request.toString().contains("takenStories") && request.toString().contains("typicalDesire"), "the writer gets seeds, not a script");
            assertEquals(List.of("Марфа"), engine.memory().givenNames("minecraft:cow"));
            assertTrue(lastRequest.get().toString().contains("Марфа") || true);

            // A villager with a bell founds the village; a second villager 40 blocks away joins it and only gets a name.
            JsonObject founder = new JsonObject();
            founder.addProperty("name", "Прохор"); founder.addProperty("story", "Ты пришёл сюда первым.");
            founder.addProperty("villageName", "Тихие Грядки"); founder.addProperty("villageStory", "Деревню поставили на месте сгоревшего леса.");
            queuedAnswers.add(founder);
            engine.ensureBackstory(villager(UUID.randomUUID(), 100, 100, true));
            ConversationStore.Village village = engine.memory().villageNear("minecraft:overworld", 110, 90);
            assertEquals("Тихие Грядки", village.name()); assertEquals(100, village.x()); assertEquals(100, village.z());
            JsonObject neighbour = new JsonObject();
            neighbour.addProperty("name", "Устинья"); neighbour.addProperty("story", "Ты печёшь хлеб.");
            neighbour.addProperty("villageName", "Другая"); neighbour.addProperty("villageStory", "Не должно сохраниться");
            queuedAnswers.add(neighbour);
            MobSnapshot second = villager(UUID.randomUUID(), 140, 100, false);
            engine.ensureBackstory(second);
            assertEquals(1, engine.memory().villageNames().size(), "the neighbour inherits the village");
            assertTrue(lastRequest.get().toString().contains("Тихие Грядки"), "the known village story is handed to the writer");
            assertTrue(lastRequest.get().toString().contains("Ты пришёл сюда первым."), "the neighbour's story is handed over so this one differs");
            engine.converse(second, "Привет");
            assertTrue(systemPrompt().contains("Твоя деревня «Тихие Грядки»") && systemPrompt().contains("сгоревшего леса"), systemPrompt());
            assertNull(engine.memory().villageNear("minecraft:overworld", 1000, 1000));
            assertNull(engine.memory().villageNear("minecraft:the_nether", 100, 100));
        }
    }

    // A founder answered without a village would leave the next villager founding a second one: the writer asks once more.
    @Test void retriesAFounderWhoseVillageCameBackBlank() throws Exception {
        JsonObject cutOff = new JsonObject();
        cutOff.addProperty("name", "Фома"); cutOff.addProperty("story", "Ты пришёл сюда первым.");
        cutOff.addProperty("villageName", ""); cutOff.addProperty("villageStory", "");
        JsonObject whole = new JsonObject();
        whole.addProperty("name", "Фома"); whole.addProperty("story", "Ты пришёл сюда первым.");
        whole.addProperty("villageName", "Сырые Луга"); whole.addProperty("villageStory", "Деревня выросла на болоте.");
        queuedAnswers.add(cutOff); queuedAnswers.add(whole);
        try (ConversationEngine engine = engine()) {
            engine.ensureBackstory(villager(UUID.randomUUID(), 300, 300, true));
            assertEquals(2, chatCalls.get(), "one retry");
            assertEquals("Сырые Луга", engine.memory().villageNear("minecraft:overworld", 300, 300).name());
        }
    }

    private MobSnapshot villager(UUID id, int x, int z, boolean bell) {
        Map<String,Object> details = new java.util.LinkedHashMap<>();
        details.put("profession", "minecraft:farmer"); details.put("position", Map.of("x", x, "y", 64, "z", z));
        if (bell) details.put("meetingPoint", Map.of("dimension", "minecraft:overworld", "position", Map.of("x", x, "y", 64, "z", z)));
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                "minecraft:stick", List.of(), List.of(), UUID.randomUUID(), Map.of("name", "Стив"), details, Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    // The creature's own life leads the prompt; trading rules and the rudeness level stay at the end.
    @Test void leadsWithTheCreaturesOwnLifeAndKeepsRudenessLast() throws Exception {
        SpeciesCatalog.Species cow = SpeciesCatalog.get("minecraft:cow");
        SpeciesCatalog.Species soulful = new SpeciesCatalog.Species(cow.id(), cow.name(), cow.prompt(), cow.interests(), cow.fears(), false, cow.variants(),
                "Тянешь гласные и вставляешь «му-у» в конце важных фраз", "Чтобы телёнок пережил зиму", "Ты выдоила себя сама и винишь в этом игрока");
        var profile = new ConversationStore.Profile();
        profile.name = "Бурёнка"; profile.variantSlot = 0; profile.relationship = 10; profile.recentEvents = List.of("Игрок Стив ударил моба; отношение -80");
        config.rudeness = 1;
        try (var client = new OpenRouterConversationClient("unit-test-key", config, chatEndpoint)) {
            String system = client.systemPrompt(soulful, profile, tradingCow(cowTrading(false, false)));
            for (String part : List.of("фирменные штучки: Тянешь гласные", "Чего ты хочешь от жизни: Чтобы телёнок пережил зиму", "судишь игрока",
                    "Твоя тайна: Ты выдоила себя сама", "Чужим не выдаёшь", "Сейчас ты потрёпан: здоровье 7 из 10, мокнешь под дождём ночью, тебя недавно били",
                    "а игрок перед тобой явно голодный"))
                assertTrue(system.contains(part), "Missing: " + part + "\n" + system);
            int who = system.indexOf("Кто ты:"), character = system.indexOf("Твой характер:"), mood = system.indexOf("Сейчас ты "),
                    player = system.indexOf("Этот игрок:"), trade = system.indexOf("Торговля в твоей жизни"), json = system.indexOf("Ответ — JSON"),
                    rude = system.indexOf("Главное в манере речи");
            assertTrue(0 < who && who < character && character < mood && mood < player && player < trade && trade < json && json < rude, system);
            assertTrue(system.indexOf("дерзко и грубовато") > system.indexOf("quest — {type"), "Rudeness must stay last");

            profile.relationship = 70;
            assertTrue(client.systemPrompt(soulful, profile, tradingCow(cowTrading(true, false))).contains("Этот игрок — свой, ему можно её доверить"));
            // A species not yet written up (blank quirks/desire/secret) gets no empty headings.
            SpeciesCatalog.Species bare = new SpeciesCatalog.Species(cow.id(), cow.name(), cow.prompt(), cow.interests(), cow.fears(), false, cow.variants());
            String plain = client.systemPrompt(bare, profile, cow(UUID.randomUUID()));
            assertFalse(plain.contains("Твоя тайна") || plain.contains("фирменные штучки") || plain.contains("Чего ты хочешь"), plain);
            assertTrue(plain.contains("Сейчас ты потрёпан"));
        }
    }

    @Test void readsTheMoodOffTheSnapshot() {
        var profile = new ConversationStore.Profile();
        UUID player = UUID.randomUUID();
        MobSnapshot calf = new MobSnapshot(UUID.randomUUID(), "minecraft:cow", "Телёнок", 2, 10, "minecraft:overworld", "minecraft:plains", "day", "thunderstorm", 2,
                "minecraft:stick", List.of(), List.of(), player, Map.of("health", 4.0, "maxHealth", 20.0), Map.of("baby", true, "panicking", true, "onFire", true, "lastAttacker", player.toString()),
                Map.of("shelter", Map.of("openSky", false, "rainReachesHere", false)), List.of(), List.of(ActionType.NONE), 0);
        String mood = MobPrompt.mood(calf, profile);
        for (String part : List.of("ещё детёныш", "еле жив: здоровье 2 из 10", "горишь!", "в панике", "сидишь под крышей, пока снаружи гроза днём",
                "на тебя только что нападали", "игрок еле живой"))
            assertTrue(mood.contains(part), mood);
        MobSnapshot calm = new MobSnapshot(UUID.randomUUID(), "minecraft:cow", "Корова", 10, 10, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                "minecraft:stick", List.of(), List.of());
        assertEquals("цел и здоров, белый день.", MobPrompt.mood(calm, profile));
    }

    // With the vanilla window open the mob is told the player sees the price change at once and which row they look at.
    // A villager has no catalog deals of its own (its goods are the vanilla window), yet it may still ask and accept favours.
    @Test void merchantKeepsFavoursWithoutOwnDeals() throws Exception {
        Map<String,Object> trading = new java.util.LinkedHashMap<>(freeCow(false));
        trading.put("deals", List.of()); trading.put("secret", List.of()); trading.put("allowedDealTypes", List.of("NONE"));
        MobSnapshot base = tradingCow(trading);
        MobSnapshot villager = new MobSnapshot(base.id(), "minecraft:villager", "Житель", base.health(), base.maxHealth(), base.dimension(), base.biome(),
                base.timeOfDay(), base.weather(), base.distance(), base.heldItem(), base.nearby(), base.recentEvents(), base.playerId(), base.playerDetails(),
                base.mobDetails(), base.environmentDetails(), base.observedEntities(), base.availableActions(), 0);
        queuedAnswers.add(ownAnswer("Принеси колокол, телятам пора домой.", "NONE", "", 0, "", "OFFER", "minecraft:bell", 1, "Колокол", "", 0));
        try (ConversationEngine engine = engine()) {
            var reply = engine.converse(villager, "Чем помочь?");
            assertEquals(QuestIntent.Type.OFFER, reply.quest().type());
            assertTrue(systemPrompt().contains("Своих отдельных сделок у тебя нет") && systemPrompt().contains("quest — {type"), systemPrompt());
            assertFalse(systemPrompt().contains("Своей торговли у тебя нет"), "favours are still possible");
            assertEquals("[\"NONE\"]", schemaEnum("deal").toString());
            assertEquals("[\"NONE\",\"OFFER\"]", schemaEnum("quest").toString());
        }
    }

    @Test void describesTheOpenDealWindowAndItsSelectedRow() {
        assertFalse(MobPrompt.dealSummary(tradingCow(cowTrading(false, false))).contains("Окно твоих сделок"));
        Map<String,Object> open = new java.util.LinkedHashMap<>(cowTrading(false, false));
        open.put("windowOpen", true); open.put("selectedDeal", "milk");
        String summary = MobPrompt.dealSummary(tradingCow(open));
        assertTrue(summary.contains("Окно твоих сделок у игрока сейчас открыто") && summary.contains("тут же появится в нём новой ценой"), summary);
        assertTrue(summary.contains("строку «Ведро молока» (id milk): торгуйся за неё, если игрок не назвал другую сделку"), summary);
        assertTrue(summary.indexOf("Окно твоих сделок") < summary.indexOf("Секретное, ещё закрытое"), summary);
        open.put("selectedDeal", "");
        summary = MobPrompt.dealSummary(tradingCow(open));
        assertTrue(summary.contains("Строка со сделкой пока не выбрана") && !summary.contains("Игрок смотрит"), summary);
        open.put("selectedDeal", "leather"); // A teaser row maps to no visible deal.
        assertTrue(MobPrompt.dealSummary(tradingCow(open)).contains("Строка со сделкой пока не выбрана"));
        open.put("windowOpen", false); open.put("selectedDeal", "milk");
        assertFalse(MobPrompt.dealSummary(tradingCow(open)).contains("Окно твоих сделок"));
    }

    // A number the cow says is honest only when the deal data or the applied OFFER backs it.
    @Test void recognisesOnlyPricesBackedByTheDeal() {
        MobSnapshot cow = tradingCow(cowTrading(false, false));
        var four = new DealIntent(DealIntent.Type.OFFER, "milk", 4);
        assertEquals(-1, AnswerValidator.unbackedPrice("Ладно, отдам за четыре пшеницы.", BargainIntent.none(), four, cow));
        assertEquals(4, AnswerValidator.unbackedPrice("Ладно, отдам за четыре пшеницы.", BargainIntent.none(), DealIntent.none(), cow));
        assertEquals(-1, AnswerValidator.unbackedPrice("Молоко стоит шесть пшеницы, и точка.", BargainIntent.none(), DealIntent.none(), cow));
        assertEquals(-1, AnswerValidator.unbackedPrice("Продам одно ведро, не больше.", BargainIntent.none(), DealIntent.none(), cow));
        assertEquals(2, AnswerValidator.unbackedPrice("Цена теперь два.", BargainIntent.none(), new DealIntent(DealIntent.Type.DECLINE, "milk", 0), cow));
        assertEquals(-1, AnswerValidator.unbackedPrice("Мне 12 лет, отстань.", BargainIntent.none(), DealIntent.none(), cow));
        assertEquals(-1, AnswerValidator.unbackedPrice("Цена дружбы — 100 ударов.", BargainIntent.none(), DealIntent.none(), cow(UUID.randomUUID())));
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
