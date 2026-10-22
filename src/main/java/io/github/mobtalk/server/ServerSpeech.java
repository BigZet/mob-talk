package io.github.mobtalk.server;

import io.github.mobtalk.core.ClipSlicer;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SpeechCache;
import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.core.SpeechVoicer;
import io.github.mobtalk.net.MobTalkNet;
import io.github.mobtalk.net.SpeechClipS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Озвучка на сервере (раздел 3 плана): один синтез на реплику, кэш {@link SpeechCache} по (голос, модель, текст) в памяти и в
 * папке мира, звук чанками {@link SpeechClipS2C} всем игрокам в радиусе. Звук идёт сырым PCM 24 кГц ({@link ClipSlicer#PCM}), как его
 * отдаёт провайдер: ~48 КБ/с на слушателя, без кодировщика. Чанки шлёт {@link #pump()} в конце каждого тика сервера. */
public final class ServerSpeech implements SpeechService {
    static final int CACHE_CLIPS = 200;
    /** Озвучка дольше этого не дождётся конца: поток закрывается, сказанное уже ушло. */
    private static final long MAX_CLIP_NANOS = 120_000_000_000L;
    /** Чанков на клип за тик: 8 × 0,2 с догоняют даже клип из кэша за пару тиков. */
    private static final int PIECES_PER_TICK = 8;

    private final Supplier<MobTalkConfig> config;
    private final Path configDirectory;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "mobtalk-server-speech"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger ids = new AtomicInteger();
    private final ConcurrentLinkedQueue<Outgoing> outgoing = new ConcurrentLinkedQueue<>();
    private volatile SpeechCache cache = new SpeechCache(CACHE_CLIPS, null);
    private SpeechVoicer voicer;
    private String voicerKey = "";

    private record Outgoing(int id, ClipSlicer slicer, SpeechStream audio, Entity source, List<ServerPlayerEntity> listeners, long started) { }

    public ServerSpeech(Supplier<MobTalkConfig> config, Path configDirectory) {
        this.config = config;
        this.configDirectory = configDirectory;
    }

    /** Мир открыт: кэш на диске в {@code data/mobtalk/speech-cache} этого мира. */
    public void open(Path worldDirectory) {
        cache = new SpeechCache(CACHE_CLIPS, worldDirectory == null ? null : worldDirectory.resolve("data").resolve("mobtalk").resolve("speech-cache"));
    }

    /** Мир закрыт: недосланные клипы закрываются. */
    public void close() {
        for (Outgoing clip; (clip = outgoing.poll()) != null; ) clip.audio().close();
        cache = new SpeechCache(CACHE_CLIPS, null);
    }

    public int nextId() {
        int id;
        do { id = ids.updateAndGet(value -> value >= Integer.MAX_VALUE - 1 ? 1 : value + 1); } while (id == 0);
        return id;
    }

    @Override public CompletableFuture<Optional<Clip>> synthesize(String voiceId, String text) {
        if (voiceId == null || voiceId.isBlank() || text == null || text.isBlank()) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            try {
                SpeechVoicer current = voicer();
                SpeechCache store = cache;
                byte[] pcm = store.get(voiceId, current.model(), text).orElse(null);
                if (pcm == null) {
                    pcm = current.voice(voiceId, "", text).pcm();
                    store.put(voiceId, current.model(), text, pcm);
                }
                return Optional.of(new Clip(nextId(), ClipSlicer.PCM, pcm, (int) (pcm.length / 48L)));
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                io.github.mobtalk.MobTalkMod.LOGGER.warn("Mob Talk: озвучка на сервере не удалась ({})", failure.getMessage());
                return Optional.empty();
            }
        }, worker);
    }

    @Override public void broadcast(ServerWorld world, Entity source, double range, Clip clip) {
        if (clip == null || clip.data() == null || clip.data().length == 0) return;
        if (!ClipSlicer.PCM.equals(clip.format())) {
            // Чужой формат (mp3/wav) — целиком, с известным числом чанков: клиент раскодирует, когда соберёт.
            List<ServerPlayerEntity> listeners = listeners(world, source, range);
            for (SpeechClipS2C chunk : StubSpeech.chunks(clip)) for (ServerPlayerEntity player : listeners) ServerPlayNetworking.send(player, chunk);
            return;
        }
        queue(clip.id(), world, source, range, SpeechStream.of(clip.data()));
    }

    @Override public int stream(ServerWorld world, Entity source, double range, SpeechStream audio) {
        if (audio == null) return 0;
        int id = nextId();
        queue(id, world, source, range, audio);
        return id;
    }

    private void queue(int id, ServerWorld world, Entity source, double range, SpeechStream audio) {
        List<ServerPlayerEntity> listeners = listeners(world, source, range);
        if (listeners.isEmpty()) { audio.close(); return; }
        outgoing.add(new Outgoing(id, new ClipSlicer(audio, ClipSlicer.MIN_CHUNK, MobTalkNet.MAX_CHUNK), audio, source, listeners, System.nanoTime()));
    }

    private static List<ServerPlayerEntity> listeners(ServerWorld world, Entity source, double range) {
        double limit = range * range;
        List<ServerPlayerEntity> listeners = new ArrayList<>();
        for (ServerPlayerEntity player : world.getPlayers())
            if (player.squaredDistanceTo(source) <= limit && ServerPlayNetworking.canSend(player, SpeechClipS2C.ID)) listeners.add(player);
        return listeners;
    }

    /** Конец тика сервера: всё, что успело скачаться, уходит слушателям. Моб умер или выгружен — скачивание закрывается. */
    public void pump() {
        long now = System.nanoTime();
        for (Iterator<Outgoing> it = outgoing.iterator(); it.hasNext(); ) {
            Outgoing clip = it.next();
            if (clip.source().isRemoved() || now - clip.started() > MAX_CLIP_NANOS) clip.audio().close();
            for (int i = 0; i < PIECES_PER_TICK; i++) {
                ClipSlicer.Piece piece = clip.slicer().next();
                if (piece == null) break;
                SpeechClipS2C chunk = new SpeechClipS2C(clip.id(), piece.index(), piece.count(), ClipSlicer.PCM, piece.data());
                for (ServerPlayerEntity player : clip.listeners()) if (!player.isDisconnected()) ServerPlayNetworking.send(player, chunk);
            }
            if (clip.slicer().done()) it.remove();
        }
    }

    /** Клиент озвучки под текущие настройки; новый, когда админ сменил провайдера, модель или голос Venice. */
    private synchronized SpeechVoicer voicer() {
        MobTalkConfig current = config.get();
        String key = current.ttsProvider + "|" + current.speechModel() + "|" + current.veniceVoice;
        if (voicer == null || !key.equals(voicerKey)) {
            if (voicer != null) voicer.close();
            voicer = SpeechVoicer.open(current, configDirectory);
            voicerKey = key;
        }
        return voicer;
    }

    /** Сколько клипов ещё досылается (журнал и самотест). */
    public int sending() { return outgoing.size(); }
}
