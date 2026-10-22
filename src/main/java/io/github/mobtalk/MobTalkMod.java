package io.github.mobtalk;

import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.PlayerSettings;
import io.github.mobtalk.core.ServerFeatures;
import io.github.mobtalk.core.Tuning;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.game.WeddingItems;
import io.github.mobtalk.net.GiftC2S;
import io.github.mobtalk.net.MobActionC2S;
import io.github.mobtalk.net.MobTalkNet;
import io.github.mobtalk.net.PlayerSettingsC2S;
import io.github.mobtalk.net.ScreenRequestC2S;
import io.github.mobtalk.net.ServerFeaturesS2C;
import io.github.mobtalk.net.TalkAudioC2S;
import io.github.mobtalk.net.TalkCancelC2S;
import io.github.mobtalk.net.TalkStartC2S;
import io.github.mobtalk.net.TalkStatusS2C;
import io.github.mobtalk.net.TalkTextC2S;
import io.github.mobtalk.server.AllowAllBudget;
import io.github.mobtalk.server.OpsOnlyPermissions;
import io.github.mobtalk.server.Permissions;
import io.github.mobtalk.server.PlayerBudget;
import io.github.mobtalk.server.PlayerSettingsStore;
import io.github.mobtalk.server.ServerConfig;
import io.github.mobtalk.server.SpeechService;
import io.github.mobtalk.server.StubSpeech;
import io.github.mobtalk.server.StubTranscription;
import io.github.mobtalk.server.TranscriptionService;
import io.github.mobtalk.server.VoiceIntake;
import io.github.mobtalk.server.VoiceServices;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Общий вход Mob Talk (сервер и одиночка; раздел 2 плана): предметы, миксины, пакеты, серверные настройки, мост к миру и
 * приёмники пакетов от клиента. Диалог на сервере появится в треке A ({@code DialogueService}): пока приёмники только отвечают
 * {@link TalkStatusS2C} «сервер ещё не умеет диалог». В одиночке клиент ({@code MobTalkClient}) продолжает вести диалог сам через
 * {@link MobGameBridge#active}, а мост создаёт этот класс. */
public final class MobTalkMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    public static final String NOT_YET = "Сервер ещё не умеет диалог: эта часть появится в треке A";
    /** {@code -Dmobtalk.selftest.server=true}: после старта сервер проверяет мост и пакеты, пишет итог в лог и останавливается. */
    public static final String SELFTEST_PROPERTY = "mobtalk.selftest.server";

    private static volatile TranscriptionService transcription = new StubTranscription();
    private static volatile SpeechService speech = new StubSpeech();
    private static volatile PlayerBudget budget = new AllowAllBudget();
    private static volatile Permissions permissions = new OpsOnlyPermissions();
    private static volatile CompletableFuture<MobGameBridge> bridgeReady = new CompletableFuture<>();
    private static volatile MinecraftServer current;
    private static volatile Path worldDirectory;
    private static final ExecutorService disk = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mobtalk-world-memory"); thread.setDaemon(true); return thread;
    });

    public static TranscriptionService transcription() { return transcription; }
    public static SpeechService speech() { return speech; }
    public static PlayerBudget budget() { return budget; }
    public static Permissions permissions() { return permissions; }
    /** Точки подключения треков B и C: подменить заглушки настоящими службами. */
    public static void setTranscription(TranscriptionService service) { transcription = service; }
    public static void setSpeech(SpeechService service) { speech = service; }
    public static void setBudget(PlayerBudget service) { budget = service; }
    public static void setPermissions(Permissions service) { permissions = service; }

    /** Мост этого сервера, когда память мира открыта; падает, если сервер остановился раньше. */
    public static CompletableFuture<MobGameBridge> bridgeReady(MinecraftServer server) {
        return server != null && server == current ? bridgeReady : CompletableFuture.failedFuture(new IllegalStateException("Сервер не запущен"));
    }
    public static boolean dedicated() { return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER; }
    public static String version() {
        return FabricLoader.getInstance().getModContainer("mobtalk").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    @Override public void onInitialize() {
        WeddingItems.register(); // Свои предметы — до загрузки любого мира, на обеих сторонах.
        MobGameBridge.initialize();
        MobTalkNet.registerPayloads();
        if (dedicated()) {
            try { ServerConfig.init(FabricLoader.getInstance().getConfigDir()); }
            catch (IOException failure) { LOGGER.error("Не удалось прочитать настройки сервера: {}", failure.toString()); }
            Tuning.apply(ServerConfig.get());
            ServerConfig.listen(Tuning::apply);
        }
        ServerConfig.listen(MobTalkMod::reconfigure);
        VoiceServices.install(); // Трек B: распознавание и озвучка на сервере вместо заглушек.
        ServerLifecycleEvents.SERVER_STARTED.register(MobTalkMod::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(MobTalkMod::stopping);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendFeatures(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { });
        registerReceivers();
        LOGGER.info("Mob Talk {}: {}", version(), dedicated() ? "выделенный сервер" : "клиент с встроенным сервером");
    }

    private static void started(MinecraftServer server) {
        current = server;
        CompletableFuture<MobGameBridge> ready = new CompletableFuture<>();
        bridgeReady = ready;
        Path directory = server.getSavePath(WorldSavePath.ROOT);
        worldDirectory = directory;
        PlayerSettingsStore.open(directory);
        MobTalkConfig config = ServerConfig.get();
        disk.execute(() -> {
            try {
                ConversationStore memory = ConversationStore.open(directory, config);
                server.execute(() -> {
                    if (current != server || bridgeReady != ready) { ready.completeExceptionally(new IllegalStateException("Мир сменился")); return; }
                    try {
                        MobGameBridge bridge = MobGameBridge.attach(server, memory, ServerConfig.get());
                        ready.complete(bridge);
                        LOGGER.info("Mob Talk: мост к миру поднят ({})", directory.getFileName());
                        if (Boolean.getBoolean(SELFTEST_PROPERTY)) ServerSelfTest.run(server, bridge);
                    } catch (Throwable failure) { ready.completeExceptionally(failure); LOGGER.error("Mob Talk: мост не поднялся", failure); }
                });
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
                LOGGER.error("Mob Talk: память мира не открылась ({})", failure.toString());
            }
        });
    }

    private static void stopping(MinecraftServer server) {
        if (current != server) return;
        current = null;
        Path directory = worldDirectory;
        worldDirectory = null;
        CompletableFuture<MobGameBridge> ready = bridgeReady;
        bridgeReady = new CompletableFuture<>();
        ready.completeExceptionally(new IllegalStateException("Сервер остановлен"));
        PlayerSettingsStore.close();
        CompletableFuture<Void> detached = MobGameBridge.detach(server);
        if (directory == null) return;
        disk.execute(() -> {
            try { detached.get(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
            try { ConversationStore.closeWorld(directory); }
            catch (Exception failure) { LOGGER.warn("Mob Talk: память мира не дописана ({})", failure.getClass().getSimpleName()); }
        });
    }

    /** Новые серверные настройки: мост получает копию, не пересоздаваясь. */
    private static void reconfigure(MobTalkConfig config) {
        MinecraftServer server = current;
        if (server == null) return;
        server.execute(() -> {
            MobGameBridge bridge = MobGameBridge.active(server);
            if (bridge != null) MobGameBridge.attach(server, bridge.memory, config);
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sendFeatures(player);
        });
    }

    /** Что сервер умеет для этого игрока: при входе и после смены настроек. */
    public static ServerFeatures features(ServerPlayerEntity player) {
        MobTalkConfig config = ServerConfig.get();
        UUID streamer = ServerConfig.streamer();
        ServerPlayerEntity star = streamer == null || player.getServer() == null ? null : player.getServer().getPlayerManager().getPlayer(streamer);
        return new ServerFeatures(version(), config.enabled, false,
                VoiceServices.transcription(config), VoiceServices.speech(config), VoiceServices.sttPaths(config), config.maxRecordingSeconds,
                permissions.level(player).name(), star == null ? "" : star.getGameProfile().getName());
    }
    private static void sendFeatures(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, ServerFeaturesS2C.ID)) return;
        ServerFeatures features = features(player);
        ServerPlayNetworking.send(player, new ServerFeaturesS2C(features.toJson()));
        LOGGER.info("Mob Talk: ServerFeaturesS2C отправлен {} (уровень {})", player.getGameProfile().getName(), features.level());
    }

    private static void notYet(ServerPlayerEntity player, String what) {
        LOGGER.info("Mob Talk: {} от {} — {}", what, player.getGameProfile().getName(), NOT_YET);
        if (ServerPlayNetworking.canSend(player, TalkStatusS2C.ID)) ServerPlayNetworking.send(player, new TalkStatusS2C(NOT_YET, false, NOT_YET));
    }

    /** Приёмники C2S; трек A заменяет тела на {@code DialogueService}. Контекст приёмника уже на потоке сервера. */
    private static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(TalkStartC2S.ID, (payload, context) -> notYet(context.player(), "TalkStartC2S " + payload.mobId()));
        ServerPlayNetworking.registerGlobalReceiver(TalkTextC2S.ID, (payload, context) -> notYet(context.player(), "TalkTextC2S " + payload.mobId()));
        // Трек B: аудио распознаётся здесь; трек A вместо notYet отдаёт текст диалогу (как TalkTextC2S с fromVoice=true).
        ServerPlayNetworking.registerGlobalReceiver(TalkAudioC2S.ID, (payload, context) ->
                VoiceIntake.receive(context.player(), payload, text -> notYet(context.player(), "TalkAudioC2S «" + text + "»")));
        ServerPlayNetworking.registerGlobalReceiver(TalkCancelC2S.ID, (payload, context) -> LOGGER.info("Mob Talk: TalkCancelC2S от {}: {}", context.player().getGameProfile().getName(), payload.reason()));
        ServerPlayNetworking.registerGlobalReceiver(GiftC2S.ID, (payload, context) -> notYet(context.player(), "GiftC2S " + payload.mobId()));
        ServerPlayNetworking.registerGlobalReceiver(MobActionC2S.ID, (payload, context) -> notYet(context.player(), "MobActionC2S " + payload.mobId()));
        ServerPlayNetworking.registerGlobalReceiver(ScreenRequestC2S.ID, (payload, context) -> notYet(context.player(), "ScreenRequestC2S " + payload.screen()));
        ServerPlayNetworking.registerGlobalReceiver(PlayerSettingsC2S.ID, (payload, context) -> {
            PlayerSettingsStore.put(context.player().getUuid(), PlayerSettings.fromJson(payload.json()));
            LOGGER.info("Mob Talk: личные настройки {} приняты", context.player().getGameProfile().getName());
        });
    }
}
