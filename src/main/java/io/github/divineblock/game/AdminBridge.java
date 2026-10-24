package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.AdminProtocol;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.DivineSettings;
import io.github.divineblock.core.GodJournal;
import io.github.divineblock.core.GodLedger;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.Penance;
import io.github.divineblock.core.PrayerLog;
import io.github.divineblock.core.PrayerTone;
import io.github.divineblock.core.Verdict;
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
import net.minecraft.util.math.BlockPos;
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

    /** A blessing that lasts, for the admin's «Бог» tab: whose, what and until when (epoch ms). Server thread only. */
    private record Blessing(UUID player, String title, long until) { }
    private static final List<Blessing> BLESSINGS = new java.util.ArrayList<>();

    /** A mercy fell on {@code player}: the lasting ones (strength and speed, the great blessing) are kept until they end. */
    static void blessed(ServerPlayerEntity player, Miracle miracle, int power) {
        long ticks = switch (miracle) {
            case BUFF -> 20L * 60 * (power <= 1 ? 3 : power == 2 ? 5 : 8);
            case BLESSING -> 24_000;
            default -> 0;
        };
        if (ticks <= 0) return;
        BLESSINGS.removeIf(old -> old.player().equals(player.getUuid()) && old.title().equals(miracle.title()));
        BLESSINGS.add(new Blessing(player.getUuid(), miracle.title(), System.currentTimeMillis() + ticks * 50));
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
                    new AdminProtocol.Action("boss_remove", "Убрать босса", args("uuid", "UUID босса")),
                    new AdminProtocol.Action("boss_clear", "Убрать всех священных боссов", Map.of()),
                    new AdminProtocol.Action("god_recall", "Отозвать Бога с булавой", Map.of()),
                    new AdminProtocol.Action("court_end", "Закончить Небесный суд", Map.of()),
                    new AdminProtocol.Action("streamer", "Назначить стримера", args("player", "Ник (пусто — снять)")),
                    new AdminProtocol.Action("mercy", "Изменить милость", args("player", "Ник", "amount", "На сколько (+10, -15) или =значение (=40)")),
                    new AdminProtocol.Action("forget", "Стереть память бога об игроке", args("player", "Ник")),
                    new AdminProtocol.Action("forget_all", "Стереть память бога обо всех", Map.of()),
                    new AdminProtocol.Action("act", "Послать деяние", args("player", "Ник (в игре)", "deed", "Деяние (id)", "power", "Сила 1–4")));
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
                case "boss_remove" -> {
                    UUID uuid;
                    try { uuid = UUID.fromString(args.getOrDefault("uuid", "").strip()); }
                    catch (IllegalArgumentException broken) { throw new IllegalArgumentException("нужен UUID босса"); }
                    for (ServerWorld world : running.getWorlds())
                        if (world.getEntity(uuid) instanceof io.github.divineblock.boss.SacredBoss boss && boss.isAlive()) {
                            String who = target(boss);
                            boss.discard();
                            Multiplayer.journal(GodJournal.Kind.VERDICT, who, "админ убрал босса «" + boss.kind().title + "»");
                            yield "убран: " + boss.kind().title;
                        }
                    throw new IllegalArgumentException("такого босса нет: он уже пал или ушёл");
                }
                case "boss_clear", "clear" -> {
                    List<io.github.divineblock.boss.SacredBoss> bosses = bosses(running);
                    bosses.forEach(Entity::discard);
                    Multiplayer.journal(GodJournal.Kind.VERDICT, "", "админ убрал всех священных боссов: " + bosses.size());
                    yield "убрано боссов: " + bosses.size();
                }
                case "god_recall" -> {
                    List<String> names = new java.util.ArrayList<>();
                    for (ServerPlayerEntity over : io.github.divineblock.boss.DivineBosses.godAvatarPlayers())
                        if (io.github.divineblock.boss.DivineBosses.dismissGodAvatar(over)) names.add(over.getName().getString());
                    if (names.isEmpty()) yield "Бога с булавой нет ни над кем";
                    for (String name : names) Multiplayer.journal(GodJournal.Kind.VERDICT, name, "админ отозвал Бога с булавой");
                    yield "Бог с булавой отозван: " + String.join(", ", names);
                }
                case "court_end" -> {
                    List<String> names = new java.util.ArrayList<>();
                    for (UUID judged : SkyCourt.judged()) names.add(player(running, DivineState.of(running), judged.toString()).get("name").getAsString());
                    int ended = SkyCourt.endAll(running);
                    if (ended == 0) yield "Небесного суда сейчас нет";
                    for (String name : names) Multiplayer.journal(GodJournal.Kind.VERDICT, name, "админ закончил Небесный суд");
                    yield "Небесный суд окончен: " + String.join(", ", names);
                }
                case "streamer" -> {
                    if (player.isEmpty()) { Multiplayer.streamer(running, null, null); yield "стример снят"; }
                    var profile = Multiplayer.profile(running, player).orElseThrow(() -> new IllegalArgumentException("игрок " + player + " не найден: он должен хоть раз зайти"));
                    Multiplayer.streamer(running, profile.getId(), profile.getName());
                    yield "стример: " + profile.getName();
                }
                case "mercy" -> {
                    var profile = Multiplayer.profile(running, player).orElseThrow(() -> new IllegalArgumentException("игрок " + player + " не найден"));
                    String amount = args.getOrDefault("amount", "").strip().replace(',', '.');
                    boolean set = amount.startsWith("=");
                    double value;
                    try { value = Double.parseDouble(set ? amount.substring(1).strip() : amount); }
                    catch (NumberFormatException broken) { throw new IllegalArgumentException("милость: нужно число, например +10, -15 или =40"); }
                    DivineState state = DivineState.of(running);
                    Righteousness righteousness = state.ledger.soul(profile.getId()).righteousness;
                    righteousness.drift(running.getOverworld().getTime() / 24_000);
                    double before = righteousness.score;
                    double after = righteousness.adjust(set ? value - before : value);
                    state.markDirty();
                    String done = String.format(java.util.Locale.ROOT, "милость %s: %.0f → %.0f (%s)", profile.getName(), before, after, righteousness.label());
                    Multiplayer.journal(GodJournal.Kind.VERDICT, profile.getName(), "админ изменил " + done);
                    yield done;
                }
                case "forget" -> {
                    var profile = Multiplayer.profile(running, player).orElseThrow(() -> new IllegalArgumentException("игрок " + player + " не найден"));
                    DivineState state = DivineState.of(running);
                    boolean known = state.ledger.forget(profile.getId().toString());
                    Prayers.PENANCE.end(profile.getId());
                    state.markDirty();
                    Multiplayer.journal(GodJournal.Kind.VERDICT, profile.getName(), "админ стёр память бога об игроке");
                    yield known ? "бог забыл " + profile.getName() + ": милость, поступки, молитвы и кары подряд с нуля" : "бог и так ничего не помнит о " + profile.getName();
                }
                case "forget_all" -> {
                    DivineState state = DivineState.of(running);
                    List<String> ids = new java.util.ArrayList<>(state.ledger.souls.keySet());
                    for (String soul : ids) state.ledger.forget(soul);
                    Prayers.PENANCE.clear();
                    state.markDirty();
                    Multiplayer.journal(GodJournal.Kind.VERDICT, "", "админ стёр память бога обо всех: " + ids.size());
                    yield "бог забыл всех: " + ids.size();
                }
                case "act" -> {
                    ServerPlayerEntity target = running.getPlayerManager().getPlayer(player);
                    if (target == null) throw new IllegalArgumentException("игрока " + player + " нет в игре: деяние падает только на того, кто на сервере");
                    Miracle miracle = Miracle.byId(args.getOrDefault("deed", "").strip().toLowerCase(java.util.Locale.ROOT));
                    if (miracle == null) throw new IllegalArgumentException("нет такого деяния: " + args.get("deed"));
                    int asked;
                    try { asked = Integer.parseInt(args.getOrDefault("power", "").strip()); } catch (NumberFormatException none) { asked = miracle.maxTier; }
                    yield actOn(target, miracle, Math.max(miracle.minTier, Math.min(miracle.maxTier, asked)));
                }
                default -> throw new IllegalArgumentException("нет такого действия: " + id);
            });
        }

        /** The admin's act on a mortal: performed where he stands (by the nearest altar), counted as his running punishment when it
         * is wrath, and written to the prayer log as the admin's verdict so the «Бог» tab and the history show it. */
        private static String actOn(ServerPlayerEntity target, Miracle miracle, int power) {
            BlockPos altar = target.getBlockPos();
            for (BlockPos pos : BlockPos.iterateOutwards(target.getBlockPos(), 12, 6, 12))
                if (target.getServerWorld().getBlockState(pos).getBlock() instanceof AltarBlock) { altar = pos.toImmutable(); break; }
            long tick = target.getServer().getOverworld().getTime();
            if (miracle.kind == Miracle.Kind.WRATH) Prayers.PENANCE.begin(target.getUuid(), miracle.id(), tick);
            String done = Zones.perform(miracle, power, target, altar, "", DivineBlockMod.config(), Math.max(1, power));
            if (done == null) {
                if (miracle.kind == Miracle.Kind.WRATH) Prayers.PENANCE.end(target.getUuid());
                throw new IllegalArgumentException("«" + miracle.title() + "» здесь не выходит (место, защита церкви или настройки): выберите другое");
            }
            if (miracle.kind == Miracle.Kind.WRATH) Prayers.PENANCE.fell(target.getUuid(), miracle.id(), tick);
            if (miracle.kind == Miracle.Kind.HELP) blessed(target, miracle, power);
            Verdict verdict = new Verdict();
            verdict.prayer = "(деяние послал админ)"; verdict.miracle = miracle.id(); verdict.power = power; verdict.inclination = miracle.kind;
            verdict.decidedBy = "admin"; verdict.decisionNote = "веб-админка"; verdict.speech = done;
            PrayerLog.shared().add(PrayerLog.of(System.currentTimeMillis(), target.getUuidAsString(), target.getName().getString(), verdict, PrayerTone.of(""), false, "", 0));
            Multiplayer.journal(GodJournal.Kind.VERDICT, target.getName().getString(), "админ послал «" + miracle.title() + "» ×" + power + ": " + done);
            return miracle.title() + " ×" + power + " → " + target.getName().getString() + ": " + done;
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
                    Righteousness righteousness = soul == null ? new Righteousness() : soul.righteousness;
                    one.addProperty("mercy", Math.round(righteousness.score * 10) / 10.0);
                    one.addProperty("label", righteousness.label());
                    com.google.gson.JsonArray deeds = new com.google.gson.JsonArray();
                    long today = tick / 24_000;
                    for (Righteousness.Memory memory : righteousness.notable(today, 7, 5)) {
                        com.google.gson.JsonObject deed = new com.google.gson.JsonObject();
                        deed.addProperty("text", memory.describe(today));
                        // The start of that game day, counted back in world ticks (50 ms) from now.
                        deed.addProperty("t", now - Math.max(0, tick - memory.day * 24_000) * 50L);
                        deed.addProperty("day", memory.day);
                        deeds.add(deed);
                    }
                    one.add("deeds", deeds);
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
                for (io.github.divineblock.boss.SacredBoss boss : bosses(running)) {
                    com.google.gson.JsonObject one = new com.google.gson.JsonObject();
                    one.addProperty("uuid", boss.getUuidAsString());
                    one.addProperty("id", boss.kind().id); one.addProperty("title", boss.kind().title);
                    one.addProperty("health", Math.round(boss.getHealth())); one.addProperty("maxHealth", Math.round(boss.getMaxHealth()));
                    one.addProperty("since", now - boss.age * 50L);
                    one.addProperty("phase", boss.phase());
                    if (boss.getTarget() instanceof net.minecraft.entity.player.PlayerEntity target) one.add("target", player(running, state, target.getUuidAsString()));
                    else one.add("target", com.google.gson.JsonNull.INSTANCE);
                    one.addProperty("dimension", boss.getWorld().getRegistryKey().getValue().toString());
                    one.addProperty("x", boss.getBlockX()); one.addProperty("y", boss.getBlockY()); one.addProperty("z", boss.getBlockZ());
                    bosses.add(one);
                }
                com.google.gson.JsonArray curses = new com.google.gson.JsonArray();
                for (Curses.Curse curse : state.curses.active) {
                    com.google.gson.JsonObject one = curse(curse, tick, now);
                    one.add("player", player(running, state, curse.player));
                    curses.add(one);
                }
                List<UUID> avatar = io.github.divineblock.boss.DivineBosses.godAvatarPlayers().stream().map(Entity::getUuid).toList();
                // Blessings that ended, or that milk or death took away (every lasting one gives luck), are forgotten.
                BLESSINGS.removeIf(blessing -> {
                    ServerPlayerEntity online = running.getPlayerManager().getPlayer(blessing.player());
                    return blessing.until() <= now || online != null && !online.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.LUCK);
                });
                com.google.gson.JsonArray blessings = new com.google.gson.JsonArray();
                for (Blessing blessing : BLESSINGS) {
                    com.google.gson.JsonObject one = new com.google.gson.JsonObject();
                    one.add("player", player(running, state, blessing.player().toString()));
                    one.addProperty("title", blessing.title()); one.addProperty("until", blessing.until());
                    blessings.add(one);
                }
                com.google.gson.JsonObject out = new com.google.gson.JsonObject();
                out.add("bosses", bosses); out.add("curses", curses);
                out.add("godAvatar", presence(running, state, avatar));
                out.add("skyCourt", presence(running, state, SkyCourt.judged()));
                out.add("blessings", blessings);
                com.google.gson.JsonArray punishments = new com.google.gson.JsonArray();
                for (var entry : Prayers.PENANCE.all(tick).entrySet()) {
                    Penance.Running one = entry.getValue();
                    Miracle miracle = Miracle.byId(one.miracle);
                    com.google.gson.JsonObject p = new com.google.gson.JsonObject();
                    p.add("player", player(running, state, entry.getKey().toString()));
                    p.addProperty("deed", one.miracle); p.addProperty("title", miracle == null ? "бог решает" : miracle.title());
                    p.addProperty("since", now - Math.max(0, tick - one.since) * 50L); p.addProperty("until", now + Math.max(0, one.until - tick) * 50L);
                    p.addProperty("strengthened", one.strengthened); p.addProperty("moved", one.moved);
                    punishments.add(p);
                }
                out.add("punishments", punishments);
                return out;
            });
        }

        // ---- Confession (Mob Talk's priests): the sins to confess and the absolution; the scene itself is Mob Talk's. ----

        @Override public com.google.gson.JsonObject soulDeeds(String player) {
            MinecraftServer running = server;
            if (running == null) throw new IllegalStateException("сервер не запущен");
            UUID uuid = uuid(player);
            return onServer(running, () -> {
                DivineState state = DivineState.of(running);
                long tick = running.getOverworld().getTime();
                return AdminProtocol.deedsJson(player(running, state, uuid.toString()), state.ledger.soul(uuid).righteousness, tick / 24_000,
                        punished(running, uuid, tick), DivineBlockMod.config());
            });
        }

        @Override public com.google.gson.JsonObject absolve(com.google.gson.JsonObject request) {
            MinecraftServer running = server;
            if (running == null) throw new IllegalStateException("сервер не запущен");
            UUID uuid = uuid(AdminProtocol.text(request, "player"));
            java.util.Set<Long> named = AdminProtocol.ids(request, "named");
            int sincerity = request.has("sincerity") ? Math.max(0, Math.min(2, request.get("sincerity").getAsInt())) : 1;
            boolean temple = "temple".equals(AdminProtocol.text(request, "place"));
            String priest = AdminProtocol.text(request, "priest");
            // Кара, что шла, когда исповедь началась (батюшка уже назвал по ней епитимью), считается и после её конца.
            boolean wasPunished = request.has("punished") && request.get("punished").getAsBoolean();
            return onServer(running, () -> {
                DivineConfig config = DivineBlockMod.config();
                DivineState state = DivineState.of(running);
                long tick = running.getOverworld().getTime();
                Righteousness righteousness = state.ledger.soul(uuid).righteousness;
                io.github.divineblock.core.Absolution.Result result = io.github.divineblock.core.Absolution.absolve(righteousness, tick / 24_000, named, sincerity,
                        temple, wasPunished || punished(running, uuid, tick), config);
                int curses = config.confessLiftsCurse ? Curses.liftSettled(running, uuid, righteousness) : 0;
                state.markDirty();
                String name = player(running, state, uuid.toString()).get("name").getAsString();
                Multiplayer.journal(GodJournal.Kind.CONFESSION, name, AdminProtocol.confessionLine(priest.isBlank() ? "священника" : priest, temple, result, named.size(), sincerity, curses));
                return AdminProtocol.resultJson(result, curses);
            });
        }

        @Override public com.google.gson.JsonObject absolveRest(com.google.gson.JsonObject request) {
            MinecraftServer running = server;
            if (running == null) throw new IllegalStateException("сервер не запущен");
            UUID uuid = uuid(AdminProtocol.text(request, "player"));
            java.util.Set<Long> ids = AdminProtocol.ids(request, "deeds");
            String priest = AdminProtocol.text(request, "priest");
            return onServer(running, () -> {
                DivineConfig config = DivineBlockMod.config();
                DivineState state = DivineState.of(running);
                long tick = running.getOverworld().getTime();
                Righteousness righteousness = state.ledger.soul(uuid).righteousness;
                io.github.divineblock.core.Absolution.Result result = io.github.divineblock.core.Absolution.absolveRest(righteousness, tick / 24_000, ids);
                int curses = config.confessLiftsCurse ? Curses.liftSettled(running, uuid, righteousness) : 0;
                state.markDirty();
                String name = player(running, state, uuid.toString()).get("name").getAsString();
                Multiplayer.journal(GodJournal.Kind.CONFESSION, name, AdminProtocol.restLine(priest.isBlank() ? "священника" : priest, result, curses));
                return AdminProtocol.resultJson(result, curses);
            });
        }

        @Override public com.google.gson.JsonObject absolveRelease(com.google.gson.JsonObject request) {
            MinecraftServer running = server;
            if (running == null) throw new IllegalStateException("сервер не запущен");
            UUID uuid = uuid(AdminProtocol.text(request, "player"));
            java.util.Set<Long> ids = AdminProtocol.ids(request, "deeds");
            return onServer(running, () -> {
                DivineState state = DivineState.of(running);
                int released = io.github.divineblock.core.Absolution.release(state.ledger.soul(uuid).righteousness, ids);
                state.markDirty();
                if (released > 0) Multiplayer.journal(GodJournal.Kind.CONFESSION, player(running, state, uuid.toString()).get("name").getAsString(),
                        "срок епитимьи вышел: грехов снова на душе " + released);
                com.google.gson.JsonObject out = new com.google.gson.JsonObject();
                out.addProperty("ok", true); out.addProperty("released", released);
                return out;
            });
        }

        /** A punishment still runs on him: the god's act under way, or a curse that stays. */
        private static boolean punished(MinecraftServer running, UUID player, long tick) {
            return Prayers.PENANCE.running(player, tick, !Curses.of(running, player).isEmpty());
        }
        private static UUID uuid(String text) {
            try { return UUID.fromString(text == null ? "" : text.strip()); }
            catch (IllegalArgumentException broken) { throw new IllegalArgumentException("нужен UUID игрока"); }
        }

        /** {active, player}: whether it goes on, and over whom (the first, when there are several). */
        private static com.google.gson.JsonObject presence(MinecraftServer running, DivineState state, List<UUID> players) {
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            out.addProperty("active", !players.isEmpty());
            out.add("player", players.isEmpty() ? com.google.gson.JsonNull.INSTANCE : player(running, state, players.get(0).toString()));
            return out;
        }

        /** Every sacred boss alive in every world. */
        private static List<io.github.divineblock.boss.SacredBoss> bosses(MinecraftServer running) {
            List<io.github.divineblock.boss.SacredBoss> out = new java.util.ArrayList<>();
            for (ServerWorld world : running.getWorlds())
                for (Entity entity : world.iterateEntities())
                    if (entity instanceof io.github.divineblock.boss.SacredBoss boss && boss.isAlive()) out.add(boss);
            return out;
        }
        /** The name of the player a boss is after, or "". */
        private static String target(io.github.divineblock.boss.SacredBoss boss) {
            return boss.getTarget() instanceof net.minecraft.entity.player.PlayerEntity target ? target.getName().getString() : "";
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

        private static Map<String, String> args(String... keysAndTitles) {
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keysAndTitles.length; i += 2) out.put(keysAndTitles[i], keysAndTitles[i + 1]);
            return out;
        }

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
