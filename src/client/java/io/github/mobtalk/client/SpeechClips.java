package io.github.mobtalk.client;

import io.github.mobtalk.core.ClipReceiver;
import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.net.SpeechClipS2C;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Звук реплик с сервера (трек B): чанки {@link SpeechClipS2C} собираются по {@code clipId} в растущий {@link SpeechStream}.
 * Приёмник {@code MobSpeechS2C} (трек A) берёт его через {@link #take} и играет от моба как раньше —
 * {@code MobTalkClient.startVoice(payload.mob(), SpeechClips.take(payload.clipId()), !payload.toYou())}; чанки могут прийти и до,
 * и после реплики. */
public final class SpeechClips {
    private SpeechClips() { }
    /** Как у озвучки в одиночке: две минуты PCM 24 кГц с запасом. */
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    /** Клип, по которому 15 с ничего не приходит, больше не ждут. */
    private static final long STALE_MILLIS = 15_000;
    private static final ClipReceiver receiver = new ClipReceiver(MAX_BYTES, STALE_MILLIS, System::currentTimeMillis);
    private static int ticks;

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> { if (++ticks % 20 == 0) receiver.prune(); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> receiver.clear());
    }

    /** Приёмник {@link SpeechClipS2C} (поток игры). */
    static void accept(SpeechClipS2C payload) {
        receiver.accept(payload.clipId(), payload.index(), payload.count(), payload.format(), payload.chunk());
    }

    /** Звук реплики для {@code MobSpeechS2C.clipId}; null, если реплика без звука (0). */
    public static SpeechStream take(int clipId) { return receiver.take(clipId); }
}
