package io.github.mobtalk.server;

import com.google.gson.JsonObject;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.net.ScreenDataS2C;
import io.github.mobtalk.net.TalkAudioC2S;
import io.github.mobtalk.net.TalkStatusS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Приём {@link TalkAudioC2S}: проверка, распознавание через {@link MobTalkMod#transcription()} не на потоке сервера, статус
 * игроку и готовый текст обратно на поток сервера. Диалог (трек A) зовёт {@link #receive} из своего приёмника и получает текст в
 * {@code onText}. {@code mobId < 0} — проверка микрофона из F7: текст уходит только в {@code ScreenDataS2C("stt-test")}. */
public final class VoiceIntake {
    private VoiceIntake() { }
    public static final String TEST_SCREEN = "stt-test";
    public static final Set<String> FORMATS = Set.of("wav16k", "mp3", "ogg");
    /** Одна запись на игрока в распознавании: вторая, пока первая не вернулась, получает отказ. */
    private static final Map<UUID, Long> busy = new ConcurrentHashMap<>();

    /** Поток сервера. {@code onText} вызывается на потоке сервера, только с непустым текстом и пока игрок на сервере. */
    public static void receive(ServerPlayerEntity player, TalkAudioC2S audio, Consumer<String> onText) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        MobTalkConfig config = ServerConfig.get();
        String refusal = refusal(config, audio);
        if (!refusal.isEmpty()) { status(player, "", false, refusal); return; }
        UUID id = player.getUuid();
        long started = System.nanoTime();
        if (busy.putIfAbsent(id, started) != null) { status(player, "", false, "Предыдущая запись ещё распознаётся"); return; }
        boolean test = audio.mobId() < 0;
        status(player, "Сервер распознаёт речь…", true, "");
        int seconds = Math.max(1, audio.seconds());
        String name = player.getGameProfile().getName();
        MobTalkMod.transcription().transcribe(id, audio.audio(), audio.format(), seconds, "").whenComplete((text, failure) -> server.execute(() -> {
            busy.remove(id, started);
            long millis = (System.nanoTime() - started) / 1_000_000;
            String error = failure == null ? "" : Transcriptions.reason(failure);
            String heard = text == null ? "" : text.strip();
            MobTalkMod.LOGGER.info("Mob Talk: распознавание {} с от {} за {} мс: {}", seconds, name, millis,
                    failure != null ? error : heard.isEmpty() ? "тишина" : heard.length() + " симв.");
            if (player.isDisconnected()) return;
            if (test) { testResult(player, heard, error, millis); return; }
            if (!error.isEmpty()) { status(player, "", false, error); return; }
            if (heard.isEmpty()) { status(player, "Речь не распознана: удерживайте голосовую клавишу и говорите", false, ""); return; }
            onText.accept(heard);
        }));
    }

    /** Пустая строка — запись годится. */
    static String refusal(MobTalkConfig config, TalkAudioC2S audio) {
        if (!config.sttServer && !config.sttServerLocal) return Transcriptions.OFF;
        if (!FORMATS.contains(audio.format())) return "Неизвестный формат записи: " + audio.format();
        if (audio.audio().length < 64) return "Запись пустая";
        if (audio.seconds() > Math.max(1, config.maxRecordingSeconds) + 1) return "Запись длиннее " + config.maxRecordingSeconds + " с";
        return "";
    }

    /** Игрок вышел: его запись больше никого не ждёт. */
    public static void forget(UUID player) { busy.remove(player); }

    private static void testResult(ServerPlayerEntity player, String text, String error, long millis) {
        status(player, "", false, "");
        if (!ServerPlayNetworking.canSend(player, ScreenDataS2C.ID)) return;
        JsonObject json = new JsonObject();
        json.addProperty("text", text);
        json.addProperty("error", error);
        json.addProperty("millis", millis);
        json.addProperty("model", ServerConfig.get().sttServerLocal ? "server-local" : ServerConfig.get().sttModel);
        ServerPlayNetworking.send(player, new ScreenDataS2C(TEST_SCREEN, json.toString()));
    }

    private static void status(ServerPlayerEntity player, String status, boolean busy, String error) {
        if (ServerPlayNetworking.canSend(player, TalkStatusS2C.ID)) ServerPlayNetworking.send(player, new TalkStatusS2C(status, busy, error));
    }
}
