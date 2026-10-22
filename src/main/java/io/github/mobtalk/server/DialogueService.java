package io.github.mobtalk.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.BargainMeter;
import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.Decider;
import io.github.mobtalk.core.DecisionState;
import io.github.mobtalk.core.Failures;
import io.github.mobtalk.core.FallbackLines;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.RuleDecider;
import io.github.mobtalk.core.SocialIntent;
import io.github.mobtalk.core.SpeciesCatalog;
import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.core.Vision;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.GameBridge.MobView;
import io.github.mobtalk.game.GameBridge.PlayerView;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.MobTalkNet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/** Диалог на сервере (трек A, раздел 2 плана): всё, что {@code MobTalkClient} делал вокруг разговора, кроме микрофона и экранов,
 * с сессией на каждого игрока ({@link Session}): своя цель, своё поколение запроса, своя очередь очевидцев и свой «эфир» (пока
 * звучит голос, посторонние реплики ждут). Один {@link ConversationEngine} на мир; моб, с которым идёт разговор, заперт за игроком
 * ({@link #busyMobs}): второй получает «занят». Вход — пакеты C2S через {@code MobTalkMod}; выход — {@link Outbox}
 * (статус, реплики всем в радиусе, звук чанками, уведомления, данные экранов). Ни одного класса Minecraft: мир — {@link GameBridge},
 * в тестах — фейк. Состояние сессий меняется только на потоке сервера ({@link GameBridge#onServer}), как раньше на потоке клиента. */
public final class DialogueService {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    private static final Gson GSON = new Gson();
    static final String AIM_HINT = "Наведите прицел на моба и удерживайте V";
    static final String READY = "Готов: удерживайте клавишу речи";
    /** Байт PCM 24 кГц 16 бит моно в миллисекунде: длительность клипа для эфира. */
    public static final int PCM_BYTES_PER_MILLI = 48;
    public static final String PCM_FORMAT = "pcm24k";
    private static final long BARGAIN_HUD_MILLIS = 120_000;

    /** Куда уходят пакеты; реализация на Minecraft — {@link ServerOutbox}, в тестах — запись. Все вызовы с потока сервера. */
    public interface Outbox {
        void status(UUID player, String status, boolean busy, String error);
        void speech(UUID player, MobSpeechS2C packet);
        /** Звук реплики целиком; реализация режет на чанки. */
        void clip(UUID player, SpeechService.Clip clip);
        /** Строка в чат как есть. */
        void chat(UUID player, String line);
        void screen(UUID player, String screen, String json);
        void state(UUID player, io.github.mobtalk.net.MobStateS2C packet);
        void label(UUID player, io.github.mobtalk.net.MobLabelS2C packet);
    }
    /** Фабрика движка: на сервере — настоящий {@link ConversationEngine} с ключами из окружения или DPAPI; в тестах — фейковый HTTP. */
    public interface Engines { ConversationEngine open(MobTalkConfig config, Path worldDirectory) throws Exception; }
    /** Что выключено на выделенном сервере: семья и свадьба (вторая версия), снимки экрана. */
    public record Options(boolean family, boolean pictures) { }
    /** Журнал админки (трек W, подключает интегратор): каждый ответ собеседнику — трасса шагов; остальное — события. Все вызовы на
     * потоке сервера, кроме {@link #step} (поток пула). Заглушка {@link #NONE} ничего не пишет. */
    public interface Journal {
        /** Начало ответа собеседнику; возвращает ключ записи для {@link #step}, {@link #finished}, {@link #failed}. */
        Object started(UUID player, String playerName, UUID mob, String mobName, String species, String question, String stt);
        void step(Object run, String step);
        void finished(Object run, String answer, List<String> actions, String model, String stt);
        void failed(Object run, String step, String message);
        /** Событие без трассы: подарок, торг, слух, очевидец. */
        void event(String kind, UUID player, String playerName, UUID mob, String mobName, String species, String text, Map<String, Object> data);
        Journal NONE = new Journal() {
            @Override public Object started(UUID player, String playerName, UUID mob, String mobName, String species, String question, String stt) { return null; }
            @Override public void step(Object run, String step) { }
            @Override public void finished(Object run, String answer, List<String> actions, String model, String stt) { }
            @Override public void failed(Object run, String step, String message) { }
            @Override public void event(String kind, UUID player, String playerName, UUID mob, String mobName, String species, String text, Map<String, Object> data) { }
        };
    }

    private final GameBridge bridge;
    private final Path worldDirectory;
    private final Outbox out;
    private final Engines engines;
    private final Function<MobTalkConfig, Decider> deciders;
    private final Supplier<PlayerBudget> budget;
    private final Supplier<TranscriptionService> transcription;
    private final Options options;
    private final DialoguePool pool;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Моб, с которым у игрока идёт запрос: второй игрок к нему получает «занят». */
    private final Map<UUID, Session> busyMobs = new ConcurrentHashMap<>();
    private final AtomicInteger clipIds = new AtomicInteger();
    private volatile ConversationEngine engine;
    private volatile MobTalkConfig engineConfig;
    private volatile Decider decider = new RuleDecider();
    private volatile boolean closed;
    private volatile Journal journal = Journal.NONE;
    final SpontaneousVoices spontaneous;
    private final MobStateSync states;
    private long ticks;

    public DialogueService(GameBridge bridge, Path worldDirectory, Outbox out, Engines engines, Function<MobTalkConfig, Decider> deciders,
                           Supplier<PlayerBudget> budget, Supplier<TranscriptionService> transcription, Options options, int threads) {
        this.bridge = bridge; this.worldDirectory = worldDirectory; this.out = out; this.engines = engines; this.deciders = deciders;
        this.budget = budget; this.transcription = transcription; this.options = options;
        this.pool = new DialoguePool(threads);
        this.spontaneous = new SpontaneousVoices(this, bridge);
        this.states = new MobStateSync(bridge, out);
    }

    // ---- Сессии. ----

    /** Один игрок на сервере: его цель, запрос, очередь очевидцев и эфир. Поля читает и меняет только поток сервера, кроме
     * помеченных volatile (их смотрят задачи пула). */
    final class Session {
        final UUID player;
        volatile String name = "";
        volatile long generation;
        volatile UUID target;
        volatile String dimension = "";
        final AtomicBoolean pipelineBusy = new AtomicBoolean();
        volatile Thread pipelineThread;
        volatile boolean requestActive;
        UUID conversationMob; long lastReplyMillis;
        final Deque<Ready> ready = new ArrayDeque<>();
        final List<Future<?>> witnessTasks = new ArrayList<>();
        int witnessesInFlight, pendingDeliveries;
        /** Эфир: пока {@code now < floorUntil} у игрока звучит голос; {@code voicesPending} — клипы, которые ещё качаются. */
        long floorUntil; int voicesPending; UUID speakingMob;
        Deferred deferred;
        CompletableFuture<String> actionRequest;
        final Map<UUID, BargainMeter.Move> bargainMoves = new HashMap<>();
        final Map<UUID, Long> bargainUntil = new HashMap<>();
        String status = AIM_HINT;
        final MobStateSync.Tracker tracker = new MobStateSync.Tracker();
        /** Путь, которым пришла последняя реплика (text/local/server), и ключ её записи в журнале админки. */
        volatile String stt = "text"; volatile Object run;
        Session(UUID player) { this.player = player; }

        boolean voiceOn() { return voicesPending > 0 || System.currentTimeMillis() < floorUntil; }
        boolean busyTalking() { return requestActive || voiceOn(); }
        boolean witnessPending() { return witnessesInFlight > 0 || !ready.isEmpty() || pendingDeliveries > 0; }
        void status(String text) { status = text; out.status(player, text, requestActive, ""); }
    }
    record Ready(Request request, ConversationEngine engine, ConversationEngine.PreparedReply prepared, boolean owed) { }
    record Deferred(Request request, ActionIntent action) { }
    /** overheard non-null: моб — очевидец или инициатор, а не собеседник; {@code merchant} — моб торгового окна, если запрос из него. */
    record Request(long generation, UUID target, MobTalkConfig config, MobSnapshot snapshot, UUID player, String playerName, String dimension,
                   String mobName, int mobEntityId, UUID merchant, ConversationEngine.Overheard overheard, byte[] picture) {
        Request with(MobSnapshot observed) {
            return new Request(generation, target, config, observed, player, playerName, dimension, mobName, mobEntityId, merchant, overheard, picture);
        }
    }
    private static final class EarlyState { boolean shown, delivered; volatile long offeredNanos; int clipId; }

    Session session(UUID player) { return sessions.computeIfAbsent(player, Session::new); }
    /** Сессии всех игроков в сети (для инициативы и болтовни). */
    java.util.Collection<Session> sessions() { return sessions.values(); }
    GameBridge bridge() { return bridge; }
    Outbox out() { return out; }
    Decider decider() { return decider; }
    DialoguePool pool() { return pool; }
    public ConversationEngine engine() { return engine; }
    /** Точка подключения админки: журнал диалогов и событий. */
    public void setJournal(Journal journal) { this.journal = journal == null ? Journal.NONE : journal; }
    Journal journal() { return journal; }
    MobTalkConfig config() { return bridge.config(); }

    public void join(UUID player, String name) { Session session = session(player); session.name = name; }
    public void leave(UUID player) {
        Session session = sessions.remove(player);
        if (session == null) return;
        cancelWork(session, "Игрок вышел", true);
        busyMobs.values().removeIf(s -> s == session);
        if (session.conversationMob != null) endConversation(session, session.conversationMob);
    }
    public void close() {
        closed = true;
        for (Session session : sessions.values()) cancelWork(session, "Остановлено", true);
        sessions.clear(); busyMobs.clear();
        pool.shutdown();
        ConversationEngine old = engine; engine = null;
        if (old != null) old.close();
    }

    // ---- Вход: пакеты от клиента (поток сервера). ----

    /** V зажата на мобе: он становится собеседником, статус уходит игроку. */
    public void talkStart(UUID player, int entityId) {
        Session session = session(player);
        bridge.mobByEntityId(player, entityId).whenComplete((mob, error) -> onServer(() -> {
            if (error != null) { refuse(session, plain(error, "Моб погиб или выгрузился: наведите прицел на другого")); return; }
            select(session, mob);
            bridge.attend(mob.id(), player, 60);
            session.status(READY);
        }));
    }
    /** Реплика текстом: от локального Whisper игрока или из чата. */
    public void talkText(UUID player, int entityId, String text, boolean fromVoice, byte[] picture) {
        Session session = session(player);
        if (text == null || text.isBlank()) { refuse(session, "Речь не распознана: удерживайте голосовую клавишу и произнесите вопрос"); return; }
        if (!"server".equals(session.stt) || !fromVoice) session.stt = fromVoice ? "local" : "text";
        bridge.mobByEntityId(player, entityId).whenComplete((mob, error) -> onServer(() -> {
            if (error != null) { refuse(session, plain(error, "Моб погиб или выгрузился")); return; }
            if (!mob.id().equals(session.target)) select(session, mob);
            readyForRequest(session, mob).thenAccept(ready -> onServer(() -> {
                if (!ready) return;
                PlayerBudget.Verdict verdict = budget.get().allow(player, PlayerBudget.Kind.DIALOGUE);
                if (!verdict.allowed()) { refuse(session, verdict.reason()); return; }
                stopFloor(session);
                long generation = ++session.generation;
                request(session, generation, mob, null, options.pictures ? picture : null)
                        .thenAccept(request -> onServer(() -> submit(session, request, text)));
            }));
        }));
    }
    /** Аудио на распознавание сервером (трек B даёт службу; заглушка отказывает). */
    public void talkAudio(UUID player, int entityId, String format, int seconds, byte[] audio) {
        Session session = session(player);
        bridge.mobByEntityId(player, entityId).whenComplete((mob, error) -> onServer(() -> {
            if (error != null) { refuse(session, plain(error, "Моб погиб или выгрузился")); return; }
            if (!mob.id().equals(session.target)) select(session, mob);
            readyForRequest(session, mob).thenAccept(ready -> onServer(() -> {
                if (!ready) return;
                PlayerBudget.Verdict verdict = budget.get().allow(player, PlayerBudget.Kind.TRANSCRIPTION);
                if (!verdict.allowed()) { refuse(session, verdict.reason()); return; }
                long generation = ++session.generation;
                session.requestActive = true;
                session.status("Сервер распознаёт речь…");
                String hints = hints(session, mob);
                transcription.get().transcribe(player, audio, format, seconds, hints).whenComplete((text, failure) -> onServer(() -> {
                    if (session.generation != generation) return;
                    session.requestActive = false;
                    if (failure != null) { reportFailure(session, null, failure); return; }
                    if (text == null || text.isBlank()) { refuse(session, "Речь не распознана: удерживайте голосовую клавишу и произнесите вопрос"); return; }
                    budget.get().charge(player, PlayerBudget.Kind.TRANSCRIPTION, 0, seconds);
                    session.stt = "server";
                    talkText(player, entityId, text, true, new byte[0]);
                }));
            }));
        }));
    }
    public void cancel(UUID player, String reason) {
        Session session = sessions.get(player);
        if (session != null) cancelWork(session, reason == null || reason.isBlank() ? AIM_HINT : reason);
    }
    /** Подарок из руки (G): мост берёт предмет, реакция моба придёт поводом инициативы. */
    public void gift(UUID player, int entityId) {
        Session session = session(player);
        bridge.mobByEntityId(player, entityId).thenCompose(mob -> bridge.giftFromHand(mob.id(), player).thenApply(result -> {
                    journal.event("gift", player, session.name, mob.id(), mob.name(), mob.type(), session.name + " → " + mob.name() + ": " + result, Map.of("reaction", result));
                    return result;
                }))
                .whenComplete((result, error) -> onServer(() -> out.chat(player, "[Mob Talk] " + (error != null ? "Подарок не удался: " + plain(error, "моб недоступен") : result))));
    }
    /** STAY/следуй/GUARD/стоп из экрана или команды: {@code intentJson} — {@link ActionIntent}; цель {@code all} у STOP — все мобы. */
    public void action(UUID player, int entityId, String intentJson) {
        Session session = session(player);
        ActionIntent action;
        try { action = GSON.fromJson(intentJson, ActionIntent.class); } catch (RuntimeException bad) { action = null; }
        if (action == null || action.type() == ActionType.NONE) { out.screen(player, "action", answer("Выберите действие", "")); return; }
        if (action.type() == ActionType.STOP && "all".equals(action.target())) {
            bridge.stopAllFollowers(player).whenComplete((result, error) -> onServer(() -> out.screen(player, "action", answer(result, error == null ? "" : plain(error, "не удалось")))));
            return;
        }
        ActionIntent intent = action;
        bridge.mobByEntityId(player, entityId).whenComplete((mob, error) -> onServer(() -> {
            if (error != null) { out.screen(player, "action", answer("", plain(error, "Моб не выбран"))); return; }
            if (intent.type() == ActionType.STOP) {
                cancelWork(session, "Действие отменено");
                bridge.apply(mob.id(), player, mob.dimension(), intent, null, -1, () -> true)
                        .whenComplete((result, failure) -> onServer(() -> out.screen(player, "action", answer(result, failure == null ? "" : plain(failure, "не удалось")))));
                return;
            }
            if (!mob.id().equals(session.target)) select(session, mob);
            readyForRequest(session, mob).thenAccept(ready -> onServer(() -> {
                if (!ready) { out.screen(player, "action", answer("", session.status)); return; }
                PlayerBudget.Verdict verdict = budget.get().allow(player, PlayerBudget.Kind.DIALOGUE);
                if (!verdict.allowed()) { refuse(session, verdict.reason()); out.screen(player, "action", answer("", verdict.reason())); return; }
                cancelWork(session, "Моб обдумывает просьбу…");
                long generation = session.generation;
                CompletableFuture<String> result = new CompletableFuture<>();
                session.actionRequest = result;
                result.whenComplete((message, failure) -> onServer(() -> out.screen(player, "action", answer(message, failure == null ? "" : plain(failure, "просьба отменена")))));
                bridge.capture(mob.id(), player, mob.dimension()).whenComplete((snapshot, failure) -> onServer(() -> {
                    if (session.generation != generation || !mob.id().equals(session.target)) { result.completeExceptionally(new CancellationException("Просьба отменена")); return; }
                    if (failure != null) { reportFailure(session, null, failure); return; }
                    String target = "меня";
                    if ((intent.type() == ActionType.ATTACK || intent.type() == ActionType.FLEE || intent.type() == ActionType.SPARE) && !"player".equals(intent.target())) {
                        var observed = snapshot.observedEntities().stream().filter(entity -> entity.key().equals(intent.target())
                                || entity.id().toString().equals(intent.target())).findFirst().orElse(null);
                        if (observed == null) { reportFailure(session, null, new IllegalArgumentException("Цель не наблюдается мобом")); return; }
                        target = observed.name() + " (цель " + observed.key() + ")";
                    }
                    String question = switch (intent.type()) {
                        case FOLLOW -> "Пожалуйста, следуй за мной и оставайся рядом, пока я не попрошу остановиться. Реши сам, согласен ли ты.";
                        case FLEE -> "Пожалуйста, отойди подальше от " + ("меня".equals(target) ? "меня" : "моба " + target) + ". Реши сам, хочешь ли ты уйти.";
                        case ATTACK -> "Я прошу тебя атаковать " + target + ". Реши сам, согласен ли ты и позволяет ли это твоя природа.";
                        case STAY -> "Пожалуйста, подожди меня здесь и никуда не уходи, пока я не вернусь. Реши сам, согласен ли ты.";
                        case GUARD -> "Пожалуйста, охраняй меня: держись рядом и не подпускай врагов. Реши сам, согласен ли ты.";
                        case SPARE -> "Пожалуйста, не нападай на " + target + ". Реши сам, согласен ли ты.";
                        default -> "";
                    };
                    request(session, generation, mob, snapshot, null).thenAccept(request -> onServer(() -> submit(session, request, question)));
                }));
            }));
        }));
    }
    /** Данные для экранов и команд: {@code info}/{@code info_secrets} (панель «Моб и деревня»), {@code deal} (торговец за окном
     * {@code mobId} = syncId), {@code village} (положение в деревне), {@code state} (/mobtalk state), {@code forgive}. */
    public void screen(UUID player, String screen, int mobId) {
        Session session = session(player);
        switch (screen) {
            case "info", "info_secrets" -> bridge.player(player).thenCompose(view -> bridge.info(session.target, player, view.dimension(), (int) view.x(), (int) view.z(), screen.endsWith("secrets")))
                    .whenComplete((report, error) -> onServer(() -> out.screen(player, "info", answer(report, error == null ? "" : plain(error, "Мир ещё не загружен")))));
            case "deal" -> bridge.player(player).whenComplete((view, error) -> onServer(() -> {
                UUID merchant = error == null ? view.merchant() : null;
                JsonObject json = new JsonObject();
                json.addProperty("mob", merchant == null ? "" : merchant.toString()); json.addProperty("syncId", mobId);
                if (merchant != null) bridge.mob(merchant, view.dimension()).whenComplete((mob, failure) -> onServer(() -> {
                    if (failure == null) { select(session, mob); json.addProperty("entityId", mob.entityId()); json.addProperty("name", mob.name()); }
                    out.screen(player, "deal", json.toString());
                }));
                else out.screen(player, "deal", json.toString());
            }));
            case "village" -> bridge.player(player).whenComplete((view, error) -> onServer(() -> {
                List<String> lines = error != null ? List.of("Мир ещё не загружен.") : ScreenReports.villageLines(bridge, player, view);
                out.screen(player, "village", GSON.toJson(lines));
            }));
            case "state" -> bridge.player(player).whenComplete((view, error) -> onServer(() ->
                    out.chat(player, "[Mob Talk] " + (error != null ? "Мир ещё не загружен" : ScreenReports.stateLine(bridge, session.target, player, view, decider)))));
            case "trade" -> bridge.mobByEntityId(player, mobId).thenCompose(mob -> { select(session, mob); return bridge.openTrade(mob.id(), player, mob.dimension()); })
                    .whenComplete((ignored, error) -> onServer(() -> { if (error != null) out.chat(player, "[Mob Talk] " + plain(error, "Не удалось открыть сделки: моб недоступен")); }));
            case "forgive" -> bridge.forgiveVillage(player).whenComplete((result, error) -> onServer(() -> out.chat(player, "[Mob Talk] " + (error == null ? result : plain(error, "не удалось")))));
            default -> out.screen(player, screen, answer("", "Неизвестный экран: " + screen));
        }
    }
    static String answer(String message, String error) {
        JsonObject json = new JsonObject();
        json.addProperty("message", message == null ? "" : message); json.addProperty("error", error == null ? "" : error);
        return json.toString();
    }

    // ---- Тик сервера. ----

    /** Раз в тик с потока сервера: очередь очевидцев, отложенные действия, внимание, конец разговора по тишине, HUD, инициатива. */
    public void tick() {
        if (closed) return;
        onServer(this::tickNow); // На сервере это уже его поток (выполняется сразу); в тестах фейк берёт замок, как один поток.
    }
    private void tickNow() {
        if (closed) return;
        ticks++;
        long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            if (!session.ready.isEmpty()) playNextWitness(session);
            if (session.deferred != null && !session.voiceOn()) runDeferredAction(session);
            if (session.speakingMob != null && !session.voiceOn()) { session.speakingMob = null; if (!session.requestActive) session.status(READY); }
            if (ticks % 20 == 0) {
                keepAttention(session);
                if (session.conversationMob != null && now - session.lastReplyMillis > config().conversationSilenceMinutes * 60_000L) endConversation(session, session.conversationMob);
                states.tick(session.player, session.target, session.tracker, bargainFor(session, now));
                bridge.player(session.player).thenAccept(view -> onServer(() -> {
                    session.dimension = view.dimension();
                    if (ticks % 600 == 0) spontaneous.chronicle(session, view, bridge.villageAt(session.player, view.dimension(), (int) view.x(), (int) view.z()));
                }));
            }
        }
        spontaneous.tick(ticks);
    }
    private Map<UUID, BargainMeter.Move> bargainFor(Session session, long now) {
        session.bargainUntil.entrySet().removeIf(e -> e.getValue() < now);
        session.bargainMoves.keySet().retainAll(session.bargainUntil.keySet());
        return session.bargainMoves;
    }

    // ---- Выбор цели и готовность. ----

    void select(Session session, MobView mob) {
        if (!mob.id().equals(session.target)) {
            if (session.conversationMob != null && !session.conversationMob.equals(mob.id())) endConversation(session, session.conversationMob);
            cancelWork(session, READY);
            session.target = mob.id();
            session.bargainMoves.remove(mob.id()); session.bargainUntil.remove(mob.id());
        }
        session.dimension = mob.dimension();
    }
    private CompletableFuture<Boolean> readyForRequest(Session session, MobView mob) {
        if (!config().enabled) { refuse(session, "Mob Talk выключен на сервере"); return CompletableFuture.completedFuture(false); }
        return bridge.player(session.player).handle((player, error) -> {
            if (error != null || !player.alive()) { refuse(session, "Игрок недоступен"); return false; }
            if (!player.dimension().equals(mob.dimension()) || mob.squaredDistanceTo(player) > config().maxDistance * config().maxDistance) {
                refuse(session, "Подойдите к мобу ближе (до " + Math.round(config().maxDistance) + " блоков)"); return false;
            }
            if (session.pipelineBusy.get()) { refuse(session, "Предыдущий разговор ещё завершается"); return false; }
            Session holder = busyMobs.get(mob.id());
            if (holder != null && holder != session) { refuse(session, mob.name() + " занят разговором с " + holder.name); return false; }
            return true;
        });
    }
    private void refuse(Session session, String reason) {
        session.status = reason;
        out.status(session.player, "", false, reason);
    }
    private CompletableFuture<Request> request(Session session, long generation, MobView mob, MobSnapshot snapshot, byte[] picture) {
        return bridge.player(session.player).thenApply(player -> new Request(generation, mob.id(), config(), snapshot, session.player, player.name(),
                mob.dimension(), mob.name(), mob.entityId(), player.merchant(), null, picture));
    }
    private String hints(Session session, MobView mob) {
        List<String> priority = new ArrayList<>();
        priority.add(mob.name());
        try {
            for (UUID id : bridge.knownMobsNear(session.player, 32).get(2, TimeUnit.SECONDS)) {
                String name = bridge.memory().displayName(id);
                if (!name.isBlank()) priority.add(name);
            }
        } catch (Exception ignored) { }
        return bridge.memory().hotwords(priority, 500);
    }

    // ---- Отмена. ----

    void cancelWork(Session session, String reason) { cancelWork(session, reason, false); }
    /** Новое поколение: задачи прерываются, очереди чистятся, замок моба снимается. {@code keepVoice}: уже звучащее доигрывает. */
    void cancelWork(Session session, String reason, boolean keepVoice) {
        session.generation++;
        session.deferred = null;
        spontaneous.cancel(session);
        for (Future<?> task : session.witnessTasks) task.cancel(true);
        session.witnessTasks.clear();
        session.witnessesInFlight = 0;
        for (Ready ready : session.ready) ready.prepared.reply().discardSpeech();
        session.ready.clear();
        if (session.actionRequest != null) session.actionRequest.completeExceptionally(new CancellationException(reason));
        session.actionRequest = null;
        session.requestActive = false;
        if (!keepVoice) stopFloor(session);
        Thread running = session.pipelineThread;
        if (running != null) running.interrupt();
        busyMobs.values().removeIf(s -> s == session);
        session.status(reason);
    }
    /** Голос этого игрока обрывается: его собственный вопрос перебивает всё, что звучало. */
    private void stopFloor(Session session) { session.floorUntil = 0; session.voicesPending = 0; session.speakingMob = null; }

    // ---- Конвейер запроса. ----

    private void submit(Session session, Request request, String question) {
        if (session.generation != request.generation) return;
        if (!session.pipelineBusy.compareAndSet(false, true)) {
            refuse(session, "Предыдущий разговор ещё завершается");
            if (session.actionRequest != null) session.actionRequest.completeExceptionally(new IllegalStateException(session.status));
            session.actionRequest = null;
            return;
        }
        busyMobs.put(request.target, session);
        session.requestActive = true;
        session.status("Моб думает…");
        String stt = session.stt;
        Object run = journal.started(session.player, request.playerName, request.target, request.mobName,
                request.snapshot == null ? "" : request.snapshot.type(), question, stt);
        session.run = run;
        session.stt = "text";
        pool.submit(DialoguePool.Priority.ANSWER, () -> {
            session.pipelineThread = Thread.currentThread();
            long began = System.nanoTime();
            try {
                checkCurrent(session, request);
                journal.step(run, "budget");
                if (request.merchant != null) {
                    UUID merchant = bridge.currentMerchant(request.player).get(10, TimeUnit.SECONDS);
                    if (!request.target.equals(merchant)) throw new InterruptedException("Торговец сменился");
                }
                MobSnapshot snapshot = request.snapshot == null ? bridge.capture(request.target, request.player, request.dimension).get(10, TimeUnit.SECONDS) : request.snapshot;
                journal.step(run, "context");
                checkCurrent(session, request);
                ConversationEngine localEngine = ensureEngine(request.config);
                checkCurrent(session, request);
                EarlyState early = new EarlyState();
                long thinking = System.nanoTime();
                journal.step(run, "prompt");
                ConversationEngine.EarlyListener earlyListener = (line, name, audio, claim) -> {
                    early.offeredNanos = System.nanoTime();
                    journal.step(run, "llm_first");
                    onServer(() -> showEarly(session, request, early, question, line, name, audio, claim));
                };
                ConversationEngine.PreparedReply prepared = localEngine.prepare(snapshot, question, null, pictureFor(request, snapshot, question), earlyListener);
                long done = System.nanoTime();
                journal.step(run, "llm_done");
                journal.step(run, "parsed");
                LOGGER.info("Mob Talk timing ({}): {} ms before the model, line out {}, whole answer {} ms", request.playerName, (thinking - began) / 1_000_000,
                        early.offeredNanos == 0 ? "with the answer" : "at " + (early.offeredNanos - thinking) / 1_000_000 + " ms", (done - thinking) / 1_000_000);
                try { checkCurrent(session, request); } catch (InterruptedException cancelled) { prepared.reply().discardSpeech(); throw cancelled; }
                Request observed = request.with(snapshot);
                onServer(() -> { session.pendingDeliveries++; deliver(session, observed, localEngine, prepared, early); });
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                journal.failed(run, "llm_done", "Ответ отменён: " + (cancelled.getMessage() == null ? "состояние изменилось" : cancelled.getMessage()));
                onServer(() -> {
                    if (session.generation == request.generation) {
                        session.status("Состояние моба изменилось: ответ отменён");
                        if (session.actionRequest != null) session.actionRequest.completeExceptionally(cancelled);
                        session.actionRequest = null;
                        endRequest(session, request);
                    }
                });
            } catch (Exception failure) {
                onServer(() -> reportFailure(session, request, failure));
            } finally {
                session.pipelineThread = null;
                session.pipelineBusy.set(false);
                Thread.interrupted();
                onServer(() -> { if (session.generation == request.generation) session.requestActive = false; });
            }
        });
    }

    private synchronized ConversationEngine ensureEngine(MobTalkConfig config) throws Exception {
        ConversationEngine cached = engine;
        if (cached != null && config == engineConfig) return cached;
        if (cached != null) cached.close();
        ConversationEngine created = engines.open(config, worldDirectory);
        engineConfig = config;
        engine = created;
        try { decider = deciders.apply(config); } catch (RuntimeException failure) { decider = new RuleDecider(); }
        return created;
    }
    /** Движок для фоновых дел (болтовня, свадьба): создаётся на вызывающем потоке пула. */
    ConversationEngine engineOrNull() {
        try { return ensureEngine(config()); } catch (Exception unavailable) { LOGGER.info("Mob Talk: no engine ({})", unavailable.getClass().getSimpleName()); return null; }
    }

    private byte[] pictureFor(Request request, MobSnapshot snapshot, String question) {
        byte[] picture = request.picture;
        if (picture == null || picture.length == 0 || picture.length > MobTalkNet.MAX_PICTURE || request.overheard != null) return null;
        MobCard card = bridge.memory().card(request.target, request.player);
        boolean firstMeeting = card == null || card.turns().isEmpty();
        return Vision.wanted(request.config.vision, snapshot, firstMeeting, question) ? picture : null;
    }

    private static void checkCurrent(Session session, Request request) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || session.generation != request.generation) throw new InterruptedException("Cancelled");
    }
    /** Запрос ещё в силе: поколение, цель (у собеседника), игрок в сети. Поток сервера. */
    private boolean isCurrent(Session session, Request request) {
        return !closed && session.generation == request.generation && sessions.get(session.player) == session
                && (request.overheard != null || request.target.equals(session.target));
    }

    // ---- Доставка. ----

    /** Строка игрока раньше остального ответа: в чат и в динамик сейчас, доставка потом пропустит и то и другое. */
    private void showEarly(Session session, Request request, EarlyState early, String asked, String text, String name, SpeechStream audio,
                           java.util.function.BooleanSupplier claim) {
        if (early.delivered || early.shown || !isCurrent(session, request) || !claim.getAsBoolean()) { if (audio != null && !early.shown) audio.close(); return; }
        early.shown = true;
        early.clipId = speak(session, request, name, text, asked, audio, MobSpeechS2C.ANSWER, false);
        session.status("Говорит…");
    }

    void deliver(Session session, Request request, ConversationEngine localEngine, ConversationEngine.PreparedReply prepared, EarlyState early) {
        boolean async = false;
        if (early != null) early.delivered = true;
        boolean shownEarly = early != null && early.shown;
        try {
            ConversationEngine.Reply reply = prepared.reply();
            if (!isCurrent(session, request)) { dropped(session, request, reply); return; }
            CompletableFuture<UUID> merchant = request.merchant == null ? CompletableFuture.completedFuture(request.target) : bridge.currentMerchant(request.player);
            async = true;
            ActionIntent action = reply.action();
            boolean defer = reply.speech() != null && action.type() != ActionType.NONE && action.type() != ActionType.STOP;
            merchant.thenCompose(id -> {
                if (!request.target.equals(id)) throw new CancellationException("Торговец сменился");
                return bridge.applyWithRevision(request.target, request.player, request.dimension, defer ? ActionIntent.none() : action, reply.bargain(),
                        reply.deal(), reply.unlock(), reply.quest(), reply.socialIntent(), prepared.gesture(), request.snapshot, reply.interactionRevision(),
                        () -> session.generation == request.generation);
            }).whenComplete((delivery, error) -> onServer(() -> {
                try {
                    if (!isCurrent(session, request)) { dropped(session, request, reply); return; }
                    if (error != null) { reply.discardSpeech(); reportFailure(session, request, error); return; }
                    if (!localEngine.commitDelivered(prepared, delivery.revision())) {
                        reply.discardSpeech();
                        if (request.overheard == null) { journal.failed(session.run, "delivered", "Моб исчез: ответ отменён"); session.run = null; }
                        session.status("Моб исчез: ответ отменён");
                        if (session.actionRequest != null) session.actionRequest.completeExceptionally(new CancellationException(session.status));
                        session.actionRequest = null; endRequest(session, request); return;
                    }
                    if (request.overheard == null && options.family)
                        bridge.afterWords(request.target, request.player, reply.recognizedText(), prepared.gesture()).thenAccept(lines -> onServer(() -> {
                            if (lines != null && !lines.isBlank()) for (String line : lines.split("\n")) out.chat(request.player, line);
                        }));
                    finishDelivery(session, request, reply, delivery.message(), shownEarly);
                    if (defer) { session.deferred = new Deferred(request, action); if (!session.voiceOn()) runDeferredAction(session); }
                } finally { session.pendingDeliveries--; }
            }));
        } finally { if (!async) session.pendingDeliveries--; }
    }

    private void finishDelivery(Session session, Request request, ConversationEngine.Reply reply, String actionMessage, boolean shownEarly) {
        session.requestActive = false;
        if (request.overheard == null) { session.conversationMob = request.target; session.lastReplyMillis = System.currentTimeMillis(); }
        boolean spontaneousLine = request.overheard != null && request.overheard.spontaneous();
        String kind = request.overheard == null ? MobSpeechS2C.ANSWER : request.overheard.kind() == ConversationEngine.Overheard.Kind.BYSTANDER ? MobSpeechS2C.WITNESS : MobSpeechS2C.INITIATIVE;
        if (spontaneousLine) spontaneous.spoken(session, request.target, request.overheard, request.generation, reply);
        if (reply.silent()) {
            reply.discardSpeech();
            // Промолчавший инициатор: клиенту спросившего — пустая реплика, чтобы самотест и журнал знали, что повод закрыт.
            if (spontaneousLine) out.speech(session.player, new MobSpeechS2C(request.mobEntityId, request.target, reply.speakerName(), "", true, 0,
                    request.config.voiceSilentRange, kind, 0, "", ""));
            session.status(READY); endRequest(session, request); return;
        }
        if (request.overheard == null) {
            if (reply.speech() != null) journal.step(session.run, "tts");
            journal.step(session.run, "delivered");
            List<String> acts = new ArrayList<>();
            if (reply.action() != null && reply.action().type() != ActionType.NONE) acts.add(reply.action().type().name());
            if (actionMessage != null && !actionMessage.isBlank()) acts.add(actionMessage);
            journal.finished(session.run, reply.responseText(), acts, request.config.llmModel, null);
            session.run = null;
        } else journal.event(request.overheard.kind() == ConversationEngine.Overheard.Kind.BYSTANDER ? "witness" : "village_talk", request.player, request.playerName,
                request.target, reply.speakerName(), request.snapshot == null ? "" : request.snapshot.type(), reply.speakerName() + ": " + reply.responseText(),
                Map.of("line", reply.responseText(), "about", request.playerName));
        if (!shownEarly) {
            speak(session, request, reply.speakerName(), reply.responseText(), request.overheard == null ? reply.recognizedText() : "", reply.speech(), kind, request.overheard != null);
            session.status(request.overheard == null ? reply.speech() != null ? "Говорит…" : "Ответ в чате; озвучка недоступна. Повторите позже"
                    : spontaneousLine ? reply.speakerName() + " заговаривает…" : reply.speakerName() + " вмешивается…");
        } else if (!session.voiceOn()) session.status(READY);
        if (actionMessage != null && !actionMessage.isBlank()) out.chat(request.player, "[Mob Talk] " + actionMessage);
        if (request.overheard == null) {
            BargainMeter.Move move = BargainMeter.move(reply.bargain(), reply.deal(), request.snapshot, reply.recognizedText());
            if (move != null) { session.bargainMoves.put(request.target, move); session.bargainUntil.put(request.target, System.currentTimeMillis() + BARGAIN_HUD_MILLIS); }
        }
        queueWitnesses(session, request, reply);
        if (session.actionRequest != null) session.actionRequest.complete(actionMessage == null || actionMessage.isBlank() ? "Моб ответил без нового действия" : actionMessage);
        session.actionRequest = null;
        endRequest(session, request);
    }

    /** Реплика всем игрокам в {@code voiceSilentRange} от моба и её звук, когда докачается; возвращает номер клипа (0 — без звука). */
    int speak(Session session, Request request, String speaker, String text, String heard, SpeechStream audio, String kind, boolean background) {
        int clipId = audio == null ? 0 : clipIds.incrementAndGet();
        double range = request.config.voiceSilentRange;
        if (audio != null) { session.voicesPending++; session.speakingMob = request.target; }
        bridge.playersNear(request.target, request.dimension, range).whenComplete((players, error) -> onServer(() -> {
            List<UUID> listeners = error != null || players == null ? List.of(session.player) : players;
            if (!listeners.contains(session.player)) { listeners = new ArrayList<>(listeners); listeners.add(session.player); }
            for (UUID listener : listeners) {
                boolean you = listener.equals(session.player);
                out.speech(listener, new MobSpeechS2C(request.mobEntityId, request.target, speaker, text, you, clipId, range, kind, 0,
                        heard, you ? "" : request.playerName));
            }
            if (audio == null) return;
            List<UUID> audience = listeners;
            pool.submit(DialoguePool.Priority.ANSWER, () -> {
                byte[] pcm;
                try { pcm = audio.awaitAll(TimeUnit.SECONDS.toNanos(90)); }
                catch (InterruptedException cancelled) { pcm = new byte[0]; Thread.currentThread().interrupt(); }
                finally { audio.close(); }
                byte[] data = pcm;
                onServer(() -> {
                    session.voicesPending = Math.max(0, session.voicesPending - 1);
                    if (data.length == 0 || session.generation != request.generation && !background) return;
                    int millis = data.length / PCM_BYTES_PER_MILLI;
                    SpeechService.Clip clip = new SpeechService.Clip(clipId, PCM_FORMAT, data, millis);
                    for (UUID listener : audience) out.clip(listener, clip);
                    session.floorUntil = Math.max(session.floorUntil, System.currentTimeMillis() + millis + 250);
                    session.speakingMob = request.target;
                });
            });
        }));
        return clipId;
    }

    private void endRequest(Session session, Request request) {
        if (request != null && request.overheard == null) busyMobs.remove(request.target, session);
    }

    /** Ответ, который уже ни к чему: очевидец ушёл, игрок переключился. */
    private void dropped(Session session, Request request, ConversationEngine.Reply reply) {
        reply.discardSpeech();
        if (request.overheard != null) spontaneous.ended(session, request.target);
        endRequest(session, request);
        if (request.overheard != null && session.generation == request.generation) session.status(READY);
    }

    void reportFailure(Session session, Request request, Throwable failure) {
        if (request != null && request.overheard == null && session.run != null) { journal.failed(session.run, "llm_done", Failures.plain(failure)); session.run = null; }
        if (request != null && !isCurrent(session, request)) return;
        if (session.actionRequest != null) session.actionRequest.completeExceptionally(failure);
        session.actionRequest = null;
        session.requestActive = false;
        endRequest(session, request);
        LOGGER.warn("Mob Talk request of {} failed ({})", session.name, failure.getClass().getSimpleName());
        if (request != null && request.overheard != null) { spontaneous.ended(session, request.target); session.status(READY); return; }
        String message = Failures.plain(failure);
        if (message.isBlank()) message = "Разговор не удался: проверьте ключ OpenRouter на сервере";
        if (message.length() > 240) message = message.substring(0, 240);
        session.status = message;
        out.status(session.player, "", false, message);
        // Модель недоступна, а не моб: короткая местная реплика, чтобы существо не онемело.
        if (request != null && failure instanceof java.io.IOException) {
            MobCard card = bridge.memory().card(request.target, request.player);
            int calm = card == null || card.state() == null ? 50 : card.state().calm();
            boolean hostile = SpeciesCatalog.get(request.snapshot == null ? "" : request.snapshot.type()).hostile();
            String line = FallbackLines.line(hostile, calm, System.nanoTime() / 1_000_000L) + " §8(без связи)";
            out.speech(session.player, new MobSpeechS2C(request.mobEntityId, request.target, request.mobName, line, true, 0, request.config.voiceSilentRange, MobSpeechS2C.FALLBACK, 0, "", ""));
            bridge.attend(request.target, request.player, 60);
        }
    }

    private void runDeferredAction(Session session) {
        Deferred deferred = session.deferred;
        session.deferred = null;
        if (deferred == null || session.generation != deferred.request.generation) return;
        bridge.apply(deferred.request.target, deferred.request.player, deferred.request.dimension, deferred.action, deferred.request.snapshot, -1,
                () -> session.generation == deferred.request.generation).whenComplete((message, error) -> onServer(() -> {
            if (error == null && message != null && !message.isBlank() && session.generation == deferred.request.generation) out.chat(session.player, "[Mob Talk] " + message);
        }));
    }

    private void endConversation(Session session, UUID mob) {
        if (mob == null) return;
        if (mob.equals(session.conversationMob)) session.conversationMob = null;
        ConversationEngine localEngine = engine;
        if (localEngine == null || closed) return;
        UUID player = session.player; String name = session.name;
        pool.submit(DialoguePool.Priority.MAINTENANCE, () -> localEngine.endConversation(mob, player, name));
    }

    /** Каждую секунду: собеседник, говорящий и очевидцы в очереди стоят и смотрят на игрока. */
    private void keepAttention(Session session) {
        if (session.target != null && (session.requestActive || session.speakingMob == null && session.voiceOn())) bridge.attend(session.target, session.player, 40);
        if (session.speakingMob != null) bridge.attend(session.speakingMob, session.player, 40);
        for (Ready ready : session.ready) bridge.attend(ready.request.target, session.player, 40);
    }

    // ---- Очевидцы и посторонние реплики. ----

    private void queueWitnesses(Session session, Request request, ConversationEngine.Reply reply) {
        if (request.overheard != null || request.config.witnessReplies <= 0 || reply.silent()) return;
        ConversationEngine.Overheard heard = new ConversationEngine.Overheard(reply.speakerName(), reply.recognizedText(), reply.responseText());
        boolean eventful = reply.socialIntent() != SocialIntent.NONE && reply.socialIntent() != SocialIntent.KIND
                || reply.action().type() == ActionType.ATTACK || reply.action().type() == ActionType.FLEE;
        bridge.witnesses(request.target, request.player, request.dimension, request.config.witnessReplies).whenComplete((ids, error) -> onServer(() -> {
            if (error != null || ids == null || ids.isEmpty() || session.generation != request.generation) return;
            if (!budget.get().allow(session.player, PlayerBudget.Kind.WITNESS).allowed()) return;
            for (UUID id : ids) startWitness(session, id, heard, request, eventful);
        }));
    }
    private void startWitness(Session session, UUID id, ConversationEngine.Overheard heard, Request after, boolean eventful) {
        UUID player = after.player;
        startSpontaneous(session, id, null, false, () -> {
            if (eventful) return heard;
            DecisionState state = bridge.memory().decision(id, player, null, heard.speaker(), heard.said() + " / " + heard.answer(), false);
            return decider.bystanderAnswers(state) ? heard : null;
        });
    }

    /** Реплика, которой никто не просил (очевидец, первое слово, ответ другому существу): {@code decide} на пуле возвращает, что
     * моб услышал (или повод), или null — промолчать. Думает на пуле, итог ложится в очередь сессии и звучит, когда эфир свободен.
     * {@code owed}: реакция, которую игра должна (подарок) — думает с приоритетом и звучит раньше очевидцев. Поток сервера. */
    void startSpontaneous(Session session, UUID id, String mobName, boolean owed, Supplier<ConversationEngine.Overheard> decide) {
        long generation = session.generation;
        bridge.mob(id, session.dimension.isEmpty() ? "minecraft:overworld" : session.dimension).whenComplete((mob, error) -> onServer(() -> {
            if (error != null || session.generation != generation) { spontaneous.ended(session, id); return; }
            bridge.player(session.player).whenComplete((player, failure) -> onServer(() -> {
                if (failure != null || !player.alive() || mob.squaredDistanceTo(player) > config().maxDistance * config().maxDistance || session.generation != generation) {
                    LOGGER.info("Mob Talk bystander {} out of reach of {}", id, session.name); spontaneous.ended(session, id); return;
                }
                Request seed = new Request(generation, id, config(), null, session.player, player.name(), mob.dimension(),
                        mobName == null || mobName.isBlank() ? mob.name() : mobName, mob.entityId(), null, null, null);
                ConversationEngine known = engine;
                LOGGER.info("Mob Talk initiative: {} starts thinking for {}{}", seed.mobName, session.name, known == null ? " (creating the engine)" : "");
                session.witnessesInFlight++;
                session.witnessTasks.removeIf(Future::isDone);
                session.witnessTasks.add(pool.submit(owed ? DialoguePool.Priority.REACTION : DialoguePool.Priority.WITNESS, () -> {
                    boolean spoke = false;
                    try {
                        if (session.generation != seed.generation) return;
                        ConversationEngine localEngine = known != null ? known : ensureEngine(seed.config);
                        ConversationEngine.Overheard heard = decide.get();
                        if (heard == null || session.generation != seed.generation) return;
                        bridge.attend(seed.target, seed.player, 100);
                        MobSnapshot snapshot = bridge.capture(seed.target, seed.player, seed.dimension).get(10, TimeUnit.SECONDS);
                        if (session.generation != seed.generation) return;
                        ConversationEngine.PreparedReply prepared = localEngine.prepare(snapshot, "", heard);
                        spoke = true;
                        Request observed = new Request(seed.generation, seed.target, seed.config, snapshot, seed.player, seed.playerName, seed.dimension,
                                seed.mobName, seed.mobEntityId, null, heard, null);
                        onServer(() -> {
                            if (session.generation != seed.generation) { prepared.reply().discardSpeech(); spontaneous.ended(session, id); return; }
                            Ready ready = new Ready(observed, localEngine, prepared, owed);
                            if (!owed) { session.ready.add(ready); return; }
                            List<Ready> queued = new ArrayList<>(session.ready); session.ready.clear();
                            int at = 0; while (at < queued.size() && queued.get(at).owed()) at++;
                            queued.add(at, ready); session.ready.addAll(queued);
                        });
                    } catch (Exception thrown) {
                        LOGGER.info("Mob Talk bystander {} stayed out: {}", seed.mobName, String.valueOf(thrown.getMessage()));
                    } finally {
                        Thread.interrupted();
                        boolean delivered = spoke;
                        onServer(() -> { if (session.generation == seed.generation) session.witnessesInFlight = Math.max(0, session.witnessesInFlight - 1); if (!delivered) spontaneous.ended(session, id); });
                    }
                }));
            }));
        }));
    }

    private void playNextWitness(Session session) {
        if (session.voiceOn() || session.requestActive || session.pendingDeliveries > 0 || spontaneous.voicing(session)) return;
        Ready next = session.ready.poll();
        if (next == null) return;
        if (bridge.weddingActive()) { next.prepared.reply().discardSpeech(); spontaneous.ended(session, next.request.target); return; }
        if (session.generation != next.request.generation) { next.prepared.reply().discardSpeech(); if (next.request.overheard != null) spontaneous.ended(session, next.request.target); return; }
        session.pendingDeliveries++;
        deliver(session, next.request, next.engine, next.prepared, null);
    }

    /** Строка болтовни деревни: в чат всем рядом и голосом от говорящего. */
    void playOverheard(Session session, MobView mob, String name, String line, SpeechStream audio, double range) {
        Request request = new Request(session.generation, mob.id(), config(), null, session.player, session.name, mob.dimension(), mob.name(), mob.entityId(), null,
                ConversationEngine.Overheard.dialogue(name, line), null);
        speak(session, request, name, line, "", audio, MobSpeechS2C.VILLAGE, true);
    }
    /** Объявление жителя в чат всем, кто рядом с ним. */
    void announce(Session session, MobView mob, String name, String text, boolean work) {
        bridge.playersNear(mob.id(), mob.dimension(), config().noticeRange).whenComplete((players, error) -> onServer(() -> {
            List<UUID> listeners = error != null || players == null || players.isEmpty() ? List.of(session.player) : players;
            for (UUID listener : listeners) {
                out.chat(listener, "§e" + name + "§r: " + text);
                if (work) out.chat(listener, "§8[Mob Talk] Подойдите к " + name + " ближе " + Math.round(io.github.mobtalk.core.VillageNotices.ARRIVE_RANGE) + " блоков, чтобы узнать поручение");
            }
        }));
    }

    // ---- Служебное. ----

    /** На поток сервера; в тестах мост выполняет сразу. */
    void onServer(Runnable work) {
        bridge.onServer(() -> { work.run(); return null; }).whenComplete((ignored, error) -> {
            if (error != null && !closed) LOGGER.debug("Mob Talk: server task skipped ({})", error.getClass().getSimpleName());
        });
    }
    static String plain(Throwable error, String fallback) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = Failures.plain(cause);
        return message == null || message.isBlank() ? fallback : message;
    }
    /** Для тестов: статус сессии игрока. */
    public String status(UUID player) { Session session = sessions.get(player); return session == null ? "" : session.status; }
    public boolean busyTalking(UUID player) { Session session = sessions.get(player); return session != null && session.busyTalking(); }
    public boolean witnessPending(UUID player) { Session session = sessions.get(player); return session != null && session.witnessPending(); }
    public UUID holderOf(UUID mob) { Session session = busyMobs.get(mob); return session == null ? null : session.player; }
}
