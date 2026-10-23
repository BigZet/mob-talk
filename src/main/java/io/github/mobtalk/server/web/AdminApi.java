package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Every {@code /api/*} route of docs/admin-web.md over the admin's parts. The same object serves a live server and the stub;
 * only the {@link GameView} and the sources differ. */
public final class AdminApi {
    /** Everything the routes read; built by {@code AdminWeb} on a server and by {@code DevStubServer} in development. */
    public record Parts(HistoryStore history, Metrics metrics, Latency latency, AdminJournal journal, ErrorLog errors, ConfigSources sources,
                        GameView game, Backups backups, Waypoints waypoints, MapSource map, Tracks tracks, BlueMapControl map3d, Path log, Supplier<JsonObject> limits, Supplier<JsonObject> adminInfo, LongSupplier clock) { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String[] MONTHS = {"янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};

    private final Parts parts;
    private final SseHub events = new SseHub(16, 15_000), live = new SseHub(16, 15_000);
    private final Router router = new Router();
    private int polls;
    private final java.util.concurrent.ScheduledExecutorService positions = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-admin-positions"); thread.setDaemon(true); return thread;
    });

    public AdminApi(Parts parts) {
        this.parts = parts;
        parts.history().listen(record -> {
            events.publish("history", record);
            String kind = Json.string(record, "kind");
            if ("config_change".equals(kind)) events.publish("config", record);
            if ("player_join".equals(kind) || "player_leave".equals(kind))
                events.publish("presence", Json.obj("player", record.get("player"), "online", "player_join".equals(kind)));
        });
        parts.metrics().listen(point -> events.publish("metrics", point));
        parts.errors().listen(group -> events.publish("error", group));
        parts.journal().listenLive(snapshot -> live.publish("snapshot", snapshot));
        routes();
        parts.map().listen(tile -> events.publish("map", tile));
        // Every 2 s: the paths always grow, the page gets the places only while someone looks.
        positions.scheduleWithFixedDelay(() -> {
            try {
                JsonObject now = parts.game().positions();
                parts.tracks().observe(now);
                if (events.subscribers() > 0) {
                    events.publish("positions", now);
                    if (++polls % 5 == 0) parts.map().poll();
                }
            } catch (RuntimeException | LinkageError failure) { /* The next tick tries again. */ }
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
        positions.scheduleWithFixedDelay(() -> {
            try { parts.map3d().sweep(); } catch (RuntimeException | LinkageError failure) { /* Next time. */ }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    public Router router() { return router; }
    /** {@code /map3d/}: BlueMap's webapp and maps, for {@link AdminHttpServer#mount}. */
    public Router.Handler map3dFiles() { return r -> parts.map3d().file(r.path()); }
    public SseHub events() { return events; }
    public void close() { positions.shutdownNow(); events.close(); live.close(); }

    private ZoneId zone() { return parts.history().zone(); }
    private long now() { return parts.clock().getAsLong(); }

    private void routes() {
        router.get("/api/overview", r -> Response.json(overview()));

        router.get("/api/metrics", r -> {
            long to = r.longQuery("to", now()), from = r.longQuery("from", to - 3_600_000);
            List<MetricSeries> series = new ArrayList<>();
            for (String id : list(r.query("series"))) {
                MetricSeries one = MetricSeries.byId(id);
                if (one == null) throw ApiException.badRequest("Нет ряда метрик " + id + ".");
                series.add(one);
            }
            return Response.json(parts.metrics().query(from, to, r.intQuery("points", 300, 1, 2000), series));
        });
        router.get("/api/metrics/now", r -> Response.json(parts.metrics().now()));
        router.get("/api/latency", r -> {
            long to = r.longQuery("to", now()), from = r.longQuery("from", to - 3_600_000);
            return Response.json(parts.latency().histogram(r.query("kind", "llm"), from, to));
        });

        router.get("/api/history", r -> Response.json(page(parts.history().search(query(r)))));
        router.get("/api/history/export", r -> {
            String format = r.query("format", "csv");
            if (!format.equals("csv") && !format.equals("jsonl")) throw ApiException.badRequest("Формат — csv или jsonl.");
            byte[] body = parts.history().export(query(r), format, 100_000);
            String name = "mobtalk-history-" + DAY.format(Instant.ofEpochMilli(now()).atZone(zone())) + "." + format;
            return Response.bytes(200, format.equals("csv") ? "text/csv; charset=utf-8" : "application/x-ndjson; charset=utf-8", body)
                    .header("Content-Disposition", "attachment; filename=\"" + name + "\"");
        });
        router.get("/api/history/stats", r -> {
            String bucket = r.query("bucket", "day");
            if (!bucket.equals("day") && !bucket.equals("hour")) throw ApiException.badRequest("bucket — hour или day.");
            HistoryStore.Query query = query(r);
            if (query.from() == null) query = new HistoryStore.Query(query.kinds(), query.player(), query.mob(), query.text(),
                    now() - (bucket.equals("hour") ? 86_400_000L : 30 * 86_400_000L), query.to(), null, query.limit());
            return Response.json(parts.history().stats(query, bucket, r.query("by")));
        });
        router.get("/api/history/{id}", r -> {
            JsonObject record = parts.history().get(r.param("id"));
            if (record == null) throw ApiException.notFound("Нет такой записи.");
            return Response.json(record);
        });

        router.get("/api/costs", r -> Response.json(costs(r.longQuery("from", now() - 30 * 86_400_000L), r.longQuery("to", now()), r.query("group", "day"))));

        router.get("/api/sources", r -> Response.json(parts.sources().describeAll()));
        router.get("/api/sources/{id}/values", r -> Response.json(parts.sources().values(r.param("id"))));
        router.patch("/api/sources/{id}/values", r -> {
            JsonObject body = r.json();
            if (!body.has("values") || !body.get("values").isJsonObject()) throw ApiException.badRequest("Нужно поле values.");
            return Response.json(parts.sources().patch(r.param("id"), body.getAsJsonObject("values"), by(r)));
        });
        router.get("/api/sources/{id}/actions", r -> Response.json(parts.sources().actions(r.param("id"))));
        router.post("/api/sources/{id}/actions/{action}", r -> {
            JsonObject body = r.json();
            JsonObject args = body.has("args") && body.get("args").isJsonObject() ? body.getAsJsonObject("args") : new JsonObject();
            return Response.json(parts.sources().action(r.param("id"), r.param("action"), args, by(r)));
        });
        router.get("/api/config/history", r -> {
            HistoryStore.Query base = query(r);
            String source = r.query("source");
            HistoryStore.Query query = new HistoryStore.Query(Set.of("config_change"), null, null, base.text(), base.from(), base.to(), base.cursor(), base.limit());
            return Response.json(page(source == null ? parts.history().search(query) : bySource(query, source)));
        });
        router.post("/api/config/rollback/{id}", r -> Response.json(parts.sources().rollback(r.param("id"), by(r))));

        router.get("/api/players", r -> Response.json(Json.obj("players", players())));
        // Местоположение: the only answers with coordinates; the history records a teleport without them.
        router.get("/api/players/positions", r -> Response.json(parts.game().positions()));
        router.get("/api/players/{uuid}/position", r -> {
            JsonObject position = parts.game().position(uuid(r.param("uuid")));
            if (position == null) throw ApiException.notFound("Нет такого игрока.");
            return Response.json(position);
        });
        router.post("/api/players/{uuid}/teleport", r -> {
            UUID player = uuid(r.param("uuid"));
            JsonObject body = r.json(), target;
            String described;
            if (body.has("waypoint")) {
                JsonObject point = parts.waypoints().find(String.valueOf(Json.string(body, "waypoint")));
                if (point == null) throw ApiException.notFound("Нет такой точки.");
                target = Json.obj("world", point.get("world"), "x", point.get("x"), "y", point.get("y"), "z", point.get("z"));
                described = "к точке «" + Json.string(point, "name") + "»";
            } else if (body.has("toPlayer")) {
                target = Json.obj("toPlayer", uuid(Json.string(body, "toPlayer")).toString());
                described = "к игроку " + Json.string(body, "toPlayer");
            } else if (body.has("to")) {
                String to = Json.string(body, "to");
                if (!"spawn".equals(to) && !"respawn".equals(to)) throw ApiException.badRequest("to — spawn или respawn.");
                target = Json.obj("to", to);
                described = "spawn".equals(to) ? "на спавн мира" : "к точке возрождения";
            } else {
                String world = Json.string(body, "world");
                if (world == null || world.isBlank()) throw ApiException.badRequest("Нужен мир или другая цель телепорта.");
                target = Json.obj("world", world, "x", Waypoints.coordinate(body, "x"), "y", Waypoints.coordinate(body, "y"), "z", Waypoints.coordinate(body, "z"));
                described = "по координатам в " + world;
            }
            JsonObject answer = parts.game().teleport(player, target);
            parts.history().append("command", null, null, "Админка: телепорт " + player + " " + described,
                    Json.obj("command", "teleport " + described, "by", by(r), "result", 1, "output", List.of(String.valueOf(Json.string(answer, "message")))));
            return Response.json(answer);
        });
        router.get("/api/players/tracks", r -> {
            long to = r.longQuery("to", now()), from = r.longQuery("from", to - 3_600_000);
            String player = r.query("player");
            return Response.json(parts.tracks().query(world(r), from, to, player == null ? null : uuid(player), r.intQuery("points", 2000, 2, 20_000)));
        });
        router.get("/api/map/tiles/{world}/{zoom}/{tx}/{tz}", r -> {
            String world = java.net.URLDecoder.decode(r.param("world"), java.nio.charset.StandardCharsets.UTF_8), last = r.param("tz");
            if (!last.endsWith(".png")) throw ApiException.notFound("Тайл — .png.");
            int zoom, tx, tz;
            try { zoom = Integer.parseInt(r.param("zoom")); tx = Integer.parseInt(r.param("tx")); tz = Integer.parseInt(last.substring(0, last.length() - 4)); }
            catch (NumberFormatException bad) { throw ApiException.badRequest("zoom, tx, tz — целые числа."); }
            long modified = parts.map().modified(world, zoom, tx, tz);
            byte[] png = modified == 0 ? null : parts.map().tile(world, zoom, tx, tz);
            if (png == null) return Response.bytes(204, "image/png", new byte[0]).header("Cache-Control", "max-age=60");
            String etag = "\"" + Long.toString(modified, 36) + "\"";
            if (etag.equals(r.header("If-None-Match"))) return Response.bytes(304, "image/png", new byte[0]).header("ETag", etag).header("Cache-Control", "max-age=60");
            return Response.bytes(200, "image/png", png).header("ETag", etag).header("Cache-Control", "max-age=60");
        });
        router.get("/api/map/info", r -> {
            String world = world(r);
            int[] box = parts.map().bounds(world);
            JsonObject spawn = null;
            for (JsonElement marker : parts.game().mapMarkers(world))
                if ("spawn".equals(Json.string(marker.getAsJsonObject(), "kind"))) { spawn = Json.obj("x", marker.getAsJsonObject().get("x"), "z", marker.getAsJsonObject().get("z")); break; }
            return Response.json(Json.obj("world", world, "minX", box == null ? null : box[0], "minZ", box == null ? null : box[1],
                    "maxX", box == null ? null : box[2], "maxZ", box == null ? null : box[3], "spawn", spawn, "worlds", parts.map().worlds(),
                    "tileSize", parts.map().tileSize(world), "maxZoom", parts.map().maxZoom(world), "source", parts.map().name(world)));
        });
        router.get("/api/map/markers", r -> {
            String world = world(r);
            JsonArray markers = parts.game().mapMarkers(world).deepCopy();
            for (JsonElement point : parts.waypoints().list().getAsJsonArray("waypoints")) {
                JsonObject waypoint = point.getAsJsonObject();
                if (world.equals(Json.string(waypoint, "world")))
                    markers.add(Json.obj("kind", "waypoint", "id", waypoint.get("id"), "name", waypoint.get("name"), "x", waypoint.get("x"), "z", waypoint.get("z")));
            }
            return Response.json(Json.obj("world", world, "markers", markers));
        });
        router.post("/api/map3d/keepalive", r -> Response.json(parts.map3d().keepalive()));
        router.get("/api/map3d/status", r -> Response.json(parts.map3d().status()));
        router.get("/api/waypoints", r -> Response.json(parts.waypoints().list()));
        router.post("/api/waypoints", r -> Response.json(Json.obj("ok", true, "waypoint", parts.waypoints().add(r.json()))));
        router.delete("/api/waypoints/{id}", r -> {
            if (!parts.waypoints().remove(r.param("id"))) throw ApiException.notFound("Нет такой точки.");
            return Response.json(Json.ok("Точка удалена."));
        });
        router.get("/api/players/{uuid}", r -> Response.json(player(uuid(r.param("uuid")))));
        router.post("/api/players/{uuid}/{action}", r -> {
            String action = r.param("action");
            if (!Set.of("level", "kick", "ban", "unban", "whitelist", "gamemode").contains(action)) throw ApiException.notFound("Нет такого действия.");
            UUID player = uuid(r.param("uuid"));
            JsonObject body = r.json(), answer = parts.game().playerAction(player, action, body);
            parts.history().append("command", null, null, "Админка: " + action + " " + player + " " + Json.GSON.toJson(body),
                    Json.obj("command", "player " + action, "by", by(r), "result", 1, "output", List.of(String.valueOf(Json.string(answer, "message")))));
            return Response.json(answer);
        });
        router.post("/api/streamer", r -> {
            String raw = Json.string(r.json(), "uuid");
            return Response.json(parts.game().setStreamer(raw == null ? null : uuid(raw)));
        });

        router.get("/api/mobs", r -> Response.json(parts.game().mobs(r.query("village"), r.query("q"), r.query("cursor"), r.intQuery("limit", 50, 1, 500))));
        router.get("/api/mobs/{uuid}", r -> {
            JsonObject mob = parts.game().mob(uuid(r.param("uuid")));
            if (mob == null) throw ApiException.notFound("Нет такого моба.");
            return Response.json(mob);
        });
        router.post("/api/mobs/{uuid}/{action}", r -> {
            String action = r.param("action");
            if (!Set.of("card", "reset", "clear-history").contains(action)) throw ApiException.notFound("Нет такого действия.");
            return Response.json(parts.game().mobAction(uuid(r.param("uuid")), action, r.json()));
        });
        router.get("/api/villages", r -> Response.json(parts.game().villages()));
        router.post("/api/villages/{id}/forgive/{uuid}", r -> Response.json(parts.game().forgive(r.param("id"), uuid(r.param("uuid")))));

        router.get("/api/divine/souls", r -> Response.json(parts.game().divineSouls()));
        router.get("/api/divine/active", r -> Response.json(parts.game().divineActive()));

        router.get("/api/server", r -> {
            JsonObject server = parts.game().server().deepCopy();
            server.add("admin", parts.adminInfo().get());
            return Response.json(server);
        });
        router.get("/api/server/commands", r -> Response.json(Json.obj("roots", parts.game().commandRoots())));
        router.post("/api/server/command", r -> {
            String command = Json.string(r.json(), "command");
            if (command == null || command.isBlank()) throw ApiException.badRequest("Пустая команда.");
            command = command.strip();
            if (command.startsWith("/")) command = command.substring(1);
            if (command.length() > 1000) throw ApiException.badRequest("Команда длиннее 1000 символов.");
            JsonObject answer = parts.game().runCommand(command);
            parts.history().append("command", null, null, "Консоль админки: /" + command,
                    Json.obj("command", command, "by", by(r), "result", answer.get("result"), "output", answer.get("output")));
            return Response.json(Redact.object(answer));
        });
        router.get("/api/logs", r -> {
            if (parts.log() == null) return Response.json(Json.obj("lines", new JsonArray()));
            return Response.json(LogTail.tail(parts.log(), r.intQuery("lines", 200, 1, 2000), list(r.query("filter")), r.query("level")));
        });
        router.get("/api/logs/export", r -> {
            String text = parts.log() == null ? "" : LogTail.export(parts.log(), r.query("lines") == null ? null : r.intQuery("lines", 0, 1, 200_000),
                    list(r.query("filter")), r.query("level"));
            return Response.text(200, "text/plain; charset=utf-8", text)
                    .header("Content-Disposition", "attachment; filename=\"mobtalk-log-" + stamp() + ".txt\"");
        });
        router.get("/api/diagnostics", r -> Response.bytes(200, "application/zip", diagnostics())
                .header("Content-Disposition", "attachment; filename=\"mobtalk-diagnostics-" + stamp() + ".zip\""));
        router.get("/api/errors", r -> Response.json(parts.errors().groups()));
        router.post("/api/errors/clear", r -> { parts.errors().clear(); return Response.json(Json.ok(null)); });
        router.post("/api/server/backup", r -> {
            if (parts.backups() == null) throw ApiException.unavailable("Резервные копии здесь недоступны.");
            JsonObject backup = parts.backups().create();
            events.publish("backup", backup);
            parts.history().append("command", null, null, "Резервная копия " + Json.string(backup, "name"),
                    Json.obj("command", "backup", "by", by(r), "result", 1, "output", List.of(Json.string(backup, "name"))));
            return Response.json(Json.obj("ok", true, "backup", backup));
        });
        router.get("/api/backups", r -> {
            if (parts.backups() == null) return Response.json(Json.obj("backups", new JsonArray()));
            return Response.json(parts.backups().list());
        });
        router.post("/api/server/new-world", r -> {
            JsonObject body = r.json();
            String world = parts.game().worldDir();
            if (!world.equals(Json.string(body, "confirm"))) throw ApiException.badRequest("Для подтверждения введите название мира: " + world + ".");
            JsonObject backup = null;
            if (parts.backups() != null) { backup = parts.backups().create(); events.publish("backup", backup); }
            JsonObject answer = parts.game().newWorld(Json.string(body, "seed"), Json.string(body, "type"), by(r));
            parts.history().append("command", null, null, "Новый мир вместо «" + world + "»" + (backup == null ? "" : ", копия " + Json.string(backup, "name")),
                    Json.obj("command", "new-world", "by", by(r), "result", 1, "output", List.of(String.valueOf(Json.string(answer, "message")))));
            return Response.json(answer);
        });
        router.post("/api/server/reload-config", r -> Response.json(parts.game().reloadConfig()));

        router.get("/api/events", r -> events.stream(() -> List.of(SseHub.frame("hello", Json.obj("t", now())))));
        router.get("/api/live/dialogues", r -> live.stream(() -> List.of(SseHub.frame("snapshot", liveSnapshot()))));
        router.post("/api/live/dialogues/{id}/cancel", r -> {
            String id = r.param("id");
            AdminJournal.Run run = parts.journal().running(id);
            boolean stopped = parts.game().cancelDialogue(id);
            if (run != null) { run.fail(run.trace.lastStep(), "отменено из админки"); stopped = true; }
            if (!stopped) throw ApiException.notFound("Этот диалог уже закончился.");
            return Response.json(Json.ok("Диалог отменён."));
        });
    }

    private String stamp() { return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(now()).atZone(zone())); }

    /** «Отчёт для разработчика»: the log, errors, server, overview, every source's settings and the last day's history, all cleaned. */
    byte[] diagnostics() throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            java.util.function.BiConsumer<String, String> put = (name, text) -> {
                try {
                    zip.putNextEntry(new java.util.zip.ZipEntry(name));
                    zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            };
            com.google.gson.Gson pretty = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
            put.accept("latest.log", parts.log() == null ? "" : LogTail.export(parts.log(), null, List.of(), null));
            put.accept("errors.json", pretty.toJson(parts.errors().groups()));
            JsonObject server = Redact.object(parts.game().server());
            server.add("admin", parts.adminInfo().get());
            put.accept("server.json", pretty.toJson(server));
            put.accept("overview.json", pretty.toJson(Redact.object(overview())));
            JsonObject settings = new JsonObject();
            for (JsonElement source : parts.sources().describeAll().getAsJsonArray("sources")) {
                String id = Json.string(source.getAsJsonObject(), "id");
                try { settings.add(id, Redact.object(parts.sources().values(id).getAsJsonObject("values"))); }
                catch (ApiException broken) { settings.addProperty(id, broken.getMessage()); }
            }
            put.accept("settings.json", pretty.toJson(settings));
            StringBuilder history = new StringBuilder();
            int[] count = {0};
            parts.history().scan(new HistoryStore.Query(Set.of(), null, null, null, now() - 86_400_000L, now(), null, 0), record -> {
                history.append(Json.GSON.toJson(record)).append('\n');
                return ++count[0] < 500;
            });
            put.accept("history.jsonl", history.toString());
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
        return bytes.toByteArray();
    }

    private JsonObject liveSnapshot() {
        JsonObject snapshot = parts.journal().liveSnapshot();
        if (snapshot.getAsJsonArray("activities").isEmpty()) snapshot.add("activities", parts.game().activities());
        return snapshot;
    }

    JsonObject overview() {
        JsonObject game = parts.game().overview();
        JsonObject out = game.deepCopy();
        JsonObject values = parts.metrics().now().getAsJsonObject("values");
        out.addProperty("t", now());
        copy(values, "tps", out, "tps");
        copy(values, "mspt", out, "mspt");
        copy(values, "mspt_p95", out, "msptP95");
        copy(values, "mem_used", out, "memUsedMb");
        copy(values, "mem_max", out, "memMaxMb");
        if (!out.has("dialoguesActive")) out.addProperty("dialoguesActive", parts.journal().active());
        LocalDate today = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate();
        double[] spent = {0};
        parts.history().scan(new HistoryStore.Query(Set.of("cost"), null, null, null, today.atStartOfDay(zone()).toInstant().toEpochMilli(), now(), null, 0),
                record -> { spent[0] += usd(record); return true; });
        out.addProperty("costTodayUsd", round(spent[0]));
        out.addProperty("errorsLastHour", parts.errors().lastHour());
        return out;
    }

    /** Players from the game with money and dialogue counts from the history. */
    JsonArray players() {
        Map<String, double[]> money = new HashMap<>();
        Map<String, Integer> dialogues = new HashMap<>();
        long todayStart = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli();
        parts.history().scan(new HistoryStore.Query(Set.of("cost", "dialogue"), null, null, null, null, null, null, 0), record -> {
            String uuid = playerUuid(record);
            if (uuid == null) return true;
            if ("dialogue".equals(Json.string(record, "kind"))) dialogues.merge(uuid, 1, Integer::sum);
            else {
                double[] sums = money.computeIfAbsent(uuid, k -> new double[2]);
                sums[1] += usd(record);
                if (record.get("t").getAsLong() >= todayStart) sums[0] += usd(record);
            }
            return true;
        });
        JsonArray out = new JsonArray();
        for (JsonElement element : parts.game().players()) {
            JsonObject player = element.getAsJsonObject().deepCopy();
            String uuid = Json.string(player, "uuid");
            double[] sums = money.getOrDefault(uuid, new double[2]);
            player.addProperty("costTodayUsd", round(sums[0]));
            player.addProperty("costTotalUsd", round(sums[1]));
            player.addProperty("dialogues", dialogues.getOrDefault(uuid, 0));
            out.add(player);
        }
        return out;
    }

    JsonObject player(UUID uuid) {
        JsonObject player = parts.game().player(uuid);
        if (player == null) throw ApiException.notFound("Нет такого игрока.");
        JsonObject out = player.deepCopy();
        for (JsonElement summary : players())
            if (uuid.toString().equals(Json.string(summary.getAsJsonObject(), "uuid")))
                for (String key : List.of("costTodayUsd", "costTotalUsd", "dialogues")) out.add(key, summary.getAsJsonObject().get(key));
        Map<String, Double> byDay = new LinkedHashMap<>();
        LocalDate today = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate();
        for (int i = 29; i >= 0; i--) byDay.put(DAY.format(today.minusDays(i)), 0.0);
        parts.history().scan(new HistoryStore.Query(Set.of("cost"), uuid.toString(), null, null,
                today.minusDays(29).atStartOfDay(zone()).toInstant().toEpochMilli(), now(), null, 0), record -> {
            byDay.merge(DAY.format(Instant.ofEpochMilli(record.get("t").getAsLong()).atZone(zone())), usd(record), Double::sum);
            return true;
        });
        List<JsonObject> days = new ArrayList<>();
        byDay.forEach((day, usd) -> days.add(Json.obj("day", day, "usd", round(usd))));
        out.add("costByDay", Json.array(days));
        return out;
    }

    JsonObject costs(long from, long to, String group) {
        if (!Set.of("day", "player", "kind", "model").contains(group)) throw ApiException.badRequest("group — day, player, kind или model.");
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        double[] total = {0};
        parts.history().scan(new HistoryStore.Query(Set.of("cost"), null, null, null, from, to, null, 0), record -> {
            JsonObject data = record.getAsJsonObject("data");
            String key, label;
            switch (group) {
                case "day" -> {
                    LocalDate day = Instant.ofEpochMilli(record.get("t").getAsLong()).atZone(zone()).toLocalDate();
                    key = DAY.format(day); label = day.getDayOfMonth() + " " + MONTHS[day.getMonthValue() - 1];
                }
                case "player" -> { key = String.valueOf(playerUuid(record)); label = HistoryStore.nameOf(record.get("player")); if (label.isEmpty()) label = "сервер"; }
                case "kind" -> { key = String.valueOf(Json.string(data, "kind")); label = key; }
                default -> { key = String.valueOf(Json.string(data, "model")); label = key; }
            }
            double usd = usd(record);
            total[0] += usd;
            String finalLabel = label;
            JsonObject row = rows.computeIfAbsent(key, k -> Json.obj("key", k, "label", finalLabel, "usd", 0.0, "units", 0, "count", 0));
            row.addProperty("usd", row.get("usd").getAsDouble() + usd);
            row.addProperty("units", row.get("units").getAsLong() + Json.number(data, "units", 0));
            row.addProperty("count", row.get("count").getAsLong() + 1);
            return true;
        });
        List<JsonObject> list = new ArrayList<>(rows.values());
        for (JsonObject row : list) row.addProperty("usd", round(row.get("usd").getAsDouble()));
        if (group.equals("day")) list.sort((a, b) -> Json.string(a, "key").compareTo(Json.string(b, "key")));
        else list.sort((a, b) -> Double.compare(b.get("usd").getAsDouble(), a.get("usd").getAsDouble()));
        return Json.obj("group", group, "rows", list, "totalUsd", round(total[0]), "limits", parts.limits().get());
    }

    private HistoryStore.Page bySource(HistoryStore.Query query, String source) {
        List<JsonObject> items = new ArrayList<>();
        HistoryStore.Query plain = query;
        HistoryStore.Page page;
        String cursor = query.cursor();
        do {
            page = parts.history().search(plain.withCursor(cursor));
            for (JsonObject item : page.items()) if (source.equals(Json.string(item.getAsJsonObject("data"), "source")) && items.size() < query.limit()) items.add(item);
            cursor = page.next();
        } while (cursor != null && items.size() < query.limit());
        String next = items.size() >= query.limit() && !items.isEmpty() ? items.get(items.size() - 1).get("t").getAsLong() + "~" + Json.string(items.get(items.size() - 1), "id") : null;
        return new HistoryStore.Page(items, next);
    }

    private HistoryStore.Query query(Request r) {
        Set<String> kinds = new LinkedHashSet<>(list(r.query("kind")));
        Long from = r.query("from") == null ? null : r.longQuery("from", 0), to = r.query("to") == null ? null : r.longQuery("to", 0);
        return new HistoryStore.Query(kinds, r.query("player"), r.query("mob"), r.query("q"), from, to, r.query("cursor"), r.intQuery("limit", 50, 1, 500));
    }

    private static String world(Request r) { return r.query("world", "minecraft:overworld"); }

    private static JsonObject page(HistoryStore.Page page) { return Json.obj("items", page.items(), "next", page.next()); }

    /** Who changed something, as the history names it; one shared admin password for now. */
    private static String by(Request r) { return "админка"; }

    private static UUID uuid(String raw) {
        try { return UUID.fromString(raw); } catch (IllegalArgumentException bad) { throw ApiException.badRequest("Неверный UUID: " + raw); }
    }

    private static List<String> list(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    private static String playerUuid(JsonObject record) {
        JsonElement player = record.get("player");
        return player == null || !player.isJsonObject() ? null : Json.string(player.getAsJsonObject(), "uuid");
    }

    private static double usd(JsonObject record) {
        JsonObject data = record.getAsJsonObject("data");
        JsonElement usd = data == null ? null : data.get("usd");
        return usd == null || usd.isJsonNull() ? 0 : usd.getAsDouble();
    }

    private static void copy(JsonObject from, String key, JsonObject to, String as) { if (from.has(key)) to.add(as, from.get(key)); }
    private static double round(double usd) { return Math.round(usd * 10000.0) / 10000.0; }
}
