package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodVoice;
import io.github.divineblock.game.GodLinePayload;
import java.nio.ByteBuffer;
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

/** Voices the god's lines: text to speech on a worker, then a sound that comes from everywhere, as a voice from the sky.
 * A newer line cuts the older one off; leaving the world drops whatever was still on its way. */
final class GodVoicePlayer {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "divineblock-voice"); t.setDaemon(true); return t; });
    private static final AtomicInteger generation = new AtomicInteger();
    private static GodSound playing;
    /** Seconds of each voiced line, for the self-test. */
    static volatile java.util.function.DoubleConsumer played = seconds -> { };

    private GodVoicePlayer() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(GodLinePayload.ID, (payload, context) -> speak(context.client(), payload.line()));
    }

    static final String PREVIEW_LINE = "Ты взвешен на весах и найден лёгким, смертный. Что посеял, то и пожнёшь.";
    /** The dry preview line per voice: the knobs are tuned by ear without paying for speech again. */
    private static final java.util.Map<String, byte[]> previewCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Plays the preview line with the given (unsaved) settings, in a world or in a menu. */
    static void preview(MinecraftClient client, DivineConfig config, java.util.function.Consumer<String> status) {
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
                voiced = settings.voiceTemple ? io.github.divineblock.core.VoiceEffects.apply(dry, GodVoice.SAMPLE_RATE, settings.voiceEffects()) : dry;
            } catch (Exception failure) { status.accept("Голос недоступен: " + failure.getMessage()); return; }
            byte[] pcm = voiced;
            client.execute(() -> {
                if (generation.get() != mine) return;
                if (playing != null) client.getSoundManager().stop(playing);
                playing = new GodSound(pcm);
                client.getSoundManager().play(playing);
                played.accept(pcm.length / (2.0 * GodVoice.SAMPLE_RATE));
                status.accept("");
            });
        });
    }

    static void speak(MinecraftClient client, String line) {
        DivineConfig config = DivineBlockMod.config();
        if (!config.voice || line == null || line.isBlank()) return;
        int mine = generation.incrementAndGet();
        ClientWorld world = client.world;
        WORKER.execute(() -> {
            if (generation.get() != mine) return; // A newer line is already waiting.
            byte[] voiced;
            try {
                voiced = new GodVoice(config, DivineBlockMod.configDirectory()).synthesize(line);
                if (config.voiceTemple) voiced = io.github.divineblock.core.VoiceEffects.apply(voiced, GodVoice.SAMPLE_RATE, config.voiceEffects());
            }
            catch (Exception failure) { DivineBlockMod.LOG.info("Божественный блок: голос бога молчит: {}", failure.getMessage()); return; }
            catch (Throwable ignored) { return; }
            byte[] pcm = voiced;
            client.execute(() -> {
                if (generation.get() != mine || client.world != world || world == null) return;
                if (playing != null) client.getSoundManager().stop(playing);
                playing = new GodSound(pcm);
                played.accept(pcm.length / (2.0 * GodVoice.SAMPLE_RATE));
                client.getSoundManager().play(playing);
            });
        });
    }

    /** Non-positional: the god is heard the same wherever the player looks. */
    static final class GodSound extends AbstractSoundInstance {
        private final byte[] pcm;
        GodSound(byte[] pcm) {
            super(SoundEvent.of(Identifier.of("divineblock", "god_voice")), SoundCategory.VOICE, SoundInstance.createRandom());
            this.pcm = pcm;
            this.relative = true;
            this.attenuationType = AttenuationType.NONE;
            this.x = 0; this.y = 0; this.z = 0;
        }
        @Override public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
            return CompletableFuture.completedFuture(new PcmStream(pcm));
        }
    }

    /** Whole 24 kHz PCM handed to the sound engine in small chunks. */
    static final class PcmStream implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(GodVoice.SAMPLE_RATE, 16, 1, true, false);
        private static final int CHUNK = 4_800;
        private final byte[] pcm;
        private int offset;
        private ByteBuffer buffer;
        PcmStream(byte[] pcm) { this.pcm = pcm; }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public ByteBuffer read(int size) {
            int amount = Math.min(Math.min(size, CHUNK), pcm.length - offset) & ~1;
            if (amount <= 0) return null;
            if (buffer == null) buffer = ByteBuffer.allocateDirect(CHUNK);
            buffer.clear();
            buffer.put(pcm, offset, amount);
            offset += amount;
            buffer.flip();
            return buffer;
        }
        @Override public void close() { buffer = null; }
    }
}
