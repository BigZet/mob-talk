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
 * any shared code between the two mods. Requests: {@code describe}, {@code values}, {@code patch} (applied at once, without a
 * restart), {@code journal}, {@code actions} and {@code action}. The game behind it is a {@link Host}, so the protocol itself is
 * plain Java and tested without Minecraft. */
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
        String id = in.has("id") ? in.get("id").getAsString() : "";
        if (host.actions().stream().noneMatch(action -> action.id().equals(id))) return errorObject("нет такого действия: " + id);
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

    private static JsonObject errorObject(String why) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", GodJournal.redact(why));
        return out;
    }
    private static String error(String why) { return errorObject(why).toString(); }
}
