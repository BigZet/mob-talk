package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Мост к серверу Minecraft (встроенному в одиночке или выделенному): настоящая реализация {@link GameBridge}. Ни диска, ни сети. */
public final class MobGameBridge implements GameBridge {
    private static volatile MobGameBridge active;
    private static boolean registered;
    public final MinecraftServer server;
    public final ConversationStore memory;
    public volatile MobTalkConfig config;
    final MobActionController actions;
    /** Who saw the player last and when: a mob that loses sight for a moment (a tree, a corner) keeps its attention (Grace). */
    private final io.github.mobtalk.core.Grace<UUID> sight = new io.github.mobtalk.core.Grace<>();
    /** The mob sees the player now, or saw them within voiceGraceSeconds. Server thread. */
    public boolean sees(MobEntity mob, net.minecraft.entity.LivingEntity player) {
        return sight.hold(mob.getUuid(), mob.canSee(player), MobClock.now(), Math.max(0, config.voiceGraceSeconds) * 1000L);
    }
    final VillagerBargaining bargaining;
    final MobTrading trading;
    /** Spoken promises of villagers as one-use rows in their vanilla window. */
    final TradeBridge trades;
    /** Misdeeds without a blow, seen by the village. */
    final MischiefWatch mischief;
    /** What the player built or dug lately, for the place analysis ("he walled me in"). */
    final BlockChangeLog changes = new BlockChangeLog();
    /** Bonds between mobs: their sources in the game and the walks they cause. */
    final MobSocialController social;
    /** Occasions for a creature to speak first, found on the server and voiced by the client. */
    public final InitiativeController initiative;
    /** Villagers talking among themselves and writing to the chat, found on the server and voiced by the client. */
    public final VillageTalkController talk;
    /** The villagers' social layer in the world: gifts changing hands, slaps, families, the village's stance. */
    final SocialController society;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mobtalk-bridge");
    /** Villagers' families: weddings, births, growth, mourning. */
    final FamilyController families;
    /** The village's stance towards the player: no trade for an exile, a watchful golem, warnings. */
    final VillageController villages;
    /** Village defence: the alarm, no monsters spawning in a guarded village, a sturdier golem. */
    final VillageDefenseController defense;
    /** Night bandits in the cities: the night's draw, the gangs' minds. */
    final BanditController bandits;
    /** Track «чиновники»: officials' desks, envelopes and the inspector's orders. */
    final OfficialsController officials;
    /** Officials, stage 2: the passport office (cases, the queue at the clerk's window). */
    final PassportOffice passports;
    final CivilOffice civil;
    /** Officials, stages 3-4: the head's dacha, heaven's punishments, «Охрана!», the black ledger and the revizor. */
    final GraftController graft;
    /** Б1: the god's deeds reaching the villages. */
    final DivineController divine;
    /** Трек J: ревность пар игрока и сон вместе. */
    final JealousyController jealousy;
    /** The world around known villagers turned into moods: weather, night, sleep, hunger, alarms, friends near. */
    final MoodWatch moods;
    /** П3: соперники за одно сердце — уведомления, испытания, выбор у колокола. */
    public final RivalryController rivalry;
    final CoSleepController coSleep;
    /** Трек L: свадьба у колокола. */
    public final WeddingController wedding;
    /** Трек N: особая ночь, беременность, роды. */
    public final ChildController children;
    /** The village bar's evenings: guests on the stools, the bartender, the jukebox, the talk over rumours. */
    final BarEvenings bars;
    private final Map<UUID, Deque<Heard>> heard = new HashMap<>();

    private MobGameBridge(MinecraftServer server, ConversationStore memory, MobTalkConfig config) {
        this.server = server; this.memory = memory; this.config = config.copy();
        social = new MobSocialController(this);
        society = new SocialController(this);
        families = new FamilyController(this);
        villages = new VillageController(this);
        defense = new VillageDefenseController(this);
        bandits = new BanditController(this);
        officials = new OfficialsController(this);
        passports = new PassportOffice(this);
        civil = new CivilOffice(this);
        graft = new GraftController(this);
        divine = new DivineController(this);
        jealousy = new JealousyController(this);
        moods = new MoodWatch(this);
        rivalry = new RivalryController(this);
        coSleep = new CoSleepController(this);
        wedding = new WeddingController(this);
        children = new ChildController(this);
        initiative = new InitiativeController(this);
        talk = new VillageTalkController(this);
        bars = new BarEvenings(this);
        actions = new MobActionController(this);
        bargaining = new VillagerBargaining(this);
        trading = new MobTrading(this);
        trades = new TradeBridge(this);
        mischief = new MischiefWatch(this);
    }

    public static void initialize() {
        if (registered) return;
        registered = true;
        // A favour may only ask for an item the game knows; the core checks the id shape alone.
        Trading.setItemChecker(id -> MobTrading.item(id) != null);
        MischiefWatch.initialize();
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            MobGameBridge bridge = forEntity(entity);
            return bridge == null || !bridge.defense.guards.shieldBlocks(entity, source); // a guard's shield turns half the arrows
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, amount, blocked) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge == null || !(entity instanceof MobEntity mob)) return;
            if (source.getAttacker() instanceof ServerPlayerEntity player && !blocked && amount > 0) {
                bridge.memory.recordDamage(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                        MobGameBridge.playerName(player), MobClock.now());
                bridge.witnessed(mob, player, Witness.HIT);
                bridge.wedding.hurt(mob, player);
                if (mob instanceof VillagerEntity villager) { bridge.bargaining.hurtBy(villager, player); bridge.graft.assaulted(villager, player); }
                bridge.actions.hurtBy(mob, player);
                if (!blocked && amount > 0) bridge.defense.hurt(mob, source);
            } else if (!blocked && amount > 0 && bridge.memory.known(mob.getUuid())) {
                // Only a mob the player already knows keeps a diary: a profile for every burning zombie would bloat the world memory.
                bridge.event(mob, "Damage: " + source.getName() + "; attacker="
                        + (source.getAttacker() == null ? "environment" : type(source.getAttacker())));
                if (source.getAttacker() instanceof MobEntity attacker) bridge.social.attacked(attacker, mob);
            }
            if (!(source.getAttacker() instanceof ServerPlayerEntity) && !blocked && amount > 0) bridge.defense.hurt(mob, source);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge != null) bridge.defense.died(entity, source);
            if (bridge == null || !(entity instanceof MobEntity mob)) return;
            // Fabric fires AFTER_DAMAGE only for a survived blow, so the kill is seen here alone. A mob nobody talked to leaves no
            // memory behind; one with a conversation keeps its profile, and its death goes into it.
            UUID id = mob.getUuid();
            if (source.getAttacker() instanceof ServerPlayerEntity player) bridge.witnessed(mob, player, Witness.KILL);
            if (mob instanceof VillagerEntity villager) bridge.divine.died(villager); // Before the family's mourning: it reads the kin.
            bridge.social.died(mob); // Before the stranger's profile goes: the mourners keep its name.
            bridge.families.died(mob, source); // Relatives mourn wherever they are.
            bridge.children.died(mob);
            if (bridge.memory.known(id) && !bridge.memory.forgetStranger(id)) bridge.event(mob, "The mob died: " + source.getName());
            // END_WORLD_TICK removes dead leases; a death callback can run inside that lease's melee goal.
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge != null) bridge.wedding.loaded(entity);
            if (bridge != null && entity instanceof MobEntity mob) { bridge.actions.restore(mob); bridge.keep(mob); }
            if (bridge != null && entity instanceof net.minecraft.entity.passive.IronGolemEntity golem) bridge.defense.sturdy(golem);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            MobGameBridge bridge = forEntity(entity);
            if (bridge != null && entity instanceof MobEntity mob) {
                bridge.actions.unload(mob);
                bridge.social.unload(mob);
                bridge.initiative.unload(mob);
                bridge.talk.unload(mob.getUuid());
                bridge.coSleep.unload(mob);
                if (mob instanceof VillagerEntity villager) { bridge.bargaining.unload(villager); bridge.trades.unload(villager); }
                bridge.heard.remove(mob.getUuid());
                bridge.changes.forget(mob.getUuid());
            }
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            MobGameBridge bridge = world instanceof ServerWorld serverWorld ? active(serverWorld.getServer()) : null;
            if (bridge != null && player instanceof ServerPlayerEntity serverPlayer) bridge.changes.broken((ServerWorld) world, pos, serverPlayer);
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            MobGameBridge bridge = active;
            if (bridge != null && bridge.server == world.getServer()) {
                bridge.actions.tick(world);
                bridge.actions.stance(world);
                bridge.social.tick(world);
                bridge.society.tick(world);
                bridge.families.tick(world);
                bridge.villages.tick(world);
                bridge.defense.tick(world);
                bridge.bandits.tick(world);
                bridge.officials.tick(world, bridge.defense.zones(world));
                bridge.passports.tick(world);
                bridge.civil.tick(world);
                bridge.graft.tick(world, bridge.defense.zones(world));
                bridge.divine.tick(world);
                bridge.jealousy.tick(world);
                bridge.moods.tick(world);
                bridge.rivalry.tick(world);
                bridge.coSleep.tick(world);
                bridge.wedding.tick(world);
                bridge.children.tick(world);
                bridge.initiative.tick(world);
                bridge.talk.tick(world);
                bridge.bars.tick(world);
                bridge.bargaining.tick(world);
                bridge.trades.tick(world);
                bridge.changes.tick(bridge, world);
                if (world.getTime() % 20 == 7) bridge.refreshStandingIn(world);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MobGameBridge bridge = active(server);
            if (bridge != null) { bridge.bargaining.close(); bridge.trades.close(); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MobGameBridge bridge = active;
            if (bridge != null && bridge.server == server) active = null;
        });
    }

    /** Call on server thread after the memory file was opened by a worker. */
    public static MobGameBridge attach(MinecraftServer server, ConversationStore memory, MobTalkConfig config) {
        if (active != null && active.server == server) { active.config = config.copy(); return active; }
        MobGameBridge bridge = new MobGameBridge(server, memory, config);
        active = bridge;
        for (ServerWorld world : server.getWorlds())
            for (Entity entity : world.iterateEntities()) if (entity instanceof MobEntity mob) bridge.actions.restore(mob);
        return bridge;
    }

    /** The bars found around players and their evenings (self-test, /mobtalk bar). */
    public List<String> barStatus(ServerWorld world) { return bars.describe(world); }
    /** Where the bartender of the bar near this spot stands, null without an open bar (self-test). */
    public BlockPos barStand(BlockPos near) { return bars.bartenderStand(near); }
    public static MobGameBridge active(MinecraftServer server) {
        MobGameBridge bridge = active;
        return bridge != null && bridge.server == server ? bridge : null;
    }
    public static CompletableFuture<Void> detach(MinecraftServer server) {
        MobGameBridge bridge = active(server);
        if (bridge == null) return CompletableFuture.completedFuture(null);
        active = null;
        for (MobCard card : bridge.memory.cards(null))
            if (card.action() != null && card.action().type() != ActionType.NONE && card.action().type() != ActionType.FOLLOW)
                bridge.memory.recordAction(card.id(), ActionIntent.none());
        if (server.isStopped()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> { bridge.actions.clearRuntime(); bridge.sight.clear(); bridge.social.clearRuntime(); bridge.society.clearRuntime(); bridge.villages.clearRuntime(); bridge.divine.clearRuntime(); bridge.defense.clearRuntime(); bridge.officials.clearRuntime(); bridge.passports.clearRuntime(); bridge.graft.clearRuntime(); bridge.jealousy.clearRuntime(); bridge.rivalry.clearRuntime(); bridge.coSleep.clearRuntime(); bridge.wedding.clearRuntime(); bridge.children.clearRuntime(); bridge.initiative.clearRuntime(); bridge.talk.clearRuntime(); bridge.bars.clearRuntime(); bridge.bargaining.close(); bridge.trades.close(); bridge.bandits.clearRuntime(); bridge.civil.clearRuntime(); done.complete(null); });
        return done;
    }
    /** Б1: an event of the divine block (JSON), from any thread; dropped when no world is open. */
    public static void divineEvent(String json) {
        MobGameBridge bridge = active;
        if (bridge != null && json != null) bridge.divine.offer(json);
    }
    /** Self-test: Shift+right-click on an official (the passport office); how many stand ahead of the player in the clerk's queue
     * (-1: not in one) and how many villagers stand in that line. Server thread. */
    public boolean officeVisitForTest(net.minecraft.entity.passive.VillagerEntity official, ServerPlayerEntity player) { return passports.visit((ServerWorld) official.getWorld(), player, official); }
    public int queueAheadForTest(UUID player) { return passports.ahead(player); }
    /** The papers window: what the player was offered last, and a click on its line {@code n}. */
    public List<io.github.mobtalk.core.CivilRegistry.Offer> papersMenuForTest(UUID player) { return civil.menu(player); }
    public void papersChooseForTest(ServerPlayerEntity player, int n) { civil.choose(player, n); }
    public int queueLineForTest(UUID player) { return passports.line(player); }
    /** The head's dacha placed at {@code origin} (front facing {@code facing}, 0..3) and grown to {@code points}; returns the stages built. */
    public int dachaForTest(net.minecraft.entity.passive.VillagerEntity head, BlockPos bell, BlockPos origin, int facing, int points) {
        ServerWorld world = (ServerWorld) head.getWorld();
        VillageDefenseController.Zone zone = defense.zoneAt(world, bell);
        if (zone == null) return -1;
        memory.estate(head.getUuid(), officials.name(head), zone.key());
        memory.estateUpdate(head.getUuid(), e -> { e.place(origin.getX(), origin.getY(), origin.getZ(), facing); e.points(points); });
        for (int i = 0; i < io.github.mobtalk.core.Graft.Stage.values().length; i++) graft.grow(world, zone, head, memory.estate(head.getUuid(), null, null));
        return memory.estate(head.getUuid(), null, null).built();
    }
    /** Heaven strikes the head now: LIGHTNING, TOADS or DUNG. */
    public void smiteForTest(net.minecraft.entity.passive.VillagerEntity head, BlockPos bell, String smite) {
        ServerWorld world = (ServerWorld) head.getWorld();
        VillageDefenseController.Zone zone = defense.zoneAt(world, bell);
        if (zone == null) return;
        graft.strike(world, zone, head, memory.estate(head.getUuid(), null, null), io.github.mobtalk.core.Graft.Smite.valueOf(smite), GraftController.day(world));
    }
    public boolean inspectingForTest(BlockPos bell, ServerWorld world) { VillageDefenseController.Zone zone = defense.zoneAt(world, bell); return zone != null && graft.inspecting(zone.key()); }
    public String zoneKeyForTest(BlockPos bell, ServerWorld world) { VillageDefenseController.Zone zone = defense.zoneAt(world, bell); return zone == null ? "" : zone.key(); }
    /** Where the admin history hears the villages learn of the god's deeds; null to stop. */
    public static void divineJournal(java.util.function.BiConsumer<String, com.google.gson.JsonObject> sink) { DivineController.journal = sink; }
    public static MobGameBridge forEntity(Entity entity) {
        return entity.getWorld() instanceof ServerWorld world ? active(world.getServer()) : null;
    }
    static MobGameBridge currentTradeBridge() {
        MobGameBridge bridge = active;
        return bridge != null && bridge.server.isOnThread() ? bridge : null;
    }
    /** The name mobs know this player by: the one set in the settings, else the nick ({@link PlayerIdentity}). Use it wherever the
     * name goes into a prompt, a memory, a rumour or a family record — never for commands or the real account. */
    public static String playerName(net.minecraft.entity.player.PlayerEntity player) { return player == null ? "" : PlayerIdentity.name(player.getUuid(), player.getName().getString()); }
    public static String type(Entity entity) { return Registries.ENTITY_TYPE.getId(entity.getType()).toString(); }
    /** From the placement mixins: a block or a bucket of fluid the player put down. */
    public static void blockPlaced(ServerWorld world, net.minecraft.util.math.BlockPos pos, ServerPlayerEntity player) {
        MobGameBridge bridge = active(world.getServer());
        if (bridge != null) bridge.changes.placed(world, pos, player);
    }
    public void event(MobEntity mob, String text) {
        memory.recordEvent(mob.getUuid(), type(mob), mob.getName().getString(), text, MobClock.now());
    }
    /** Every mob with a profile that can see the victim takes it personally: a smaller drop than the victim's and a memory
     * "Видел, как игрок … ударил Марфу (корова)". Strangers notice nothing — a profile for each would bloat the world memory. */
    void witnessed(MobEntity victim, ServerPlayerEntity player, Witness deed) { witnessed(victim, player, deed.verb, -deed.penalty(config), deed.range(config)); }
    void witnessed(MobEntity victim, ServerPlayerEntity player, String seen, int delta, double range) {
        if (!config.enabled || !(victim.getWorld() instanceof ServerWorld world)) return;
        // Killing monsters, or a creature that is attacking the player, is self-defence in everyone's eyes.
        if (SpeciesCatalog.get(type(victim)).hostile() || victim.getTarget() == player) return;
        String name = memory.displayName(victim.getUuid());
        String what = seen + " " + (name.isBlank() ? victim.getName().getString() : name) + " (" + SpeciesCatalog.get(type(victim)).name().toLowerCase(java.util.Locale.ROOT) + ")";
        long now = MobClock.now();
        for (MobEntity witness : world.getEntitiesByClass(MobEntity.class, victim.getBoundingBox().expand(range),
                other -> other != victim && other.isAlive() && !other.isRemoved() && memory.known(other.getUuid()) && other.canSee(victim)))
            memory.recordWitnessed(witness.getUuid(), player.getUuid(), MobGameBridge.playerName(player), what, delta, now);
    }
    /** How far a mob sees what happens to another, and how far it hears what is said: the defaults of the settings
     * {@code witnessSightRange} and {@code witnessHearingRange} (see {@link Witness}). */
    static final double WITNESS_RANGE = 16, HEARING_RANGE = 5;

    /** The mob stops wandering and keeps looking at the player for {@code ticks}: it is being spoken to, thinking or speaking. */
    /** The gift key: one item from the player's hand to this creature, on the server. */
    @Override public ConversationStore memory() { return memory; }
    @Override public MobTalkConfig config() { return config; }
    @Override public ConversationStore.Village villageAt(UUID playerId, String dimension, int x, int z) { return villageWhereStanding(playerId, dimension, x, z); }
    /** По UUID из памяти: тип и имя берутся с карточки, незнакомец без карточки пропускается. */
    @Override public void event(UUID mobId, String text) {
        MobCard card = memory.card(mobId, null);
        if (card != null) memory.recordEvent(mobId, card.type(), card.name(), text, MobClock.now());
    }
    @Override public CompletableFuture<String> giftFromHand(UUID mobId, UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            Entity entity = player == null ? null : ((ServerWorld) player.getWorld()).getEntity(mobId);
            if (!(entity instanceof MobEntity mob) || !mob.isAlive()) return "Моб недоступен";
            if (mob instanceof BanditEntity bandit) return bandit.pay(player, player.getMainHandStack()); // a bandit takes it as his due
            return society.giftFromHand((ServerWorld) player.getWorld(), player, mob);
        });
    }

    /** Трек J: игрок позвал свою пару спать («пойдём спать»): она идёт за ним и ляжет рядом, когда он ляжет. */
    public CompletableFuture<Void> inviteToBed(UUID mobId, UUID playerId) { return onServer(() -> { coSleep.invite(mobId, playerId); return null; }); }
    /** Трек N: игрок позвал супруга на особый вечер; строка для чата ("" — звали не супруга). */
    public CompletableFuture<String> startEvening(UUID mobId, UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            Entity entity = player == null ? null : ((ServerWorld) player.getWorld()).getEntity(mobId);
            return entity instanceof VillagerEntity villager ? children.startEvening(villager, player) : "";
        });
    }
    /** Трек N: экран семьи — сводка из памяти мира и то, что видно в мире: где супруг и дети, настроение, профессия. */
    public record FamilyPage(ChildLedger.Overview overview, Map<UUID, String> where, Map<UUID, String> mood, Map<UUID, String> profession) { }
    public CompletableFuture<FamilyPage> family(UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            ChildLedger.Overview overview = memory.children().overview(playerId, server.getOverworld().getTimeOfDay(), server.getOverworld().getTime(), MobClock.now());
            Map<UUID, String> where = new HashMap<>(), mood = new HashMap<>(), profession = new HashMap<>();
            List<UUID> ids = new ArrayList<>();
            overview.spouses().forEach(s -> ids.add(s.id())); overview.children().forEach(c -> ids.add(c.id()));
            for (UUID id : ids) {
                Entity entity = null;
                for (ServerWorld world : server.getWorlds()) { entity = world.getEntity(id); if (entity != null) break; }
                if (!(entity instanceof MobEntity mob) || !mob.isAlive()) { where.put(id, "не рядом (не загружен)"); continue; }
                if (player != null && mob.getWorld() == player.getWorld()) {
                    int distance = (int) Math.sqrt(mob.squaredDistanceTo(player));
                    where.put(id, (distance <= 16 ? "рядом" : distance <= 64 ? "в деревне" : "далеко") + " (" + distance + " бл., " + mob.getBlockX() + " " + mob.getBlockY() + " " + mob.getBlockZ() + ")");
                } else where.put(id, "в другом измерении");
                MobCard card = memory.card(id, playerId);
                MobState.Descriptor marker = card == null || card.state() == null ? null : card.state().marker();
                if (marker != null) mood.put(id, net.minecraft.text.Text.translatable("mobtalk.state." + marker.key()).getString()
                        + (card.state().thought() == null || card.state().thought().isBlank() ? "" : " — «" + card.state().thought() + "»"));
                if (mob instanceof VillagerEntity villager) {
                    net.minecraft.util.Identifier job = Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession());
                    if (job != null && !"none".equals(job.getPath())) profession.put(id, net.minecraft.text.Text.translatable("entity.minecraft.villager." + job.getPath()).getString());
                }
            }
            return new FamilyPage(overview, where, mood, profession);
        });
    }
    /** Трек N: игрок что-то сказал своей паре — в особую ночь это держит ночь. */
    public CompletableFuture<Void> eveningSpoke(UUID playerId) { return onServer(() -> { children.spoke(playerId); return null; }); }
    /** Трек L: игрок позвал невесту (жениха) на свадьбу; строка для чата — почему нельзя, "" — началась или звали не ту. */
    public CompletableFuture<String> startWedding(UUID mobId, UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            Entity entity = player == null ? null : ((ServerWorld) player.getWorld()).getEntity(mobId);
            return entity instanceof VillagerEntity villager ? wedding.start(villager, player) : "";
        });
    }
    /** Трек L: игрок попросил жителя быть свидетелем; строка для чата, "" — не стал (или некого женить). */
    public CompletableFuture<String> appointWitness(UUID mobId, UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            return player == null ? "" : memory.wedding().appointWitness(mobId, playerId, playerName(player), MobClock.now());
        });
    }

    public CompletableFuture<Void> attend(UUID mobId, UUID playerId, int ticks) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            Entity entity = player == null ? null : ((ServerWorld) player.getWorld()).getEntity(mobId);
            if (entity instanceof MobEntity mob && mob.isAlive()) actions.attend(mob, player, ticks);
            return null;
        });
    }

    /** Bystanders that may add a line after the mob spoken to: alive, within the conversation range of the player, able to see
     * them, nearest first with mobs the player already knows ahead of strangers. Never the speaker itself. */
    @Override public CompletableFuture<List<UUID>> witnesses(UUID speakerId, UUID playerId, String dimension, int limit) {
        return onServer(() -> {
            ServerWorld world = world(dimension);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (world == null || player == null || !player.isAlive() || player.getWorld() != world || limit <= 0) return List.of();
            double range = Math.min(config.witnessHearingRange, config.maxDistance);
            Entity speaker = world.getEntity(speakerId);
            // Within earshot of either side of the conversation, and able to see the player.
            List<MobEntity> around = world.getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(range + 4),
                    mob -> !mob.getUuid().equals(speakerId) && mob.isAlive() && !mob.isRemoved() && !mob.isAiDisabled()
                            && (mob.squaredDistanceTo(player) <= range * range || speaker != null && mob.squaredDistanceTo(speaker) <= range * range)
                            && mob.squaredDistanceTo(player) <= config.maxDistance * config.maxDistance && sees(mob, player));
            around.sort(Comparator.comparingInt((MobEntity mob) -> memory.known(mob.getUuid()) ? 0 : 1).thenComparingDouble(player::squaredDistanceTo));
            return around.stream().limit(limit).map(Entity::getUuid).toList();
        });
    }

    /** A creature the player has a history with never despawns (vanilla keeps only named or fed ones): the flag is set when the
     * player talks to it, gives to it or helps it, and again whenever a known creature loads with a world. */
    void keep(MobEntity mob) { keep(mob, memory.acquainted(mob.getUuid())); }
    void keep(MobEntity mob, boolean acquainted) {
        if (!config.keepAcquainted || !acquainted || mob.isPersistent()) return;
        mob.setPersistent();
    }

    /** Трек J: пропуск ночи ждёт, пока игрок лежит рядом с парой (миксин на {@code PlayerEntity.canResetTimeBySleeping}). */
    public static boolean holdsNight(ServerPlayerEntity player) {
        MobGameBridge bridge = forEntity(player);
        return bridge != null && (bridge.coSleep.holdsNight(player) || bridge.children.holdsNight(player) && bridge.coSleep.withinCap(player));
    }
    /** Сделка в окне торговли состоялась: запись «Торг» в журнал админки (на выделенном сервере; в одиночке журнал пуст). */
    public static void journalTrade(MobEntity merchant, ServerPlayerEntity player, net.minecraft.village.TradeOffer offer) {
        io.github.mobtalk.server.DialogueService dialogue = io.github.mobtalk.MobTalkMod.dialogue();
        MobGameBridge bridge = forEntity(merchant);
        if (dialogue == null || bridge == null || offer == null) return;
        String known = bridge.memory.displayName(merchant.getUuid());
        String gave = stack(offer.getDisplayedFirstBuyItem()) + (offer.getDisplayedSecondBuyItem().isEmpty() ? "" : " + " + stack(offer.getDisplayedSecondBuyItem()));
        dialogue.traded(player.getUuid(), playerName(player), merchant.getUuid(), known.isBlank() ? merchant.getName().getString() : known, type(merchant), gave, stack(offer.getSellItem()));
    }
    private static String stack(net.minecraft.item.ItemStack stack) { return stack.getCount() + " " + Registries.ITEM.getId(stack.getItem()).getPath(); }
    public static void helped(MobEntity mob, ServerPlayerEntity player, String reason) {
        MobGameBridge bridge = forEntity(mob);
        if (bridge == null) return;
        bridge.keep(mob, true);
        bridge.memory.recordHelp(mob.getUuid(), type(mob), mob.getName().getString(), player.getUuid(),
                MobGameBridge.playerName(player), MobClock.now());
        bridge.event(mob, reason + ": " + MobGameBridge.playerName(player));
    }

    /** The conversation target behind the player's open merchant window: a mob's own deals (B) or a villager's profession trades. */
    public CompletableFuture<UUID> selectMerchant(UUID playerId, int syncId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("Игрок недоступен");
            MobMerchant own = MobMerchant.of(player);
            if (own != null && player.currentScreenHandler.syncId == syncId) return own.mob.getUuid();
            return VillagerBargaining.selected(player, syncId).getUuid();
        });
    }
    /** « отношение +N» for the HUD and the trade-window title; no card means a stranger. */
    public static String relationshipLabel(MobCard card) {
        int value = card == null ? 0 : card.relationship();
        boolean hostile = card != null && SpeciesCatalog.get(card.type()).hostile();
        String mark = hostile && value >= CombatRules.ALLY ? " (свой)" : hostile && value >= CombatRules.TRUCE ? " (перемирие)" : "";
        String hearts = card != null && "minecraft:villager".equals(card.type()) ? Hearts.symbols(value) : "";
        return "отношение " + (value >= 0 ? "+" : "") + value + mark + (hearts.isEmpty() ? "" : " " + hearts);
    }

    @Override public CompletableFuture<MobSnapshot> capture(UUID mobId, UUID playerId, String dimension) {
        return onServer(() -> {
            Nearby nearby = nearby(mobId, playerId, dimension);
            if (memory.card(mobId, playerId) == null) event(nearby.mob, "The player met this mob");
            actions.attend(nearby.mob, nearby.player, 100); // Being spoken to: stand and listen while the answer is written.
            MobSnapshot snapshot = MobContextCapture.capture(this, nearby.world, nearby.mob, nearby.player);
            social.conversation(nearby.mob, snapshot); // The known creatures around get acquainted with the one spoken to.
            return snapshot;
        }).thenApplyAsync(snapshot -> config.placeClassifier ? PlaceCapture.classify(snapshot) : snapshot); // Tens of milliseconds: never on the server thread.
    }
    /** The living mob and player in the same world within the conversation range; server thread only. */
    private Nearby nearby(UUID mobId, UUID playerId, String dimension) {
        ServerWorld world = world(dimension);
        Entity entity = world == null ? null : world.getEntity(mobId);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (!(entity instanceof MobEntity mob) || !mob.isAlive() || mob.isRemoved() || player == null
                || !player.isAlive() || player.getWorld() != world)
            throw new IllegalStateException("Собеседник или игрок недоступен");
        if (mob.squaredDistanceTo(player) > config.maxDistance * config.maxDistance)
            throw new IllegalStateException("Подойдите к мобу ближе");
        return new Nearby(world, mob, player);
    }
    private record Nearby(ServerWorld world, MobEntity mob, ServerPlayerEntity player) { }

    /** Opens the vanilla merchant window over the mob's own deals; the vanilla handler moves the items from then on. */
    @Override public CompletableFuture<Void> openTrade(UUID mobId, UUID playerId, String dimension) {
        return onServer(() -> {
            if (!config.enabled) throw new IllegalStateException("Mob Talk выключен");
            Nearby nearby = nearby(mobId, playerId, dimension);
            synchronized (memory) {
                if (memory.card(mobId, playerId) == null) event(nearby.mob, "The player met this mob");
                trading.open(nearby.mob, nearby.player);
            }
            return null;
        });
    }

    @Override public CompletableFuture<String> apply(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, observed, expectedRevision, valid).thenApply(Outcome::message);
    }
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, BargainIntent.none(), observed, expectedRevision, valid);
    }
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, MobSnapshot observed, long expectedRevision, java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, bargain, DealIntent.none(), "", QuestIntent.none(), observed, expectedRevision, valid);
    }
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, DealIntent deal, String unlock, QuestIntent quest, MobSnapshot observed, long expectedRevision,
            java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, bargain, deal, unlock, quest, SocialIntent.NONE, observed, expectedRevision, valid);
    }
    /** {@code social}: what the player's words were (the store scores the victim on commit); bystanders in sight take an insult
     * or a death threat to one of their own personally. */
    public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, DealIntent deal, String unlock, QuestIntent quest, SocialIntent social, MobSnapshot observed, long expectedRevision,
            java.util.function.BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, bargain, deal, unlock, quest, social, GestureIntent.none(), observed, expectedRevision, valid);
    }
    /** {@code gesture}: the social gesture of the reply with its decided outcome; its effect on the world runs here, before the commit. */
    @Override public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, DealIntent deal, String unlock, QuestIntent quest, SocialIntent social, GestureIntent gesture, MobSnapshot observed,
            long expectedRevision, java.util.function.BooleanSupplier valid) {
        return onServer(() -> {
            synchronized (memory) {
                if (!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException("Разговор отменён");
                // A reply written before a blow or a gift still stands (the player asked for no cancellations): the next turn sees the change.
                if (expectedRevision >= 0 && memory.revision(mobId) != expectedRevision) LOGGER.info("Mob Talk: отношение моба изменилось во время ответа, ответ оставлен");
                ServerWorld world = world(dimension);
                Entity entity = world == null ? null : world.getEntity(mobId);
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (!(entity instanceof MobEntity mob) || !mob.isAlive() || player == null || !player.isAlive()
                        || player.getWorld() != world) throw new java.util.concurrent.CancellationException("Моб или игрок недоступен");
                if (memory.card(mobId, playerId) == null) event(mob, "The player met this mob");
                keep(mob, true); // Spoken to: it will not despawn behind the player's back.
                // The thing handed over is what the favour asked for: it is the delivery, not a present — the favour takes it and pays,
                // whatever the model called it (a gift would eat the item and leave the favour open).
                Favor owed = memory.favor(mobId, playerId);
                GestureIntent handed = gesture; QuestIntent asked = quest;
                if (handed != null && handed.takesItem() && owed != null && owed.item().equals(handed.item())) {
                    handed = GestureIntent.none();
                    net.minecraft.item.Item wanted = MobTrading.item(owed.item());
                    if (asked == null || asked.type() != QuestIntent.Type.COMPLETE) {
                        if (wanted != null && MobTrading.count(player, wanted) >= owed.count()) asked = QuestIntent.complete();
                        else LOGGER.info("Mob Talk: предмет услуги в руке, но не хватает до {} шт.: подарок не засчитан, услуга открыта", owed.count());
                    }
                }
                // П3: предмет испытания соперников — сдача в испытание, а не подарок (житель уже отвечает, отдельной реплики не нужно).
                if (handed != null && handed.takesItem() && Registries.ITEM.getId(player.getMainHandStack().getItem()).toString().equals(handed.item())
                        && rivalry.deliver(mob, player, player.getMainHandStack(), false) > 0) handed = GestureIntent.none();
                // First: a gift no longer in hand cancels the whole reply before anything else changes.
                String gestured = society.apply(mob, player, handed);
                jealousy.gift(mob, player, handed); // Трек J: подарок паре гасит ревность, сопернику — разжигает.
                String trade = "";
                if (bargain != null && bargain.type() != BargainIntent.Type.NONE) {
                    if (!(mob instanceof VillagerEntity villager)) throw new IllegalStateException("Торг доступен только с жителями");
                    // Window shut: the bargain is a spoken promise that becomes a one-use row; open: a price in the window.
                    trade = TradeBridge.spoken(observed) ? trades.apply(villager, player, bargain, observed) : bargaining.apply(villager, player, bargain, observed);
                }
                String deals = trading.apply(mob, player, deal, unlock, asked);
                if (!deals.isBlank()) trade = trade.isBlank() ? deals : trade + "; " + deals;
                // Посреди обряда пару и ведущего не уводят: «следуй», «стой», «беги» от обычного разговора ломали сцену.
                String result = wedding.involved(mob.getUuid()) && intent != null && intent.type() != ActionType.NONE ? "" : actions.apply(mob, player, intent, observed);
                if (!gestured.isBlank()) result = result.isBlank() ? gestured : result + "; " + gestured;
                if (!trade.isBlank()) result = result.isBlank() ? trade : result + "; " + trade;
                jealousy.talked(mob, player, handed == null ? null : handed.gesture()); // Трек J: разговор с парой или с жителем её пола.
                Witness heard = Witness.of(social);
                if (heard != null) witnessed(mob, player, heard);
                return new Outcome(result, memory.revision(mobId));
            }
        });
    }

    public CompletableFuture<String> stopAllFollowers() {
        return onServer(() -> { actions.stopFollowers(); return "Следование всех мобов отменено"; });
    }
    @Override public <T> CompletableFuture<T> onServer(java.util.function.Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            if (active != this) { future.completeExceptionally(new IllegalStateException("Мир сменился")); return; }
            try { future.complete(work.get()); } catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        return future;
    }
    /** The known village at this spot: the one founded in the village structure there (with the option on), an old village by the
     * old rule, else the nearest within the village radius; null outside any. Only looks: an old village takes a structure only
     * from its residents. Server thread only. */
    public ConversationStore.Village villageAt(ServerWorld world, BlockPos pos) {
        String dimension = world.getRegistryKey().getValue().toString();
        ConversationStore.Site site = config.villageSites ? VillageSites.at(world, pos) : null;
        return site != null ? memory.villageFor(dimension, pos.getX(), pos.getZ(), site, false) : memory.villageNear(dimension, pos.getX(), pos.getZ());
    }

    /** /mobtalk village forgive: the village where the player stands, found like {@link #villageAt}, forgives them. */
    public CompletableFuture<String> forgiveVillage(UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) return "Мир ещё не загружен";
            defense.forgive(player);
            return memory.forgiveVillage(villageAt(player.getServerWorld(), player.getBlockPos()), playerId);
        });
    }

    /** The village each player stood in at the last refresh (once a second), "" for none, for the client's status lines. */
    private final Map<UUID, String> standingIn = new java.util.concurrent.ConcurrentHashMap<>();
    private void refreshStandingIn(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            ConversationStore.Village village = villageAt(world, player.getBlockPos());
            standingIn.put(player.getUuid(), village == null ? "" : village.id());
        }
    }
    /** The known village the player stood in at the last refresh, by the same rule as {@link #villageAt}; until the first refresh,
     * the nearest within the radius of this point. Safe from any thread. */
    public ConversationStore.Village villageWhereStanding(UUID player, String dimension, int x, int z) {
        String id = player == null ? null : standingIn.get(player);
        return id == null ? memory.villageNear(dimension, x, z) : memory.village(id);
    }

    ServerWorld world(String dimension) {
        for (ServerWorld world : server.getWorlds())
            if (world.getRegistryKey().getValue().toString().equals(dimension)) return world;
        return null;
    }

    /** Only sound-producing game events are perceptible to a blind warden. */
    public static void heard(WardenEntity warden, Vec3d position, Entity source, String event) {
        MobGameBridge bridge = forEntity(warden);
        if (bridge == null) return;
        Deque<Heard> events = bridge.heard.computeIfAbsent(warden.getUuid(), ignored -> new ArrayDeque<>());
        events.addLast(new Heard(warden.getWorld().getTime(), position, source == null ? null : source.getUuid(), event));
        while (events.size() > 16) events.removeFirst();
    }
    List<Heard> heard(MobEntity mob) {
        Deque<Heard> events = heard.get(mob.getUuid());
        if (events == null) return List.of();
        events.removeIf(event -> mob.getWorld().getTime() - event.tick > 100);
        return List.copyOf(events);
    }
    public static void afterBrain(ServerWorld world, LivingEntity entity) {
        MobGameBridge bridge = active(world.getServer());
        if (bridge != null && entity instanceof MobEntity mob) bridge.actions.afterBrain(mob);
    }
    /** The mob whose brain is ticking now (server thread), so a wake-up from its daily routine can be told from a blow or an alarm. */
    private static MobEntity thinking;
    public static void thinking(MobEntity mob) { thinking = mob; }
    /** A villager in apathy is not woken by its routine: it lies in bed day and night. A blow, an alarm or a raid still wake it. */
    public static boolean keepsInBed(net.minecraft.entity.passive.VillagerEntity villager) {
        if (thinking != villager) return false;
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && bridge.config.enabled && bridge.moods.keepsInBed(villager);
    }
    public static boolean suppressesAttack(MobEntity mob) {
        MobGameBridge bridge = forEntity(mob);
        return bridge != null && bridge.actions.suppressesNativeAttack(mob);
    }
    /** A hostile mob that earned this player's trust leaves them alone; the first blow drops the relationship and ends it. */
    public static boolean truce(MobEntity mob, ServerPlayerEntity player) {
        MobGameBridge bridge = forEntity(mob);
        if (bridge == null || !bridge.config.enabled) return false;
        return CombatRules.truce(SpeciesCatalog.get(type(mob)).hostile(), bridge.memory.relationship(mob.getUuid(), player.getUuid()));
    }
    /** An exiled player gets no trade window from this village's villagers. */
    /** From the interact mixin: the bartender of an open bar opens his drinks menu instead of his own trade. */
    public static boolean serveDrinks(VillagerEntity villager, ServerPlayerEntity player) {
        MobGameBridge bridge = forEntity(villager);
        if (bridge == null || !bridge.config.barDrinks || player.isSneaking() || !bridge.bars.isBartender(villager)) return false;
        BarMerchant.open(villager, player);
        return true;
    }
    public static boolean refusesTrade(VillagerEntity villager, ServerPlayerEntity player) {
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && bridge.villages.refuseTrade(villager, player);
    }
    /** From the spawn mixin: no monster appears inside a village that has a golem (village defence). */
    public static boolean blocksMonsterSpawn(ServerWorld world, Entity entity) {
        MobGameBridge bridge = active(world.getServer());
        return bridge != null && bridge.defense.blocksSpawn(world, entity);
    }
    /** From the trade-show mixin: a fighter of the village or a tower's builder keeps its weapon or block in hand. */
    public static boolean keepsHands(VillagerEntity villager) {
        if (DefenseContent.defender(villager)) return true;
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && villager.getWorld() instanceof ServerWorld world && bridge.defense.towers.builds(world, villager);
    }
    /** From the panic mixin: a guard on duty stands its ground at a monster or a blow instead of fleeing (village defence). */
    /** From the zombie mixin: this villager, killed by a zombie, dies rather than turning (village defence). */
    public static boolean blocksZombieConversion(VillagerEntity villager) {
        if (DefenseContent.defender(villager)) return true;
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && bridge.config.enabled && bridge.config.villageNoConversion;
    }

    public static boolean guardStandsGround(VillagerEntity villager) {
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && bridge.defense.guards.standsGround(villager);
    }
    /** Villager families own breeding: while they are on, vanilla villagers do not breed (children come only to couples). */
    public static boolean familiesOwnBreeding(VillagerEntity villager) {
        MobGameBridge bridge = forEntity(villager);
        return bridge != null && bridge.config.enabled && bridge.config.socialLayer && bridge.config.familyLife;
    }
    /** A creature this hostile promised to leave alone (SPARE) is never taken as a target while the promise lasts. */
    public static boolean spared(MobEntity mob, LivingEntity target) {
        MobGameBridge bridge = forEntity(mob);
        return bridge != null && bridge.config.enabled && bridge.actions.friends.spared(mob, target);
    }
    /** The «Моб и деревня» panel ({@link MobInfo}): the place is captured on the server thread like before a reply (without making the
     * mob stop and listen), classified and written up off it. {@code mobId} may be null: only the village where the player stands. */
    @Override public CompletableFuture<String> info(UUID mobId, UUID playerId, String dimension, int x, int z, boolean secrets) {
        record Seen(List<String> duties, Map<String, Object> place, byte[] cube, String here) { }
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            ServerWorld world = world(dimension);
            Entity entity = mobId == null || world == null ? null : world.getEntity(mobId);
            List<String> duties = new ArrayList<>();
            long now = world == null ? 0 : world.getTime();
            if (mobId != null) for (FriendActions.Status active : friendStatus(mobId))
                duties.add(net.minecraft.text.Text.translatable("mobtalk.friend.status", net.minecraft.text.Text.translatable("mobtalk.action." + active.type().name().toLowerCase(Locale.ROOT)),
                        active.type() == ActionType.SPARE ? active.target() : net.minecraft.text.Text.translatable("mobtalk.friend." + active.target()),
                        Math.max(1, (active.expires() - now + 1199) / 1200)).getString());
            Map<String, Object> place = Map.of(); byte[] cube = null;
            if (config.placeAnalysis && entity instanceof MobEntity mob && mob.isAlive() && player != null && player.getWorld() == world) {
                PlaceCapture.Result captured = PlaceCapture.capture(world, mob, player, changes);
                place = PlaceCapture.details(captured);
                if (config.placeClassifier) cube = captured.grid().cube();
            }
            // The village where the player stands, by its structure like everywhere on the server; "" for none.
            ConversationStore.Village here = world == null ? null : villageAt(world, player != null && player.getWorld() == world ? player.getBlockPos() : new BlockPos(x, 64, z));
            return new Seen(duties, place, cube, here == null ? "" : here.id());
        }).thenApplyAsync(seen -> {
            Map<String, Object> place = new LinkedHashMap<>(seen.place());
            io.github.mobtalk.core.place.PlaceClassifier classifier = seen.cube() == null ? null : io.github.mobtalk.core.place.PlaceClassifier.bundled();
            if (classifier != null) {
                io.github.mobtalk.core.place.PlaceClassifier.Guess guess = classifier.guess(seen.cube());
                place.put("looksLike", guess.label()); place.put("looksLikeConfidence", (double) guess.confidence());
            }
            return MobInfo.report(memory, mobId, playerId, dimension, x, z, seen.here(), MobClock.now(), secrets, seen.duties(), place);
        });
    }
    /** The STAY/GUARD/SPARE leases of this mob for /mobtalk state; safe from the client thread. */
    @Override public List<FriendActions.Status> friendStatus(UUID mob) { return actions.friends.status(mob); }
    record Heard(long tick, Vec3d position, UUID source, String event) { }

    // ---- Трек A: взгляды на мир и очереди поводов для DialogueService. ----

    private MobView view(MobEntity mob) {
        String given = memory.displayName(mob.getUuid());
        return new MobView(mob.getId(), mob.getUuid(), given.isBlank() ? mob.getName().getString() : given, type(mob),
                mob.getWorld().getRegistryKey().getValue().toString(), mob.getX(), mob.getY(), mob.getZ());
    }
    private static boolean living(Entity entity) { return entity instanceof MobEntity mob && mob.isAlive() && !mob.isRemoved(); }
    private ServerPlayerEntity online(UUID playerId) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Игрок вышел");
        return player;
    }
    @Override public CompletableFuture<MobView> mobByEntityId(UUID playerId, int entityId) {
        return onServer(() -> {
            Entity entity = ((ServerWorld) online(playerId).getWorld()).getEntityById(entityId);
            if (entity instanceof net.minecraft.entity.boss.dragon.EnderDragonPart part) entity = part.owner;
            if (!living(entity)) throw new IllegalStateException("Моб погиб или выгрузился");
            return view((MobEntity) entity);
        });
    }
    @Override public CompletableFuture<MobView> mob(UUID mobId, String dimension) {
        return onServer(() -> {
            ServerWorld world = world(dimension);
            Entity entity = world == null ? null : world.getEntity(mobId);
            if (!living(entity)) throw new IllegalStateException("Моб погиб или выгрузился");
            return view((MobEntity) entity);
        });
    }
    @Override public CompletableFuture<PlayerView> player(UUID playerId) {
        return onServer(() -> {
            ServerPlayerEntity player = online(playerId);
            MobMerchant own = MobMerchant.of(player);
            UUID merchant = own != null ? own.mob.getUuid() : null;
            if (merchant == null && player.currentScreenHandler instanceof net.minecraft.screen.MerchantScreenHandler handler) {
                try { merchant = VillagerBargaining.selected(player, handler.syncId).getUuid(); } catch (RuntimeException none) { merchant = null; }
            }
            return new PlayerView(playerId, player.getGameProfile().getName(), player.getWorld().getRegistryKey().getValue().toString(),
                    player.getX(), player.getY(), player.getZ(), player.isAlive() && !player.isSpectator(), merchant,
                    player.getWorld().getTimeOfDay());
        });
    }
    @Override public CompletableFuture<List<UUID>> playersNear(UUID mobId, String dimension, double range) {
        return onServer(() -> {
            ServerWorld world = world(dimension);
            Entity entity = world == null ? null : world.getEntity(mobId);
            if (!living(entity)) return List.of();
            double limit = range * range;
            List<UUID> near = new ArrayList<>();
            for (ServerPlayerEntity player : world.getPlayers())
                if (player.isAlive() && !player.isSpectator() && player.squaredDistanceTo(entity) <= limit) near.add(player.getUuid());
            return near;
        });
    }
    @Override public CompletableFuture<List<UUID>> knownMobsNear(UUID playerId, double range) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || !player.isAlive()) return List.of();
            List<MobEntity> around = ((ServerWorld) player.getWorld()).getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(range),
                    mob -> mob.isAlive() && !mob.isRemoved() && mob.squaredDistanceTo(player) <= range * range && memory.acquainted(mob.getUuid()));
            around.sort(Comparator.comparingDouble(player::squaredDistanceTo));
            return around.stream().map(Entity::getUuid).toList();
        });
    }
    @Override public CompletableFuture<UUID> currentMerchant(UUID playerId) { return player(playerId).thenApply(PlayerView::merchant); }
    /** Трек A: слова семьи и свадьбы после ответа — то, что раньше делал клиент в {@code weddingWords}/{@code familyWords}. */
    @Override public CompletableFuture<String> afterWords(UUID mobId, UUID playerId, String said, GestureIntent gesture) {
        return onServer(() -> {
            List<CompletableFuture<String>> parts = new ArrayList<>();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) return CompletableFuture.completedFuture("");
            // Службу свадьбы игрока ведёт в одиночке клиентский WeddingVoice, на выделенном сервере — WeddingHost.
            if (config.weddingCeremony) {
                if (gesture != null && gesture.gesture() == Gesture.PROPOSE && gesture.outcome() == Gesture.Outcome.ACCEPTED)
                    parts.add(CompletableFuture.completedFuture("§d[Mob Talk] Вы помолвлены с " + memory.displayName(mobId)
                            + ". Свадьбу играют у колокола днём: скажите «идём на свадьбу». Свидетелей зовите словами «будь моим свидетелем»."));
                if (Wedding.calls(said)) parts.add(startWedding(mobId, playerId).thenApply(m -> m.isBlank() ? "" : "[Mob Talk] " + m));
                if (Wedding.asksWitness(said)) parts.add(appointWitness(mobId, playerId).thenApply(m -> m.isBlank() ? "" : "[Mob Talk] " + m));
            }
            if (config.bandits && ((ServerWorld) player.getWorld()).getEntity(mobId) instanceof BanditEntity bandit) {
                String note = bandits.haggle((ServerWorld) player.getWorld(), bandit, player, said);
                if (!note.isBlank()) parts.add(CompletableFuture.completedFuture("§7[Mob Talk] " + note));
            }
            if (config.coSleep && CoSleep.invites(said)) parts.add(inviteToBed(mobId, playerId).thenApply(v -> ""));
            if (config.familyLife && config.socialLayer) {
                parts.add(eveningSpoke(playerId).thenApply(v -> ""));
                if (SpecialEvening.invites(said)) parts.add(startEvening(mobId, playerId).thenApply(m -> m.isBlank() ? "" : "§d[Mob Talk] " + m));
            }
            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                StringBuilder text = new StringBuilder();
                for (CompletableFuture<String> part : parts) {
                    String line = part.join();
                    if (!line.isBlank()) text.append(text.isEmpty() ? "" : "\n").append(line);
                }
                return text.toString();
            });
        }).thenCompose(future -> future);
    }
    @Override public CompletableFuture<String> stopAllFollowers(UUID playerId) { return stopAllFollowers(); }
    @Override public CompletableFuture<Integer> countInHand(UUID playerId, String itemId) {
        return onServer(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(itemId);
            if (player == null || id == null || !Registries.ITEM.containsId(id)) return 0;
            net.minecraft.item.Item wanted = Registries.ITEM.get(id);
            int have = 0;
            for (var stack : player.getInventory().main) if (stack.isOf(wanted)) have += stack.getCount();
            for (var stack : player.getInventory().offHand) if (stack.isOf(wanted)) have += stack.getCount();
            return have;
        });
    }
    @Override public Initiative.Cue pollInitiative(boolean floorBusy, Set<UUID> inFlight) {
        return floorBusy ? config.giftsFirst ? initiative.pollGift(inFlight) : null : initiative.poll();
    }
    @Override public void refundInitiative(Initiative.Cue cue) { initiative.refund(cue); }
    @Override public int pendingInitiatives() { return initiative.pending(); }
    @Override public void floorBusy(boolean busy) { initiative.playerBusy = busy; }
    @Override public VillageChatter.Cue pollTalk() { return talk.pollTalk(); }
    @Override public void refundTalk(VillageChatter.Cue cue) { talk.refund(cue); }
    @Override public boolean talkWaiting() { return talk.waiting(); }
    @Override public void talking(boolean on) { talk.talking = on; }
    @Override public VillageNotices.Cue pollNotice() { return talk.pollNotice(); }
    @Override public boolean weddingActive() { return wedding.active(); }
    @Override public WeddingView wedding() {
        WeddingController.Scene scene = wedding.scene();
        return scene == null ? null : new WeddingView(scene.ceremony, scene.input, scene.player, scene.world.getRegistryKey().getValue().toString());
    }
    @Override public io.github.mobtalk.core.WeddingScript.Input takeWeddingScript() { return wedding.takeScriptRequest(); }
}
