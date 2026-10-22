package io.github.mobtalk.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** OpenRouter STT по фейковому HTTP: разбор text и usage, повтор на запасной модели, ошибки понятным текстом (трек B). */
class OpenRouterTranscriberTest {
    private static final String KEY = "sk-or-v1-" + "0123456789abcdef".repeat(4);

    private record Reply(int status, String body) { }

    private record Fake(HttpServer server, List<JsonObject> requests, URI uri) implements AutoCloseable {
        @Override public void close() { server.stop(0); }
    }

    /** Ответ по модели запроса; статус 0 — не отвечать дольше ожидания. */
    private static Fake fake(Function<String, Reply> answer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        List<JsonObject> requests = new ArrayList<>();
        server.createContext("/stt", exchange -> {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            synchronized (requests) { requests.add(body); }
            Reply reply = answer.apply(body.get("model").getAsString());
            if (reply.status() == 0) {
                try { Thread.sleep(3_000); } catch (InterruptedException ignored) { }
                exchange.close();
                return;
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new Fake(server, requests, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stt"));
    }

    private static MobTalkConfig config() {
        MobTalkConfig config = new MobTalkConfig();
        config.sttTimeoutSeconds = 1;
        return config;
    }

    @Test void parsesTextAndUsageAndSendsBase64Wav() throws Exception {
        byte[] wav = {1, 2, 3, 4, 5};
        try (Fake fake = fake(model -> new Reply(200, "{\"text\":\" Привет, крипер \",\"usage\":{\"seconds\":8,\"cost\":0.0008}}"));
             OpenRouterTranscriber stt = new OpenRouterTranscriber(KEY, config(), fake.uri())) {
            OpenRouterTranscriber.Result result = stt.transcribe(wav, "wav16k", "ru");
            assertEquals("Привет, крипер", result.text());
            assertEquals(8, result.seconds());
            assertEquals(0.0008, result.usd(), 1e-9);
            assertEquals("mistralai/voxtral-mini-transcribe-3-0", result.model());
            JsonObject sent = fake.requests().getFirst();
            assertEquals("wav", sent.getAsJsonObject("input_audio").get("format").getAsString());
            assertArrayEquals(wav, Base64.getDecoder().decode(sent.getAsJsonObject("input_audio").get("data").getAsString()));
            assertEquals("ru", sent.get("language").getAsString());
        }
    }

    @Test void serverErrorAndTimeoutGoToFallbackModelOnce() throws Exception {
        try (Fake fake = fake(model -> model.startsWith("mistralai") ? new Reply(503, "{}") : new Reply(200, "{\"text\":\"запас\"}"));
             OpenRouterTranscriber stt = new OpenRouterTranscriber(KEY, config(), fake.uri())) {
            OpenRouterTranscriber.Result result = stt.transcribe(new byte[]{1}, "wav", "ru");
            assertEquals("запас", result.text());
            assertEquals("openai/whisper-large-v3", result.model());
            assertEquals(0, result.usd());
            assertEquals(2, fake.requests().size());
        }
        try (Fake fake = fake(model -> model.startsWith("mistralai") ? new Reply(0, "") : new Reply(200, "{\"text\":\"после таймаута\"}"));
             OpenRouterTranscriber stt = new OpenRouterTranscriber(KEY, config(), fake.uri())) {
            assertEquals("после таймаута", stt.transcribe(new byte[]{1}, "wav", "ru").text());
        }
    }

    @Test void moneyAndKeyErrorsAreNotRetriedAndReadable() throws Exception {
        try (Fake fake = fake(model -> new Reply(402, "{\"error\":\"no credits\"}"));
             OpenRouterTranscriber stt = new OpenRouterTranscriber(KEY, config(), fake.uri())) {
            IOException failure = assertThrows(IOException.class, () -> stt.transcribe(new byte[]{1}, "wav", "ru"));
            assertTrue(failure.getMessage().contains("кредиты"), failure.getMessage());
            assertEquals(1, fake.requests().size());
        }
        try (OpenRouterTranscriber stt = new OpenRouterTranscriber(null, config(), URI.create("http://127.0.0.1:1/stt"))) {
            IOException failure = assertThrows(IOException.class, () -> stt.transcribe(new byte[]{1}, "wav", "ru"));
            assertTrue(failure.getMessage().contains("нет ключа"), failure.getMessage());
        }
    }

    @Test void malformedAnswersAndFormatsAreRefused() throws Exception {
        try (Fake fake = fake(model -> new Reply(200, "<html>"));
             OpenRouterTranscriber stt = new OpenRouterTranscriber(KEY, config(), fake.uri())) {
            assertThrows(IOException.class, () -> stt.transcribe(new byte[]{1}, "wav", "ru"));
        }
        assertThrows(IOException.class, () -> OpenRouterTranscriber.providerFormat("flac"));
        assertEquals("ogg", OpenRouterTranscriber.providerFormat("opus"));
        assertFalse(OpenRouterTranscriber.body("m/x", new byte[]{1}, "wav", "auto").contains("language"));
    }
}
