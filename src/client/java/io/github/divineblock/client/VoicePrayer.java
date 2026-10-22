package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.LocalWhisper;
import io.github.divineblock.core.PcmWav;
import io.github.divineblock.game.AltarBlock;
import io.github.divineblock.game.PrayPayload;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/** Praying aloud: hold the key near an altar (15 seconds at most), release, and Whisper turns the words into the prayer.
 * The microphone records only while the key is held; recognition runs on a worker, and the prayer is dropped if the player
 * left the world or the altar meanwhile. */
final class VoicePrayer {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "divineblock-whisper"); t.setDaemon(true); return t; });
    private static final MicrophoneRecorder RECORDER = new MicrophoneRecorder();
    private static MicrophoneRecorder.Capture capture;
    private static BlockPos altar;
    private static ClientWorld world;
    private static volatile boolean busy;
    /** Every recognised prayer, for the self-test. */
    static volatile java.util.function.Consumer<String> heard = text -> { };

    private VoicePrayer() { }

    static void tick(MinecraftClient client, KeyBinding key) {
        if (client.player == null || client.world == null) { cancel(); return; }
        boolean held = key.isPressed() && client.currentScreen == null;
        if (capture == null && held) start(client);
        else if (capture != null && !held) finish(client);
        else if (capture != null) {
            int bars = (int) Math.round(capture.level * 10);
            client.player.sendMessage(Text.literal("Молитва слышна… " + "▮".repeat(bars) + "▯".repeat(10 - bars) + " (отпусти, чтобы вознести)").formatted(Formatting.GOLD), true);
        }
    }

    private static void start(MinecraftClient client) {
        if (busy) { debug(client, "Бог ещё внимает прежней молитве…"); return; }
        if (io.github.divineblock.boss.BossEffects.silenced(client.player)) {
            client.player.sendMessage(Text.literal("Обет молчания: уста запечатаны").formatted(Formatting.DARK_GRAY), true);
            return;
        }
        BlockPos found = findAltar(client);
        if (found == null) { debug(client, "Молиться вслух можно лишь у алтаря."); return; }
        DivineConfig config = DivineBlockMod.config();
        try { capture = RECORDER.start(config.microphoneName, PcmWav.MAX_SECONDS); }
        catch (RuntimeException occupied) { client.player.sendMessage(Text.literal(occupied.getMessage()).formatted(Formatting.RED), true); return; }
        altar = found; world = client.world;
    }

    private static void finish(MinecraftClient client) {
        MicrophoneRecorder.Capture done = capture;
        capture = null;
        done.stop();
        process(client, done.wav, altar, world);
    }

    /** Recognises a recorded prayer and sends it to the altar; the self-test feeds a synthesised one through here too. */
    static void process(MinecraftClient client, java.util.concurrent.CompletableFuture<byte[]> recording, BlockPos target, ClientWorld where) {
        DivineConfig config = DivineBlockMod.config();
        busy = true;
        debug(client, "Бог вслушивается…");
        recording.whenComplete((wav, failure) -> WORKER.execute(() -> {
            String text = null, problem = null;
            try {
                if (failure != null) throw failure instanceof Exception e ? e : new Exception(failure);
                if (wav == null) problem = "Слишком коротко: держи клавишу, пока молишься.";
                else {
                    WhisperHelper.ensureReady(config, line -> client.execute(() -> { if (client.player != null) client.player.sendMessage(Text.literal(line).formatted(Formatting.GRAY), true); }));
                    text = new LocalWhisper(config.whisperUrl).transcribe(wav);
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); problem = "Молитва прервана."; }
            catch (Exception error) { problem = error.getMessage(); io.github.divineblock.DivineBlockMod.LOG.warn("Божественный блок: молитва голосом не распознана: {}", problem); }
            String prayer = text, message = problem;
            client.execute(() -> {
                busy = false;
                if (client.player == null || client.world != where) return; // Left the world: the prayer is lost.
                if (message != null) { client.player.sendMessage(Text.literal(message).formatted(Formatting.RED), true); return; }
                if (prayer == null || prayer.isBlank()) { debug(client, "Бог не расслышал ни слова."); return; }
                if (!target.isWithinDistance(client.player.getBlockPos(), 8)) { debug(client, "Ты отошёл от алтаря."); return; }
                if (config.debug) client.player.sendMessage(Text.literal("Ты молишься: «" + prayer + "»").formatted(Formatting.GRAY, Formatting.ITALIC), false);
                heard.accept(prayer);
                if (ClientPlayNetworking.canSend(PrayPayload.ID)) ClientPlayNetworking.send(new PrayPayload(target, prayer.length() > PrayPayload.MAX_TEXT ? prayer.substring(0, PrayPayload.MAX_TEXT) : prayer));
            });
        }));
    }

    /** The altar under the crosshair, else the nearest within six blocks. */
    static BlockPos findAltar(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && client.world.getBlockState(block.getBlockPos()).getBlock() instanceof AltarBlock
                && block.getBlockPos().isWithinDistance(client.player.getBlockPos(), 8)) return block.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(client.player.getBlockPos(), 6, 4, 6))
            if (client.world.getBlockState(pos).getBlock() instanceof AltarBlock) return pos.toImmutable();
        return null;
    }

    /** The settings' microphone check: four seconds from the chosen microphone, then what Whisper heard. */
    static void testMicrophone(DivineConfig config, java.util.function.Consumer<String> status) {
        if (capture != null || busy) { status.accept("Микрофон сейчас занят молитвой."); return; }
        MicrophoneRecorder.Capture test;
        try { test = RECORDER.start(config.microphoneName, 4); }
        catch (RuntimeException occupied) { status.accept(occupied.getMessage()); return; }
        DivineConfig settings = config.copy();
        test.wav.whenComplete((wav, failure) -> WORKER.execute(() -> {
            try {
                if (failure != null) { status.accept(failure.getMessage() == null ? "Микрофон недоступен." : failure.getMessage()); return; }
                if (wav == null) { status.accept("Микрофон молчит: тишина или нет доступа."); return; }
                status.accept("Распознаю…");
                WhisperHelper.ensureReady(settings, status);
                String text = new LocalWhisper(settings.whisperUrl).transcribe(wav);
                status.accept(text.isBlank() ? "Записалось, но слов не слышно." : "Слышно: «" + text + "»");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception error) { status.accept(error.getMessage()); }
        }));
    }

    /** A service line for the action bar: only in debug mode. */
    private static void debug(MinecraftClient client, String line) {
        if (client.player != null && DivineBlockMod.config().debug) client.player.sendMessage(Text.literal(line).formatted(Formatting.GRAY), true);
    }

    static void cancel() {
        if (capture != null) { capture.cancel(); capture = null; }
    }

    static void shutdown() { cancel(); RECORDER.close(); WhisperHelper.stopOwned(); }
}
