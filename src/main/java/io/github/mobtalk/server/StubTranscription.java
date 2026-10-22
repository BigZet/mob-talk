package io.github.mobtalk.server;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Пока трек B не подключил OpenRouter STT: любое аудио отвергается с понятной причиной, игроку остаётся локальный Whisper или текст. */
public final class StubTranscription implements TranscriptionService {
    public static final String REASON = "Распознавание на сервере не настроено";

    @Override public CompletableFuture<String> transcribe(UUID player, byte[] audio, String format, int seconds, String hints) {
        return CompletableFuture.failedFuture(new IllegalStateException(REASON));
    }
}
