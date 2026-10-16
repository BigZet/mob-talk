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
    private static final String MODEL = OpenRouterSpeechClient.MODEL;
    private static final Gson JSON = new Gson();
    private SpeechPreview() { }

    /** Read-only authentication check; never calls a paid model or exposes the key response. */
    public static String connectionStatus(Path directory, MobTalkConfig config) throws IOException, InterruptedException {
        String key = OpenRouterToken.load(directory);
        try (BoundedHttp http = new BoundedHttp(config)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/key"))
                    .timeout(http.timeout()).header("Authorization", "Bearer " + key).GET().build();
            http.send(request, 64 * 1024, "OpenRouter");
            return "OpenRouter доступен, ключ принят. Выбранная LLM: " + config.llmModel;
        }
    }

    public static byte[] synthesize(MobTalkConfig config, Path directory, String voice, String text) throws IOException, InterruptedException {
        if (!OpenRouterSpeechClient.validVoiceId(voice)) throw new IOException("Голос ещё не назначен или ID имеет неверный формат.");
        String key = OpenRouterToken.load(directory);
        try (OpenRouterSpeechClient speech = new OpenRouterSpeechClient(key, config)) {
            byte[] pcm = speech.synthesize(voice, TextBounds.reply(text, 120));
            try { mark(directory, voice, key); } catch (IOException cacheUnavailable) { /* Audio remains usable. */ }
            return pcm;
        }
    }

    public static synchronized void mark(Path directory, String voice, String key) throws IOException {
        Map<String, Validation> validations = load(directory);
        validations.put(voice, new Validation(MODEL, digest(key), Instant.now().toString()));
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "mobtalk-voice-status", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(validations), StandardCharsets.UTF_8);
            try { Files.move(temporary, file(directory), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file(directory), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static synchronized String status(Path directory, String voice) {
        if (voice == null || voice.isBlank()) return "Голос не назначен";
        try {
            Validation validation = load(directory).get(voice);
            if (validation != null && MODEL.equals(validation.model) && digest(OpenRouterToken.load(directory)).equals(validation.account))
                return "OpenRouter Flash v2.5: успешно проверен " + validation.date.substring(0, 10);
        } catch (IOException | RuntimeException unavailable) { }
        return (SpeciesCatalog.voice(voice).previewUrl().isBlank() ? "Пользовательский ID; библиотека не проверена" : "Есть в публичном каталоге")
                + "; OpenRouter Flash v2.5 ещё не проверен";
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
