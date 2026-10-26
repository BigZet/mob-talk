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
    private static final java.util.regex.Pattern MUSIC_REFUSAL = java.util.regex.Pattern.compile("авториз|войдите|не могу включить|слушать музыку", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
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
                client.send(() -> client.setScreen(new AliceSettingsScreen(null)));
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("alice-authprobe").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();
                notice(client, "Проверяю, какой вход принимает Алиса (до пары минут)…", Formatting.GRAY);
                probeAuth().whenComplete((lines, failure) -> client.execute(() -> {
                    if (failure != null) notice(client, "Проверка входа: " + failure.getMessage(), Formatting.RED);
                    else lines.forEach(line -> notice(client, line, Formatting.GRAY));
                }));
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("alice-login").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.send(() -> client.setScreen(new YandexLoginScreen(new AliceSettingsScreen(null))));
                return 1;
            }));
        });
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        while (micKey.wasPressed()) if (client.currentScreen == null && mic == null) client.setScreen(new AliceSettingsScreen(null));
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
        // Typed "next", "stop", "continue" go straight to the player: waiting for Alice only delays them. "Включи …" goes
        // to Alice when logged in (she picks the album or playlist herself), else straight to the Yandex Music search.
        YandexMusic.Intent typed = text != null ? YandexMusic.intent(text, musicSound != null, false) : null;
        if (typed != null && (typed.kind() != YandexMusic.Kind.PLAY || auth.oauthToken().isBlank())) { handleMusic(client, client.world, speaker, typed); return; }
        overlay(client, auth.hasLogin() ? "Алиса думает…" : "Алиса думает… (без входа в Яндекс)");
        logLine("---- " + LocalTime.now() + (text != null ? " text" : " voice " + pcm.length + " bytes") + ", cookies " + (auth.cookie().isBlank() ? "no" : "yes")
                + ", sync.json " + (auth.sync().isBlank() ? "no" : "yes") + ", oauth " + (auth.oauthToken().isBlank() ? "no" : "yes"));
        AliceSession next = text != null ? AliceSession.text(text, auth, this::logLine) : AliceSession.voice(pcm, auth, this::logLine);
        session = next;
        next.start()
                .exceptionallyCompose(failure -> retryWithFreshLogin(failure, auth, text, pcm))
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
                    // Logged in, she answers "Включаю …" with a link to her own player: play exactly that from the speaker.
                    if (!reply.musicUri().isBlank()) {
                        if (!reply.text().isBlank()) notice(client, "Алиса: " + reply.text(), Formatting.LIGHT_PURPLE);
                        YandexMusic.Intent fallback = YandexMusic.intent(text != null ? text : reply.heard(), musicSound != null, true);
                        playFromAlice(client, world, speaker, reply.musicUri(), fallback != null && fallback.kind() == YandexMusic.Kind.PLAY ? fallback.query() : null);
                        return;
                    }
                    // The web chat Alice cannot play music; music requests go to Yandex Music instead of her refusal.
                    // Her music refusals ("авторизуйтесь…", or a bare player directive) mean she heard a music request.
                    boolean refusedMusic = reply.music() && (reply.text().isBlank() || MUSIC_REFUSAL.matcher(reply.text()).find());
                    YandexMusic.Intent intent = YandexMusic.intent(text != null ? text : reply.heard(), musicSound != null, refusedMusic);
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

    record Reply(String text, String heard, byte[] pcm, String voiceProblem, boolean music, String musicUri) { }

    /** InvalidAuth with an in-game login: mint a new token from the x_token and ask once more. */
    private CompletableFuture<AliceSession.Answer> retryWithFreshLogin(Throwable failure, AliceAuth auth, String text, byte[] pcm) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (!(cause instanceof AliceSession.InvalidAuth)) return CompletableFuture.failedFuture(cause);
        return CompletableFuture.supplyAsync(() -> {
            String xToken = YandexLogin.savedXToken();
            if (xToken.isBlank()) throw new java.util.concurrent.CompletionException(cause);
            try {
                String fresh = new YandexLogin().musicToken(xToken);
                YandexLogin.saveMusicToken(fresh);
                logLine("login refused, minted a new token from the x_token");
                return fresh;
            } catch (IOException renew) { throw new java.util.concurrent.CompletionException(cause); }
        }, WORKER).thenCompose(fresh -> {
            AliceAuth renewed = new AliceAuth(auth.cookie(), auth.authToken(), auth.sync(), fresh);
            return (text != null ? AliceSession.text(text, renewed, this::logLine) : AliceSession.voice(pcm, renewed, this::logLine)).start();
        });
    }

    /**
     * Which login the web Alice accepts: asks "Как меня зовут?" once per way of passing the in-game login (sync.json as
     * is, an OAuth token in the handshake, session cookies of yandex.ru or ya.ru). Only her answers are reported.
     */
    CompletableFuture<List<String>> probeAuth() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> lines = new java.util.ArrayList<>();
            String xToken = YandexLogin.savedXToken();
            if (xToken.isBlank()) { lines.add("нет входа из игры (x_token.txt)"); return lines; }
            String sync = AliceAuth.load().sync(), music = YandexLogin.savedMusicToken(), yandexCookies = "", yaCookies = "";
            try { if (music.isBlank()) music = new YandexLogin().musicToken(xToken); } catch (IOException failure) { lines.add("токен Музыки: " + failure.getMessage()); }
            try { yandexCookies = new YandexLogin().sessionCookies(xToken, "https://www.yandex.ru"); } catch (IOException failure) { lines.add("cookies yandex.ru: " + failure.getMessage()); }
            try { yaCookies = new YandexLogin().sessionCookies(xToken, "https://ya.ru"); } catch (IOException failure) { lines.add("cookies ya.ru: " + failure.getMessage()); }
            logLine("auth probe: sync.json " + (sync.isBlank() ? "no" : "yes") + ", music token " + (music.isBlank() ? "no" : "yes")
                    + ", yandex.ru cookies " + cookieNames(yandexCookies) + ", ya.ru cookies " + cookieNames(yaCookies));
            java.util.Map<String, AliceAuth> ways = new java.util.LinkedHashMap<>();
            ways.put("A sync.json", new AliceAuth("", "", sync, ""));
            ways.put("B oauth_token=Музыка", new AliceAuth("", "", "", music));
            ways.put("C sync.json+oauth_token=Музыка", new AliceAuth("", "", sync, music));
            ways.put("D oauth_token=x_token", new AliceAuth("", "", "", xToken));
            ways.put("E cookies yandex.ru", new AliceAuth(yandexCookies, "", "", ""));
            ways.put("E2 cookies yandex.ru+sync.json", new AliceAuth(yandexCookies, "", sync, ""));
            ways.put("F cookies ya.ru", new AliceAuth(yaCookies, "", "", ""));
            ways.put("G cookies ya.ru+sync.json", new AliceAuth(yaCookies, "", sync, ""));
            // "Как меня зовут?" is refused on the web client whatever the login; these two ask anonymous users to log in.
            ways.forEach((name, auth) -> {
                for (String question : new String[] {"Включи песню группа крови", "Напомни завтра в 10 утра купить хлеб"}) {
                    String answer;
                    try { answer = "«" + AliceSession.text(question, auth, this::logLine).start().get(45, java.util.concurrent.TimeUnit.SECONDS).text() + "»"; }
                    catch (Exception failure) { answer = "ошибка: " + (failure.getCause() != null ? failure.getCause().getMessage() : failure.getMessage()); }
                    logLine("auth probe " + name + " / " + question + ": " + answer);
                    lines.add(name + " / " + question + ": " + answer);
                }
            });
            return lines;
        });
    }

    private static String cookieNames(String header) {
        if (header.isBlank()) return "none";
        return "[" + java.util.Arrays.stream(header.split(";\\s*")).map(c -> c.split("=", 2)[0]).collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    // ---- Yandex Music: a queue of tracks played one after another from the speaker.
    SpeakerVoiceSound musicSound;
    volatile String nowPlaying, musicError;
    private List<YandexMusic.Track> queue = List.of(), pausedQueue = List.of();
    private int queueIndex, musicGeneration, pausedIndex;
    private boolean musicStarted, waveQueue, pausedWave;
    private BlockPos musicSpeaker;

    private void handleMusic(MinecraftClient client, ClientWorld world, BlockPos speaker, YandexMusic.Intent intent) {
        logLine("music intent " + intent.kind() + " \"" + intent.query() + "\"");
        switch (intent.kind()) {
            case STOP -> {
                if (!queue.isEmpty()) { pausedQueue = queue; pausedIndex = queueIndex; pausedWave = waveQueue; }
                stopMusic();
                notice(client, "Алиса: Выключаю музыку", Formatting.LIGHT_PURPLE);
            }
            case NEXT -> { if (!queue.isEmpty()) playTrack(client, world, musicSpeaker, queueIndex + 1); }
            case RESUME -> {
                // The track starts over: a decoded track is not kept after "стоп". Nothing stopped yet → "My wave".
                if (pausedQueue.isEmpty()) { handleMusic(client, world, speaker, new YandexMusic.Intent(YandexMusic.Kind.PLAY, "")); return; }
                queue = pausedQueue;
                waveQueue = pausedWave;
                musicSpeaker = speaker;
                pausedQueue = List.of();
                playTrack(client, world, speaker, pausedIndex);
            }
            case PLAY -> {
                stopMusic();
                int expected = ++musicGeneration;
                musicError = null;
                overlay(client, intent.query().isBlank() ? "Включаю «Мою волну»…" : "Ищу в Яндекс Музыке: " + intent.query());
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return YandexMusic.withToken(this::logLine, music -> {
                            if (!music.hasToken() && intent.query().isBlank()) throw new IOException("для «Моей волны» войдите в Яндекс: N → «Войти в Яндекс»");
                            return music.find(intent.query());
                        });
                    } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
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

    /** Alice's own pick (her musicsdk:// link); if it names nothing playable, the words are searched instead. */
    private void playFromAlice(MinecraftClient client, ClientWorld world, BlockPos speaker, String uri, String fallbackQuery) {
        stopMusic();
        int expected = ++musicGeneration;
        musicError = null;
        overlay(client, "Включаю…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return YandexMusic.withToken(this::logLine, music -> {
                    List<YandexMusic.Track> tracks = music.fromAlice(uri);
                    return tracks.isEmpty() && fallbackQuery != null ? music.find(fallbackQuery) : tracks;
                });
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, WORKER).whenComplete((tracks, failure) -> client.execute(() -> {
            if (expected != musicGeneration || client.world != world) return;
            if (failure != null) { musicFailed(client, failure); return; }
            if (tracks.isEmpty()) { musicError = "ничего не нашлось"; notice(client, "Алиса: Не нашла, что включить", Formatting.LIGHT_PURPLE); return; }
            queue = tracks;
            waveQueue = false;
            musicSpeaker = speaker;
            playTrack(client, world, speaker, 0);
        }));
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
        overlay(client, "Загружаю: " + track.label());
        CompletableFuture.supplyAsync(() -> {
            try { return YandexMusic.withToken(this::logLine, music -> music.load(track)); } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
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
        if (answer.voice().isEmpty()) return new Reply(answer.text(), answer.heard(), null, "сервер не прислал аудио", music, answer.musicUri());
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
        return new Reply(answer.text(), answer.heard(), pcm.size() > 0 ? pcm.toByteArray() : null, problem, music, answer.musicUri());
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
        pausedQueue = List.of();
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
