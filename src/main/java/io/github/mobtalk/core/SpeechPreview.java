package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit paid voice probe; secrets stay local and cache records contain only a one-way account digest. */
public final class SpeechPreview {
    private static final Gson JSON = new Gson();
    private SpeechPreview() { }

    /** Read-only authentication check; never calls a paid model or exposes the key response. */
    public static String connectionStatus(Path directory, MobTalkConfig config) throws IOException, InterruptedException {
        try (BoundedHttp http = new BoundedHttp(config)) {
            if (!config.usesVenice()) {
                checkOpenRouter(http, OpenRouterToken.load(directory));
                String speech = config.usesVeniceSpeech() ? "; озвучка Venice (" + config.veniceTtsModel + ", голос " + config.veniceVoice + ")"
                        + (OpenRouterToken.status(directory, OpenRouterToken.Provider.VENICE).source() == OpenRouterToken.Source.NONE ? " — ключ Venice не задан" : "")
                        : "; озвучка " + config.ttsModel;
                return "OpenRouter доступен, ключ принят. Выбранная LLM: " + config.llmModel + speech;
            }
            String venice = OpenRouterToken.load(directory, OpenRouterToken.Provider.VENICE);
            http.send(HttpRequest.newBuilder(URI.create("https://api.venice.ai/api/v1/api_keys/rate_limits"))
                    .timeout(http.timeout()).header("Authorization", "Bearer " + venice).GET().build(), 256 * 1024, "Venice");
            String speech;
            if (config.usesVeniceSpeech()) speech = "озвучка Venice (" + config.veniceTtsModel + ", голос " + config.veniceVoice + ")";
            else try { checkOpenRouter(http, OpenRouterToken.load(directory)); speech = "озвучка OpenRouter готова (" + config.ttsModel + ")"; }
            catch (IOException unavailable) { speech = "озвучка недоступна: " + unavailable.getMessage(); }
            return "Venice доступен, ключ принят. Выбранная LLM: " + config.veniceModel + "; " + speech;
        }
    }

    private static void checkOpenRouter(BoundedHttp http, String key) throws IOException, InterruptedException {
        http.send(HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/key"))
                .timeout(http.timeout()).header("Authorization", "Bearer " + key).GET().build(), 64 * 1024, "OpenRouter");
    }

    /** Voices {@code text} with the configured speech provider; through Venice the per-mob voice gives way to the configured one. */
    public static byte[] synthesize(MobTalkConfig config, Path directory, String voice, String text) throws IOException, InterruptedException {
        if (!config.usesVeniceSpeech() && !OpenRouterSpeechClient.validVoiceId(voice)) throw new IOException("Голос ещё не назначен или ID имеет неверный формат.");
        String key = OpenRouterToken.load(directory, config.usesVeniceSpeech() ? OpenRouterToken.Provider.VENICE : OpenRouterToken.Provider.OPENROUTER);
        try (OpenRouterSpeechClient speech = new OpenRouterSpeechClient(key, config)) {
            String used = speech.selectedVoice(voice);
            byte[] pcm = speech.synthesize(used, TextBounds.reply(text, 120));
            try { mark(directory, used, key, speech.model()); } catch (IOException cacheUnavailable) { /* Audio remains usable. */ }
            return pcm;
        }
    }

    public static synchronized void mark(Path directory, String voice, String key, String model) throws IOException {
        Map<String, Validation> validations = load(directory);
        validations.put(voice, new Validation(model, digest(key), Instant.now().toString()));
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "mobtalk-voice-status", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(validations), StandardCharsets.UTF_8);
            try { Files.move(temporary, file(directory), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file(directory), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static synchronized String status(Path directory, String voice, MobTalkConfig config) {
        if (voice == null || voice.isBlank()) return "Голос не назначен";
        String model = config.speechModel();
        if (config.usesVeniceSpeech()) voice = config.veniceVoice;
        try {
            Validation validation = load(directory).get(voice);
            OpenRouterToken.Provider provider = config.usesVeniceSpeech() ? OpenRouterToken.Provider.VENICE : OpenRouterToken.Provider.OPENROUTER;
            if (validation != null && model.equals(validation.model) && digest(OpenRouterToken.load(directory, provider)).equals(validation.account))
                return model + ": успешно проверен " + validation.date.substring(0, 10);
        } catch (IOException | RuntimeException unavailable) { }
        if (config.usesVeniceSpeech()) return "Venice, голос " + voice + "; " + model + " ещё не проверен";
        return (SpeciesCatalog.voice(voice).previewUrl().isBlank() ? "Пользовательский ID; библиотека не проверена" : "Есть в публичном каталоге")
                + "; " + model + " ещё не проверен";
    }

    private static Map<String, Validation> load(Path directory) throws IOException {
        if (!Files.isRegularFile(file(directory))) return new LinkedHashMap<>();
        try {
            Map<String, Validation> result = JSON.fromJson(Files.readString(file(directory)), new TypeToken<Map<String, Validation>>() { }.getType());
            return result == null ? new LinkedHashMap<>() : result;
        } catch (RuntimeException malformed) { return new LinkedHashMap<>(); }
    }
    private static Path file(Path directory) { return directory.resolve("mobtalk-voice-status.json"); }
    private static String digest(String key) throws IOException {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IOException("Не удалось проверить локальный статус голоса."); }
    }
    private record Validation(String model, String account, String date) { }
}
