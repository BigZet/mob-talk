package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.*;

/** What the villages know of the god's deeds (idea Б1, «Жители знают о гневе бога»): the divine block reports every deed it does
 * as one JSON event ({@link Deed}), and the village that saw it talks, fears and blames the sinner by name, or thanks the righteous.
 * Here: the event's shape, the reaction table (how loud a rumour, how much a sufferer or a witness turns against the culprit), the
 * rumour's words, and the ledger the world keeps — events already counted, the curses still on the villages, the blessed, what a
 * player lost in a village today (the daily cap), the deltas each deed applied (half come back on penance), the bell assemblies and
 * the exiles they decided. Pure Java, persisted in the world memory, every mutation under the store lock. */
public final class DivineTidings {
    /** A curse with no length of its own counts as hanging over the village this long. */
    public static final long DEFAULT_CURSE_MILLIS = 10 * 60_000L;
    /** A blessing makes the villagers call the player blessed for one game day. */
    public static final long BLESSING_MILLIS = VillageRumors.DAY_MILLIS;
    /** How many event ids the ledger remembers, so neither the push nor the pull counts one twice. */
    public static final int SEEN = 300;
    /** A god's relation drop never takes a villager's relationship below this on its own (exile needs the village's own reasons). */
    public static final int RELATION_FLOOR = -39;
    /** A relative of a villager killed by a curse turns against the sinner by this much. */
    public static final int MOURNER_DELTA = -50;

    public enum Kind {
        WRATH, MIRACLE, WARNING, JEST, CURSE_END, FORGIVEN, BAN, BOSS;
        public static Kind of(String name) {
            if (name == null) return null;
            try { return valueOf(name.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return null; }
        }
        /** A punishment: the village fears and blames. */
        public boolean wrath() { return this == WRATH || this == BOSS || this == BAN; }
    }
    /** A player as the event names them. */
    public record Soul(UUID id, String name, String reason) {
        public Soul { name = name == null ? "" : name.strip(); reason = reason == null ? "" : reason.strip(); }
    }
    /** One deed of the god, as the divine block sends it (docs: Б1 «Жители знают о гневе бога», «Что в событии»). */
    public record Deed(String id, long time, Kind kind, String deed, String title, int group, int power, boolean world,
                       Soul culprit, Soul beneficiary, boolean named, boolean prompted, String dimension, int x, int y, int z, int radius,
                       int seconds, String altarDimension, int altarX, int altarZ, List<String> reasons, List<String> of) {
        public Deed {
            deed = deed == null ? "" : deed; title = title == null || title.isBlank() ? deed : title.strip();
            group = Math.max(0, Math.min(6, group)); power = Math.max(0, Math.min(4, power));
            dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
            radius = Math.max(0, radius); seconds = Math.max(0, seconds);
            altarDimension = altarDimension == null ? "" : altarDimension;
            reasons = reasons == null ? List.of() : List.copyOf(reasons); of = of == null ? List.of() : List.copyOf(of);
        }
        /** The player the village may blame: the culprit of a punishment; the one forgiven on penance. */
        public Soul blamed() { return culprit; }
        /** The altar where the prayer was said, when the event names one. */
        public boolean hasAltar() { return !altarDimension.isBlank(); }
        /** How long the curse hangs over the village. */
        public long curseMillis() { return seconds > 0 ? seconds * 1000L : DEFAULT_CURSE_MILLIS; }
        /** The deed is heard in every known village of its dimension, far ones a step quieter: К5 and the eternal ban. */
        public boolean everywhere() { return kind == Kind.BAN || kind.wrath() && group >= 5; }
        /** The bell rings and the street empties: a punishment of group 3 and up, or a boss. */
        public boolean alarm() { return kind == Kind.BOSS || kind == Kind.WRATH && group >= 3; }
        /** The golem steps between the sinner and the villagers. */
        public boolean golem() { return kind == Kind.BOSS || kind == Kind.WRATH && group >= 4; }
    }

    /** Parses one event; throws IllegalArgumentException on anything unusable (no id, unknown kind). */
    public static Deed parse(String json) {
        JsonObject o;
        try { o = JsonParser.parseString(json).getAsJsonObject(); } catch (RuntimeException bad) { throw new IllegalArgumentException("Событие бога — не JSON-объект"); }
        String id = string(o, "id");
        Kind kind = Kind.of(string(o, "kind"));
        if (id.isBlank() || kind == null) throw new IllegalArgumentException("Событие бога без id или с неизвестным kind");
        JsonObject altar = o.has("altar") && o.get("altar").isJsonObject() ? o.getAsJsonObject("altar") : null;
        String scope = string(o, "scope");
        return new Deed(id, number(o, "t", System.currentTimeMillis()), kind, string(o, "deed"), string(o, "deedTitle"),
                (int) number(o, "group", 0), (int) number(o, "power", 0), "world".equals(scope),
                soul(o, "culprit"), soul(o, "beneficiary"), bool(o, "named"), !o.has("prompted") || bool(o, "prompted"),
                string(o, "dim"), (int) number(o, "x", 0), (int) number(o, "y", 64), (int) number(o, "z", 0), (int) number(o, "radius", 0),
                (int) number(o, "seconds", 0), altar == null ? "" : string(altar, "dim").isBlank() ? string(o, "dim") : string(altar, "dim"),
                altar == null ? 0 : (int) number(altar, "x", 0), altar == null ? 0 : (int) number(altar, "z", 0), strings(o, "reasons"), strings(o, "of"));
    }
    private static Soul soul(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonObject()) return null;
        JsonObject s = o.getAsJsonObject(key);
        UUID id;
        try { id = UUID.fromString(string(s, "uuid")); } catch (IllegalArgumentException bad) { return null; }
        return new Soul(id, TextBounds.characters(string(s, "name"), 40), TextBounds.characters(string(s, "reason"), 160));
    }
    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
    private static long number(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        try { return e == null || e.isJsonNull() ? fallback : Math.round(e.getAsDouble()); } catch (RuntimeException bad) { return fallback; }
    }
    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try { return e != null && !e.isJsonNull() && e.getAsBoolean(); } catch (RuntimeException bad) { return false; }
    }
    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) if (e.isJsonPrimitive() && !e.getAsString().isBlank() && out.size() < 8) out.add(TextBounds.characters(e.getAsString().strip(), 160));
        return out;
    }

    // ---- The reaction table (doc «Сила реакции по умолчанию»). ----

    /** How a village takes one deed: the rumour's importance (1–5) and sign, and the relationship change of a villager who
     * suffered from it and of one who saw it and knows the culprit. Zero importance: no rumour at all. */
    public record Reaction(int importance, int sign, int sufferer, int witness) {
        public static final Reaction NONE = new Reaction(0, 0, 0, 0);
        /** The same scaled by the strength setting (percent); a far village hears it a step quieter and nobody there suffered or saw. */
        Reaction scaled(int percent) { return new Reaction(importance, sign, Math.round(sufferer * percent / 100f), Math.round(witness * percent / 100f)); }
        public Reaction far() { return new Reaction(Math.max(1, importance - 1), sign, 0, 0); }
    }
    public static Reaction reaction(Deed deed, int strengthPercent) {
        Reaction base = switch (deed.kind()) {
            case WARNING -> new Reaction(1, -1, 0, -2);
            case JEST -> new Reaction(1, 0, 0, 0);
            case MIRACLE -> deed.power() >= 3 ? new Reaction(Math.min(4, deed.power()), 1, 10, 5) : new Reaction(2, 1, 5, 3);
            case FORGIVEN -> new Reaction(3, 1, 0, 0);
            case BAN -> new Reaction(5, -1, 0, 0);
            case BOSS -> new Reaction(4, -1, -20, -12);
            case WRATH -> switch (deed.group()) {
                case 0, 1, 2 -> new Reaction(2, -1, -5, -5);
                case 3 -> new Reaction(3, -1, -10, -8);
                case 4 -> new Reaction(4, -1, -20, -12);
                default -> new Reaction(5, -1, -30, -15);
            };
            case CURSE_END -> Reaction.NONE;
        };
        return base.scaled(Math.max(0, Math.min(200, strengthPercent)));
    }

    /** The rumour's words; {@code name} "" when the village does not know whose sins brought it. */
    public static String rumorText(Deed deed, String name) {
        String what = "«" + TextBounds.characters(deed.title(), 60) + "»";
        boolean known = name != null && !name.isBlank();
        String reason = deed.culprit() == null || deed.culprit().reason().isBlank() ? "" : ": " + TextBounds.characters(deed.culprit().reason(), 120);
        return switch (deed.kind()) {
            case WRATH -> known ? "на деревню пала божья кара " + what + " за грехи игрока " + name + reason
                    : "на деревню пала божья кара " + what + " за чьи-то грехи, а за чьи — никто не знает";
            case BOSS -> known ? "бог наслал на округу " + what + " за грехи игрока " + name + reason
                    : "бог наслал на округу " + what + ", а за чьи грехи — неведомо";
            case WARNING -> known ? "над игроком " + name + " было грозное знамение " + what + ": бог им недоволен"
                    : "было грозное знамение " + what + ", не к добру это";
            case JEST -> known ? "бог подшутил над игроком " + name + ": " + what : "бог над кем-то подшутил: " + what;
            case MIRACLE -> known ? "по молитве игрока " + name + " бог явил милость " + what + ": благословенный человек"
                    : "бог явил милость " + what;
            case FORGIVEN -> known ? "игрок " + name + " покаялся, и бог снял свой гнев" + (deed.title().isBlank() ? "" : " (" + what + ")")
                    : "кто-то покаялся, и бог сменил гнев на милость";
            case BAN -> known ? "бог навеки изгнал игрока " + name + " из этого мира" : "бог навеки изгнал кого-то из этого мира";
            case CURSE_END -> "кара " + what + " миновала";
        };
    }

    // ---- The ledger. ----

    /** A curse still on some villages. */
    static final class Curse {
        String id = "", title = "", culpritId = "", culpritName = ""; int group; long start, until; List<String> villages = new ArrayList<>();
        Curse copy() { Curse c = new Curse(); c.id = id; c.title = title; c.culpritId = culpritId; c.culpritName = culpritName; c.group = group; c.start = start; c.until = until; c.villages = new ArrayList<>(villages); return c; }
    }
    /** A player the god blessed lately. */
    static final class Blessing {
        String title = "", villages = ""; long until;
        Blessing copy() { Blessing b = new Blessing(); b.title = title; b.villages = villages; b.until = until; return b; }
    }
    /** What one player lost in one village on one game day, and how many punishments for them fell on it. */
    static final class Day {
        long day = -1; int lost, wraths;
        Day copy() { Day d = new Day(); d.day = day; d.lost = lost; d.wraths = wraths; return d; }
    }
    /** One relationship change a deed made, so penance can give half of it back. */
    static final class Delta {
        String mob = "", player = ""; int delta;
        Delta copy() { Delta d = new Delta(); d.mob = mob; d.player = player; d.delta = delta; return d; }
    }
    static final class Applied {
        long time; String player = ""; boolean restored; List<Delta> deltas = new ArrayList<>();
        Applied copy() { Applied a = new Applied(); a.time = time; a.player = player; a.restored = restored; for (Delta d : deltas) a.deltas.add(d.copy()); return a; }
    }
    /** The elder called the village to the bell: the sinner repents before {@code deadline} or is driven out. */
    static final class Assembly {
        String village = "", player = "", name = ""; long deadline; boolean announced;
        Assembly copy() { Assembly a = new Assembly(); a.village = village; a.player = player; a.name = name; a.deadline = deadline; a.announced = announced; return a; }
    }

    /** Event id → when it was counted. */
    Map<String, Long> seen = new LinkedHashMap<>();
    /** The newest event time the pull fallback already asked for. */
    long pulledSince;
    List<Curse> curses = new ArrayList<>();
    /** Player UUID → their blessing. */
    Map<String, Blessing> blessed = new LinkedHashMap<>();
    /** "village|player" → today's losses. */
    Map<String, Day> days = new LinkedHashMap<>();
    /** Event id → the deltas it applied. */
    Map<String, Applied> applied = new LinkedHashMap<>();
    /** "village|player" → an assembly waiting for penance. */
    Map<String, Assembly> assemblies = new LinkedHashMap<>();
    /** "village|player" the bell assembly drove out; only forgiveness lifts it. */
    Set<String> exiled = new LinkedHashSet<>();

    static String key(String village, String player) { return village + "|" + player; }
    static long day(long now) { return now / VillageRumors.DAY_MILLIS; }

    /** Whether this event was already counted; marks it counted otherwise. */
    public boolean first(String id, long now) {
        if (id == null || id.isBlank() || seen.containsKey(id)) return false;
        seen.put(id, now);
        while (seen.size() > SEEN) seen.remove(seen.keySet().iterator().next());
        return true;
    }
    public long pulledSince() { return pulledSince; }
    public void pulled(long time) { pulledSince = Math.max(pulledSince, time); }

    /** How much of a {@code loss} (positive) the daily cap still lets through for this player in this village. */
    int allowance(String village, String player, int loss, int cap, long now) {
        Day d = days.get(key(village, player));
        long today = day(now);
        int lost = d == null || d.day != today ? 0 : d.lost;
        return Math.max(0, Math.min(loss, cap - lost));
    }
    void lose(String village, String player, int loss, long now) {
        Day d = days.computeIfAbsent(key(village, player), ignored -> new Day());
        long today = day(now);
        if (d.day != today) { d.day = today; d.lost = 0; d.wraths = 0; }
        d.lost += Math.max(0, loss);
    }
    /** One more punishment for this player fell on the village today; returns today's count. */
    int punished(String village, String player, long now) {
        Day d = days.computeIfAbsent(key(village, player), ignored -> new Day());
        long today = day(now);
        if (d.day != today) { d.day = today; d.lost = 0; d.wraths = 0; }
        return ++d.wraths;
    }
    void record(String eventId, String player, String mob, int delta, long now) {
        Applied a = applied.computeIfAbsent(eventId, ignored -> new Applied());
        a.time = now; a.player = player;
        Delta d = new Delta(); d.mob = mob; d.player = player; d.delta = delta;
        a.deltas.add(d);
    }
    /** The deltas a player's punishments made within a game day that penance has not yet halved; marks them restored. */
    List<Delta> restore(String player, long now) {
        List<Delta> back = new ArrayList<>();
        for (Applied a : applied.values()) {
            if (a.restored || !a.player.equals(player) || now - a.time > VillageRumors.DAY_MILLIS) continue;
            a.restored = true;
            for (Delta d : a.deltas) if (d.delta < 0) back.add(d);
        }
        return back;
    }

    void curse(Deed deed, List<String> villages, long now) {
        if (villages.isEmpty()) return;
        Curse c = new Curse();
        c.id = deed.id(); c.title = deed.title(); c.group = deed.group(); c.start = now; c.until = now + deed.curseMillis(); c.villages = new ArrayList<>(villages);
        if (deed.culprit() != null) { c.culpritId = deed.culprit().id().toString(); c.culpritName = deed.culprit().name(); }
        curses.removeIf(old -> old.id.equals(c.id));
        curses.add(c);
    }
    /** The curse ended (or was lifted): gone from every village; returns the villages it hung over. */
    List<String> lift(String id) {
        List<String> villages = new ArrayList<>();
        curses.removeIf(c -> { if (!c.id.equals(id)) return false; villages.addAll(c.villages); return true; });
        return villages;
    }
    /** Every curse of this player is lifted (penance); returns the villages they hung over. */
    List<String> liftAll(String player) {
        List<String> villages = new ArrayList<>();
        curses.removeIf(c -> { if (!c.culpritId.equals(player)) return false; villages.addAll(c.villages); return true; });
        return villages;
    }
    /** The curse hanging over this village now, the newest first; null for none. */
    public CurseView curseOn(String village, long now) {
        Curse best = null;
        for (Curse c : curses) if (c.until > now && c.villages.contains(village) && (best == null || c.start > best.start)) best = c;
        return best == null ? null : new CurseView(best.id, best.title, best.culpritId, best.culpritName, best.group, best.start, best.until);
    }
    public record CurseView(String id, String title, String culpritId, String culpritName, int group, long start, long until) {
        public boolean blames(UUID player) { return player != null && culpritId.equals(player.toString()); }
        public boolean known() { return !culpritName.isBlank(); }
    }
    void bless(UUID player, String title, long now) {
        Blessing b = new Blessing(); b.title = title; b.until = now + BLESSING_MILLIS;
        blessed.put(player.toString(), b);
    }
    /** The blessing's title while it lasts, else "". */
    public String blessing(UUID player, long now) {
        Blessing b = player == null ? null : blessed.get(player.toString());
        return b == null || b.until <= now ? "" : b.title;
    }

    /** Opens an assembly unless one waits already; returns whether it is new. */
    boolean assemble(String village, UUID player, String name, long deadline) {
        String k = key(village, player.toString());
        if (assemblies.containsKey(k) || exiled.contains(k)) return false;
        Assembly a = new Assembly(); a.village = village; a.player = player.toString(); a.name = name; a.deadline = deadline;
        assemblies.put(k, a);
        return true;
    }
    public record AssemblyView(String village, UUID player, String name, long deadline, boolean announced) { }
    public List<AssemblyView> assemblies() {
        List<AssemblyView> out = new ArrayList<>();
        for (Assembly a : assemblies.values()) {
            try { out.add(new AssemblyView(a.village, UUID.fromString(a.player), a.name, a.deadline, a.announced)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }
    void announced(String village, UUID player) { Assembly a = assemblies.get(key(village, player.toString())); if (a != null) a.announced = true; }
    /** The sinner repented: every assembly waiting for them is dismissed. */
    void dismiss(UUID player) { assemblies.values().removeIf(a -> a.player.equals(player.toString())); }
    /** The deadline passed: the village drives the sinner out. */
    void exile(String village, UUID player) {
        String k = key(village, player.toString());
        assemblies.remove(k);
        exiled.add(k);
    }
    public boolean exiled(String village, UUID player) { return village != null && player != null && exiled.contains(key(village, player.toString())); }
    /** The village forgave the player (the command or the admin): exile, assembly and today's losses are wiped there. */
    void forgive(String village, UUID player) {
        String k = key(village, player.toString());
        exiled.remove(k); assemblies.remove(k); days.remove(k);
    }

    /** Old entries go: curses long ended, blessings past, days and applied deltas older than a game day. */
    void trim(long now) {
        curses.removeIf(c -> c.until + VillageRumors.DAY_MILLIS < now);
        blessed.values().removeIf(b -> b.until <= now);
        days.values().removeIf(d -> d.day < day(now) - 1);
        applied.values().removeIf(a -> now - a.time > 2 * VillageRumors.DAY_MILLIS);
    }

    void sanitize() {
        if (seen == null) seen = new LinkedHashMap<>();
        seen.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        if (curses == null) curses = new ArrayList<>();
        curses.removeIf(c -> c == null || c.id == null);
        for (Curse c : curses) {
            if (c.villages == null) c.villages = new ArrayList<>();
            if (c.title == null) c.title = ""; if (c.culpritId == null) c.culpritId = ""; if (c.culpritName == null) c.culpritName = "";
        }
        if (blessed == null) blessed = new LinkedHashMap<>();
        blessed.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        for (Blessing b : blessed.values()) { if (b.title == null) b.title = ""; if (b.villages == null) b.villages = ""; }
        if (days == null) days = new LinkedHashMap<>();
        days.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        if (applied == null) applied = new LinkedHashMap<>();
        applied.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        for (Applied a : applied.values()) {
            if (a.player == null) a.player = "";
            if (a.deltas == null) a.deltas = new ArrayList<>();
            a.deltas.removeIf(d -> d == null || d.mob == null || d.player == null);
        }
        if (assemblies == null) assemblies = new LinkedHashMap<>();
        assemblies.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().village == null || e.getValue().player == null);
        for (Assembly a : assemblies.values()) if (a.name == null) a.name = "";
        if (exiled == null) exiled = new LinkedHashSet<>();
        exiled.removeIf(Objects::isNull);
    }
    DivineTidings copy() {
        DivineTidings c = new DivineTidings();
        c.seen = new LinkedHashMap<>(seen); c.pulledSince = pulledSince;
        for (Curse curse : curses) c.curses.add(curse.copy());
        blessed.forEach((k, b) -> c.blessed.put(k, b.copy()));
        days.forEach((k, d) -> c.days.put(k, d.copy()));
        applied.forEach((k, a) -> c.applied.put(k, a.copy()));
        assemblies.forEach((k, a) -> c.assemblies.put(k, a.copy()));
        c.exiled = new LinkedHashSet<>(exiled);
        return c;
    }

    /** The event back as JSON for the admin history. */
    public static JsonObject summary(Deed deed, String village, String villageName, String blamed, int change) {
        JsonObject json = new JsonObject();
        json.addProperty("event", deed.id()); json.addProperty("kind", deed.kind().name().toLowerCase(Locale.ROOT));
        json.addProperty("deed", deed.deed()); json.addProperty("title", deed.title()); json.addProperty("group", deed.group());
        json.addProperty("village", village); json.addProperty("villageName", villageName); json.addProperty("blamed", blamed);
        json.addProperty("change", change);
        JsonArray reasons = new JsonArray(); deed.reasons().forEach(reasons::add); json.add("reasons", reasons);
        return json;
    }

    /** The one with the larger size, so a sufferer who also saw it keeps the heavier change. */
    public static int stronger(int a, int b) { return Math.abs(a) >= Math.abs(b) ? a : b; }
}
