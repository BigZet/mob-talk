package io.github.mobtalk.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.Decider;
import io.github.mobtalk.core.JevDecider;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.RuleDecider;
import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.core.WhisperClient;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.net.GiftC2S;
import io.github.mobtalk.net.MobActionC2S;
import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.MobStateS2C;
import io.github.mobtalk.net.MobTalkNet;
import io.github.mobtalk.net.ScreenRequestC2S;
import io.github.mobtalk.net.SpeechClipS2C;
import io.github.mobtalk.net.TalkAudioC2S;
import io.github.mobtalk.net.TalkCancelC2S;
import io.github.mobtalk.net.TalkStartC2S;
import io.github.mobtalk.net.TalkTextC2S;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Клиент Mob Talk после переноса диалога на сервер (трек A): микрофон по удержанию клавиши, выбор цели прицелом, локальный Whisper
 * (или аудио серверу), отправка пакетов, приём реплик, звука, состояния и данных экранов; HUD, подписи, окна. Сам диалог, очевидцы,
 * подарки, торг и инициатива идут на сервере ({@code DialogueService}) — в одиночке на встроенном, тем же путём. Из прежнего
 * оркестратора здесь остались только ввод и показ; ничего не ходит к {@code client.getServer()}, кроме экранов второй версии
 * (семья, свадьба, кровать), которые пока живут на встроенном сервере одиночки. */
public final class MobTalkClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    private static final Gson GSON = new Gson();
    final MinecraftClient client = MinecraftClient.getInstance();
    final Path configDirectory = FabricLoader.getInstance().getConfigDir();
    private final ExecutorService pipeline = worker("mobtalk-conversation");
    final ExecutorService maintenance = worker("mobtalk-settings");
    private final MicrophoneRecorder microphone = new MicrophoneRecorder();
    private final WhisperProcess helper = new WhisperProcess();
    private final AtomicBoolean pipelineBusy = new AtomicBoolean();
    private volatile long generation;
    private volatile Thread pipelineThread;
    private volatile WhisperClient whisper;
    private volatile long whisperPreloads;
    private final ExecutorService whisperLoader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Mob Talk Whisper preload"); thread.setDaemon(true); return thread;
    });
    /** Движок второй версии (свадьба у колокола в одиночке): создаётся лениво на вызывающем потоке. */
    private volatile ConversationEngine engine;
    private Path engineWorld; private MobTalkConfig engineConfig;
    private volatile Decider decider = new RuleDecider();
    private MobTalkConfig config = new MobTalkConfig();
    ClientWorld world;
    UUID selected;
    KeyBinding talkKey, settingsKey, tradeKey, giftKey, familyKey;
    private final MobLabels labels = new MobLabels(this);
    private final ChildView children = new ChildView(this);
    final MobViews views = new MobViews();
    private final ClipAssembler clips = new ClipAssembler();
    /** Реплики, чей звук ещё в пути: по номеру клипа — моб и фон ли это (посторонняя реплика). */
    private final Map<Integer, PendingVoice> pendingVoices = new HashMap<>();
    private record PendingVoice(UUID mob, boolean background, long since) { }
    private boolean openSettingsRequested, openInfoRequested, openFamilyRequested;
    private MicrophoneRecorder.Capture recording;
    private boolean configReady;
    boolean reloading;
    private boolean stopped;
    boolean keyHeld;
    private boolean requestActive;
    /** Сервер подтвердил запрос статусом «занят»: только после этого статус «свободен» снимает запрос (устаревший «Готов» от выбора цели — нет). */
    private boolean requestAcked;
    private final MerchantSession merchant = new MerchantSession(this);
    private final BedTalk bed = new BedTalk(this);
    final InitiativeVoice initiatives = new InitiativeVoice(this);
    final VillageTalkVoice village = new VillageTalkVoice(this);
    final WeddingVoice wedding = new WeddingVoice(this);
    /** Ответы экранов и команд, которых ждут: по имени экрана. */
    private final Map<String, CompletableFuture<String>> screenRequests = new HashMap<>();
    private boolean armRecording;
    private long ticks;
    String status = "Загрузка настроек…";
    private MobSpeechSound speech;
    final io.github.mobtalk.core.VoiceFloor<MobSpeechSound> voices = new io.github.mobtalk.core.VoiceFloor<>();
    private final io.github.mobtalk.core.Grace<UUID> reach = new io.github.mobtalk.core.Grace<>();
    private boolean deathHandled;
    private UUID speakingMob;
    int speechStarts, speechCutoffs, repliesShown;

    @Override public void onInitializeClient() {
        ClientNet.register(this);
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.mobtalk"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.mobtalk"));
        tradeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.trade", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.mobtalk"));
        giftKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.gift", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.mobtalk"));
        familyKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.family", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), "category.mobtalk"));
        MomentClient.register(configDirectory); // Метки для монтажа: клавиша, OBS (идея З4).
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(labels::render);
        ChildView.register();
        io.github.mobtalk.client.game.CityBuildClient.register();
        io.github.mobtalk.client.game.LiftClient.register();
        io.github.mobtalk.client.human.VillagerLooks.register();
        FurnitureClient.register();
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES).registerReloadListener(
                new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    @Override public net.minecraft.util.Identifier getFabricId() { return net.minecraft.util.Identifier.of("mobtalk", "human_textures"); }
                    @Override public void reload(net.minecraft.resource.ResourceManager manager) { io.github.mobtalk.client.human.HumanTextures.reload(); }
                });
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(children::renderFade);
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof GameMenuScreen) {
                boolean pack = ModHubScreen.packInstalled();
                if (pack) Screens.getButtons(screen).removeIf(widget -> "Божественный блок".equals(widget.getMessage().getString()));
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal(pack ? "Моды сборки" : "Mob Talk"),
                                button -> { if (pack) client.setScreen(new ModHubScreen(screen, () -> openSettings(client.currentScreen))); else openSettings(screen); })
                        .dimensions(width - 128, height - 26, 120, 20).build());
            }
            if (screen instanceof MerchantScreen trading) merchant.initialize(trading);
            if (screen instanceof net.minecraft.client.gui.screen.SleepingChatScreen sleeping) bed.initialize(sleeping);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> leaveWorld());
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> shutdown());
        MobTalkCommands.register(this);
        SelfTest.install(this);
        SelfTestServer.install(this);
        io.github.mobtalk.client.game.DatasetHarvester.install(client);
        reload();
    }

    // ---- Состояние для окон, команд и самотеста. ----

    boolean enabled() { return configReady && config.enabled; }
    /** Запись, запрос, звук или ожидаемый звук в полёте. */
    boolean busyTalking() { return recording != null || requestActive || voiceOn() || !pendingVoices.isEmpty(); }
    boolean speaking() { return voiceOn(); }
    private boolean voiceOn() { return speech != null || !voices.free(); }
    boolean recordingNow() { return recording != null; }
    MobTalkConfig config() { return config; }
    MicrophoneRecorder.Capture microphoneProbe(int seconds) { return microphone.start(config.microphoneName, seconds); }
    /** Звук посторонних реплик ещё в пути: эфир не свободен (очевидцев решает сервер, клиент видит только их звук). */
    boolean witnessPending() { return !pendingVoices.isEmpty(); }
    String busyReason() { return "recording=" + (recording != null) + " request=" + requestActive + " speech=" + (speech != null) + " voices=" + voices.count() + " pending=" + pendingVoices.size(); }
    ConversationEngine engine() { return engine; }
    Decider decider() { return decider; }
    long generation() { return generation; }
    void background(Runnable work) { maintenance.execute(work); }
    void requestSettings() { openSettingsRequested = true; }
    void requestInfo() { openInfoRequested = true; }
    void requestFamily() { openFamilyRequested = true; }
    boolean connected() { return client.world != null && client.getNetworkHandler() != null; }
    private boolean send(CustomPayload payload) {
        if (!connected() || !ClientPlayNetworking.canSend(payload.getId())) { notice("На этом сервере нет Mob Talk"); return false; }
        ClientPlayNetworking.send(payload);
        return true;
    }

    /** Снимок экрана к вопросу: пиксели с потока рендера, JPEG позже вне его. */
    private record Shot(long generation, int width, int height, int[] rgb) { }
    private volatile Shot shot;
    private void captureShot(long requestGeneration) {
        shot = null;
        if (io.github.mobtalk.core.Vision.OFF.equals(io.github.mobtalk.core.Vision.normalize(config.vision)) || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return;
        try (net.minecraft.client.texture.NativeImage image = net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            int width = image.getWidth(), height = image.getHeight();
            int step = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) io.github.mobtalk.core.Vision.MAX_SIDE));
            int w = Math.max(1, width / step), h = Math.max(1, height / step);
            int[] rgb = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int abgr = image.getColor(x * step, y * step);
                rgb[y * w + x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
            }
            shot = new Shot(requestGeneration, w, h, rgb);
        } catch (RuntimeException failure) { LOGGER.debug("Mob Talk screenshot skipped ({})", failure.getClass().getSimpleName()); }
    }
    /** JPEG для этого запроса или пусто: нужен ли он, решит сервер по снимку моба и вопросу. */
    private byte[] pictureFor(Request request) {
        Shot taken = shot;
        if (taken == null || taken.generation() != request.generation) return new byte[0];
        try {
            byte[] jpeg = io.github.mobtalk.core.Vision.jpeg(taken.rgb(), taken.width(), taken.height());
            return jpeg == null || jpeg.length > MobTalkNet.MAX_PICTURE ? new byte[0] : jpeg;
        } catch (java.io.IOException failure) { return new byte[0]; }
    }

    /** /mobtalk say и самотест: строка идёт тем же путём, что речь. */
    boolean say(String text) {
        if (!readyForRequest()) { notice(status); return false; }
        stopSpeech();
        long requestGeneration = ++generation;
        captureShot(requestGeneration);
        submit(request(requestGeneration), null, text);
        return true;
    }
    /** Самотест: записанный вопрос как WAV проходит через Whisper, как настоящая запись. */
    boolean sayWav(byte[] wav) {
        if (!readyForRequest()) { notice(status); return false; }
        stopSpeech();
        long requestGeneration = ++generation;
        captureShot(requestGeneration);
        submit(request(requestGeneration), wav, null);
        return true;
    }
    String statusLine() {
        MobStateS2C state = views.state(selected);
        return status + (selected == null ? " · цель не выбрана" : " · UUID " + selected + (state == null || state.favor().isEmpty() ? "" : " · " + state.favor()));
    }
    String readyStatus() { return "Готов: удерживайте " + talkKey.getBoundKeyLocalizedText().getString(); }
    static final String AIM_HINT = "Наведите прицел на моба и удерживайте V";

    // ---- Цель. ----

    MobEntity aimedMob() { return aimedMob(config.maxDistance); }
    MobEntity aimedMob(double range) {
        if (client.player == null || world == null || client.world != world) return null;
        Vec3d start = client.player.getCameraPosVec(1f), look = client.player.getRotationVec(1f), end = start.add(look.multiply(range));
        HitResult block = client.player.raycast(range, 1f, false);
        double limit = block.getType() == HitResult.Type.MISS ? range * range : block.getPos().squaredDistanceTo(start);
        EntityHitResult hit = ProjectileUtil.raycast(client.player, start, end, client.player.getBoundingBox().stretch(look.multiply(range)).expand(1),
                entity -> !entity.isSpectator() && entity.isAlive() && entity.canHit(), limit);
        Entity entity = hit == null ? null : hit.getEntity();
        if (entity instanceof net.minecraft.entity.boss.dragon.EnderDragonPart part) entity = part.owner;
        return entity instanceof MobEntity mob && mob.isAlive() && !mob.isRemoved() ? mob : null;
    }
    private void aimBeforeTalking() {
        if (!enabled() || client.player == null || !client.player.isAlive() || client.player.isSpectator() || world == null) return;
        MobEntity aimed = bed.open() ? bed.partner() : aimedMob();
        if (aimed != null && !aimed.getUuid().equals(selected)) select(aimed);
        else if (aimed == null && target() == null) { status = AIM_HINT; showHud(); }
    }
    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; });
    }
    /** Собеседник: сервер узнаёт о выборе ({@link TalkStartC2S}) и ведёт его сессию. */
    void select(MobEntity mob) {
        if (!mob.getUuid().equals(selected)) {
            cancelWork(readyStatus());
            selected = mob.getUuid();
            if (connected() && ClientPlayNetworking.canSend(TalkStartC2S.ID)) ClientPlayNetworking.send(new TalkStartC2S(mob.getId()));
        }
        showHud();
    }
    void clearSelection(String reason) { cancelWork(reason); selected = null; showHud(); }
    private MobEntity target() { return MobSpeechSound.find(world, selected); }
    boolean withinRange(MobEntity mob) {
        return mob != null && client.player != null && client.player.squaredDistanceTo(mob) <= config.maxDistance * config.maxDistance;
    }
    long graceMillis() { return Math.max(0, config.voiceGraceSeconds) * 1000L; }
    boolean inReach(MobEntity mob) {
        return mob != null && client.player != null && reach.hold(mob.getUuid(), withinRange(mob), System.currentTimeMillis(), graceMillis());
    }

    // ---- Тик. ----

    private void tick() {
        ticks++;
        OverlayClient.tick();
        updateWorld();
        merchant.tick(ticks);
        while (settingsKey.wasPressed()) openSettingsRequested = true;
        if (openSettingsRequested && !reloading && !stopped) { openSettingsRequested = false; openSettings(client.currentScreen); }
        if (openInfoRequested && !reloading && !stopped) { openInfoRequested = false; openInfo(client.currentScreen); }
        if (openFamilyRequested && !reloading && !stopped) { openFamilyRequested = false; openFamily(client.currentScreen); }
        while (tradeKey.wasPressed()) openTrade();
        while (giftKey.wasPressed()) giftFromHand();
        while (familyKey.wasPressed()) openFamily(client.currentScreen);
        boolean held = merchant.windowOpen() ? merchant.keyHeld() : bed.open() ? bed.keyHeld() : talkKey.isPressed();
        MobEntity mob = target();
        if (selected != null && mob == null) clearSelection("Моб погиб или выгрузился: наведите прицел на другого");
        boolean dead = client.player != null && !client.player.isAlive();
        if (!dead) deathHandled = false;
        else if (!deathHandled) {
            deathHandled = true;
            if (recording != null || requestActive || witnessPending() || village.active()) cancelWork("Игрок погиб: разговор отменён", true);
        }
        if (mob != null && !inReach(mob) && (recording != null || requestActive)) cancelWork("Подойдите к мобу ближе: разговор отменён");
        if (merchant.work() != null && !client.isWindowFocused()) { cancelWork("Окно неактивно: торговый разговор отменён"); merchant.clearKeyHeld(); held = false; }
        if (recording != null && (!recordingScreen() || !client.isWindowFocused() || world == null || client.player == null || !client.player.isAlive())) cancelWork("Запись отменена");
        if (held && !keyHeld && !merchant.windowOpen()) aimBeforeTalking();
        if (held && !keyHeld && canRecord()) {
            if (recording == null && pipelineBusy.get()) armRecording = true;
            else beginRecording();
        }
        if (armRecording && held && recording == null && !pipelineBusy.get() && canRecord()) { armRecording = false; beginRecording(); }
        if (!held) armRecording = false;
        village.tick(ticks);
        wedding.tick(ticks);
        if (!held && keyHeld && recording != null) { status = "Завершение записи…"; recording.stop(); }
        keyHeld = held;
        voices.prune(client.getSoundManager()::isPlaying, MobSpeechSound::release);
        if (speech != null && !client.getSoundManager().isPlaying(speech)) {
            speech = null; speakingMob = null;
            if (!requestActive && recording == null) status = readyStatus();
        }
        if (ticks % 20 == 0) { pendingVoices.values().removeIf(voice -> System.currentTimeMillis() - voice.since > 60_000); clips.prune(); views.prune(); }
        if (selected != null && ticks % 20 == 0) showHud();
        if (ticks % 10 == 7) { children.tick(); labels.tick(); updateLooks(); }
    }

    /** Трек M: переключатель людей-жителей и копия пола, родителей и стадий для их облика (из памяти встроенного сервера одиночки). */
    private void updateLooks() {
        io.github.mobtalk.client.human.HumanLooks.enabled = config == null || config.humanVillagers;
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        if (game == null) io.github.mobtalk.client.human.HumanLooks.clear();
        else io.github.mobtalk.client.human.HumanLooks.update(game.memory.lineages());
    }
    private boolean recordingScreen() { return merchant.allowedRecordingScreen() || bed.open(); }
    private boolean canRecord() {
        return enabled() && !reloading && recordingScreen() && client.isWindowFocused() && client.player != null && client.player.isAlive() && world != null;
    }
    void updateWorld() {
        ClientWorld current = client.world;
        if (world == current) return;
        leaveWorld();
        world = current;
        keyHeld = talkKey != null && talkKey.isPressed();
        if (world != null) status = configReady ? AIM_HINT : "Загрузка настроек…";
    }
    private void leaveWorld() {
        merchant.end("Мир сменился: торговый разговор отменён");
        cancelWork(AIM_HINT, true, false);
        wedding.reset();
        selected = null;
        reach.clear();
        views.clear(); clips.clear(); pendingVoices.clear();
        for (CompletableFuture<String> waiting : screenRequests.values()) waiting.completeExceptionally(new IllegalStateException("Мир сменился"));
        screenRequests.clear();
        world = null;
    }

    // ---- Отмена. ----

    void cancelWork(String reason) { cancelWork(reason, false, true); }
    private void cancelWork(String reason, boolean keepVoice) { cancelWork(reason, keepVoice, true); }
    /** Новое поколение: запись и распознавание прерываются, сервер получает {@link TalkCancelC2S}. {@code keepVoice}: звучащее доигрывает. */
    private void cancelWork(String reason, boolean keepVoice, boolean tellServer) {
        generation++;
        armRecording = false;
        initiatives.cancel();
        village.cancel();
        requestActive = false;
        merchant.setWork(null);
        if (recording != null) recording.cancel();
        recording = null;
        if (!keepVoice) { stopSpeech(); pendingVoices.clear(); }
        Thread running = pipelineThread;
        if (running != null) running.interrupt();
        WhisperClient oldWhisper = whisper;
        whisper = null;
        if (!stopped && oldWhisper != null) maintenance.execute(oldWhisper::close);
        if (tellServer && connected() && ClientPlayNetworking.canSend(TalkCancelC2S.ID)) ClientPlayNetworking.send(new TalkCancelC2S(reason));
        status = reason;
    }

    // ---- Запись и отправка. ----

    private void beginRecording() {
        if (!readyForRequest()) return;
        stopSpeech();
        long requestGeneration = ++generation;
        Request seed = request(requestGeneration);
        captureShot(requestGeneration);
        try {
            int seconds = wedding.takesVow() ? wedding.vowSeconds() : config.maxRecordingSeconds;
            MicrophoneRecorder.Capture capture = microphone.start(config.microphoneName, seconds);
            recording = capture;
            merchant.setWork(seed.merchant);
            status = "Запись… отпустите " + talkKey.getBoundKeyLocalizedText().getString() + " (до " + seconds + " сек.)";
            showHud();
            capture.wav.whenComplete((wav, error) -> client.execute(() -> finishRecording(seed, capture, wav, error)));
        } catch (RuntimeException unavailable) {
            status = "Микрофон занят или недоступен: /mobtalk mic list";
            notice(status);
        }
    }
    private void finishRecording(Request seed, MicrophoneRecorder.Capture capture, byte[] wav, Throwable error) {
        if (recording != capture || !isCurrent(seed)) return;
        recording = null;
        if (!recordingScreen() || !client.isWindowFocused()) { status = "Запись отменена"; showHud(); return; }
        if (error != null) { reportFailure(seed, error); return; }
        if (wav == null) { status = "Запись слишком короткая: " + readyStatus() + " и произнесите вопрос"; showHud(); return; }
        submit(seed, wav, null);
    }
    private boolean readyForRequest() {
        updateWorld();
        if (!configReady || reloading) status = "Настройки ещё загружаются";
        else if (!config.enabled) status = "Mob Talk выключен в config/mobtalk.json";
        else if (world == null || client.player == null || !client.player.isAlive()) status = "Войдите в мир";
        else if (!connected() || !ClientPlayNetworking.canSend(TalkTextC2S.ID)) status = "На этом сервере нет Mob Talk";
        else if (!ClientNet.features().dialogue() && ClientNet.features().version().isEmpty() && client.getServer() == null) status = "Сервер ещё не ответил";
        else if (target() == null) status = AIM_HINT;
        else if (!withinRange(target())) status = "Подойдите к мобу ближе (до " + Math.round(config.maxDistance) + " блоков)";
        else if (recording != null || pipelineBusy.get()) status = "Предыдущий разговор ещё завершается";
        else return true;
        showHud();
        return false;
    }
    private Request request(long requestGeneration) {
        MobEntity mob = target();
        return new Request(requestGeneration, selected, mob == null ? 0 : mob.getId(), mob == null ? "" : mob.getName().getString(), config, merchant.current());
    }
    /** Распознаёт (если было аудио) и отправляет серверу; клятва на свадьбе уходит в службу, а не в разговор. */
    private void submit(Request request, byte[] wav, String text) {
        if (!pipelineBusy.compareAndSet(false, true)) { status = "Предыдущий разговор ещё завершается"; showHud(); return; }
        requestActive = true;
        merchant.setWork(request.merchant);
        status = wav == null ? "Отправка…" : "Whisper распознаёт…";
        showHud();
        pipeline.execute(() -> {
            pipelineThread = Thread.currentThread();
            try {
                checkCurrent(request);
                String question = text;
                boolean fromVoice = wav != null;
                if (wav != null) {
                    if (io.github.mobtalk.core.WeddingFaults.whisper && wedding.takesVow()) throw new java.io.IOException("самотест: Whisper недоступен");
                    // Трек B: личный sttPath ∩ пути сервера ∩ установлен ли Whisper (SttRoute); локальный путь при сбое падает на сервер.
                    io.github.mobtalk.core.SttRoute.Choice route = VoiceInput.route(request.config);
                    boolean serverAllowed = ClientNet.features().transcription() && ClientNet.features().sttPaths().contains(io.github.mobtalk.core.SttRoute.SERVER);
                    boolean local = io.github.mobtalk.core.SttRoute.LOCAL.equals(route.path())
                            || !serverAllowed && !io.github.mobtalk.core.SttRoute.TEXT.equals(route.path());
                    boolean server = serverAllowed && !io.github.mobtalk.core.SttRoute.TEXT.equals(route.path());
                    if (!route.hint().isEmpty()) client.execute(() -> notice(route.hint()));
                    if (!local && !server) throw new java.io.IOException(route.hint().isEmpty() ? "Голос недоступен: пишите текстом (T)" : route.hint());
                    if (local) {
                        try {
                            updateStatus(request, "Whisper запускается и распознаёт…");
                            helper.ensureAvailable(request.config, () -> generation != request.generation, message -> updateStatus(request, message));
                            checkCurrent(request);
                            try (WhisperClient localWhisper = new WhisperClient(request.config)) {
                                whisper = localWhisper;
                                checkCurrent(request);
                                question = localWhisper.transcribe(wav, whisperContext(request));
                            } finally { whisper = null; }
                        } catch (java.io.IOException unavailable) {
                            if (!server) throw unavailable;
                            question = null;
                        }
                    }
                    checkCurrent(request);
                    if ((question == null || question.isBlank()) && server) {
                        updateStatus(request, "Сервер распознаёт…");
                        byte[] audio = wav.length > MobTalkNet.MAX_AUDIO ? java.util.Arrays.copyOf(wav, MobTalkNet.MAX_AUDIO) : wav;
                        client.execute(() -> { if (isCurrent(request)) { requestAcked = false; send(new TalkAudioC2S(request.entityId, "wav16k", Math.max(1, wav.length / 32_000), audio)); } });
                        return;
                    }
                    if (question == null || question.isBlank()) {
                        if (wedding.takesVow()) client.execute(wedding::vowUnheard);
                        updateStatus(request, "Речь не распознана: удерживайте голосовую клавишу и произнесите вопрос");
                        client.execute(() -> { if (isCurrent(request)) requestActive = false; });
                        return;
                    }
                }
                if (wedding.takesVow() && !wedding.serverVowOpen() && question != null && !question.isBlank()) {
                    String vow = question;
                    client.execute(() -> { wedding.vowHeard(vow); requestActive = false; });
                    updateStatus(request, "Клятва произнесена");
                    return;
                }
                checkCurrent(request);
                String asked = question;
                byte[] picture = pictureFor(request);
                boolean voice = fromVoice;
                client.execute(() -> {
                    if (!isCurrent(request)) return;
                    status = "Моб думает…"; showHud();
                    requestAcked = false;
                    if (!send(new TalkTextC2S(request.entityId, asked, voice, picture))) requestActive = false;
                });
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                client.execute(() -> reportFailure(request, failure));
            } finally {
                pipelineThread = null;
                pipelineBusy.set(false);
                Thread.interrupted();
            }
        });
    }
    private String whisperContext(Request request) {
        List<String> words = new ArrayList<>();
        if (!request.mobName.isBlank()) words.add(request.mobName);
        String given = views.name(request.target);
        if (!given.isBlank()) words.add(given);
        for (String name : views.names()) if (!words.contains(name)) words.add(name);
        if (config.whisperHotwords != null && !config.whisperHotwords.isBlank()) words.add(config.whisperHotwords);
        String context = String.join(", ", words);
        return context.length() > 500 ? context.substring(0, 500) : context;
    }
    private void updateStatus(Request request, String message) { client.execute(() -> { if (!isCurrent(request)) return; status = message; showHud(); }); }
    private void reportFailure(Request request, Throwable failure) {
        boolean vow = wedding.takesVow();
        if (vow) wedding.vowUnheard();
        if (!isCurrent(request)) return;
        requestActive = false;
        LOGGER.warn("Mob Talk request failed on the client ({})", failure.getClass().getSimpleName());
        String message = io.github.mobtalk.core.Failures.plain(failure);
        status = message.isBlank() ? "Разговор не удался: проверьте микрофон и Whisper" : message;
        if (status.length() > 240) status = status.substring(0, 240);
        notice(status);
    }
    private void checkCurrent(Request request) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || generation != request.generation) throw new InterruptedException("Cancelled");
    }
    private boolean isCurrent(Request request) {
        return !stopped && generation == request.generation && request.target != null && request.target.equals(selected) && world != null && client.world == world
                && client.player != null && client.player.isAlive() && inReach(target())
                && (request.merchant == null || request.merchant.equals(merchant.context()) && merchant.allowedRecordingScreen());
    }

    // ---- Приём от сервера (поток клиента; см. ClientNet). ----

    void onStatus(String text, boolean busy, String error) {
        if (!error.isEmpty()) { requestActive = false; status = error; notice(error); return; }
        status = text;
        if (busy) requestAcked = true;
        else if (requestAcked || !requestActive) requestActive = false;
        showHud();
    }
    void onSpeech(MobSpeechS2C packet) {
        OverlayClient.speech(packet);
        if (MobSpeechS2C.INITIATIVE.equals(packet.kind())) initiatives.record(packet.mob(), packet.text());
        if (packet.toYou() && (MobSpeechS2C.ANSWER.equals(packet.kind()) || MobSpeechS2C.FALLBACK.equals(packet.kind()))) requestActive = false;
        if (!packet.heard().isEmpty()) chat(packet.toYou() ? "Вы: " + packet.heard() : "§7" + packet.asker() + ": " + packet.heard());
        if (packet.silent()) { if (packet.toYou() && !requestActive && recording == null) status = readyStatus(); showHud(); return; }
        chat(packet.speaker() + ": " + packet.text());
        repliesShown++;
        boolean background = !packet.toYou() || !MobSpeechS2C.ANSWER.equals(packet.kind());
        if (packet.clipId() != 0) pendingVoices.put(packet.clipId(), new PendingVoice(packet.mob(), background, System.currentTimeMillis()));
        else if (packet.toYou() && MobSpeechS2C.ANSWER.equals(packet.kind())) stopSpeech();
        if (packet.toYou()) status = packet.clipId() != 0 ? (background ? packet.speaker() + " говорит…" : "Говорит…") : MobSpeechS2C.ANSWER.equals(packet.kind()) ? "Ответ в чате; озвучка недоступна" : status;
        showHud();
    }
    void onClip(SpeechClipS2C chunk) {
        byte[] pcm = clips.offer(chunk);
        if (pcm == null) return;
        if (pcm.length > 0) OverlayClient.voiceStarted(chunk.clipId(), pcm.length / 48);
        PendingVoice voice = pendingVoices.remove(chunk.clipId());
        LOGGER.info("Mob Talk: clip {} {} {} bytes ({} ms, {} crossings/s){}", chunk.clipId(), chunk.format(), pcm.length, pcm.length / 48,
                io.github.mobtalk.core.SpeechStream.crossingsPerSecond(pcm), voice == null ? ", nobody waits for it" : "");
        if (voice == null || pcm.length == 0 || world == null) return;
        startVoice(voice.mob, SpeechStream.of(pcm), voice.background);
        if (!voice.background) status = "Говорит…";
    }
    void onState(MobStateS2C packet) { views.accept(packet); }
    void onLabel(MobLabelS2C packet) {
        boolean fresh = !packet.name().isEmpty() && (views.label(packet.mob()) == null || !packet.name().equals(views.name(packet.mob())));
        views.accept(packet);
        if (fresh) LOGGER.info("Mob Talk: подпись «{}» для {} (entity {}){}", packet.name(), packet.mob(), packet.mobId(),
                world != null && world.getEntityById(packet.mobId()) == null ? ", сущности нет на клиенте" : "");
    }
    void onScreen(String screen, String json) {
        if ("deal".equals(screen)) { merchant.resolved(json); return; }
        if ("wedding_vow".equals(screen)) { if (!wedding.serverVow(json)) requestActive = false; return; }
        CompletableFuture<String> waiting = screenRequests.remove(screen);
        if (waiting == null) return;
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String error = object.has("error") ? object.get("error").getAsString() : "";
            if (!error.isEmpty()) waiting.completeExceptionally(new IllegalStateException(error));
            else waiting.complete(object.has("message") ? object.get("message").getAsString() : json);
        } catch (RuntimeException notObject) { waiting.complete(json); }
    }
    private CompletableFuture<String> ask(String screen, int mobId) {
        CompletableFuture<String> old = screenRequests.remove(screen);
        if (old != null) old.completeExceptionally(new java.util.concurrent.CancellationException("Запрошено заново"));
        CompletableFuture<String> result = new CompletableFuture<>();
        screenRequests.put(screen, result);
        if (!send(new ScreenRequestC2S(screen, mobId))) { screenRequests.remove(screen); result.completeExceptionally(new IllegalStateException("На этом сервере нет Mob Talk")); }
        return result.orTimeout(30, TimeUnit.SECONDS);
    }
    void requestDeal(int syncId) { send(new ScreenRequestC2S("deal", syncId)); }

    // ---- Действия, экраны, команды. ----

    /** На выделенном сервере снимок и карточка выбранного моба приходят одним ответом экрана {@code card}; снимок и карточку
     * вкладка F7 просит разом, поэтому идущий запрос того же моба делится. */
    private CompletableFuture<JsonObject> remoteCard;
    private UUID remoteCardMob;
    private CompletableFuture<JsonObject> remoteCard() {
        MobEntity mob = target();
        if (mob == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран или далеко"));
        if (remoteCard != null && !remoteCard.isDone() && mob.getUuid().equals(remoteCardMob)) return remoteCard;
        remoteCardMob = mob.getUuid();
        remoteCard = ask("card", mob.getId()).thenApply(json -> JsonParser.parseString(json).getAsJsonObject());
        return remoteCard;
    }
    public CompletableFuture<MobSnapshot> selectedSnapshot() {
        if (selected == null || client.player == null || world == null) return CompletableFuture.failedFuture(new IllegalStateException("Сначала наведите прицел на моба и нажмите V"));
        if (client.getServer() == null) return remoteCard().thenApply(json -> GSON.fromJson(json.get("snapshot"), MobSnapshot.class));
        UUID mob = selected, player = client.player.getUuid();
        String dimension = world.getRegistryKey().getValue().toString();
        return io.github.mobtalk.MobTalkMod.bridgeReady(client.getServer()).thenCompose(game -> game.capture(mob, player, dimension));
    }
    public CompletableFuture<MobCard> selectedCard() {
        if (client.getServer() == null) {
            if (selected == null) return CompletableFuture.failedFuture(new IllegalStateException("Сначала наведите прицел на моба и нажмите V"));
            return remoteCard().thenApply(json -> json.has("card") ? GSON.fromJson(json.get("card"), MobCard.class) : null);
        }
        CompletableFuture<MobGameBridge> ready = io.github.mobtalk.MobTalkMod.bridgeReady(client.getServer());
        return selectedSnapshot().thenCompose(snapshot -> ready.thenApply(game -> { game.memory.observe(snapshot); return game.memory.card(snapshot.id(), snapshot.playerId()); }));
    }
    public CompletableFuture<String> requestMobAction(ActionIntent action) {
        if (action == null || action.type() == ActionType.NONE) return CompletableFuture.completedFuture("Выберите действие");
        MobEntity mob = target();
        if (mob == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
        if (action.type() != ActionType.STOP) {
            if (!readyForRequest()) return CompletableFuture.failedFuture(new IllegalStateException(status));
            cancelWork("Моб обдумывает просьбу…");
            requestActive = true;
        } else cancelWork("Действие отменено");
        CompletableFuture<String> result = ask("action", mob.getId());
        if (!send(new MobActionC2S(mob.getId(), GSON.toJson(action)))) { screenRequests.remove("action"); requestActive = false; return CompletableFuture.failedFuture(new IllegalStateException("На этом сервере нет Mob Talk")); }
        return result.whenComplete((ignored, error) -> client.execute(() -> requestActive = false));
    }
    public void stopSelectedAction() {
        MobEntity mob = target();
        if (mob == null) return;
        cancelWork("Действие отменено");
        ask("action", mob.getId()).whenComplete((result, error) -> client.execute(() -> { if (error == null) notice(result); }));
        send(new MobActionC2S(mob.getId(), GSON.toJson(new ActionIntent(ActionType.STOP, "player"))));
    }
    public void stopAllFollowers() {
        ask("action", 0).whenComplete((result, error) -> client.execute(() -> { if (error == null) notice(result); }));
        send(new MobActionC2S(0, GSON.toJson(new ActionIntent(ActionType.STOP, "all"))));
    }
    private CompletableFuture<MobGameBridge> integratedBridge(String what) {
        if (client.getServer() == null) return CompletableFuture.failedFuture(new IllegalStateException(what + " пока доступно только в одиночной игре"));
        return io.github.mobtalk.MobTalkMod.bridgeReady(client.getServer());
    }
    public CompletableFuture<String> editSelectedCard(String name, String personality, String voiceId, String memory) {
        UUID id = selected;
        if (id == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
        cancelWork("Карточка изменена");
        return integratedBridge("Правка карточки").thenApply(game -> { game.memory.updateCard(id, name, personality, voiceId, memory); return "Карточка сохранена"; });
    }
    public CompletableFuture<String> clearSelectedHistory() {
        UUID id = selected;
        if (id == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
        cancelWork("История очищена");
        return integratedBridge("Очистка истории").thenApply(game -> { game.memory.clearHistory(id); return "История очищена"; });
    }
    public CompletableFuture<String> resetSelectedPersonality() {
        UUID id = selected;
        if (id == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
        cancelWork("Личность изменена");
        return integratedBridge("Смена личности").thenApply(game -> { game.memory.resetPersonality(id); return "Особенности личности обновлены; память и отношения сохранены"; });
    }
    /** Подарок из руки: сервер берёт предмет и отвечает строкой; реакция моба придёт его репликой. */
    void giftFromHand() {
        if (stopped || reloading || client.player == null || world == null) return;
        MobEntity mob = aimedMob();
        if (mob == null) mob = target();
        if (mob == null) { notice("Наведите прицел на моба"); return; }
        if (client.player.getMainHandStack().isEmpty()) { notice("Возьмите подарок в руку"); return; }
        send(new GiftC2S(mob.getId()));
    }
    void forgiveVillage() { send(new ScreenRequestC2S("forgive", 0)); }
    /** /mobtalk state: сервер пишет строку в чат. */
    void requestState() { send(new ScreenRequestC2S("state", 0)); }
    void openInfo(Screen parent) {
        if (stopped) return;
        client.setScreen(new InfoScreen(parent, this::infoReport));
    }
    CompletableFuture<String> infoReport(boolean secrets) {
        if (client.player == null || world == null) return CompletableFuture.failedFuture(new IllegalStateException("Мир ещё не загружен"));
        MobEntity mob = target();
        return ask(secrets ? "info_secrets" : "info", mob == null ? 0 : mob.getId()).thenApply(text -> text);
    }
    CompletableFuture<List<String>> villageStatus() {
        if (client.player == null || world == null) return CompletableFuture.completedFuture(List.of("Мир ещё не загружен."));
        return ask("village", 0).thenApply(json -> {
            try { return List.of(GSON.fromJson(json, String[].class)); } catch (RuntimeException bad) { return List.of(json); }
        });
    }
    /** Трек N: экран семьи — пока только в одиночке (встроенный сервер). */
    void openFamily(Screen parent) {
        if (client.getServer() == null || client.player == null) { notice("Экран семьи пока доступен в одиночной игре"); return; }
        MobGameBridge game = MobGameBridge.active(client.getServer());
        if (game == null) { notice("Мир ещё загружается"); return; }
        client.setScreen(new FamilyScreen(parent, game, client.player.getUuid()));
    }
    void openTrade() {
        if (stopped || reloading || client.currentScreen != null) return;
        updateWorld();
        if (!configReady) { notice("Настройки ещё загружаются"); return; }
        if (!config.enabled) { notice("Mob Talk выключен в config/mobtalk.json"); return; }
        MobEntity mob = target();
        if (mob == null || !withinRange(mob) || client.player == null || world == null) { notice("Сначала наведите прицел на моба и нажмите V"); return; }
        if (recording != null) { notice("Сначала отпустите клавишу речи"); return; }
        merchant.ownTradeRequested = selected;
        send(new ScreenRequestC2S("trade", mob.getId()));
    }

    // ---- HUD и чат. ----

    private void showHud() {
        if (client.player == null) return;
        MobEntity mob = target();
        MobStateS2C state = views.state(selected);
        MobLabelS2C label = views.label(selected);
        boolean labelled = config.showNameTags && label != null && !label.name().isBlank();
        String targetLabel = mob == null ? "" : mob.getName().getString() + " · " + String.format(Locale.ROOT, "%.1f м · ", Math.sqrt(client.player.squaredDistanceTo(mob)))
                + (state == null || labelled ? "" : state.label() + " · ");
        MobEntity aimed = client.currentScreen == null ? aimedMob() : null, far = aimed == null && client.currentScreen == null ? aimedMob(48) : null;
        String aimLabel = aimed != null && aimed != mob ? "в прицеле: " + aimed.getName().getString() + " · "
                : far != null && far != mob ? "в прицеле: " + far.getName().getString() + " (далеко, подойдите) · " : "";
        net.minecraft.text.MutableText line = Text.literal("Mob Talk · " + aimLabel + targetLabel);
        if (mob != null && state != null && config.showStateHud && !labelled && !state.word().isEmpty())
            line.append(Text.translatable("mobtalk.state." + state.word()).formatted(state.wordSign() < 0 ? net.minecraft.util.Formatting.RED
                    : state.wordSign() > 0 ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.GRAY)).append(Text.literal(" · "));
        if (mob != null && state != null && config.showBargainHud && !state.bargain().isEmpty())
            line.append(Text.literal("торг: ")).append(Text.translatable("mobtalk.bargain." + state.bargain()).formatted(state.bargainSign() < 0 ? net.minecraft.util.Formatting.RED
                    : state.bargainSign() > 0 ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.YELLOW)).append(Text.literal(" · "));
        String favor = state == null || state.favor().isEmpty() ? "" : state.favor() + " · ";
        client.player.sendMessage(line.append(Text.literal(favor + status)), true);
    }
    void chat(String text) { client.inGameHud.getChatHud().addMessage(Text.literal(text)); }
    private String lastNotice = ""; private long lastNoticeMillis;
    void notice(String message) {
        long now = System.currentTimeMillis();
        if (!message.equals(lastNotice) || now - lastNoticeMillis > 15_000) chat("[Mob Talk] " + message);
        lastNotice = message; lastNoticeMillis = now;
        showHud();
    }

    // ---- Голос. ----

    void stopSpeech() {
        if (speech != null && client.getSoundManager().isPlaying(speech)) speechCutoffs++;
        if (speech != null) speech.stop();
        voices.stopAll(voice -> { if (voice != speech && client.getSoundManager().isPlaying(voice)) speechCutoffs++; voice.stop(); });
        speech = null;
    }
    /** Голос моба от моба; собственный ответ перебивает всё, фоновая реплика идёт, когда эфир свободен (это решает сервер). */
    void startVoice(UUID mob, SpeechStream audio, boolean background) {
        if (background && !voices.mayStart(true)) LOGGER.warn("Mob Talk: a background voice of {} starts over {} sounding", mob, voices.count());
        stopSpeech();
        if (audio == null || world == null) return;
        speech = new MobSpeechSound(client, world, mob, io.github.mobtalk.core.VoiceFalloff.Curve.of(config), audio);
        client.getSoundManager().play(speech); speechStarts++; speakingMob = mob;
        voices.started(speech, mob, background);
    }
    void playOverheard(UUID mob, String line, SpeechStream audio, double range) { chat(line); playVoice(mob, audio, range); }
    void playVoice(UUID mob, SpeechStream audio, double range) {
        if (audio == null || world == null) { stopSpeech(); return; }
        startVoice(mob, audio, true);
    }

    // ---- Движок второй версии (свадьба в одиночке). ----

    /** Движок для свадьбы у колокола: пока она живёт на клиенте одиночки, он создаётся на вызывающем потоке с ключами клиента. */
    java.util.function.Supplier<ConversationEngine> engineSupplier() {
        if (client.getServer() == null || client.player == null || world == null) return () -> null;
        Path worldDirectory = client.getServer().getSavePath(WorldSavePath.ROOT);
        MobTalkConfig settings = config;
        return () -> {
            try { return ensureEngine(settings, worldDirectory); }
            catch (Exception unavailable) { LOGGER.info("Mob Talk: no client engine ({})", unavailable.getClass().getSimpleName()); return null; }
        };
    }
    private synchronized ConversationEngine ensureEngine(MobTalkConfig settings, Path worldDirectory) throws Exception {
        ConversationEngine cached = engine;
        if (cached != null && worldDirectory.equals(engineWorld) && settings == engineConfig) return cached;
        if (cached != null) cached.close();
        ConversationEngine created = new ConversationEngine(settings, worldDirectory, configDirectory);
        engineWorld = worldDirectory; engineConfig = settings; engine = created;
        decider = JevDecider.forConfig(settings, configDirectory);
        return created;
    }
    private void dropEngine() {
        ConversationEngine old = engine; engine = null; engineWorld = null; engineConfig = null;
        if (old != null && !stopped) maintenance.execute(old::close);
    }

    // ---- Настройки. ----

    CompletableFuture<Void> warmWhisper() {
        MobTalkConfig settings = config;
        return CompletableFuture.runAsync(() -> {
            try { helper.ensureAvailable(settings, () -> stopped); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, whisperLoader);
    }
    void openSettings(Screen parent) {
        if (stopped || reloading || parent instanceof MobTalkSettingsScreen) return;
        cancelWork("Настройки Mob Talk");
        dropEngine();
        client.setScreen(new MobTalkSettingsScreen(parent, config.copy(), new SettingsController(this)));
    }
    /** Пишет черновик, перечитывает его как живые настройки, отдаёт серверные поля встроенному серверу и личные — серверу по сети. */
    void saveConfig(MobTalkConfig draft, java.util.function.Consumer<String> completion) {
        if (reloading || stopped) { completion.accept("Настройки ещё сохраняются. Повторите через секунду."); return; }
        reloading = true;
        cancelWork("Сохранение настроек…");
        dropEngine();
        MobTalkConfig snapshot = draft.copy();
        maintenance.execute(() -> {
            try {
                MobTalkConfig.save(configDirectory.resolve("mobtalk.json"), snapshot);
                MobTalkConfig loaded = MobTalkConfig.load(configDirectory.resolve("mobtalk.json"));
                client.execute(() -> {
                    reloading = false;
                    if (stopped) return;
                    applyConfig(loaded);
                    status = !loaded.enabled ? "Mob Talk выключен" : selected == null ? AIM_HINT : readyStatus();
                    completion.accept(null);
                });
            } catch (Exception failure) {
                LOGGER.warn("Mob Talk settings save failed ({})", failure.getClass().getSimpleName());
                client.execute(() -> { reloading = false; if (!stopped) completion.accept(failure.getMessage() == null ? "Не удалось сохранить настройки. Повторите попытку." : failure.getMessage()); });
            }
        });
    }
    private void applyConfig(MobTalkConfig loaded) {
        config = loaded; io.github.mobtalk.core.Tuning.apply(loaded);
        io.github.mobtalk.server.ServerConfig.adopt(loaded); // Встроенный сервер одиночки живёт по серверным полям этого файла.
        configReady = true;
        preloadWhisper(loaded);
        OverlayClient.apply(loaded);
        ClientNet.sendSettings(this);
    }
    void reload() {
        if (reloading || stopped) return;
        reloading = true;
        cancelWork("Загрузка настроек…");
        dropEngine();
        maintenance.execute(() -> {
            try {
                MobTalkConfig loaded = MobTalkConfig.load(configDirectory.resolve("mobtalk.json"));
                client.execute(() -> {
                    if (stopped) return;
                    applyConfig(loaded);
                    reloading = false;
                    status = selected == null ? AIM_HINT : readyStatus();
                    if (world != null) notice("Настройки загружены");
                });
            } catch (Exception failure) {
                LOGGER.warn("Mob Talk config failed ({})", failure.getClass().getSimpleName());
                client.execute(() -> {
                    reloading = false; configReady = false;
                    status = "Не удалось прочитать config/mobtalk.json: исправьте JSON и /mobtalk reload";
                    if (world != null) notice(status);
                });
            }
        });
    }
    private void preloadWhisper(MobTalkConfig settings) {
        if (stopped || !settings.enabled || !settings.whisperAutoStart || System.getProperty("mobtalk.selftest") != null || SelfTestServer.on()) return;
        long ticket = ++whisperPreloads;
        whisperLoader.execute(() -> {
            try {
                helper.ensureAvailable(settings, () -> stopped || ticket != whisperPreloads);
                LOGGER.info("Mob Talk: Whisper {} loaded at start and kept loaded", settings.whisperModel);
            } catch (InterruptedException cancelled) {
                // Игра закрывается или настройки сменились.
            } catch (Exception failure) {
                LOGGER.warn("Mob Talk: Whisper preload failed: {}", failure.getMessage());
            }
        });
    }
    private void shutdown() {
        if (stopped) return;
        cancelWork("Остановлено", false, false);
        stopped = true;
        microphone.close();
        pipeline.shutdownNow();
        whisperLoader.shutdownNow();
        ConversationEngine old = engine; engine = null;
        maintenance.execute(() -> { if (old != null) old.close(); helper.stopOwned(); });
        maintenance.shutdown();
    }

    /** Запрос клиента: цель, её сетевой номер и имя, настройки и торговое окно, из которого он сделан. */
    private record Request(long generation, UUID target, int entityId, String mobName, MobTalkConfig config, MerchantSession.Context merchant) { }
}
