package io.github.mobtalk.server;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Распознавание речи для тех, кто шлёт аудио на сервер ({@code TalkAudioC2S}); реализация — трек B (OpenRouter STT, Whisper на
 * сервере с GPU). Заглушка: {@link StubTranscription}. */
public interface TranscriptionService {
    /** Текст или пустая строка; исключение — понятная игроку причина. {@code hints} — слова мира для подсказки модели. */
    CompletableFuture<String> transcribe(UUID player, byte[] audio, String format, int seconds, String hints);
}
