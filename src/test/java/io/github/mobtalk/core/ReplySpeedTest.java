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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The speed of a reply: the line reaches the player before the answer's tail, a silent model is asked again, and the player's
 * question never waits behind bystanders or background work. All against a local stub; no real provider is touched. */
class ReplySpeedTest {
    /** Long enough for the validator's echo check, which ignores lines of fewer than four content words. */
    private static final String REPLY = "Муу, привет. Трава сегодня сочная, дождик тёплый, телята резвятся.";
    @TempDir Path directory;
    private HttpServer server;
    private URI chatEndpoint, speechEndpoint;
    private final MobTalkConfig config = new MobTalkConfig();
    private final AtomicInteger chatCalls = new AtomicInteger(), speechCalls = new AtomicInteger(), held = new AtomicInteger();
    private final AtomicReference<String> reply = new AtomicReference<>("Муу, привет. Трава сегодня сочная, дождик тёплый, телята резвятся.");
    /** Streamed answers: the head (assessment and reply) goes out at once, the tail waits for this gate. */
    private final AtomicBoolean streaming = new AtomicBoolean();
    private volatile CountDownLatch tailGate = new CountDownLatch(0);
    /** How many of the next requests stay silent (headers and body) for this long before answering. */
    private final AtomicInteger stalls = new AtomicInteger();
    private volatile long stallMillis = 2500;
    /** Requests of bystanders and background work wait for this gate while the player's goes through. */
    private volatile CountDownLatch holdGate = new CountDownLatch(0);

    @BeforeEach void startProvider() throws IOException {
        SpeciesCatalog.Override cowVoices = new SpeciesCatalog.Override();
        cowVoices.voiceIds = new String[]{"LocalCowFixtureVoice0001", "LocalCowFixtureVoice0002", "LocalCowFixtureVoice0003"};
        config.speciesOverrides.put("minecraft:cow", cowVoices);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/chat", exchange -> {
            chatCalls.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (stalls.getAndUpdate(n -> Math.max(0, n - 1)) > 0) Thread.sleep(stallMillis);
                boolean other = request.contains("слышишь чужой разговор") || request.contains("cheap/model");
                if (other) { held.incrementAndGet(); holdGate.await(10, TimeUnit.SECONDS); }
            } catch (InterruptedException ignored) { }
            JsonObject content = new JsonObject();
            content.addProperty("assessment", "спокойно");
            content.addProperty("reply", reply.get());
            content.addProperty("socialIntent", "NONE");
            JsonObject action = new JsonObject(); action.addProperty("type", "NONE"); action.addProperty("target", ""); content.add("action", action);
            content.addProperty("memory", "Игрок поздоровался.");
            if (streaming.get() && request.contains("\"stream\":true")) {
                String whole = content.toString();
                int cut = whole.indexOf("\"socialIntent\"") - 1; // The reply's closing quote is in, the comma and the tail are not.
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(event(whole.substring(0, cut))); out.flush();
                    try { tailGate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    out.write(event(whole.substring(cut))); out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                }
                return;
            }
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", content.toString());
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonArray choices = new JsonArray(); choices.add(choice);
            JsonObject response = new JsonObject(); response.add("choices", choices);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/speech", exchange -> {
            speechCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = new byte[ConversationEngine.PREBUFFER_BYTES];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        chatEndpoint = URI.create(base + "/chat");
        speechEndpoint = URI.create(base + "/speech");
    }

    private static byte[] event(String content) {
        JsonObject delta = new JsonObject(); delta.addProperty("content", content);
        JsonObject choice = new JsonObject(); choice.add("delta", delta);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject event = new JsonObject(); event.add("choices", choices);
        return ("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    @AfterEach void stopProvider() throws IOException { tailGate.countDown(); holdGate.countDown(); server.stop(0); ConversationStore.closeWorld(directory); }

    private OpenRouterConversationClient llm() { return new OpenRouterConversationClient("unit-test-key", config, chatEndpoint); }
    private ConversationEngine engine() throws IOException {
        return new ConversationEngine(config, directory, llm(), new OpenRouterSpeechClient("unit-test-key", config, speechEndpoint));
    }
    private MobSnapshot cow(UUID id) {
        return new MobSnapshot(id, "minecraft:cow", "Бурёнка", 7, 10, "minecraft:overworld", "minecraft:meadow", "ночь", "дождь", 3.5,
                "minecraft:stick", List.of("minecraft:sheep"), List.of("Игрок подошёл к мобу"));
    }

    // The line and its voice are offered to the game while the model still writes the tail; the final reply is the same line,
    // voiced by that same single speech request.
    @Test void handsTheLineOverBeforeTheTailArrives() throws Exception {
        streaming.set(true); tailGate = new CountDownLatch(1);
        CompletableFuture<String> early = new CompletableFuture<>();
        AtomicReference<SpeechStream> earlyAudio = new AtomicReference<>();
        AtomicReference<String> earlyName = new AtomicReference<>();
        try (ConversationEngine engine = engine()) {
            MobSnapshot cow = cow(UUID.randomUUID());
            CompletableFuture<ConversationEngine.PreparedReply> prepared = CompletableFuture.supplyAsync(() -> {
                try {
                    return engine.prepare(cow, "Привет", null, null, (text, name, audio, claim) -> {
                        assertTrue(claim.getAsBoolean(), "the line is still valid when the game claims it");
                        earlyAudio.set(audio); earlyName.set(name); early.complete(text);
                    });
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            assertEquals(REPLY, early.get(10, TimeUnit.SECONDS));
            assertFalse(prepared.isDone(), "the answer's tail is still being written");
            assertTrue(earlyAudio.get().awaitPrebuffer(ConversationEngine.PREBUFFER_BYTES, 0), "the voice already started");
            assertEquals("Бурёнка", earlyName.get());
            tailGate.countDown();
            ConversationEngine.Reply result = prepared.get(10, TimeUnit.SECONDS).reply();
            assertEquals(REPLY, result.responseText());
            assertSame(earlyAudio.get(), result.speech(), "the delivery plays the very stream the early line started");
            assertEquals(1, speechCalls.get()); assertEquals(1, chatCalls.get());
            assertTrue(engine.commitDelivered(prepared.get(), cow.interactionRevision()));
        }
    }

    // A bystander, or a villager with goods to haggle about, gets no early line: those answers are spoken only once checked.
    @Test void tradersAndBystandersKeepTheOrdinaryPath() throws Exception {
        streaming.set(true);
        AtomicInteger offers = new AtomicInteger();
        ConversationEngine.EarlyListener listener = (text, name, audio, claim) -> offers.incrementAndGet();
        try (ConversationEngine engine = engine()) {
            engine.prepare(cow(UUID.randomUUID()), "", new ConversationEngine.Overheard("Марфа", "Привет", "И тебе привет"), null, listener);
            MobSnapshot seller = new MobSnapshot(UUID.randomUUID(), "minecraft:villager", "Ладко", 20, 20, "minecraft:overworld", "minecraft:plains",
                    "день", "ясно", 3.0, "", List.of(), List.of(), null, java.util.Map.of(), java.util.Map.of("offers", List.of(java.util.Map.of("buy",
                    java.util.Map.of("id", "minecraft:stick", "count", 32)))), java.util.Map.of(), List.of(), List.of(ActionType.NONE), 0);
            assertFalse(ConversationEngine.deliversEarly(null, seller, listener));
            assertTrue(ConversationEngine.deliversEarly(null, cow(UUID.randomUUID()), listener));
            assertFalse(ConversationEngine.deliversEarly(null, cow(UUID.randomUUID()), null));
        }
        assertEquals(0, offers.get());
    }

    // Once the player has the line, a tail the validator refuses (here: the mob repeating itself) is dropped, not corrected:
    // the spoken line stands, with no intents, and no second request is sent.
    @Test void aShownLineIsNeverCorrected() throws Exception {
        try (ConversationEngine engine = engine()) {
            MobSnapshot cow = cow(UUID.randomUUID());
            engine.converse(cow, "Привет");
            assertEquals(1, chatCalls.get());
            streaming.set(true); tailGate = new CountDownLatch(1);
            CompletableFuture<String> early = new CompletableFuture<>();
            CompletableFuture<ConversationEngine.PreparedReply> prepared = CompletableFuture.supplyAsync(() -> {
                try { return engine.prepare(cow, "Привет ещё раз", null, null, (text, name, audio, claim) -> { assertTrue(claim.getAsBoolean()); early.complete(text); }); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            assertEquals(REPLY, early.get(10, TimeUnit.SECONDS));
            tailGate.countDown();
            ConversationEngine.Reply result = prepared.get(10, TimeUnit.SECONDS).reply();
            assertEquals(REPLY, result.responseText());
            assertEquals(ActionIntent.none(), result.action());
            assertEquals(2, chatCalls.get(), "no correction after the line went out");
        }
    }

    // The game never claimed the line (nobody was there to show it): the refused answer is corrected as before, and the early
    // line is invalidated so a late claim fails.
    @Test void anUnshownLineIsStillCorrected() throws Exception {
        try (ConversationEngine engine = engine()) {
            MobSnapshot cow = cow(UUID.randomUUID());
            engine.converse(cow, "Привет");
            streaming.set(true);
            AtomicReference<java.util.function.BooleanSupplier> claim = new AtomicReference<>();
            assertThrows(IOException.class, () -> engine.prepare(cow, "Привет ещё раз", null, null, (text, name, audio, c) -> claim.set(c)),
                    "the stub keeps repeating the same line, so after two corrections the answer is refused");
            assertEquals(4, chatCalls.get(), "the first exchange and three attempts at the second");
            // The offer may or may not have reached the listener before the model withdrew the line; if it did, the claim fails.
            if (claim.get() != null) assertFalse(claim.get().getAsBoolean(), "a line the model withdrew can no longer be shown");
        }
    }

    // A model that takes the request and sends nothing is cancelled after the first-token wait and asked once more; a second
    // silence is the player's answer, well before the request timeout.
    @Test void aSilentModelIsAskedOnceMoreThenGivenUp() throws Exception {
        config.firstTokenSeconds = 1; config.requestTimeoutSeconds = 30;
        try (ConversationEngine engine = engine()) {
            stalls.set(1);
            long started = System.nanoTime();
            assertEquals(REPLY, engine.converse(cow(UUID.randomUUID()), "Привет").responseText());
            assertEquals(2, chatCalls.get(), "the stalled request was cancelled and sent again");
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(6));
            stalls.set(2); stallMillis = 3000;
            started = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> engine.converse(cow(UUID.randomUUID()), "Привет"));
            assertTrue(failure.getMessage().contains("не начала отвечать"), failure.getMessage());
            assertEquals(4, chatCalls.get());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(8), "two first-token waits, not the whole timeout");
        }
    }

    // Two bystanders and a background summary are in flight (one of them still queued behind the other two); the player's own
    // question goes through at once on its own lane instead of waiting for a free permit.
    @Test void thePlayersQuestionNeverWaitsBehindOthers() throws Exception {
        config.requestTimeoutSeconds = 5; config.backgroundModel = "cheap/model";
        holdGate = new CountDownLatch(1);
        OpenRouterConversationClient client = llm();
        try (ConversationEngine engine = new ConversationEngine(config, directory, client, new OpenRouterSpeechClient("unit-test-key", config, speechEndpoint))) {
            List<CompletableFuture<?>> others = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) others.add(CompletableFuture.runAsync(() -> {
                try { engine.prepare(cow(UUID.randomUUID()), "", new ConversationEngine.Overheard("Марфа", "Привет", "И тебе"), null, null); }
                catch (Exception ignored) { }
            }));
            JsonArray messages = new JsonArray(); messages.add(ReplySchema.message("user", "сводка"));
            JsonObject fields = new JsonObject(); fields.add("reply", ReplySchema.property("string"));
            others.add(CompletableFuture.runAsync(() -> {
                try { client.complete(client.backgroundBody(messages, 100, ReplySchema.format("summary", ReplySchema.objectSchema(fields, "reply")))); }
                catch (Exception ignored) { }
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (held.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(2, held.get(), "two bystanders hold the shared lanes; the background request queues behind them");
            long started = System.nanoTime();
            assertEquals(REPLY, engine.converse(cow(UUID.randomUUID()), "Привет").responseText());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "answered while the others were still held");
            holdGate.countDown();
            for (CompletableFuture<?> other : others) other.get(10, TimeUnit.SECONDS);
            assertEquals(3, held.get(), "the background request went through once a lane freed up");
        }
    }

    @Test void readsTheSpokenLineOutOfARefusedCompletion() {
        JsonObject content = new JsonObject(); content.addProperty("reply", "Муу."); content.addProperty("socialIntent", "INSULT");
        JsonObject message = new JsonObject(); message.addProperty("content", content.toString());
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject completion = new JsonObject(); completion.add("choices", choices);
        assertEquals("Муу.", OpenRouterConversationClient.spokenReply(completion.toString().getBytes(StandardCharsets.UTF_8)));
        message.addProperty("content", "{\"assessment\":\"x\",\"reply\":\"Му-у.\",\"socialIntent\":");
        assertEquals("Му-у.", OpenRouterConversationClient.spokenReply(completion.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals("", OpenRouterConversationClient.spokenReply("garbage".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(JsonParser.parseString("{}"));
    }
}
