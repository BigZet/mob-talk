package io.github.mobtalk.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** Расходы и лимиты по игрокам (раздел 12.2 и бриф C плана). Считает доллары и единицы по игроку и виду работы за сегодня (сутки по
 * часам сервера), всего и по дням за месяц; хранит в {@code <мир>/data/mobtalk/budget.json}. Лимиты «в минуту» (запросы) и «в день»
 * (доллары) на игрока и на сервер берутся из серверных настроек и по умолчанию равны 0 — без ограничения; поверх — правила
 * {@code /mobtalk admin budget set} для игрока или всех и вида работы. Стримеру лимиты умножаются на {@code budgetStreamerMultiplier},
 * администратор не ограничен. Отдельно и всегда — не больше {@link #PER_PLAYER_SLOTS} ответов на игрока сразу и
 * {@code maxConcurrentDialogues} на сервер ({@link #enter}). Без Minecraft: уровень игрока даёт функция, время — {@link Clock}. */
public final class PlayerBudgetImpl implements PlayerBudget {
    public static final String FILE = "budget.json";
    public static final int PER_PLAYER_SLOTS = 2, HISTORY_DAYS = 31;
    private static final long MINUTE = 60_000;

    /** Правило поверх настроек: {@code perMinute} запросов, {@code dailyUsd} долларов в день (0 — без ограничения). */
    public record Limit(int perMinute, double dailyUsd) { }

    private static final class Usage {
        double usd; long units; int requests;
        JsonObject json() { JsonObject o = new JsonObject(); o.addProperty("usd", usd); o.addProperty("units", units); o.addProperty("requests", requests); return o; }
        static Usage of(JsonElement e) {
            Usage u = new Usage();
            if (e != null && e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                u.usd = number(o, "usd"); u.units = (long) number(o, "units"); u.requests = (int) number(o, "requests");
            }
            return u;
        }
    }

    private final Supplier<MobTalkConfig> config;
    private final Function<UUID, Permissions.Level> level;
    private final Clock clock;
    private volatile Path file;

    private LocalDate day;
    private final Map<UUID, EnumMap<Kind, Usage>> today = new LinkedHashMap<>();
    private final Map<UUID, Double> total = new LinkedHashMap<>();
    private final TreeMap<String, Map<UUID, Double>> history = new TreeMap<>();
    private final Map<String, Limit> overrides = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<String, Deque<Long>> recent = new LinkedHashMap<>();
    private final Deque<Long> serverRecent = new ArrayDeque<>();
    private final Map<UUID, Integer> active = new LinkedHashMap<>();
    private int serverActive;
    private boolean dirty, scheduled;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mobtalk-budget"); thread.setDaemon(true); return thread;
    });

    public PlayerBudgetImpl(Supplier<MobTalkConfig> config, Function<UUID, Permissions.Level> level, Clock clock) {
        this.config = config; this.level = level; this.clock = clock;
        this.day = LocalDate.now(clock);
    }

    // ---- Файл: открыть при старте мира, дописать и закрыть при остановке. ----

    /** Читает {@code <мир>/data/mobtalk/budget.json}; повреждённый файл даёт пустой учёт. Диск — звать не на потоке сервера или при старте. */
    public synchronized void open(Path worldDirectory) {
        clear();
        file = worldDirectory == null ? null : worldDirectory.resolve("data").resolve("mobtalk").resolve(FILE);
        if (file == null || !Files.exists(file)) return;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (parsed.isJsonObject()) read(parsed.getAsJsonObject());
        } catch (IOException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Не удалось прочитать {}: {}", FILE, failure.toString());
        }
        rollDay();
    }
    /** Дописать на диск и забыть мир. */
    public void close() { flush(); synchronized (this) { file = null; clear(); } }
    /** Дождаться записи (остановка, тесты). */
    public void flush() {
        try { writer.submit(this::save).get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Не удалось дописать {}: {}", FILE, failure.toString()); }
    }

    private void clear() {
        today.clear(); total.clear(); history.clear(); overrides.clear(); names.clear(); recent.clear(); serverRecent.clear();
        day = LocalDate.now(clock); dirty = false;
    }

    // ---- Имена для отчёта: ник, который сервер видел последним. ----

    public synchronized void remember(UUID player, String name) {
        if (player == null || name == null || name.isBlank() || name.equals(names.get(player))) return;
        names.put(player, name); markDirty();
    }
    public synchronized String name(UUID player) { return player == null ? "сервер" : names.getOrDefault(player, player.toString().substring(0, 8)); }

    // ---- Правила поверх настроек. ----

    /** {@code who} — UUID игрока строкой или {@code all}. */
    public synchronized void setLimit(String who, Kind kind, int perMinute, double dailyUsd) {
        overrides.put(key(who, kind), new Limit(Math.max(0, perMinute), Math.max(0, finite(dailyUsd))));
        markDirty();
    }
    public synchronized boolean clearLimit(String who, Kind kind) { boolean removed = overrides.remove(key(who, kind)) != null; if (removed) markDirty(); return removed; }
    public synchronized Map<String, Limit> limits() { return Map.copyOf(overrides); }
    private static String key(String who, Kind kind) { return (who == null || who.isBlank() ? "all" : who.toLowerCase(Locale.ROOT)) + ":" + kind.name(); }

    // ---- Лимиты. ----

    @Override public synchronized Verdict allow(UUID player, Kind kind) {
        rollDay();
        long now = clock.millis();
        prune(serverRecent, now);
        Permissions.Level who = player == null ? Permissions.Level.PLAYER : level.apply(player);
        if (who != Permissions.Level.ADMIN) {
            MobTalkConfig c = config.get();
            if (player != null) {
                double multiplier = who == Permissions.Level.STREAMER ? Math.max(1, c.budgetStreamerMultiplier) : 1;
                Limit limit = limitFor(player, kind, c);
                Deque<Long> mine = recent.computeIfAbsent(player + ":" + kind.name(), ignored -> new ArrayDeque<>());
                prune(mine, now);
                int perMinute = (int) Math.floor(limit.perMinute() * multiplier);
                if (limit.perMinute() > 0 && mine.size() >= perMinute)
                    return Verdict.no("Слишком часто: не больше " + perMinute + " " + requests(perMinute) + " в минуту, подождите немного");
                double daily = limit.dailyUsd() * multiplier;
                double spent = overridden(player, kind) ? usage(player, kind).usd : spentUsd(player);
                if (limit.dailyUsd() > 0 && spent >= daily)
                    return Verdict.no("Ваш дневной лимит расходов исчерпан (" + money(daily) + "), он обновится завтра");
            }
            if (c.budgetServerPerMinute > 0 && serverRecent.size() >= c.budgetServerPerMinute)
                return Verdict.no("Сервер сейчас перегружен разговорами, попробуйте через минуту");
            if (c.budgetServerDailyUsd > 0 && serverSpent() >= c.budgetServerDailyUsd)
                return Verdict.no("Дневной лимит расходов сервера исчерпан, он обновится завтра");
        }
        serverRecent.addLast(now);
        if (player != null) {
            recent.computeIfAbsent(player + ":" + kind.name(), ignored -> new ArrayDeque<>()).addLast(now);
            usage(player, kind).requests++;
            markDirty();
        }
        return Verdict.ok();
    }

    /** Правило игрока для вида, иначе правило для всех, иначе настройки: «в минуту» из настроек — только для диалога (запросы
     * игрока), «в день» — сумма по всем видам. */
    private Limit limitFor(UUID player, Kind kind, MobTalkConfig c) {
        Limit own = overrides.get(key(player.toString(), kind));
        if (own != null) return own;
        Limit all = overrides.get(key("all", kind));
        if (all != null) return all;
        return new Limit(kind == Kind.DIALOGUE ? c.budgetPlayerPerMinute : 0, c.budgetPlayerDailyUsd);
    }
    private boolean overridden(UUID player, Kind kind) { return overrides.containsKey(key(player.toString(), kind)) || overrides.containsKey(key("all", kind)); }

    @Override public synchronized void charge(UUID player, Kind kind, double usd, int units) {
        rollDay();
        double money = Math.max(0, finite(usd));
        UUID owner = player; // null — работа сервера (болтовня деревни без адресата): учитывается в сумме сервера.
        Usage u = usage(owner, kind);
        u.usd += money; u.units += Math.max(0, units);
        total.merge(owner == null ? SERVER : owner, money, Double::sum);
        markDirty();
    }

    // ---- Одновременные ответы. ----

    @Override public synchronized Slot enter(UUID player) {
        int limit = Math.max(1, config.get().maxConcurrentDialogues);
        if (player != null && active.getOrDefault(player, 0) >= PER_PLAYER_SLOTS) return denied("Подождите: у вас уже идут два ответа");
        if (serverActive >= limit) return denied("Сервер занят ответами другим игрокам, попробуйте через пару секунд");
        serverActive++;
        if (player != null) active.merge(player, 1, Integer::sum);
        return new Slot() {
            private boolean closed;
            @Override public boolean granted() { return true; }
            @Override public String reason() { return ""; }
            @Override public void close() {
                synchronized (PlayerBudgetImpl.this) {
                    if (closed) return;
                    closed = true;
                    serverActive = Math.max(0, serverActive - 1);
                    if (player != null) active.computeIfPresent(player, (id, n) -> n <= 1 ? null : n - 1);
                }
            }
        };
    }
    private static Slot denied(String reason) {
        return new Slot() {
            @Override public boolean granted() { return false; }
            @Override public String reason() { return reason; }
            @Override public void close() { }
        };
    }
    /** Сколько ответов идёт сейчас: у игрока и на всём сервере (для админки и тестов). */
    public synchronized int active(UUID player) { return player == null ? serverActive : active.getOrDefault(player, 0); }

    // ---- Отчёты. ----

    @Override public synchronized String spentToday(UUID player) { rollDay(); return money(spentUsd(player)); }
    public synchronized double spentTodayUsd(UUID player) { rollDay(); return player == null ? serverSpent() : spentUsd(player); }
    public synchronized double totalUsd(UUID player) { return total.getOrDefault(player == null ? SERVER : player, 0.0); }

    @Override public synchronized String report() {
        rollDay();
        MobTalkConfig c = config.get();
        StringBuilder out = new StringBuilder("Расходы за ").append(day).append(" (сутки по часам сервера)");
        out.append("\n  лимиты игрока: ").append(limitText(c.budgetPlayerPerMinute, c.budgetPlayerDailyUsd))
           .append("; сервера: ").append(limitText(c.budgetServerPerMinute, c.budgetServerDailyUsd))
           .append("; стример ×").append(String.format(Locale.ROOT, "%.1f", c.budgetStreamerMultiplier)).append(", админ без лимита");
        for (Map.Entry<String, Limit> rule : overrides.entrySet()) {
            String[] parts = rule.getKey().split(":", 2);
            String who = "all".equals(parts[0]) ? "все" : nameOf(parts[0]);
            out.append("\n  правило: ").append(who).append(", ").append(kindName(Kind.valueOf(parts[1]))).append(" — ")
               .append(limitText(rule.getValue().perMinute(), rule.getValue().dailyUsd()));
        }
        if (today.isEmpty()) out.append("\n  сегодня трат не было");
        for (Map.Entry<UUID, EnumMap<Kind, Usage>> entry : today.entrySet()) {
            UUID player = entry.getKey().equals(SERVER) ? null : entry.getKey();
            out.append("\n  ").append(name(player)).append(": ").append(money(sum(entry.getValue())));
            StringBuilder kinds = new StringBuilder();
            for (Map.Entry<Kind, Usage> kind : entry.getValue().entrySet()) {
                Usage u = kind.getValue();
                if (u.usd <= 0 && u.requests == 0 && u.units == 0) continue;
                if (!kinds.isEmpty()) kinds.append(", ");
                kinds.append(kindName(kind.getKey())).append(' ').append(money(u.usd)).append(" ·").append(u.requests);
            }
            if (!kinds.isEmpty()) out.append(" (").append(kinds).append(')');
            out.append(", всего ").append(money(total.getOrDefault(entry.getKey(), 0.0)));
        }
        out.append("\n  сервер сегодня: ").append(money(serverSpent())).append(", всего: ").append(money(total.values().stream().mapToDouble(Double::doubleValue).sum()));
        return out.toString();
    }

    /** Для админки (трек W1): {@code {"day","today":{ник:{вид:{usd,units,requests}}},"total":{ник:usd},"history":{дата:{ник:usd}}}}. */
    public synchronized String costsJson() {
        rollDay();
        JsonObject root = new JsonObject();
        root.addProperty("day", day.toString());
        JsonObject todayJson = new JsonObject();
        today.forEach((player, kinds) -> {
            JsonObject row = new JsonObject();
            kinds.forEach((kind, u) -> row.add(kind.name().toLowerCase(Locale.ROOT), u.json()));
            todayJson.add(name(player.equals(SERVER) ? null : player), row);
        });
        root.add("today", todayJson);
        JsonObject totals = new JsonObject();
        total.forEach((player, usd) -> totals.addProperty(name(player.equals(SERVER) ? null : player), usd));
        root.add("total", totals);
        JsonObject days = new JsonObject();
        history.forEach((date, players) -> {
            JsonObject row = new JsonObject();
            players.forEach((player, usd) -> row.addProperty(name(player.equals(SERVER) ? null : player), usd));
            days.add(date, row);
        });
        root.add("history", days);
        return root.toString();
    }

    // ---- Внутреннее. ----

    /** Ключ работы сервера без игрока в картах. */
    private static final UUID SERVER = new UUID(0, 0);

    private Usage usage(UUID player, Kind kind) {
        return today.computeIfAbsent(player == null ? SERVER : player, ignored -> new EnumMap<>(Kind.class)).computeIfAbsent(kind, ignored -> new Usage());
    }
    private double spentUsd(UUID player) { EnumMap<Kind, Usage> kinds = today.get(player == null ? SERVER : player); return kinds == null ? 0 : sum(kinds); }
    private double serverSpent() { double sum = 0; for (EnumMap<Kind, Usage> kinds : today.values()) sum += sum(kinds); return sum; }
    private static double sum(Map<Kind, Usage> kinds) { double sum = 0; for (Usage u : kinds.values()) sum += u.usd; return sum; }
    private static void prune(Deque<Long> times, long now) { while (!times.isEmpty() && now - times.peekFirst() >= MINUTE) times.removeFirst(); }

    /** Новые сутки: вчерашнее уходит в историю по дням, окна «в минуту» не трогаем. */
    private void rollDay() {
        LocalDate now = LocalDate.now(clock);
        if (now.equals(day)) return;
        if (!today.isEmpty()) {
            Map<UUID, Double> row = new LinkedHashMap<>();
            today.forEach((player, kinds) -> row.put(player, sum(kinds)));
            history.put(day.toString(), row);
        }
        while (history.size() > HISTORY_DAYS) history.pollFirstEntry();
        today.clear();
        day = now;
        markDirty();
    }

    private void markDirty() {
        dirty = true;
        if (file == null || scheduled) return;
        scheduled = true;
        writer.schedule(this::save, 5, TimeUnit.SECONDS);
    }

    private void save() {
        Path target; String json;
        synchronized (this) {
            scheduled = false;
            target = file;
            if (target == null || !dirty) return;
            json = new GsonBuilder().setPrettyPrinting().create().toJson(write());
            dirty = false;
        }
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), FILE + ".", ".tmp");
            try {
                Files.writeString(temporary, json);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Не удалось записать {}: {}", FILE, failure.toString());
        }
    }

    private JsonObject write() {
        JsonObject root = new JsonObject();
        root.addProperty("day", day.toString());
        JsonObject todayJson = new JsonObject();
        today.forEach((player, kinds) -> {
            JsonObject row = new JsonObject();
            kinds.forEach((kind, u) -> row.add(kind.name(), u.json()));
            todayJson.add(player.toString(), row);
        });
        root.add("today", todayJson);
        JsonObject totals = new JsonObject();
        total.forEach((player, usd) -> totals.addProperty(player.toString(), usd));
        root.add("total", totals);
        JsonObject days = new JsonObject();
        history.forEach((date, players) -> { JsonObject row = new JsonObject(); players.forEach((p, usd) -> row.addProperty(p.toString(), usd)); days.add(date, row); });
        root.add("history", days);
        JsonObject rules = new JsonObject();
        overrides.forEach((key, limit) -> { JsonObject o = new JsonObject(); o.addProperty("perMinute", limit.perMinute()); o.addProperty("dailyUsd", limit.dailyUsd()); rules.add(key, o); });
        root.add("limits", rules);
        JsonObject known = new JsonObject();
        names.forEach((player, name) -> known.addProperty(player.toString(), name));
        root.add("names", known);
        return root;
    }

    private void read(JsonObject root) {
        try { day = LocalDate.parse(root.get("day").getAsString()); } catch (RuntimeException missing) { day = LocalDate.now(clock); }
        for (Map.Entry<String, JsonElement> entry : object(root, "today").entrySet()) {
            UUID player = uuid(entry.getKey());
            if (player == null || !entry.getValue().isJsonObject()) continue;
            EnumMap<Kind, Usage> kinds = new EnumMap<>(Kind.class);
            for (Map.Entry<String, JsonElement> kind : entry.getValue().getAsJsonObject().entrySet()) {
                try { kinds.put(Kind.valueOf(kind.getKey()), Usage.of(kind.getValue())); } catch (IllegalArgumentException unknown) { /* вид из будущей версии */ }
            }
            today.put(player, kinds);
        }
        for (Map.Entry<String, JsonElement> entry : object(root, "total").entrySet()) {
            UUID player = uuid(entry.getKey());
            if (player != null && entry.getValue().isJsonPrimitive()) total.put(player, finite(entry.getValue().getAsDouble()));
        }
        for (Map.Entry<String, JsonElement> entry : object(root, "history").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            Map<UUID, Double> row = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> cell : entry.getValue().getAsJsonObject().entrySet()) {
                UUID player = uuid(cell.getKey());
                if (player != null && cell.getValue().isJsonPrimitive()) row.put(player, finite(cell.getValue().getAsDouble()));
            }
            history.put(entry.getKey(), row);
        }
        for (Map.Entry<String, JsonElement> entry : object(root, "limits").entrySet()) {
            if (!entry.getValue().isJsonObject() || !entry.getKey().contains(":")) continue;
            JsonObject o = entry.getValue().getAsJsonObject();
            overrides.put(entry.getKey(), new Limit((int) Math.max(0, number(o, "perMinute")), Math.max(0, number(o, "dailyUsd"))));
        }
        for (Map.Entry<String, JsonElement> entry : object(root, "names").entrySet()) {
            UUID player = uuid(entry.getKey());
            if (player != null && entry.getValue().isJsonPrimitive()) names.put(player, entry.getValue().getAsString());
        }
    }

    private String nameOf(String id) { UUID player = uuid(id); return player == null ? id : name(player); }
    private static JsonObject object(JsonObject root, String field) { JsonElement e = root.get(field); return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(); }
    private static double number(JsonObject o, String field) {
        try { JsonElement e = o.get(field); return e == null ? 0 : finite(e.getAsDouble()); } catch (RuntimeException bad) { return 0; }
    }
    private static UUID uuid(String text) { try { return UUID.fromString(text); } catch (RuntimeException bad) { return null; } }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }

    public static String money(double usd) { return String.format(Locale.ROOT, "%.4f $", usd); }
    private static String limitText(int perMinute, double dailyUsd) {
        return (perMinute > 0 ? perMinute + "/мин" : "без лимита в минуту") + ", " + (dailyUsd > 0 ? money(dailyUsd) + " в день" : "без лимита в день");
    }
    private static String requests(int n) {
        int last = n % 10, lastTwo = n % 100;
        return last == 1 && lastTwo != 11 ? "запроса" : "запросов";
    }
    public static String kindName(Kind kind) {
        return switch (kind) {
            case DIALOGUE -> "диалог"; case TRANSCRIPTION -> "распознавание"; case SPEECH -> "озвучка";
            case WITNESS -> "очевидцы"; case VILLAGE_TALK -> "болтовня деревни";
        };
    }
    /** Вид работы по русскому или английскому имени («диалог», «dialogue»); null — нет такого. */
    public static Kind kind(String text) {
        if (text == null) return null;
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        for (Kind kind : Kind.values()) if (kind.name().toLowerCase(Locale.ROOT).equals(wanted) || kindName(kind).equals(wanted)) return kind;
        return null;
    }
}
