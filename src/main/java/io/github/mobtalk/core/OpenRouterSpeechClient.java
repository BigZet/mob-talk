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

    static boolean validVoiceId(String voiceId) { return voiceId != null && voiceId.strip().matches("[A-Za-z0-9]{20,64}"); }
    String selectedVoice(String preferred) { return validVoiceId(preferred) ? preferred.strip() : ""; }

    Audio synthesizeWithFallback(String preferred, String text) throws IOException, InterruptedException {
        return synthesizeWithFallback(preferred, "", text);
    }

    /** Only the caller's dedicated species reserve may replace a permanently rejected voice. */
    Audio synthesizeWithFallback(String preferred, String reserve, String text) throws IOException, InterruptedException {
        if (preferred == null || preferred.isBlank()) throw new IOException("Для этого характера голос ещё не назначен.");
        String selected = selectedVoice(preferred), replacement = selectedVoice(reserve);
        if (selected.isBlank()) {
            if (replacement.isBlank()) throw new IOException("Для этого характера нет допустимого видового голоса.");
            return new Audio(replacement, synthesize(replacement, text));
        }
        try { return new Audio(selected, synthesize(selected, text)); }
        catch (IOException failure) {
            String message = failure.getMessage();
            if (replacement.isBlank() || selected.equals(replacement) || message == null
                    || !(message.contains("HTTP 400") || message.contains("HTTP 404") || message.contains("HTTP 422"))) throw failure;
            return new Audio(replacement, synthesize(replacement, text));
        }
    }

    byte[] synthesize(String voiceId, String text) throws IOException, InterruptedException {
        if (!validVoiceId(voiceId) || text == null || text.isBlank())
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
    record Audio(String voiceId, byte[] pcm) { }
}
