package io.github.mobtalk;

import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.Gesture;
import io.github.mobtalk.core.GestureIntent;
import io.github.mobtalk.core.RivalryLedger;
import io.github.mobtalk.core.Romance;
import io.github.mobtalk.core.SocialProfile;
import io.github.mobtalk.core.Wedding;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.game.WeddingController;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.SelfTestLine;
import io.github.mobtalk.server.AdminLink;
import io.github.mobtalk.server.DialogueService;
import io.github.mobtalk.server.PlayerBudget;
import io.github.mobtalk.server.PlayerBudgetImpl;
import io.github.mobtalk.server.PlayerSettingsStore;
import io.github.mobtalk.server.ServerConfig;
import io.github.mobtalk.server.web.AdminAuth;
import io.github.mobtalk.server.web.AdminWeb;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Сквозной самотест выделенного сервера с двумя живыми клиентами (трек G, раздел 16.4 плана): {@code -Dmobtalk.selftest.play=Test1,Test2}.
 * Сервер — дирижёр: строит площадку, призывает жителей и шлёт клиентам реплики сценария ({@link SelfTestLine}: «скажи», «подари»,
 * «торгуйся», «помолись», «команда», «выйди»); клиенты ({@code SelfTestServer}, {@code -Dmobtalk.selftest.play.client}) делают это
 * своими настоящими пакетами и докладывают, что увидели (реплики, состояние, статусы, чат, экраны). Первый ник — стример, второй —
 * игрок, оператор — сам сервер. Итог {@code [selftest-play] ALL CHECKS PASSED} или FAIL с причинами; в конце сервер останавливается. */
final class ServerSelfTestPlay {
    static final String PROPERTY = "mobtalk.selftest.play";
    /** {@code -Dmobtalk.selftest.play.only=cards}: только игра в 21 за одним столом (этап 6 docs/cards-21.md), без диалогов и голоса. */
    private static final boolean ONLY_CARDS = "cards".equals(System.getProperty("mobtalk.selftest.play.only"));
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-selftest-play");
    private static final int Y = 200;
    private static ServerSelfTestPlay running;

    /** Что клиент увидел: вид ({@code speech}, {@code state}, {@code status}, {@code chat}, …), поля через табуляцию, тик сервера. */
    record Seen(String kind, String[] fields, int tick) {
        String field(int i) { return i < fields.length ? fields[i] : ""; }
    }

    private final MinecraftServer server;
    private final MobGameBridge bridge;
    private final String streamerName, playerName;
    private final Map<String, List<Seen>> seen = new HashMap<>();
    private final List<String> failures = new ArrayList<>();
    private int ticks, deadline, wholeDeadline, cx, cz, spoke;
    private String waitingFor = "";
    private BooleanSupplier until = () -> true;
    private Runnable then = () -> { };
    private boolean finished, finishing;
    private UUID streamer, player;
    private VillagerEntity talker, trader, chatter, bride, cleric;
    private final List<VillagerEntity> guests = new ArrayList<>();
    private String adminCookie = "", adminUrl = "";

    private ServerSelfTestPlay(MinecraftServer server, MobGameBridge bridge, String streamerName, String playerName) {
        this.server = server; this.bridge = bridge; this.streamerName = streamerName; this.playerName = playerName;
    }

    /** Из {@code MobTalkMod.onInitialize}: приёмник докладов клиентов и тик; без свойства ничего не делает. */
    static void register() {
        if (System.getProperty(PROPERTY, "").isBlank()) return;
        ServerPlayNetworking.registerGlobalReceiver(SelfTestLine.ID, (payload, context) -> {
            ServerSelfTestPlay test = running;
            if (test != null) test.report(context.player().getGameProfile().getName(), payload.line());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { ServerSelfTestPlay test = running; if (test != null && test.server == server) test.tick(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerSelfTestPlay test = running;
            if (test != null) test.report(handler.getPlayer().getGameProfile().getName(), "left");
        });
    }

    /** Мост поднят: ждём обоих игроков и начинаем. */
    static void start(MinecraftServer server, MobGameBridge bridge) {
        String[] names = System.getProperty(PROPERTY, "").split(",");
        if (names.length < 2) { LOGGER.error("[selftest-play] FAIL нужно два ника через запятую в -D{}", PROPERTY); return; }
        ServerSelfTestPlay test = new ServerSelfTestPlay(server, bridge, names[0].strip(), names[1].strip());
        running = test;
        test.log("ждём игроков " + test.streamerName + " (стример) и " + test.playerName + " (игрок)");
        test.wholeDeadline = 20 * 60 * 35;
        test.await("оба игрока на сервере", 900, () -> test.online(test.streamerName) != null && test.online(test.playerName) != null
                && test.saw(test.streamerName, "features", 0, s -> true) && test.saw(test.playerName, "features", 0, s -> true), test::setup);
    }

    // ---- Каркас: журнал, ожидание, доклады, реплики. ----

    private void log(String text) { LOGGER.info("[selftest-play] {}", text); }
    private void check(boolean ok, String what) {
        if (ok) log("OK: " + what);
        else { failures.add(what); LOGGER.error("[selftest-play] FAIL: {}", what); }
    }
    private void await(String what, int seconds, BooleanSupplier condition, Runnable next) {
        waitingFor = what; until = condition; then = next; deadline = ticks + seconds * 20;
    }
    private void pause(int seconds, Runnable next) { await("пауза", seconds, () -> false, next); }

    private void tick() {
        if (finished) return;
        ticks++;
        if (ticks > wholeDeadline && !finishing) { check(false, "самотест не уложился в 35 минут (ждали: " + waitingFor + ")"); finish(); return; }
        if (waitingFor.isEmpty()) return;
        if (until.getAsBoolean()) { String done = waitingFor; waitingFor = ""; log("… " + done); then.run(); }
        else if (ticks >= deadline) {
            String what = waitingFor; waitingFor = "";
            if (what.startsWith("пауза")) then.run();
            else { log("… время вышло: " + what); then.run(); }
        }
    }

    private synchronized void report(String nick, String line) {
        if (line == null) return;
        String[] parts = line.split("\t", -1);
        String kind = parts[0];
        String[] fields = java.util.Arrays.copyOfRange(parts, 1, parts.length);
        seen.computeIfAbsent(nick, ignored -> new ArrayList<>()).add(new Seen(kind, fields, ticks));
        if (!kind.equals("state")) log(nick + " видит " + line.replace('\t', ' '));
    }
    private synchronized int mark(String nick) { return seen.getOrDefault(nick, List.of()).size(); }
    private synchronized List<Seen> since(String nick, String kind, int from) {
        List<Seen> all = seen.getOrDefault(nick, List.of());
        List<Seen> out = new ArrayList<>();
        for (int i = Math.min(from, all.size()); i < all.size(); i++) if (kind == null || all.get(i).kind().equals(kind)) out.add(all.get(i));
        return out;
    }
    private boolean saw(String nick, String kind, int from, Predicate<Seen> match) { return since(nick, kind, from).stream().anyMatch(match); }
    private Seen first(String nick, String kind, int from, Predicate<Seen> match) { return since(nick, kind, from).stream().filter(match).findFirst().orElse(null); }

    private ServerPlayerEntity online(String nick) { return server.getPlayerManager().getPlayer(nick); }
    /** Реплика сценария клиенту: поля через табуляцию. */
    private void cue(String nick, String... fields) {
        ServerPlayerEntity target = online(nick);
        if (target == null) { log("реплика " + String.join(" ", fields) + " не ушла: " + nick + " не на сервере"); return; }
        log("→ " + nick + ": " + String.join(" ", fields));
        ServerPlayNetworking.send(target, new SelfTestLine(String.join("\t", fields)));
    }
    private void command(String line) {
        server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent().withLevel(4), line);
    }
    private ServerWorld world() { return server.getOverworld(); }
    private DialogueService dialogue() { return MobTalkMod.dialogue(); }
    private String at(int dx, int dy, int dz) { return (cx + dx) + " " + (Y + dy) + " " + (cz + dz); }

    /** Реплика моба: {@code speech} toYou kind mob speaker text heard asker. */
    private static Predicate<Seen> speechOf(VillagerEntity mob, boolean toYou, String kind) {
        return s -> s.field(2).equals(mob.getUuidAsString()) && (s.field(0).equals(String.valueOf(toYou)))
                && (kind == null || s.field(1).equals(kind)) && !s.field(4).isEmpty();
    }
    private static Predicate<Seen> anySpeechOf(VillagerEntity mob) { return s -> s.field(2).equals(mob.getUuidAsString()) && !s.field(4).isEmpty(); }
    private static Predicate<Seen> contains(int field, String... words) {
        return s -> { String text = s.field(field).toLowerCase(Locale.ROOT); for (String w : words) if (text.contains(w.toLowerCase(Locale.ROOT))) return true; return false; };
    }
    private String lastAnswer(String nick, VillagerEntity mob, int from) {
        List<Seen> answers = since(nick, "speech", from).stream().filter(speechOf(mob, true, null)).toList();
        return answers.isEmpty() ? "" : answers.getLast().field(4);
    }

    private VillagerEntity villager(int dx, int dz, VillagerProfession profession, float yaw) {
        VillagerEntity villager = EntityType.VILLAGER.create(world());
        if (villager == null) throw new IllegalStateException("житель не создан");
        villager.refreshPositionAndAngles(cx + dx + 0.5, Y, cz + dz + 0.5, yaw, 0);
        villager.setAiDisabled(true);
        villager.setPersistent();
        villager.setVillagerData(villager.getVillagerData().withProfession(profession).withLevel(2));
        world().spawnEntity(villager);
        return villager;
    }
    private void place(String nick, int dx, int dz, float yaw) { command("tp " + nick + " " + at(dx, 0, dz) + " " + yaw + " 10"); }

    // ---- Старт: настройки, уровни, площадка, админка. ----

    private void setup() {
        streamer = online(streamerName).getUuid();
        player = online(playerName).getUuid();
        // Без платной озвучки и с серверным распознаванием; лимитов нет, деревня сама никого не женит.
        ServerConfig.update(config -> {
            config.ttsProvider = "openrouter"; config.sttServer = true; config.familyLife = true; config.weddingChance = 0;
            config.rivalGatherMinutes = 1; // Соперники: к колоколу через минуту, а не через пять.
            config.budgetPlayerPerMinute = 0; config.budgetServerPerMinute = 0; config.budgetPlayerDailyUsd = 0; config.budgetServerDailyUsd = 0;
            return config;
        });
        ServerConfig.setAdmins(java.util.Set.of());
        int[] marks = {mark(streamerName), mark(playerName)};
        ServerConfig.setStreamer(streamer);
        BlockPos spawn = world().getSpawnPos();
        cx = spawn.getX(); cz = spawn.getZ();
        log("площадка над точкой появления " + cx + " " + Y + " " + cz);
        command("gamerule doDaylightCycle false"); command("gamerule doWeatherCycle false"); command("gamerule doMobSpawning false");
        command("time set 1000"); command("weather clear");
        command("forceload add " + (cx - 32) + " " + (cz - 32) + " " + (cx + 32) + " " + (cz + 32));
        command("fill " + at(-12, -1, -12) + " " + at(12, -1, 30) + " minecraft:smooth_stone");
        command("fill " + at(-12, 0, -12) + " " + at(12, 6, 30) + " minecraft:air");
        command("fill " + at(-12, 0, -12) + " " + at(12, 1, 30) + " minecraft:barrier outline");
        command("fill " + at(-11, 0, -11) + " " + at(11, 1, 29) + " minecraft:air");
        for (String nick : List.of(streamerName, playerName)) {
            command("gamemode survival " + nick); command("clear " + nick);
            command("effect give " + nick + " minecraft:resistance 1800 255 true");
            command("effect give " + nick + " minecraft:saturation 1800 1 true");
        }
        place(streamerName, -1, 0, 180); place(playerName, 1, 0, 180);
        talker = villager(0, -3, VillagerProfession.FARMER, 0);
        trader = villager(3, -3, VillagerProfession.LIBRARIAN, 0);
        chatter = villager(-3, -3, VillagerProfession.FISHERMAN, 0);
        log("жители: собеседник " + talker.getId() + ", торговец " + trader.getId() + ", болтун " + chatter.getId());
        // Пары игрок—мир в stream 1: игрок может быть изгнанником деревни внизу; площадке это не мешает, но прощение не повредит.
        for (UUID id : List.of(streamer, player)) log("прощение: " + bridge.memory.forgiveVillage(world().getRegistryKey().getValue().toString(), cx, cz, id));
        CompletableFuture<String> admin = CompletableFuture.supplyAsync(this::adminCheck);
        await("уровни STREAMER и PLAYER пришли клиентам", 20, () -> saw(streamerName, "features", marks[0], s -> s.field(0).equals("STREAMER"))
                && saw(playerName, "features", marks[1], s -> s.field(0).equals("PLAYER")), () -> {
            check(saw(streamerName, "features", marks[0], s -> s.field(0).equals("STREAMER")), streamerName + " получил ServerFeaturesS2C с уровнем STREAMER");
            check(saw(playerName, "features", marks[1], s -> s.field(0).equals("PLAYER")), playerName + " получил ServerFeaturesS2C с уровнем PLAYER");
            check(since(streamerName, "features", 0).stream().anyMatch(s -> s.field(1).equals("true")), "сервер объявил диалог");
            await("админка ответила", 30, admin::isDone, () -> {
                check(!admin.isCompletedExceptionally() && admin.join().startsWith("200"), "админка: вход по паролю и GET /api/sources (" + (admin.isCompletedExceptionally() ? admin.exceptionNow() : admin.join()) + ")");
                await("клиенты видят жителей", 20, () -> sees(streamerName, talker, trader, chatter) && sees(playerName, talker, trader, chatter), () -> {
                    // -Dmobtalk.selftest.play.only=confession: только исповедь (Б4); =cards: только игра в 21.
                    if ("confession".equals(System.getProperty(PROPERTY + ".only", ""))) confession(this::finish); else if (ONLY_CARDS) cards(); else dialogue1();
                });
                cue(streamerName, "find", ids(talker, trader, chatter)); cue(playerName, "find", ids(talker, trader, chatter));
            });
        });
    }
    private static String ids(VillagerEntity... mobs) { return String.join(",", java.util.Arrays.stream(mobs).map(m -> String.valueOf(m.getId())).toList()); }
    private boolean sees(String nick, VillagerEntity... mobs) { return saw(nick, "found", 0, s -> s.field(0).equals(ids(mobs))); }

    /** Админка: новый пароль этой копии сервера, вход и {@code GET /api/sources}; самоподписанный сертификат принимается только здесь. */
    private String adminCheck() {
        try {
            AdminWeb web = AdminLink.web();
            if (web == null) return "админка не поднята";
            String password = AdminAuth.generatePassword();
            web.setPassword(password);
            adminUrl = web.describe().getFirst().replaceAll("/$", "");
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            HttpClient http = HttpClient.newBuilder().sslContext(ssl).build();
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(adminUrl + "/api/login")).header("X-MobTalk", "1")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + password + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie").orElse("");
            adminCookie = cookie.contains(";") ? cookie.substring(0, cookie.indexOf(';')) : cookie;
            HttpResponse<String> sources = adminGet(http, "/api/sources");
            return sources.statusCode() + " login " + login.statusCode() + ", " + sources.body().length() + " байт";
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private HttpResponse<String> adminGet(HttpClient http, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(adminUrl + path)).header("Cookie", adminCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private CompletableFuture<String> adminHistory() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[] {new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, null);
                HttpResponse<String> history = adminGet(HttpClient.newBuilder().sslContext(ssl).build(), "/api/history");
                return history.statusCode() + " " + history.body();
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }

    // ---- Игра в 21: двое за одним столом, ход по местам, новичок посреди раунда, зритель (этап 6). ----

    private io.github.mobtalk.server.CardTableService table() { return io.github.mobtalk.game.CardTables.service(); }
    private int emeralds(String nick) { ServerPlayerEntity p = online(nick); return p == null ? 0 : p.getInventory().count(net.minecraft.item.Items.EMERALD); }
    private int purse() { return bridge.memory.cards().balance(talker.getUuid()); }
    private boolean cardSeen(String nick, int from, Predicate<Seen> match) { return saw(nick, "card", from, match); }
    private static Predicate<Seen> phase(String phase) { return s -> s.field(0).equals(phase); }

    private void cards() {
        log("игра в 21: двое за одним столом жителя " + talker.getId());
        ServerConfig.update(config -> { config.cards = true; config.cardsSeats = 4; config.cardsCheating = false; config.cardsDailyLoss = 0; return config; });
        for (String nick : List.of(streamerName, playerName)) { command("clear " + nick + " minecraft:emerald"); command("give " + nick + " minecraft:emerald 30"); }
        pause(2, () -> {
            int s = mark(streamerName), p = mark(playerName);
            String a = table().open(streamer, talker.getId(), ""), b = table().open(player, talker.getId(), "");
            check(a.isEmpty() && b.isEmpty(), "оба игрока сели за стол одного жителя (" + a + b + ")");
            int sum = emeralds(streamerName) + emeralds(playerName) + purse();
            await("оба видят стол на своих местах", 15, () -> saw(streamerName, "cardopen", s, x -> x.field(0).equals("0")) && saw(playerName, "cardopen", p, x -> x.field(0).equals("1")), () -> {
                check(saw(streamerName, "cardopen", s, x -> x.field(0).equals("0")) && saw(playerName, "cardopen", p, x -> x.field(0).equals("1")), "места: " + streamerName + " — 0, " + playerName + " — 1");
                cue(streamerName, "card", "bet", "3"); cue(playerName, "card", "bet", "2");
                // «Раздать» первого не раздаёт, пока второй не готов: оба нажимают, раздача — когда готовы все.
                cue(streamerName, "card", "deal");
                pause(2, () -> cue(playerName, "card", "deal"));
                await("раздача: ходит место 0", 25, () -> cardSeen(streamerName, s, x -> x.field(0).equals("PLAYERS") && x.field(5).equals("0"))
                        && cardSeen(playerName, p, x -> x.field(0).equals("PLAYERS") && x.field(5).equals("0")), () -> {
                    check(cardSeen(playerName, p, x -> x.field(3).equals("2")), playerName + " видит за столом двоих");
                    check(cardSeen(playerName, p, x -> x.field(0).equals("PLAYERS") && x.field(8).equals("1")), "раздача досталась и " + playerName + " (у него рука), первым ходит " + streamerName);
                    int p2 = mark(playerName);
                    cue(playerName, "card", "hit");
                    await("не свой ход отклонён", 10, () -> cardSeen(playerName, p2, x -> x.field(7).contains("Не ваш ход")), () -> {
                        check(cardSeen(playerName, p2, x -> x.field(7).contains("Не ваш ход")), playerName + " не может ходить за " + streamerName + ": «Не ваш ход»");
                        cue(streamerName, "shot", "mobtalk-server-cards-" + streamerName + ".png"); cue(playerName, "shot", "mobtalk-server-cards-" + playerName + ".png");
                        cue(streamerName, "card", "stand");
                        await("ход перешёл к месту 1", 10, () -> cardSeen(playerName, p2, x -> x.field(0).equals("PLAYERS") && x.field(5).equals("1")), () -> {
                            check(cardSeen(playerName, p2, x -> x.field(5).equals("1")), "после «хватит» " + streamerName + " ход перешёл к " + playerName);
                            cue(playerName, "card", "stand");
                            await("расчёт у обоих", 10, () -> cardSeen(streamerName, s, phase("SETTLED")) && cardSeen(playerName, p2, phase("SETTLED")), () -> {
                                check(cardSeen(streamerName, s, phase("SETTLED")) && cardSeen(playerName, p2, phase("SETTLED")), "житель доиграл, расчёт пришёл обоим");
                                check(emeralds(streamerName) + emeralds(playerName) + purse() == sum, "изумруды только переходили: " + emeralds(streamerName) + " + " + emeralds(playerName) + " + банк " + purse() + " = " + sum);
                                newcomer(sum);
                            });
                        });
                    });
                });
            });
        });
    }

    /** Новичок садится посреди раунда: рук у него нет до следующей раздачи. */
    private void newcomer(int sum) {
        cue(playerName, "cardleave");
        int s = mark(streamerName);
        await("новая раздача для ставок", 30, () -> cardSeen(streamerName, s, phase("BETTING")), () -> {
            cue(streamerName, "card", "bet", "2"); cue(streamerName, "card", "deal");
            await("раунд идёт", 15, () -> cardSeen(streamerName, s, phase("PLAYERS")), () -> {
                int p = mark(playerName);
                String b = table().open(player, talker.getId(), "");
                check(b.isEmpty(), playerName + " снова садится посреди раунда: " + b);
                await("новичок видит раунд без своих карт", 10, () -> cardSeen(playerName, p, x -> x.field(0).equals("PLAYERS") && x.field(2).equals("1") && x.field(8).equals("0")), () -> {
                    check(cardSeen(playerName, p, x -> x.field(0).equals("PLAYERS") && x.field(8).equals("0")), "новичок сел посреди раунда: место есть, карт до следующей раздачи нет");
                    cue(streamerName, "card", "stand");
                    await("следующая раздача: новичок может ставить", 30, () -> cardSeen(playerName, p, phase("BETTING")), () -> {
                        check(cardSeen(playerName, p, phase("BETTING")), "со следующей раздачи новичок играет наравне");
                        check(emeralds(streamerName) + emeralds(playerName) + purse() == sum, "после второй раздачи изумрудов столько же: " + sum);
                        cue(streamerName, "cardleave"); cue(playerName, "cardleave");
                        await("стол закрыт", 10, () -> !table().playing(talker.getUuid()), this::watcher);
                    });
                });
            });
        });
    }

    /** Одно место: второй смотрит без кнопок, видит раздачу; ушёл сидящий — зритель садится. */
    private void watcher() {
        ServerConfig.update(config -> { config.cardsSeats = 1; return config; });
        pause(1, () -> {
            int s = mark(streamerName), p = mark(playerName);
            table().open(streamer, talker.getId(), "");
            table().open(player, talker.getId(), "");
            await("второй — зритель", 10, () -> saw(playerName, "cardopen", p, x -> x.field(0).equals("-1")), () -> {
                check(saw(playerName, "cardopen", p, x -> x.field(0).equals("-1")), playerName + " при одном месте смотрит (место −1)");
                cue(streamerName, "card", "bet", "1"); cue(streamerName, "card", "deal");
                await("зритель видит раздачу", 15, () -> cardSeen(playerName, p, x -> x.field(0).equals("PLAYERS") && x.field(2).equals("-1")), () -> {
                    check(cardSeen(playerName, p, x -> x.field(0).equals("PLAYERS") && x.field(2).equals("-1")), "зритель видит раздачу сидящего");
                    cue(playerName, "shot", "mobtalk-server-cards-watcher-" + playerName + ".png");
                    cue(streamerName, "card", "stand");
                    await("расчёт", 10, () -> cardSeen(playerName, p, phase("SETTLED")), () -> {
                        cue(streamerName, "cardleave");
                        int p2 = mark(playerName);
                        await("место освободилось", 10, () -> cardSeen(playerName, p2, x -> x.field(3).equals("0")), () -> {
                            cue(playerName, "card", "sit");
                            await("зритель сел", 10, () -> cardSeen(playerName, p2, x -> x.field(2).equals("0")), () -> {
                                check(cardSeen(playerName, p2, x -> x.field(2).equals("0")), "место освободилось — зритель сел за стол");
                                cue(playerName, "cardleave");
                                ServerConfig.update(config -> { config.cardsSeats = 4; return config; });
                                await("стол закрыт", 10, () -> !table().playing(talker.getUuid()), this::finish);
                            });
                        });
                    });
                });
            });
        });
    }
    // ---- Диалог: стример спрашивает, оба слышат; журнал админки. ----

    private void dialogue1() {
        int s = mark(streamerName), p = mark(playerName);
        cue(streamerName, "say", String.valueOf(talker.getId()), "Привет! Меня зовут " + streamerName + ". Как тебя зовут и чем ты занимаешься?");
        await("ответ собеседника стримеру", 120, () -> saw(streamerName, "speech", s, speechOf(talker, true, null)), () -> {
            check(saw(streamerName, "speech", s, speechOf(talker, true, null)), "ответ пришёл спросившему с toYou: «" + lastAnswer(streamerName, talker, s) + "»");
            spoke++;
            await("сосед слышит ту же реплику", 10, () -> saw(playerName, "speech", p, speechOf(talker, false, null)), () -> {
                Seen heard = first(playerName, "speech", p, speechOf(talker, false, null));
                check(heard != null, playerName + " слышит реплику жителя без toYou");
                check(heard != null && heard.field(6).equals(streamerName), playerName + " видит, кто спросил (asker " + (heard == null ? "—" : heard.field(6)) + ")");
                check(!saw(playerName, "speech", p, speechOf(talker, true, null)), "у соседа реплика не помечена «вам»");
                check(saw(streamerName, "state", s, st -> st.field(0).equals(talker.getUuidAsString())), streamerName + " получил MobStateS2C собеседника (HUD)");
                Predicate<Seen> named = st -> st.field(0).equals(talker.getUuidAsString()) && !st.field(1).isEmpty();
                await("подпись с именем у обоих", 10, () -> saw(streamerName, "label", 0, named) && saw(playerName, "label", 0, named), () -> {
                check(saw(streamerName, "label", 0, named) && saw(playerName, "label", 0, named), "подпись с именем жителя пришла обоим (MobLabelS2C): "
                        + (first(playerName, "label", 0, named) == null ? "нет у " + playerName : first(playerName, "label", 0, named).field(1)));
                cue(streamerName, "shot", "mobtalk-server-dialogue-" + streamerName + ".png"); cue(playerName, "shot", "mobtalk-server-dialogue-" + playerName + ".png");
                CompletableFuture<String> history = adminHistory();
                await("история админки", 20, history::isDone, () -> {
                    String body = history.isCompletedExceptionally() ? "ошибка " + history.exceptionNow() : history.join();
                    check(body.startsWith("200") && body.contains(streamerName), "журнал админки записал диалог (" + body.substring(0, Math.min(140, body.length())).replace('\n', ' ') + "…)");
                    busy();
                });
                });
            });
        });
    }

    // ---- Занятый моб: второй игрок получает «сейчас говорит с …». ----

    private void busy() {
        int s = mark(streamerName), p = mark(playerName);
        cue(streamerName, "say", String.valueOf(talker.getId()), "Расскажи в двух словах, что нового в деревне?");
        await("стример занял собеседника", 15, () -> streamer.equals(dialogue().holderOf(talker.getUuid())), () -> {
            check(streamer.equals(dialogue().holderOf(talker.getUuid())), "на сервере собеседник занят стримером");
            cue(playerName, "say", String.valueOf(talker.getId()), "Эй, а со мной поговоришь?");
            await("игрок получил «занят»", 15, () -> saw(playerName, "status", p, contains(2, "сейчас говорит с")), () -> {
                Seen busy = first(playerName, "status", p, contains(2, "сейчас говорит с"));
                check(busy != null, playerName + " получил отказ «занят»: " + (busy == null ? "нет" : busy.field(2)));
                await("стример дождался ответа", 120, () -> saw(streamerName, "speech", s, speechOf(talker, true, null)), () -> {
                    check(saw(streamerName, "speech", s, speechOf(talker, true, null)), "стример получил ответ, несмотря на попытку соседа");
                    check(!saw(playerName, "speech", p, speechOf(talker, true, null)), "игроку моб не отвечал, пока был занят");
                    gift();
                });
            });
        });
    }

    // ---- Подарок: отношение растёт у дарителя, реакцию видят оба. ----

    private void gift() {
        int before1 = bridge.memory.relationship(talker.getUuid(), streamer), before2 = bridge.memory.relationship(talker.getUuid(), player);
        int s = mark(streamerName), p = mark(playerName);
        command("item replace entity " + playerName + " weapon.mainhand with minecraft:emerald 3");
        pause(1, () -> {
            cue(playerName, "gift", String.valueOf(talker.getId()));
            await("отношение выросло у дарителя", 30, () -> bridge.memory.relationship(talker.getUuid(), player) > before2, () -> {
                int after2 = bridge.memory.relationship(talker.getUuid(), player), after1 = bridge.memory.relationship(talker.getUuid(), streamer);
                check(after2 > before2, "подарок поднял отношение к " + playerName + ": " + before2 + " → " + after2);
                check(after1 == before1, "отношение к " + streamerName + " не изменилось: " + before1 + " → " + after1);
                check(saw(playerName, "chat", p, contains(0, "подар", "принял", "взял", "[Mob Talk]")), playerName + " получил строку о подарке");
                await("реакция на подарок у обоих", 60, () -> saw(playerName, "speech", p, anySpeechOf(talker)) && saw(streamerName, "speech", s, anySpeechOf(talker)), () -> {
                    check(saw(playerName, "speech", p, anySpeechOf(talker)), "реакция жителя на подарок у дарителя: «" + text(first(playerName, "speech", p, anySpeechOf(talker))) + "»");
                    check(saw(streamerName, "speech", s, anySpeechOf(talker)), "реакцию на подарок слышит и " + streamerName);
                    trade();
                });
            });
        });
    }
    private static String text(Seen speech) { return speech == null ? "" : speech.field(4); }

    // ---- Торг: окно сделки у игрока, слова в торге, индикатор торга. ----

    private void trade() {
        int p = mark(playerName);
        cue(playerName, "trade", String.valueOf(trader.getId()));
        await("окно сделки открылось у игрока", 20, () -> saw(playerName, "screen", p, s -> s.field(0).contains("Merchant")), () -> {
            check(saw(playerName, "screen", p, s -> s.field(0).contains("Merchant")), "окно сделки открылось у " + playerName);
            bargain(1);
        });
    }
    /** Слова в торге: индикатор торга появляется, когда торговец сдвинулся (или упёрся); если первый ответ торг не начал — вторая просьба. */
    private void bargain(int attempt) {
        int q = mark(playerName);
        cue(playerName, "say", String.valueOf(trader.getId()), attempt == 1 ? "Уступи в цене, пожалуйста! Я у тебя постоянный покупатель, сделай скидку."
                : "Давай поторгуемся: самую дорогую сделку отдай на пару изумрудов дешевле, и я возьму сразу.");
        await("ответ торговца " + attempt, 120, () -> saw(playerName, "speech", q, speechOf(trader, true, null)), () -> {
            check(saw(playerName, "speech", q, speechOf(trader, true, null)), "торговец ответил в окне сделки: «" + lastAnswer(playerName, trader, q) + "»");
            Predicate<Seen> moved = st -> st.field(0).equals(trader.getUuidAsString()) && !st.field(2).isEmpty();
            await("индикатор торга", 8, () -> saw(playerName, "state", 0, moved), () -> {
                Seen state = first(playerName, "state", 0, moved);
                if (state == null && attempt < 2) { log("торговец не начал торг, просим ещё раз"); bargain(attempt + 1); return; }
                check(state != null, "индикатор торга пришёл в MobStateS2C: " + (state == null ? "пусто после двух просьб" : state.field(2)));
                cue(playerName, "shot", "mobtalk-server-trade-" + playerName + ".png");
                pause(2, () -> { cue(playerName, "close"); pause(1, this::rumour); });
            });
        });
    }

    // ---- Имена в слухах: игрок бьёт жителя, стример спрашивает, кто обидел. ----

    private void rumour() {
        command("damage " + talker.getUuidAsString() + " 1 minecraft:player_attack by " + playerName);
        await("житель запомнил обидчика", 10, () -> bridge.memory.events(talker.getUuid()).stream().anyMatch(e -> e.contains(playerName)), () -> {
            List<String> events = bridge.memory.events(talker.getUuid());
            check(events.stream().anyMatch(e -> e.contains(playerName)), "в памяти жителя событие с ником " + playerName + ": " + (events.isEmpty() ? "нет" : events.getLast()));
            command("effect give " + talker.getUuidAsString() + " minecraft:instant_health 1 5 true");
            int s = mark(streamerName);
            cue(streamerName, "say", String.valueOf(talker.getId()), "Кто тебя сейчас ударил? Назови его имя.");
            await("ответ про обидчика", 120, () -> saw(streamerName, "speech", s, speechOf(talker, true, null)), () -> {
                String answer = lastAnswer(streamerName, talker, s);
                check(answer.toLowerCase(Locale.ROOT).contains(playerName.toLowerCase(Locale.ROOT)) || answer.toLowerCase(Locale.ROOT).contains("тест2"),
                        "житель назвал обидчика по нику: «" + answer + "»");
                limit();
            });
        });
    }

    // ---- Лимит: три реплики в минуту, четвёртая — отказ с причиной. ----

    private void limit() {
        PlayerBudgetImpl budget = MobTalkMod.budget() instanceof PlayerBudgetImpl impl ? impl : null;
        if (budget == null) { check(false, "служба расходов не PlayerBudgetImpl: лимит не проверить"); voice(); return; }
        budget.setLimit(player.toString(), PlayerBudget.Kind.DIALOGUE, 3, 0);
        limitTry(budget, 1);
    }
    private void limitTry(PlayerBudgetImpl budget, int attempt) {
        int p = mark(playerName);
        cue(playerName, "say", String.valueOf(chatter.getId()), "Как дела? Вопрос номер " + attempt + ".");
        await("реплика " + attempt + " под лимитом", 120, () -> saw(playerName, "speech", p, speechOf(chatter, true, null))
                || saw(playerName, "status", p, contains(2, "Слишком часто")), () -> {
            Seen refused = first(playerName, "status", p, contains(2, "Слишком часто"));
            if (refused != null || attempt >= 4) {
                check(refused != null, "лимит 3 в минуту отказал на реплике " + attempt + " с причиной: " + (refused == null ? "отказа нет" : refused.field(2)));
                budget.clearLimit(player.toString(), PlayerBudget.Kind.DIALOGUE);
                rights();
            } else pause(1, () -> limitTry(budget, attempt + 1));
        });
    }

    // ---- Права: команды модов у игрока и стримера. ----

    private void rights() {
        int s = mark(streamerName), p = mark(playerName);
        cue(playerName, "cmd", "divineblock list");
        cue(streamerName, "cmd", "divineblock list");
        pause(3, () -> {
            int s2 = mark(streamerName), p2 = mark(playerName);
            cue(playerName, "cmd", "mobtalk admin");
            cue(streamerName, "cmd", "mobtalk admin");
            pause(4, () -> {
                Predicate<Seen> refusal = contains(0, "доступна", "Unknown", "Неизвестная", "incomplete", "не найдена");
                check(between(playerName, p, p2).stream().anyMatch(refusal), "/divineblock list у игрока — отказ: " + chats(playerName, p));
                check(between(streamerName, s, s2).stream().anyMatch(contains(0, "Деяния:")), "/divineblock list у стримера — список: " + chats(streamerName, s));
                check(saw(playerName, "chat", p2, refusal), "/mobtalk admin у игрока — отказ: " + chats(playerName, p2));
                check(saw(streamerName, "chat", s2, refusal), "/mobtalk admin у стримера — отказ: " + chats(streamerName, s2));
                voice();
            });
        });
    }
    private synchronized List<Seen> between(String nick, int from, int to) {
        List<Seen> all = seen.getOrDefault(nick, List.of());
        return all.subList(Math.min(from, all.size()), Math.min(to, all.size())).stream().filter(x -> x.kind().equals("chat")).toList();
    }
    private String chats(String nick, int from) {
        List<String> lines = since(nick, "chat", from).stream().map(s -> s.field(0)).toList();
        return lines.isEmpty() ? "чат пуст" : String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
    }

    // ---- Голос без Whisper: аудио фикстуры распознаёт сервер. ----

    private void voice() {
        int s = mark(streamerName);
        cue(streamerName, "audio", String.valueOf(talker.getId()));
        await("ответ на голос через сервер", 120, () -> saw(streamerName, "speech", s, speechOf(talker, true, null)) || saw(streamerName, "audio-skip", s, x -> true)
                || saw(streamerName, "status", s, st -> !st.field(1).isEmpty()), () -> {
            if (saw(streamerName, "audio-skip", s, x -> true)) { log("голос без Whisper пропущен: " + first(streamerName, "audio-skip", s, x -> true).field(0)); prayer(); return; }
            Seen answer = first(streamerName, "speech", s, speechOf(talker, true, null));
            check(answer != null && !answer.field(5).isEmpty(), "сервер распознал аудио: «" + (answer == null ? chatsStatus(streamerName, s) : answer.field(5)) + "» → «" + text(answer) + "»");
            prayer();
        });
    }
    private String chatsStatus(String nick, int from) {
        List<Seen> statuses = since(nick, "status", from);
        return statuses.isEmpty() ? "нет ответа" : statuses.getLast().field(1) + " " + statuses.getLast().field(2);
    }

    // ---- Бог: молитва игрока у алтаря — вердикт только ему; мировая кара — у обоих. ----

    private void prayer() {
        place(playerName, 1, 4, 0);
        command("setblock " + at(1, 0, 6) + " divineblock:altar");
        pause(2, () -> {
            int s = mark(streamerName), p = mark(playerName);
            cue(playerName, "pray", (cx + 1) + "", Y + "", (cz + 6) + "", "Господи, я голоден, пошли мне немного хлеба, пожалуйста.");
            await("бог ответил игроку", 120, () -> since(playerName, "chat", p).stream().anyMatch(godLine(s)), () -> {
                List<String> god = since(playerName, "chat", p).stream().filter(godLine(s)).map(x -> x.field(0)).toList();
                check(!god.isEmpty(), "вердикт пришёл молившемуся: " + (god.isEmpty() ? "нет" : god.getFirst()));
                pause(8, () -> {
                    List<String> mine = since(playerName, "chat", p).stream().filter(godLine(s)).map(x -> x.field(0)).toList();
                    check(since(streamerName, "chat", s).stream().noneMatch(x -> mine.contains(x.field(0))), "стример не получил слов бога игроку (" + chats(streamerName, s) + ")");
                    worldWrath();
                });
            });
        });
    }
    /** Строка бога у игрока: в чате игрока, которой нет у стримера после {@code streamerMark}, и это не наш служебный чат. */
    private Predicate<Seen> godLine(int streamerMark) {
        return x -> !x.field(0).isBlank() && !x.field(0).startsWith("<") && !x.field(0).startsWith("[Mob Talk]")
                && since(streamerName, "chat", streamerMark).stream().noneMatch(y -> y.field(0).equals(x.field(0)));
    }
    private void worldWrath() {
        command("weather clear");
        command("op " + streamerName);
        pause(1, () -> {
            int s = mark(streamerName), p = mark(playerName);
            server.getCommandManager().executeWithPrefix(online(streamerName).getCommandSource(), "divineblock act acid_rain 2");
            command("deop " + streamerName);
            pause(4, () -> {
                cue(streamerName, "probe"); cue(playerName, "probe");
                await("оба доложили небо", 10, () -> saw(streamerName, "probe", s, x -> true) && saw(playerName, "probe", p, x -> true), () -> {
                    Seen a = first(streamerName, "probe", s, x -> true), b = first(playerName, "probe", p, x -> true);
                    check(a != null && a.field(0).equals("rain=true") && b != null && b.field(0).equals("rain=true"),
                            "мировая кара (кислотный дождь) у обоих: " + (a == null ? "—" : String.join(" ", a.fields())) + " / " + (b == null ? "—" : String.join(" ", b.fields())));
                    command("weather clear");
                    command("effect clear " + streamerName + " minecraft:poison"); command("effect clear " + playerName + " minecraft:poison");
                    place(playerName, 1, 0, 180);
                    pause(2, this::cancelOnTravel);
                });
            });
        });
    }

    // ---- Отмена: стример уходит в другой мир во время ответа. ----

    private void cancelOnTravel() {
        cue(streamerName, "say", String.valueOf(talker.getId()), "Расскажи подробную длинную историю о своей деревне, с самого начала.");
        await("стример занял собеседника", 15, () -> streamer.equals(dialogue().holderOf(talker.getUuid())), () -> {
            int moved = mark(streamerName);
            command("execute in minecraft:the_nether run tp " + streamerName + " 0 130 0");
            await("ответ не доставлен", 25, () -> false, () -> {
                check(!saw(streamerName, "speech", moved, speechOf(talker, true, MobSpeechS2C.ANSWER)), "ответ ушедшему в другой мир не доставлен");
                check(!dialogue().busyTalking(streamer), "сессия стримера не занята после смены мира");
                check(dialogue().holderOf(talker.getUuid()) == null, "собеседник освобождён");
                log("статусы стримера после смены мира: " + since(streamerName, "status", moved).stream().map(x -> x.field(2)).toList());
                command("execute in minecraft:overworld run tp " + streamerName + " " + at(-1, 0, 0) + " 180 10");
                pause(6, this::rivals);
            });
        });
    }

    // ---- Соперники (П3, docs/rivals.md): оба игрока ухаживают за одним жителем — уведомление, испытание с полосой, «⚔» при
    // взгляде, сдача предмета, кольцо при сильном сопернике и выбор у колокола. До свадьбы: побеждает Test2, стример остаётся свободен. ----

    private static final int RX = -6, RZ = 9;
    private VillagerEntity rival;
    private RivalryLedger rivalry() { return bridge.memory.rivalry(); }
    private RivalryLedger.TrialView rivalTrial() {
        return rival == null ? null : rivalry().trials(System.currentTimeMillis()).stream().filter(t -> t.villager().equals(rival.getUuid())).findFirst().orElse(null);
    }
    private boolean rivalScene() { return rival != null && rivalry().scenes(System.currentTimeMillis()).stream().anyMatch(s -> s.villager().equals(rival.getUuid())); }
    /** Сердечки к сопернику ровно до {@code target}: одним подарком, у обоих по одному (поправка жадного характера одинакова). */
    private void hearts(UUID id, String nick, int target, long now) {
        int delta = target - bridge.memory.relationship(rival.getUuid(), id);
        bridge.memory.social().applyGesture(rival.getUuid(), id, nick, new GestureIntent(Gesture.GIFT, Gesture.Outcome.BEST, delta, "minecraft:diamond", ""), now);
    }
    private static Predicate<Seen> overlay(String... words) { return contains(0, words); }
    /** Точка на площадке (внутри барьера) как можно ближе к {@code x, z}. */
    private String onStage(double x, double z) {
        return (Math.max(cx - 10.5, Math.min(cx + 10.5, x))) + " " + Y + " " + (Math.max(cz - 10.5, Math.min(cz + 28.5, z)));
    }

    private void rivals() {
        Gender g1 = Gender.ofPlayer(PlayerSettingsStore.of(streamer).applyTo(ServerConfig.get()).playerGender);
        Gender g2 = Gender.ofPlayer(PlayerSettingsStore.of(player).applyTo(ServerConfig.get()).playerGender);
        for (int i = 0; i < 24 && rival == null; i++) {
            VillagerEntity candidate = villager(RX, RZ, VillagerProfession.FARMER, 180);
            if (SocialProfile.drawnTo(candidate.getUuid(), g1) && SocialProfile.drawnTo(candidate.getUuid(), g2)) rival = candidate; else candidate.discard();
        }
        if (rival == null) { check(false, "нет жителя, которого тянет к обоим игрокам (" + g1 + ", " + g2 + ")"); wedding(); return; }
        command("setblock " + at(RX, 0, RZ + 4) + " minecraft:bell[attachment=floor,facing=north]");
        long now = System.currentTimeMillis();
        bridge.memory.recordEvent(rival.getUuid(), "minecraft:villager", "Житель", "Самотест: знакомство (соперники)", now);
        // Стример 90 (можно свататься), игрок 84: оба сильные соперники (80), стример пока впереди.
        hearts(streamer, streamerName, 88, now); hearts(player, playerName, 82, now);
        int s = mark(streamerName), p = mark(playerName);
        bridge.memory.social().applyGesture(rival.getUuid(), streamer, streamerName, new GestureIntent(Gesture.FLIRT, Gesture.Outcome.SUCCESS, 2, "", ""), now);
        bridge.memory.social().applyGesture(rival.getUuid(), player, playerName, new GestureIntent(Gesture.FLIRT, Gesture.Outcome.SUCCESS, 2, "", ""), now + 1);
        String name = bridge.memory.displayName(rival.getUuid());
        log("соперничество за " + name + ": " + streamerName + " " + bridge.memory.relationship(rival.getUuid(), streamer) + ", " + playerName + " "
                + bridge.memory.relationship(rival.getUuid(), player) + ", ухажёры " + rivalry().suitors(rival.getUuid(), now).stream().map(x -> x.name() + " " + x.score()).toList());
        command("tp " + streamerName + " " + at(RX - 1, 0, RZ - 3) + " 0 10"); command("tp " + playerName + " " + at(RX + 1, 0, RZ - 3) + " 0 10");
        await("оба узнали о сопернике", 15, () -> saw(streamerName, "overlay", s, overlay("соперник")) && saw(playerName, "overlay", p, overlay("соперник")), () -> {
            check(saw(streamerName, "overlay", s, overlay("соперник")) && saw(playerName, "overlay", p, overlay("соперник")),
                    "оба получили «у тебя соперник» над хотбаром: " + overlays(streamerName, s) + " / " + overlays(playerName, p));
            RivalryLedger.TrialView trial = rivalTrial();
            check(trial != null && trial.entries().size() == 2, "началось испытание ухажёров: " + (trial == null ? "нет" : trial.kind() + " " + trial.item() + " ×" + trial.count()));
            check(saw(streamerName, "overlay", s, overlay("испытание")) && saw(playerName, "overlay", p, overlay("испытание")), "оба получили строку об испытании");
            if (trial == null) { rivalsDone(); return; }
            cue(streamerName, "find", ids(rival)); cue(playerName, "find", ids(rival));
            // Подсказка «⚔» молчит, пока хотбар держит уведомление (3 с).
            await("клиенты видят соперника", 15, () -> sees(streamerName, rival) && sees(playerName, rival), () -> pause(4, () -> {
                int s2 = mark(streamerName), p2 = mark(playerName);
                cue(streamerName, "look", String.valueOf(rival.getId())); cue(playerName, "look", String.valueOf(rival.getId()));
                await("подсказка «⚔» у обоих", 10, () -> saw(streamerName, "overlay", s2, overlay("⚔")) && saw(playerName, "overlay", p2, overlay("⚔")), () -> {
                    check(saw(streamerName, "overlay", s2, overlay("⚔ " + playerName + " позади")), streamerName + " видит «⚔ " + playerName + " позади»: " + overlays(streamerName, s2));
                    check(saw(playerName, "overlay", p2, overlay("⚔ " + streamerName + " впереди")), playerName + " видит «⚔ " + streamerName + " впереди»: " + overlays(playerName, p2));
                    int s3 = mark(streamerName), p3 = mark(playerName);
                    cue(streamerName, "bars"); cue(playerName, "bars");
                    cue(streamerName, "shot", "mobtalk-server-rivals-" + streamerName + ".png"); cue(playerName, "shot", "mobtalk-server-rivals-" + playerName + ".png");
                    await("полосы у клиентов", 10, () -> saw(streamerName, "bars", s3, x -> true) && saw(playerName, "bars", p3, x -> true), () -> {
                        Seen a = first(streamerName, "bars", s3, x -> true), b = first(playerName, "bars", p3, x -> true);
                        check(a != null && a.field(0).contains(name) && b != null && b.field(0).contains(name),
                                "полоса испытания у обоих: «" + (a == null ? "—" : a.field(0)) + "» / «" + (b == null ? "—" : b.field(0)) + "»");
                        pause(2, () -> rivalDeliver(trial));
                    });
                });
            }));
        });
    }
    private String overlays(String nick, int from) {
        List<String> lines = since(nick, "overlay", from).stream().map(x -> x.field(0)).toList();
        return lines.isEmpty() ? "пусто" : String.join(" | ", lines.subList(Math.max(0, lines.size() - 4), lines.size()));
    }
    /** Игрок сдаёт весь предмет испытания из рук через контроллер: житель берёт, испытание закрыто победой. */
    private void rivalDeliver(RivalryLedger.TrialView trial) {
        int before = bridge.memory.relationship(rival.getUuid(), player);
        int s = mark(streamerName), p = mark(playerName);
        command("item replace entity " + playerName + " weapon.mainhand with " + trial.item() + " " + trial.count());
        ServerPlayerEntity target = online(playerName);
        net.minecraft.item.ItemStack stack = target.getMainHandStack();
        int had = stack.getCount();
        int taken = bridge.rivalry.deliver(rival, target, stack, false);
        check(taken == trial.count() && had - stack.getCount() == taken, "сдача для испытания засчитана: взято " + taken + " из " + trial.count() + " (в руке было " + had + ", осталось " + stack.getCount() + ")");
        check(rivalTrial() == null, "испытание закрыто победой " + playerName);
        int after = bridge.memory.relationship(rival.getUuid(), player);
        check(after > before, "победа в испытании подняла сердечки " + playerName + ": " + before + " → " + after);
        await("итог испытания у обоих", 10, () -> saw(playerName, "overlay", p, overlay("испытание твоё")) && saw(streamerName, "overlay", s, overlay("испытание выиграл")), () -> {
            check(saw(playerName, "overlay", p, overlay("испытание твоё")), playerName + " получил «испытание твоё»: " + overlays(playerName, p));
            check(saw(streamerName, "overlay", s, overlay("испытание выиграл")), streamerName + " узнал, кто выиграл: " + overlays(streamerName, s));
            rivalRing();
        });
    }
    /** Стример протягивает кольцо при сильном сопернике: житель не отвечает сразу и зовёт к колоколу; там выбирает лучшего по счёту. */
    private void rivalRing() {
        command("item replace entity " + streamerName + " weapon.mainhand with " + Romance.ENGAGEMENT_RING);
        int s = mark(streamerName), p = mark(playerName);
        cue(streamerName, "say", String.valueOf(rival.getId()), "Выходи за меня замуж! Вот кольцо, оно твоё.");
        await("ответ на кольцо", 120, () -> saw(streamerName, "speech", s, speechOf(rival, true, null)), () -> {
            boolean scheduled = rivalScene();
            check(scheduled, "кольцо при сильном сопернике назначило выбор у колокола: «" + lastAnswer(streamerName, rival, s) + "»");
            if (!scheduled) bridge.memory.social().applyGesture(rival.getUuid(), streamer, streamerName, new GestureIntent(Gesture.PROPOSE, Gesture.Outcome.REFUSED, 0,
                    Romance.ENGAGEMENT_RING, RivalryLedger.BELL_NOTE + ": самотест"), System.currentTimeMillis());
            if (!rivalScene()) { check(false, "выбор у колокола не назначен и вручную"); rivalsDone(); return; }
            await("оба позваны к колоколу", 10, () -> saw(streamerName, "overlay", s, overlay("у колокола")) && saw(playerName, "overlay", p, overlay("у колокола")), () -> {
                check(saw(streamerName, "overlay", s, overlay("у колокола")) && saw(playerName, "overlay", p, overlay("у колокола")),
                        "оба позваны к колоколу: " + overlays(streamerName, s) + " / " + overlays(playerName, p));
                // Все у колокола: житель на площади, оба ухажёра рядом — выбор без ожидания таймера (не дольше минуты).
                WeddingController.Venue venue = bridge.wedding.venue(world(), rival);
                net.minecraft.util.math.Vec3d plaza = venue == null ? rival.getPos() : venue.plaza();
                command("tp " + rival.getUuidAsString() + " " + plaza.x + " " + plaza.y + " " + plaza.z);
                // Площадь бывает у края площадки: ухажёры встают рядом, но на ней, иначе падают вниз и выбор ждёт таймера.
                command("tp " + streamerName + " " + onStage(plaza.x - 2, plaza.z - 2));
                command("tp " + playerName + " " + onStage(plaza.x + 2, plaza.z - 2));
                int s2 = mark(streamerName), p2 = mark(playerName);
                int loserBefore = bridge.memory.relationship(rival.getUuid(), streamer);
                long called = ticks;
                // Итог: выбор сделан, и уведомления (они уходят на следующей секунде) и строка в чат дошли до обоих.
                await("выбор у колокола", 80, () -> !rivalScene() && saw(playerName, "overlay", p2, overlay("тебя")) && saw(streamerName, "overlay", s2, overlay("другого"))
                        && saw(streamerName, "chat", s2, contains(0, "при всех выбирает")) && saw(playerName, "chat", p2, contains(0, "при всех выбирает")), () -> {
                    log("выбор у колокола через " + (ticks - called) / 20 + " с после сбора");
                    check(!rivalScene(), "выбор у колокола состоялся (колокол " + (venue == null ? "не найден" : venue.bell().toShortString()) + ")");
                    long now = System.currentTimeMillis();
                    List<RivalryLedger.Suitor> left = rivalry().suitors(rival.getUuid(), now);
                    check(left.size() == 1 && left.getFirst().player().equals(player) && left.getFirst().courtship() == io.github.mobtalk.core.Courtship.DATING,
                            "победил " + playerName + " и стал парой; ухажёры теперь: " + left.stream().map(x -> x.name() + " " + x.courtship()).toList());
                    int loserAfter = bridge.memory.relationship(rival.getUuid(), streamer);
                    check(loserAfter == loserBefore - ServerConfig.get().rivalLoserPenalty, "проигравший " + streamerName + " потерял сердечки: " + loserBefore + " → " + loserAfter);
                    check(saw(streamerName, "chat", s2, contains(0, "при всех выбирает: " + playerName)) && saw(playerName, "chat", p2, contains(0, "при всех выбирает: " + playerName)),
                            "оба услышали выбор у колокола: " + chats(streamerName, s2));
                    check(saw(playerName, "overlay", p2, overlay("тебя")) && saw(streamerName, "overlay", s2, overlay("другого")),
                            "победителю «выбрал тебя», проигравшему «выбрал другого»: " + overlays(playerName, p2) + " / " + overlays(streamerName, s2));
                    pause(6, this::rivalsDone);
                });
            });
        });
    }
    /** Соперник уходит со сцены вместе со своим колоколом, игроки — на свои места: свадьба ищет свой колокол. */
    private void rivalsDone() {
        if (rival != null) rival.discard();
        command("setblock " + at(RX, 0, RZ + 4) + " minecraft:air");
        command("item replace entity " + streamerName + " weapon.mainhand with minecraft:air");
        command("item replace entity " + playerName + " weapon.mainhand with minecraft:air");
        place(streamerName, -1, 0, 180); place(playerName, 1, 0, 180);
        pause(3, this::wedding);
    }

    // ---- Свадьба (familyLife): помолвка кольцом, зов на свадьбу, сбор у колокола; сосед видит и слышит. ----

    private WeddingController.Scene scene() { return bridge.wedding.scene(); }
    private void wedding() {
        int wz = 20;
        command("setblock " + at(0, 0, wz) + " minecraft:bell[attachment=floor,facing=north]");
        Gender gender = Gender.ofPlayer(PlayerSettingsStore.of(streamer).applyTo(ServerConfig.get()).playerGender);
        for (int i = 0; i < 16 && bride == null; i++) {
            VillagerEntity candidate = villager(-4 + i % 8, wz - 4 - i / 8 * 2, VillagerProfession.FARMER, 180);
            if (SocialProfile.drawnTo(candidate.getUuid(), gender)) bride = candidate; else guests.add(candidate);
        }
        cleric = villager(3, wz + 2, VillagerProfession.CLERIC, 180);
        if (bride == null) { check(false, "нет жительницы, которую тянет к стримеру (" + gender + ")"); leave(); return; }
        long now = System.currentTimeMillis();
        bridge.memory.recordEvent(bride.getUuid(), "minecraft:villager", "Житель", "Самотест: знакомство (свадьба на сервере)", now);
        bridge.memory.social().applyGesture(bride.getUuid(), streamer, streamerName, new GestureIntent(Gesture.GIFT, Gesture.Outcome.BEST, 95, "minecraft:diamond", ""), now);
        log("невеста " + bridge.memory.displayName(bride.getUuid()) + " (" + bridge.memory.relationship(bride.getUuid(), streamer) + "), гостей " + guests.size());
        command("tp " + bride.getUuidAsString() + " " + at(-1, 0, 3));
        command("item replace entity " + streamerName + " weapon.mainhand with mobtalk:engagement_ring");
        cue(streamerName, "find", ids(bride)); cue(playerName, "find", ids(bride));
        await("клиенты видят невесту", 15, () -> sees(streamerName, bride) && sees(playerName, bride), () -> {
            int s = mark(streamerName);
            cue(streamerName, "say", String.valueOf(bride.getId()), "Выходи за меня замуж! Вот кольцо, оно твоё.");
            await("ответ на предложение", 120, () -> saw(streamerName, "speech", s, speechOf(bride, true, null)), () -> {
                boolean engaged = bridge.memory.wedding().engaged(bride.getUuid(), streamer);
                check(engaged, "кольцо в руке дало помолвку: «" + lastAnswer(streamerName, bride, s) + "»");
                if (!engaged) bridge.memory.social().applyGesture(bride.getUuid(), streamer, streamerName,
                        new GestureIntent(Gesture.PROPOSE, Gesture.Outcome.ACCEPTED, 10, Romance.ENGAGEMENT_RING, ""), System.currentTimeMillis());
                command("item replace entity " + streamerName + " hotbar.1 with mobtalk:wedding_ring 2");
                int s2 = mark(streamerName), p2 = mark(playerName);
                cue(streamerName, "say", String.valueOf(bride.getId()), "Идём на свадьбу, любимая! Все к колоколу!");
                await("свадьба началась", 120, () -> scene() != null && scene().ready, () -> {
                    check(scene() != null && scene().ready, "свадьба на выделенном сервере началась (ответ невесты: «" + lastAnswer(streamerName, bride, s2) + "»)");
                    if (scene() == null) { leave(); return; }
                    var spot = scene().playerSpot();
                    var couple = scene().couple();
                    String facing = " facing " + couple.x + " " + (couple.y + 1.5) + " " + couple.z;
                    if (spot != null) command("tp " + streamerName + " " + spot.x + " " + spot.y + " " + spot.z + facing);
                    // The guest stands back and aside, clear of the seated villagers, looking at the couple.
                    command("tp " + playerName + " " + at(9, 0, wz - 9) + facing);
                    await("служба началась", 110, () -> scene() == null || scene().ceremony.phase() == Wedding.Ceremony.Phase.SERVICE, () -> {
                        check(scene() != null && scene().ceremony.phase() == Wedding.Ceremony.Phase.SERVICE, "гости собрались, служба идёт");
                        cue(streamerName, "shot", "mobtalk-server-wedding-" + streamerName + ".png"); cue(playerName, "shot", "mobtalk-server-wedding-" + playerName + ".png");
                        await("ведущий просит клятву", 150, () -> scene() == null || scene().ceremony.awaitingVow(), () -> {
                            check(saw(playerName, "speech", p2, x -> !x.field(4).isEmpty()), playerName + " (гость) слышит свадьбу: " + since(playerName, "speech", p2).size() + " реплик");
                            if (scene() == null || !scene().ceremony.awaitingVow()) { check(false, "ведущий не дошёл до клятвы"); leave(); return; }
                            int s3 = mark(streamerName);
                            cue(streamerName, "say", String.valueOf(bride.getId()), "Да, я согласен. Клянусь быть с тобой в радости и в беде и заботиться о тебе всю жизнь.");
                            await("брак заключён", 60, () -> scene() == null || scene().ceremony.phase() == Wedding.Ceremony.Phase.DONE
                                    || bridge.memory.jealousy().partners(streamer).stream().anyMatch(x -> x.married()), () -> {
                                boolean married = bridge.memory.jealousy().partners(streamer).stream().anyMatch(x -> x.married());
                                check(married, "клятва текстом на сервере довела до брака (статусы: " + since(streamerName, "status", s3).stream().map(x -> x.field(2)).toList() + ")");
                                pause(3, this::leave);
                            });
                        });
                    });
                });
            });
        });
    }

    // ---- Выход: игрок уходит во время разговора, стример продолжает. ----

    // ---- Исповедь (Б4): ServerSelfTestConfession, перед выходом игрока. ----

    private boolean confessed;
    private void leave() {
        if (!confessed) { confession(this::leaveNow); return; }
        leaveNow();
    }
    private void confession(Runnable next) {
        confessed = true;
        ServerSelfTestPlay self = this;
        new ServerSelfTestConfession(new ServerSelfTestConfession.Conductor() {
            @Override public void log(String text) { self.log(text); }
            @Override public void check(boolean ok, String what) { self.check(ok, what); }
            @Override public void await(String what, int seconds, BooleanSupplier condition, Runnable then) { self.await(what, seconds, condition, then); }
            @Override public void pause(int seconds, Runnable then) { self.pause(seconds, then); }
            @Override public void cue(String nick, String... fields) { self.cue(nick, fields); }
            @Override public void command(String line) { self.command(line); }
            @Override public ServerWorld world() { return self.world(); }
            @Override public String at(int dx, int dy, int dz) { return self.at(dx, dy, dz); }
            @Override public VillagerEntity villager(int dx, int dz, VillagerProfession profession, float yaw) { return self.villager(dx, dz, profession, yaw); }
            @Override public int mark(String nick) { return self.mark(nick); }
            @Override public boolean saw(String nick, String kind, int from, Predicate<Seen> match) { return self.saw(nick, kind, from, match); }
            @Override public List<Seen> since(String nick, String kind, int from) { return self.since(nick, kind, from); }
            @Override public ServerPlayerEntity online(String nick) { return self.online(nick); }
        }, bridge, streamerName, playerName, player, next).run();
    }
    private void leaveNow() {
        command("tp " + streamerName + " " + at(-1, 0, 0) + " 180 10");
        command("tp " + playerName + " " + at(1, 0, 0) + " 180 10");
        pause(3, () -> {
            cue(streamerName, "shot", "mobtalk-server-" + streamerName + ".png"); cue(playerName, "shot", "mobtalk-server-" + playerName + ".png");
            pause(3, () -> {
                cue(playerName, "say", String.valueOf(chatter.getId()), "Скажи, а где тут ближайший колодец?");
                await("игрок занял болтуна", 15, () -> player.equals(dialogue().holderOf(chatter.getUuid())), () -> {
                    cue(playerName, "quit");
                    await("игрок вышел", 30, () -> online(playerName) == null, () -> {
                        check(online(playerName) == null, playerName + " вышел посреди разговора");
                        pause(5, () -> {
                            check(dialogue().holderOf(chatter.getUuid()) == null && !dialogue().busyTalking(player), "сессия вышедшего закрыта, болтун свободен");
                            int s = mark(streamerName);
                            cue(streamerName, "say", String.valueOf(talker.getId()), "Мой друг ушёл. Как думаешь, он вернётся?");
                            await("стример продолжает", 120, () -> saw(streamerName, "speech", s, speechOf(talker, true, null)), () -> {
                                check(saw(streamerName, "speech", s, speechOf(talker, true, null)), streamerName + " продолжает разговор после выхода соседа");
                                finish();
                            });
                        });
                    });
                });
            });
        });
    }

    private void finish() {
        if (finishing) return;
        finishing = true;
        if (failures.isEmpty()) LOGGER.info("[selftest-play] ALL CHECKS PASSED");
        else { LOGGER.error("[selftest-play] FAILED: {} проверок", failures.size()); for (String failure : failures) LOGGER.error("[selftest-play] FAIL {}", failure); }
        for (String nick : List.of(streamerName, playerName)) if (online(nick) != null) cue(nick, "quit");
        waitingFor = "";
        await("клиенты вышли", 30, () -> online(streamerName) == null && online(playerName) == null, () -> {
            finished = true;
            running = null;
            log("остановка сервера");
            server.stop(false);
        });
    }
}
