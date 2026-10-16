package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/** Eleven v4 Turbo through OpenRouter, returning signed 16-bit mono PCM at 24 kHz. */
final class OpenRouterSpeechClient implements AutoCloseable {
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/audio/speech");
    static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;
    private final String key;
    private final URI endpoint;
    private final BoundedHttp http;

    OpenRouterSpeechClient(String key, MobTalkConfig config) { this(key, config, ENDPOINT); }
    OpenRouterSpeechClient(String key, MobTalkConfig config, URI endpoint) {
        this.key = OpenRouterConversationClient.validKey(key);
        this.endpoint = endpoint;
        this.http = new BoundedHttp(config);
    }

    byte[] synthesize(String voiceId, String text) throws IOException, InterruptedException {
        if (voiceId == null || voiceId.isBlank() || text == null || text.isBlank())
            throw new IOException("Для озвучки нужны голос и текст.");
        JsonObject body = new JsonObject();
        body.addProperty("model", "elevenlabs/eleven-v4-turbo");
        body.addProperty("input", text);
        body.addProperty("voice", voiceId);
        body.addProperty("response_format", "pcm");
        JsonObject elevenlabs = new JsonObject();
        elevenlabs.addProperty("output_format", "pcm_24000");
        JsonObject options = new JsonObject();
        options.add("elevenlabs", elevenlabs);
        JsonObject provider = new JsonObject();
        provider.add("options", options);
        body.add("provider", provider);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(http.timeout())
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .header("Accept", "audio/pcm")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        byte[] pcm = http.send(request, MAX_AUDIO_BYTES, "OpenRouter TTS");
        if (pcm.length == 0 || (pcm.length & 1) != 0) throw new IOException("OpenRouter TTS вернул неверный PCM звук.");
        return pcm;
    }

    @Override public void close() { http.close(); }
}
