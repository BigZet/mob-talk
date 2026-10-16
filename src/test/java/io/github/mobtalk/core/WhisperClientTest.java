package io.github.mobtalk.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WhisperClientTest {
    // Encoding the audio as JSON or treating silence as a question would break the local worker contract.
    @Test void sendsRawWavAndReturnsEmptyTextForSilence() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>("{\"text\":\"  Привет, корова!  \"}");
        server.createContext("/transcribe", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            assertEquals("audio/wav", exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            MobTalkConfig config = new MobTalkConfig();
            config.whisperUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] wav = new byte[44];
            wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
            try (WhisperClient client = new WhisperClient(config)) {
                assertEquals("Привет, корова!", client.transcribe(wav));
                assertArrayEquals(wav, received.get());
                response.set("{\"text\":\"\"}");
                assertEquals("", client.transcribe(wav));
                response.set("{\"error\":\"secret-provider-detail\"}");
                IOException malformed = assertThrows(IOException.class, () -> client.transcribe(wav));
                assertFalse(malformed.toString().contains("secret-provider-detail"));
            }
        } finally { server.stop(0); }
    }

    // Removing bounds or loopback enforcement would send invalid/remote microphone data.
    @Test void rejectsOversizedAudioAndNonlocalEndpoint() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        try (WhisperClient client = new WhisperClient(config)) {
            assertThrows(IOException.class, () -> client.transcribe(new byte[2 * 1024 * 1024 + 1]));
            assertThrows(IOException.class, () -> client.transcribe(new byte[0]));
        }
        config.whisperUrl = "https://example.com";
        assertThrows(IllegalArgumentException.class, () -> new WhisperClient(config));
    }
}
