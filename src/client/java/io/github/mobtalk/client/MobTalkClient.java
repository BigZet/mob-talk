package io.github.mobtalk.client;

import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.WhisperClient;
import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.OpenRouterConnection;
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
    KeyBinding talkKey;
    private KeyBinding settingsKey;
    KeyBinding tradeKey;
    private boolean openSettingsRequested;
    private MicrophoneRecorder.Capture recording;
    private boolean configReady;
    boolean reloading;
    private boolean stopped;
    boolean keyHeld;
    private boolean requestActive;
    private final MerchantSession merchant = new MerchantSession(this);
    private CompletableFuture<String> actionRequest;
    /** Bystanders still to answer after the mob spoken to, in order; emptied by any new request. */
    private final Deque<UUID> witnessQueue = new ArrayDeque<>();
    private ConversationEngine.Overheard overheard;
    private long witnessGeneration = -1;
    /** Replies handed to the client thread whose server application is still in flight: the next bystander waits for them. */
    private int pendingDeliveries;
    /** The request in the pipeline is a bystander's: a new V cancels it instead of waiting. */
    private boolean witnessActive;
    /** V was pressed while the pipeline was still busy: recording starts the moment it frees up, as long as V stays down. */
    private boolean armRecording;
    /** The mob whose life story the selection asked for; the prefetch stops only when the selection moves on. */
    private volatile UUID prefetchTarget;
    private long ticks;
    String status = "Загрузка настроек…";
    private MobSpeechSound speech;

    @Override public void onInitializeClient() {
        MobGameBridge.initialize();
        ConversationEngine.setDiagnostics(line -> LOGGER.info("Mob Talk: {}", line));
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.talk", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V, "category.mobtalk"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.settings", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7, "category.mobtalk"));
        tradeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.trade", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B, "category.mobtalk"));
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Mob Talk"), button -> openSettings(screen))
                        .dimensions(width - 128, height - 26, 120, 20).build());
            }
            if (screen instanceof MerchantScreen trading) merchant.initialize(trading);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> leaveWorld());
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> shutdown());
        MobTalkCommands.register(this);
        SelfTest.install(this);
        reload();
    }

    /** Settings are on and loaded. */
    boolean enabled() { return configReady && config.enabled; }
    /** A recording, a request or a spoken reply is in flight. */
    boolean busyTalking() { return recording != null || requestActive || speech != null; }
    boolean speaking() { return speech != null; }
    /** Bystanders still waiting for their turn, or one being applied right now. */
    boolean witnessPending() { return !witnessQueue.isEmpty() || pendingDeliveries > 0 || pipelineBusy.get(); }
    void requestSettings() { openSettingsRequested = true; }
    /** /mobtalk say: the typed line goes through the same pipeline as speech. */
    boolean say(String text) {
        if (!readyForRequest()) { notice(status); return false; }
        stopSpeech();
        long requestGeneration = ++generation;
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
    /** How often a bystander is asked about an exchange with nothing in it. */
    static final double SMALL_TALK_CHANCE = 0.35;

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
        MobEntity aimed = aimedMob();
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
        while (tradeKey.wasPressed()) openTrade();
        boolean held = merchant.windowOpen() ? merchant.keyHeld() : talkKey.isPressed();
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
        if (recording != null && (!merchant.allowedRecordingScreen() || !client.isWindowFocused()
                || world == null || client.player == null || !client.player.isAlive())) {
            cancelWork("Запись отменена");
        }
        if (held && !keyHeld && !merchant.windowOpen()) aimBeforeTalking();
        if (held && !keyHeld && canRecord()) {
            if (recording == null && pipelineBusy.get()) {
                // A bystander in the middle of its line is cut off by the player's V; the player's own request is waited out.
                if (witnessActive) cancelWork(readyStatus());
                armRecording = true;
            } else beginRecording();
        }
        if (armRecording && held && recording == null && !pipelineBusy.get() && canRecord()) { armRecording = false; beginRecording(); }
        if (!held) armRecording = false;
        if (!witnessQueue.isEmpty()) advanceWitnesses();
        if (!held && keyHeld && recording != null) {
            status = "Завершение записи…";
            recording.stop();
        }
        keyHeld = held;
        if (speech != null && !client.getSoundManager().isPlaying(speech)) {
            speech = null;
            if (!requestActive && recording == null) status = readyStatus();
        }
        if (selected != null && ticks % 20 == 0) showHud();
    }

    private boolean canRecord() {
        return enabled() && !reloading && merchant.allowedRecordingScreen()
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
        selected = null; prefetchTarget = null;
        world = null;
        if (client.getServer() == null) releaseMemory();
        if (!stopped) helperCleanup = CompletableFuture.runAsync(helper::stopOwned, maintenance);
    }

    void select(MobEntity mob) {
        if (!mob.getUuid().equals(selected)) {
            cancelWork(readyStatus());
            selected = mob.getUuid();
            prefetchTarget = selected;
            keepConnectionWarm();
            prefetchBackstory();
        }
        // Selecting the same UUID keeps its existing engine/profile/history.
        showHud();
    }

    /** A mob met for the first time gets its life story (and its village's) written while the player is still raising the key;
     * the conversation worker runs it so a request that follows simply queues behind. Failures are silent: the story can come later. */
    private void prefetchBackstory() {
        if (stopped || !configReady || !config.enabled || selected == null || client.player == null || world == null || gameReady == null) return;
        ensureGameBridge();
        Request seed = request(generation, null);
        pipeline.execute(() -> {
            try {
                // V right after aiming bumps the generation at once; only moving the selection on makes this story pointless.
                if (!seed.target.equals(prefetchTarget)) return;
                MobGameBridge game = gameReady.get(10, TimeUnit.SECONDS);
                if (game.memory.hasBackstory(seed.target)) return;
                MobSnapshot snapshot = game.capture(seed.target, seed.player, seed.dimension).get(10, TimeUnit.SECONDS);
                if (!seed.target.equals(prefetchTarget)) return;
                ensureEngine(seed, false).ensureBackstory(snapshot);
                client.execute(() -> { if (seed.target.equals(selected)) showHud(); });
            } catch (Exception failure) {
                LOGGER.warn("Mob Talk backstory not written yet: {}", String.valueOf(failure.getMessage()));
            } finally { Thread.interrupted(); }
        });
    }

    /** Opens (or keeps open) the OpenRouter connection while a mob is selected, so the first reply skips the handshake. */
    private void keepConnectionWarm() {
        if (stopped || !configReady || !config.enabled || selected == null) return;
        maintenance.execute(() -> OpenRouterConnection.keepWarm(configDirectory, config.needsVenice(), config.needsOpenRouter()));
    }

    void clearSelection(String reason) {
        cancelWork(reason);
        selected = null; prefetchTarget = null;
        showHud();
    }

    void cancelWork(String reason) { cancelWork(reason, false); }

    /** Interrupting the worker cancels in-flight requests; the engine itself is kept across mob switches and trade windows,
     * since rebuilding it reloads the key and relearns provider quirks. Settings, reloads and world changes drop it. */
    private void cancelWork(String reason, boolean dropEngine) {
        generation++;
        witnessQueue.clear(); overheard = null; witnessActive = false; armRecording = false;
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
            if (oldWhisper != null) oldWhisper.close();
            if (oldEngine != null) oldEngine.close();
        });
        status = reason;
    }

    private void beginRecording() {
        if (!readyForRequest()) return;
        stopSpeech();
        long requestGeneration = ++generation;
        Request seed = request(requestGeneration, null);
        try {
            MicrophoneRecorder.Capture capture = microphone.start(config.microphoneName, config.maxRecordingSeconds);
            recording = capture;
            merchant.setWork(seed.merchant);
            status = "Запись… отпустите " + talkKey.getBoundKeyLocalizedText().getString() + " (до 15 сек.)";
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
        if (!merchant.allowedRecordingScreen() || !client.isWindowFocused()) {
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

    /** After the mob spoken to answered: who else heard it, so they can add a line one after another. */
    private void queueWitnesses(Request request, ConversationEngine.Reply reply) {
        if (request.overheard != null) return; // The queue is the partner's; a bystander finishing keeps the next one waiting.
        witnessQueue.clear(); overheard = null;
        if (config.witnessReplies <= 0 || reply.silent()) return;
        MobGameBridge game = MobGameBridge.active(request.server);
        if (game == null) return;
        ConversationEngine.Overheard heard = new ConversationEngine.Overheard(reply.speakerName(), reply.recognizedText(), reply.responseText());
        // An insult, a threat, an apology or a mob that runs or attacks is worth a bystander's voice every time; small talk only sometimes.
        boolean eventful = reply.socialIntent() != io.github.mobtalk.core.SocialIntent.NONE && reply.socialIntent() != io.github.mobtalk.core.SocialIntent.KIND
                || reply.action().type() == ActionType.ATTACK || reply.action().type() == ActionType.FLEE;
        game.witnesses(request.target, request.player, request.dimension, config.witnessReplies).whenComplete((ids, error) -> client.execute(() -> {
            if (error != null || ids.isEmpty() || generation != request.generation) return;
            java.util.Random chance = new java.util.Random();
            for (UUID id : ids) if (eventful || chance.nextDouble() < SMALL_TALK_CHANCE) witnessQueue.add(id);
            if (!witnessQueue.isEmpty()) { overheard = heard; witnessGeneration = request.generation; }
        }));
    }

    /** One bystander at a time, and only once the previous voice has gone quiet. */
    private void advanceWitnesses() {
        if (generation != witnessGeneration) { witnessQueue.clear(); overheard = null; return; }
        if (speech != null || requestActive || recording != null || pipelineBusy.get() || pendingDeliveries > 0 || overheard == null) return;
        UUID next = witnessQueue.poll();
        MobEntity mob = MobSpeechSound.find(world, next);
        if (mob == null || !withinRange(mob) || client.player == null || client.getServer() == null) return;
        Request seed = new Request(generation, next, client.getServer().getSavePath(WorldSavePath.ROOT), config, null, client.getServer(),
                client.player.getUuid(), world.getRegistryKey().getValue().toString(), mob.getName().getString(), null, overheard);
        submit(seed, null, "");
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
        witnessActive = request.overheard != null;
        merchant.setWork(request.merchant);
        status = request.overheard != null ? request.mobName + " прислушивается…" : wav == null ? "Моб думает…" : "Whisper распознаёт…";
        showHud();
        pipeline.execute(() -> {
            pipelineThread = Thread.currentThread();
            try {
                checkCurrent(request);
                String question = text;
                if (wav != null) {
                    updateStatus(request, "Whisper запускается и распознаёт…");
                    helperCleanup.get(10, TimeUnit.SECONDS);
                    checkCurrent(request);
                    helper.ensureAvailable(request.config, () -> generation != request.generation, message -> updateStatus(request, message));
                    checkCurrent(request);
                    try (WhisperClient localWhisper = new WhisperClient(request.config)) {
                        whisper = localWhisper;
                        checkCurrent(request);
                        question = localWhisper.transcribe(wav, request.mobName);
                    } finally { whisper = null; }
                    checkCurrent(request);
                    if (question == null || question.isBlank()) {
                        updateStatus(request, "Речь не распознана: удерживайте голосовую клавишу и произнесите вопрос");
                        return;
                    }
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
                if (request.overheard == null && !game.memory.hasBackstory(request.target)) {
                    // The prefetch on selection may have lost the race; a first reply without a life story would sound generic.
                    // A bystander gets its story when the player turns to it: one extra request per passer-by would be too slow.
                    updateStatus(request, "Моб вспоминает, кто он…");
                    try { localEngine.ensureBackstory(snapshot); }
                    catch (java.io.IOException noStory) { LOGGER.warn("Mob Talk backstory not written yet: {}", String.valueOf(noStory.getMessage())); }
                    checkCurrent(request);
                    updateStatus(request, "Моб думает и готовит голос…");
                }
                ConversationEngine.PreparedReply prepared = localEngine.prepare(snapshot, question, request.overheard);
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
                    if (generation == request.generation) { requestActive = false; witnessActive = false; }
                });
            }
        });
    }

    private ConversationEngine ensureEngine(Request request) throws Exception { return ensureEngine(request, true); }
    /** {@code bound}: the engine is for this request's generation; a prefetch is bound to the selection instead. */
    private ConversationEngine ensureEngine(Request request, boolean bound) throws Exception {
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
        return created;
    }

    /** Entered with pendingDeliveries already counted; every way out of the delivery, synchronous or not, releases it. */
    private void deliver(Request request, ConversationEngine localEngine, ConversationEngine.PreparedReply prepared) {
        boolean async = false;
        try {
            ConversationEngine.Reply reply = prepared.reply();
            if (!isCurrent(request)) { dropped(request, reply); return; }
            MobGameBridge game = MobGameBridge.active(request.server);
            if (game == null || game.memory.revision(request.target) != reply.interactionRevision()) {
                reply.discardSpeech();
                status = "Отношение моба изменилось: задайте вопрос ещё раз";
                if (actionRequest != null) actionRequest.completeExceptionally(new java.util.concurrent.CancellationException(status));
                actionRequest = null; showHud(); return;
            }
            CompletableFuture<UUID> merchant = request.merchant == null ? CompletableFuture.completedFuture(request.target)
                    : game.selectMerchant(request.player, request.merchant.syncId());
            async = true;
            merchant.thenCompose(id -> {
                if (!request.target.equals(id)) throw new java.util.concurrent.CancellationException("Торговец сменился");
                return game.applyWithRevision(request.target, request.player, request.dimension, reply.action(), reply.bargain(),
                        reply.deal(), reply.unlock(), reply.quest(), reply.socialIntent(), request.snapshot, reply.interactionRevision(), () -> generation == request.generation);
            }).whenComplete((delivery, error) -> client.execute(() -> {
                try {
                    if (!isCurrent(request)) { dropped(request, reply); return; }
                    if (error != null) { reply.discardSpeech(); reportFailure(request, error); return; }
                    if (!localEngine.commitDelivered(prepared, delivery.revision())) {
                        reply.discardSpeech();
                        status = "Отношение моба изменилось: ответ отменён";
                        if (actionRequest != null) actionRequest.completeExceptionally(new java.util.concurrent.CancellationException(status));
                        actionRequest = null; showHud(); return;
                    }
                    finishDelivery(request, reply, delivery.message());
                } finally { pendingDeliveries--; }
            }));
        } finally { if (!async) pendingDeliveries--; }
    }

    /** A reply that no longer applies: a bystander that walked off leaves the HUD on the ready line, not on "прислушивается". */
    private void dropped(Request request, ConversationEngine.Reply reply) {
        reply.discardSpeech();
        if (request.overheard != null && generation == request.generation) { status = readyStatus(); showHud(); }
    }

    private void finishDelivery(Request request, ConversationEngine.Reply reply, String actionMessage) {
        requestActive = false;
        if (request.overheard == null) chat("Вы: " + reply.recognizedText());
        if (reply.silent()) { status = readyStatus(); showHud(); return; }
        chat(reply.speakerName() + ": " + reply.responseText());
        stopSpeech();
        if (reply.speech() != null) {
            speech = new MobSpeechSound(client, world, request.target, request.config.maxDistance, reply.speech());
            client.getSoundManager().play(speech);
            status = request.overheard == null ? "Говорит…" : reply.speakerName() + " вмешивается…";
        } else {
            status = request.overheard == null ? "Ответ в чате; озвучка недоступна. Повторите позже" : "Ответ в чате; озвучка недоступна";
        }
        if (actionMessage != null && !actionMessage.isBlank()) chat("[Mob Talk] " + actionMessage);
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
        if (!isCurrent(request)) return;
        if (actionRequest != null) actionRequest.completeExceptionally(failure);
        actionRequest = null;
        requestActive = false;
        LOGGER.warn("Mob Talk request failed ({})", failure.getClass().getSimpleName());
        if (request.overheard != null) { status = readyStatus(); showHud(); return; } // A passer-by that could not answer is no one's problem.
        String message = failure.getMessage();
        status = message == null || message.isBlank() ? "Разговор не удался: проверьте микрофон, Whisper и ключ OpenRouter"
                : message.replace('\n', ' ').replace('\r', ' ');
        if (status.length() > 240) status = status.substring(0, 240);
        notice(status);
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

    private boolean withinRange(MobEntity mob) {
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
            if ((action.type() == ActionType.ATTACK || action.type() == ActionType.FLEE) && !"player".equals(action.target())) {
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
        String targetLabel = mob == null ? "" : mob.getName().getString() + " · "
                + String.format(Locale.ROOT, "%.1f м · ", Math.sqrt(client.player.squaredDistanceTo(mob)))
                + (card == null ? "" : MobGameBridge.relationshipLabel(card) + " · ")
                + (game == null ? "" : favorLabel(game));
        MobEntity aimed = client.currentScreen == null ? aimedMob() : null, far = aimed == null && client.currentScreen == null ? aimedMob(48) : null;
        String aimLabel = aimed != null && aimed != mob ? "в прицеле: " + aimed.getName().getString() + " · "
                : far != null && far != mob ? "в прицеле: " + far.getName().getString() + " (далеко, подойдите) · " : "";
        client.player.sendMessage(Text.literal("Mob Talk · " + aimLabel + targetLabel + status), true);
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
        if (speech != null) speech.stop();
        speech = null;
    }

    void chat(String text) {
        client.inGameHud.getChatHud().addMessage(Text.literal(text));
    }

    void notice(String message) {
        chat("[Mob Talk] " + message);
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
        maintenance.execute(() -> {
            try {
                MobTalkConfig.save(configDirectory.resolve("mobtalk.json"), snapshot);
                MobTalkConfig loaded = MobTalkConfig.load(configDirectory.resolve("mobtalk.json"));
                helper.stopOwned();
                client.execute(() -> {
                    reloading = false;
                    if (stopped) return;
                    config = loaded;
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
                    config = loaded;
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
        pipeline.shutdownNow();
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
