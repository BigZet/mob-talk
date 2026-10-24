package io.github.divineblock.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** What the villages of Mob Talk may learn of the god's deeds (Mob Talk's idea Б1 «Жители знают о гневе бога»): every deed as
 * one JSON event, protocol v1. The game pushes each to Mob Talk's entrypoint {@code mobtalk-events} and keeps the last
 * {@link #CAPACITY} here for the admin op {@code events} ({@code {"since":ms,"limit":n}}, oldest first), so Mob Talk can pull
 * what a push missed. Nothing of Mob Talk is needed: without it the events go nowhere. A curse's end ({@code curse_end})
 * carries the same id as the punishment that laid it. Plain Java, for the tests. */
public final class VillageNews {
    public static final int VERSION = 1, CAPACITY = 200, LIMIT = 50;

    public enum Kind {
        WRATH, MIRACLE, WARNING, JEST, CURSE_END, FORGIVEN, BAN, BOSS;
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A player as the event names him; {@code reason} — why the god struck (the culprit only). */
    public record Soul(UUID uuid, String name, String reason) {
        public Soul { name = name == null ? "" : name; reason = reason == null ? "" : reason; }
    }
    /** A place in a dimension ({@code minecraft:overworld}). */
    public record Place(String dim, int x, int y, int z) { }

    /** One event. {@code group} 1–6 for punishments (К1–К6, the sacred bosses count as К4), 0 otherwise; {@code power} 0–4 for
     * mercies; {@code radius} 0 when the deed has no reach of its own; {@code seconds} the curse's length, 0 for the default;
     * {@code altar} null when nobody prayed at an altar; {@code of} the ids of the punishments a forgiveness or an end lifts. */
    public record Event(String id, long t, Kind kind, String deed, String deedTitle, int group, int power, boolean world,
                        Soul culprit, Soul beneficiary, boolean named, boolean prompted, Place at, int radius, int seconds,
                        Place altar, List<String> reasons, List<String> of) {
        public Event {
            deed = deed == null ? "" : deed; deedTitle = deedTitle == null ? "" : deedTitle;
            reasons = reasons == null ? List.of() : List.copyOf(reasons); of = of == null ? List.of() : List.copyOf(of);
        }

        public JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("v", VERSION);
            o.addProperty("id", id); o.addProperty("t", t); o.addProperty("kind", kind.id());
            o.addProperty("deed", deed); o.addProperty("deedTitle", deedTitle);
            o.addProperty("group", group); o.addProperty("power", power);
            o.addProperty("scope", world ? "world" : "personal");
            if (culprit != null) o.add("culprit", soul(culprit, true));
            if (beneficiary != null) o.add("beneficiary", soul(beneficiary, false));
            o.addProperty("named", named); o.addProperty("prompted", prompted);
            o.addProperty("dim", at.dim()); o.addProperty("x", at.x()); o.addProperty("y", at.y()); o.addProperty("z", at.z());
            o.addProperty("radius", radius); o.addProperty("seconds", seconds);
            if (altar != null) {
                JsonObject a = new JsonObject();
                a.addProperty("dim", altar.dim()); a.addProperty("x", altar.x()); a.addProperty("y", altar.y()); a.addProperty("z", altar.z());
                o.add("altar", a);
            }
            JsonArray why = new JsonArray();
            reasons.forEach(why::add);
            o.add("reasons", why);
            JsonArray lifted = new JsonArray();
            of.forEach(lifted::add);
            o.add("of", lifted);
            return o;
        }
        private static JsonObject soul(Soul soul, boolean reason) {
            JsonObject s = new JsonObject();
            s.addProperty("uuid", soul.uuid().toString()); s.addProperty("name", soul.name());
            if (reason) s.addProperty("reason", soul.reason());
            return s;
        }
    }

    /** How an act reads for the villages; null for the omens that change nothing (the thunder omen is the god's warning). */
    public static Kind kind(Miracle miracle) {
        if (miracle == Miracle.ETERNAL_BAN) return Kind.BAN;
        if (miracle == Miracle.GOD_MACE || miracle.name().startsWith("BOSS_")) return Kind.BOSS;
        return switch (miracle.kind) {
            case WRATH -> Kind.WRATH;
            case HELP -> Kind.MIRACLE;
            case JEST -> Kind.JEST;
            case OMEN -> miracle == Miracle.OMEN_THUNDER ? Kind.WARNING : null;
        };
    }

    /** The punishment's group, 1 (К1) to 6 (К6); the sacred bosses' own group counts as К4. 0 for anything but a punishment. */
    public static int group(Miracle miracle) {
        Kind kind = kind(miracle);
        if (kind != Kind.WRATH && kind != Kind.BOSS && kind != Kind.BAN) return 0;
        for (MiracleGroup group : MiracleGroup.values()) {
            if (group.kind != Miracle.Kind.WRATH || !group.members.contains(miracle)) continue;
            return group == MiracleGroup.KS ? 4 : group.ordinal() - MiracleGroup.K1.ordinal() + 1 - (group.ordinal() > MiracleGroup.KS.ordinal() ? 1 : 0);
        }
        return 1;
    }

    /** How far a world act reaches around the sinner, in blocks; 0 for a personal act and for the sky itself (weather, time). */
    public static int radius(Miracle miracle) {
        if (MiracleScope.of(miracle) != MiracleScope.WORLD) return 0;
        return switch (miracle) {
            case WEATHER_CLEAR, TIME_RUSH -> 0;
            case FIRE_RING, ANVIL_RAIN, SINKHOLE -> 8;
            case CHUNK_VOID -> 16;
            case EARTHQUAKE, TNT_RAIN, WITHER, GOD_MACE -> 32;
            case METEOR, TORNADO, BABEL -> 48;
            case GREAT_CRATER -> 80;
            default -> 64;
        };
    }

    // ---- The buffer the admin op «events» reads. ----

    private final Deque<Event> recent = new ArrayDeque<>();
    private long last;
    private static final VillageNews SHARED = new VillageNews();
    public static VillageNews shared() { return SHARED; }

    public static String newId() { return UUID.randomUUID().toString(); }

    /** The event's time: now, but always after the previous one, so a pull «since» the newest never skips a twin. */
    public synchronized long stamp(long now) {
        last = Math.max(now, last + 1);
        return last;
    }

    public synchronized void add(Event event) {
        recent.addLast(event);
        while (recent.size() > CAPACITY) recent.removeFirst();
    }

    /** The events after {@code since}, oldest first, at most {@code limit}. */
    public synchronized List<Event> since(long since, int limit) {
        List<Event> out = new ArrayList<>();
        for (Event event : recent) if (event.t() > since && out.size() < Math.max(1, Math.min(LIMIT, limit))) out.add(event);
        return out;
    }

    public synchronized void clear() { recent.clear(); }
}
