package io.github.mobtalk.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.mobtalk.core.ObsWebSocket;
import io.github.mobtalk.core.OpenRouterToken;
import io.github.mobtalk.net.MomentKeyC2S;
import io.github.mobtalk.net.MomentReplayC2S;
import io.github.mobtalk.net.MomentS2C;
import io.github.mobtalk.net.RecStateC2S;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Метки для монтажа (идея З4) на стороне игрока: клавиша «Отметить момент», строка подтверждения, и — если включено в F7 —
 * связь с OBS: сервер узнаёт, когда идут эфир и запись (таймкоды от их начала), момент становится главой записи, сильный
 * момент сохраняет буфер повтора, и путь файла уходит в админку. Всё на потоке клиента, кроме ответов OBS (переносятся сюда). */
public final class MomentClient {
    private MomentClient() { }
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-moments");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Личные настройки меток: config/mobtalk-obs.json (пароль OBS — отдельно, как ключи). */
    public static final class Settings {
        public boolean obsEnabled = false;
        public String obsAddress = "127.0.0.1:4455";
        public boolean obsChapters = true;
        public boolean obsReplay = false;
        public int replayMinWeight = 3;
        public int replayDelaySeconds = 10;
        /** mine — главы только своих моментов и мира; all — всех моментов сервера (оператор, который пишет всех). */
        public String scope = "mine";
        /** line — строка над хотбаром, sound — строка и тихий звук, none — ничего. */
        public String notice = "line";

        Settings normalized() {
            replayMinWeight = Math.max(1, Math.min(3, replayMinWeight));
            replayDelaySeconds = Math.max(0, Math.min(60, replayDelaySeconds));
            if (!"all".equals(scope)) scope = "mine";
            if (!"sound".equals(notice) && !"none".equals(notice)) notice = "line";
            if (obsAddress == null || obsAddress.isBlank()) obsAddress = "127.0.0.1:4455";
            obsAddress = obsAddress.strip();
            return this;
        }
        public Settings copy() { return GSON.fromJson(GSON.toJson(this), Settings.class); }
    }

    private static Path configDirectory;
    private static Settings settings = new Settings();
    private static KeyBinding key;
    private static volatile ObsWebSocket obs;
    private static boolean connecting;
    private static long nextConnect, nextReport;
    private static volatile String status = "";
    private static volatile boolean statusOk;
    /** Последнее известное состояние выходов OBS: active и момент, с которого идёт (по часам клиента). */
    private static final Map<String, Long> since = new ConcurrentHashMap<>();
    private static final Deque<Runnable> fromObs = new ArrayDeque<>();
    /** Моменты, ждущие сохранения повтора: id и когда сохранять; после сохранения — тот, чей путь ждём. */
    private static final Deque<Object[]> replays = new ArrayDeque<>();
    private static String awaitingReplay;

    static void register(Path config) {
        configDirectory = config;
        settings = load();
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mobtalk.moment", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), "category.mobtalk"));
        ClientPlayNetworking.registerGlobalReceiver(MomentS2C.ID, (payload, context) -> onMoment(context.client(), payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { nextReport = 0; replays.clear(); awaitingReplay = null; });
        ClientTickEvents.END_CLIENT_TICK.register(MomentClient::tick);
    }

    static KeyBinding key() { return key; }
    public static Settings settings() { return settings.copy(); }
    /** Текущее состояние связи с OBS для F7. */
    public static String obsStatus() {
        if (!settings.obsEnabled) return "OBS не подключается: включите ниже.";
        return status.isEmpty() ? "Подключаюсь к OBS…" : status;
    }
    public static boolean obsOk() { return statusOk && obs != null && obs.ready(); }

    /** Сохранить из F7; связь переподключится с новым адресом и паролем. */
    public static void save(Settings changed) {
        settings = changed.copy().normalized();
        try {
            Files.createDirectories(configDirectory);
            Files.writeString(configDirectory.resolve("mobtalk-obs.json"), GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException failure) { LOGGER.warn("Mob Talk: не сохранил mobtalk-obs.json: {}", failure.toString()); }
        reconnect();
    }
    /** После смены пароля в F7. */
    public static void reconnect() { disconnect(); nextConnect = 0; }

    private static Settings load() {
        Path file = configDirectory.resolve("mobtalk-obs.json");
        try {
            if (Files.isRegularFile(file)) {
                Settings read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
                if (read != null) return read.normalized();
            }
        } catch (IOException | RuntimeException broken) { LOGGER.warn("Mob Talk: mobtalk-obs.json не прочитан, беру умолчания: {}", broken.toString()); }
        return new Settings();
    }

    // ---- тик ----

    private static void tick(MinecraftClient client) {
        while (key != null && key.wasPressed()) mark(client);
        Runnable task;
        synchronized (fromObs) { while ((task = fromObs.poll()) != null) task.run(); }
        long now = System.currentTimeMillis();
        if (settings.obsEnabled && obs == null && !connecting && now >= nextConnect) connect();
        if (!settings.obsEnabled && obs != null) disconnect();
        boolean online = client.getNetworkHandler() != null;
        if (online && obsOk() && now >= nextReport) { nextReport = now + 60_000; obs.refresh(); }
        while (!replays.isEmpty() && (long) replays.peekFirst()[1] <= now) {
            Object[] due = replays.pollFirst();
            if (obsOk()) { awaitingReplay = (String) due[0]; obs.saveReplay(); }
        }
    }

    private static void mark(MinecraftClient client) {
        if (client.player == null) return;
        if (!ClientPlayNetworking.canSend(MomentKeyC2S.ID)) {
            client.inGameHud.setOverlayMessage(Text.literal("Метки работают на сервере с Mob Talk"), false);
            return;
        }
        ClientPlayNetworking.send(new MomentKeyC2S(""));
    }

    private static void onMoment(MinecraftClient client, MomentS2C moment) {
        if (moment.manual() && moment.mine() && !"none".equals(settings.notice) && client.inGameHud != null) {
            client.inGameHud.setOverlayMessage(Text.literal("✦ Момент отмечен: " + moment.title()), false);
            if ("sound".equals(settings.notice)) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 1.6f, 0.4f));
        }
        if (!obsOk() || !moment.mine() && !"all".equals(settings.scope)) return;
        if (settings.obsChapters && since.containsKey("record")) obs.chapter("★".repeat(Math.max(1, Math.min(3, moment.weight()))) + " " + moment.title());
        if (settings.obsReplay && moment.weight() >= settings.replayMinWeight)
            replays.addLast(new Object[] {moment.id(), System.currentTimeMillis() + settings.replayDelaySeconds * 1000L});
    }

    // ---- OBS ----

    private static void connect() {
        connecting = true;
        Settings current = settings;
        String password = OpenRouterToken.obsPassword(configDirectory);
        ObsWebSocket.connect(current.obsAddress, password, new ObsWebSocket.Events() {
            @Override public void state(String output, boolean active, long elapsed) { later(() -> onState(output, active, elapsed)); }
            @Override public void replaySaved(String path) { later(() -> onReplay(path)); }
            @Override public void status(boolean ok, String text) { later(() -> onStatus(ok, text)); }
        }).whenComplete((socket, failure) -> later(() -> {
            connecting = false;
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                onStatus(false, "OBS не отвечает на " + current.obsAddress + " (в OBS: Сервис → Настройки WebSocket-сервера → включить). " + cause.getClass().getSimpleName());
                return;
            }
            if (!settings.obsEnabled || settings != current) { socket.close(); return; }
            obs = socket;
        }));
    }

    private static void later(Runnable task) { synchronized (fromObs) { fromObs.add(task); } }

    private static void disconnect() {
        ObsWebSocket current = obs;
        obs = null;
        if (current != null) current.close();
        boolean wasActive = !since.isEmpty();
        since.clear();
        if (wasActive) for (String output : new String[] {"stream", "record"}) report(output, false, 0);
        status = ""; statusOk = false;
    }

    private static void onStatus(boolean ok, String text) {
        status = text; statusOk = ok;
        if (!ok) {
            LOGGER.info("Mob Talk: {}", text);
            ObsWebSocket current = obs;
            obs = null;
            if (current != null) current.close();
            for (String output : since.keySet().toArray(String[]::new)) { since.remove(output); report(output, false, 0); }
            nextConnect = System.currentTimeMillis() + 15_000;
        }
    }

    private static void onState(String output, boolean active, long elapsed) {
        boolean was = since.containsKey(output);
        if (active) since.put(output, System.currentTimeMillis() - elapsed); else since.remove(output);
        // Повторный ответ о той же записи — тоже отправляем: сервер поправит начало, если оно уехало.
        if (active || was) report(output, active, elapsed);
    }

    private static void onReplay(String path) {
        String id = awaitingReplay;
        awaitingReplay = null;
        if (id != null && ClientPlayNetworking.canSend(MomentReplayC2S.ID)) ClientPlayNetworking.send(new MomentReplayC2S(id, path));
    }

    private static void report(String output, boolean active, long elapsed) {
        if (MinecraftClient.getInstance().getNetworkHandler() == null || !ClientPlayNetworking.canSend(RecStateC2S.ID)) return;
        ClientPlayNetworking.send(new RecStateC2S(output, active, elapsed, "all".equals(settings.scope)));
    }
}
