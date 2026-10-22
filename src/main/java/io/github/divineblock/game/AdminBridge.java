package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.AdminProtocol;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.DivineSettings;
import io.github.divineblock.core.GodJournal;
import io.github.divineblock.core.GodLedger;
import io.github.divineblock.core.Righteousness;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The god's side of the web admin: Mob Talk finds this class by its entrypoint {@code mobtalk-admin} (fabric.mod.json) and
 * talks to it in JSON ({@link AdminProtocol}); nothing of Mob Talk is needed here. It also keeps the players' F9 in step with
 * the server's settings: they arrive with {@link SettingsPayload}, and an operator's changes come back as
 * {@link SettingsPatchPayload}. Actions run on the server thread; the admin calls from its own threads. */
public final class AdminBridge implements UnaryOperator<String> {
    private static volatile MinecraftServer server;
    private static final AdminProtocol PROTOCOL = new AdminProtocol(new GameHost());

    /** Fabric creates the entrypoint with this constructor. */
    public AdminBridge() { }

    @Override public String apply(String request) { return PROTOCOL.handle(request); }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> { if (server == stopped) server = null; });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, joined) -> joined.execute(() -> send(handler.player)));
        ServerPlayNetworking.registerGlobalReceiver(SettingsPatchPayload.ID, (payload, context) -> patchFromPlayer(context.player(), payload.json()));
    }

    /** Who may change the server's settings from F9: an operator for now (Mob Talk's admin level later). */
    static boolean mayEdit(ServerPlayerEntity player) { return player.hasPermissionLevel(2); }

    static void send(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, SettingsPayload.ID)) return;
        ServerPlayNetworking.send(player, new SettingsPayload(DivineSettings.valuesJson(DivineBlockMod.config()), mayEdit(player)));
    }
    private static void broadcast(MinecraftServer target) {
        for (ServerPlayerEntity player : target.getPlayerManager().getPlayerList()) send(player);
    }

    private static void patchFromPlayer(ServerPlayerEntity player, String json) {
        if (!mayEdit(player)) { player.sendMessage(Text.literal("Настройки бога на сервере меняет админ").formatted(Formatting.RED), false); send(player); return; }
        com.google.gson.JsonObject request = new com.google.gson.JsonObject();
        request.addProperty("op", "patch");
        try { request.add("values", com.google.gson.JsonParser.parseString(json).getAsJsonObject()); }
        catch (RuntimeException broken) { return; }
        // Saving writes the file: off the game thread, as the web admin does.
        String name = player.getName().getString();
        MinecraftServer target = player.getServer();
        Thread.ofVirtual().name("divineblock-settings").start(() -> {
            com.google.gson.JsonObject answer = com.google.gson.JsonParser.parseString(PROTOCOL.handle(request.toString())).getAsJsonObject();
            DivineBlockMod.LOG.info("Божественный блок: {} меняет настройки сервера: {}", name, answer);
            if (target != null && answer.has("errors") && !answer.getAsJsonObject("errors").isEmpty())
                target.execute(() -> {
                    ServerPlayerEntity online = target.getPlayerManager().getPlayer(player.getUuid());
                    if (online != null) online.sendMessage(Text.literal("Не применено: " + answer.getAsJsonObject("errors")).formatted(Formatting.RED), false);
                });
        });
    }

    /** The running game as the protocol sees it. */
    private static final class GameHost implements AdminProtocol.Host {
        @Override public DivineConfig config() { return DivineBlockMod.config(); }

        @Override public void save(DivineConfig config) throws IOException {
            config.save(DivineBlockMod.configFile());
            DivineBlockMod.reloadConfig();
            MinecraftServer running = server;
            if (running != null) running.execute(() -> broadcast(running));
        }

        @Override public GodJournal journal() { return GodJournal.shared(); }

        @Override public List<AdminProtocol.Action> actions() {
            return List.of(
                    new AdminProtocol.Action("unban", "Снять вечное изгнание", args("player", "Ник (пусто — снять со всех)")),
                    new AdminProtocol.Action("lift", "Снять проклятия и лестницу кар", args("player", "Ник")),
                    new AdminProtocol.Action("clear", "Убрать всех священных боссов", Map.of()),
                    new AdminProtocol.Action("streamer", "Назначить стримера", args("player", "Ник (пусто — снять)")));
        }

        @Override public String run(String id, Map<String, String> args) {
            MinecraftServer running = server;
            if (running == null) throw new IllegalStateException("сервер не запущен");
            String player = args.getOrDefault("player", "").strip();
            return onServer(running, () -> switch (id) {
                case "unban" -> {
                    UUID target = null;
                    if (!player.isEmpty()) {
                        for (var entry : DivineState.of(running).banned.entrySet())
                            if (entry.getValue().name.equalsIgnoreCase(player)) target = UUID.fromString(entry.getKey());
                        if (target == null) throw new IllegalArgumentException("изгнанного " + player + " нет");
                    }
                    int lifted = Banishment.unban(running, target, "веб-админка");
                    yield lifted == 0 ? "изгнанных нет" : "изгнание снято: " + lifted;
                }
                case "lift" -> {
                    var profile = Multiplayer.profile(running, player).orElseThrow(() -> new IllegalArgumentException("игрок " + player + " не найден"));
                    int lifted = Curses.lift(running, profile.getId());
                    DivineState.of(running).ledger.soul(profile.getId()).forgiven();
                    DivineState.of(running).markDirty();
                    Multiplayer.journal(GodJournal.Kind.VERDICT, profile.getName(), "админ снял проклятия: " + lifted);
                    yield "снято проклятий: " + lifted + ", лестница кар сброшена";
                }
                case "clear" -> {
                    List<Entity> bosses = new java.util.ArrayList<>();
                    for (ServerWorld world : running.getWorlds())
                        for (Entity entity : world.iterateEntities())
                            if (entity instanceof io.github.divineblock.boss.SacredBoss boss && boss.isAlive()) bosses.add(boss);
                    bosses.forEach(Entity::discard);
                    yield "убрано боссов: " + bosses.size();
                }
                case "streamer" -> {
                    if (player.isEmpty()) { Multiplayer.streamer(running, null, null); yield "стример снят"; }
                    var profile = Multiplayer.profile(running, player).orElseThrow(() -> new IllegalArgumentException("игрок " + player + " не найден: он должен хоть раз зайти"));
                    Multiplayer.streamer(running, profile.getId(), profile.getName());
                    yield "стример: " + profile.getName();
                }
                default -> throw new IllegalArgumentException("нет такого действия: " + id);
            });
        }

        @Override public com.google.gson.JsonObject souls() {
            MinecraftServer running = server;
            if (running == null) throw new IllegalStateException("сервер не запущен");
            return onServer(running, () -> {
                DivineState state = DivineState.of(running);
                long tick = running.getOverworld().getTime(), now = System.currentTimeMillis();
                java.util.Set<String> ids = new java.util.LinkedHashSet<>(state.ledger.souls.keySet());
                ids.addAll(state.banned.keySet());
                for (Curses.Curse curse : state.curses.active) ids.add(curse.player);
                com.google.gson.JsonArray souls = new com.google.gson.JsonArray();
                for (String id : ids) {
                    GodLedger.Soul soul = state.ledger.souls.get(id);
                    com.google.gson.JsonObject one = new com.google.gson.JsonObject();
                    one.add("player", player(running, state, id));
                    int sins = 0;
                    if (soul != null) for (Righteousness.Memory memory : soul.righteousness.memories) if (memory.deed != null && memory.deed.weight < 0) sins += Math.max(1, memory.count);
                    one.addProperty("sins", sins);
                    one.addProperty("ladder", soul == null ? 0 : soul.wrathStreak(tick / 24_000));
                    one.addProperty("banned", state.banned.containsKey(id));
                    com.google.gson.JsonArray curses = new com.google.gson.JsonArray();
                    for (Curses.Curse curse : state.curses.active) if (id.equals(curse.player)) curses.add(curse(curse, tick, now));
                    one.add("curses", curses);
                    souls.add(one);
                }
                com.google.gson.JsonObject out = new com.google.gson.JsonObject();
                out.add("souls", souls);
                return out;
            });
        }

        @Override public com.google.gson.JsonObject active() {
            MinecraftServer running = server;
            if (running == null) throw new IllegalStateException("сервер не запущен");
            return onServer(running, () -> {
                DivineState state = DivineState.of(running);
                long tick = running.getOverworld().getTime(), now = System.currentTimeMillis();
                com.google.gson.JsonArray bosses = new com.google.gson.JsonArray();
                for (ServerWorld world : running.getWorlds())
                    for (Entity entity : world.iterateEntities())
                        if (entity instanceof io.github.divineblock.boss.SacredBoss boss && boss.isAlive()) {
                            com.google.gson.JsonObject one = new com.google.gson.JsonObject();
                            one.addProperty("id", boss.kind().id); one.addProperty("title", boss.kind().title);
                            one.addProperty("health", Math.round(boss.getHealth())); one.addProperty("maxHealth", Math.round(boss.getMaxHealth()));
                            one.addProperty("since", now - boss.age * 50L);
                            bosses.add(one);
                        }
                com.google.gson.JsonArray curses = new com.google.gson.JsonArray();
                for (Curses.Curse curse : state.curses.active) {
                    com.google.gson.JsonObject one = curse(curse, tick, now);
                    one.add("player", player(running, state, curse.player));
                    curses.add(one);
                }
                com.google.gson.JsonObject out = new com.google.gson.JsonObject();
                out.add("bosses", bosses); out.add("curses", curses);
                return out;
            });
        }

        /** A curse for the admin: its name and when it ends, in epoch ms (the world's ticks are 50 ms). */
        private static com.google.gson.JsonObject curse(Curses.Curse curse, long tick, long now) {
            com.google.gson.JsonObject one = new com.google.gson.JsonObject();
            one.addProperty("title", curse.type == null ? "" : curse.type.label);
            one.addProperty("until", now + Math.max(0, curse.until - tick) * 50L);
            return one;
        }
        /** {uuid, name}: the name from the ban book, the online list or the server's user cache. */
        private static com.google.gson.JsonObject player(MinecraftServer running, DivineState state, String id) {
            String name = "";
            Banishment.Ban ban = state.banned.get(id);
            if (ban != null) name = ban.name;
            try {
                UUID uuid = UUID.fromString(id);
                ServerPlayerEntity online = running.getPlayerManager().getPlayer(uuid);
                if (online != null) name = online.getName().getString();
                else if (name.isBlank() && running.getUserCache() != null) name = running.getUserCache().getByUuid(uuid).map(com.mojang.authlib.GameProfile::getName).orElse("");
            } catch (IllegalArgumentException notUuid) { }
            com.google.gson.JsonObject player = new com.google.gson.JsonObject();
            player.addProperty("uuid", id); player.addProperty("name", name.isBlank() ? id : name);
            return player;
        }

        private static Map<String, String> args(String key, String title) { Map<String, String> out = new LinkedHashMap<>(); out.put(key, title); return out; }

        /** Runs on the server thread and waits a little for the answer (directly when already there). */
        private static <T> T onServer(MinecraftServer running, Supplier<T> work) {
            if (running.isOnThread()) return work.get();
            try { return running.submit(work).get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException failure) {
                throw failure.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(failure.getCause());
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("прервано"); }
            catch (java.util.concurrent.TimeoutException slow) { throw new IllegalStateException("сервер не ответил за 5 с"); }
        }
    }
}
