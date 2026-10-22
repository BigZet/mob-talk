package io.github.mobtalk.server;

import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.OpenRouterTranscriber;
import io.github.mobtalk.core.WhisperHelper;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Голос на сервере (трек B): ставит настоящие {@link TranscriptionService} и {@link SpeechService} вместо заглушек S1, кэш озвучки
 * по миру, досылку чанков в конце тика и то, что сервер объявляет игрокам про распознавание. Вызывается один раз из
 * {@code MobTalkMod.onInitialize}. */
public final class VoiceServices {
    private VoiceServices() { }
    private static final ExecutorService stt = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "mobtalk-server-stt"); thread.setDaemon(true); return thread;
    });
    private static volatile ServerSpeech speech;

    public static void install() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        WhisperHelper helper = new WhisperHelper();
        OpenRouterTranscription remote = new OpenRouterTranscription(ServerConfig::get, config -> engine(config, configDirectory),
                MobTalkMod::budget, VoiceServices::language, stt);
        ServerWhisperTranscription local = new ServerWhisperTranscription(ServerConfig::get, ServerWhisperTranscription.helper(helper), MobTalkMod::budget);
        MobTalkMod.setTranscription(new Transcriptions(ServerConfig::get, local, remote));
        ServerSpeech voices = new ServerSpeech(ServerConfig::get, configDirectory);
        speech = voices;
        MobTalkMod.setSpeech(voices);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> voices.open(server.getSavePath(WorldSavePath.ROOT)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { voices.close(); helper.stopOwned(); });
        ServerTickEvents.END_SERVER_TICK.register(server -> voices.pump());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VoiceIntake.forget(handler.getPlayer().getUuid()));
    }

    /** Пути распознавания для {@code ServerFeatures.sttPaths}. */
    public static List<String> sttPaths(MobTalkConfig config) { return Transcriptions.paths(config); }
    /** Сервер принимает аудио ({@code ServerFeatures.transcription}). */
    public static boolean transcription(MobTalkConfig config) { return config.sttServer || config.sttServerLocal; }
    /** Сервер озвучивает реплики ({@code ServerFeatures.speech}). */
    public static boolean speech(MobTalkConfig config) { return config.enabled && speech != null; }

    private static OpenRouterTranscription.Engine engine(MobTalkConfig config, Path configDirectory) {
        OpenRouterTranscriber transcriber;
        try { transcriber = OpenRouterTranscriber.open(config, configDirectory); }
        catch (IOException missing) {
            return (audio, format, language) -> { throw new IOException("На сервере нет ключа OpenRouter для распознавания."); };
        }
        return new OpenRouterTranscription.Engine() {
            @Override public OpenRouterTranscriber.Result transcribe(byte[] audio, String format, String language) throws IOException, InterruptedException {
                return transcriber.transcribe(audio, format, language);
            }
            @Override public void close() { transcriber.close(); }
        };
    }

    /** Язык из личных настроек игрока ({@code whisperLanguage}), иначе русский. */
    static String language(UUID player) {
        try {
            var value = PlayerSettingsStore.of(player).value("whisperLanguage");
            String language = value == null || !value.isJsonPrimitive() ? "" : value.getAsString().strip();
            return language.matches("[a-z]{2,3}") ? language : "ru";
        } catch (RuntimeException unreadable) { return "ru"; }
    }
}
