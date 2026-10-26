package io.github.mobtalk.server;

import io.github.mobtalk.core.MobClock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.net.OverlayStateS2C;
import io.github.mobtalk.server.web.Json;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Серверная часть оверлея для OBS (идея З3, docs/overlay.md): раз в 2 с собирает состояние бога (через источник {@code divineblock}
 * entrypoint {@code mobtalk-admin}), свадьбу и баннер админа и шлёт {@link OverlayStateS2C} тем, у кого в F7 включён оверлей и есть
 * право: серверная настройка {@code streamOverlay} и {@code streamOverlayAudience} ({@code streamer} — стример и админы, в одиночке —
 * хозяин мира). Шлёт только при изменении; кто потерял право или выключил оверлей, получает пустой объект. Поток сервера. */
public final class OverlayHub {
    private OverlayHub() { }
    static final int POLL_TICKS = 40;
    private static final int MAX_BANNER = 200;
    private static final Map<UUID, String> sent = new HashMap<>();
    private static UnaryOperator<String> divine;
    private static boolean divineLooked, overlayOp = true;
    private static volatile Banner banner;
    private static long linesSince;
    private static int ticks;

    record Banner(String text, long until) { }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(OverlayHub::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> { sent.clear(); banner = null; overlayOp = true; linesSince = MobClock.now(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> sent.remove(handler.getPlayer().getUuid())));
    }

    /** Игрок прислал личные настройки: ответить при следующем тике, а не через 2 с. */
    public static void settingsChanged() { ticks = POLL_TICKS - 1; }

    /** Баннер админа на оверлеях всех получателей; {@code minutes} 1–120. */
    public static String banner(String text, int minutes) {
        String clean = text == null ? "" : text.replaceAll("§.", "").strip();
        if (clean.isEmpty()) return "Пустой баннер не показывается.";
        if (clean.length() > MAX_BANNER) clean = clean.substring(0, MAX_BANNER);
        int span = Math.max(1, Math.min(120, minutes));
        banner = new Banner(clean, MobClock.now() + span * 60_000L);
        settingsChanged();
        return "Баннер на оверлее на " + span + " мин: " + clean;
    }
    public static String clearBanner() {
        boolean had = banner != null;
        banner = null; settingsChanged();
        return had ? "Баннер убран." : "Баннера не было.";
    }

    /** Имеет ли игрок право на данные оверлея по серверным настройкам. */
    static boolean allowed(MinecraftServer server, ServerPlayerEntity player, MobTalkConfig config) {
        if (!config.streamOverlay) return false;
        if ("all".equals(config.streamOverlayAudience)) return true;
        if (!server.isDedicated() && server.isHost(player.getGameProfile())) return true;
        return MobTalkMod.permissions().level(player) != Permissions.Level.PLAYER;
    }
    private static boolean personal(UUID player, String field, boolean fallback) {
        JsonElement value = PlayerSettingsStore.of(player).value(field);
        try { return value == null || value.isJsonNull() ? fallback : value.getAsBoolean(); }
        catch (RuntimeException notBoolean) { return fallback; }
    }

    private static void tick(MinecraftServer server) {
        if (++ticks < POLL_TICKS) return;
        ticks = 0;
        MobTalkConfig config = ServerConfig.get();
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        Set<UUID> online = new HashSet<>(), hidden = new HashSet<>();
        Map<UUID, ServerPlayerEntity> receivers = new HashMap<>();
        for (ServerPlayerEntity player : players) {
            UUID id = player.getUuid();
            online.add(id);
            if (!personal(id, "overlayShowMe", true)) hidden.add(id);
            if (personal(id, "overlayEnabled", false) && allowed(server, player, config) && ServerPlayNetworking.canSend(player, OverlayStateS2C.ID)) receivers.put(id, player);
        }
        for (UUID gone : new HashSet<>(sent.keySet())) {
            if (receivers.containsKey(gone)) continue;
            sent.remove(gone);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(gone);
            if (player != null && ServerPlayNetworking.canSend(player, OverlayStateS2C.ID)) ServerPlayNetworking.send(player, new OverlayStateS2C("{}"));
        }
        if (receivers.isEmpty()) return;
        JsonObject common;
        try { common = divineState(online, hidden); }
        catch (RuntimeException | LinkageError failure) {
            MobTalkMod.LOGGER.debug("Оверлей: данные бога не получены ({})", failure.toString());
            common = new JsonObject();
        }
        JsonArray events = common.has("events") && common.get("events").isJsonArray() ? common.getAsJsonArray("events") : new JsonArray();
        JsonObject wedding = wedding(server, hidden);
        if (wedding != null) events.add(wedding);
        Banner shown = banner;
        if (shown != null && shown.until() < MobClock.now()) { banner = null; shown = null; }
        if (shown != null) events.add(Json.obj("kind", "banner", "title", shown.text(), "player", null, "until", shown.until()));
        common.add("events", events);
        JsonArray lines = common.has("lines") && common.get("lines").isJsonArray() ? common.getAsJsonArray("lines") : new JsonArray();
        common.remove("lines");
        for (JsonElement line : lines) linesSince = Math.max(linesSince, Json.number(line.getAsJsonObject(), "t", 0));
        UUID streamer = ServerConfig.streamer();
        for (Map.Entry<UUID, ServerPlayerEntity> entry : receivers.entrySet()) {
            JsonObject mine = common.deepCopy();
            mine.addProperty("you", entry.getKey().toString());
            mine.addProperty("streamer", streamer == null ? null : streamer.toString());
            JsonArray heard = new JsonArray();
            for (JsonElement element : lines) {
                JsonObject line = element.getAsJsonObject();
                JsonArray listeners = line.has("listeners") ? line.getAsJsonArray("listeners") : new JsonArray();
                boolean hears = false;
                for (JsonElement who : listeners) if (entry.getKey().toString().equals(who.getAsString())) hears = true;
                if (hears) heard.add(Json.obj("t", Json.number(line, "t", 0), "text", Json.string(line, "text"), "millis", Json.number(line, "millis", 0)));
            }
            if (!heard.isEmpty()) mine.add("lines", heard);
            String json = Json.GSON.toJson(mine);
            if (json.equals(sent.get(entry.getKey()))) continue;
            sent.put(entry.getKey(), json);
            ServerPlayNetworking.send(entry.getValue(), new OverlayStateS2C(json));
        }
    }

    /** Бог: операция {@code overlay}, а если мод её не знает — запасной путь из {@code souls}, {@code active} и {@code prayers}. */
    private static JsonObject divineState(Set<UUID> online, Set<UUID> hidden) {
        UnaryOperator<String> source = divine();
        if (source == null) return new JsonObject();
        if (overlayOp) {
            JsonObject answer = call(source, Json.obj("op", "overlay", "since", linesSince));
            if (answer != null && !(answer.has("ok") && !answer.get("ok").getAsBoolean())) return OverlayState.fromOverlay(answer, online, hidden);
            overlayOp = false;
            MobTalkMod.LOGGER.info("Оверлей: Божественный блок без операции overlay, данные бога по запасному пути (souls, active, prayers)");
        }
        long now = MobClock.now();
        return OverlayState.fromParts(call(source, Json.obj("op", "souls")), call(source, Json.obj("op", "active")),
                call(source, Json.obj("op", "prayers", "since", now - OverlayState.MOOD_MILLIS, "limit", 50)), online, hidden, now);
    }
    private static JsonObject call(UnaryOperator<String> source, JsonObject request) {
        try {
            JsonObject answer = Json.parseObject(source.apply(Json.GSON.toJson(request)));
            return answer.has("ok") && !answer.get("ok").getAsBoolean() && !"overlay".equals(Json.string(request, "op")) ? null : answer;
        } catch (RuntimeException failure) { return null; }
    }
    @SuppressWarnings("unchecked")
    private static UnaryOperator<String> divine() {
        if (divineLooked) return divine;
        divineLooked = true;
        for (var entry : FabricLoader.getInstance().getEntrypointContainers("mobtalk-admin", UnaryOperator.class)) {
            if (!"divineblock".equals(entry.getProvider().getMetadata().getId())) continue;
            try { divine = (UnaryOperator<String>) entry.getEntrypoint(); }
            catch (Throwable failure) { MobTalkMod.LOGGER.warn("Оверлей: источник divineblock не подключён ({})", failure.toString()); }
        }
        return divine;
    }

    private static JsonObject wedding(MinecraftServer server, Set<UUID> hidden) {
        MobGameBridge bridge = MobTalkMod.bridgeReady(server).getNow(null);
        GameBridge.WeddingView view = bridge == null ? null : bridge.wedding();
        if (view == null) return null;
        try {
            var cast = view.ceremony().cast();
            String other;
            if (view.player() != null) {
                ServerPlayerEntity groom = server.getPlayerManager().getPlayer(view.player());
                other = hidden.contains(view.player()) || groom == null ? "игрок" : groom.getGameProfile().getName();
            } else other = cast.partner() == null ? "" : cast.partner().name();
            return Json.obj("kind", "wedding", "title", "Свадьба: " + cast.spouse().name() + (other.isEmpty() ? "" : " и " + other), "player", null, "until", 0);
        } catch (RuntimeException incomplete) { return null; }
    }
}
