package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.WhisperClient;
import io.github.mobtalk.core.WhisperHelper;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Путь {@code server-local}: сервер на машине с видеокартой поднимает тот же {@code whisper_server.py}, что у игроков
 * ({@link WhisperHelper}), и распознаёт им аудио всех игроков, по одному. Бесплатно; в бюджет идут только секунды. Только WAV. */
public final class ServerWhisperTranscription implements TranscriptionService {
    /** Распознать WAV локальным helper; фейк в тестах. */
    public interface Recognizer {
        String recognize(MobTalkConfig config, byte[] wav, String hints) throws IOException, InterruptedException;
    }

    private final Supplier<MobTalkConfig> config;
    private final Recognizer recognizer;
    private final Supplier<PlayerBudget> budget;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-server-whisper"); thread.setDaemon(true); return thread;
    });

    public ServerWhisperTranscription(Supplier<MobTalkConfig> config, Recognizer recognizer, Supplier<PlayerBudget> budget) {
        this.config = config;
        this.recognizer = recognizer;
        this.budget = budget;
    }

    /** Настоящий helper: запускается при первой записи и живёт, пока жив сервер. */
    public static Recognizer helper(WhisperHelper helper) {
        return (config, wav, hints) -> {
            helper.ensureAvailable(config, () -> false);
            try (WhisperClient whisper = new WhisperClient(config)) { return whisper.transcribe(wav, hints); }
        };
    }

    @Override public CompletableFuture<String> transcribe(UUID player, byte[] audio, String format, int seconds, String hints) {
        if (!"wav16k".equals(format) && !"wav".equals(format))
            return CompletableFuture.failedFuture(new IllegalStateException("Whisper сервера принимает только WAV"));
        PlayerBudget.Verdict verdict = budget.get().allow(player, PlayerBudget.Kind.TRANSCRIPTION);
        if (!verdict.allowed()) return CompletableFuture.failedFuture(new IllegalStateException(verdict.reason()));
        return CompletableFuture.supplyAsync(() -> {
            try {
                MobTalkConfig current = config.get();
                String text = recognizer.recognize(current, audio, hints == null || hints.isBlank() ? current.whisperHotwords : hints);
                budget.get().charge(player, PlayerBudget.Kind.TRANSCRIPTION, 0, Math.max(1, seconds));
                return text == null ? "" : text.strip();
            } catch (IOException failure) {
                throw new CompletionException(new IllegalStateException("Whisper сервера: " + failure.getMessage(), failure));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IllegalStateException("Распознавание прервано"));
            }
        }, worker);
    }

    public void close() { worker.shutdownNow(); }
}
