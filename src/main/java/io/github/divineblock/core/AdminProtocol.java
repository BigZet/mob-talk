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
 *   <li>{@code soul_deeds} {@code {"player":uuid}} — the sins still to confess (Mob Talk's priests): {@code {ok, player, mercy,
 *       punished, deeds:[{id, kind, text, weight, heavy, relapse, recent}]}}, {@code recent} — the kind was absolved within two days;</li>
 *   <li>{@code absolve} {@code {"player","named":[ids],"sincerity":0..2,"place":"temple|village","priest":name}} — the
 *       absolution by the F9 numbers ({@link Absolution}): {@code {ok, absolved:[…], deferred:[…], concealed:[ids], refused, curses,
 *       mercyBefore, mercyAfter}}, each item {@code {id, kind, text, heavy, percent, restored, remaining}};</li>
 *   <li>{@code absolve_rest} {@code {"player","deeds":[ids],"priest":name}} — the penance done: the rest of those sins.</li>
 * </ul>
 * A confession writes the journal kind {@code confession}: the place, the priest and the numbers, never the penitent's words.
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
        /** {@code soul_deeds}: the soul's sins still to confess ({@link #deedsJson}). */
        default JsonObject soulDeeds(String player) { throw new IllegalStateException("души недоступны"); }
        /** {@code absolve} and {@code absolve_rest}: the request as it came; the answer {@link #resultJson}. */
        default JsonObject absolve(JsonObject request) { throw new IllegalStateException("исповедь недоступна"); }
        default JsonObject absolveRest(JsonObject request) { throw new IllegalStateException("исповедь недоступна"); }
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
                case "soul_deeds" -> guarded(() -> host.soulDeeds(text(in, "player"))).toString();
                case "absolve" -> guarded(() -> host.absolve(in)).toString();
                case "absolve_rest" -> guarded(() -> host.absolveRest(in)).toString();
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

    // ---- Confession (Mob Talk's priests). ----

    /** The answer of {@code soul_deeds}. */
    public static JsonObject deedsJson(JsonObject player, Righteousness righteousness, long today, boolean punished, DivineConfig config) {
        JsonArray deeds = new JsonArray();
        for (Absolution.Open open : Absolution.open(righteousness, today, config)) {
            Righteousness.Memory memory = righteousness.memory(open.id());
            JsonObject deed = new JsonObject();
            deed.addProperty("id", open.id()); deed.addProperty("kind", open.kind()); deed.addProperty("text", open.text());
            deed.addProperty("weight", open.weight()); deed.addProperty("heavy", open.heavy()); deed.addProperty("relapse", open.relapse());
            deed.addProperty("recent", memory != null && Absolution.recentlyAbsolved(righteousness, memory.deed, today));
            deeds.add(deed);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.add("player", player);
        out.addProperty("mercy", Math.round(righteousness.score * 10) / 10.0);
        out.addProperty("punished", punished);
        out.add("deeds", deeds);
        return out;
    }

    /** The answer of {@code absolve} and {@code absolve_rest}: what was forgiven, what waits, how many curses ended. */
    public static JsonObject resultJson(Absolution.Result result, int curses) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.add("absolved", items(result.absolved()));
        out.add("deferred", items(result.deferred()));
        JsonArray concealed = new JsonArray();
        result.concealed().forEach(concealed::add);
        out.add("concealed", concealed);
        out.addProperty("refused", result.refused());
        out.addProperty("curses", curses);
        out.addProperty("mercyBefore", result.before()); out.addProperty("mercyAfter", result.after());
        return out;
    }
    private static JsonArray items(List<Absolution.Item> items) {
        JsonArray out = new JsonArray();
        for (Absolution.Item item : items) {
            JsonObject one = new JsonObject();
            one.addProperty("id", item.id()); one.addProperty("kind", item.kind()); one.addProperty("text", item.text());
            one.addProperty("heavy", item.heavy()); one.addProperty("percent", item.percent());
            one.addProperty("restored", item.restored()); one.addProperty("remaining", item.remaining());
            out.add(one);
        }
        return out;
    }
    /** The ids of a JSON array field ({@code named}, {@code deeds}); anything else is skipped. */
    public static java.util.Set<Long> ids(JsonObject in, String key) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        if (in == null || !in.has(key) || !in.get(key).isJsonArray()) return out;
        for (JsonElement id : in.getAsJsonArray(key))
            try { out.add(id.getAsLong()); } catch (RuntimeException notNumber) { }
        return out;
    }
    /** The journal line of a confession: place, priest and numbers, no words. */
    public static String confessionLine(String priest, boolean temple, Absolution.Result result, int named, int sincerity, int curses) {
        if (result.refused()) return "исповедь у " + priest + (temple ? " (храм)" : " (село)") + ": разрешения нет, искренность 0, хула засчитана";
        return "исповедь у " + priest + (temple ? " (храм)" : " (село)") + ": названо " + named + ", отпущено " + result.absolved().size()
                + ", до епитимьи " + result.deferred().size() + ", скрыто " + result.concealed().size() + ", искренность " + sincerity
                + (curses > 0 ? ", снято проклятий " + curses : "") + String.format(java.util.Locale.ROOT, ", милость %.0f → %.0f", result.before(), result.after());
    }
    public static String restLine(String priest, Absolution.Result result, int curses) {
        return "после епитимьи у " + priest + ": отпущено " + result.absolved().size() + (curses > 0 ? ", снято проклятий " + curses : "")
                + String.format(java.util.Locale.ROOT, ", милость %.0f → %.0f", result.before(), result.after());
    }
    public static String text(JsonObject in, String key) {
        return in != null && in.has(key) && in.get(key).isJsonPrimitive() ? in.get(key).getAsString().strip() : "";
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
