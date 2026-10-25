package io.github.mobtalk.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.FamilyTree;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.VillageRumors;
import io.github.mobtalk.core.RivalryLedger;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.server.web.ApiException;
import io.github.mobtalk.server.web.GameView;
import io.github.mobtalk.server.web.Json;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Веб-админка смотрит на живой сервер (трек H, подключение W1): игроки, мобы и деревни из {@link ConversationStore}, позиции и
 * телепорт, бан и белый список, консольные команды, обзор. Админка зовёт на своих потоках; всё, что трогает игру, прыгает на поток
 * сервера через {@link #onServer} и ждёт не дольше десяти секунд. Координаты отдаются только там, где их ждёт контракт. */
final class LiveGameView implements GameView {
    private final MinecraftServer server;
    private final MobGameBridge bridge;
    private final Supplier<DialogueService> dialogue;
    private final Path runDir;
    private final long startedMillis = System.currentTimeMillis();
    /** Когда игрок вошёл (для {@code onlineSeconds}) и когда вышел (для {@code lastSeen}). */
    private final Map<UUID, Long> joined = new ConcurrentHashMap<>(), left = new ConcurrentHashMap<>();
    /** Последняя известная точка вышедшего игрока: мир и координаты, только для «Местоположения». */
    private final Map<UUID, JsonObject> lastPlace = new ConcurrentHashMap<>();
    /** Где игроки погибали: метки «death» на карте. */
    private final io.github.mobtalk.server.web.Deaths deaths;

    LiveGameView(MinecraftServer server, MobGameBridge bridge, Supplier<DialogueService> dialogue, Path runDir) {
        this.server = server; this.bridge = bridge; this.dialogue = dialogue; this.runDir = runDir;
        this.deaths = new io.github.mobtalk.server.web.Deaths(runDir.resolve("config").resolve("mobtalk-deaths.json"));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) joined.putIfAbsent(player.getUuid(), startedMillis);
    }

    /** Игрок погиб: точка для карты; строку в историю (без координат) пишет {@link AdminLink}. */
    void died(ServerPlayerEntity player, String cause) {
        deaths.add(System.currentTimeMillis(), player.getUuid(), player.getGameProfile().getName(), player.getWorld().getRegistryKey().getValue().toString(),
                player.getBlockX(), player.getBlockY(), player.getBlockZ(), cause);
    }

    void joined(ServerPlayerEntity player) { joined.put(player.getUuid(), System.currentTimeMillis()); left.remove(player.getUuid()); }
    void left(ServerPlayerEntity player) {
        joined.remove(player.getUuid());
        left.put(player.getUuid(), System.currentTimeMillis());
        lastPlace.put(player.getUuid(), place(player, false));
    }

    // ---- Поток сервера. ----

    private <T> T onServer(Supplier<T> work) {
        if (server.isOnThread()) return work.get();
        CompletableFuture<T> future = server.submit(work);
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof ApiException api) throw api;
            throw new IllegalStateException(failure.getCause() == null ? failure : failure.getCause());
        } catch (TimeoutException slow) { throw ApiException.conflict("Сервер не ответил за 10 с."); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw ApiException.conflict("Прервано."); }
    }
    private ConversationStore memory() { return bridge.memory; }
    private PlayerManager players_() { return server.getPlayerManager(); }

    // ---- Обзор и сервер. ----

    @Override public JsonObject overview() {
        return onServer(() -> {
            DialogueService service = dialogue.get();
            int talking = 0;
            if (service != null) for (ServerPlayerEntity player : players_().getPlayerList()) if (service.busyTalking(player.getUuid())) talking++;
            return Json.obj("online", players_().getCurrentPlayerCount(), "maxPlayers", players_().getMaxPlayerCount(),
                    "uptimeSeconds", (System.currentTimeMillis() - startedMillis) / 1000, "streamer", streamerJson(),
                    "dialoguesActive", talking, "llmQueue", 0, "llmInFlight", talking);
        });
    }

    private JsonObject streamerJson() {
        UUID streamer = ServerConfig.streamer();
        return streamer == null ? null : Json.player(streamer, nameOf(streamer));
    }

    @Override public JsonObject server() {
        return onServer(() -> {
            List<JsonObject> mods = new ArrayList<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                if (id.startsWith("fabric-") && !id.equals("fabric-api") || id.startsWith("java") || id.equals("minecraft") || id.equals("mixinextras")) continue;
                mods.add(Json.obj("id", id, "name", mod.getMetadata().getName(), "version", mod.getMetadata().getVersion().getFriendlyString()));
            }
            ServerWorld overworld = server.getOverworld();
            List<String> pack = new ArrayList<>();
            Path manifest = runDir.resolve("pack-manifest.txt");
            try { if (Files.exists(manifest)) pack.addAll(Files.readAllLines(manifest)); } catch (java.io.IOException ignored) { }
            return Json.obj("version", MobTalkMod.version(), "minecraft", server.getVersion(),
                    "loader", FabricLoader.getInstance().getModContainer("fabricloader").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(""),
                    "mods", mods, "uptimeSeconds", (System.currentTimeMillis() - startedMillis) / 1000,
                    "world", Json.obj("name", server.getSaveProperties().getLevelName(), "dir", worldDir(), "difficulty", server.getSaveProperties().getDifficulty().getName(),
                            "dayTime", overworld == null ? 0 : overworld.getTimeOfDay() % 24000),
                    "players", Json.obj("online", players_().getCurrentPlayerCount(), "max", players_().getMaxPlayerCount()), "pack", pack);
        });
    }

    @Override public List<String> commandRoots() {
        return onServer(() -> server.getCommandManager().getDispatcher().getRoot().getChildren().stream().map(node -> node.getName()).sorted().toList());
    }

    @Override public JsonObject runCommand(String command) {
        String text = command == null ? "" : command.strip();
        if (text.startsWith("/")) text = text.substring(1);
        if (text.isBlank()) throw ApiException.badRequest("Пустая команда.");
        String finalText = text;
        return onServer(() -> {
            List<String> output = new ArrayList<>();
            boolean[] ok = {false}; int[] result = {0};
            net.minecraft.server.command.CommandOutput sink = new net.minecraft.server.command.CommandOutput() {
                @Override public void sendMessage(Text message) { if (output.size() < 200) output.add(message.getString()); }
                @Override public boolean shouldReceiveFeedback() { return true; }
                @Override public boolean shouldTrackOutput() { return true; }
                @Override public boolean shouldBroadcastConsoleToOps() { return false; }
            };
            ServerCommandSource source = server.getCommandSource().withOutput(sink).withReturnValueConsumer((success, value) -> { ok[0] = success; result[0] = value; });
            try { server.getCommandManager().executeWithPrefix(source, finalText); }
            catch (RuntimeException failure) { output.add(String.valueOf(failure.getMessage())); }
            return Json.obj("ok", ok[0], "result", result[0], "output", output);
        });
    }



    @Override public JsonObject whitelistAdd(String nick) {
        if (nick == null || !nick.strip().matches("[A-Za-z0-9_]{3,16}")) throw ApiException.badRequest("Ник — 3–16 латинских букв, цифр или «_».");
        Optional<GameProfile> found;
        try { found = AdminCommands.profile(server, nick.strip()).get(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception failure) { throw ApiException.conflict("Не удалось найти игрока «" + nick.strip() + "»: " + failure.getClass().getSimpleName()); }
        if (found.isEmpty()) throw ApiException.notFound("Игрока «" + nick.strip() + "» нет: проверьте ник (на сервере с лицензией это ник аккаунта Minecraft).");
        GameProfile profile = found.get();
        return onServer(() -> {
            var list = players_().getWhitelist();
            if (list.isAllowed(profile)) return Json.ok(profile.getName() + " уже в whitelist");
            list.add(new WhitelistEntry(profile));
            return Json.ok(profile.getName() + " добавлен в whitelist");
        });
    }
    @Override public String worldDir() { return NewWorld.levelName(runDir); }
    @Override public JsonObject newWorld(String seed, String type, String by) {
        try { NewWorld.request(runDir, seed, type, by); }
        catch (IllegalArgumentException bad) { throw ApiException.badRequest(bad.getMessage()); }
        catch (java.io.IOException failure) { throw ApiException.conflict("Заявка на новый мир не записана: " + failure.getMessage()); }
        // Ответ успевает уйти в браузер, потом сервер останавливается как по /stop.
        Thread.ofVirtual().name("mobtalk-new-world").start(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            server.execute(() -> {
                players_().getPlayerList().forEach(player -> player.networkHandler.disconnect(Text.literal("Сервер готовит новый мир — зайдите через минуту")));
                server.stop(false);
            });
        });
        return Json.ok("Сервер останавливается: мир «" + worldDir() + "» уйдёт в world-backups, новый создастся при запуске");
    }
    @Override public JsonObject reloadConfig() {
        if (!ServerConfig.persistent()) return Json.obj("ok", false, "message", "В одиночной игре настройки сервера берутся из F7");
        try { ServerConfig.init(FabricLoader.getInstance().getConfigDir()); return Json.ok("Настройки сервера перечитаны из " + ServerConfig.FILE); }
        catch (Exception failure) { throw ApiException.conflict("Не удалось перечитать " + ServerConfig.FILE + ": " + failure.getClass().getSimpleName()); }
    }

    // ---- Игроки. ----

    private String nameOf(UUID player) {
        if (player == null) return "";
        ServerPlayerEntity online = players_().getPlayer(player);
        if (online != null) return online.getGameProfile().getName();
        for (Map.Entry<String, String> known : memory().playerIdsByName().entrySet()) if (known.getValue().equals(player.toString())) return known.getKey();
        return server.getUserCache() == null ? "" : server.getUserCache().getByUuid(player).map(GameProfile::getName).orElse("");
    }
    private GameProfile profile(UUID player) {
        ServerPlayerEntity online = players_().getPlayer(player);
        if (online != null) return online.getGameProfile();
        Optional<GameProfile> cached = server.getUserCache() == null ? Optional.empty() : server.getUserCache().getByUuid(player);
        return cached.orElseGet(() -> new GameProfile(player, nameOf(player)));
    }
    private Map<UUID, String> knownPlayers() {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (ServerPlayerEntity player : players_().getPlayerList()) out.put(player.getUuid(), player.getGameProfile().getName());
        memory().playerIdsByName().forEach((name, id) -> { try { out.putIfAbsent(UUID.fromString(id), name); } catch (IllegalArgumentException ignored) { } });
        for (UUID id : left.keySet()) out.putIfAbsent(id, nameOf(id));
        return out;
    }

    private JsonObject playerSummary(UUID id, String name) {
        ServerPlayerEntity online = players_().getPlayer(id);
        GameProfile profile = online != null ? online.getGameProfile() : profile(id);
        boolean op = players_().isOperator(profile);
        Permissions.Level level = online != null ? MobTalkMod.permissions().level(online) : PlayerServices.levelOf(id);
        BannedPlayerEntry ban = players_().getUserBanList().get(profile);
        Long since = joined.get(id);
        return Json.obj("uuid", id.toString(), "name", name, "online", online != null, "level", level.name(),
                "streamer", id.equals(ServerConfig.streamer()), "op", op, "whitelisted", players_().getWhitelist().isAllowed(profile),
                "banned", ban != null, "banReason", ban == null ? null : ban.getReason(),
                "onlineSeconds", online == null || since == null ? 0 : (System.currentTimeMillis() - since) / 1000,
                "lastSeen", online != null ? System.currentTimeMillis() : left.getOrDefault(id, 0L), "stt", stt(id));
    }

    /** Распознавание речи игрока: выбор в F7 ({@code chosen}), стоит ли свой Whisper ({@code installed}, null — клиент не сообщил),
     * что он получает на этом сервере ({@code effective}) и чем распознана последняя реплика ({@code last}). */
    private JsonObject stt(UUID id) {
        var settings = PlayerSettingsStore.find(id);
        String chosen = settings.map(s -> s.applyTo(ServerConfig.get()).sttPath).orElse("");
        com.google.gson.JsonElement flag = settings.map(s -> s.value(io.github.mobtalk.core.PlayerSettings.WHISPER_INSTALLED)).orElse(null);
        Boolean installed = flag != null && flag.isJsonPrimitive() ? flag.getAsBoolean() : null;
        String effective = settings.isEmpty() ? "" : io.github.mobtalk.core.SttRoute.choose(chosen, VoiceServices.sttPaths(ServerConfig.get()), Boolean.TRUE.equals(installed)).path();
        DialogueService service = dialogue.get();
        return Json.obj("chosen", chosen, "installed", installed, "effective", effective, "last", service == null ? "" : service.lastStt(id));
    }

    @Override public JsonArray players() {
        return onServer(() -> {
            JsonArray out = new JsonArray();
            knownPlayers().forEach((id, name) -> out.add(playerSummary(id, name)));
            return out;
        });
    }

    @Override public JsonObject player(UUID id) {
        return onServer(() -> {
            Map<UUID, String> known = knownPlayers();
            if (!known.containsKey(id)) return null;
            JsonObject out = playerSummary(id, known.get(id));
            List<JsonObject> relations = new ArrayList<>();
            for (MobCard card : memory().cards(id)) {
                if (!memory().relations(card.id()).containsKey(id)) continue;
                relations.add(Json.obj("mob", mobJson(card.id()), "relationship", card.relationship(), "label", MobGameBridge.relationshipLabel(card), "hearts", 0));
            }
            relations.sort((a, b) -> Integer.compare(b.get("relationship").getAsInt(), a.get("relationship").getAsInt()));
            out.add("relations", Json.array(relations));
            out.add("settings", PlayerSettingsStore.find(id).map(settings -> JsonParser.parseString(settings.toJson())).orElse(new JsonObject()));
            out.addProperty("activity", activity(id));
            ServerPlayerEntity online = players_().getPlayer(id);
            if (online != null) out.addProperty("gamemode", online.interactionManager.getGameMode().getName());
            return out;
        });
    }

    private String activity(UUID player) {
        DialogueService service = dialogue.get();
        ServerPlayerEntity online = players_().getPlayer(player);
        if (online == null) return "offline";
        if (online.currentScreenHandler instanceof net.minecraft.screen.MerchantScreenHandler) return "trading";
        return service != null && service.busyTalking(player) ? "talking" : "idle";
    }

    @Override public JsonObject playerAction(UUID id, String action, JsonObject body) {
        return onServer(() -> {
            GameProfile profile = profile(id);
            String name = profile.getName() == null || profile.getName().isBlank() ? id.toString().substring(0, 8) : profile.getName();
            ServerPlayerEntity online = players_().getPlayer(id);
            String reason = body == null ? "" : Json.string(body, "reason");
            switch (action) {
                case "level" -> {
                    String level = body == null ? "" : Json.string(body, "level");
                    if ("ADMIN".equals(level)) { players_().addToOperators(profile); return Json.ok(name + " теперь оператор"); }
                    if ("PLAYER".equals(level)) { players_().removeFromOperators(profile); return Json.ok(name + " больше не оператор"); }
                    throw ApiException.badRequest("level — ADMIN или PLAYER.");
                }
                case "kick" -> {
                    if (online == null) throw ApiException.conflict(name + " не в игре.");
                    online.networkHandler.disconnect(Text.literal(reason.isBlank() ? "Выгнан администратором" : reason));
                    return Json.ok(name + " выгнан");
                }
                case "ban" -> {
                    players_().getUserBanList().add(new BannedPlayerEntry(profile, null, "админка", null, reason.isBlank() ? null : reason));
                    if (online != null) online.networkHandler.disconnect(Text.literal(reason.isBlank() ? "Вы забанены" : reason));
                    return Json.ok(name + " забанен");
                }
                case "unban" -> { players_().getUserBanList().remove(profile); return Json.ok("Бан " + name + " снят"); }
                case "gamemode" -> {
                    if (online == null) throw ApiException.conflict(name + " не в игре: режим меняется, пока игрок на сервере.");
                    net.minecraft.world.GameMode mode = net.minecraft.world.GameMode.byName(body == null ? "" : String.valueOf(Json.string(body, "mode")), null);
                    if (mode == null) throw ApiException.badRequest("mode — survival, creative, adventure или spectator.");
                    online.changeGameMode(mode);
                    return Json.ok(name + ": режим " + mode.getName());
                }
                case "whitelist" -> {
                    boolean on = body != null && body.has("on") && body.get("on").getAsBoolean();
                    if (on) players_().getWhitelist().add(new WhitelistEntry(profile)); else players_().getWhitelist().remove(profile);
                    return Json.ok(name + (on ? " в белом списке" : " убран из белого списка"));
                }
                default -> throw ApiException.badRequest("Неизвестное действие: " + action);
            }
        });
    }

    @Override public JsonObject setStreamer(UUID id) {
        return onServer(() -> {
            if (id == null) return Json.ok(AdminActions.clearStreamer());
            if (!knownPlayers().containsKey(id)) throw ApiException.notFound("Нет такого игрока.");
            return Json.ok(AdminActions.streamer(id, nameOf(id)));
        });
    }

    // ---- Местоположение. ----

    private JsonObject place(ServerPlayerEntity player, boolean online) {
        ServerWorld world = player.getServerWorld();
        BlockPos at = player.getBlockPos();
        return Json.obj("player", Json.player(player.getUuid(), player.getGameProfile().getName()), "online", online,
                "world", world.getRegistryKey().getValue().toString(), "x", round(player.getX()), "y", round(player.getY()), "z", round(player.getZ()),
                "yaw", round(player.getYaw()), "biome", world.getBiome(at).getKey().map(key -> key.getValue().toString()).orElse(""),
                "health", round(player.getHealth()), "food", player.getHungerManager().getFoodLevel(),
                "gamemode", player.interactionManager.getGameMode().getName());
    }
    private static double round(double value) { return Math.round(value * 10) / 10.0; }

    @Override public JsonObject positions() {
        return onServer(() -> {
            List<JsonObject> list = new ArrayList<>();
            for (ServerPlayerEntity player : players_().getPlayerList()) list.add(place(player, true));
            return Json.obj("t", System.currentTimeMillis(), "players", list);
        });
    }

    @Override public JsonObject position(UUID id) {
        return onServer(() -> {
            ServerPlayerEntity online = players_().getPlayer(id);
            JsonObject out;
            if (online != null) {
                out = place(online, true);
                out.addProperty("t", System.currentTimeMillis());
                BlockPos spawn = online.getSpawnPointPosition();
                out.add("respawn", spawn == null ? null : Json.obj("world", online.getSpawnPointDimension().getValue().toString(), "x", spawn.getX(), "y", spawn.getY(), "z", spawn.getZ()));
                return out;
            }
            JsonObject last = lastPlace.get(id);
            if (last == null) return null;
            out = last.deepCopy();
            out.addProperty("t", left.getOrDefault(id, 0L));
            out.add("respawn", null);
            return out;
        });
    }

    @Override public JsonObject teleport(UUID id, JsonObject target) {
        return onServer(() -> {
            ServerPlayerEntity player = players_().getPlayer(id);
            if (player == null) throw ApiException.conflict("Игрок не в игре.");
            ServerWorld world; double x, y, z;
            if (target.has("toPlayer")) {
                ServerPlayerEntity other = players_().getPlayer(UUID.fromString(target.get("toPlayer").getAsString()));
                if (other == null) throw ApiException.conflict("Второй игрок не в игре.");
                world = other.getServerWorld(); x = other.getX(); y = other.getY(); z = other.getZ();
            } else if ("spawn".equals(Json.string(target, "to"))) {
                world = server.getOverworld(); BlockPos spawn = world.getSpawnPos(); x = spawn.getX() + 0.5; y = spawn.getY(); z = spawn.getZ() + 0.5;
            } else if ("respawn".equals(Json.string(target, "to"))) {
                BlockPos spawn = player.getSpawnPointPosition();
                world = spawn == null ? server.getOverworld() : server.getWorld(player.getSpawnPointDimension());
                if (spawn == null) { spawn = world.getSpawnPos(); }
                x = spawn.getX() + 0.5; y = spawn.getY(); z = spawn.getZ() + 0.5;
            } else {
                String worldId = target.has("world") ? target.get("world").getAsString() : "minecraft:overworld";
                world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId)));
                if (world == null) throw ApiException.badRequest("Нет мира " + worldId);
                if (!target.has("x") || !target.has("y") || !target.has("z")) throw ApiException.badRequest("Нужны x, y, z.");
                x = target.get("x").getAsDouble(); y = target.get("y").getAsDouble(); z = target.get("z").getAsDouble();
            }
            player.teleport(world, x, y, z, player.getYaw(), player.getPitch());
            return Json.ok(player.getGameProfile().getName() + " перемещён");
        });
    }

    @Override public JsonArray mapMarkers(String worldId) {
        return onServer(() -> {
            List<JsonObject> out = new ArrayList<>();
            ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId == null ? "minecraft:overworld" : worldId)));
            if (world != null) { BlockPos spawn = world.getSpawnPos(); out.add(Json.obj("kind", "spawn", "id", "spawn", "name", "Спавн мира", "x", spawn.getX(), "z", spawn.getZ())); }
            for (ConversationStore.Village village : memory().villages())
                if (worldId == null || worldId.equals(village.dimension()))
                    out.add(Json.obj("kind", "village", "id", village.id(), "name", village.name(), "x", village.x(), "z", village.z()));
            out.addAll(deaths.markers(worldId == null ? "minecraft:overworld" : worldId));
            return Json.array(out);
        });
    }

    // ---- Мобы и деревни. ----

    private JsonObject mobJson(UUID mob) {
        return Json.obj("uuid", mob.toString(), "name", memory().givenNameOf(mob), "species", species(memory().typeOf(mob)));
    }
    private static String species(String type) { return type == null ? "" : type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type; }
    private JsonObject villageJson(String villageId) {
        ConversationStore.Village village = memory().village(villageId);
        return village == null ? null : Json.obj("id", village.id(), "name", village.name());
    }
    private Entity entity(UUID mob) {
        for (ServerWorld world : server.getWorlds()) { Entity entity = world.getEntity(mob); if (entity != null) return entity; }
        return null;
    }

    @Override public JsonObject mobs(String village, String query, String cursor, int limit) {
        return onServer(() -> {
            String q = query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT);
            List<JsonObject> items = new ArrayList<>();
            for (UUID id : memory().knownMobs()) {
                String villageId = memory().villageOf(id);
                if (village != null && !village.isBlank() && !village.equals(villageId)) continue;
                JsonObject mob = mobJson(id);
                JsonObject villageObject = villageId.isBlank() ? null : villageJson(villageId);
                String haystack = (Json.string(mob, "name") + " " + Json.string(mob, "species") + " " + (villageObject == null ? "" : Json.string(villageObject, "name"))).toLowerCase(Locale.ROOT);
                if (q != null && !haystack.contains(q)) continue;
                Entity entity = entity(id);
                List<ConversationStore.Happening> recent = memory().happenings(id, 1);
                mob.add("village", villageObject);
                mob.addProperty("trait", memory().traitOf(id));
                mob.addProperty("alive", entity == null || entity.isAlive());
                mob.addProperty("loaded", entity != null);
                mob.addProperty("relations", memory().relations(id).size());
                mob.add("lastEvent", recent.isEmpty() ? null : Json.obj("t", recent.get(0).time(), "text", recent.get(0).text()));
                items.add(mob);
            }
            items.sort((a, b) -> Json.string(a, "name").compareToIgnoreCase(Json.string(b, "name")));
            int start = 0;
            try { if (cursor != null && !cursor.isBlank()) start = Math.max(0, Integer.parseInt(cursor)); } catch (NumberFormatException ignored) { }
            int end = Math.min(items.size(), start + Math.max(1, limit));
            return Json.obj("items", items.subList(Math.min(start, items.size()), end), "next", end < items.size() ? Integer.toString(end) : null);
        });
    }

    @Override public JsonObject mob(UUID id) {
        return onServer(() -> {
            if (!memory().known(id)) return null;
            MobCard card = memory().card(id, null);
            if (card == null) return null;
            MobState.View state = card.state() == null ? MobState.View.NEUTRAL : card.state();
            List<JsonObject> relations = new ArrayList<>();
            memory().relations(id).forEach((player, score) -> {
                MobCard forPlayer = memory().card(id, player);
                relations.add(Json.obj("player", Json.player(player, nameOf(player)), "relationship", score,
                        "label", forPlayer == null ? "" : MobGameBridge.relationshipLabel(forPlayer), "hearts", 0));
            });
            List<JsonObject> facts = new ArrayList<>();
            memory().facts(id).forEach(fact -> facts.add(Json.obj("t", fact.time(), "text", fact.text(), "importance", fact.importance())));
            List<JsonObject> events = new ArrayList<>();
            memory().happenings(id, 50).forEach(event -> events.add(Json.obj("t", event.time(), "text", event.text())));
            UUID spouse = memory().spouseOf(id);
            List<JsonObject> children = new ArrayList<>();
            for (UUID child : memory().childrenOf(id)) children.add(kinJson(child));
            String character = card.customPersonality() == null || card.customPersonality().isBlank() ? card.personality() : card.customPersonality();
            String backstory = memory().backstoryOf(id);
            return Json.obj("mob", mobJson(id), "rivals", rivalsJson(id),
                    "card", Json.obj("name", card.name(), "character", (character == null ? "" : character) + (backstory.isBlank() ? "" : "\n" + backstory), "voice", card.voiceId(), "memory", card.memory()),
                    "state", Json.obj("calm", state.calm(), "trust", state.trust(), "interest", state.interest(), "mood", state.mood(), "word", state.thought()),
                    "relations", relations, "facts", facts, "events", events,
                    "family", Json.obj("spouse", spouse == null ? null : kinJson(spouse), "children", children));
        });
    }
    /** П3: ухажёры жителя, идущее испытание и назначенный выбор у колокола; null — соперничества нет. */
    private JsonObject rivalsJson(UUID id) {
        long now = System.currentTimeMillis();
        List<JsonObject> suitors = new ArrayList<>();
        for (RivalryLedger.Suitor s : memory().rivalry().suitors(id, now))
            suitors.add(Json.obj("player", Json.player(s.player(), s.name()), "hearts", s.hearts(), "score", s.score(), "courtship", switch (s.courtship()) {
                case DATING -> "встречаются"; case ENGAGED -> "помолвлены"; case MARRIED -> "в браке"; default -> "ухаживает";
            }));
        JsonObject trial = null;
        for (RivalryLedger.TrialView t : memory().rivalry().trials(now)) {
            if (!t.villager().equals(id)) continue;
            List<JsonObject> entries = new ArrayList<>();
            for (RivalryLedger.Entry e : t.entries()) entries.add(Json.obj("player", Json.player(e.player(), e.name()), "progress", e.progress()));
            trial = Json.obj("kind", t.kind(), "item", t.item(), "count", t.count(), "endsAt", t.endsAt(), "entries", entries);
        }
        Long scene = null;
        for (RivalryLedger.Scene s : memory().rivalry().scenes(now)) if (s.villager().equals(id)) scene = s.at();
        if (suitors.isEmpty() && trial == null && scene == null) return null;
        return Json.obj("suitors", suitors, "trial", trial, "sceneAt", scene);
    }
    private JsonObject kinJson(UUID id) {
        if (memory().known(id)) return mobJson(id);
        String name = nameOf(id);
        return Json.obj("uuid", id.toString(), "name", name.isBlank() ? "?" : name, "species", name.isBlank() ? "" : "player");
    }

    @Override public JsonObject mobAction(UUID id, String action, JsonObject body) {
        return onServer(() -> {
            if (!memory().known(id)) throw ApiException.notFound("Нет такого моба.");
            switch (action) {
                case "card" -> {
                    JsonObject fields = body == null ? new JsonObject() : body;
                    memory().editCard(id, Json.string(fields, "name"), Json.string(fields, "character"), Json.string(fields, "voice"),
                            fields.has("memory") && !fields.get("memory").isJsonNull() ? fields.get("memory").getAsString() : null);
                    return Json.ok("Карточка обновлена");
                }
                case "reset" -> { memory().resetMob(id); return Json.ok("Состояние и память сброшены"); }
                case "clear-history" -> { memory().clearHistory(id); return Json.ok("История реплик очищена"); }
                case "rivals-reset" -> { return Json.ok(memory().rivalry().reset(id) ? "Соперничество сброшено" : "Соперничества не было"); }
                case "rivals-end-trial" -> { return Json.ok(memory().rivalry().endTrial(id, System.currentTimeMillis()) ? "Испытание закончено" : "Испытания нет"); }
                case "rivals-call" -> {
                    if (!memory().rivalry().callScene(id, System.currentTimeMillis())) throw ApiException.badRequest("Нужны двое ухажёров, и выбор не должен быть уже назначен.");
                    return Json.ok("Ухажёры позваны к колоколу");
                }
                default -> throw ApiException.badRequest("Неизвестное действие: " + action);
            }
        });
    }

    @Override public JsonObject villages() {
        return onServer(() -> {
            long now = System.currentTimeMillis();
            List<JsonObject> out = new ArrayList<>();
            Map<UUID, String> players = knownPlayers();
            for (ConversationStore.Village village : memory().villages()) {
                List<JsonObject> chronicle = new ArrayList<>();
                if (village.story() != null && !village.story().isBlank()) chronicle.add(Json.obj("t", 0, "text", village.story()));
                List<JsonObject> rumors = new ArrayList<>();
                for (VillageRumors.View view : memory().villageRumors(village.id(), now)) {
                    UUID about = view.subject() == null || view.subject().isBlank() ? null : memory().playerByName(view.subject());
                    rumors.add(Json.obj("t", view.time(), "text", view.line(), "about", about == null ? null : Json.player(about, view.subject())));
                }
                List<JsonObject> standings = new ArrayList<>();
                players.forEach((id, name) -> {
                    VillageRumors.Reputation fame = memory().reputation(village.id(), id, now);
                    if (!fame.known()) return;
                    standings.add(Json.obj("player", Json.player(id, name), "points", (int) Math.round(fame.balance()), "rank", fame.describe()));
                });
                out.add(Json.obj("id", village.id(), "name", village.name(), "residents", memory().residents(village.id()),
                        "chronicle", chronicle, "rumors", rumors, "standings", standings));
            }
            return Json.obj("villages", out);
        });
    }

    @Override public JsonObject forgive(String villageId, UUID player) {
        return onServer(() -> {
            ConversationStore.Village village = memory().village(villageId);
            if (village == null) throw ApiException.notFound("Нет такой деревни.");
            return Json.ok(memory().forgiveVillage(village, player));
        });
    }

    // ---- Бог: своих данных у Mob Talk нет; души и активное отдаёт источник divineblock (операции souls и active). ----

    /** Источники других модов: ставит {@link AdminLink}, когда админка поднялась. */
    private volatile io.github.mobtalk.server.web.ConfigSources sources;
    void sources(io.github.mobtalk.server.web.ConfigSources sources) { this.sources = sources; }

    @Override public JsonObject divineSouls() { return divine("souls"); }
    @Override public JsonObject divineActive() { return divine("active"); }
    @Override public JsonObject divinePrayers(String player, long since, int limit) {
        return divine(Json.obj("op", "prayers", "player", player == null ? "" : player, "since", since, "limit", limit));
    }
    private JsonObject divine(String op) { return divine(Json.obj("op", op)); }
    private JsonObject divine(JsonObject request) {
        io.github.mobtalk.server.web.ConfigSources known = sources;
        if (known == null || !known.has("divineblock")) throw ApiException.unavailable("Божественного блока нет в этой сборке.");
        JsonObject answer = known.call("divineblock", request);
        if (answer.has("ok") && !answer.get("ok").getAsBoolean())
            throw ApiException.unavailable("Божественный блок не отдал данные: " + Json.string(answer, "error") + ". Обновите мод до версии с вкладкой «Бог».");
        return answer;
    }

    // ---- Сейчас. ----

    @Override public JsonArray activities() {
        return onServer(() -> {
            JsonArray out = new JsonArray();
            for (ServerPlayerEntity player : players_().getPlayerList())
                out.add(Json.obj("player", Json.player(player.getUuid(), player.getGameProfile().getName()), "activity", activity(player.getUuid())));
            return out;
        });
    }

    @Override public boolean cancelDialogue(String id) { return AdminLink.cancelDialogue(id); }

    /** Сколько мобов с профилем Mob Talk сейчас загружено (для метрик). Поток сервера. */
    int profiledLoaded() {
        int n = 0;
        for (ServerWorld world : server.getWorlds()) for (Entity entity : world.iterateEntities()) if (entity instanceof LivingEntity && memory().known(entity.getUuid())) n++;
        return n;
    }
}
