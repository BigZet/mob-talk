package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodVoice;
import io.github.divineblock.core.VoiceEffects;
import io.github.divineblock.game.GodLinePayload;
import io.github.divineblock.game.VoiceStartPayload;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** Voices the god's lines as a sound from everywhere, a voice from the sky. The speech streams: it starts to play within
 * a second while the rest is still being synthesised, passing through the temple's reverb chunk by chunk. The server hears
 * when the voice starts (the chat line and the beams appear) and when the last word is said (the act falls). A newer line
 * cuts the older one off; leaving the world drops whatever was still on its way. */
final class GodVoicePlayer {
    private static final ExecutorService WORKER = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "divineblock-voice"); t.setDaemon(true); return t; });
    private static final AtomicInteger generation = new AtomicInteger();
    private static GodSound playing;
    private static Buffer current;
    /** Seconds of each voiced line once its words are heard, for the self-test. */
    static volatile java.util.function.DoubleConsumer played = seconds -> { };
    /** About a quarter of a second of speech before playback starts, so a slow network does not stutter at once. */
    private static final int PREBUFFER = GodVoice.SAMPLE_RATE / 2;

    private GodVoicePlayer() { }

    /** Self-tests run without paid speech unless -Ddivineblock.selftest.tts=true: the lines are shown and the acts follow at
     * once, as with the voice switched off. Any of the mod's test modes counts. */
    static final boolean TEST_WITHOUT_SPEECH = !Boolean.getBoolean("divineblock.selftest.tts")
            && java.util.stream.Stream.of("divineblock.selftest", "divineblock.bosstest", "divineblock.churchtest", "divineblock.cemeterytest", "divineblock.fxtest")
                    .anyMatch(flag -> !System.getProperty(flag, "").isEmpty() && !"false".equalsIgnoreCase(System.getProperty(flag)));

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(GodLinePayload.ID, (payload, context) -> speak(context.client(), payload.id(), payload.line()));
    }

    static final String PREVIEW_LINE = "Ты взвешен на весах и найден лёгким, смертный. Что посеял, то и пожнёшь.";
    /** The dry preview line per voice: the knobs are tuned by ear without paying for speech again. */
    private static final java.util.Map<String, byte[]> previewCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Plays the preview line with the given (unsaved) settings, in a world or in a menu. */
    static void preview(MinecraftClient client, DivineConfig config, java.util.function.Consumer<String> status) {
        if (TEST_WITHOUT_SPEECH) { status.accept("Самотест без озвучки: прослушивание пропущено."); return; }
        int mine = generation.incrementAndGet();
        DivineConfig settings = config.copy();
        WORKER.execute(() -> {
            byte[] voiced;
            try {
                byte[] dry = previewCache.get(settings.voiceId);
                if (dry == null) {
                    status.accept("Голос готовится…");
                    dry = new GodVoice(settings, DivineBlockMod.configDirectory()).synthesize(PREVIEW_LINE);
                    previewCache.put(settings.voiceId, dry);
                }
                voiced = settings.voiceTemple ? VoiceEffects.apply(dry, GodVoice.SAMPLE_RATE, settings.voiceEffects()) : dry;
            } catch (Exception failure) { status.accept("Голос недоступен: " + failure.getMessage()); return; }
            Buffer buffer = new Buffer(0);
            buffer.append(voiced, voiced.length); buffer.wordsDone(); buffer.finish();
            client.execute(() -> {
                if (generation.get() != mine) return;
                play(client, buffer);
                status.accept("");
            });
        });
    }

    /** Voices a line from the server; {@code id} is acknowledged on start (-1) and on the last word (its length in seconds). */
    static void speak(MinecraftClient client, int id, String line) {
        DivineConfig config = DivineBlockMod.config();
        if (!config.voice || TEST_WITHOUT_SPEECH || line == null || line.isBlank()) { ack(id, 0); return; }
        int mine = generation.incrementAndGet();
        ClientWorld world = client.world;
        Buffer buffer = new Buffer(id);
        WORKER.execute(() -> {
            VoiceEffects.Hall hall = config.voiceTemple ? new VoiceEffects.Hall(GodVoice.SAMPLE_RATE, config.voiceEffects()) : null;
            boolean started = false;
            try (InputStream in = new GodVoice(config, DivineBlockMod.configDirectory()).stream(line)) {
                byte[] chunk = new byte[4800];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    if (generation.get() != mine || buffer.closed) { buffer.close(); break; }
                    byte[] out = hall != null ? hall.process(chunk, 0, read) : Arrays.copyOf(chunk, read);
                    buffer.append(out, out.length);
                    if (!started && buffer.size() >= PREBUFFER) { started = true; startPlaying(client, world, mine, buffer); }
                }
            } catch (Exception failure) {
                DivineBlockMod.LOG.info("Божественный блок: голос бога молчит: {}", failure.getMessage());
            }
            buffer.wordsDone();
            if (hall != null && buffer.size() > 0 && !buffer.closed) { byte[] tail = hall.finish(); buffer.append(tail, tail.length); }
            buffer.finish();
            if (!started) {
                if (buffer.size() > 0 && generation.get() == mine && !buffer.closed) startPlaying(client, world, mine, buffer);
                else ack(id, 0); // Nothing to say aloud: the act need not wait.
            }
        });
    }

    private static void startPlaying(MinecraftClient client, ClientWorld world, int mine, Buffer buffer) {
        client.execute(() -> {
            if (generation.get() != mine || client.world != world || world == null) { buffer.close(); ack(buffer.id, 0); return; }
            play(client, buffer);
            ack(buffer.id, -1);
        });
    }

    private static void play(MinecraftClient client, Buffer buffer) {
        if (playing != null) client.getSoundManager().stop(playing);
        if (current != null && current != buffer && !current.endAnnounced()) { current.close(); ack(current.id, 0); }
        current = buffer;
        playing = new GodSound(buffer);
        client.getSoundManager().play(playing);
    }

    /** Whether the god's voice is sounding now: the chants sink under it. */
    static boolean speaking() {
        return playing != null && MinecraftClient.getInstance().getSoundManager().isPlaying(playing);
    }

    /** Tells the server the voice began ({@code -1}) or the words are over ({@code >= 0}), so the act can follow the words. */
    static void ack(int id, float seconds) {
        if (id == 0) return;
        MinecraftClient.getInstance().execute(() -> {
            if (ClientPlayNetworking.canSend(VoiceStartPayload.ID)) ClientPlayNetworking.send(new VoiceStartPayload(id, seconds));
        });
    }

    /** 24 kHz PCM that keeps arriving while it already plays: written by the voice worker, read by the sound engine. Knows
     * where the words end (the hall's tail follows), so the act can fall on the last word rather than after the echo. */
    static final class Buffer {
        final int id;
        private byte[] data = new byte[GodVoice.SAMPLE_RATE * 2 * 4];
        private int size, wordsEnd = -1;
        private boolean finished, endAnnounced;
        volatile boolean closed;

        Buffer(int id) { this.id = id; }
        synchronized void append(byte[] bytes, int length) {
            if (closed) return;
            if (size + length > data.length) data = Arrays.copyOf(data, Math.max(size + length, data.length * 2));
            System.arraycopy(bytes, 0, data, size, length);
            size += length;
        }
        synchronized int size() { return size; }
        synchronized void wordsDone() { if (wordsEnd < 0) wordsEnd = size & ~1; }
        synchronized void finish() { size &= ~1; finished = true; }
        synchronized boolean endAnnounced() { return endAnnounced; }
        void close() { closed = true; }

        /** Up to {@code max} bytes from {@code offset}; 0 while more is coming; -1 at the end. */
        synchronized int read(int offset, ByteBuffer target, int max) {
            if (closed) return -1;
            if (!endAnnounced && wordsEnd >= 0 && offset >= wordsEnd) {
                endAnnounced = true;
                float seconds = wordsEnd / (2.0f * GodVoice.SAMPLE_RATE);
                if (id != 0) played.accept(seconds);
                ack(id, seconds);
            }
            int available = (size & ~1) - offset;
            if (available <= 0) return finished ? -1 : 0;
            int amount = Math.min(available, max) & ~1;
            target.put(data, offset, amount);
            return amount;
        }
    }

    /** Non-positional: the god is heard the same wherever the player looks. */
    static final class GodSound extends AbstractSoundInstance {
        private final Buffer buffer;
        GodSound(Buffer buffer) {
            super(SoundEvent.of(Identifier.of("divineblock", "god_voice")), SoundCategory.VOICE, SoundInstance.createRandom());
            this.buffer = buffer;
            this.relative = true;
            this.attenuationType = AttenuationType.NONE;
            this.x = 0; this.y = 0; this.z = 0;
        }
        @Override public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
            return CompletableFuture.completedFuture(new PcmStream(buffer));
        }
    }

    /** Hands the growing buffer to the sound engine in small chunks; while the download lags, 20 ms of silence keeps it alive. */
    static final class PcmStream implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(GodVoice.SAMPLE_RATE, 16, 1, true, false);
        private static final int CHUNK = 4_800, SILENCE = 960;
        private final Buffer source;
        private int offset;
        private ByteBuffer buffer;
        PcmStream(Buffer source) { this.source = source; }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public ByteBuffer read(int size) {
            int max = Math.min(size, CHUNK) & ~1;
            if (max < 2) return null;
            if (buffer == null) buffer = ByteBuffer.allocateDirect(CHUNK);
            buffer.clear();
            int amount = source.read(offset, buffer, max);
            if (amount < 0) return null;
            if (amount == 0) for (int i = 0; i < SILENCE; i++) buffer.put((byte) 0);
            offset += amount;
            buffer.flip();
            return buffer;
        }
        @Override public void close() { buffer = null; }
    }
}
