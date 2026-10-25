package io.github.alicespeaker.client;

import io.github.alicespeaker.AliceSpeakerMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.Registries;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * {@code -Dalicespeaker.selftest=<world>}: creates a fresh creative world, places a speaker and talks to the real Alice:
 * a typed question, her own answer played back to her as voice input, a music request, and breaking the speaker
 * mid-answer. Every step logs "[selftest]" lines and screenshots, then the game quits.
 */
final class SelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("alicespeaker");
    private final AliceSpeakerClient owner;
    private final MinecraftClient client = MinecraftClient.getInstance();
    private final String world;
    private int stage, ticks, deadline, asked;
    private boolean failed;
    private BlockPos speaker;
    private byte[] firstVoice;
    private String waitingFor = "";
    private BooleanSupplier until = () -> true;
    private Runnable then = () -> { };

    private SelfTest(AliceSpeakerClient owner, String world) { this.owner = owner; this.world = world; }

    static void install(AliceSpeakerClient owner) {
        String world = System.getProperty("alicespeaker.selftest");
        if (world == null || world.isBlank()) return;
        SelfTest test = new SelfTest(owner, world);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> test.tick());
        LOGGER.info("[selftest] armed for a new world {}", world);
    }

    private void log(String text) { LOGGER.info("[selftest] {}", text); }
    private void fail(String text) { failed = true; LOGGER.error("[selftest] FAIL: {}", text); }
    private void check(boolean ok, String what) { if (ok) log("OK: " + what); else fail(what); }

    private void await(String what, int seconds, BooleanSupplier condition, Runnable next) {
        waitingFor = what; until = condition; then = next; deadline = ticks + seconds * 20;
    }

    private void tick() {
        ticks++;
        if (stage == 0) {
            if (client.currentScreen instanceof TitleScreen && ticks > 40) {
                stage = 1;
                log("creating world " + world);
                LevelInfo info = new LevelInfo(world, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), DataConfiguration.SAFE_MODE);
                client.createIntegratedServerLoader().createAndStart(world, info, GeneratorOptions.DEMO_OPTIONS, WorldPresets::createDemoOptions, new TitleScreen());
                await("world joined", 180, () -> client.player != null && client.world != null && client.getServer() != null && client.player.age > 60, this::prepare);
            }
            return;
        }
        if (waitingFor.isEmpty()) return;
        if (until.getAsBoolean()) { String done = waitingFor; waitingFor = ""; log("… " + done); then.run(); }
        else if (ticks >= deadline) { String what = waitingFor; waitingFor = ""; fail("timeout waiting for " + what); then.run(); }
    }

    private void command(String line) {
        MinecraftServer server = client.getServer();
        server.execute(() -> server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent().withLevel(4), line));
    }

    private void shot(String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "alice-" + name + ".png", client.getFramebuffer(), text -> { });
        log("screenshot alice-" + name + ".png");
    }

    // ---- Stage 1: a stone platform in the sky, the player facing a speaker two blocks away. Teleport first: commands
    // in chunks that are not loaded yet (the world spawn can be far from 0,0) silently do nothing.
    private void prepare() {
        check(Registries.BLOCK.getId(AliceSpeakerMod.SPEAKER).toString().equals("alicespeaker:speaker"), "speaker block registered");
        String name = client.player.getGameProfile().getName();
        command("time set day");
        command("weather clear");
        command("gamerule doDaylightCycle false");
        command("effect give " + name + " minecraft:slow_falling 600 0 true");
        command("tp " + name + " 0 201 -2 0 25");
        speaker = new BlockPos(0, 200, 0);
        await("player at the test spot", 30, () -> client.player.squaredDistanceTo(0.5, 201, -1.5) < 4 && client.world.isChunkLoaded(0, 0), () -> {
            command("fill -4 199 -4 4 199 4 minecraft:smooth_stone");
            command("fill -4 200 -4 4 204 4 minecraft:air");
            command("setblock 0 200 0 alicespeaker:speaker");
            command("tp " + name + " 0.5 200 -1.5 0 25");
            await("speaker visible to the client", 20, () -> client.world.getBlockState(speaker).isOf(AliceSpeakerMod.SPEAKER), () -> {
                check(client.world.getBlockState(speaker).isOf(AliceSpeakerMod.SPEAKER), "speaker placed in the world");
                int settle = ticks + 40;
                await("player settled", 5, () -> ticks >= settle, this::pickMicrophone);
            });
        });
    }

    // ---- Stage 1b: the microphone menu lists the capture devices and remembers the pick (in this copy's config).
    private void pickMicrophone() {
        MicrophoneScreen menu = new MicrophoneScreen(null);
        client.setScreen(menu);
        int[] drawn = new int[1];
        await("microphone menu listed the devices", 20, menu::loaded, () -> { drawn[0] = ticks + 10; await("menu drawn", 2, () -> ticks >= drawn[0], () -> {
            List<String> devices = menu.listedDevices();
            log("microphones: " + devices.size() + " " + devices);
            check(menu.loaded() && client.currentScreen == menu, "microphone menu opens");
            shot("0-microphones");
            if (!devices.isEmpty()) {
                Microphone.choose(devices.get(0));
                check(Microphone.chosen().equals(devices.get(0)), "picked microphone is remembered");
            }
            Microphone.choose("");
            menu.close();
            check(client.currentScreen == null, "microphone menu closes");
            askText();
        }); });
    }

    private BooleanSupplier answered() {
        int target = ++asked;
        return () -> owner.finished >= target;
    }

    // ---- Stage 2: a typed question; Alice answers in text and in her own voice from the block.
    private void askText() {
        shot("1-speaker");
        check(AliceSpeakerClient.findSpeaker(client) != null, "speaker found near the player");
        log("asking (text): Расскажи короткий анекдот");
        owner.ask(client, speaker, "Расскажи короткий анекдот", null);
        await("Alice answered the typed question", 60, answered(), () -> {
            AliceSpeakerClient.Reply reply = owner.lastReply;
            check(reply != null, "answer without error" + (owner.lastError == null ? "" : ": " + owner.lastError));
            if (reply == null) { askVoice(); return; }
            log("Alice: " + reply.text());
            check(!reply.text().isBlank(), "Alice's text answer is not empty");
            check(reply.pcm() != null, "Alice's own voice arrived and decoded" + (reply.pcm() == null ? " (" + reply.voiceProblem() + ")" : ""));
            if (reply.pcm() == null) { askVoice(); return; }
            double seconds = reply.pcm().length / 2.0 / OggOpus.RATE;
            log(String.format("voice %.1f s", seconds));
            check(seconds > 0.5, "voice is longer than half a second");
            firstVoice = reply.pcm();
            await("voice playing from the speaker", 5, () -> owner.sound != null && client.getSoundManager().isPlaying(owner.sound), () -> {
                check(owner.sound != null && client.getSoundManager().isPlaying(owner.sound), "voice plays from the speaker block");
                shot("2-speaking");
                await("voice finished", (int) seconds + 15, () -> owner.sound == null || !client.getSoundManager().isPlaying(owner.sound), this::askVoice);
            });
        });
    }

    // ---- Stage 3: Alice's own voice, downsampled to 16 kHz, is sent back as microphone input (Alice's own speech recognition).
    private void askVoice() {
        if (firstVoice == null) { log("voice input skipped: no voice from stage 2"); askMusic(); return; }
        byte[] pcm16k = downsample3(firstVoice, Microphone.RATE * 2 * Microphone.MAX_SECONDS);
        log("asking (voice): " + pcm16k.length / 2 / Microphone.RATE + " s of Alice's own speech");
        owner.ask(client, speaker, null, pcm16k);
        await("Alice answered the voice question", 60, answered(), () -> {
            AliceSpeakerClient.Reply reply = owner.lastReply;
            check(reply != null, "voice question answered without error" + (owner.lastError == null ? "" : ": " + owner.lastError));
            if (reply != null) {
                log("Alice heard: " + reply.heard());
                log("Alice: " + reply.text());
                check(!reply.heard().isBlank(), "Alice recognised the voice input");
                check(reply.pcm() != null, "Alice answered the voice input with her voice");
            }
            owner.stopVoice();
            askMusic();
        });
    }

    // ---- Stage 4: Yandex Music. The web Alice cannot play music, so the mod finds the track itself and plays it
    // from the speaker: a search, "next", then "stop". Needs Yandex Music to be reachable (no VPN).
    private void askMusic() {
        boolean token = YandexMusic.loadToken().length() > 0;
        log("Yandex Music token: " + (token ? "present" : "absent (search only, tracks may be 30 s previews)"));
        log("asking (text): Включи музыку группы Кино");
        owner.ask(client, speaker, "Включи музыку группы Кино", null);
        await("a track from Yandex Music plays", 90, () -> owner.nowPlaying != null && owner.musicSound != null && client.getSoundManager().isPlaying(owner.musicSound)
                || owner.musicError != null, () -> {
            check(owner.musicError == null, "Yandex Music answered" + (owner.musicError == null ? "" : ": " + owner.musicError));
            check(owner.nowPlaying != null && owner.musicSound != null && client.getSoundManager().isPlaying(owner.musicSound), "music plays from the speaker: " + owner.nowPlaying);
            if (owner.nowPlaying == null) { owner.stopMusic(); breakMidAnswer(); return; }
            shot("3-music");
            String first = owner.nowPlaying;
            log("asking (text): Следующая");
            owner.ask(client, speaker, "Следующая", null);
            await("the next track plays", 90, () -> owner.nowPlaying != null && !owner.nowPlaying.equals(first) && owner.musicSound != null
                    && client.getSoundManager().isPlaying(owner.musicSound) || owner.musicError != null, () -> {
                check(owner.nowPlaying != null && !owner.nowPlaying.equals(first), "\"следующая\" switches the track: " + owner.nowPlaying);
                log("asking (text): Выключи музыку");
                owner.ask(client, speaker, "Выключи музыку", null);
                await("music stopped", 60, () -> owner.musicSound == null, () -> {
                    check(owner.musicSound == null && owner.nowPlaying == null, "\"выключи музыку\" stops it");
                    breakMidAnswer();
                });
            });
        });
    }

    // ---- Stage 5: breaking the speaker while Alice talks silences her; without a speaker nearby nothing is asked.
    private void breakMidAnswer() {
        asked = owner.finished;   // music commands above were not counted
        log("asking (text): Расскажи сказку");
        owner.ask(client, speaker, "Расскажи сказку", null);
        await("Alice started the fairy tale", 60, () -> owner.sound != null && client.getSoundManager().isPlaying(owner.sound) || owner.finished >= asked + 1 && owner.lastReply == null, () -> {
            asked++;
            if (owner.sound == null || !client.getSoundManager().isPlaying(owner.sound)) { fail("fairy tale did not start playing" + (owner.lastError == null ? "" : ": " + owner.lastError)); finish(); return; }
            shot("4-fairy-tale");
            command("setblock 0 200 0 minecraft:air");
            await("speaker broken", 10, () -> !client.world.getBlockState(speaker).isOf(AliceSpeakerMod.SPEAKER), () ->
                await("voice stopped", 3, () -> !client.getSoundManager().isPlaying(owner.sound), () -> {
                    check(!client.getSoundManager().isPlaying(owner.sound), "breaking the speaker stops Alice's voice");
                    check(AliceSpeakerClient.findSpeaker(client) == null, "no speaker nearby after breaking it");
                    shot("5-broken");
                    finish();
                }));
        });
    }

    private void finish() {
        log(failed ? "FINISHED WITH FAILURES" : "ALL CHECKS PASSED");
        int end = ticks + 40;
        await("pause before quitting", 5, () -> ticks >= end, client::scheduleStop);
    }

    /** 48 kHz → 16 kHz by averaging every three samples (mono 16-bit little-endian), capped at {@code maxBytes}. */
    static byte[] downsample3(byte[] pcm48k, int maxBytes) {
        int samples = Math.min(pcm48k.length / 6, maxBytes / 2);
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int sum = 0;
            for (int k = 0; k < 3; k++) {
                int at = (i * 3 + k) * 2;
                sum += (short) ((pcm48k[at] & 255) | (pcm48k[at + 1] << 8));
            }
            short value = (short) (sum / 3);
            out[i * 2] = (byte) value;
            out[i * 2 + 1] = (byte) (value >> 8);
        }
        return out;
    }
}
