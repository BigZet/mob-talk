package io.github.mobtalk;

import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.JevDecider;
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
import io.github.mobtalk.server.AdminLink;
import io.github.mobtalk.server.AllowAllBudget;
import io.github.mobtalk.server.DialogueService;
import io.github.mobtalk.server.OpsOnlyPermissions;
import io.github.mobtalk.server.Permissions;
import io.github.mobtalk.server.PlayerBudget;
import io.github.mobtalk.server.PlayerSettingsStore;
import io.github.mobtalk.server.ServerConfig;
import io.github.mobtalk.server.ServerOutbox;
import io.github.mobtalk.server.SpeechService;
import io.github.mobtalk.server.StubSpeech;
import io.github.mobtalk.server.StubTranscription;
import io.github.mobtalk.server.TranscriptionService;
import io.github.mobtalk.server.VoiceIntake;
import io.github.mobtalk.server.VoiceServices;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import java.util.function.Consumer;

/** Общий вход Mob Talk (сервер и одиночка; раздел 2 плана): предметы, миксины, пакеты, серверные настройки, мост к миру,
 * {@link DialogueService} (трек A) и приёмники пакетов от клиента. В одиночке тот же путь через встроенный сервер: клиент
 * ({@code MobTalkClient}) только шлёт пакеты и показывает ответы. */
public final class MobTalkMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    public static final String NOT_YET = "Мир ещё загружается: подождите секунду";
    /** {@code -Dmobtalk.selftest.server=true}: после старта сервер проверяет мост и пакеты, пишет итог в лог и останавливается. */
    public static final String SELFTEST_PROPERTY = "mobtalk.selftest.server";

    private static volatile TranscriptionService transcription = new StubTranscription();
    private static volatile SpeechService speech = new StubSpeech();
    private static volatile PlayerBudget budget = new AllowAllBudget();
    private static volatile Permissions permissions = new OpsOnlyPermissions();
    private static volatile CompletableFuture<MobGameBridge> bridgeReady = new CompletableFuture<>();
    private static volatile DialogueService dialogue;
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
    /** Диалог этого сервера; null, пока мир не поднят. */
    public static DialogueService dialogue() { return dialogue; }

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
        ServerTickEvents.END_SERVER_TICK.register(server -> { DialogueService service = dialogue; if (service != null && server == current) service.tick(); });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendFeatures(handler.getPlayer());
            DialogueService service = dialogue;
            if (service != null) service.join(handler.getPlayer().getUuid(), handler.getPlayer().getGameProfile().getName());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DialogueService service = dialogue;
            if (service != null) service.leave(handler.getPlayer().getUuid());
            PlayerSettingsStore.flush();
        });
        registerReceivers();
        io.github.mobtalk.server.PlayerServices.initialize(); // Трек C: права, фильтр команд, расходы, /mobtalk admin, имена игроков.
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
                        dialogue = openDialogue(server, bridge, directory);
                        if (dedicated()) AdminLink.start(server, bridge, dialogue); // Трек W: веб-админка на выделенном сервере.
                        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                            dialogue.join(player.getUuid(), player.getGameProfile().getName());
                            sendFeatures(player);
                        }
                        ready.complete(bridge);
                        LOGGER.info("Mob Talk: мост к миру поднят ({}), диалог на сервере", directory.getFileName());
                        if (Boolean.getBoolean(SELFTEST_PROPERTY)) ServerSelfTest.run(server, bridge);
                    } catch (Throwable failure) { ready.completeExceptionally(failure); LOGGER.error("Mob Talk: мост не поднялся", failure); }
                });
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
                LOGGER.error("Mob Talk: память мира не открылась ({})", failure.toString());
            }
        });
    }

    /** Трек A: служба диалога на этом мосту. Ключи LLM и озвучки движок читает из окружения или DPAPI папки настроек сервера;
     * семья и свадьба — по серверной настройке {@code familyLife} (решение Дмитрия: житель женат на одном игроке, имена и пол игрока
     * берутся из его личных настроек, трек C); снимки экрана на выделенном сервере выключены. */
    private static DialogueService openDialogue(MinecraftServer server, MobGameBridge bridge, Path worldDirectory) {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        ConversationEngine.setDiagnostics(line -> LOGGER.info("Mob Talk: {}", line));
        return new DialogueService(bridge, worldDirectory, new ServerOutbox(server),
                (config, directory) -> new ConversationEngine(config, directory, configDirectory),
                config -> JevDecider.forConfig(config, configDirectory),
                MobTalkMod::budget, MobTalkMod::transcription,
                new DialogueService.Options(ServerConfig.get().familyLife, !dedicated()), 4);
    }

    private static void stopping(MinecraftServer server) {
        if (current != server) return;
        current = null;
        Path directory = worldDirectory;
        worldDirectory = null;
        CompletableFuture<MobGameBridge> ready = bridgeReady;
        bridgeReady = new CompletableFuture<>();
        ready.completeExceptionally(new IllegalStateException("Сервер остановлен"));
        AdminLink.stop();
        DialogueService service = dialogue;
        dialogue = null;
        if (service != null) service.close();
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
        return new ServerFeatures(version(), config.enabled, dialogue != null,
                VoiceServices.transcription(config), VoiceServices.speech(config), VoiceServices.sttPaths(config), config.maxRecordingSeconds,
                permissions.level(player).name(), star == null ? "" : star.getGameProfile().getName());
    }
    private static void sendFeatures(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, ServerFeaturesS2C.ID)) return;
        ServerFeatures features = features(player);
        ServerPlayNetworking.send(player, new ServerFeaturesS2C(features.toJson()));
        LOGGER.info("Mob Talk: ServerFeaturesS2C отправлен {} (уровень {}, диалог {})", player.getGameProfile().getName(), features.level(), features.dialogue());
    }

    private static void withDialogue(ServerPlayerEntity player, Consumer<DialogueService> work) {
        DialogueService service = dialogue;
        if (service != null) { work.accept(service); return; }
        if (ServerPlayNetworking.canSend(player, TalkStatusS2C.ID)) ServerPlayNetworking.send(player, new TalkStatusS2C(NOT_YET, false, NOT_YET));
    }

    /** Приёмники C2S: всё уходит в {@link DialogueService}; контекст приёмника уже на потоке сервера. */
    private static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(TalkStartC2S.ID, (payload, context) -> withDialogue(context.player(), s -> s.talkStart(context.player().getUuid(), payload.mobId())));
        ServerPlayNetworking.registerGlobalReceiver(TalkTextC2S.ID, (payload, context) -> withDialogue(context.player(), s -> s.talkText(context.player().getUuid(), payload.mobId(), payload.text(), payload.fromVoice(), payload.picture())));
        // Трек B: mobId < 0 — проверка микрофона из F7, текст уходит в ScreenDataS2C("stt-test"); иначе трек A: диалог распознаёт и отвечает.
        ServerPlayNetworking.registerGlobalReceiver(TalkAudioC2S.ID, (payload, context) -> {
            if (payload.mobId() < 0) { VoiceIntake.receive(context.player(), payload, text -> { }); return; }
            withDialogue(context.player(), s -> s.talkAudio(context.player().getUuid(), payload.mobId(), payload.format(), payload.seconds(), payload.audio()));
        });
        ServerPlayNetworking.registerGlobalReceiver(TalkCancelC2S.ID, (payload, context) -> withDialogue(context.player(), s -> s.cancel(context.player().getUuid(), payload.reason())));
        ServerPlayNetworking.registerGlobalReceiver(GiftC2S.ID, (payload, context) -> withDialogue(context.player(), s -> s.gift(context.player().getUuid(), payload.mobId())));
        ServerPlayNetworking.registerGlobalReceiver(MobActionC2S.ID, (payload, context) -> withDialogue(context.player(), s -> s.action(context.player().getUuid(), payload.mobId(), payload.intentJson())));
        ServerPlayNetworking.registerGlobalReceiver(ScreenRequestC2S.ID, (payload, context) -> withDialogue(context.player(), s -> s.screen(context.player().getUuid(), payload.screen(), payload.mobId())));
        ServerPlayNetworking.registerGlobalReceiver(PlayerSettingsC2S.ID, (payload, context) -> {
            PlayerSettingsStore.put(context.player().getUuid(), PlayerSettings.fromJson(payload.json()));
            LOGGER.info("Mob Talk: личные настройки {} приняты", context.player().getGameProfile().getName());
        });
    }
}
