package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Моменты для монтажа (идея З4): bright events of the history become one list with timecodes of a recording. A detector listens to
 * {@link HistoryStore}, weighs each record by {@link MomentRules}, merges one player's events that come close together and writes
 * the result as a {@code moment} record; {@code /clip} and the admin add manual ones. A recording session ({@code rec_session}) is
 * where a player's video starts: OBS on the player's computer tells it, a command or button sets it, or a join opens it. Edits
 * (name, star, hide, a replay file) are {@code moment_edit} records, so the history stays append-only. Nothing here calls a model
 * or touches the game; every method may be called from any thread. */
public final class Moments {
    /** Kinds this class writes; the detector skips them. */
    public static final Set<String> OWN = Set.of("moment", "moment_edit", "rec_session");
    public static final Set<String> TYPES = Set.of("god", "village", "combat", "manual");
    static final long LOW_WEIGHT_GAP = 60_000;
    /** A finished OBS session that comes back with the same start (a reconnect) is reopened, not duplicated. */
    static final long REOPEN_SLACK = 10_000;
    static final String FORMATS = "youtube, csv, edl, xml";

    /** Sent the moment a group first reaches the threshold, before it is written: OBS chapters and replay files want it now. */
    public record Live(String id, String title, int weight, String type, Set<String> players, boolean manual) { }
    public record Export(String filename, String contentType, byte[] body) { }

    /** One recording: whose, from when, until when (0 — still going), and the correction in milliseconds added to every timecode. */
    static final class Session {
        final String id;
        final JsonObject player;
        final String uuid;
        long start, end, shift;
        String source, output;
        Session(String id, JsonObject player, long start, String source, String output) {
            this.id = id; this.player = player; this.uuid = Json.string(player, "uuid"); this.start = start; this.source = source; this.output = output;
        }
        boolean open() { return end == 0; }
        JsonObject json(long now, int count) {
            return Json.obj("id", id, "player", player, "start", start, "end", end == 0 ? null : end, "open", end == 0, "source", source,
                    "output", output, "shift", shift, "seconds", ((end == 0 ? now : end) - start) / 1000, "moments", count);
        }
    }

    /** A weighed event. */
    record Hit(String key, int weight, long t, long start, String title, String quote, String type, JsonObject player, JsonObject mob, String source) { }

    /** Events of one actor waiting for the merge window to close. */
    private final class Group {
        final String actor;
        final List<Hit> hits = new ArrayList<>();
        final Map<String, JsonObject> players = new LinkedHashMap<>();
        final JsonObject replays = new JsonObject();
        String liveId;
        long last;
        Group(String actor) { this.actor = actor; }
        void add(Hit hit) {
            hits.add(hit); last = Math.max(last, hit.t());
            if (hit.player() != null && Json.string(hit.player(), "uuid") != null) players.putIfAbsent(Json.string(hit.player(), "uuid"), hit.player());
        }
        int weight() {
            int max = 0, notable = 0;
            for (Hit hit : hits) { max = Math.max(max, hit.weight()); if (hit.weight() >= 1) notable++; }
            return Math.min(3, max + (notable >= 3 ? 1 : 0));
        }
        Hit top() { return hits.stream().max(Comparator.comparingInt(Hit::weight).thenComparingLong(hit -> -hit.t())).orElseThrow(); }
        String title() {
            List<String> parts = new ArrayList<>();
            for (Hit hit : hits) if (hit.weight() >= 2 && !parts.contains(hit.title())) parts.add(hit.title());
            if (parts.isEmpty()) return top().title();
            return String.join(" → ", parts.size() > 3 ? parts.subList(0, 3) : parts);
        }
        long start() { long start = Long.MAX_VALUE; for (Hit hit : hits) start = Math.min(start, hit.start()); return start; }
    }

    private final HistoryStore history;
    private final LongSupplier clock;
    private final Supplier<MomentRules> rules;
    private final Consumer<String> log;
    private final List<Consumer<Live>> listeners = new CopyOnWriteArrayList<>();
    private volatile Predicate<String> streamer = uuid -> false;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, Long> lastLow = new HashMap<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, JsonObject> edits = new HashMap<>();
    /** Live id → the moment written for it, for replay files that arrive after the merge window. */
    private final Map<String, String> written = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 500; }
    };
    private long liveSequence;

    public Moments(HistoryStore history, LongSupplier clock, Supplier<MomentRules> rules, Consumer<String> log) {
        this.history = history; this.clock = clock; this.rules = rules; this.log = log;
        load();
        history.listen(this::observe);
    }

    /** Who the streamer is (a UUID string), for sessions that open when the streamer joins. */
    public void streamer(Predicate<String> isStreamer) { streamer = isStreamer == null ? uuid -> false : isStreamer; }
    public void onLive(Consumer<Live> listener) { listeners.add(listener); }
    private MomentRules rules() { MomentRules now = rules.get(); return now == null ? new MomentRules() : now; }

    // ---- Detector. ----

    synchronized void observe(JsonObject record) {
        String kind = Json.string(record, "kind");
        if (kind == null || OWN.contains(kind)) return;
        long t = Json.number(record, "t", clock.getAsLong());
        JsonObject player = object(record, "player");
        if ("player_join".equals(kind) || "player_leave".equals(kind)) { presence(kind, player, t); return; }
        MomentRules rules = rules();
        if (!rules.enabled) return;
        Hit hit = classify(record, rules);
        if (hit == null || hit.weight() <= 0) return;
        String actor = hit.player() == null || Json.string(hit.player(), "uuid") == null ? "*" : Json.string(hit.player(), "uuid");
        Group group = groups.get(actor);
        if (group != null && t - group.last > rules.mergeSeconds * 1000L) { flush(group); group = null; }
        if (group == null) { group = new Group(actor); groups.put(actor, group); }
        group.add(hit);
        if (group.liveId == null && group.weight() >= rules.threshold) {
            group.liveId = nextLive();
            announce(new Live(group.liveId, group.title(), group.weight(), group.top().type(), Set.copyOf(group.players.keySet()), false));
        }
    }

    /** Writes the groups whose merge window has closed; the admin calls it every few seconds. */
    public synchronized void tick() {
        long now = clock.getAsLong(), window = rules().mergeSeconds * 1000L;
        for (Group group : new ArrayList<>(groups.values())) if (now - group.last > window) flush(group);
    }

    /** Everything still waiting goes out now (server stop, tests). */
    public synchronized void flushAll() { for (Group group : new ArrayList<>(groups.values())) flush(group); }

    private void flush(Group group) {
        groups.remove(group.actor);
        if (group.hits.isEmpty()) return;
        MomentRules rules = rules();
        int weight = group.weight();
        if (weight < rules.threshold) return;
        if (weight <= 1) {
            Long last = lastLow.get(group.actor);
            if (last != null && group.last - last < LOW_WEIGHT_GAP) return;
            lastLow.put(group.actor, group.last);
        }
        Hit top = group.top();
        List<String> sources = new ArrayList<>(), keys = new ArrayList<>();
        for (Hit hit : group.hits) { if (hit.source() != null) sources.add(hit.source()); if (!keys.contains(hit.key())) keys.add(hit.key()); }
        JsonObject record = write(group.players.isEmpty() ? null : group.players.values().iterator().next(), top.mob(), group.title(), top.quote(),
                weight, top.type(), group.start(), group.last + 5_000, List.copyOf(group.players.values()), sources, keys, null, group.liveId);
        if (group.liveId != null) written.put(group.liveId, Json.string(record, "id"));
        if (group.replays.size() > 0) edit(Json.string(record, "id"), Json.obj("replay", group.replays), "obs");
    }

    private JsonObject write(JsonObject player, JsonObject mob, String title, String quote, int weight, String type, long start, long end,
                             List<JsonObject> players, List<String> sources, List<String> keys, String by, String liveId) {
        JsonObject data = Json.obj("start", start, "end", end, "weight", weight, "type", type, "title", title, "quote", quote,
                "players", players, "sources", sources, "keys", keys, "by", by, "live", liveId);
        return history.append("moment", player, mob, title, data);
    }

    private String nextLive() { return "l" + Long.toString(clock.getAsLong(), 36) + "-" + Long.toString(++liveSequence, 36); }

    private void announce(Live live) {
        for (Consumer<Live> listener : listeners) {
            try { listener.accept(live); } catch (RuntimeException failure) { log.accept("моменты: слушатель упал: " + failure); }
        }
    }

    /** Weighs one history record, or null when it is not a moment. */
    static Hit classify(JsonObject record, MomentRules rules) {
        String kind = Json.string(record, "kind");
        long t = Json.number(record, "t", 0);
        JsonObject data = object(record, "data"), player = object(record, "player"), mob = object(record, "mob");
        String name = name(player), mobName = name(mob), text = Json.string(record, "text"), id = Json.string(record, "id");
        long since = Json.number(data, "since", 0);
        String key, title, quote = null, type;
        switch (kind == null ? "" : kind) {
            case "verdict" -> {
                String act = String.valueOf(Json.string(data, "act")), deed = blank(Json.string(data, "deed"));
                quote = blank(Json.string(data, "verdict"));
                type = "god";
                switch (act) {
                    case "wrath" -> { key = "wrath"; title = deed == null ? "Кара для " + who(name) : "Кара: " + deed + " — " + who(name); }
                    case "miracle" -> { key = "miracle"; title = deed == null ? "Чудо для " + who(name) : "Чудо: " + deed + " — " + who(name); }
                    case "warning" -> { key = "omen"; title = "Знамение для " + who(name); }
                    case "jest" -> { key = "jest"; title = "Шутка бога над " + who(name); }
                    default -> { return null; }
                }
            }
            case "boss" -> {
                String boss = blank(Json.string(data, "boss")), event = String.valueOf(Json.string(data, "event"));
                type = "combat";
                if ("spawn".equals(event)) { key = "boss_spawn"; title = "Босс: " + (boss == null ? "явился" : boss + " явился"); }
                else if ("death".equals(event)) { key = "boss_end"; title = "Босс повержен" + (boss == null ? "" : ": " + boss); }
                else if ("despawn".equals(event) || "gone".equals(event)) { key = "boss_end"; title = "Бой с боссом окончен" + (boss == null ? "" : ": " + boss); }
                else return null;
            }
            case "ban" -> {
                if (Boolean.parseBoolean(String.valueOf(Json.string(data, "lifted")))) return null;
                key = "ban"; type = "god"; title = "Изгнание: " + who(name); quote = blank(Json.string(data, "reason"));
            }
            case "wedding" -> {
                String outcome = String.valueOf(Json.string(data, "outcome"));
                type = "village";
                if ("married".equals(outcome)) key = "wedding";
                else if ("jilted".equals(outcome) || "cancelled".equals(outcome)) key = "wedding_broken";
                else return null;
                title = text == null || text.isBlank() ? "Свадьба" : text;
                quote = blank(Json.string(data, "vow"));
            }
            case "death" -> { key = "death"; type = "combat"; title = "Смерть: " + (text == null || text.isBlank() ? who(name) : text); }
            case "scene" -> {
                JsonElement tags = data == null ? null : data.get("tags");
                key = null; int best = 0;
                if (tags != null && tags.isJsonArray()) for (JsonElement tag : tags.getAsJsonArray()) {
                    String one = tag.isJsonPrimitive() ? tag.getAsString() : null;
                    if (one != null && rules.weight(one) > best) { best = rules.weight(one); key = one; }
                }
                if (key == null) return null;
                type = "village"; title = text == null || text.isBlank() ? "Сцена: " + who(name) + " и " + who(mobName) : text;
                quote = blank(Json.string(data, "answer"));
            }
            case "trade" -> {
                if (!"refused".equals(Json.string(data, "outcome"))) return null;
                key = "trade_refused"; type = "village"; title = "Торг сорвался: " + who(mobName) + " и " + who(name);
            }
            default -> { return null; }
        }
        int weight = rules.weight(key);
        if (weight <= 0) return null;
        long start = since > 0 && since <= t ? since : t - rules.preroll(key) * 1000L;
        return new Hit(key, weight, t, start, clip(title, 120), quote == null ? null : clip(quote, 300), type, player, mob, id);
    }

    // ---- Manual marks. ----

    /** {@code /clip}, the key or the admin's button: a moment of weight 3 now, reaching {@code backSeconds} back (negative — the rule). */
    public synchronized JsonObject mark(JsonObject player, String title, int backSeconds, String by) {
        long now = clock.getAsLong();
        int back = backSeconds < 0 ? rules().manualBackSeconds : Math.min(600, backSeconds);
        String uuid = Json.string(player, "uuid");
        String name = blank(title);
        if (name == null) {
            Group pending = uuid == null ? null : groups.get(uuid);
            name = pending != null ? "Метка: " + pending.title() : "Метка: " + who(name(player));
        }
        name = clip(name, 120);
        String live = nextLive();
        Set<String> players = uuid == null ? Set.of() : Set.of(uuid);
        announce(new Live(live, name, 3, "manual", players, true));
        JsonObject record = write(player, null, name, null, 3, "manual", now - back * 1000L, now + 5_000,
                player == null ? List.of() : List.of(player), List.of(), List.of("manual"), by, live);
        written.put(live, Json.string(record, "id"));
        return record;
    }

    /** OBS saved a replay for a live moment: the path on that player's computer goes into the moment. */
    public synchronized void replayFile(String liveId, String playerUuid, String path) {
        if (liveId == null || playerUuid == null || blank(path) == null) return;
        String file = clip(path.strip(), 400);
        for (Group group : groups.values()) if (liveId.equals(group.liveId)) { group.replays.addProperty(playerUuid, file); return; }
        String moment = written.get(liveId);
        if (moment != null) edit(moment, Json.obj("replay", Json.obj(playerUuid, file)), "obs");
    }

    // ---- Edits. ----

    /** Rename, star, hide: {@code changes} with {@code title}, {@code starred}, {@code hidden}. Returns the merged edit. */
    public synchronized JsonObject edit(String momentId, JsonObject changes, String by) {
        if (momentId == null || !momentId.startsWith("moment.")) throw ApiException.badRequest("Нет такого момента.");
        JsonObject clean = new JsonObject();
        if (changes.has("title")) {
            String title = blank(Json.string(changes, "title"));
            if (title == null) throw ApiException.badRequest("Пустое название.");
            clean.addProperty("title", clip(title, 120));
        }
        for (String flag : new String[]{"starred", "hidden"})
            if (changes.has(flag)) clean.addProperty(flag, changes.get(flag).isJsonPrimitive() && changes.get(flag).getAsBoolean());
        if (changes.has("replay") && changes.get("replay").isJsonObject()) clean.add("replay", changes.getAsJsonObject("replay").deepCopy());
        if (clean.size() == 0) throw ApiException.badRequest("Нечего менять.");
        clean.addProperty("target", momentId);
        clean.addProperty("by", by);
        history.append("moment_edit", null, null, "Правка момента", clean);
        return apply(momentId, clean);
    }

    private JsonObject apply(String target, JsonObject change) {
        JsonObject merged = edits.computeIfAbsent(target, ignored -> new JsonObject());
        for (Map.Entry<String, JsonElement> entry : change.entrySet()) {
            if (entry.getKey().equals("target") || entry.getKey().equals("by")) continue;
            if (entry.getKey().equals("replay") && entry.getValue().isJsonObject()) {
                JsonObject replay = merged.has("replay") ? merged.getAsJsonObject("replay") : new JsonObject();
                for (Map.Entry<String, JsonElement> file : entry.getValue().getAsJsonObject().entrySet()) replay.add(file.getKey(), file.getValue());
                merged.add("replay", replay);
            } else merged.add(entry.getKey(), entry.getValue());
        }
        return merged.deepCopy();
    }

    // ---- Recording sessions. ----

    private void presence(String kind, JsonObject player, long t) {
        String uuid = Json.string(player, "uuid");
        if (uuid == null) return;
        Session open = openOf(uuid);
        if ("player_leave".equals(kind)) {
            if (open != null && "join".equals(open.source)) stop(open, t, "выход");
            return;
        }
        String auto = rules().sessionAuto;
        boolean starts = "any_join".equals(auto) || "streamer_join".equals(auto) && streamer.test(uuid);
        if (open == null && starts) start(player, t, "join", "");
    }

    /** {@code /clip start}, the admin's button: a session that starts now (or {@code backSeconds} ago). */
    public synchronized JsonObject startSession(JsonObject player, String source, int backSeconds) {
        String uuid = Json.string(player, "uuid");
        if (uuid == null) throw ApiException.badRequest("Нужен игрок.");
        long now = clock.getAsLong();
        Session open = openOf(uuid);
        if (open != null) stop(open, now, source);
        return start(player, now - Math.max(0, backSeconds) * 1000L, source, "").json(now, 0);
    }

    /** {@code /clip stop}: closes the player's open session; null if none. */
    public synchronized JsonObject stopSession(String uuid, String source) {
        Session open = openOf(uuid);
        if (open == null) return null;
        stop(open, clock.getAsLong(), source);
        return open.json(clock.getAsLong(), 0);
    }

    /** OBS on the player's computer: {@code output} (stream or record) is running for {@code elapsedMillis}, or stopped. Opens, corrects
     * or closes the session; a correction under 1.5 s is noise and ignored. */
    public synchronized JsonObject obs(JsonObject player, String output, boolean active, long elapsedMillis) {
        String uuid = Json.string(player, "uuid");
        if (uuid == null) return null;
        long now = clock.getAsLong();
        String what = "record".equals(output) ? "record" : "stream";
        Session open = openOf(uuid);
        if (!active) {
            if (open != null && "obs".equals(open.source) && what.equals(open.output)) stop(open, now, "obs");
            return open == null ? null : open.json(now, 0);
        }
        long start = now - Math.max(0, elapsedMillis);
        if (open != null && "obs".equals(open.source)) {
            // Streaming wins over a local recording: VOD timecodes are what chapters need.
            if (!what.equals(open.output) && "stream".equals(open.output)) return open.json(now, 0);
            if (!what.equals(open.output) || Math.abs(open.start - start) > 1_500) resync(open, start, what);
            return open.json(now, 0);
        }
        if (open != null) stop(open, now, "obs");
        for (Session old : sessions.values())
            if (uuid.equals(old.uuid) && "obs".equals(old.source) && what.equals(old.output) && Math.abs(old.start - start) < REOPEN_SLACK) {
                history.append("rec_session", player, null, "Запись продолжается", Json.obj("action", "reopen", "session", old.id));
                old.end = 0;
                return old.json(now, 0);
            }
        return start(player, start, "obs", what).json(now, 0);
    }

    /** The admin's «Сдвинуть на…»: every timecode of the session moves by {@code shiftMillis} (absolute, not added). */
    public synchronized JsonObject shift(String sessionId, long shiftMillis) {
        Session session = sessions.get(sessionId);
        if (session == null) throw ApiException.notFound("Нет такой сессии записи.");
        long shift = Math.max(-3_600_000, Math.min(3_600_000, shiftMillis));
        history.append("rec_session", session.player, null, "Сдвиг таймкодов на " + shift / 1000 + " с", Json.obj("action", "shift", "session", sessionId, "shift", shift));
        session.shift = shift;
        return session.json(clock.getAsLong(), 0);
    }

    /** The admin types the exact start of the stream. */
    public synchronized JsonObject setStart(String sessionId, long start) {
        Session session = sessions.get(sessionId);
        if (session == null) throw ApiException.notFound("Нет такой сессии записи.");
        if (start <= 0 || session.end != 0 && start >= session.end) throw ApiException.badRequest("Начало должно быть раньше конца.");
        resync(session, start, session.output);
        return session.json(clock.getAsLong(), 0);
    }

    private Session start(JsonObject player, long start, String source, String output) {
        JsonObject record = history.append("rec_session", player, null, "Начало записи (" + sourceTitle(source) + ")",
                Json.obj("action", "start", "start", start, "source", source, "output", output));
        Session session = new Session(Json.string(record, "id"), player, start, source, output);
        sessions.put(session.id, session);
        return session;
    }

    private void stop(Session session, long t, String why) {
        history.append("rec_session", session.player, null, "Конец записи", Json.obj("action", "stop", "session", session.id, "end", t, "why", why));
        session.end = Math.max(t, session.start + 1);
    }

    private void resync(Session session, long start, String output) {
        history.append("rec_session", session.player, null, "Начало записи уточнено", Json.obj("action", "resync", "session", session.id, "start", start, "output", output));
        session.start = start; session.output = output;
    }

    /** The player's newest session (open or not), or null: what {@code /clip export} and {@code /clip shift} work on. */
    public synchronized String latestSession(String uuid) {
        Session best = null;
        for (Session session : sessions.values()) if (uuid != null && uuid.equals(session.uuid) && (best == null || session.start > best.start)) best = session;
        return best == null ? null : best.id;
    }
    /** Shift of a session in milliseconds (for {@code /clip shift +5}). */
    public synchronized long shiftOf(String sessionId) { Session session = sessions.get(sessionId); return session == null ? 0 : session.shift; }

    private Session openOf(String uuid) {
        Session found = null;
        for (Session session : sessions.values()) if (session.open() && uuid != null && uuid.equals(session.uuid)) found = session;
        return found;
    }

    private static String sourceTitle(String source) {
        return switch (source == null ? "" : source) { case "obs" -> "OBS"; case "join" -> "вход в игру"; case "admin" -> "админка"; default -> "команда"; };
    }

    /** Sessions and edits are rebuilt from the history at start. */
    private void load() {
        List<JsonObject> records = new ArrayList<>();
        history.scan(new HistoryStore.Query(Set.of("rec_session", "moment_edit"), null, null, null, null, null, null, 0), record -> { records.add(record); return true; });
        records.sort(Comparator.comparingLong(record -> Json.number(record, "t", 0)));
        for (JsonObject record : records) {
            JsonObject data = object(record, "data");
            if ("moment_edit".equals(Json.string(record, "kind"))) { String target = Json.string(data, "target"); if (target != null) apply(target, data); continue; }
            String action = String.valueOf(Json.string(data, "action"));
            Session session = sessions.get(Json.string(data, "session"));
            switch (action) {
                case "start" -> {
                    JsonObject player = object(record, "player");
                    sessions.put(Json.string(record, "id"), new Session(Json.string(record, "id"), player, Json.number(data, "start", Json.number(record, "t", 0)),
                            Json.string(data, "source"), String.valueOf(Json.string(data, "output") == null ? "" : Json.string(data, "output"))));
                }
                case "stop" -> { if (session != null) session.end = Json.number(data, "end", Json.number(record, "t", 0)); }
                case "reopen" -> { if (session != null) session.end = 0; }
                case "shift" -> { if (session != null) session.shift = Json.number(data, "shift", 0); }
                case "resync" -> { if (session != null) { session.start = Json.number(data, "start", session.start); String out = Json.string(data, "output"); if (out != null) session.output = out; } }
                default -> { }
            }
        }
        // A server that stopped mid-recording left sessions open; they end where the history of their player ends.
        for (Session session : sessions.values()) if (session.open() && !"obs".equals(session.source)) session.end = Math.max(session.start + 1, clock.getAsLong());
    }

    // ---- Reading. ----

    /** Sessions newest first, each with its count of moments; {@code player} — UUID or name, or null for all. */
    public synchronized JsonObject sessions(String player) {
        long now = clock.getAsLong();
        List<Session> list = new ArrayList<>();
        for (Session session : sessions.values())
            if (player == null || player.isBlank() || player.equalsIgnoreCase(session.uuid) || player.equalsIgnoreCase(name(session.player))) list.add(session);
        list.sort(Comparator.comparingLong((Session session) -> session.start).reversed());
        List<JsonObject> moments = all(list.isEmpty() ? now : list.stream().mapToLong(session -> session.start).min().orElse(now), now);
        JsonArray out = new JsonArray();
        for (Session session : list) {
            int count = 0;
            for (JsonObject moment : moments) if (inside(moment, session) && mine(moment, session) && !hidden(moment)) count++;
            out.add(session.json(now, count));
        }
        return Json.obj("sessions", out, "open", openList(now));
    }

    private JsonArray openList(long now) {
        JsonArray open = new JsonArray();
        for (Session session : sessions.values()) if (session.open()) open.add(session.json(now, 0));
        return open;
    }

    /** Moments of a session (or the last {@code hours} without one), oldest first, with edits applied and the timecode in seconds.
     * {@code everyone} — all moments in the session's time, not only the owner's and the world's. */
    public synchronized JsonObject moments(String sessionId, int minWeight, boolean everyone, boolean withHidden, int hours) {
        long now = clock.getAsLong();
        Session session = sessionId == null || sessionId.isBlank() ? null : sessions.get(sessionId);
        if (sessionId != null && !sessionId.isBlank() && session == null) throw ApiException.notFound("Нет такой сессии записи.");
        long from = session != null ? session.start : now - Math.max(1, hours) * 3_600_000L;
        JsonArray items = new JsonArray();
        for (JsonObject moment : all(from, session == null || session.open() ? now : session.end)) {
            if (session != null && (!inside(moment, session) || !everyone && !mine(moment, session))) continue;
            if (session == null && start(moment) < from) continue;
            if (!withHidden && hidden(moment)) continue;
            if (weight(moment) < minWeight && !"manual".equals(type(moment))) continue;
            items.add(view(moment, session));
        }
        JsonArray pending = new JsonArray();
        for (Group group : groups.values()) pending.add(Json.obj("title", group.title(), "weight", group.weight(), "start", group.start(), "players", List.copyOf(group.players.values())));
        return Json.obj("session", session == null ? null : session.json(now, items.size()), "items", items, "pending", pending);
    }

    /** All moments whose start is at or after {@code from} (records are written up to the merge window later). */
    private List<JsonObject> all(long from, long to) {
        List<JsonObject> list = new ArrayList<>();
        long slack = rules().mergeSeconds * 1000L + 120_000;
        history.scan(new HistoryStore.Query(Set.of("moment"), null, null, null, from, to + slack, null, 0), record -> {
            if (Json.number(object(record, "data"), "end", start(record)) >= from && start(record) <= to) list.add(record);
            return true;
        });
        list.sort(Comparator.comparingLong(Moments::start));
        return list;
    }

    private JsonObject view(JsonObject moment, Session session) {
        JsonObject data = object(moment, "data");
        JsonObject edit = edits.get(Json.string(moment, "id"));
        String title = edit != null && edit.has("title") ? edit.get("title").getAsString() : Json.string(data, "title");
        Long tc = session == null ? null : timecode(moment, session);
        return Json.obj("id", Json.string(moment, "id"), "t", Json.number(moment, "t", 0), "start", start(moment), "end", Json.number(data, "end", 0),
                "timecode", tc, "weight", weight(moment), "type", type(moment), "title", title, "originalTitle", Json.string(data, "title"),
                "quote", Json.string(data, "quote"), "players", data == null ? new JsonArray() : data.get("players"), "mob", moment.get("mob"),
                "sources", data == null ? new JsonArray() : data.get("sources"), "by", Json.string(data, "by"),
                "starred", edit != null && edit.has("starred") && edit.get("starred").getAsBoolean(), "hidden", hidden(moment),
                "replay", edit != null && edit.has("replay") ? edit.get("replay") : null);
    }

    /** Seconds into the recording, never below zero. */
    static long timecode(JsonObject moment, Session session) { return Math.max(0, (start(moment) - session.start + session.shift) / 1000); }

    private boolean inside(JsonObject moment, Session session) {
        long start = start(moment);
        return Json.number(object(moment, "data"), "end", start) >= session.start && (session.open() || start <= session.end);
    }
    /** The owner's own moments, the world's (no player) and the owner's manual marks. */
    private static boolean mine(JsonObject moment, Session session) {
        JsonObject data = object(moment, "data");
        JsonElement players = data == null ? null : data.get("players");
        if (players == null || !players.isJsonArray() || players.getAsJsonArray().isEmpty()) return true;
        for (JsonElement one : players.getAsJsonArray())
            if (one.isJsonObject() && session.uuid != null && session.uuid.equals(Json.string(one.getAsJsonObject(), "uuid"))) return true;
        return false;
    }
    private boolean hidden(JsonObject moment) { JsonObject edit = edits.get(Json.string(moment, "id")); return edit != null && edit.has("hidden") && edit.get("hidden").getAsBoolean(); }
    static long start(JsonObject moment) { return Json.number(object(moment, "data"), "start", Json.number(moment, "t", 0)); }
    static int weight(JsonObject moment) { return (int) Json.number(object(moment, "data"), "weight", 0); }
    static String type(JsonObject moment) { return String.valueOf(Json.string(object(moment, "data"), "type")); }

    // ---- Export. ----

    /** youtube — chapters for a VOD description; csv; edl — markers for DaVinci Resolve; xml — sequence markers for Premiere (FCP7 XML). */
    public synchronized Export export(String sessionId, String format, boolean starredOnly, int minWeight, boolean everyone) {
        Session session = sessions.get(sessionId);
        if (session == null) throw ApiException.notFound("Нет такой сессии записи.");
        String kind = format == null ? "youtube" : format.toLowerCase(Locale.ROOT);
        JsonArray items = moments(sessionId, minWeight, everyone, false, 0).getAsJsonArray("items");
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement item : items) if (!starredOnly || item.getAsJsonObject().get("starred").getAsBoolean()) list.add(item.getAsJsonObject());
        String stem = "moments-" + safe(name(session.player)) + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(session.start).atZone(zone()));
        return switch (kind) {
            case "youtube" -> new Export(stem + ".txt", "text/plain; charset=utf-8", youtube(list, session).getBytes(StandardCharsets.UTF_8));
            case "csv" -> new Export(stem + ".csv", "text/csv; charset=utf-8", csv(list).getBytes(StandardCharsets.UTF_8));
            case "edl" -> new Export(stem + ".edl", "text/plain; charset=utf-8", edl(list, stem).getBytes(StandardCharsets.UTF_8));
            case "xml" -> new Export(stem + ".xml", "application/xml; charset=utf-8", xml(list, stem, session).getBytes(StandardCharsets.UTF_8));
            default -> throw ApiException.badRequest("Формат — " + FORMATS + ".");
        };
    }

    private ZoneId zone() { return history.zone(); }

    /** YouTube wants the first chapter at 0:00, chapters at least 10 s apart and at least three of them. */
    static String youtube(List<JsonObject> list, Session session) {
        boolean hours = list.stream().anyMatch(item -> Json.number(item, "timecode", 0) >= 3600);
        StringBuilder out = new StringBuilder();
        long previous = -1;
        for (JsonObject item : list) {
            long tc = Json.number(item, "timecode", 0);
            if (previous < 0 && tc >= 10) { out.append(clock(0, hours)).append(" Начало\n"); previous = 0; }
            if (previous < 0) tc = 0;
            else if (tc - previous < 10) continue;
            out.append(clock(tc, hours)).append(' ').append(oneLine(Json.string(item, "title"))).append('\n');
            previous = tc;
        }
        return out.toString();
    }

    static String csv(List<JsonObject> list) {
        StringBuilder out = new StringBuilder("﻿таймкод;секунды;конец;вес;тип;игроки;название;цитата;повтор\n");
        for (JsonObject item : list) {
            long tc = Json.number(item, "timecode", 0);
            long end = tc + Math.max(5, (Json.number(item, "end", 0) - Json.number(item, "start", 0)) / 1000);
            out.append(clock(tc, true)).append(';').append(tc).append(';').append(clock(end, true)).append(';').append(Json.number(item, "weight", 0)).append(';')
                    .append(cell(typeTitle(Json.string(item, "type")))).append(';').append(cell(names(item.get("players")))).append(';')
                    .append(cell(Json.string(item, "title"))).append(';').append(cell(Json.string(item, "quote"))).append(';')
                    .append(cell(replays(item.get("replay")))).append('\n');
        }
        return out.toString();
    }

    /** CMX 3600 with Resolve marker comments, for a timeline that starts at 01:00:00:00 (Resolve's default). */
    static String edl(List<JsonObject> list, String title) {
        StringBuilder out = new StringBuilder("TITLE: ").append(oneLine(title)).append("\nFCM: NON-DROP FRAME\n\n");
        int n = 0;
        for (JsonObject item : list) {
            long tc = Json.number(item, "timecode", 0) + 3600;
            String in = frames(tc, 0), outPoint = frames(tc, 1);
            out.append(String.format(Locale.ROOT, "%03d  001      V     C        %s %s %s %s  \n", ++n, in, outPoint, in, outPoint));
            out.append(" |C:").append(color(Json.string(item, "type"))).append(" |M:").append(oneLine(Json.string(item, "title")).replace("|", "/"))
                    .append(" |D:1\n\n");
        }
        return out.toString();
    }

    /** FCP7 XML (xmeml 4) with sequence markers; Premiere imports it as a sequence whose markers lie on these times. 30 fps timebase. */
    static String xml(List<JsonObject> list, String title, Session session) {
        long last = list.stream().mapToLong(item -> Json.number(item, "timecode", 0)).max().orElse(0) + 60;
        if (!session.open()) last = Math.max(last, (session.end - session.start) / 1000);
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE xmeml>\n<xmeml version=\"4\">\n<sequence>\n");
        out.append("  <name>").append(xmlText(title)).append("</name>\n  <duration>").append(last * 30).append("</duration>\n");
        out.append("  <rate><timebase>30</timebase><ntsc>FALSE</ntsc></rate>\n");
        for (JsonObject item : list) {
            long tc = Json.number(item, "timecode", 0);
            long length = Math.max(5, (Json.number(item, "end", 0) - Json.number(item, "start", 0)) / 1000);
            out.append("  <marker><name>").append(xmlText(Json.string(item, "title"))).append("</name><comment>")
                    .append(xmlText(Json.string(item, "quote") == null ? "" : Json.string(item, "quote"))).append("</comment><in>")
                    .append(tc * 30).append("</in><out>").append((tc + length) * 30).append("</out></marker>\n");
        }
        out.append("  <media><video><format><samplecharacteristics><rate><timebase>30</timebase><ntsc>FALSE</ntsc></rate></samplecharacteristics></format><track/></video><audio><track/></audio></media>\n");
        out.append("</sequence>\n</xmeml>\n");
        return out.toString();
    }

    /** 12:05 or 1:02:05 — how a timecode reads in chat. */
    public static String clockOf(long seconds) { return clock(seconds, seconds >= 3600); }
    static String clock(long seconds, boolean hours) {
        long s = Math.max(0, seconds);
        return hours ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }
    private static String frames(long seconds, int frame) { return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60, frame); }
    private static String color(String type) {
        return switch (type == null ? "" : type) { case "god" -> "ResolveColorYellow"; case "combat" -> "ResolveColorRed"; case "village" -> "ResolveColorGreen"; default -> "ResolveColorBlue"; };
    }
    static String typeTitle(String type) {
        return switch (type == null ? "" : type) { case "god" -> "бог"; case "combat" -> "бой"; case "village" -> "жители"; case "manual" -> "ручная"; default -> type == null ? "" : type; };
    }
    private static String names(JsonElement players) {
        List<String> names = new ArrayList<>();
        if (players != null && players.isJsonArray()) for (JsonElement one : players.getAsJsonArray()) if (one.isJsonObject()) names.add(who(name(one.getAsJsonObject())));
        return String.join(", ", names);
    }
    private static String replays(JsonElement replay) {
        if (replay == null || !replay.isJsonObject()) return "";
        Set<String> files = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : replay.getAsJsonObject().entrySet()) files.add(entry.getValue().getAsString());
        return String.join(" | ", files);
    }
    private static String cell(String text) {
        String value = text == null ? "" : oneLine(text);
        return value.contains(";") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
    private static String xmlText(String text) {
        return oneLine(text == null ? "" : text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static String oneLine(String text) { return text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").strip(); }
    private static String safe(String text) { String value = text == null ? "" : text.replaceAll("[^\\p{L}\\p{N}_-]+", ""); return value.isEmpty() ? "player" : value; }

    // ---- Small helpers. ----

    static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
    private static String name(JsonObject who) { return Json.string(who, "name"); }
    private static String who(String name) { return name == null || name.isBlank() ? "игрок" : name; }
    private static String blank(String text) { return text == null || text.isBlank() || "null".equals(text) ? null : text.strip(); }
    private static String clip(String text, int max) { String one = oneLine(text); return one.length() <= max ? one : one.substring(0, max - 1) + "…"; }
}
