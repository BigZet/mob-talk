package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Фейковый OpenRouter для тестов серверной логики (трек A): чат отвечает заданным JSON ответа моба, озвучка — заданным PCM;
 * считает вызовы и умеет задерживать ответ, чтобы проверить отмену на полпути. Движок собирается пакетным конструктором
 * {@link ConversationEngine}, поэтому фикстура живёт в пакете ядра. */
public final class FakeProvider implements AutoCloseable {
    private final HttpServer server;
    private final URI chat, speech;
    public final AtomicInteger chatCalls = new AtomicInteger(), speechCalls = new AtomicInteger();
    /** JSON ответа модели (поля reply, memory, socialIntent, action, bargain). */
    public volatile String answer = """
            {"reply":"За такие слова и торговаться не стану.","memory":"Игрок грубо потребовал скидку.",
             "socialIntent":"INSULT","action":{"type":"NONE","target":""},
             "bargain":{"type":"NONE","offerIndex":-1,"discount":0}}
            """;
    /** Задержка чата в миллисекундах: отмена на полпути. */
    public volatile long chatDelayMillis;
    /** PCM 24 кГц, который отдаёт озвучка. */
    public volatile byte[] pcm = new byte[] { 0, 0, 1, 0, 2, 0, 3, 0 };

    public FakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            chatCalls.incrementAndGet();
            try { Thread.sleep(chatDelayMillis); } catch (InterruptedException ignored) { }
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", com.google.gson.JsonParser.parseString(answer).getAsJsonObject().toString());
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
        server.createContext("/speech", exchange -> {
            exchange.getRequestBody().readAllBytes();
            speechCalls.incrementAndGet();
            byte[] bytes = pcm;
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        chat = URI.create(base + "/chat");
        speech = URI.create(base + "/speech");
    }

    /** Настройки с голосами жителей, чтобы озвучка шла через фейк. */
    public static MobTalkConfig config() {
        MobTalkConfig config = new MobTalkConfig();
        SpeciesCatalog.Override voices = new SpeciesCatalog.Override();
        voices.voiceIds = new String[] { "LocalDeliveryVoice00001", "LocalDeliveryVoice00002", "LocalDeliveryVoice00003" };
        config.speciesOverrides.put("minecraft:villager", voices);
        config.speciesOverrides.put("minecraft:cow", voices);
        return config;
    }

    public ConversationEngine engine(MobTalkConfig config, Path worldDirectory) throws IOException {
        return new ConversationEngine(config, worldDirectory,
                new OpenRouterConversationClient("unit-test-key", config, chat),
                new OpenRouterSpeechClient("unit-test-key", config, speech));
    }

    /** One recorded exchange: the mob becomes acquainted (labels, HUD, witnesses treat it as known). */
    public static void meet(ConversationStore store, MobSnapshot snapshot, String said, String reply) {
        store.observe(snapshot);
        store.commit(snapshot, said, reply, "", SocialIntent.KIND, ActionIntent.none(), System.currentTimeMillis(), null, "");
    }

    @Override public void close() { server.stop(0); }
}
