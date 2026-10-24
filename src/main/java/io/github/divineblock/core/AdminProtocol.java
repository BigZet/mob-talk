package io.github.divineblock.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The web admin's protocol, Mob Talk's entrypoint {@code mobtalk-admin}: one JSON request in, one JSON answer out, without
 * any shared code between the two mods. Requests ({@code {"op":…}}):
 * <ul>
 *   <li>{@code describe} — the server's settings by group; types {@code int/bool/enum/string/flags} ({@link DivineSettings});</li>
 *   <li>{@code values} — {@code {key: value}}; a {@code flags} value is the comma-joined ids switched off;</li>
 *   <li>{@code patch} {@code {"values":{…}}} — applied at once, without a restart; {@code {ok, applied, errors}};</li>
 *   <li>{@code journal} {@code {"since":ms,"limit":n}} — the god's journal, oldest first;</li>
 *   <li>{@code actions} / {@code action} {@code {"id","args":{…}}} — the admin's buttons ({@code clear} is the old name of
 *       {@code boss_clear});</li>
 *   <li>{@code souls} — per soul {@code player, sins, ladder, banned, curses, mercy, label, deeds:[{text, t, day}]};</li>
 *   <li>{@code active} — {@code bosses:[{uuid, id, title, health, maxHealth, since, phase, target, dimension, x, y, z}],
 *       curses, godAvatar:{active, player}, skyCourt:{active, player}, blessings:[{player, title, until}]};</li>
 *   <li>{@code prayers} {@code {"since":ms,"limit":n,"player":"name or uuid"}} — heard prayers, newest first ({@link PrayerLog});</li>
 *   <li>{@code events} {@code {"since":ms,"limit":n}} — the god's deeds for Mob Talk's villages, oldest first ({@link VillageNews}).</li>
 * </ul>
 * Times are epoch ms, a player is {@code {uuid, name}}. The game behind it is a {@link Host}, so the protocol itself is plain
 * Java and tested without Minecraft. */
public final class AdminProtocol {
    /** One button of the admin: its id, title and the arguments it takes (each a string). */
    public record Action(String id, String title, Map<String, String> args) { }

    /** What the protocol needs from the running game. */
    public interface Host {
        DivineConfig config();
        /** Saves the server's config and makes the game use it (players' screens included). */
        void save(DivineConfig config) throws IOException;
        GodJournal journal();
        List<Action> actions();
        /** Performs an action; returns what happened in a few words, or throws with a reason the admin can show. */
        String run(String id, Map<String, String> args);
        /** The souls for the admin's «Бог» tab: {@code souls} of {player:{uuid,name}, sins, ladder, banned, curses:[{title, until}]}, until in epoch ms. */
        default JsonObject souls() { throw new IllegalStateException("души недоступны"); }
        /** What goes on now: {@code bosses} of {uuid, id, title, health, maxHealth, since, phase, target, dimension, x, y, z},
         * {@code curses} of {player, title, until}, {@code godAvatar} and {@code skyCourt} of {active, player} and
         * {@code blessings} of {player, title, until}. */
        default JsonObject active() { throw new IllegalStateException("нет данных"); }
        /** The heard prayers ({@code prayers}): this game's log by default. */
        default PrayerLog prayers() { return PrayerLog.shared(); }
        /** The deeds told to Mob Talk's villages ({@code events}): this game's buffer by default. */
        default VillageNews news() { return VillageNews.shared(); }
    }

    private final Host host;
    private final Object lock = new Object();

    public AdminProtocol(Host host) { this.host = host; }

    public String handle(String request) {
        try {
            JsonObject in = JsonParser.parseString(request == null ? "" : request).getAsJsonObject();
            String op = in.has("op") ? in.get("op").getAsString() : "";
            return switch (op) {
                case "describe" -> DivineSettings.describe().toString();
                case "values" -> DivineSettings.values(host.config()).toString();
                case "patch" -> patch(in).toString();
                case "journal" -> journal(in).toString();
                case "actions" -> actions().toString();
                case "action" -> action(in).toString();
                case "souls" -> guarded(host::souls).toString();
                case "active" -> guarded(host::active).toString();
                case "prayers" -> prayers(in).toString();
                case "events" -> events(in).toString();
                default -> error("неизвестный запрос: " + op);
            };
        } catch (RuntimeException broken) {
            return error("запрос не разобран: " + broken.getClass().getSimpleName());
        }
    }

    private JsonObject patch(JsonObject in) {
        if (!in.has("values") || !in.get("values").isJsonObject()) return errorObject("нет values");
        synchronized (lock) {
            DivineConfig config = host.config().copy();
            DivineSettings.Patched patched = DivineSettings.patch(config, in.getAsJsonObject("values"));
            if (!patched.applied().isEmpty()) {
                try { host.save(config.sanitized()); }
                catch (IOException failure) { return errorObject("настройки не сохранены: " + failure.getMessage()); }
            }
            return patched.json();
        }
    }

    private JsonObject journal(JsonObject in) {
        long since = in.has("since") ? in.get("since").getAsLong() : 0;
        int limit = in.has("limit") ? Math.max(1, Math.min(GodJournal.LIMIT, in.get("limit").getAsInt())) : 200;
        JsonArray items = new JsonArray();
        for (GodJournal.Entry entry : host.journal().since(since, limit)) {
            JsonObject item = new JsonObject();
            item.addProperty("t", entry.t()); item.addProperty("kind", entry.kind().id());
            item.addProperty("player", entry.player()); item.addProperty("text", entry.text());
            items.add(item);
        }
        JsonObject out = new JsonObject();
        out.add("items", items);
        return out;
    }

    private JsonObject events(JsonObject in) {
        long since = in.has("since") ? in.get("since").getAsLong() : 0;
        int limit = in.has("limit") ? in.get("limit").getAsInt() : VillageNews.LIMIT;
        JsonArray items = new JsonArray();
        for (VillageNews.Event event : host.news().since(since, limit)) items.add(event.json());
        JsonObject out = new JsonObject();
        out.add("items", items);
        return out;
    }

    private JsonObject prayers(JsonObject in) {
        long since = in.has("since") ? in.get("since").getAsLong() : 0;
        int limit = in.has("limit") ? Math.max(1, Math.min(PrayerLog.LIMIT, in.get("limit").getAsInt())) : 100;
        String player = in.has("player") && in.get("player").isJsonPrimitive() ? in.get("player").getAsString() : "";
        JsonArray items = new JsonArray();
        for (PrayerLog.Entry entry : host.prayers().query(since, limit, player)) {
            JsonObject item = new JsonObject();
            item.addProperty("t", entry.t());
            JsonObject who = new JsonObject();
            who.addProperty("uuid", entry.uuid()); who.addProperty("name", entry.name());
            item.add("player", who);
            item.addProperty("text", entry.text());
            item.addProperty("tone", entry.tone()); item.addProperty("toneTitle", entry.toneTitle());
            item.addProperty("offering", entry.offering()); item.addProperty("offeringValue", entry.offeringValue());
            item.addProperty("answer", entry.answer());
            PrayerLog.Choice c = entry.choice();
            JsonObject choice = new JsonObject();
            choice.addProperty("kind", c.kind()); choice.addProperty("deed", c.deed()); choice.addProperty("deedTitle", c.deedTitle());
            choice.addProperty("power", c.power()); choice.addProperty("decidedBy", c.decidedBy()); choice.addProperty("note", c.note());
            item.add("choice", choice);
            JsonArray reasons = new JsonArray();
            for (PrayerLog.Reason r : entry.reasons()) {
                JsonObject reason = new JsonObject();
                reason.addProperty("title", r.title()); reason.addProperty("mercy", r.mercy()); reason.addProperty("power", r.power());
                reasons.add(reason);
            }
            item.add("reasons", reasons);
            PrayerLog.Decision d = entry.decision();
            if (d != null) {
                JsonObject decision = new JsonObject();
                decision.addProperty("favor", d.favor()); decision.addProperty("force", d.force()); decision.addProperty("inclination", d.inclination());
                decision.addProperty("level", d.level()); decision.addProperty("budget", d.budget()); decision.addProperty("escalation", d.escalation());
                decision.addProperty("fury", d.fury()); decision.addProperty("forgiveness", d.forgiveness()); decision.addProperty("group", d.group());
                decision.add("jev", odds(d.jev())); decision.add("ladder", odds(d.ladder())); decision.add("odds", odds(d.odds()));
                JsonObject labels = new JsonObject();
                d.labels().forEach(labels::addProperty);
                decision.add("labels", labels);
                item.add("decision", decision);
            }
            items.add(item);
        }
        JsonObject out = new JsonObject();
        out.add("items", items);
        return out;
    }

    private static JsonObject odds(Map<String, Double> odds) {
        JsonObject out = new JsonObject();
        if (odds != null) odds.forEach((id, p) -> out.addProperty(id, Math.round(p * 1000) / 1000.0));
        return out;
    }

    /** Old names of actions the admin may still send. */
    private static final Map<String, String> ALIASES = Map.of("clear", "boss_clear");

    private JsonObject actions() {
        JsonArray list = new JsonArray();
        for (Action action : host.actions()) {
            JsonObject a = new JsonObject();
            a.addProperty("id", action.id()); a.addProperty("title", action.title());
            JsonArray args = new JsonArray();
            action.args().forEach((key, title) -> {
                JsonObject arg = new JsonObject(); arg.addProperty("key", key); arg.addProperty("title", title); arg.addProperty("type", "string"); args.add(arg);
            });
            a.add("args", args);
            list.add(a);
        }
        JsonObject out = new JsonObject();
        out.add("actions", list);
        return out;
    }

    private JsonObject action(JsonObject in) {
        String asked = in.has("id") ? in.get("id").getAsString() : "";
        boolean listed = host.actions().stream().anyMatch(action -> action.id().equals(asked));
        String id = listed ? asked : ALIASES.getOrDefault(asked, asked);
        if (host.actions().stream().noneMatch(action -> action.id().equals(id))) return errorObject("нет такого действия: " + asked);
        Map<String, String> args = new LinkedHashMap<>();
        if (in.has("args") && in.get("args").isJsonObject())
            for (Map.Entry<String, JsonElement> arg : in.getAsJsonObject("args").entrySet())
                args.put(arg.getKey(), arg.getValue().isJsonPrimitive() ? arg.getValue().getAsString() : "");
        try {
            String done = host.run(id, args);
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("text", GodJournal.redact(done));
            return out;
        } catch (RuntimeException refused) {
            return errorObject(refused.getMessage() == null ? refused.getClass().getSimpleName() : refused.getMessage());
        }
    }

    private static JsonObject guarded(java.util.function.Supplier<JsonObject> read) {
        try { return read.get(); }
        catch (RuntimeException refused) { return errorObject(refused.getMessage() == null ? refused.getClass().getSimpleName() : refused.getMessage()); }
    }

    private static JsonObject errorObject(String why) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", GodJournal.redact(why));
        return out;
    }
    private static String error(String why) { return errorObject(why).toString(); }
}
