package io.github.mobtalk.server;

import io.github.mobtalk.MobTalkMod;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Entrypoint {@code mobtalk-stt} для других модов без зависимости от jar Mob Talk (раздел 13, бриф B, п. 5): Божественный блок
 * отдаёт WAV 16 кГц моно молитвы и получает текст тем же распознаванием, что и диалог. {@link Function} — как в плане; если мод
 * знает игрока, лучше entrypoint {@code mobtalk-stt-player} ({@link ForPlayer}, {@link BiFunction} с UUID): тогда расход виден на
 * нём в бюджете. Пустая строка — речи нет; упавший future — понятная игроку причина. */
public final class MobTalkStt implements Function<byte[], CompletableFuture<String>> {
    /** Кому записывать расход, если мод не назвал игрока. */
    public static final UUID UNKNOWN = new UUID(0, 0);

    @Override public CompletableFuture<String> apply(byte[] wav) { return transcribe(UNKNOWN, wav); }

    /** Entrypoint {@code mobtalk-stt-player}. */
    public static final class ForPlayer implements BiFunction<UUID, byte[], CompletableFuture<String>> {
        @Override public CompletableFuture<String> apply(UUID player, byte[] wav) { return transcribe(player, wav); }
    }

    static CompletableFuture<String> transcribe(UUID player, byte[] wav) {
        if (wav == null || wav.length < 64) return CompletableFuture.completedFuture("");
        int seconds = (int) Math.max(1, Math.ceil((wav.length - 44) / 32_000.0));
        return MobTalkMod.transcription().transcribe(player == null ? UNKNOWN : player, wav, "wav16k", seconds, "");
    }
}
