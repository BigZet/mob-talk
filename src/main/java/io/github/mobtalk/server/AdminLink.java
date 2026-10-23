package io.github.mobtalk.server;

import com.google.gson.JsonObject;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SettingsScope;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.server.web.AdminJournal;
import io.github.mobtalk.server.web.AdminWeb;
import io.github.mobtalk.server.web.Backups;
import io.github.mobtalk.server.web.Json;
import io.github.mobtalk.server.web.Latency;
import io.github.mobtalk.server.web.MapSampler;
import io.github.mobtalk.server.web.MetricSeries;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/** Веб-админка на живом выделенном сервере (трек H по точкам подключения docs/admin-web.md): старт и остановка {@link AdminWeb} вместе
 * с сервером, {@link LiveGameView}, резервные копии через save-off/save-all/save-on, метрики раз в 10 с из тиков, журнал диалогов из
 * {@link DialogueService}, карта из загруженных чанков, источники других модов из entrypoint {@code mobtalk-admin}. В одиночке
 * админка не поднимается. Один сервер на JVM: обработчики событий регистрируются один раз. */
public final class AdminLink {
    private AdminLink() { }
    private static final int SAMPLE_TICKS = 200;

    private static volatile AdminWeb web;
    private static volatile LiveGameView view;
    private static volatile MinecraftServer current;
    private static MapSampler sampler;
    private static boolean hooked;
    private static final List<Long> tickNanos = new ArrayList<>();
    private static int ticks;
    /** Молитвы бога уже перенесены в историю до этого времени (журнал молитв мода живёт в памяти и начинается с запуска сервера). */
    private static long prayersSince;
    /** Открытые записи диалогов: id записи → игрок, чтобы админка могла отменить диалог. */
    private static final Map<String, UUID> runs = new ConcurrentHashMap<>();

    public static AdminWeb web() { return web; }

    /** Поток сервера, мост уже поднят. */
    public static void start(MinecraftServer server, MobGameBridge bridge, DialogueService dialogue) {
        if (web != null) stop();
        java.nio.file.Path runDir = server.getRunDirectory().toAbsolutePath();
        LiveGameView game = new LiveGameView(server, bridge, MobTalkMod::dialogue, runDir);
        Backups.Saver saver = new Backups.Saver() {
            @Override public void pauseAndFlush() throws Exception {
                server.submit(() -> { for (ServerWorld world : server.getWorlds()) world.savingDisabled = true; server.saveAll(true, true, true); return null; }).get(60, TimeUnit.SECONDS);
            }
            @Override public void resume() { server.execute(() -> { for (ServerWorld world : server.getWorlds()) world.savingDisabled = false; }); }
        };
        AdminWeb.Hooks hooks = new AdminWeb.Hooks(game, saver, server.getSavePath(WorldSavePath.ROOT), ServerConfig::get, ServerConfig::update,
                SettingsScope::personal, AdminLink::limits);
        AdminWeb started = AdminWeb.start(runDir, hooks, MobTalkMod.LOGGER::info);
        if (started == null) return;
        web = started; view = game; current = server;
        runs.clear();
        prayersSince = System.currentTimeMillis();
        for (var entry : FabricLoader.getInstance().getEntrypointContainers("mobtalk-admin", UnaryOperator.class)) {
            String id = entry.getProvider().getMetadata().getId();
            if ("mobtalk".equals(id) || started.sources().has(id)) continue;
            try {
                @SuppressWarnings("unchecked") UnaryOperator<String> source = (UnaryOperator<String>) entry.getEntrypoint();
                started.sources().add(id, source);
                MobTalkMod.LOGGER.info("Админка: источник настроек «{}» подключён", id);
            } catch (Throwable failure) { MobTalkMod.LOGGER.warn("Админка: источник «{}» не подключён ({})", id, failure.toString()); }
        }
        game.sources(started.sources());
        AdminCommands.setWebAddress(() -> { AdminWeb current = web; return current == null ? "" : String.join("  ", current.describe()); });
        if (dialogue != null) dialogue.setJournal(new JournalAdapter(started.journal()));
        if (sampler == null) sampler = MapSampler.register(started.map());
        if (!hooked) {
            hooked = true;
            ServerTickEvents.END_SERVER_TICK.register(AdminLink::tick);
            ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
                LiveGameView live = view; AdminWeb admin = web;
                if (live != null) live.joined(handler.getPlayer());
                if (admin != null) admin.journal().event("player_join", Json.player(handler.getPlayer().getUuid(), handler.getPlayer().getGameProfile().getName()), null,
                        handler.getPlayer().getGameProfile().getName() + " вошёл", new JsonObject());
            });
            // Смерти игроков: точка для карты админки (LiveGameView) и строка «death» в истории — без координат, как вся история.
            net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
                if (!(entity instanceof net.minecraft.server.network.ServerPlayerEntity player)) return;
                LiveGameView live = view; AdminWeb admin = web;
                String cause = source.getDeathMessage(player).getString();
                if (live != null) live.died(player, cause);
                if (admin != null) admin.journal().event("death", Json.player(player.getUuid(), player.getGameProfile().getName()), null, cause, new JsonObject());
            });
            ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
                LiveGameView live = view; AdminWeb admin = web;
                if (live != null) live.left(handler.getPlayer());
                if (admin != null) admin.journal().event("player_leave", Json.player(handler.getPlayer().getUuid(), handler.getPlayer().getGameProfile().getName()), null,
                        handler.getPlayer().getGameProfile().getName() + " вышел", new JsonObject());
            });
        }
    }

    public static void stop() {
        AdminWeb admin = web;
        web = null; view = null; current = null;
        runs.clear();
        AdminCommands.setWebAddress(() -> "");
        if (admin != null) admin.stop();
    }

    /** Лимиты для вкладки «Расходы» (поля трека C). */
    private static JsonObject limits() {
        MobTalkConfig config = ServerConfig.get();
        return Json.obj("perPlayerMinute", config.budgetPlayerPerMinute, "perPlayerDayUsd", config.budgetPlayerDailyUsd,
                "serverDayUsd", config.budgetServerDailyUsd, "maxDialoguesPerPlayer", PlayerBudgetImpl.PER_PLAYER_SLOTS);
    }

    /** Админка отменяет диалог по id записи. */
    static boolean cancelDialogue(String id) {
        UUID player = runs.get(id);
        DialogueService service = MobTalkMod.dialogue();
        MinecraftServer server = current;
        if (player == null || service == null || server == null) return false;
        server.execute(() -> service.cancel(player, "Разговор остановлен администратором"));
        return true;
    }

    // ---- Метрики раз в 10 с. ----

    private static void tick(MinecraftServer server) {
        AdminWeb admin = web;
        if (admin == null || server != current) return;
        long[] times = server.getTickTimes();
        if (times != null && times.length > 0) tickNanos.add(times[Math.floorMod(server.getTicks() - 1, times.length)]);
        if (++ticks % SAMPLE_TICKS != 0) return;
        List<Long> sample = new ArrayList<>(tickNanos);
        tickNanos.clear();
        Map<MetricSeries, Double> values = new EnumMap<>(MetricSeries.class);
        if (!sample.isEmpty()) {
            long[] sorted = sample.stream().mapToLong(Long::longValue).sorted().toArray();
            double avg = Arrays.stream(sorted).average().orElse(0) / 1e6;
            values.put(MetricSeries.MSPT, avg);
            values.put(MetricSeries.MSPT_P95, sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * 0.95))] / 1e6);
            values.put(MetricSeries.MSPT_MAX, sorted[sorted.length - 1] / 1e6);
            values.put(MetricSeries.TPS, Math.min(20.0, 1000.0 / Math.max(50.0, avg)));
        }
        Runtime runtime = Runtime.getRuntime();
        values.put(MetricSeries.MEM_USED, (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0);
        values.put(MetricSeries.MEM_MAX, runtime.maxMemory() / 1048576.0);
        values.put(MetricSeries.ONLINE, (double) server.getCurrentPlayerCount());
        int chunks = 0, entities = 0;
        List<JsonObject> worlds = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            int loaded = world.getChunkManager().getLoadedChunkCount();
            int count = 0;
            for (var ignored : world.iterateEntities()) count++;
            chunks += loaded; entities += count;
            worlds.add(Json.obj("id", world.getRegistryKey().getValue().toString(), "chunks", loaded, "entities", count));
        }
        values.put(MetricSeries.CHUNKS, (double) chunks);
        values.put(MetricSeries.ENTITIES, (double) entities);
        LiveGameView live = view;
        if (live != null) values.put(MetricSeries.MOBS_PROFILED, (double) live.profiledLoaded());
        AdminJournal journal = admin.journal();
        values.put(MetricSeries.DIALOGUES_ACTIVE, (double) journal.active());
        values.put(MetricSeries.LLM_INFLIGHT, (double) journal.active());
        values.put(MetricSeries.LLM_QUEUE, 0.0);
        values.put(MetricSeries.DIALOGUES, (double) journal.drainStarted());
        values.put(MetricSeries.COST_USD, journal.drainSpent());
        values.put(MetricSeries.ERRORS, (double) admin.errors().drain());
        long now = System.currentTimeMillis();
        Latency latency = admin.latency();
        for (String kind : Latency.KINDS) {
            long p50 = latency.percentile(kind, now - 600_000, now, 50), p95 = latency.percentile(kind, now - 600_000, now, 95);
            if (p50 > 0) values.put(MetricSeries.latency(kind, false), (double) p50);
            if (p95 > 0) values.put(MetricSeries.latency(kind, true), (double) p95);
        }
        admin.metrics().record(values, worlds);
        pullVerdicts(admin);
    }

    /** Вердикты бога в историю: молитвы источника divineblock (операция prayers) становятся записями «verdict» с data.act — по ним
     * рисуются графики и «Последние вердикты» на вкладке «Бог», и они переживают перезапуск. Журнал молитв в памяти и мал, поэтому раз в 10 с. */
    private static void pullVerdicts(AdminWeb admin) {
        if (!admin.sources().has("divineblock")) return;
        try {
            JsonObject answer = admin.sources().call("divineblock", Json.obj("op", "prayers", "since", prayersSince, "limit", 300));
            if (!answer.has("items")) return;
            var items = answer.getAsJsonArray("items");
            for (int i = items.size() - 1; i >= 0; i--) { // новые первыми — пишем по порядку
                JsonObject p = items.get(i).getAsJsonObject();
                long t = Json.number(p, "t", 0);
                prayersSince = Math.max(prayersSince, t);
                JsonObject who = p.has("player") ? p.getAsJsonObject("player") : new JsonObject();
                JsonObject choice = p.has("choice") ? p.getAsJsonObject("choice") : new JsonObject();
                String name = Json.string(who, "name"), act = Json.string(choice, "kind"), deed = Json.string(choice, "deedTitle");
                UUID uuid = null;
                try { uuid = UUID.fromString(Json.string(who, "uuid")); } catch (IllegalArgumentException | NullPointerException none) { }
                String what = switch (act == null ? "" : act) { case "wrath" -> "кара"; case "miracle" -> "благо"; case "warning" -> "знамение"; case "jest" -> "шутка"; default -> "без ответа"; };
                admin.journal().event("verdict", Json.player(uuid, name), null, name + ": " + what + (deed == null || deed.isBlank() || "none".equals(act) ? "" : " «" + deed + "»"),
                        Json.obj("prayer", Json.string(p, "text"), "verdict", Json.string(p, "answer"), "act", act, "deed", deed,
                                "power", Json.number(choice, "power", 0), "tone", Json.string(p, "toneTitle")));
            }
        } catch (RuntimeException failure) { MobTalkMod.LOGGER.debug("Админка: вердикты бога не перенесены ({})", failure.toString()); }
    }

    // ---- Журнал диалогов. ----

    private static final class JournalAdapter implements DialogueService.Journal {
        private final AdminJournal journal;
        JournalAdapter(AdminJournal journal) { this.journal = journal; }

        private static JsonObject mob(UUID mob, String name, String species) {
            return Json.obj("uuid", mob == null ? null : mob.toString(), "name", name, "species", species == null ? "" : species.startsWith("minecraft:") ? species.substring(10) : species);
        }
        @Override public Object started(UUID player, String playerName, UUID mob, String mobName, String species, String question, String stt) {
            AdminJournal.Run run = journal.dialogueStarted(Json.player(player, playerName), mob(mob, mobName, species), question);
            run.step("accepted");
            runs.put(run.id, player);
            return run;
        }
        @Override public void step(Object run, String step) { if (run instanceof AdminJournal.Run r) r.step(step); }
        @Override public void finished(Object run, String answer, List<String> actions, String model, String stt, io.github.mobtalk.core.LlmSpend.Tally spend) {
            if (!(run instanceof AdminJournal.Run r)) return;
            runs.remove(r.id);
            r.finish(answer, actions == null ? List.of() : actions, spend.usd(), spend.tokensIn(), spend.tokensOut(), model, stt, null, null);
        }
        @Override public void failed(Object run, String step, String message) {
            if (!(run instanceof AdminJournal.Run r)) return;
            runs.remove(r.id);
            r.fail(step, message);
        }
        @Override public void cost(UUID player, String playerName, String kind, String model, io.github.mobtalk.core.LlmSpend.Tally spend) {
            journal.cost(player == null ? null : Json.player(player, playerName), kind, model, spend.usd(), (int) (spend.tokensIn() + spend.tokensOut()));
        }
        @Override public void event(String kind, UUID player, String playerName, UUID mob, String mobName, String species, String text, Map<String, Object> data) {
            JsonObject json = new JsonObject();
            if (data != null) data.forEach((key, value) -> json.add(key, Json.tree(value)));
            journal.event(kind, player == null ? null : Json.player(player, playerName), mob == null ? null : mob(mob, mobName, species), text, json);
        }
    }
}
