package io.github.mobtalk.client;

import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.ConversationSummary;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.WhisperClient;
import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.Decider;
import io.github.mobtalk.core.DecisionState;
import io.github.mobtalk.core.JevDecider;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.OpenRouterConnection;
import io.github.mobtalk.core.RuleDecider;
import io.github.mobtalk.client.game.MobGameBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.MinecraftServer;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client-only adapter: immutable world snapshots cross the worker boundary, Minecraft objects do not. */
public final class MobTalkClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    final MinecraftClient client = MinecraftClient.getInstance();
    final Path configDirectory = FabricLoader.getInstance().getConfigDir();
    private final ExecutorService pipeline = worker("mobtalk-conversation");
    final ExecutorService maintenance = worker("mobtalk-settings");
    /** Bystanders think in parallel with each other and with the partner's voice; only the playback is one at a time. */
    private final ExecutorService witnesses = Executors.newFixedThreadPool(2, task -> { Thread thread = new Thread(task, "mobtalk-witness"); thread.setDaemon(true); return thread; });

    private final MicrophoneRecorder microphone = new MicrophoneRecorder();
    private final WhisperProcess helper = new WhisperProcess();
    private final AtomicBoolean pipelineBusy = new AtomicBoolean();
    private volatile long generation;
    private volatile Thread pipelineThread;
    private volatile ConversationEngine engine;
    private volatile WhisperClient whisper;
    private volatile CompletableFuture<Void> helperCleanup = CompletableFuture.completedFuture(null);
    volatile CompletableFuture<MobGameBridge> gameReady;
    private MinecraftServer gameServer;
    private MobTalkConfig gameConfig;
    private Path memoryWorld;
    private Path engineWorld;
    private MobTalkConfig engineConfig;
    private MobTalkConfig config = new MobTalkConfig();
    ClientWorld world;
    UUID selected;
    /** The mob of the conversation under way (its last reply was delivered) and when; silence or another mob ends it (a summary is made). */
    private UUID conversationMob;
    private long lastReplyMillis;
    KeyBinding talkKey;
    KeyBinding settingsKey;
    KeyBinding tradeKey;
    KeyBinding giftKey;
    private final MobLabels labels = new MobLabels(this);
    /** Трек N: живот, строка под именем, затемнения. */
    private final ChildView children = new ChildView(this);
    KeyBinding familyKey;
    private boolean openSettingsRequested;
    private MicrophoneRecorder.Capture recording;
    private boolean configReady;
    boolean reloading;
    private boolean stopped;
    boolean keyHeld;
    private boolean requestActive;
    private final MerchantSession merchant = new MerchantSession(this);
    private final BedTalk bed = new BedTalk(this);
    /** Creatures speaking first and scenes between them; the decider behind them (rules, or Jev with the rules as fallback). */
    final InitiativeVoice initiatives = new InitiativeVoice(this);
    /** Villagers talking among themselves and writing to the chat. */
    final VillageTalkVoice village = new VillageTalkVoice(this);
    /** Трек L: свадьба у колокола — тексты, голоса и клятва игрока. */
    final WeddingVoice wedding = new WeddingVoice(this);
    private volatile Decider decider = new RuleDecider();
    private CompletableFuture<String> actionRequest;
    /** Bystander replies already prepared (LLM done, voice prebuffered), waiting for the current voice to end. */
    private final Deque<Ready> readyWitnesses = new ArrayDeque<>();
    private final List<java.util.concurrent.Future<?>> witnessTasks = new ArrayList<>();
    private int witnessesInFlight;
    private record Ready(Request request, ConversationEngine engine, ConversationEngine.PreparedReply prepared) { }
    /** The mob whose voice is playing: it keeps standing and looking at the player until it is done. */
    private UUID speakingMob;
    /** A move (flee, follow, attack) the mob announced: it is carried out once its voice has finished, not mid-sentence. */
    private Deferred deferredAction;
    private record Deferred(Request request, ActionIntent action) { }
    /** Replies handed to the client thread whose server application is still in flight: the next bystander waits for them. */
    private int pendingDeliveries;
    /** V was pressed while the pipeline was still busy: recording starts the moment it frees up, as long as V stays down. */
    private boolean armRecording;
    /** The mob's last haggling move, for the one-word verdict in the HUD; shown for {@link #BARGAIN_HUD_TICKS} after it. */
    private io.github.mobtalk.core.BargainMeter.Move bargainMove;
    private long bargainUntil;
    private static final long BARGAIN_HUD_TICKS = 2400;

    private long ticks;
    String status = "Загрузка настроек…";
    private MobSpeechSound speech;

    @Override public void onInitializeClient() {
        io.github.mobtalk.client.game.WeddingItems.register(); // Свои предметы — до загрузки любого мира.
        MobGameBridge.initialize();
        ConversationEngine.setDiagnostics(line -> LOGGER.info("Mob Talk: {}", line));
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.talk", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V, "category.mobtalk"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.settings", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7, "category.mobtalk"));
        tradeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.trade", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B, "category.mobtalk"));
        giftKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.gift", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G, "category.mobtalk"));
        familyKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.family", InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(), "category.mobtalk"));
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(labels::render);
        ChildView.register();
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(children::renderFade);
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Mob Talk"), button -> openSettings(screen))
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
        io.github.mobtalk.client.game.DatasetHarvester.install(client);
        reload();
    }

    /** Settings are on and loaded. */
    boolean enabled() { return configReady && config.enabled; }
    /** A recording, a request or a spoken reply is in flight. */
    boolean busyTalking() { return recording != null || requestActive || speech != null; }
    boolean speaking() { return speech != null; }
    MobTalkConfig config() { return config; }
    /** Self-test only: opens the configured microphone and records for up to {@code seconds} (stop() ends it earlier). */
    MicrophoneRecorder.Capture microphoneProbe(int seconds) { return microphone.start(config.microphoneName, seconds); }
    /** How many spoken replies started and how many were cut off by the next one while still playing (self-test bookkeeping). */
    int speechStarts, speechCutoffs, repliesShown;
    /** A recorded question handed in as WAV (the self-test voices one with TTS); goes through Whisper like a real recording. */
    boolean sayWav(byte[] wav) {
        if (!readyForRequest()) { notice(status); return false; }
        stopSpeech();
        long requestGeneration = ++generation;
        captureShot(requestGeneration);
        submit(request(requestGeneration, null), wav, null);
        return true;
    }
    /** Bystanders still waiting for their turn, or one being applied right now. */
    boolean witnessPending() { return witnessesInFlight > 0 || !readyWitnesses.isEmpty() || pendingDeliveries > 0; }
    ConversationEngine engine() { return engine; }
    Decider decider() { return decider; }
    long generation() { return generation; }
    /** A short piece of work off the game thread (a decision), on the bystander lane. */
    void background(Runnable work) { witnesses.execute(work); }
    void requestSettings() { openSettingsRequested = true; }
    /** /mobtalk info: the chat closes first, the panel opens on the next tick. */
    void requestInfo() { openInfoRequested = true; }
    /** Трек N: экран семьи из команды — откроется на следующем тике, когда чат закроется. */
    void requestFamily() { openFamilyRequested = true; }
    private boolean openFamilyRequested;
    private boolean openInfoRequested;
    /** The player's view at the moment a question starts: pixels sampled on the render thread, encoded later off it. */
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
                int abgr = image.getColor(x * step, y * step); // NativeImage packs ABGR: red in the low byte.
                rgb[y * w + x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
            }
            shot = new Shot(requestGeneration, w, h, rgb);
        } catch (RuntimeException failure) { LOGGER.debug("Mob Talk screenshot skipped ({})", failure.getClass().getSimpleName()); }
    }
    /** The JPEG for this request, or null: only when the mode and the question call for it ({@link io.github.mobtalk.core.Vision#wanted}). */
    private byte[] pictureFor(Request request, MobSnapshot snapshot, String question, MobGameBridge game) {
        Shot taken = shot;
        if (taken == null || taken.generation() != request.generation || request.overheard != null) return null;
        MobCard card = game.memory.card(request.target, request.player);
        boolean firstMeeting = card == null || card.turns().isEmpty();
        if (!io.github.mobtalk.core.Vision.wanted(request.config.vision, snapshot, firstMeeting, question)) return null;
        try { return io.github.mobtalk.core.Vision.jpeg(taken.rgb(), taken.width(), taken.height()); }
        catch (java.io.IOException failure) { LOGGER.debug("Mob Talk screenshot not encoded ({})", failure.getMessage()); return null; }
    }

    /** /mobtalk say: the typed line goes through the same pipeline as speech. */
    boolean say(String text) {
        if (!readyForRequest()) { notice(status); return false; }
        stopSpeech();
        long requestGeneration = ++generation;
        captureShot(requestGeneration);
        submit(request(requestGeneration, null), null, text);
        return true;
    }
    String statusLine() {
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        String favor = game == null ? "" : favorLabel(game).replace(" · ", "");
        return status + (selected == null ? " · цель не выбрана" : " · UUID " + selected + (favor.isEmpty() ? "" : " · " + favor));
    }

    String readyStatus() { return "Готов: удерживайте " + talkKey.getBoundKeyLocalizedText().getString(); }
    static final String AIM_HINT = "Наведите прицел на моба и удерживайте V";

    /** The living mob under the crosshair within the conversation range, or null; a dragon part counts as the dragon. */
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

    /** V with no window open: the mob under the crosshair becomes the conversation partner; with nothing in the crosshair the
     * last one keeps the conversation. */
    private void aimBeforeTalking() {
        if (!enabled() || client.player == null || !client.player.isAlive() || client.player.isSpectator() || world == null) return;
        MobEntity aimed = bed.open() ? bed.partner() : aimedMob(); // В кровати прицела нет: собеседник — житель у кровати.
        if (aimed != null && !aimed.getUuid().equals(selected)) select(aimed);
        else if (aimed == null && target() == null) { status = AIM_HINT; showHud(); }
    }

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private void tick() {
        ticks++;
        if (ticks % 200 == 0) keepConnectionWarm();
        updateWorld();
        ensureGameBridge();
        merchant.tick(ticks);
        while (settingsKey.wasPressed()) openSettingsRequested = true;
        if (openSettingsRequested && !reloading && !stopped) {
            openSettingsRequested = false;
            openSettings(client.currentScreen);
        }
        if (openInfoRequested && !reloading && !stopped) { openInfoRequested = false; openInfo(client.currentScreen); }
        if (openFamilyRequested && !reloading && !stopped) { openFamilyRequested = false; openFamily(client.currentScreen); }
        while (tradeKey.wasPressed()) openTrade();
        while (giftKey.wasPressed()) giftFromHand();
        while (familyKey.wasPressed()) openFamily(client.currentScreen);
        boolean held = merchant.windowOpen() ? merchant.keyHeld() : bed.open() ? bed.keyHeld() : talkKey.isPressed();
        MobEntity mob = target();
        if (selected != null && mob == null) clearSelection("Моб погиб или выгрузился: наведите прицел на другого");
        if (client.player != null && !client.player.isAlive()
                && (recording != null || requestActive || speech != null)) cancelWork("Игрок погиб: разговор отменён");
        if (mob != null) {
            if (!withinRange(mob) && (recording != null || requestActive || speech != null))
                cancelWork("Подойдите к мобу ближе: разговор отменён");
        }
        if (merchant.work() != null && !client.isWindowFocused()) {
            cancelWork("Окно неактивно: торговый разговор отменён");
            merchant.clearKeyHeld();
            held = false;
        }
        if (recording != null && (!recordingScreen() || !client.isWindowFocused()
                || world == null || client.player == null || !client.player.isAlive())) {
            cancelWork("Запись отменена");
        }
        if (held && !keyHeld && !merchant.windowOpen()) aimBeforeTalking();
        if (held && !keyHeld && canRecord()) {
            if (recording == null && pipelineBusy.get()) armRecording = true; // The player's own request is waited out.
            else beginRecording();
        }
        if (armRecording && held && recording == null && !pipelineBusy.get() && canRecord()) { armRecording = false; beginRecording(); }
        if (!held) armRecording = false;
        if (!readyWitnesses.isEmpty()) playNextWitness();
        if (ticks % 20 == 0) keepAttention();
        initiatives.tick(ticks);
        village.tick(ticks);
        wedding.tick(ticks);
        if (!held && keyHeld && recording != null) {
            status = "Завершение записи…";
            recording.stop();
        }
        keyHeld = held;
        if (speech != null && !client.getSoundManager().isPlaying(speech)) {
            speech = null; speakingMob = null;
            if (!requestActive && recording == null) status = readyStatus();
            runDeferredAction();
        }
        if (selected != null && ticks % 20 == 0) showHud();
        if (ticks % 10 == 7) { children.tick(); labels.tick(); }
        if (conversationMob != null && ticks % 20 == 0 && System.currentTimeMillis() - lastReplyMillis > config.conversationSilenceMinutes * 60_000L) endConversation(conversationMob);
    }

    /** The conversation with this mob is over: the engine summarises it in the background (nothing here waits or touches the network). */
    private void endConversation(UUID mob) {
        if (mob == null) return;
        if (mob.equals(conversationMob)) conversationMob = null;
        ConversationEngine localEngine = engine;
        if (localEngine == null || stopped || client.player == null) return;
        UUID player = client.player.getUuid(); String name = MobGameBridge.playerName(client.player);
        maintenance.execute(() -> localEngine.endConversation(mob, player, name));
    }

    /** Окно, в котором можно говорить: никакого, торговое окно этого моба или окно чата в кровати (трек J). */
    private boolean recordingScreen() { return merchant.allowedRecordingScreen() || bed.open(); }

    private boolean canRecord() {
        return enabled() && !reloading && recordingScreen()
                && client.isWindowFocused() && client.player != null && client.player.isAlive() && world != null;
    }

    void updateWorld() {
        ClientWorld current = client.getServer() == null ? null : client.world;
        if (world == current) return;
        leaveWorld();
        world = current;
        keyHeld = talkKey != null && talkKey.isPressed();
        if (world != null) status = configReady ? AIM_HINT : "Загрузка настроек…";
    }

    private void leaveWorld() {
        merchant.end("Мир сменился: торговый разговор отменён");
        cancelWork(AIM_HINT, true);
        wedding.reset();
        selected = null;
        world = null;
        if (client.getServer() == null) releaseMemory();
        if (!stopped) helperCleanup = CompletableFuture.runAsync(helper::stopOwned, maintenance);
    }

    void select(MobEntity mob) {
        if (!mob.getUuid().equals(selected)) {
            if (conversationMob != null && !conversationMob.equals(mob.getUuid())) endConversation(conversationMob);
            cancelWork(readyStatus());
            selected = mob.getUuid();
            bargainMove = null; // Another partner, another haggle.
            keepConnectionWarm();
        }
        // Selecting the same UUID keeps its existing engine/profile/history.
        showHud();
    }

    /** Opens (or keeps open) the OpenRouter connection while a mob is selected, so the first reply skips the handshake. */
    private void keepConnectionWarm() {
        if (stopped || !configReady || !config.enabled || selected == null) return;
        maintenance.execute(() -> OpenRouterConnection.keepWarm(configDirectory, config.needsVenice(), config.needsOpenRouter()));
    }

    void clearSelection(String reason) {
        endConversation(conversationMob);
        cancelWork(reason);
        selected = null;
        showHud();
    }

    void cancelWork(String reason) { cancelWork(reason, false); }

    /** Interrupting the worker cancels in-flight requests; the engine itself is kept across mob switches and trade windows,
     * since rebuilding it reloads the key and relearns provider quirks. Settings, reloads and world changes drop it. */
    private void cancelWork(String reason, boolean dropEngine) {
        generation++;
        armRecording = false; deferredAction = null;
        initiatives.cancel();
        village.cancel();
        for (var task : witnessTasks) task.cancel(true);
        witnessTasks.clear();
        for (Ready ready : readyWitnesses) ready.prepared.reply().discardSpeech();
        readyWitnesses.clear();
        if (actionRequest != null) actionRequest.completeExceptionally(new java.util.concurrent.CancellationException(reason));
        actionRequest = null;
        requestActive = false;
        merchant.setWork(null);
        if (recording != null) recording.cancel();
        recording = null;
        stopSpeech();
        Thread running = pipelineThread;
        if (running != null) running.interrupt();
        WhisperClient oldWhisper = whisper;
        whisper = null;
        ConversationEngine oldEngine = dropEngine ? engine : null;
        if (dropEngine) engine = null;
        if (!stopped && (oldWhisper != null || oldEngine != null)) maintenance.execute(() -> {
            long started = System.nanoTime();
            if (oldWhisper != null) oldWhisper.close();
            if (oldEngine != null) oldEngine.close();
            long took = (System.nanoTime() - started) / 1_000_000;
            if (took > 1000) LOGGER.info("Mob Talk: closing the previous engine took {} ms", took);
        });
        status = reason;
    }

    private void beginRecording() {
        if (!readyForRequest()) return;
        stopSpeech();
        long requestGeneration = ++generation;
        Request seed = request(requestGeneration, null);
        captureShot(requestGeneration);
        try {
            // Трек L: клятву на свадьбе можно говорить дольше обычного вопроса.
            int seconds = wedding.takesVow() ? config.weddingVowSeconds : config.maxRecordingSeconds;
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
        if (!recordingScreen() || !client.isWindowFocused()) {
            status = "Запись отменена";
            showHud();
            return;
        }
        if (error != null) {
            reportFailure(seed, error);
            return;
        }
        if (wav == null) {
            status = "Запись слишком короткая: " + readyStatus() + " и произнесите вопрос";
            showHud();
            return;
        }
        // The authoritative server snapshot is captured after STT, immediately before the LLM.
        submit(seed, wav, null);
    }

    private boolean readyForRequest() {
        updateWorld();
        if (!configReady || reloading) status = "Настройки ещё загружаются";
        else if (!config.enabled) status = "Mob Talk выключен в config/mobtalk.json";
        else if (world == null || client.player == null || !client.player.isAlive()) status = "Mob Talk работает в одиночном мире";
        else if (target() == null) status = AIM_HINT;
        else if (!withinRange(target())) status = "Подойдите к мобу ближе (до " + Math.round(config.maxDistance) + " блоков)";
        else if (recording != null || pipelineBusy.get()) status = "Предыдущий разговор ещё завершается";
        else return true;
        showHud();
        return false;
    }

    private Request request(long requestGeneration, MobSnapshot snapshot) {
        return new Request(requestGeneration, selected,
                client.getServer().getSavePath(WorldSavePath.ROOT), config, snapshot, client.getServer(),
                client.player.getUuid(), world.getRegistryKey().getValue().toString(), target() == null ? "" : target().getName().getString(),
                merchant.current(), null);
    }

    /** After the mob spoken to answered: who else heard it. Every bystander starts thinking at once, in parallel with the
     * partner's voice; the lines are then played one after another as they come ready, never over another voice. */
    private void queueWitnesses(Request request, ConversationEngine.Reply reply) {
        if (request.overheard != null || config.witnessReplies <= 0 || reply.silent()) return;
        MobGameBridge game = MobGameBridge.active(request.server);
        if (game == null) return;
        ConversationEngine.Overheard heard = new ConversationEngine.Overheard(reply.speakerName(), reply.recognizedText(), reply.responseText());
        // An insult, a threat, an apology or a mob that runs or attacks is worth a bystander's voice every time; small talk only sometimes.
        boolean eventful = reply.socialIntent() != io.github.mobtalk.core.SocialIntent.NONE && reply.socialIntent() != io.github.mobtalk.core.SocialIntent.KIND
                || reply.action().type() == ActionType.ATTACK || reply.action().type() == ActionType.FLEE;
        game.witnesses(request.target, request.player, request.dimension, config.witnessReplies).whenComplete((ids, error) -> client.execute(() -> {
            if (error != null || ids.isEmpty() || generation != request.generation) return;
            for (UUID id : ids) startWitness(id, heard, request, eventful, game);
        }));
    }

    /** One bystander: the decider says off the game thread whether it cuts in (always after something eventful), then it thinks. */
    private void startWitness(UUID id, ConversationEngine.Overheard heard, Request after, boolean eventful, MobGameBridge game) {
        UUID player = after.player;
        startSpontaneous(id, null, () -> {
            if (eventful) return heard;
            DecisionState state = game.memory.decision(id, player, null, heard.speaker(), heard.said() + " / " + heard.answer(), false);
            return decider.bystanderAnswers(state) ? heard : null;
        });
    }

    /** A line nobody asked for — a bystander's, a creature's first word, an answer to another creature: {@code decide} runs on the
     * bystander lane and returns what the mob heard (or the occasion), or null to keep quiet. The thinking happens off both the game
     * thread and the conversation worker; the result lands in readyWitnesses and is voiced when the floor is free. */
    void startSpontaneous(UUID id, String mobName, java.util.function.Supplier<ConversationEngine.Overheard> decide) {
        MobEntity mob = MobSpeechSound.find(world, id);
        if (mob == null || !withinRange(mob) || client.player == null || client.getServer() == null) { LOGGER.info("Mob Talk bystander {} out of reach on the client", id); initiatives.ended(id); return; }
        Request seed = new Request(generation, id, client.getServer().getSavePath(WorldSavePath.ROOT), config, null, client.getServer(), client.player.getUuid(),
                world.getRegistryKey().getValue().toString(), mobName == null || mobName.isBlank() ? mob.getName().getString() : mobName, null, null);
        ConversationEngine localEngine = engine;
        if (localEngine == null) { initiatives.ended(id); return; }
        witnessesInFlight++;
        witnessTasks.add(witnesses.submit(() -> {
            boolean spoke = false;
            try {
                if (generation != seed.generation) return;
                ConversationEngine.Overheard heard = decide.get();
                if (heard == null || generation != seed.generation) return;
                MobGameBridge game = gameReady.get(10, TimeUnit.SECONDS);
                game.attend(seed.target, seed.player, 100);
                MobSnapshot snapshot = game.capture(seed.target, seed.player, seed.dimension).get(10, TimeUnit.SECONDS);
                if (generation != seed.generation) return;
                ConversationEngine.PreparedReply prepared = localEngine.prepare(snapshot, "", heard);
                spoke = true;
                Request observed = new Request(seed.generation, seed.target, seed.worldDirectory, seed.config, snapshot, seed.server, seed.player,
                        seed.dimension, seed.mobName, null, heard);
                client.execute(() -> {
                    if (generation != seed.generation) { prepared.reply().discardSpeech(); initiatives.ended(id); return; }
                    readyWitnesses.add(new Ready(observed, localEngine, prepared));
                });
            } catch (Exception failure) {
                LOGGER.info("Mob Talk bystander {} stayed out: {}", seed.mobName, String.valueOf(failure.getMessage()));
            } finally {
                Thread.interrupted();
                boolean delivered = spoke;
                client.execute(() -> { witnessesInFlight--; if (!delivered) initiatives.ended(id); });
            }
        }));
    }

    /** The floor is free: the next ready bystander speaks. */
    private void playNextWitness() {
        if (speech != null || requestActive || recording != null || pendingDeliveries > 0) return;
        Ready next = readyWitnesses.poll();
        if (next == null) return;
        // Трек L: на свадьбе посторонние реплики (очевидцы, инициатива), задуманные до неё, не звучат.
        if (wedding.active()) { next.prepared.reply().discardSpeech(); initiatives.ended(next.request.target); return; }
        if (generation != next.request.generation) { next.prepared.reply().discardSpeech(); return; }
        pendingDeliveries++;
        deliver(next.request, next.engine, next.prepared);
    }

    /** Every second: the mob being spoken to, the one speaking and every bystander still thinking stay put and keep looking at the player. */
    private void keepAttention() {
        if (client.player == null || client.getServer() == null) return;
        MobGameBridge game = MobGameBridge.active(client.getServer());
        if (game == null) return;
        UUID player = client.player.getUuid();
        if (selected != null && (requestActive || speakingMob == null && speech != null)) game.attend(selected, player, 40);
        if (speakingMob != null) game.attend(speakingMob, player, 40);
        for (Ready ready : readyWitnesses) game.attend(ready.request.target, player, 40);
    }

    void ensureGameBridge() {
        MinecraftServer server = client.getServer();
        if (server == null) { releaseMemory(); return; }
        if (world == null || !configReady || stopped || gameServer == server && gameConfig == config) return;
        gameServer = server; gameConfig = config;
        Path directory = server.getSavePath(WorldSavePath.ROOT);
        memoryWorld = directory;
        MobTalkConfig preferences = config;
        CompletableFuture<MobGameBridge> ready = new CompletableFuture<>();
        gameReady = ready;
        maintenance.execute(() -> {
            try {
                ConversationStore memory = ConversationStore.open(directory, preferences);
                server.execute(() -> {
                    if (gameReady != ready || stopped) { ready.completeExceptionally(new IllegalStateException("Мир сменился")); return; }
                    try { ready.complete(MobGameBridge.attach(server, memory, preferences)); }
                    catch (Throwable failure) { ready.completeExceptionally(failure); }
                });
            } catch (Throwable failure) { ready.completeExceptionally(failure); }
        });
    }
    private void releaseMemory() {
        Path leaving = memoryWorld;
        MinecraftServer leavingServer = gameServer;
        if (leaving == null) return;
        memoryWorld = null; gameServer = null; gameConfig = null; gameReady = null;
        if (!stopped) MobGameBridge.detach(leavingServer).thenRunAsync(() -> {
            try { ConversationStore.closeWorld(leaving); }
            catch (Exception failure) { LOGGER.warn("Mob Talk memory flush failed ({})", failure.getClass().getSimpleName()); }
        }, maintenance);
    }

    private void submit(Request request, byte[] wav, String text) {
        if (!pipelineBusy.compareAndSet(false, true)) {
            status = "Предыдущий разговор ещё завершается";
            if (actionRequest != null) actionRequest.completeExceptionally(new IllegalStateException(status));
            actionRequest = null;
            showHud();
            return;
        }
        requestActive = true;
        merchant.setWork(request.merchant);
        status = request.overheard != null ? request.mobName + " прислушивается…" : wav == null ? "Моб думает…" : "Whisper распознаёт…";
        showHud();
        pipeline.execute(() -> {
            pipelineThread = Thread.currentThread();
            try {
                checkCurrent(request);
                String question = text;
                if (wav != null) {
                    if (io.github.mobtalk.core.WeddingFaults.whisper && wedding.takesVow()) throw new java.io.IOException("самотест: Whisper недоступен");
                    updateStatus(request, "Whisper запускается и распознаёт…");
                    helperCleanup.get(10, TimeUnit.SECONDS);
                    checkCurrent(request);
                    helper.ensureAvailable(request.config, () -> generation != request.generation, message -> updateStatus(request, message));
                    checkCurrent(request);
                    try (WhisperClient localWhisper = new WhisperClient(request.config)) {
                        whisper = localWhisper;
                        checkCurrent(request);
                        question = localWhisper.transcribe(wav, whisperContext(request));
                    } finally { whisper = null; }
                    checkCurrent(request);
                    if (question == null || question.isBlank()) {
                        if (request.overheard == null && wedding.takesVow()) client.execute(wedding::vowUnheard);
                        updateStatus(request, "Речь не распознана: удерживайте голосовую клавишу и произнесите вопрос");
                        return;
                    }
                }
                // Трек L: на свадьбе сказанное в свою очередь — клятва, она уходит в службу, а не в разговор.
                if (request.overheard == null && wedding.takesVow() && question != null && !question.isBlank()) {
                    String vow = question;
                    client.execute(() -> wedding.vowHeard(vow));
                    updateStatus(request, "Клятва произнесена");
                    return;
                }
                checkCurrent(request);
                updateStatus(request, request.overheard != null ? request.mobName + " прислушивается…" : "Моб думает и готовит голос…");
                CompletableFuture<MobGameBridge> ready = gameReady;
                if (ready == null) throw new IllegalStateException("Память мира ещё загружается");
                MobGameBridge game = ready.get(10, TimeUnit.SECONDS);
                if (game.server != request.server) throw new InterruptedException("Мир сменился");
                if (request.merchant != null && !request.target.equals(game.selectMerchant(request.player,
                        request.merchant.syncId()).get(10, TimeUnit.SECONDS))) throw new InterruptedException("Торговец сменился");
                MobSnapshot snapshot = request.snapshot == null
                        ? game.capture(request.target, request.player, request.dimension).get(10, TimeUnit.SECONDS) : request.snapshot;
                checkCurrent(request);
                ConversationEngine localEngine = ensureEngine(request);
                checkCurrent(request);
                // A mob without a life story invents it inside this first reply (see MobPrompt.lifeToInvent): no separate request.
                ConversationEngine.PreparedReply prepared = localEngine.prepare(snapshot, question, request.overheard, pictureFor(request, snapshot, question, game));
                try { checkCurrent(request); }
                catch (InterruptedException cancelled) { prepared.reply().discardSpeech(); throw cancelled; }
                Request observed = new Request(request.generation, request.target, request.worldDirectory, request.config,
                        snapshot, request.server, request.player, request.dimension, request.mobName, request.merchant, request.overheard);
                client.execute(() -> { pendingDeliveries++; deliver(observed, localEngine, prepared); });
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                client.execute(() -> {
                    if (generation == request.generation) {
                        status = "Состояние моба изменилось: ответ отменён";
                        if (actionRequest != null) actionRequest.completeExceptionally(cancelled);
                        actionRequest = null; showHud();
                    }
                });
            } catch (Exception failure) {
                client.execute(() -> reportFailure(request, failure));
            } finally {
                pipelineThread = null;
                pipelineBusy.set(false);
                // Do not leak an interrupt into the executor's next request.
                Thread.interrupted();
                client.execute(() -> {
                    if (generation == request.generation) requestActive = false;
                });
            }
        });
    }

    private ConversationEngine ensureEngine(Request request) throws Exception { return ensureEngine(request, true); }
    /** {@code bound}: the engine is for this request's generation; a prefetch is bound to the selection instead. Synchronized: the
     * conversation worker and the biography worker may both need it at once. */
    private synchronized ConversationEngine ensureEngine(Request request, boolean bound) throws Exception {
        // bound=false was the biography prefetch's; kept for a caller that outlives the request generation.
        ConversationEngine cached = engine;
        if (cached != null && request.worldDirectory.equals(engineWorld) && request.config == engineConfig) return cached;
        if (cached != null) cached.close();
        ConversationEngine created = new ConversationEngine(request.config, request.worldDirectory, configDirectory);
        if (bound && generation != request.generation || Thread.currentThread().isInterrupted()) {
            created.close();
            throw new InterruptedException("Cancelled");
        }
        engineWorld = request.worldDirectory;
        engineConfig = request.config;
        engine = created;
        decider = JevDecider.forConfig(request.config, configDirectory); // Loads the key on this worker, never on the game thread.
        return created;
    }

    /** Entered with pendingDeliveries already counted; every way out of the delivery, synchronous or not, releases it. */
    private void deliver(Request request, ConversationEngine localEngine, ConversationEngine.PreparedReply prepared) {
        boolean async = false;
        try {
            ConversationEngine.Reply reply = prepared.reply();
            if (!isCurrent(request)) { dropped(request, reply); return; }
            MobGameBridge game = MobGameBridge.active(request.server);
            if (game == null) {
                reply.discardSpeech();
                status = "Мир сменился: задайте вопрос ещё раз";
                if (actionRequest != null) actionRequest.completeExceptionally(new java.util.concurrent.CancellationException(status));
                actionRequest = null; showHud(); return;
            }
            // The relationship may have moved while the model wrote (a blow, a gift, a bystander's word): the reply is still spoken;
            // the model sees the change on its next turn. Only a dead or missing mob cancels (checked on the server).
            CompletableFuture<UUID> merchant = request.merchant == null ? CompletableFuture.completedFuture(request.target)
                    : game.selectMerchant(request.player, request.merchant.syncId());
            async = true;
            // A mob that says "I'm leaving" leaves after saying it: the move waits for the voice, the rest applies now.
            ActionIntent action = reply.action();
            boolean defer = reply.speech() != null && action.type() != ActionType.NONE && action.type() != ActionType.STOP;
            merchant.thenCompose(id -> {
                if (!request.target.equals(id)) throw new java.util.concurrent.CancellationException("Торговец сменился");
                return game.applyWithRevision(request.target, request.player, request.dimension, defer ? ActionIntent.none() : action, reply.bargain(),
                        reply.deal(), reply.unlock(), reply.quest(), reply.socialIntent(), prepared.gesture(), request.snapshot, reply.interactionRevision(), () -> generation == request.generation);
            }).whenComplete((delivery, error) -> client.execute(() -> {
                try {
                    if (!isCurrent(request)) { dropped(request, reply); return; }
                    if (error != null) { reply.discardSpeech(); reportFailure(request, error); return; }
                    if (!localEngine.commitDelivered(prepared, delivery.revision())) {
                        reply.discardSpeech();
                        status = "Моб исчез: ответ отменён";
                        if (actionRequest != null) actionRequest.completeExceptionally(new java.util.concurrent.CancellationException(status));
                        actionRequest = null; showHud(); return;
                    }
                    // Трек J: «пойдём спать» своей паре — она пойдёт за игроком к кровати (не пару сервер не позовёт).
                    if (request.overheard == null && io.github.mobtalk.core.CoSleep.invites(reply.recognizedText())) game.inviteToBed(request.target, request.player);
                    // Трек L: «идём на свадьбу» невесте — свадьба у колокола; «будь моим свидетелем» — свидетель (решено заранее, как жест).
                    if (request.overheard == null) weddingWords(game, request, reply, prepared.gesture());
                    if (request.overheard == null) familyWords(game, request, reply);
                    finishDelivery(request, reply, delivery.message());
                    if (defer) { deferredAction = new Deferred(request, action); if (speech == null) runDeferredAction(); }
                } finally { pendingDeliveries--; }
            }));
        } finally { if (!async) pendingDeliveries--; }
    }

    /** Трек N: «у меня для тебя сюрприз» супругу — особый вечер; любые слова паре в особую ночь держат ночь, пока шепчутся. */
    private void familyWords(MobGameBridge game, Request request, ConversationEngine.Reply reply) {
        if (!config.familyLife || !config.socialLayer) return;
        game.eveningSpoke(request.player);
        if (io.github.mobtalk.core.SpecialEvening.invites(reply.recognizedText()))
            game.startEvening(request.target, request.player).thenAccept(message -> client.execute(() -> { if (!message.isBlank()) chat("§d[Mob Talk] " + message); }));
    }

    /** Трек N: экран семьи — супруг, дети и что им нужно; только чтение мира. */
    void openFamily(net.minecraft.client.gui.screen.Screen parent) {
        if (client.getServer() == null || client.player == null) { notice("Экран семьи доступен в одиночной игре"); return; }
        MobGameBridge game = MobGameBridge.active(client.getServer());
        if (game == null) { notice("Мир ещё загружается"); return; }
        client.setScreen(new FamilyScreen(parent, game, client.player.getUuid()));
    }

    /** Трек L: что сказанное значит для свадьбы — помолвка состоялась, зовут на свадьбу, просят быть свидетелем. */
    private void weddingWords(MobGameBridge game, Request request, ConversationEngine.Reply reply, io.github.mobtalk.core.GestureIntent gesture) {
        if (!config.weddingCeremony) return;
        if (gesture != null && gesture.gesture() == io.github.mobtalk.core.Gesture.PROPOSE && gesture.outcome() == io.github.mobtalk.core.Gesture.Outcome.ACCEPTED)
            chat("§d[Mob Talk] Вы помолвлены с " + reply.speakerName() + ". Свадьбу играют у колокола днём: скажите «идём на свадьбу». Свидетелей зовите словами «будь моим свидетелем».");
        String said = reply.recognizedText();
        if (io.github.mobtalk.core.Wedding.calls(said))
            game.startWedding(request.target, request.player).thenAccept(message -> client.execute(() -> { if (!message.isBlank()) notice(message); }));
        if (io.github.mobtalk.core.Wedding.asksWitness(said))
            game.appointWitness(request.target, request.player).thenAccept(message -> client.execute(() -> { if (!message.isBlank()) notice(message); }));
    }

    /** The announced move, now that the voice is over; the mob's state may have changed meanwhile, so the game checks it again. */
    private void runDeferredAction() {
        Deferred deferred = deferredAction;
        deferredAction = null;
        if (deferred == null || generation != deferred.request.generation || client.getServer() == null) return;
        MobGameBridge game = MobGameBridge.active(deferred.request.server);
        if (game == null) return;
        game.apply(deferred.request.target, deferred.request.player, deferred.request.dimension, deferred.action, deferred.request.snapshot, -1,
                () -> generation == deferred.request.generation).whenComplete((message, error) -> client.execute(() -> {
            if (error == null && message != null && !message.isBlank() && generation == deferred.request.generation) chat("[Mob Talk] " + message);
        }));
    }

    /** A reply that no longer applies: a bystander that walked off leaves the HUD on the ready line, not on "прислушивается". */
    private void dropped(Request request, ConversationEngine.Reply reply) {
        reply.discardSpeech();
        if (request.overheard != null) initiatives.ended(request.target);
        if (request.overheard != null && generation == request.generation) { status = readyStatus(); showHud(); }
    }

    private void finishDelivery(Request request, ConversationEngine.Reply reply, String actionMessage) {
        requestActive = false;
        if (request.overheard == null) { conversationMob = request.target; lastReplyMillis = System.currentTimeMillis(); }
        if (request.overheard == null) chat("Вы: " + reply.recognizedText());
        boolean spontaneous = request.overheard != null && request.overheard.spontaneous();
        if (spontaneous) initiatives.spoken(request.target, request.overheard, request.generation, reply);
        if (reply.silent()) { status = readyStatus(); showHud(); return; }
        chat(reply.speakerName() + ": " + reply.responseText()); repliesShown++;
        stopSpeech();
        if (reply.speech() != null) {
            speech = new MobSpeechSound(client, world, request.target, request.config.maxDistance, reply.speech());
            client.getSoundManager().play(speech); speechStarts++; speakingMob = request.target;
            status = request.overheard == null ? "Говорит…" : spontaneous ? reply.speakerName() + " заговаривает…" : reply.speakerName() + " вмешивается…";
        } else {
            status = request.overheard == null ? "Ответ в чате; озвучка недоступна. Повторите позже" : "Ответ в чате; озвучка недоступна";
        }
        if (actionMessage != null && !actionMessage.isBlank()) chat("[Mob Talk] " + actionMessage);
        if (request.overheard == null) {
            io.github.mobtalk.core.BargainMeter.Move move = io.github.mobtalk.core.BargainMeter.move(reply.bargain(), reply.deal(), request.snapshot, reply.recognizedText());
            if (move != null) { bargainMove = move; bargainUntil = ticks + BARGAIN_HUD_TICKS; }
        }
        queueWitnesses(request, reply);
        if (actionRequest != null) actionRequest.complete(actionMessage == null || actionMessage.isBlank()
                ? "Моб ответил без нового действия" : actionMessage);
        actionRequest = null;
        showHud();
    }

    private void updateStatus(Request request, String message) {
        client.execute(() -> {
            if (!isCurrent(request)) return;
            status = message;
            showHud();
        });
    }

    private void reportFailure(Request request, Throwable failure) {
        boolean vow = request.overheard == null && wedding.takesVow();
        if (vow) wedding.vowUnheard();
        if (!isCurrent(request)) return;
        if (actionRequest != null) actionRequest.completeExceptionally(failure);
        actionRequest = null;
        requestActive = false;
        LOGGER.warn("Mob Talk request failed ({})", failure.getClass().getSimpleName());
        if (request.overheard != null) { initiatives.ended(request.target); status = readyStatus(); showHud(); return; } // A passer-by that could not answer is no one's problem.
        String message = io.github.mobtalk.core.Failures.plain(failure);
        status = message.isBlank() ? "Разговор не удался: проверьте микрофон, Whisper и ключ OpenRouter" : message;
        if (status.length() > 240) status = status.substring(0, 240);
        notice(status);
        // The model is unreachable, not the mob: a short local line keeps the creature present instead of mute.
        MobGameBridge game = request.server == null ? null : MobGameBridge.active(request.server);
        if (failure instanceof java.io.IOException && game != null && client.player != null && !vow) {
            MobCard card = game.memory.card(request.target, client.player.getUuid());
            int calm = card == null || card.state() == null ? 50 : card.state().calm();
            boolean hostile = io.github.mobtalk.core.SpeciesCatalog.get(request.snapshot == null ? "" : request.snapshot.type()).hostile();
            chat(request.mobName + ": " + io.github.mobtalk.core.FallbackLines.line(hostile, calm, System.nanoTime() / 1_000_000L) + " §8(без связи)");
            game.attend(request.target, client.player.getUuid(), 60); // The gesture that goes with it: it turns to the player and waits.
        }
    }

    private static void checkCurrentInterrupt() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
    }

    private void checkCurrent(Request request) throws InterruptedException {
        checkCurrentInterrupt();
        if (generation != request.generation) throw new InterruptedException("Cancelled");
    }

    private boolean isCurrent(Request request) {
        MobEntity subject = request.overheard == null ? target() : MobSpeechSound.find(world, request.target);
        return !stopped && generation == request.generation && (request.overheard != null || request.target.equals(selected))
                && world != null && client.world == world && client.getServer() != null
                && client.player != null && client.player.isAlive() && withinRange(subject)
                && (request.merchant == null || request.merchant.equals(merchant.context()) && merchant.allowedRecordingScreen());
    }

    private MobEntity target() { return MobSpeechSound.find(world, selected); }

    boolean withinRange(MobEntity mob) {
        return mob != null && client.player != null && client.player.squaredDistanceTo(mob) <= config.maxDistance * config.maxDistance;
    }

    public CompletableFuture<MobSnapshot> selectedSnapshot() {
        ensureGameBridge();
        if (selected == null || client.player == null || world == null || gameReady == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Сначала наведите прицел на моба и нажмите V"));
        UUID mob = selected, player = client.player.getUuid();
        String dimension = world.getRegistryKey().getValue().toString();
        return gameReady.thenCompose(game -> game.capture(mob, player, dimension));
    }
    public CompletableFuture<MobCard> selectedCard() {
        CompletableFuture<MobSnapshot> snapshotFuture = selectedSnapshot();
        CompletableFuture<MobGameBridge> ready = gameReady;
        if (ready == null) return CompletableFuture.failedFuture(new IllegalStateException("Мир недоступен"));
        return snapshotFuture.thenCompose(snapshot -> ready.thenApply(game -> {
            game.memory.observe(snapshot); return game.memory.card(snapshot.id(), snapshot.playerId());
        }));
    }
    public CompletableFuture<String> requestMobAction(ActionIntent action) {
        if (action == null || action.type() == ActionType.NONE) return CompletableFuture.completedFuture("Выберите действие");
        if (action.type() == ActionType.STOP) {
            cancelWork("Действие отменено");
            if (selected == null || client.player == null || world == null || client.getServer() == null)
                return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
            MobGameBridge game = MobGameBridge.active(client.getServer());
            if (game == null) return CompletableFuture.failedFuture(new IllegalStateException("Мир недоступен"));
            return game.apply(selected, client.player.getUuid(), world.getRegistryKey().getValue().toString(), action, null, -1, () -> true);
        }
        if (!readyForRequest()) return CompletableFuture.failedFuture(new IllegalStateException(status));
        cancelWork("Моб обдумывает просьбу…");
        long requestGeneration = generation;
        CompletableFuture<String> result = new CompletableFuture<>();
        actionRequest = result;
        Request seed = request(requestGeneration, null);
        selectedSnapshot().whenComplete((snapshot, error) -> client.execute(() -> {
            if (!isCurrent(seed)) { result.completeExceptionally(new java.util.concurrent.CancellationException("Просьба отменена")); return; }
            if (error != null) { reportFailure(seed, error); return; }
            String target = "меня";
            if ((action.type() == ActionType.ATTACK || action.type() == ActionType.FLEE || action.type() == ActionType.SPARE) && !"player".equals(action.target())) {
                var observed = snapshot.observedEntities().stream().filter(entity -> entity.key().equals(action.target())
                        || entity.id().toString().equals(action.target())).findFirst().orElse(null);
                if (observed == null) { reportFailure(seed, new IllegalArgumentException("Цель не наблюдается мобом")); return; }
                target = observed.name() + " (цель " + observed.key() + ")";
            }
            String question = switch (action.type()) {
                case FOLLOW -> "Пожалуйста, следуй за мной и оставайся рядом, пока я не попрошу остановиться. Реши сам, согласен ли ты.";
                case FLEE -> "Пожалуйста, отойди подальше от " + ("меня".equals(target) ? "меня" : "моба " + target)
                        + ". Реши сам, хочешь ли ты уйти.";
                case ATTACK -> "Я прошу тебя атаковать " + target + ". Реши сам, согласен ли ты и позволяет ли это твоя природа.";
                case STAY -> "Пожалуйста, подожди меня здесь и никуда не уходи, пока я не вернусь. Реши сам, согласен ли ты.";
                case GUARD -> "Пожалуйста, охраняй меня: держись рядом и не подпускай врагов. Реши сам, согласен ли ты.";
                case SPARE -> "Пожалуйста, не нападай на " + target + ". Реши сам, согласен ли ты.";
                default -> "";
            };
            submit(new Request(seed.generation, seed.target, seed.worldDirectory, seed.config, snapshot,
                    seed.server, seed.player, seed.dimension, seed.mobName, seed.merchant, null), null, question);
        }));
        return result;
    }
    public void stopSelectedAction() {
        if (selected == null || client.getServer() == null || client.player == null || world == null) return;
        MobGameBridge game = MobGameBridge.active(client.getServer());
        if (game == null) return;
        cancelWork("Действие отменено");
        game.apply(selected, client.player.getUuid(), world.getRegistryKey().getValue().toString(),
                new ActionIntent(ActionType.STOP, "player"), null, -1, () -> true)
                .whenComplete((result, error) -> client.execute(() -> { if (error == null) notice(result); }));
    }
    public void stopAllFollowers() {
        if (client.getServer() == null) return;
        MobGameBridge game = MobGameBridge.active(client.getServer());
        if (game != null) game.stopAllFollowers().thenAccept(result -> client.execute(() -> notice(result)));
    }
    public CompletableFuture<String> editSelectedCard(String name, String personality, String voiceId, String memory) {
        UUID id = selected;
        if (id == null || gameReady == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
        cancelWork("Карточка изменена");
        return gameReady.thenApply(game -> { game.memory.updateCard(id, name, personality, voiceId, memory); return "Карточка сохранена"; });
    }
    public CompletableFuture<String> clearSelectedHistory() {
        UUID id = selected;
        if (id == null || gameReady == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
        cancelWork("История очищена");
        return gameReady.thenApply(game -> { game.memory.clearHistory(id); return "История очищена"; });
    }
    public CompletableFuture<String> resetSelectedPersonality() {
        UUID id = selected;
        if (id == null || gameReady == null) return CompletableFuture.failedFuture(new IllegalStateException("Моб не выбран"));
        cancelWork("Личность изменена");
        return gameReady.thenApply(game -> { game.memory.resetPersonality(id); return "Особенности личности обновлены; память и отношения сохранены"; });
    }

    private void showHud() {
        if (client.player == null) return;
        MobEntity mob = target();
        MobGameBridge game = mob == null || client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        MobCard card = game == null ? null : game.memory.card(selected, client.player.getUuid());
        // With a label over the creature (name · state · relationship) the line keeps only what the label lacks.
        boolean labelled = config.showNameTags && card != null && game.memory.acquainted(selected) && !game.memory.displayName(selected).isBlank();
        String targetLabel = mob == null ? "" : mob.getName().getString() + " · "
                + String.format(Locale.ROOT, "%.1f м · ", Math.sqrt(client.player.squaredDistanceTo(mob)))
                + (card == null || labelled ? "" : MobGameBridge.relationshipLabel(card) + " · ");
        MobEntity aimed = client.currentScreen == null ? aimedMob() : null, far = aimed == null && client.currentScreen == null ? aimedMob(48) : null;
        String aimLabel = aimed != null && aimed != mob ? "в прицеле: " + aimed.getName().getString() + " · "
                : far != null && far != mob ? "в прицеле: " + far.getName().getString() + " (далеко, подойдите) · " : "";
        net.minecraft.text.MutableText line = Text.literal("Mob Talk · " + aimLabel + targetLabel);
        // One word of how the mob feels, coloured by its sign; the words themselves live in the lang files.
        if (mob != null && card != null && config.showStateHud && !labelled) {
            io.github.mobtalk.core.MobState.Descriptor marker = card.state().marker();
            line.append(Text.translatable("mobtalk.state." + marker.key()).formatted(marker.sign() < 0 ? net.minecraft.util.Formatting.RED
                    : marker.sign() > 0 ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.GRAY)).append(Text.literal(" · "));
        }
        // How the haggle goes, recomputed against the drifting state: «торг: склоняется» in green, «против» in red.
        if (mob != null && card != null && config.showBargainHud && bargainMove != null && ticks < bargainUntil) {
            io.github.mobtalk.core.BargainMeter.Verdict verdict = io.github.mobtalk.core.BargainMeter.read(bargainMove, card.state(), card.relationship()).verdict();
            line.append(Text.literal("торг: ")).append(Text.translatable("mobtalk.bargain." + verdict.key).formatted(verdict.sign < 0 ? net.minecraft.util.Formatting.RED
                    : verdict.sign > 0 ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.YELLOW)).append(Text.literal(" · "));
        }
        client.player.sendMessage(line.append(Text.literal((game == null ? "" : favorLabel(game)) + status)), true);
    }

    /** /mobtalk state: every axis of the selected mob's state in words and its last thought, for the chat. */
    /** What Whisper should expect in this question: the mob's name, the known creatures around the player, the villages and the rest
     * of the known names; Whisper's prompt is short, so it is cut at 500 characters. */
    private String whisperContext(Request request) {
        MobGameBridge game = request.server == null ? null : MobGameBridge.active(request.server);
        if (game == null) return request.mobName == null ? "" : request.mobName;
        List<String> priority = new ArrayList<>();
        if (request.mobName != null) priority.add(request.mobName);
        if (client.player != null && client.world != null)
            for (MobEntity near : client.world.getEntitiesByClass(MobEntity.class, client.player.getBoundingBox().expand(32), Entity::isAlive)) {
                String name = game.memory.displayName(near.getUuid());
                if (!name.isBlank()) priority.add(name);
            }
        return game.memory.hotwords(priority, 500);
    }

    /** The gift key: one item from the hand to the chosen (or aimed) creature; it takes or declines and then says a word of it. */
    void giftFromHand() {
        if (stopped || reloading || client.player == null || world == null) return;
        // The present goes to the creature under the crosshair; the chosen one only when nothing is aimed at.
        MobEntity mob = aimedMob();
        if (mob == null) mob = target();
        if (mob == null) { notice("Наведите прицел на моба"); return; }
        if (client.player.getMainHandStack().isEmpty()) { notice("Возьмите подарок в руку"); return; }
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        if (game == null) { notice("Мир ещё не загружен"); return; }
        game.giftFromHand(mob.getUuid(), client.player.getUuid()).whenComplete((result, error) -> client.execute(() -> notice(error != null ? "Подарок не удался: " + error.getMessage() : result)));
    }

    /** /mobtalk village forgive: every resident of the village around the player forgets the grudges, and its rumours go. */
    void forgiveVillage() {
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        if (game == null || client.player == null) { notice("Мир ещё не загружен"); return; }
        UUID player = client.player.getUuid();
        String dimension = client.player.getWorld().getRegistryKey().getValue().toString();
        int x = client.player.getBlockX(), z = client.player.getBlockZ();
        game.onServer(() -> game.memory.forgiveVillage(dimension, x, z, player)).thenAccept(result -> client.execute(() -> notice(result)));
    }

    /** «Моб и деревня» over {@code parent}: the selected mob (or none) and the village where the player stands. */
    void openInfo(net.minecraft.client.gui.screen.Screen parent) {
        if (stopped) return;
        client.setScreen(new InfoScreen(parent, this::infoReport));
    }
    CompletableFuture<String> infoReport(boolean secrets) {
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        if (game == null || client.player == null || world == null) return CompletableFuture.failedFuture(new IllegalStateException("Мир ещё не загружен"));
        return game.info(selected, client.player.getUuid(), world.getRegistryKey().getValue().toString(), client.player.getBlockX(), client.player.getBlockZ(), secrets);
    }
    /** The player's standing in the village where they stand, for the F7 menu and /mobtalk state. */
    CompletableFuture<List<String>> villageStatus() {
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        if (game == null || client.player == null || world == null) return CompletableFuture.completedFuture(List.of("Мир ещё не загружен."));
        UUID player = client.player.getUuid();
        String dimension = world.getRegistryKey().getValue().toString();
        int x = client.player.getBlockX(), z = client.player.getBlockZ();
        return CompletableFuture.supplyAsync(() -> {
            ConversationStore.Village village = game.memory.villageNear(dimension, x, z);
            return io.github.mobtalk.core.MobInfo.standingLines(village == null ? null : game.memory.social().standingAt(dimension, x, z, player, System.currentTimeMillis()),
                    village == null ? "" : village.name());
        }, maintenance);
    }

    String stateLine() {
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        MobCard card = game == null || selected == null || client.player == null ? null : game.memory.card(selected, client.player.getUuid());
        if (card == null) return "Состояние: собеседник не выбран или ещё незнаком";
        io.github.mobtalk.core.MobState.View state = card.state();
        StringBuilder text = new StringBuilder("Состояние ").append(card.name().isBlank() ? card.type() : card.name()).append(": ");
        List<String> words = new java.util.ArrayList<>();
        for (io.github.mobtalk.core.MobState.Descriptor word : state.words()) words.add(Text.translatable("mobtalk.state." + word.key()).getString());
        text.append(words.isEmpty() ? "ровно" : String.join(", ", words))
                .append(" (настроение ").append(state.mood()).append(", спокойствие ").append(state.calm())
                .append(", интерес ").append(state.interest()).append(", доверие ").append(state.trust()).append(')');
        if (!state.thought().isEmpty()) text.append(" · мысль: «").append(state.thought()).append('»');
        long now = client.world == null ? 0 : client.world.getTime();
        for (io.github.mobtalk.core.FriendActions.Status active : game.friendStatus(selected))
            text.append(" · ").append(Text.translatable("mobtalk.friend.status", Text.translatable("mobtalk.action." + active.type().name().toLowerCase(java.util.Locale.ROOT)),
                    active.type() == ActionType.SPARE ? active.target() : Text.translatable("mobtalk.friend." + active.target()),
                    Math.max(1, (active.expires() - now + 1199) / 1200)).getString());
        List<io.github.mobtalk.core.MobRelations.View> bonds = game.memory.bonds(selected, System.currentTimeMillis());
        if (!bonds.isEmpty()) text.append(" · свои: ").append(String.join(", ", bonds.stream().limit(6).map(io.github.mobtalk.core.MobRelations.View::label).toList()));
        List<io.github.mobtalk.core.VillageRumors.View> rumors = game.memory.rumorsOf(selected, System.currentTimeMillis());
        if (!rumors.isEmpty()) text.append(" · слухи: ").append(String.join("; ", rumors.stream().limit(3).map(io.github.mobtalk.core.VillageRumors.View::label).toList()));
        String social = game.memory.social().describe(selected, client.player.getUuid());
        if (!social.isBlank()) text.append(" · нрав: ").append(social);
        if (client.player != null && world != null) {
            String dimension = world.getRegistryKey().getValue().toString();
            ConversationStore.Village village = game.memory.villageNear(dimension, client.player.getBlockX(), client.player.getBlockZ());
            if (village != null) text.append(" · ").append(io.github.mobtalk.core.MobInfo.standingLine(game.memory.social().standingAt(dimension,
                    client.player.getBlockX(), client.player.getBlockZ(), client.player.getUuid(), System.currentTimeMillis()), village.name()));
        }
        text.append(" · решатель: ").append(decider.name());
        return text.toString();
    }

    /** «услуга: Колокол площади 0/1 · » while this species waits for something from the player; counts the player's own inventory. */
    private String favorLabel(MobGameBridge game) {
        io.github.mobtalk.core.Favor favor = selected == null || client.player == null ? null : game.memory.favor(selected, client.player.getUuid());
        if (favor == null) return "";
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(favor.item());
        net.minecraft.item.Item wanted = id == null || !net.minecraft.registry.Registries.ITEM.containsId(id) ? null : net.minecraft.registry.Registries.ITEM.get(id);
        int have = 0;
        // The same slots the server takes from on delivery: main inventory and offhand, not worn armour.
        if (wanted != null) {
            for (var stack : client.player.getInventory().main) if (stack.isOf(wanted)) have += stack.getCount();
            for (var stack : client.player.getInventory().offHand) if (stack.isOf(wanted)) have += stack.getCount();
        }
        return "услуга: " + favor.title() + " " + Math.min(have, favor.count()) + "/" + favor.count() + " · ";
    }

    void stopSpeech() {
        if (speech != null && client.getSoundManager().isPlaying(speech)) speechCutoffs++;
        if (speech != null) speech.stop();
        speech = null;
    }

    /** A line of village small talk: into the chat, and voiced at the speaker when there is audio. Only while nobody speaks. */
    void playOverheard(UUID mob, String line, io.github.mobtalk.core.SpeechStream audio, double range) {
        chat(line);
        playVoice(mob, audio, range);
    }
    /** A voice at the speaker without a chat line (the wedding prints the whole line once and voices it phrase by phrase). */
    void playVoice(UUID mob, io.github.mobtalk.core.SpeechStream audio, double range) {
        stopSpeech();
        if (audio == null || world == null) return;
        speech = new MobSpeechSound(client, world, mob, Math.max(range, config.maxDistance), audio);
        client.getSoundManager().play(speech); speechStarts++; speakingMob = mob;
    }

    /** The engine for background village talk, created on the calling worker the first time (it loads the key), like for a first question. */
    java.util.function.Supplier<ConversationEngine> engineSupplier() {
        if (client.getServer() == null || client.player == null || world == null) return () -> null;
        Request seed = request(generation, null);
        return () -> {
            try { return ensureEngine(seed, false); }
            catch (Exception unavailable) { LOGGER.info("Mob Talk: no engine for village talk ({})", unavailable.getClass().getSimpleName()); return null; }
        };
    }

    void chat(String text) {
        client.inGameHud.getChatHud().addMessage(Text.literal(text));
    }

    private String lastNotice = ""; private long lastNoticeMillis;
    void notice(String message) {
        long now = System.currentTimeMillis();
        if (!message.equals(lastNotice) || now - lastNoticeMillis > 15_000) chat("[Mob Talk] " + message);
        lastNotice = message; lastNoticeMillis = now;
        showHud();
    }

    /** The mob's own deals in the vanilla merchant window, opened by the server; a running conversation keeps going. */
    void openTrade() {
        if (stopped || reloading || client.currentScreen != null) return;
        updateWorld();
        ensureGameBridge();
        if (!configReady) { notice("Настройки ещё загружаются"); return; }
        if (!config.enabled) { notice("Mob Talk выключен в config/mobtalk.json"); return; }
        MobEntity mob = target();
        if (mob == null || !withinRange(mob) || client.player == null || world == null) { notice("Сначала наведите прицел на моба и нажмите V"); return; }
        if (recording != null) { notice("Сначала отпустите клавишу речи"); return; }
        CompletableFuture<MobGameBridge> ready = gameReady;
        if (ready == null) { notice("Память мира ещё загружается"); return; }
        UUID mobId = selected, player = client.player.getUuid();
        String dimension = world.getRegistryKey().getValue().toString();
        merchant.ownTradeRequested = mobId;
        ready.thenCompose(game -> game.openTrade(mobId, player, dimension)).whenComplete((ignored, error) -> client.execute(() -> {
            if (error == null) return;
            merchant.ownTradeRequested = null;
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            String message = MobTalkSettingsScreen.cleanMessage(cause.getMessage());
            notice(message.isBlank() ? "Не удалось открыть сделки: моб недоступен" : message);
        }));
    }

    private void openSettings(Screen parent) {
        if (stopped || reloading || parent instanceof MobTalkSettingsScreen) return;
        cancelWork("Настройки Mob Talk", true);
        client.setScreen(new MobTalkSettingsScreen(parent, config.copy(), new SettingsController(this)));
    }

    /** Writes the draft, reloads it as the live config and restarts the Whisper helper; completion gets null or a message. */
    void saveConfig(MobTalkConfig draft, java.util.function.Consumer<String> completion) {
        if (reloading || stopped) {
            completion.accept("Настройки ещё сохраняются. Повторите через секунду.");
            return;
        }
        reloading = true;
        cancelWork("Сохранение настроек…", true);
        MobTalkConfig snapshot = draft.copy();
        long queued = System.nanoTime();
        maintenance.execute(() -> {
            try {
                long started = System.nanoTime();
                MobTalkConfig.save(configDirectory.resolve("mobtalk.json"), snapshot);
                MobTalkConfig loaded = MobTalkConfig.load(configDirectory.resolve("mobtalk.json"));
                long saved = System.nanoTime();
                helper.stopOwned();
                long stoppedAt = System.nanoTime();
                if (stoppedAt - queued > 2_000_000_000L) LOGGER.info("Mob Talk settings save: waited in queue {} ms, wrote {} ms, stopped Whisper {} ms",
                        (started - queued) / 1_000_000, (saved - started) / 1_000_000, (stoppedAt - saved) / 1_000_000);
                client.execute(() -> {
                    reloading = false;
                    if (stopped) return;
                    config = loaded; io.github.mobtalk.core.Tuning.apply(loaded);
                    configReady = true;
                    status = !loaded.enabled ? "Mob Talk выключен" : selected == null ? AIM_HINT : readyStatus();
                    completion.accept(null);
                });
            } catch (Exception failure) {
                LOGGER.warn("Mob Talk settings save failed ({})", failure.getClass().getSimpleName());
                client.execute(() -> {
                    reloading = false;
                    if (!stopped) completion.accept(failure.getMessage() == null
                            ? "Не удалось сохранить настройки. Повторите попытку." : failure.getMessage());
                });
            }
        });
    }

    void reload() {
        if (reloading || stopped) return;
        reloading = true;
        cancelWork("Загрузка настроек…", true);
        maintenance.execute(() -> {
            helper.stopOwned();
            try {
                MobTalkConfig loaded = MobTalkConfig.load(configDirectory.resolve("mobtalk.json"));
                client.execute(() -> {
                    if (stopped) return;
                    config = loaded; io.github.mobtalk.core.Tuning.apply(loaded);
                    configReady = true;
                    reloading = false;
                    status = selected == null ? AIM_HINT : readyStatus();
                    if (world != null) notice("Настройки загружены");
                });
            } catch (Exception failure) {
                LOGGER.warn("Mob Talk config failed ({})", failure.getClass().getSimpleName());
                client.execute(() -> {
                    reloading = false;
                    configReady = false;
                    status = "Не удалось прочитать config/mobtalk.json: исправьте JSON и /mobtalk reload";
                    if (world != null) notice(status);
                });
            }
        });
    }

    private void shutdown() {
        if (stopped) return;
        cancelWork("Остановлено", true);
        stopped = true;
        microphone.close();
        pipeline.shutdownNow(); witnesses.shutdownNow();
        Path directory = memoryWorld;
        CompletableFuture<Void> detached = gameServer == null ? CompletableFuture.completedFuture(null) : MobGameBridge.detach(gameServer);
        if (directory != null) maintenance.execute(() -> {
            try { detached.get(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
            try { ConversationStore.closeWorld(directory); } catch (Exception ignored) { }
        });
        maintenance.execute(helper::stopOwned);
        maintenance.shutdown();
    }

    /** overheard non-null: the mob is a bystander answering the exchange it heard, not the conversation partner. */
    private record Request(long generation, UUID target, Path worldDirectory, MobTalkConfig config, MobSnapshot snapshot,
            MinecraftServer server, UUID player, String dimension, String mobName, MerchantSession.Context merchant,
            ConversationEngine.Overheard overheard) { }
}
