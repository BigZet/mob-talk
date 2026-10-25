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
    private KeyBinding talkKey;
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
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        SelfTest.install(this);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(this::cancelAll));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommandManager.literal("alice").then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(context -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            BlockPos speaker = findSpeaker(client);
                            if (speaker == null) { notice(client, "Рядом нет колонки с Алисой", Formatting.RED); return 0; }
                            ask(client, speaker, StringArgumentType.getString(context, "text"), null);
                            return 1;
                        }))));
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
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
                    notice(client, "Алиса: " + reply.text(), Formatting.LIGHT_PURPLE);
                    if (reply.music()) notice(client, "Алиса хочет включить музыку: в прототипе пока только голос (подробности в logs/alicespeaker-protocol.log)", Formatting.DARK_GRAY);
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
