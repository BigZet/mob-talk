package io.github.alicespeaker.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.alicespeaker.AliceSpeakerMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hold the key (or type /alice ...) near a speaker block: the words go to the real Alice and her voice comes out
 * of the block. Network, decoding, microphone and the protocol log all stay off the game thread.
 */
public final class AliceSpeakerClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("alicespeaker");
    private static final int SEARCH_RADIUS = 6;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "alicespeaker-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Path protocolLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("alicespeaker-protocol.log");
    private KeyBinding talkKey, micKey;
    private Microphone mic;
    private BlockPos micSpeaker;
    private AliceSession session;
    SpeakerVoiceSound sound;
    private int generation;
    /** Last finished answer or error, and how many asks have finished: read by the self-test. */
    volatile Reply lastReply;
    volatile String lastError;
    volatile int finished;

    @Override public void onInitializeClient() {
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.alicespeaker.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.alicespeaker"));
        micKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.alicespeaker.microphone", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.alicespeaker"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        SelfTest.install(this);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(this::cancelAll));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> {
            dispatcher.register(ClientCommandManager.literal("alice").then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                    .executes(context -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        BlockPos speaker = findSpeaker(client);
                        if (speaker == null) { notice(client, "Рядом нет колонки с Алисой", Formatting.RED); return 0; }
                        ask(client, speaker, StringArgumentType.getString(context, "text"), null);
                        return 1;
                    })));
            // send, not execute: the chat screen closes itself right after the command and would close the menu too.
            dispatcher.register(ClientCommandManager.literal("alicemic").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.send(() -> client.setScreen(new MicrophoneScreen(null)));
                return 1;
            }));
        });
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        while (micKey.wasPressed()) if (client.currentScreen == null && mic == null) client.setScreen(new MicrophoneScreen(null));
        tickMusic(client);
        boolean held = talkKey.isPressed() && client.currentScreen == null;
        if (held && mic == null) {
            BlockPos speaker = findSpeaker(client);
            if (speaker == null) {
                if (talkKey.wasPressed()) notice(client, "Рядом нет колонки с Алисой", Formatting.RED);
                return;
            }
            stopVoice();
            cancelSession();
            micSpeaker = speaker;
            mic = Microphone.start();
            overlay(client, "Алиса слушает… (отпустите кнопку)");
        } else if (!held && mic != null) {
            Microphone finished = mic;
            BlockPos speaker = micSpeaker;
            mic = null;
            finished.stop();
            int expected = generation;
            finished.pcm.whenComplete((pcm, failure) -> client.execute(() -> {
                if (expected != generation) return;
                if (finished.fellBack) notice(client, "Микрофон «" + Microphone.chosen() + "» не найден, записала с системного (выбор: N)", Formatting.DARK_GRAY);
                if (failure != null) notice(client, failure.getMessage(), Formatting.RED);
                else if (pcm == null) overlay(client, "Слишком коротко");
                else ask(client, speaker, null, pcm);
            }));
        }
        while (talkKey.wasPressed()) { }
    }

    void ask(MinecraftClient client, BlockPos speaker, String text, byte[] pcm) {
        stopVoice();
        cancelSession();
        int expected = ++generation;
        ClientWorld world = client.world;
        AliceAuth auth = AliceAuth.load();
        if (text != null) notice(client, "Вы: " + text, Formatting.GRAY);
        // Typed music commands go straight to Yandex Music: the web Alice cannot play music and answers them with
        // an empty player directive, so waiting for her only delays "next" and "stop".
        YandexMusic.Intent typed = text != null ? YandexMusic.intent(text, musicSound != null, false) : null;
        if (typed != null) { handleMusic(client, client.world, speaker, typed); return; }
        overlay(client, auth.hasLogin() ? "Алиса думает…" : "Алиса думает… (без входа в Яндекс)");
        logLine("---- " + LocalTime.now() + (text != null ? " text" : " voice " + pcm.length + " bytes") + ", cookies " + (auth.cookie().isBlank() ? "no" : "yes") + ", sync.json " + (auth.sync().isBlank() ? "no" : "yes"));
        AliceSession next = text != null ? AliceSession.text(text, auth, this::logLine) : AliceSession.voice(pcm, auth, this::logLine);
        session = next;
        next.start()
                .thenApplyAsync(this::decode, WORKER)
                .whenComplete((reply, failure) -> client.execute(() -> {
                    if (expected != generation || client.world != world) return;
                    session = null;
                    finished++;
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        lastReply = null;
                        lastError = cause.getMessage() == null ? "Алиса недоступна" : cause.getMessage();
                        notice(client, lastError, Formatting.RED);
                        return;
                    }
                    lastReply = reply;
                    lastError = null;
                    if (!reply.heard().isBlank()) notice(client, "Вы: " + reply.heard(), Formatting.GRAY);
                    // The web chat Alice cannot play music; music requests go to Yandex Music instead of her refusal.
                    YandexMusic.Intent intent = YandexMusic.intent(text != null ? text : reply.heard(), musicSound != null, reply.music());
                    if (intent != null) { handleMusic(client, world, speaker, intent); return; }
                    notice(client, "Алиса: " + (reply.text().isBlank() ? "(ответила без слов)" : reply.text()), Formatting.LIGHT_PURPLE);
                    if (reply.pcm() == null) {
                        notice(client, "Голос Алисы не пришёл: " + reply.voiceProblem(), Formatting.DARK_GRAY);
                        return;
                    }
                    if (!world.getBlockState(speaker).isOf(AliceSpeakerMod.SPEAKER)) return;
                    sound = new SpeakerVoiceSound(client, world, speaker, reply.pcm(), OggOpus.RATE);
                    client.getSoundManager().play(sound);
                }));
    }

    record Reply(String text, String heard, byte[] pcm, String voiceProblem, boolean music) { }

    // ---- Yandex Music: a queue of tracks played one after another from the speaker.
    SpeakerVoiceSound musicSound;
    volatile String nowPlaying, musicError;
    private List<YandexMusic.Track> queue = List.of();
    private int queueIndex, musicGeneration;
    private boolean musicStarted, waveQueue;
    private BlockPos musicSpeaker;

    private void handleMusic(MinecraftClient client, ClientWorld world, BlockPos speaker, YandexMusic.Intent intent) {
        logLine("music intent " + intent.kind() + " \"" + intent.query() + "\"");
        switch (intent.kind()) {
            case STOP -> { stopMusic(); notice(client, "Алиса: Выключаю музыку", Formatting.LIGHT_PURPLE); }
            case NEXT -> { if (!queue.isEmpty()) playTrack(client, world, musicSpeaker, queueIndex + 1); }
            case PLAY -> {
                YandexMusic music = new YandexMusic(YandexMusic.loadToken(), this::logLine);
                if (!music.hasToken() && intent.query().isBlank()) {
                    musicError = "нет токена";
                    notice(client, "Для «Моей волны» нужен токен Яндекс Музыки: %USERPROFILE%\\.alice-speaker\\music_token.txt (см. README)", Formatting.RED);
                    return;
                }
                stopMusic();
                int expected = ++musicGeneration;
                musicError = null;
                overlay(client, intent.query().isBlank() ? "Включаю «Мою волну»…" : "Ищу в Яндекс Музыке: " + intent.query());
                CompletableFuture.supplyAsync(() -> {
                    try { return music.find(intent.query()); } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
                }, WORKER).whenComplete((tracks, failure) -> client.execute(() -> {
                    if (expected != musicGeneration || client.world != world) return;
                    if (failure != null) { musicFailed(client, failure); return; }
                    if (tracks.isEmpty()) { musicError = "ничего не нашлось"; notice(client, "Алиса: Не нашла такую музыку", Formatting.LIGHT_PURPLE); return; }
                    queue = tracks;
                    waveQueue = intent.query().isBlank();
                    musicSpeaker = speaker;
                    playTrack(client, world, speaker, 0);
                }));
            }
        }
    }

    private void playTrack(MinecraftClient client, ClientWorld world, BlockPos speaker, int index) {
        if (musicSound != null) musicSound.stop();
        musicSound = null;
        musicStarted = false;
        if (index >= queue.size()) {
            if (waveQueue) handleMusic(client, world, speaker, new YandexMusic.Intent(YandexMusic.Kind.PLAY, ""));
            else { nowPlaying = null; notice(client, "Музыка закончилась", Formatting.DARK_GRAY); }
            return;
        }
        queueIndex = index;
        YandexMusic.Track track = queue.get(index);
        int expected = ++musicGeneration;
        YandexMusic music = new YandexMusic(YandexMusic.loadToken(), this::logLine);
        overlay(client, "Загружаю: " + track.label());
        CompletableFuture.supplyAsync(() -> {
            try { return music.load(track); } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, WORKER).whenComplete((audio, failure) -> client.execute(() -> {
            if (expected != musicGeneration || client.world != world) return;
            if (failure != null) { musicFailed(client, failure); return; }
            if (!world.getBlockState(speaker).isOf(AliceSpeakerMod.SPEAKER)) { stopMusic(); return; }
            musicSound = new SpeakerVoiceSound(client, world, speaker, audio.pcm(), audio.rate());
            client.getSoundManager().play(musicSound);
            nowPlaying = track.label();
            notice(client, "♪ " + track.label() + (audio.preview() ? " (отрывок: без подписки Плюс Яндекс даёт 30 секунд)" : ""), Formatting.LIGHT_PURPLE);
        }));
    }

    private void musicFailed(MinecraftClient client, Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        musicError = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        logLine("music failed: " + musicError);
        notice(client, "Музыка: " + musicError, Formatting.RED);
    }

    void stopMusic() {
        musicGeneration++;
        if (musicSound != null) musicSound.stop();
        musicSound = null;
        nowPlaying = null;
        queue = List.of();
    }

    /** Next track once the current one has played out; silence when the speaker is gone. */
    private void tickMusic(MinecraftClient client) {
        if (musicSound == null) return;
        boolean playing = client.getSoundManager().isPlaying(musicSound);
        if (playing) { musicStarted = true; return; }
        if (!musicStarted) return;
        if (musicSpeaker == null || !client.world.getBlockState(musicSpeaker).isOf(AliceSpeakerMod.SPEAKER)) { stopMusic(); return; }
        playTrack(client, client.world, musicSpeaker, queueIndex + 1);
    }

    private Reply decode(AliceSession.Answer answer) {
        boolean music = answer.notes().contains("music");
        if (answer.voice().isEmpty()) return new Reply(answer.text(), answer.heard(), null, "сервер не прислал аудио", music);
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        String problem = "";
        for (byte[] stream : answer.voice()) {
            logLine("audio stream " + stream.length + " bytes, " + answer.voiceFormat() + ", starts " + hex(stream));
            try {
                if (OggOpus.isOgg(stream)) pcm.writeBytes(OggOpus.toPcm(stream));
                else if (answer.voiceFormat().startsWith("audio/x-pcm")) pcm.writeBytes(OggOpus.upsampleTo48k(stream, OggOpus.pcmRate(answer.voiceFormat())));
                else problem = "неизвестный формат аудио " + answer.voiceFormat() + " (" + hex(stream) + ")";
            } catch (IOException broken) {
                problem = broken.getMessage();
            }
        }
        return new Reply(answer.text(), answer.heard(), pcm.size() > 0 ? pcm.toByteArray() : null, problem, music);
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(8, data.length); i++) out.append(String.format("%02x", data[i]));
        return out.toString();
    }

    static BlockPos findSpeaker(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) return null;
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && world.getBlockState(hit.getBlockPos()).isOf(AliceSpeakerMod.SPEAKER)) return hit.getBlockPos().toImmutable();
        for (BlockPos pos : BlockPos.iterateOutwards(client.player.getBlockPos(), SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))
            if (world.getBlockState(pos).isOf(AliceSpeakerMod.SPEAKER)) return pos.toImmutable();
        return null;
    }

    void stopVoice() {
        if (sound != null) sound.stop();
        sound = null;
    }

    private void cancelSession() {
        generation++;
        if (session != null) session.cancel();
        session = null;
    }

    private void cancelAll() {
        if (mic != null) mic.cancel();
        mic = null;
        stopVoice();
        stopMusic();
        cancelSession();
    }

    private static void notice(MinecraftClient client, String message, Formatting color) {
        if (client.player != null) client.inGameHud.getChatHud().addMessage(Text.literal(message).formatted(color));
    }

    private static void overlay(MinecraftClient client, String message) {
        if (client.player != null) client.player.sendMessage(Text.literal(message).formatted(Formatting.LIGHT_PURPLE), true);
    }

    /** Directive names and payload excerpts only; cookies and auth tokens never reach this file. */
    private void logLine(String line) {
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(protocolLog.getParent());
                Files.writeString(protocolLog, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                LOG.debug("protocol log failed", failure);
            }
        }, WORKER);
    }
}
