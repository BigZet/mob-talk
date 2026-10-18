package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/** Text to speech through OpenRouter (ElevenLabs voices by id, Eleven Flash v2.5 by default) or Venice (Kokoro, one voice for
 * all), returning signed 16-bit mono PCM at 24 kHz either way. */
final class OpenRouterSpeechClient implements AutoCloseable {
    static final String DEFAULT_MODEL = "elevenlabs/eleven-flash-v2.5";
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/audio/speech");
    static final URI VENICE_ENDPOINT = URI.create("https://api.venice.ai/api/v1/audio/speech");
    static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;
    private final String key;
    private final URI endpoint;
    private final BoundedHttp http;
    private final MobTalkConfig config;

    OpenRouterSpeechClient(String key, MobTalkConfig config) { this(key, config, config.usesVeniceSpeech() ? VENICE_ENDPOINT : ENDPOINT); }
    OpenRouterSpeechClient(String key, MobTalkConfig config, URI endpoint) {
        // No key: the client exists but every request fails, so the reply is shown as text only.
        this.key = key == null ? null : OpenRouterConversationClient.validKey(key);
        this.endpoint = endpoint;
        this.config = config.copy();
        this.http = new BoundedHttp(config);
    }

    java.time.Duration timeout() { return http.timeout(); }
    /** The speech model requests name. */
    String model() { return config.speechModel(); }
    String service() { return config.usesVeniceSpeech() ? "Venice TTS" : "OpenRouter TTS"; }

    /** An ElevenLabs voice id as the species catalog and the mob card store them. */
    static boolean validVoiceId(String voiceId) { return voiceId != null && voiceId.strip().matches("[A-Za-z0-9]{20,64}"); }
    /** Venice voices every mob with the configured Kokoro voice; the per-mob ElevenLabs id only matters through OpenRouter. */
    String selectedVoice(String preferred) {
        if (config.usesVeniceSpeech()) return config.veniceVoice.matches("[A-Za-z0-9_.-]{1,64}") ? config.veniceVoice : "";
        return validVoiceId(preferred) ? preferred.strip() : "";
    }
    private boolean usable(String voiceId) { return config.usesVeniceSpeech() ? voiceId != null && voiceId.matches("[A-Za-z0-9_.-]{1,64}") : validVoiceId(voiceId); }

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

    /** Same voice fallback as synthesizeWithFallback; returns as soon as the audio starts arriving. */
    StreamedAudio streamWithFallback(String preferred, String reserve, String text) throws IOException, InterruptedException {
        if (preferred == null || preferred.isBlank()) throw new IOException("Для этого характера голос ещё не назначен.");
        String selected = selectedVoice(preferred), replacement = selectedVoice(reserve);
        if (selected.isBlank()) {
            if (replacement.isBlank()) throw new IOException("Для этого характера нет допустимого видового голоса.");
            return new StreamedAudio(replacement, stream(replacement, text));
        }
        try { return new StreamedAudio(selected, stream(selected, text)); }
        catch (IOException failure) {
            String message = failure.getMessage();
            if (replacement.isBlank() || selected.equals(replacement) || message == null
                    || !(message.contains("HTTP 400") || message.contains("HTTP 404") || message.contains("HTTP 422"))) throw failure;
            return new StreamedAudio(replacement, stream(replacement, text));
        }
    }

    /** OpenRouter forwards Eleven's PCM as it is generated: the first bytes come long before the last. */
    SpeechStream stream(String voiceId, String text) throws IOException, InterruptedException {
        try { return http.stream(request(voiceId, text), MAX_AUDIO_BYTES, service()); }
        catch (IOException failure) {
            if (!BoundedHttp.dropped(failure)) throw failure;
            return http.stream(request(voiceId, text), MAX_AUDIO_BYTES, service());
        }
    }

    byte[] synthesize(String voiceId, String text) throws IOException, InterruptedException {
        byte[] pcm = http.send(request(voiceId, text), MAX_AUDIO_BYTES, service());
        if (pcm.length == 0 || (pcm.length & 1) != 0) throw new IOException(service() + " вернул неверный PCM звук.");
        return pcm;
    }

    private HttpRequest request(String voiceId, String text) throws IOException {
        if (key == null) throw new IOException("Для озвучки нужен ключ " + (config.usesVeniceSpeech() ? "Venice." : "OpenRouter."));
        if (!usable(voiceId) || text == null || text.isBlank())
            throw new IOException("Для озвучки нужны голос и текст.");
        JsonObject body = new JsonObject();
        body.addProperty("model", model());
        body.addProperty("input", text);
        body.addProperty("voice", voiceId);
        // Both providers speak the OpenAI shape: "pcm" is 24 kHz signed 16-bit mono.
        body.addProperty("response_format", "pcm");
        if (config.usesVeniceSpeech()) body.addProperty("streaming", true);
        else if (model().startsWith("elevenlabs/")) {
            JsonObject elevenlabs = new JsonObject();
            elevenlabs.addProperty("output_format", "pcm_24000");
            JsonObject options = new JsonObject();
            options.add("elevenlabs", elevenlabs);
            JsonObject provider = new JsonObject();
            provider.add("options", options);
            body.add("provider", provider);
        }
        return HttpRequest.newBuilder(endpoint).timeout(http.timeout())
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .header("Accept", "audio/pcm")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    @Override public void close() { http.close(); }
    record Audio(String voiceId, byte[] pcm) { }
    record StreamedAudio(String voiceId, SpeechStream stream) { }
}
