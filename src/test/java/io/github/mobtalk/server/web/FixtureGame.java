package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** The game as fixtures ({@code src/test/resources/admin-stub/*.json}): what DevStubServer and the API tests serve. Times in the
 * fixtures are relative (milliseconds from now, negative in the past); actions change the trees in memory. */
public final class FixtureGame implements GameView {
    private static final Set<String> TIMES = Set.of("t", "lastSeen", "until", "since", "first", "last");

    final JsonArray players, mobs, villages;
    final JsonObject world;
    final List<String> commands = new CopyOnWriteArrayList<>();
    private final LongSupplier clock;
    private final long started;

    public FixtureGame(LongSupplier clock) throws IOException {
        this.clock = clock;
        this.started = clock.getAsLong();
        players = load("players.json").getAsJsonArray("players");
        mobs = load("mobs.json").getAsJsonArray("mobs");
        world = load("world.json");
        villages = world.getAsJsonArray("villages");
    }

    private JsonObject load(String name) throws IOException {
        try (InputStream in = FixtureGame.class.getClassLoader().getResourceAsStream("admin-stub/" + name)) {
            if (in == null) throw new IOException("нет фикстуры " + name);
            JsonObject tree = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            shift(tree, clock.getAsLong());
            return tree;
        }
    }

    /** Relative times to absolute ones. */
    private static void shift(JsonElement element, long now) {
        if (element.isJsonArray()) { for (JsonElement item : element.getAsJsonArray()) shift(item, now); return; }
        if (!element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (TIMES.contains(entry.getKey()) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Math.abs(value.getAsLong()) < 1_000_000_000_000L)
                entry.setValue(new com.google.gson.JsonPrimitive(now + value.getAsLong()));
            else shift(value, now);
        }
    }

    /** The divine block as a settings source, from the same fixture. */
    public UnaryOperator<String> divineSource() {
        JsonObject source = world.getAsJsonObject("divineSource");
        JsonObject values = source.getAsJsonObject("values");
        return request -> {
            JsonObject query = Json.parseObject(request);
            JsonObject answer = switch (String.valueOf(Json.string(query, "op"))) {
                case "describe" -> source.getAsJsonObject("describe");
                case "values" -> values;
                case "patch" -> {
                    JsonObject errors = new JsonObject();
                    List<String> applied = new ArrayList<>();
                    for (Map.Entry<String, JsonElement> entry : query.getAsJsonObject("values").entrySet()) {
                        JsonObject field = field(source, entry.getKey());
                        String problem = field == null ? "неизвестная настройка" : check(field, entry.getValue());
                        if (problem != null) errors.addProperty(entry.getKey(), problem);
                        else { values.add(entry.getKey(), entry.getValue()); applied.add(entry.getKey()); }
                    }
                    yield Json.obj("ok", errors.isEmpty(), "applied", applied, "errors", errors);
                }
                case "journal" -> Json.obj("items", new JsonArray());
                case "metrics" -> Json.obj("bosses", 1, "curses", 1);
                case "actions" -> Json.obj("actions", source.getAsJsonArray("actions"));
                case "action" -> Json.obj("ok", true, "message", "Выполнено: " + Json.string(query, "id") + " для " + Json.string(query.getAsJsonObject("args"), "player"));
                default -> Json.obj("ok", false, "message", "неизвестная операция");
            };
            return Json.GSON.toJson(answer);
        };
    }

    private static JsonObject field(JsonObject source, String key) {
        for (JsonElement group : source.getAsJsonObject("describe").getAsJsonArray("groups"))
            for (JsonElement field : group.getAsJsonObject().getAsJsonArray("fields"))
                if (key.equals(Json.string(field.getAsJsonObject(), "key"))) return field.getAsJsonObject();
        return null;
    }

    private static String check(JsonObject field, JsonElement value) {
        String type = Json.string(field, "type");
        if (!value.isJsonPrimitive()) return "нужно значение";
        if (type.equals("bool")) return value.getAsJsonPrimitive().isBoolean() ? null : "нужно да или нет";
        if (type.equals("int") || type.equals("decimal")) {
            if (!value.getAsJsonPrimitive().isNumber()) return "нужно число";
            double v = value.getAsDouble();
            if (v < field.get("min").getAsDouble() || v > field.get("max").getAsDouble()) return "вне границ " + field.get("min") + "–" + field.get("max");
            return type.equals("int") && v != Math.rint(v) ? "нужно целое число" : null;
        }
        if (type.equals("enum")) {
            for (JsonElement option : field.getAsJsonArray("options")) if (option.getAsJsonObject().get("value").getAsString().equals(value.getAsString())) return null;
            return "нет такого варианта";
        }
        return null;
    }

    @Override public JsonObject overview() {
        int online = 0;
        JsonObject streamer = null;
        for (JsonElement p : players) {
            JsonObject player = p.getAsJsonObject();
            if (player.get("online").getAsBoolean()) online++;
            if (player.get("streamer").getAsBoolean()) streamer = Json.player(UUID.fromString(Json.string(player, "uuid")), Json.string(player, "name"));
        }
        return Json.obj("online", online, "maxPlayers", 8, "uptimeSeconds", (clock.getAsLong() - started) / 1000 + 7200, "streamer", streamer,
                "llmQueue", 0, "llmInFlight", 0);
    }

    @Override public JsonArray players() {
        JsonArray out = new JsonArray();
        for (JsonElement p : players) {
            JsonObject copy = p.getAsJsonObject().deepCopy();
            copy.remove("relations"); copy.remove("settings"); copy.remove("activity");
            out.add(copy);
        }
        return out;
    }

    @Override public JsonObject player(UUID uuid) { JsonObject player = find(players, uuid); return player == null ? null : player.deepCopy(); }

    @Override public synchronized JsonObject playerAction(UUID uuid, String action, JsonObject body) {
        JsonObject player = find(players, uuid);
        if (player == null) throw ApiException.notFound("Нет такого игрока.");
        String name = Json.string(player, "name");
        switch (action) {
            case "level" -> {
                String level = String.valueOf(Json.string(body, "level"));
                if (!level.equals("ADMIN") && !level.equals("PLAYER")) throw ApiException.badRequest("Уровень — ADMIN или PLAYER.");
                player.addProperty("op", level.equals("ADMIN"));
                player.addProperty("level", level.equals("ADMIN") ? "ADMIN" : player.get("streamer").getAsBoolean() ? "STREAMER" : "PLAYER");
                return Json.ok(name + ": уровень " + level);
            }
            case "kick" -> {
                if (!player.get("online").getAsBoolean()) throw ApiException.conflict(name + " не в игре.");
                player.addProperty("online", false);
                return Json.ok(name + " выкинут с сервера");
            }
            case "ban" -> { player.addProperty("banned", true); player.addProperty("banReason", Json.string(body, "reason")); player.addProperty("online", false); return Json.ok(name + " забанен"); }
            case "unban" -> { player.addProperty("banned", false); player.add("banReason", null); return Json.ok(name + " разбанен"); }
            default -> {
                boolean on = body.has("on") && body.get("on").getAsBoolean();
                player.addProperty("whitelisted", on);
                return Json.ok(name + (on ? " в белом списке" : " убран из белого списка"));
            }
        }
    }

    @Override public synchronized JsonObject setStreamer(UUID uuid) {
        if (uuid != null && find(players, uuid) == null) throw ApiException.notFound("Нет такого игрока.");
        for (JsonElement p : players) {
            JsonObject player = p.getAsJsonObject();
            boolean is = uuid != null && uuid.toString().equals(Json.string(player, "uuid"));
            player.addProperty("streamer", is);
            if (!player.get("op").getAsBoolean()) player.addProperty("level", is ? "STREAMER" : "PLAYER");
        }
        return Json.ok(uuid == null ? "Стример снят" : "Стример назначен");
    }

    /** Where each fixture player stands: [x, y, z, yaw]; online ones wander a little with every look. */
    private final Map<String, double[]> places = new java.util.concurrent.ConcurrentHashMap<>(Map.of(
            "461baa90-bc98-4f86-b889-50f67d759b9a", new double[]{12.5, 64, -301.2, 90},
            "2f83646d-2eeb-4a75-8358-03e02e2ef99f", new double[]{40.0, 70, -280.0, 180},
            "51a7640e-4899-4753-b8d2-90b0c715341b", new double[]{-120.0, 63, 15.5, 0}));
    private final Map<String, String> worlds = new java.util.concurrent.ConcurrentHashMap<>();

    private JsonObject place(JsonObject player, boolean drift) {
        String uuid = Json.string(player, "uuid");
        double[] at = places.computeIfAbsent(uuid, k -> new double[]{0, 64, 0, 0});
        boolean online = player.get("online").getAsBoolean();
        if (drift && online) {
            double angle = Math.toRadians(at[3]);
            at[0] += Math.cos(angle) * 0.6; at[2] += Math.sin(angle) * 0.6; at[3] = (at[3] + 7) % 360;
        }
        return Json.obj("player", Json.player(UUID.fromString(uuid), Json.string(player, "name")), "online", online,
                "world", worlds.getOrDefault(uuid, "minecraft:overworld"), "x", Math.round(at[0] * 10) / 10.0, "y", at[1], "z", Math.round(at[2] * 10) / 10.0,
                "yaw", at[3], "biome", at[2] < -290 ? "minecraft:birch_forest" : "minecraft:plains", "health", 18, "food", 20, "gamemode", "survival");
    }

    @Override public synchronized JsonObject positions() {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement p : players) if (p.getAsJsonObject().get("online").getAsBoolean()) list.add(place(p.getAsJsonObject(), true));
        return Json.obj("t", clock.getAsLong(), "players", list);
    }

    @Override public synchronized JsonObject position(UUID uuid) {
        JsonObject player = find(players, uuid);
        if (player == null) return null;
        JsonObject place = place(player, false);
        place.add("respawn", Json.obj("world", "minecraft:overworld", "x", 0, "y", 70, "z", 0));
        place.addProperty("t", player.get("online").getAsBoolean() ? clock.getAsLong() : player.get("lastSeen").getAsLong());
        return place;
    }

    @Override public synchronized JsonObject teleport(UUID uuid, JsonObject target) {
        JsonObject player = find(players, uuid);
        if (player == null) throw ApiException.notFound("Нет такого игрока.");
        if (!player.get("online").getAsBoolean()) throw ApiException.conflict(Json.string(player, "name") + " не в игре.");
        double[] at = places.get(uuid.toString());
        if (target.has("toPlayer")) {
            double[] other = places.get(Json.string(target, "toPlayer"));
            if (other == null) throw ApiException.notFound("Нет такого игрока.");
            System.arraycopy(other, 0, at, 0, 3);
        } else if (target.has("to")) {
            at[0] = 0; at[1] = 70; at[2] = 0;
        } else {
            worlds.put(uuid.toString(), Json.string(target, "world"));
            at[0] = target.get("x").getAsDouble(); at[1] = target.get("y").getAsDouble(); at[2] = target.get("z").getAsDouble();
        }
        return Json.ok(Json.string(player, "name") + " перемещён");
    }

    @Override public JsonObject mobs(String village, String query, String cursor, int limit) {
        List<JsonObject> items = new ArrayList<>();
        String q = query == null ? null : query.toLowerCase(Locale.ROOT);
        for (JsonElement m : mobs) {
            JsonObject mob = m.getAsJsonObject();
            JsonElement v = mob.get("village");
            if (village != null && (v == null || v.isJsonNull() || !village.equals(Json.string(v.getAsJsonObject(), "id")))) continue;
            String haystack = (Json.string(mob.getAsJsonObject("mob"), "name") + " " + Json.string(mob.getAsJsonObject("mob"), "species") + " "
                    + (v == null || v.isJsonNull() ? "" : Json.string(v.getAsJsonObject(), "name"))).toLowerCase(Locale.ROOT);
            if (q != null && !haystack.contains(q)) continue;
            JsonObject summary = mob.getAsJsonObject("mob").deepCopy();
            summary.add("village", v);
            summary.add("trait", mob.get("trait"));
            summary.add("alive", mob.get("alive"));
            summary.add("loaded", mob.get("loaded"));
            summary.addProperty("relations", mob.getAsJsonArray("relations").size());
            JsonArray events = mob.getAsJsonArray("events");
            summary.add("lastEvent", events.isEmpty() ? null : events.get(0));
            items.add(summary);
        }
        int start = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(items.size(), start + limit);
        return Json.obj("items", items.subList(Math.min(start, items.size()), end), "next", end < items.size() ? Integer.toString(end) : null);
    }

    @Override public JsonObject mob(UUID uuid) {
        for (JsonElement m : mobs)
            if (uuid.toString().equals(Json.string(m.getAsJsonObject().getAsJsonObject("mob"), "uuid"))) return m.getAsJsonObject().deepCopy();
        return null;
    }

    @Override public synchronized JsonObject mobAction(UUID uuid, String action, JsonObject body) {
        JsonObject mob = null;
        for (JsonElement m : mobs) if (uuid.toString().equals(Json.string(m.getAsJsonObject().getAsJsonObject("mob"), "uuid"))) mob = m.getAsJsonObject();
        if (mob == null) throw ApiException.notFound("Нет такого моба.");
        switch (action) {
            case "card" -> {
                JsonObject card = mob.getAsJsonObject("card");
                for (String key : List.of("name", "character", "voice", "memory")) if (body.has(key)) card.add(key, body.get(key));
                if (body.has("name")) mob.getAsJsonObject("mob").add("name", body.get("name"));
                return Json.ok("Карточка сохранена");
            }
            case "reset" -> {
                mob.add("state", Json.obj("calm", 50, "trust", 50, "interest", 50, "mood", 50, "word", "спокоен"));
                mob.getAsJsonObject("card").addProperty("memory", "");
                mob.add("facts", new JsonArray());
                return Json.ok("Состояние и память сброшены");
            }
            default -> { mob.add("events", new JsonArray()); return Json.ok("История реплик очищена"); }
        }
    }

    @Override public JsonObject villages() { return Json.obj("villages", villages.deepCopy()); }

    @Override public synchronized JsonObject forgive(String village, UUID player) {
        for (JsonElement v : villages) {
            if (!village.equals(Json.string(v.getAsJsonObject(), "id"))) continue;
            for (JsonElement s : v.getAsJsonObject().getAsJsonArray("standings")) {
                JsonObject standing = s.getAsJsonObject();
                if (player.toString().equals(Json.string(standing.getAsJsonObject("player"), "uuid"))) {
                    standing.addProperty("points", 0); standing.addProperty("rank", "гость");
                    return Json.ok("Деревня простила " + Json.string(standing.getAsJsonObject("player"), "name"));
                }
            }
            throw ApiException.notFound("Деревня не знает этого игрока.");
        }
        throw ApiException.notFound("Нет такой деревни.");
    }

    @Override public JsonObject divineSouls() { return Json.obj("souls", world.getAsJsonObject("divine").getAsJsonArray("souls").deepCopy()); }
    @Override public JsonObject divineActive() { return world.getAsJsonObject("divine").getAsJsonObject("active").deepCopy(); }

    @Override public JsonObject server() {
        JsonObject server = world.getAsJsonObject("server").deepCopy();
        server.addProperty("uptimeSeconds", (clock.getAsLong() - started) / 1000 + 7200);
        return server;
    }

    @Override public List<String> commandRoots() {
        List<String> roots = new ArrayList<>();
        for (JsonElement root : world.getAsJsonArray("commandRoots")) roots.add(root.getAsString());
        return roots;
    }

    @Override public JsonObject runCommand(String command) {
        commands.add(command);
        String root = command.split(" ", 2)[0];
        if (!commandRoots().contains(root)) return Json.obj("ok", false, "result", 0, "output", List.of("Неизвестная команда: " + root));
        return Json.obj("ok", true, "result", 1, "output", List.of("[заглушка] выполнено: /" + command));
    }

    @Override public JsonObject reloadConfig() { return Json.ok("Конфиг перечитан (заглушка)"); }

    @Override public JsonArray activities() {
        JsonArray out = new JsonArray();
        for (JsonElement p : players) {
            JsonObject player = p.getAsJsonObject();
            if (player.get("online").getAsBoolean())
                out.add(Json.obj("player", Json.player(UUID.fromString(Json.string(player, "uuid")), Json.string(player, "name")), "activity", player.get("activity")));
        }
        return out;
    }

    @Override public boolean cancelDialogue(String id) { return false; }

    private static JsonObject find(JsonArray list, UUID uuid) {
        for (JsonElement item : list) if (uuid.toString().equals(Json.string(item.getAsJsonObject(), "uuid"))) return item.getAsJsonObject();
        return null;
    }
}
