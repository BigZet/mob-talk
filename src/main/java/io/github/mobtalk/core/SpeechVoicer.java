package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.Path;

/** Озвучка для сервера одним куском (OpenRouter по умолчанию, Venice по {@code ttsProvider}, с той же передачей на OpenRouter,
 * что и в диалоге): публичная обёртка над {@link OpenRouterSpeechClient}. Результат — PCM 16 бит 24 кГц моно. Блокирующая. */
public final class SpeechVoicer implements AutoCloseable {
    private final OpenRouterSpeechClient client;

    public record Voiced(String voiceId, byte[] pcm) { }

    SpeechVoicer(OpenRouterSpeechClient client) { this.client = client; }

    /** Ключи сервера как у {@link ConversationEngine}; без ключа клиент есть, но каждая озвучка падает понятной ошибкой. */
    public static SpeechVoicer open(MobTalkConfig config, Path configDirectory) {
        OpenRouterToken.Provider provider = config.usesVeniceSpeech() ? OpenRouterToken.Provider.VENICE : OpenRouterToken.Provider.OPENROUTER;
        String key;
        try { key = OpenRouterToken.load(configDirectory, provider); } catch (IOException missing) { key = null; }
        return new SpeechVoicer(new OpenRouterSpeechClient(key, config, ConversationEngine.speechFallbackKey(config, configDirectory)));
    }

    /** {@code reserve} — видовой запасной голос, если основной отвергнут; может быть пустым. */
    public Voiced voice(String voiceId, String reserve, String text) throws IOException, InterruptedException {
        OpenRouterSpeechClient.Audio audio = client.synthesizeWithFallback(voiceId, reserve == null ? "" : reserve, TextBounds.reply(text, 120));
        return new Voiced(audio.voiceId(), audio.pcm());
    }

    /** Модель, которой озвучено (ключ кэша). */
    public String model() { return client.model(); }

    @Override public void close() { client.close(); }
}
