package io.github.mobtalk.server;

import io.github.mobtalk.core.SpeechStream;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Озвучка один раз на реплику с кэшем по (голос, текст) и рассылка чанками игрокам в радиусе; реализация — трек B.
 * Заглушка: {@link StubSpeech}. */
public interface SpeechService {
    /** Готовый звук: {@code format} — {@code pcm24k} (16 бит, 24 кГц, моно), {@code mp3} или {@code wav}; {@code millis} — длительность. */
    record Clip(int id, String format, byte[] data, int millis) { }

    /** Пустой {@code Optional} — реплика идёт без звука ({@code clipId = 0}). Кэш по (voiceId, text). */
    CompletableFuture<Optional<Clip>> synthesize(String voiceId, String text);

    /** Режет клип на {@code SpeechClipS2C} не больше 512 КБ и шлёт игрокам в {@code range} блоков от {@code source}. Поток сервера. */
    void broadcast(ServerWorld world, Entity source, double range, Clip clip);

    /** Озвучка, которая ещё скачивается (её отдаёт {@code ConversationEngine} в {@code Reply.speech()} и в репликах очевидцев):
     * чанки уходят игрокам в {@code range} от {@code source}, пока она растёт, так что моба слышно почти без задержки. Возвращает
     * {@code clipId} для {@code MobSpeechS2C}; 0 — без звука. Первый чанк уходит не раньше конца тика, так что {@code MobSpeechS2C},
     * посланный сразу после вызова, приходит раньше звука (клиент примет и обратный порядок). Поток сервера. Заглушка звука не шлёт. */
    default int stream(ServerWorld world, Entity source, double range, SpeechStream audio) {
        if (audio != null) audio.close();
        return 0;
    }
}
