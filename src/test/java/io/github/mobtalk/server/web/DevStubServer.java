package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import io.github.mobtalk.core.MobTalkConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The admin API without Minecraft, for building the page (track W2): the real server, login and routes over fixtures, a week
 * of generated history, 30 days of metrics, and a live feed (points every 2 s, dialogues and events every few seconds).
 * {@code gradlew runAdminStub}, then http://127.0.0.1:8788/ with the password {@code dev}. Loopback and plain HTTP only. */
public final class DevStubServer {
    private DevStubServer() { }

    static final UUID BIGZET = UUID.fromString("461baa90-bc98-4f86-b889-50f67d759b9a"), TEST2 = UUID.fromString("2f83646d-2eeb-4a75-8358-03e02e2ef99f");
    static final List<JsonObject> PLAYERS = List.of(Json.player(BIGZET, "BigZet"), Json.player(TEST2, "Тест2"));
    static final List<JsonObject> MOBS = List.of(
            mob("fb57b8eb-d3e2-4f1e-8da3-2101caf9f459", "Тереза", "villager"), mob("551637c3-f0e2-4a47-ba1c-9ea236681fb5", "Гаврила", "villager"),
            mob("0bb92b7d-a00e-47e8-b8a3-70d44fc135dd", "Пётр", "villager"), mob("47bdae92-388e-4d9c-8a4f-c8942a59f1cb", "Бурёнка", "cow"),
            mob("64c45041-c9c5-450f-9876-3b45bd34ce0d", "Шуршик", "creeper"));
    static final String[][] LINES = {
            {"где тут кузнец?", "За колодцем, где дым валит. Только Гаврилу не зли."},
            {"как дела?", "Пироги подгорели, а так ничего. Ты как?"},
            {"продашь хлеб?", "За изумруд — хоть два. Торгуемся?"},
            {"кто обижал кузнеца?", "Тест2 его стукнул, вся деревня знает."},
            {"пойдём со мной", "Пойду, только недалеко — у меня тесто."},
            {"ты меня любишь?", "Ой, скажешь тоже… может быть."}};

    public static void main(String[] args) throws Exception {
        int port = 8788;
        Path web = Path.of("src/main/resources/assets/mobtalk/web");
        String password = "dev";
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--web" -> web = Path.of(args[i + 1]);
                case "--password" -> password = args[i + 1];
                default -> throw new IllegalArgumentException("неизвестный параметр " + args[i]);
            }
        }
        Path data = Files.createTempDirectory("mobtalk-admin-stub");
        Stub stub = create(data, System::currentTimeMillis, new Random(7));
        stub.seed();
        stub.seedMap(1024);
        AdminHttpServer server = new AdminHttpServer(new AdminHttpServer.Options("127.0.0.1", port, null, false),
                new AdminAuth(AdminAuth.hash(password), System::currentTimeMillis), stub.api.router(), StaticFiles.folder(web), System.out::println);
        server.mount("/map3d/", BlueMapControl.CSP, stub.api.map3dFiles());
        server.start();
        stub.simulate();
        System.out.println("Заглушка админки: http://127.0.0.1:" + server.port() + "/  пароль: " + password);
        System.out.println("Страница: " + web.toAbsolutePath() + (Files.isRegularFile(web.resolve("index.html")) ? "" : "  (index.html ещё нет)"));
        System.out.println("Данные: " + data + "  (Ctrl+C — выход)");
        Thread.currentThread().join();
    }

    /** Everything the stub runs; the API tests build the same thing. */
    static final class Stub {
        final HistoryStore history;
        final Metrics metrics;
        final Latency latency;
        final AdminJournal journal;
        final ErrorLog errors;
        final ConfigSources sources;
        final FixtureGame game;
        final MapTiles map;
        final Tracks tracks;
        final AdminApi api;
        final AtomicReference<MobTalkConfig> config = new AtomicReference<>(new MobTalkConfig());
        final AdminSettings settings = new AdminSettings();
        final Random random;
        final java.util.function.LongSupplier clock;
        final Path data;

        Stub(Path data, java.util.function.LongSupplier clock, Random random) throws IOException {
            this.data = data; this.clock = clock; this.random = random;
            ZoneId zone = ZoneId.systemDefault();
            history = new HistoryStore(data.resolve("history"), clock, zone, 90, System.err::println);
            metrics = new Metrics(data.resolve("metrics"), clock, System.err::println);
            latency = new Latency(clock);
            journal = new AdminJournal(history, latency, clock, () -> settings.keepPrompts);
            errors = new ErrorLog(history, clock);
            sources = new ConfigSources(history);
            game = new FixtureGame(clock);
            map = new MapTiles(data.resolve("map"), System.err::println);
            tracks = new Tracks(data.resolve("tracks"), clock, 7, System.err::println);
            sources.add(MobTalkAdminSource.ID, new MobTalkAdminSource(config::get, config::updateAndGet, MobTalkAdminSource::personal));
            sources.add("divineblock", game.divineSource());
            sources.add("admin", AdminSettings.source(settings, null, history));
            Path world = data.resolve("world");
            Files.createDirectories(world.resolve("region"));
            Files.writeString(world.resolve("level.dat"), "заглушка мира");
            Files.write(world.resolve("region").resolve("r.0.0.mca"), new byte[4096]);
            Files.writeString(world.resolve("session.lock"), "lock");
            Backups backups = new Backups(world, data.resolve("backups"), new Backups.Saver() {
                @Override public void pauseAndFlush() { game.commands.add("save-off"); game.commands.add("save-all flush"); }
                @Override public void resume() { game.commands.add("save-on"); }
            }, clock, zone);
            // A stand-in BlueMap webroot: a page instead of the real webapp, one stored gzip tile, keepalive and status work for real.
            Path bluemap = data.resolve("bluemap").resolve("web");
            Files.createDirectories(bluemap.resolve("maps").resolve("world").resolve("tiles").resolve("0").resolve("x0"));
            Files.writeString(bluemap.resolve("index.html"), "<!doctype html><meta charset=utf-8><title>BlueMap</title>"
                    + "<body style=\"font:16px system-ui;background:#1b1d22;color:#ddd;display:grid;place-items:center;height:100vh;margin:0\">"
                    + "<p>Здесь будет 3D-карта BlueMap (заглушка: настоящий webroot появится на сервере).</p>");
            try (var gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(bluemap.resolve("maps/world/tiles/0/x0/z0.prbm.gz")))) { gz.write(new byte[]{1, 2, 3}); }
            Path log = data.resolve("latest.log");
            Files.writeString(log, String.join("\n",
                    "[19:30:01] [main/INFO] (FabricLoader) Loading 14 mods",
                    "[19:30:05] [Server thread/INFO] (mobtalk) Mob Talk: сервер поднят, админка на 127.0.0.1:8787",
                    "[19:30:09] [BlueMap-Plugin-Loading/INFO] (bluemap) BlueMap loaded!",
                    "[19:31:12] [mobtalk-dialogue/WARN] (mobtalk) OpenRouter ответил 429, повтор через 2 с",
                    "[19:32:40] [Server thread/INFO] (divineblock) Вердикт для Тест2: кара «Тяжёлые ноги»",
                    "[19:33:02] [Server thread/ERROR] (mobtalk) Не удалось озвучить реплику",
                    "java.io.IOException: timeout",
                    "\tat io.github.mobtalk.server.SpeechService.synthesize(SpeechService.java:42)",
                    "[19:34:10] [Server thread/INFO] (Minecraft) BigZet joined the game",
                    "[19:35:18] [Server thread/INFO] (Minecraft) <BigZet> привет") + "\n");
            api = new AdminApi(new AdminApi.Parts(history, metrics, latency, journal, errors, sources, game, backups, new Waypoints(data.resolve("waypoints.json")), map, tracks,
                    new BlueMapControl(bluemap, log, game, clock), log,
                    () -> Json.obj("perPlayerMinute", 0, "perPlayerDayUsd", 0, "serverDayUsd", 0, "maxDialoguesPerPlayer", 2),
                    () -> Json.obj("bind", "127.0.0.1", "port", 8788, "fingerprint", "заглушка без TLS"), clock));
        }

        /** A week of history and 30 days of metrics. */
        void seed() {
            long now = clock.getAsLong();
            for (long t = now - 7 * 86_400_000L; t < now - 60_000; t += 600_000 + random.nextInt(1_800_000)) seedEvent(t);
            history.append(now - 3_000_000, "config_change", null, null, "Настройка mobtalk.maxReplyWords: 45 → 60 (админка)",
                    Json.obj("source", "mobtalk", "key", "maxReplyWords", "before", 45, "after", 60, "by", "админка", "rollbackOf", null));
            config.updateAndGet(c -> { MobTalkConfig copy = c.copy(); copy.maxReplyWords = 60; return copy; });
            try { throw new IllegalStateException("моб выгружен во время ответа"); }
            catch (IllegalStateException failure) { errors.record(failure, "DialogueService.deliver", "Тереза, BigZet"); }
            long nowSeconds = now / 1000;
            for (long t = nowSeconds - 30 * 86400L; t < nowSeconds - Metrics.RAW_KEEP; t += Metrics.ROLLUP) metrics.record(t, sample(t, 0), List.of());
            for (long t = nowSeconds - Metrics.RAW_KEEP; t < nowSeconds; t += Metrics.STEP) metrics.record(t, sample(t, 0), worlds());
            for (int i = 0; i < 60; i++) {
                long t = now - random.nextInt(86_400_000);
                latency.record("stt", 600 + random.nextInt(900), t);
                latency.record("llm_first", 700 + random.nextInt(1200), t);
                latency.record("llm", 1500 + random.nextInt(2500), t);
                latency.record("tts", 500 + random.nextInt(1500), t);
                latency.record("total", 3000 + random.nextInt(4000), t);
            }
            history.flush();
            metrics.flush();
        }

        /** A made-up land around spawn (±{@code radius} blocks) and six hours of walking in circles for both online players. */
        void seedMap(int radius) {
            for (int cz = -radius / 16; cz < radius / 16; cz++) for (int cx = -radius / 16; cx < radius / 16; cx++) map.update("minecraft:overworld", cx, cz, terrain(cx, cz));
            for (int cz = -8; cz < 8; cz++) for (int cx = -8; cx < 8; cx++) map.update("minecraft:the_nether", cx, cz, nether(cx, cz));
            long now = clock.getAsLong();
            for (long t = now - 6 * 3_600_000L; t < now; t += Tracks.EVERY) {
                double a = (t % 1_800_000L) / 1_800_000.0 * 2 * Math.PI;
                tracks.record(BIGZET, "BigZet", t, "minecraft:overworld", 60 + 140 * Math.cos(a), 66, -250 + 90 * Math.sin(a));
                tracks.record(TEST2, "Тест2", t, "minecraft:overworld", 300 + 60 * Math.cos(-a * 2), 64, 120 + 60 * Math.sin(-a * 2) - 0.01 * ((t / 1000) % 600));
            }
            map.flush();
            tracks.flush();
        }

        static int[] terrain(int cx, int cz) {
            int[] colors = new int[256];
            double[] heights = new double[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int bx = cx * 16 + x, bz = cz * 16 + z;
                double h = 64 + 22 * noise(bx / 180.0, bz / 180.0) + 7 * noise(bx / 40.0 + 9, bz / 40.0 - 3);
                double trees = noise(bx / 60.0 - 20, bz / 60.0 + 11);
                heights[z * 16 + x] = h;
                int rgb;
                if (h < 62) rgb = h < 56 ? 0x2C3EAE : 0x4040FF;
                else if (h < 64) rgb = 0xF7E9A3;
                else if (h > 86) rgb = 0xFFFFFF;
                else if (h > 78) rgb = 0x707070;
                else if (trees > 0.25) rgb = 0x007C00;
                else rgb = 0x7FB238;
                double north = z > 0 ? heights[(z - 1) * 16 + x] : h;
                int brightness = h < 62 ? 220 : Math.floor(h) > Math.floor(north) ? 255 : Math.floor(h) < Math.floor(north) ? 180 : 220;
                int r = ((rgb >> 16) & 0xFF) * brightness / 255, g = ((rgb >> 8) & 0xFF) * brightness / 255, b = (rgb & 0xFF) * brightness / 255;
                colors[z * 16 + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            if (cx == 3 && cz == -16) for (int i = 0; i < 256; i += 3) colors[i] = 0xFF8F7748; // Берёзовка: дорожки
            return colors;
        }

        static int[] nether(int cx, int cz) {
            int[] colors = new int[256];
            for (int i = 0; i < 256; i++) {
                double n = noise((cx * 16 + i % 16) / 30.0, (cz * 16 + i / 16) / 30.0);
                colors[i] = n > 0.35 ? 0xFFD15C06 : n < -0.3 ? 0xFF4A1A1A : 0xFF701414;
            }
            return colors;
        }

        /** Smooth value noise in [-1, 1]. */
        static double noise(double x, double z) {
            int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
            double fx = x - x0, fz = z - z0, sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
            double a = lattice(x0, z0), b = lattice(x0 + 1, z0), c = lattice(x0, z0 + 1), d = lattice(x0 + 1, z0 + 1);
            return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sz;
        }

        private static double lattice(int x, int z) {
            long h = x * 374761393L + z * 668265263L;
            h = (h ^ (h >>> 13)) * 1274126177L;
            return ((h ^ (h >>> 16)) & 0xFFFF) / 32767.5 - 1;
        }

        private void seedEvent(long t) {
            JsonObject player = PLAYERS.get(random.nextInt(PLAYERS.size())), mob = MOBS.get(random.nextInt(MOBS.size()));
            switch (random.nextInt(8)) {
                case 0 -> history.append(t, "gift", player, mob, HistoryStore.nameOf(player) + " подарил " + HistoryStore.nameOf(mob) + " хлеб",
                        Json.obj("item", "minecraft:bread", "count", 1, "reaction", "Ой, спасибо!", "heartsBefore", 3, "heartsAfter", 4));
                case 1 -> history.append(t, "verdict", player, null, "Молитва " + HistoryStore.nameOf(player) + ": " + (random.nextBoolean() ? "кара" : "чудо"),
                        Json.obj("prayer", "Господи, дай алмазов", "verdict", "Наглость наказуема", "act", List.of("wrath", "wrath", "miracle", "jest", "warning", "none").get(random.nextInt(6)),
                                "deed", List.of("Молния", "Малое проклятие", "Исцеление", "Куриный дождь").get(random.nextInt(4)), "power", 1 + random.nextInt(3), "fury", random.nextInt(100)));
                case 2 -> history.append(t, "rumor", player, mob, "Слух: " + HistoryStore.nameOf(player) + " ходит с мечом по деревне",
                        Json.obj("village", "Берёзовка", "about", player, "line", "ходит с мечом по деревне"));
                case 3 -> history.append(t, random.nextBoolean() ? "player_join" : "player_leave", player, null, HistoryStore.nameOf(player) + " зашёл/вышел",
                        Json.obj("sessionSeconds", random.nextInt(7200)));
                case 4 -> history.append(t, "trade", player, mob, HistoryStore.nameOf(player) + " купил хлеб у " + HistoryStore.nameOf(mob),
                        Json.obj("offer", "хлеб за изумруд", "outcome", "deal", "items", List.of("minecraft:bread")));
                default -> seedDialogue(t, player, mob);
            }
        }

        private void seedDialogue(long t, JsonObject player, JsonObject mob) {
            String[] line = LINES[random.nextInt(LINES.length)];
            double usd = 0.0008 + random.nextInt(30) / 10000.0;
            int first = 700 + random.nextInt(1200), done = first + 400 + random.nextInt(1500), tts = done + 300 + random.nextInt(900);
            JsonObject trace = Json.obj("steps", List.of(Json.obj("step", "accepted", "ms", 0), Json.obj("step", "budget", "ms", 2), Json.obj("step", "context", "ms", 15),
                    Json.obj("step", "prompt", "ms", 21), Json.obj("step", "llm_first", "ms", first), Json.obj("step", "llm_done", "ms", done),
                    Json.obj("step", "parsed", "ms", done + 4), Json.obj("step", "actions", "ms", done + 9), Json.obj("step", "tts", "ms", tts),
                    Json.obj("step", "delivered", "ms", tts + 60)),
                    "sizes", Json.obj("promptChars", 5800 + random.nextInt(900), "tokensIn", 1700 + random.nextInt(300), "tokensOut", 40 + random.nextInt(40), "audioBytes", 30000 + random.nextInt(30000)),
                    "error", null);
            history.append(t, "dialogue", player, mob, HistoryStore.nameOf(player) + " → " + HistoryStore.nameOf(mob) + ": «" + line[0] + "» — «" + line[1] + "»",
                    Json.obj("question", line[0], "answer", line[1], "actions", List.of(), "usd", usd, "tokensIn", 1800, "tokensOut", 55,
                            "model", "openai/gpt-6-luna", "stt", random.nextBoolean() ? "local" : "server", "error", null, "ms", tts + 60, "trace", trace));
            history.append(t + 1, "cost", player, null, "llm openai/gpt-6-luna", Json.obj("kind", "llm", "model", "openai/gpt-6-luna", "usd", usd, "units", 1855));
            history.append(t + 2, "cost", player, null, "tts elevenlabs", Json.obj("kind", "tts", "model", "elevenlabs/eleven-flash-v2.5", "usd", usd / 2, "units", line[1].length()));
        }

        /** Smooth daily waves with noise: a server that breathes. */
        Map<MetricSeries, Double> sample(long t, int active) {
            double day = Math.sin(2 * Math.PI * (t % 86400) / 86400.0), fast = Math.sin(t / 300.0);
            Map<MetricSeries, Double> values = new EnumMap<>(MetricSeries.class);
            double mspt = 14 + 6 * day + 3 * fast + random.nextGaussian() * 1.5;
            values.put(MetricSeries.MSPT, Math.max(3, mspt));
            values.put(MetricSeries.MSPT_P95, Math.max(4, mspt * 1.6));
            values.put(MetricSeries.MSPT_MAX, Math.max(5, mspt * 2.4 + random.nextInt(10)));
            values.put(MetricSeries.TPS, Math.min(20, 1000 / Math.max(50, mspt * 3.2)));
            values.put(MetricSeries.MEM_USED, 2600 + 900 * (0.5 + 0.5 * fast) + random.nextInt(120));
            values.put(MetricSeries.MEM_MAX, 6144.0);
            values.put(MetricSeries.ONLINE, (double) Math.max(0, Math.round(1.5 + 1.5 * day)));
            values.put(MetricSeries.CHUNKS, 420 + 120 * day + random.nextInt(20));
            values.put(MetricSeries.ENTITIES, 800 + 200 * day + random.nextInt(40));
            values.put(MetricSeries.MOBS_PROFILED, 24 + 4 * day);
            values.put(MetricSeries.DIALOGUES_ACTIVE, (double) active);
            values.put(MetricSeries.LLM_QUEUE, (double) Math.max(0, random.nextInt(4) - 2));
            values.put(MetricSeries.LLM_INFLIGHT, (double) active);
            values.put(MetricSeries.DIALOGUES, (double) (random.nextInt(10) < 3 ? 1 : 0));
            values.put(MetricSeries.COST_USD, random.nextInt(10) < 3 ? 0.002 : 0.0);
            values.put(MetricSeries.ERRORS, (double) (random.nextInt(200) == 0 ? 1 : 0));
            values.put(MetricSeries.HOGWEED, 140 + 30 * day);
            values.put(MetricSeries.BOSSES, 1.0);
            values.put(MetricSeries.CURSES, 1.0);
            for (String kind : Latency.KINDS) {
                double base = switch (kind) { case "stt" -> 900; case "llm_first" -> 1100; case "llm" -> 2300; case "tts" -> 900; default -> 4800; };
                values.put(MetricSeries.latency(kind, false), base + 200 * fast);
                values.put(MetricSeries.latency(kind, true), base * 1.8 + 300 * fast);
            }
            return values;
        }

        List<JsonObject> worlds() {
            return List.of(Json.obj("id", "minecraft:overworld", "chunks", 441, "entities", 812), Json.obj("id", "minecraft:the_nether", "chunks", 25, "entities", 31));
        }

        /** The live feed: metrics every 2 s, a dialogue walking through its steps, events now and then. */
        void simulate() {
            ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, task -> { Thread thread = new Thread(task, "admin-stub"); thread.setDaemon(true); return thread; });
            timer.scheduleAtFixedRate(() -> metrics.record(clock.getAsLong() / 1000, sample(clock.getAsLong() / 1000, journal.active()), worlds()), 0, 2, TimeUnit.SECONDS);
            timer.scheduleWithFixedDelay(() -> {
                try { liveDialogue(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            }, 1, 4, TimeUnit.SECONDS);
            timer.scheduleWithFixedDelay(() -> {
                if (random.nextInt(4) == 0) {
                    try { throw new java.io.IOException("OpenRouter не ответил за 45 с"); }
                    catch (java.io.IOException failure) { errors.record(failure, "OpenRouterConversationClient.stream", "заглушка"); }
                } else seedEvent(clock.getAsLong());
            }, 3, 7, TimeUnit.SECONDS);
        }

        private void liveDialogue() throws InterruptedException {
            JsonObject player = PLAYERS.get(random.nextInt(PLAYERS.size())), mob = MOBS.get(random.nextInt(MOBS.size()));
            String[] line = LINES[random.nextInt(LINES.length)];
            AdminJournal.Run run = journal.dialogueStarted(player, mob, line[0]);
            List<String> steps = new ArrayList<>(DialogueTrace.STEPS.subList(1, DialogueTrace.STEPS.size()));
            for (String step : steps) {
                Thread.sleep(step.startsWith("llm") || step.equals("tts") ? 600 + random.nextInt(900) : 30);
                if (journal.running(run.id) == null) return;
                run.step(step);
            }
            run.size("promptChars", 6000).size("tokensIn", 1800).size("tokensOut", 50).size("audioBytes", 41000);
            run.finish(line[1], List.of(), 0.0021, 1800, 50, "openai/gpt-6-luna", "local", "(промпт заглушки)", "(сырой ответ заглушки)");
        }
    }

    static Stub create(Path data, java.util.function.LongSupplier clock, Random random) throws IOException { return new Stub(data, clock, random); }

    private static JsonObject mob(String uuid, String name, String species) { return Json.obj("uuid", uuid, "name", name, "species", species); }
}
