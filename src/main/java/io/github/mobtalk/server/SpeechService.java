package io.github.mobtalk.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Озвучка один раз на реплику с кэшем по (голос, текст) и рассылка чанками игрокам в радиусе; реализация — трек B.
 * Заглушка: {@link StubSpeech}. */
public interface SpeechService {
    /** Готовый звук: {@code format} — {@code mp3} или {@code wav}, {@code millis} — длительность. */
    record Clip(int id, String format, byte[] data, int millis) { }

    /** Пустой {@code Optional} — реплика идёт без звука ({@code clipId = 0}). Кэш по (voiceId, text). */
    CompletableFuture<Optional<Clip>> synthesize(String voiceId, String text);

    /** Режет клип на {@code SpeechClipS2C} не больше 512 КБ и шлёт игрокам в {@code range} блоков от {@code source}. Поток сервера. */
    void broadcast(ServerWorld world, Entity source, double range, Clip clip);
}
