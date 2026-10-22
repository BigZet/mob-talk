package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Молитва голосом через сервер: аудио от клиента распознаёт Mob Talk тем же путём, что и речь к мобам (entrypoint
 * {@code mobtalk-stt-player} или {@code mobtalk-stt}, без зависимости от его jar), и текст идёт в {@link Prayers#handle}, как
 * написанная молитва. Без Mob Talk приёмник не регистрируется: клиенты видят это через {@code canSend} и молятся локально. */
public final class ServerPrayerVoice {
    private ServerPrayerVoice() { }
    /** Одна молитва на игрока в распознавании. */
    private static final Map<UUID, Boolean> busy = new ConcurrentHashMap<>();
    private static BiFunction<UUID, byte[], CompletableFuture<String>> recognizer;

    /** Из {@code DivineBlockMod.onInitialize}: тип пакета регистрируется всегда, приёмник — только если есть распознавание. */
    public static void register() {
        recognizer = find();
        if (recognizer == null) return;
        ServerPlayNetworking.registerGlobalReceiver(PrayAudioPayload.ID, (payload, context) -> receive(context.player(), payload));
        DivineBlockMod.LOG.info("Божественный блок: молитва голосом может распознаваться на сервере (Mob Talk)");
    }

    @SuppressWarnings("unchecked")
    static BiFunction<UUID, byte[], CompletableFuture<String>> find() {
        FabricLoader loader = FabricLoader.getInstance();
        for (var container : loader.getEntrypointContainers("mobtalk-stt-player", Object.class))
            if (container.getEntrypoint() instanceof BiFunction<?, ?, ?> function) return (BiFunction<UUID, byte[], CompletableFuture<String>>) function;
        for (var container : loader.getEntrypointContainers("mobtalk-stt", Object.class))
            if (container.getEntrypoint() instanceof Function<?, ?> function) {
                Function<byte[], CompletableFuture<String>> plain = (Function<byte[], CompletableFuture<String>>) function;
                return (player, wav) -> plain.apply(wav);
            }
        return null;
    }

    private static void receive(ServerPlayerEntity player, PrayAudioPayload payload) {
        MinecraftServer server = player.getServer();
        if (server == null || recognizer == null) return;
        byte[] wav = payload.wav();
        if (wav.length <= 44) return;
        if (!payload.test() && !payload.altar().isWithinDistance(player.getBlockPos(), 8)) return;
        UUID id = player.getUuid();
        if (busy.putIfAbsent(id, true) != null) { say(player, "Бог ещё внимает прежней молитве…", Formatting.GRAY); return; }
        CompletableFuture<String> heard;
        try { heard = recognizer.apply(id, wav); }
        catch (RuntimeException failure) { heard = CompletableFuture.failedFuture(failure); }
        heard.whenComplete((text, failure) -> server.execute(() -> {
            busy.remove(id);
            if (player.isDisconnected()) return;
            if (failure != null) { say(player, reason(failure), Formatting.RED); return; }
            String prayer = text == null ? "" : text.strip();
            if (payload.test()) { say(player, prayer.isEmpty() ? "Сервер записал, но слов не слышно." : "Сервер слышит: «" + prayer + "»", Formatting.GRAY); return; }
            if (prayer.isEmpty()) { say(player, "Бог не расслышал ни слова.", Formatting.GRAY); return; }
            if (DivineBlockMod.config().debug) player.sendMessage(Text.literal("Ты молишься: «" + prayer + "»").formatted(Formatting.GRAY, Formatting.ITALIC), false);
            Prayers.handle(player, payload.altar(), prayer.length() > PrayPayload.MAX_TEXT ? prayer.substring(0, PrayPayload.MAX_TEXT) : prayer);
        }));
    }

    private static String reason(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null || cause.getMessage().isBlank() ? "Сервер не смог распознать молитву." : cause.getMessage();
    }

    private static void say(ServerPlayerEntity player, String line, Formatting color) {
        player.sendMessage(Text.literal(line).formatted(color), true);
    }
}
