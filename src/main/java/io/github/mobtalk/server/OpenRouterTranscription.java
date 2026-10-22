package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.OpenRouterTranscriber;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** Путь {@code server}: аудио игрока распознаёт OpenRouter STT ({@link OpenRouterTranscriber}) на рабочем потоке, не на потоке
 * сервера. До запроса — {@link PlayerBudget#allow}, после — {@link PlayerBudget#charge} с {@code usage.cost} ответа и секундами
 * записи. Клиент OpenRouter пересоздаётся, когда админ меняет модель или ожидание. */
public final class OpenRouterTranscription implements TranscriptionService {
    /** Что делает запрос: настоящий {@link OpenRouterTranscriber} или фейк в тестах. */
    public interface Engine extends AutoCloseable {
        OpenRouterTranscriber.Result transcribe(byte[] audio, String format, String language) throws IOException, InterruptedException;
        @Override default void close() { }
    }

    private final Supplier<MobTalkConfig> config;
    private final Function<MobTalkConfig, Engine> engines;
    private final Supplier<PlayerBudget> budget;
    private final Function<UUID, String> language;
    private final Executor executor;
    private Engine engine;
    private String engineKey = "";

    public OpenRouterTranscription(Supplier<MobTalkConfig> config, Function<MobTalkConfig, Engine> engines, Supplier<PlayerBudget> budget,
                                   Function<UUID, String> language, Executor executor) {
        this.config = config;
        this.engines = engines;
        this.budget = budget;
        this.language = language;
        this.executor = executor;
    }

    @Override public CompletableFuture<String> transcribe(UUID player, byte[] audio, String format, int seconds, String hints) {
        PlayerBudget.Verdict verdict = budget.get().allow(player, PlayerBudget.Kind.TRANSCRIPTION);
        if (!verdict.allowed()) return CompletableFuture.failedFuture(new IllegalStateException(verdict.reason()));
        return CompletableFuture.supplyAsync(() -> {
            try {
                OpenRouterTranscriber.Result result = engine().transcribe(audio, format, language.apply(player));
                int units = (int) Math.ceil(result.seconds() > 0 ? result.seconds() : Math.max(1, seconds));
                budget.get().charge(player, PlayerBudget.Kind.TRANSCRIPTION, result.usd(), units);
                return result.text();
            } catch (IOException failure) {
                throw new CompletionException(new IllegalStateException(failure.getMessage(), failure));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IllegalStateException("Распознавание прервано"));
            }
        }, executor);
    }

    private synchronized Engine engine() {
        MobTalkConfig current = config.get();
        String key = current.sttModel + "|" + current.sttFallbackModel + "|" + current.sttTimeoutSeconds;
        if (engine == null || !key.equals(engineKey)) {
            if (engine != null) engine.close();
            engine = engines.apply(current);
            engineKey = key;
        }
        return engine;
    }

    public synchronized void close() {
        if (engine != null) engine.close();
        engine = null;
    }
}
