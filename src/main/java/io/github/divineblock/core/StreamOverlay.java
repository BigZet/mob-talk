package io.github.divineblock.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** «Бог на стриме»: what an OBS overlay may show of the god ({@code overlay} of {@link AdminProtocol}). The game tells it
 * every act the moment it begins ({@link #verdict}) and every line of the god with whom it reached ({@link #line}); the
 * host adds what goes on now ({@link Snapshot}). Never a coordinate, a prayer's text, a prompt or a key; the settings of the
 * group «Бог на стриме» hide the reasons, the sins, the other souls and chosen deeds. Plain Java, for the tests. */
public final class StreamOverlay {
    /** How many of the god's lines are kept, and how many verdicts weigh on his mood. */
    public static final int LINES = 50, VERDICTS = 20;
    /** Verdicts older than this no longer move the mood; a fresh one weighs most. */
    public static final long MOOD_WINDOW_MS = 10 * 60_000L;
    /** The title of a deed the streamer hides ({@code overlayHidden}). */
    public static final String HIDDEN = "Тайное деяние";

    public record Player(String uuid, String name) { }
    /** One act as it began: on whom, wrath, mercy or neither ({@link #act}), which deed and its title, the main reasons, fury. */
    public record Shown(long t, Player player, String act, String deed, String title, List<String> reasons, boolean fury) { }
    /** A soul online: his score of {@link Righteousness} (−100…100), sins, punishments in a row, banned. */
    public record Soul(Player player, double score, int sins, int ladder, boolean banned) { }
    /** What goes on: {@code curse}, {@code blessing} or {@code boss}; its deed (null when no deed laid it, a church guardian),
     * title, on whom (null — on everyone), until when (0 — until it falls) and a boss's health. */
    public record Active(String kind, String deed, String title, Player target, long until, double health, double maxHealth) { }
    /** A moment of the stream: {@code court} or {@code ban}, on whom, until when. */
    public record Event(String kind, String title, Player player, long until) { }
    /** The game right now, as the host reads it on the server thread. */
    public record Snapshot(String streamer, List<Soul> souls, List<Active> active, List<Event> events) { }

    /** One line the god said, and to whom (the UUIDs that got it). The length of the voice comes when it ends. */
    public static final class Line {
        public final long t; public final String text; public final List<String> listeners;
        volatile long millis;
        Line(long t, String text, List<String> listeners) { this.t = t; this.text = text; this.listeners = List.copyOf(listeners); }
        public long millis() { return millis; }
    }

    private static final StreamOverlay SHARED = new StreamOverlay();
    public static StreamOverlay shared() { return SHARED; }

    private final Deque<Line> lines = new ArrayDeque<>();
    private final Deque<Shown> verdicts = new ArrayDeque<>();

    /** An act began. */
    public synchronized void verdict(Shown shown) {
        verdicts.addLast(shown);
        while (verdicts.size() > VERDICTS) verdicts.removeFirst();
    }
    public synchronized Shown last() { return verdicts.peekLast(); }
    public synchronized List<Shown> verdicts() { return new ArrayList<>(verdicts); }

    /** The god said {@code text}, and these players got it; returns the line, so the length of its voice can follow. */
    public synchronized Line line(long t, String text, List<String> listeners) {
        Line line = new Line(t, text == null ? "" : text, listeners);
        lines.addLast(line);
        while (lines.size() > LINES) lines.removeFirst();
        return line;
    }
    /** The voice of {@code line} ended after {@code millis}. */
    public static void voiced(Line line, long millis) { if (line != null && millis > 0) line.millis = millis; }

    /** The lines said after {@code since}, oldest first. */
    public synchronized List<Line> since(long since) {
        List<Line> out = new ArrayList<>();
        for (Line line : lines) if (line.t > since) out.add(line);
        return out;
    }

    public synchronized void clear() { lines.clear(); verdicts.clear(); }

    /** The overlay's side of a deed: {@code wrath}, {@code miracle} (a mercy) or {@code none} (a jest, an omen, silence). */
    public static String act(Miracle.Kind kind) {
        return kind == Miracle.Kind.WRATH ? "wrath" : kind == Miracle.Kind.HELP ? "miracle" : "none";
    }

    /** Up to three reasons that weigh most toward what was chosen, by their text; the bare «heard» is no reason to show. */
    public static List<String> reasons(Verdict verdict) {
        List<String> out = new ArrayList<>();
        for (Verdict.Reason reason : verdict.mainReasons(verdict.reasons.size())) {
            if (reason.favor() == 0 && reason.force() <= 1) continue;
            out.add(reason.text());
            if (out.size() == 3) break;
        }
        return out;
    }

    /** The god's mood, −100 (wrath) … 100 (mercy): six parts the verdicts of the last ten minutes (fresh ones weigh more, fury
     * more than wrath) and four parts the mean righteousness of the souls online. With no verdict lately only the souls speak,
     * and they speak softly, so the number drifts back to calm instead of jumping. */
    public static int mood(List<Double> scores, List<Shown> verdicts, long now) {
        double weight = 0, sum = 0;
        for (Shown shown : verdicts) {
            long age = now - shown.t();
            if (age < 0 || age > MOOD_WINDOW_MS) continue;
            double w = 1 - (double) age / MOOD_WINDOW_MS;
            double side = switch (shown.act()) { case "wrath" -> shown.fury() ? -1.5 : -1; case "miracle" -> 1; default -> 0; };
            weight += w; sum += w * side;
        }
        double tilt = weight == 0 ? 0 : Math.max(-1, Math.min(1, sum / weight)) * Math.min(1, weight);
        double souls = scores.isEmpty() ? 0 : scores.stream().mapToDouble(Double::doubleValue).average().orElse(0) / Righteousness.LIMIT;
        return (int) Math.round(Math.max(-100, Math.min(100, 100 * (0.6 * tilt + 0.4 * souls))));
    }

    /** The mood in one word. */
    public static String word(int mood) {
        if (mood <= -60) return "гневен";
        if (mood <= -20) return "хмур";
        if (mood < 20) return "спокоен";
        if (mood < 60) return "благосклонен";
        return "милостив";
    }

    /** Righteousness (−100…100) as the overlay's mercy, 0…100. */
    public static int mercy(double score) {
        return (int) Math.round(Math.max(0, Math.min(100, (score + Righteousness.LIMIT) * 50 / Righteousness.LIMIT)));
    }

    /** The answer of {@code overlay}: {@code {"ok":true,"disabled":true}} when the streamer switched it off. */
    public JsonObject json(DivineConfig config, Snapshot snapshot, long since, long now) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        if (!config.overlayEnabled) { out.addProperty("disabled", true); return out; }

        JsonObject god = new JsonObject();
        int mood = mood(snapshot.souls().stream().map(Soul::score).toList(), verdicts(), now);
        god.addProperty("mood", mood); god.addProperty("word", word(mood));
        out.add("god", god);

        Shown last = last();
        if (last == null) out.add("verdict", JsonNull.INSTANCE);
        else {
            JsonObject verdict = new JsonObject();
            verdict.addProperty("t", last.t());
            verdict.add("player", player(last.player()));
            verdict.addProperty("act", last.act());
            verdict.addProperty("title", hidden(config, last.deed()) ? HIDDEN : last.title());
            JsonArray reasons = new JsonArray();
            if (config.overlayReasons) last.reasons().stream().limit(3).forEach(reasons::add);
            verdict.add("reasons", reasons);
            out.add("verdict", verdict);
        }

        JsonArray souls = new JsonArray();
        for (Soul soul : snapshot.souls()) {
            if (!config.overlayOthersMercy && !soul.player().uuid().equals(snapshot.streamer())) continue;
            JsonObject one = new JsonObject();
            one.add("player", player(soul.player()));
            one.addProperty("mercy", mercy(soul.score()));
            if (config.overlaySins) one.addProperty("sins", soul.sins());
            one.addProperty("ladder", soul.ladder());
            one.addProperty("banned", soul.banned());
            souls.add(one);
        }
        out.add("souls", souls);

        JsonArray active = new JsonArray();
        for (Active a : snapshot.active()) {
            JsonObject one = new JsonObject();
            one.addProperty("kind", a.kind());
            one.addProperty("title", hidden(config, a.deed()) ? HIDDEN : a.title());
            one.add("target", a.target() == null ? JsonNull.INSTANCE : player(a.target()));
            one.addProperty("until", a.until());
            one.addProperty("health", a.health()); one.addProperty("maxHealth", a.maxHealth());
            active.add(one);
        }
        out.add("active", active);

        JsonArray events = new JsonArray();
        for (Event e : snapshot.events()) {
            JsonObject one = new JsonObject();
            one.addProperty("kind", e.kind()); one.addProperty("title", e.title());
            one.add("player", player(e.player()));
            one.addProperty("until", e.until());
            events.add(one);
        }
        out.add("events", events);

        JsonArray said = new JsonArray();
        for (Line line : since(since)) {
            JsonObject one = new JsonObject();
            one.addProperty("t", line.t); one.addProperty("text", line.text); one.addProperty("millis", line.millis());
            JsonArray listeners = new JsonArray();
            line.listeners.forEach(listeners::add);
            one.add("listeners", listeners);
            said.add(one);
        }
        out.add("lines", said);
        return out;
    }

    private static boolean hidden(DivineConfig config, String deed) { return deed != null && config.overlayHidden.contains(deed); }

    private static JsonObject player(Player player) {
        JsonObject out = new JsonObject();
        out.addProperty("uuid", player.uuid()); out.addProperty("name", player.name());
        return out;
    }
}
