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
    /** Through OpenRouter, and through Venice's ElevenLabs model, every mob speaks with its own ElevenLabs voice id; any other
     * Venice model knows only its own catalog, so the configured Venice voice stands in for all. */
    String selectedVoice(String preferred) {
        if (config.usesVeniceSpeech() && !config.veniceSpeechKeepsVoices()) return config.veniceVoice.matches("[A-Za-z0-9_.-]{1,64}") ? config.veniceVoice : "";
        return validVoiceId(preferred) ? preferred.strip() : "";
    }
    private boolean usable(String voiceId) {
        return config.usesVeniceSpeech() && !config.veniceSpeechKeepsVoices() ? voiceId != null && voiceId.matches("[A-Za-z0-9_.-]{1,64}") : validVoiceId(voiceId);
    }

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
    /** A lost connection (a connect timeout included) gets two more tries: Venice's endpoint drops connects now and then. */
    static final int CONNECT_ATTEMPTS = 3;
    SpeechStream stream(String voiceId, String text) throws IOException, InterruptedException {
        // A model that speaks only MP3 or WAV is downloaded whole and decoded; PCM streams as it is generated.
        if (!"pcm".equals(format())) return SpeechStream.of(synthesize(voiceId, text));
        for (int attempt = 1; ; attempt++) {
            try { return http.stream(request(voiceId, text), MAX_AUDIO_BYTES, service()); }
            catch (IOException failure) {
                if (!BoundedHttp.dropped(failure) || attempt >= CONNECT_ATTEMPTS) throw failure;
            }
        }
    }

    /** Venice models that speak raw PCM; the rest answer in WAV or MP3 (ElevenLabs there is MP3 only) and get decoded. */
    private static final java.util.Set<String> VENICE_PCM = java.util.Set.of("tts-kokoro", "tts-xai-v1", "tts-minimax-speech-02-hd", "tts-gradium-v1");
    private static final java.util.Set<String> VENICE_WAV = java.util.Set.of("tts-inworld-1-5-max", "tts-chatterbox-hd", "tts-orpheus");
    /** The response format asked of the provider for the configured model. */
    String format() {
        if (!config.usesVeniceSpeech()) return "pcm";
        String model = model();
        return VENICE_PCM.contains(model) ? "pcm" : VENICE_WAV.contains(model) ? "wav" : "mp3";
    }

    byte[] synthesize(String voiceId, String text) throws IOException, InterruptedException {
        byte[] audio = null;
        for (int attempt = 1; audio == null; attempt++) {
            try { audio = http.send(request(voiceId, text), MAX_AUDIO_BYTES, service()); }
            catch (IOException failure) { if (!BoundedHttp.dropped(failure) || attempt >= CONNECT_ATTEMPTS) throw failure; }
        }
        byte[] pcm = io.github.mobtalk.audio.AudioDecode.toPcm24k(audio, format());
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
        // Both providers speak the OpenAI shape: "pcm" is 24 kHz signed 16-bit mono; other formats are decoded after download.
        String format = format();
        body.addProperty("response_format", format);
        if (config.usesVeniceSpeech() && "pcm".equals(format)) body.addProperty("streaming", true);
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
                .header("Accept", "pcm".equals(format()) ? "audio/pcm" : "mp3".equals(format()) ? "audio/mpeg" : "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    @Override public void close() { http.close(); }
    record Audio(String voiceId, byte[] pcm) { }
    record StreamedAudio(String voiceId, SpeechStream stream) { }
}
