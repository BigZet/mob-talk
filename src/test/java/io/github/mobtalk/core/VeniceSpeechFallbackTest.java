package io.github.mobtalk.core;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Venice speech that runs out of credits hands over to OpenRouter instead of leaving every reply unvoiced. */
class VeniceSpeechFallbackTest {
    private static final String VOICE = "21m00Tcm4TlvDq8ikWAM";

    @Test void balanceParsesVeniceAnswer() {
        VeniceBalance.Status empty = VeniceBalance.parse("{\"data\":{\"accessPermitted\":false,\"balances\":{\"USD\":0.0988,\"DIEM\":0,\"BUNDLED_CREDITS\":0}}}");
        assertFalse(empty.permitted());
        assertTrue(empty.low());
        assertEquals(0.0988, empty.total(), 1e-6);
        assertFalse(VeniceBalance.parse("{\"data\":{\"accessPermitted\":true,\"balances\":{\"USD\":4.5,\"DIEM\":1}}}").low());
        assertTrue(VeniceBalance.parse("{\"data\":{\"accessPermitted\":true,\"balances\":{\"USD\":0.02}}}").low());
        assertFalse(VeniceBalance.parse("not json").low(), "an unreadable answer must not switch providers");
    }

    @Test void creditsGoneHandsSpeechToOpenRouterForTheSession() throws Exception {
        AtomicInteger venice = new AtomicInteger(), openRouter = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/venice", exchange -> {
            venice.incrementAndGet();
            byte[] body = "{\"error\":\"Insufficient USD or Diem balance\"}".getBytes();
            exchange.sendResponseHeaders(402, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/openrouter", exchange -> {
            openRouter.incrementAndGet();
            byte[] pcm = new byte[48_000];
            exchange.sendResponseHeaders(200, pcm.length);
            exchange.getResponseBody().write(pcm);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            MobTalkConfig config = new MobTalkConfig();
            config.ttsProvider = "venice";
            config.veniceTtsModel = "tts-elevenlabs-turbo-v2-5";
            MobTalkConfig openRouterConfig = config.copy();
            openRouterConfig.ttsProvider = "openrouter";
            try (OpenRouterSpeechClient fallback = new OpenRouterSpeechClient("or-key", openRouterConfig, URI.create(base + "/openrouter"));
                 OpenRouterSpeechClient speech = new OpenRouterSpeechClient("venice-key", config, URI.create(base + "/venice"), fallback)) {
                OpenRouterSpeechClient.Audio first = speech.synthesizeWithFallback(VOICE, "", "Привет");
                assertEquals(48_000, first.pcm().length);
                assertTrue(speech.fellBack());
                assertEquals("OpenRouter TTS", speech.service());
                speech.synthesizeWithFallback(VOICE, "", "Ещё раз");
                assertEquals(1, venice.get(), "after HTTP 402 Venice is not asked again this session");
                assertEquals(2, openRouter.get());
            }
        } finally { server.stop(0); }
    }

    @Test void requestInFlightWhenBalanceCheckSwitchesStillGoesOver() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        OpenRouterSpeechClient[] holder = new OpenRouterSpeechClient[1];
        server.createContext("/venice", exchange -> {
            // The balance check lands while Venice is still answering this request.
            try { var field = OpenRouterSpeechClient.class.getDeclaredField("onFallback"); field.setAccessible(true); field.set(holder[0], true); }
            catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
            exchange.sendResponseHeaders(402, -1); exchange.close();
        });
        server.createContext("/openrouter", exchange -> { byte[] pcm = new byte[4_800]; exchange.sendResponseHeaders(200, pcm.length); exchange.getResponseBody().write(pcm); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            MobTalkConfig config = new MobTalkConfig();
            config.ttsProvider = "venice";
            config.veniceTtsModel = "tts-elevenlabs-turbo-v2-5";
            MobTalkConfig openRouterConfig = config.copy();
            openRouterConfig.ttsProvider = "openrouter";
            try (OpenRouterSpeechClient fallback = new OpenRouterSpeechClient("or-key", openRouterConfig, URI.create(base + "/openrouter"));
                 OpenRouterSpeechClient speech = new OpenRouterSpeechClient("venice-key", config, URI.create(base + "/venice"), fallback)) {
                holder[0] = speech;
                assertEquals(4_800, speech.synthesizeWithFallback(VOICE, "", "Клятва").pcm().length);
            }
        } finally { server.stop(0); }
    }

    @Test void rejectedVoiceStaysWithVenice() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger openRouter = new AtomicInteger();
        server.createContext("/venice", exchange -> { exchange.sendResponseHeaders(422, -1); exchange.close(); });
        server.createContext("/openrouter", exchange -> { openRouter.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            MobTalkConfig config = new MobTalkConfig();
            config.ttsProvider = "venice";
            config.veniceTtsModel = "tts-elevenlabs-turbo-v2-5";
            MobTalkConfig openRouterConfig = config.copy();
            openRouterConfig.ttsProvider = "openrouter";
            try (OpenRouterSpeechClient fallback = new OpenRouterSpeechClient("or-key", openRouterConfig, URI.create(base + "/openrouter"));
                 OpenRouterSpeechClient speech = new OpenRouterSpeechClient("venice-key", config, URI.create(base + "/venice"), fallback)) {
                assertThrows(java.io.IOException.class, () -> speech.synthesizeWithFallback(VOICE, "", "Привет"));
                assertFalse(speech.fellBack());
                assertEquals(0, openRouter.get());
            }
        } finally { server.stop(0); }
    }
}
