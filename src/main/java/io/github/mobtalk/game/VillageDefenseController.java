package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.minecraft.block.BellBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import java.util.*;

/** Village defence, stage 1 (design: /mnt/project-files/village-defense/design.md): the village's alarm in the world, the zone where
 * no monster spawns, and a golem that holds out. A village here is a zone found from its living residents once in
 * {@link #ZONE_PERIOD}: their village structure ({@link VillageSites}) or, for a village without one, the square around their bell,
 * plus {@code defenseZoneMargin}. Once a second the zone is scanned: a monster a resident or golem sees within
 * {@code alarmMonsterRange} raises a threat, a crowd of them an attack; a blow at a villager or golem, a raid, a hated or exiled
 * player raise it from the event hooks. While the alarm is on the golems go for the monsters and for the players it is against
 * (no warning), and in an attack the bell rings, the villagers hide (vanilla's bell memory) and nobody trades. When it is over
 * a villager calls the all-clear and the residents the world memory knows remember how it went. Server thread only. */
final class VillageDefenseController {
    static final long SCAN_PERIOD = 20, SCAN_PHASE = 11, ZONE_PERIOD = 100, ZONE_PHASE = 3, GOLEM_PERIOD = 100, GOLEM_PHASE = 53;
    /** The bell rings again this often while an attack lasts; vanilla villagers stay hidden about this long after a ring. */
    static final long BELL_TICKS = 200;
    /** A village without a structure: the square around its bell, this far each way. */
    static final int BELL_RADIUS = 32;
    /** A villager shouts at most this often per village. */
    static final long SHOUT_TICKS = 200;
    /** An exile who stays this long after the threat becomes an attack. */
    static final long EXILE_ATTACK_TICKS = 600;
    /** How far a golem looks for a monster to fight, and a smith for a golem to mend; a smith rests this long between repairs. */
    static final double GOLEM_REACH = 32, SMITH_REACH = 32;
    static final long SMITH_REST_TICKS = 1200, REPAIR_TIMEOUT_TICKS = 600;
    static final Identifier GOLEM_HEALTH = Identifier.of("mobtalk", "village_golem_health");
    private static final Set<VillagerProfession> SMITHS = Set.of(VillagerProfession.ARMORER, VillagerProfession.TOOLSMITH, VillagerProfession.WEAPONSMITH);

    /** One village as the defence sees it: the alarm key, its ground with the margin, its centre and bell, the known village (id and
     * name, "" when the world memory has none), whether a golem lives there, the village's own bounds without the margin and how
     * many grown residents it has. */
    record Zone(String key, Box box, BlockPos center, BlockPos bell, String villageId, String name, boolean golem,
                int minX, int minZ, int maxX, int maxZ, int residents) {
        boolean holds(BlockPos pos) { return box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }
        Text title() { return name.isBlank() ? Text.translatable("mobtalk.defense.village") : Text.literal(name); }
    }
    private record Repair(VillagerEntity smith, IronGolemEntity golem, long until) { }

    private final MobGameBridge bridge;
    private final VillageAlarm alarms = new VillageAlarm();
    private final Map<String, List<Zone>> zones = new HashMap<>();
    private final Map<String, Long> lastBell = new HashMap<>(), lastShout = new HashMap<>(), exileSince = new HashMap<>();
    private final Map<UUID, Repair> repairs = new HashMap<>();
    private final Map<UUID, Long> smithRest = new HashMap<>();
    private final Random random = new Random();
    /** Watchtowers and their archers. */
    final VillageTowers towers;
    /** Guards with swords and shields. */
    final VillageGuards guards;

    VillageDefenseController(MobGameBridge bridge) { this.bridge = bridge; towers = new VillageTowers(bridge, this); guards = new VillageGuards(bridge, this); }

    /** The alarm of this zone now, null when it is quiet. */
    VillageAlarm.Alarm alarm(Zone zone) { return alarms.get(zone.key()); }
    /** The zone with this key in this world, from the last refresh. */
    Zone zone(ServerWorld world, String key) {
        for (Zone zone : zones.getOrDefault(dimension(world), List.of())) if (zone.key().equals(key)) return zone;
        return null;
    }

    private boolean enabled() { return bridge.config.enabled && bridge.config.villageDefense; }
    private VillageAlarm.Rules rules() { return VillageAlarm.Rules.from(bridge.config); }
    private static String dimension(ServerWorld world) { return world.getRegistryKey().getValue().toString(); }

    // ---- The zones ----

    /** The zone holding this spot, from the last refresh; null outside every village or before the first refresh. */
    Zone zoneAt(ServerWorld world, BlockPos pos) {
        for (Zone zone : zones.getOrDefault(dimension(world), List.of())) if (zone.holds(pos)) return zone;
        return null;
    }

    /** From the spawn mixin: a monster is not to appear here, inside a village that has a golem. */
    boolean blocksSpawn(ServerWorld world, Entity entity) {
        if (!enabled() || !bridge.config.villageNoSpawn || !(entity instanceof Monster)) return false;
        Zone zone = zoneAt(world, entity.getBlockPos());
        return zone != null && zone.golem();
    }

    private void refreshZones(ServerWorld world) {
        final class Found { BlockPos bell; int minX, minZ, maxX, maxZ, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, residents; }
        Map<String, Found> found = new LinkedHashMap<>();
        for (VillagerEntity villager : world.getEntitiesByType(EntityType.VILLAGER, v -> v.isAlive() && !v.isRemoved())) {
            BlockPos bell = bell(world, villager);
            ConversationStore.Site site = bridge.config.villageSites ? VillageSites.of(world, villager) : null;
            String key; int minX, minZ, maxX, maxZ;
            if (site != null) { key = site.key(); minX = site.minX(); minZ = site.minZ(); maxX = site.maxX(); maxZ = site.maxZ(); }
            else if (bell != null) {
                key = "bell@" + bell.getX() + "," + bell.getY() + "," + bell.getZ();
                minX = bell.getX() - BELL_RADIUS; minZ = bell.getZ() - BELL_RADIUS; maxX = bell.getX() + BELL_RADIUS; maxZ = bell.getZ() + BELL_RADIUS;
            } else continue;
            Found zone = found.computeIfAbsent(key, k -> { Found f = new Found(); f.minX = minX; f.minZ = minZ; f.maxX = maxX; f.maxZ = maxZ; return f; });
            if (zone.bell == null && bell != null) zone.bell = bell;
            zone.minY = Math.min(zone.minY, villager.getBlockY()); zone.maxY = Math.max(zone.maxY, villager.getBlockY());
            if (!villager.isBaby()) zone.residents++;
        }
        int margin = bridge.config.defenseZoneMargin;
        List<Zone> list = new ArrayList<>();
        for (Map.Entry<String, Found> entry : found.entrySet()) {
            Found f = entry.getValue();
            Box box = new Box(f.minX - margin, f.minY - 24, f.minZ - margin, f.maxX + 1 + margin, f.maxY + 24, f.maxZ + 1 + margin);
            BlockPos center = f.bell != null ? f.bell : BlockPos.ofFloored(box.getCenter());
            ConversationStore.Village village = bridge.villageAt(world, center);
            boolean golem = !world.getEntitiesByClass(IronGolemEntity.class, box, g -> g.isAlive() && !g.isRemoved()).isEmpty();
            list.add(new Zone(dimension(world) + "|" + entry.getKey(), box, center, f.bell, village == null ? "" : village.id(),
                    village == null || village.name() == null ? "" : village.name(), golem, f.minX, f.minZ, f.maxX, f.maxZ, f.residents));
        }
        zones.put(dimension(world), list);
    }

    private static BlockPos bell(ServerWorld world, VillagerEntity villager) {
        if (!MobActionController.registered(villager, MemoryModuleType.MEETING_POINT)) return null;
        Optional<GlobalPos> point = villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.MEETING_POINT);
        return point.isPresent() && point.get().dimension() == world.getRegistryKey() ? point.get().pos() : null;
    }

    // ---- The tick ----

    void tick(ServerWorld world) {
        long time = world.getTime();
        if (time % ZONE_PERIOD == ZONE_PHASE) refreshZones(world);
        if (time % GOLEM_PERIOD == GOLEM_PHASE) golems(world);
        if (!enabled()) { if (!alarms.isEmpty()) clearRuntime(); return; }
        towers.tick(world, zones.getOrDefault(dimension(world), List.of()));
        guards.tick(world, zones.getOrDefault(dimension(world), List.of()));
        if (time % SCAN_PERIOD != SCAN_PHASE) return;
        VillageAlarm.Rules rules = rules();
        String dimension = dimension(world);
        for (Zone zone : zones.getOrDefault(dimension, List.of())) scan(world, zone, time, rules);
        for (VillageAlarm.Alarm ended : alarms.tick(dimension + "|", time, rules)) allClear(world, ended);
        for (Zone zone : zones.getOrDefault(dimension, List.of())) {
            VillageAlarm.Alarm alarm = alarms.get(zone.key());
            if (alarm != null) respond(world, zone, alarm, time);
        }
        repairs(world, time);
    }

    /** Monsters a resident sees, the targets still about, a raid at the bell. */
    private void scan(ServerWorld world, Zone zone, long now, VillageAlarm.Rules rules) {
        List<MobEntity> monsters = world.getEntitiesByClass(MobEntity.class, zone.box().expand(bridge.config.archerRange / 2.0), this::threat);
        if (!monsters.isEmpty()) {
            List<LivingEntity> watchers = new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isSleeping()));
            watchers.addAll(world.getEntitiesByClass(IronGolemEntity.class, zone.box(), LivingEntity::isAlive));
            double usual = bridge.config.alarmMonsterRange;
            int seen = 0; MobEntity first = null;
            for (MobEntity monster : monsters) {
                for (LivingEntity watcher : watchers) {
                    double range = towers.sight(watcher, usual);
                    if (watcher.squaredDistanceTo(monster) <= range * range && watcher.canSee(monster)) { seen++; if (first == null) first = monster; break; }
                }
            }
            if (seen > 0) {
                VillageAlarm.Change change = alarms.monsters(zone.key(), seen, "монстры у деревни: " + seen + " (" + first.getName().getString() + ")", now, rules);
                announce(world, zone, change, null, first);
            }
        }
        if (world.hasRaidAt(zone.center())) announce(world, zone, alarms.attacked(zone.key(), "рейд", now), null, null);
        VillageAlarm.Alarm alarm = alarms.get(zone.key());
        if (alarm == null) return;
        Box reach = zone.box().expand(bridge.config.alarmPlayerRange);
        for (UUID id : List.copyOf(alarm.targets.keySet())) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
            if (player != null && player.getWorld() == world && player.isAlive() && reach.contains(player.getPos())) alarms.seen(zone.key(), id, now);
        }
    }

    /** A monster worth an alarm: a hostile that is not a calm neutral, not in a conversation or on a player's errand. */
    boolean threat(MobEntity mob) {
        if (!mob.isAlive() || mob.isRemoved() || !(mob instanceof Monster)) return false;
        if (mob instanceof Angerable && mob.getTarget() == null) return false;
        return !bridge.actions.busy(mob);
    }

    /** While the alarm lasts: golems fight, and in an attack the bell rings and the villagers hide. */
    private void respond(ServerWorld world, Zone zone, VillageAlarm.Alarm alarm, long now) {
        List<ServerPlayerEntity> enemies = new ArrayList<>();
        if (bridge.config.alarmAgainstPlayers)
            for (UUID id : alarm.targets.keySet()) {
                ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
                if (player != null && player.getWorld() == world && player.isAlive() && !player.isCreative() && !player.isSpectator()
                        && zone.box().expand(8).contains(player.getPos())) enemies.add(player);
            }
        for (IronGolemEntity golem : world.getEntitiesByClass(IronGolemEntity.class, zone.box(), g -> g.isAlive() && !g.isRemoved())) {
            LivingEntity current = golem.getTarget();
            if (current != null && current.isAlive()) continue;
            LivingEntity foe = null; double nearest = GOLEM_REACH * GOLEM_REACH;
            for (MobEntity monster : world.getEntitiesByClass(MobEntity.class, golem.getBoundingBox().expand(GOLEM_REACH), this::threat)) {
                double distance = golem.squaredDistanceTo(monster);
                if (distance < nearest && golem.canTarget(monster)) { nearest = distance; foe = monster; }
            }
            if (foe == null && !golem.isPlayerCreated())
                for (ServerPlayerEntity player : enemies) {
                    double distance = golem.squaredDistanceTo(player);
                    if (distance < nearest && golem.canTarget(player)) { nearest = distance; foe = player; }
                }
            if (foe == null) continue;
            if (foe instanceof ServerPlayerEntity player) { golem.setAngryAt(player.getUuid()); golem.chooseRandomAngerTime(); }
            golem.setTarget(foe);
        }
        if (!alarm.attack() && enemies.isEmpty()) return;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isRemoved()))
            if (MobActionController.registered(villager, MemoryModuleType.HEARD_BELL_TIME)) villager.getBrain().remember(MemoryModuleType.HEARD_BELL_TIME, now);
        Long rung = lastBell.get(zone.key());
        if (zone.bell() != null && (rung == null || now - rung >= BELL_TICKS) && world.isChunkLoaded(zone.bell())
                && world.getBlockState(zone.bell()).getBlock() instanceof BellBlock bell) {
            lastBell.put(zone.key(), now);
            bell.ring(null, world, zone.bell(), null);
        }
    }

    // ---- Hooks from the bridge ----

    /** A villager or golem took a blow and lived. */
    void hurt(LivingEntity victim, DamageSource source) {
        if (!enabled() || !(victim.getWorld() instanceof ServerWorld world) || !resident(victim)) return;
        Zone zone = zoneAt(world, victim.getBlockPos());
        if (zone == null) return;
        long now = world.getTime();
        String name = name(victim);
        if (source.getAttacker() instanceof ServerPlayerEntity player) againstPlayer(world, zone, player, VillageAlarm.Level.ATTACK, "ударил " + name);
        else if (source.getAttacker() instanceof Monster && source.getAttacker() instanceof MobEntity monster)
            announce(world, zone, alarms.attacked(zone.key(), monster.getName().getString() + " напал на " + name, now), null, monster);
    }

    /** Someone died in a village: a resident is mourned in the alarm's account, a monster counted as killed. */
    void died(LivingEntity dead, DamageSource source) {
        if (!enabled() || !(dead.getWorld() instanceof ServerWorld world)) return;
        if (dead instanceof ServerPlayerEntity player) { alarms.release(player.getUuid()); return; }
        Zone zone = zoneAt(world, dead.getBlockPos());
        if (zone == null) return;
        if (dead instanceof Monster) { alarms.killed(zone.key()); return; }
        if (!resident(dead)) return;
        String name = name(dead);
        if (source.getAttacker() instanceof ServerPlayerEntity player) againstPlayer(world, zone, player, VillageAlarm.Level.ATTACK, "убил " + name);
        else if (source.getAttacker() instanceof Monster && source.getAttacker() instanceof MobEntity monster)
            announce(world, zone, alarms.attacked(zone.key(), monster.getName().getString() + " убил " + name, world.getTime()), null, monster);
        alarms.lost(zone.key(), name);
    }

    /** A villager who hates the player saw them and ran (MobActionController's stance): the village is warned against them. */
    void hated(VillagerEntity villager, ServerPlayerEntity player) {
        if (!enabled() || !(villager.getWorld() instanceof ServerWorld world)) return;
        Zone zone = zoneAt(world, villager.getBlockPos());
        if (zone != null) againstPlayer(world, zone, player, VillageAlarm.Level.THREAT, "его тут ненавидят");
    }

    /** An exile walks in the village (VillageController): a threat at once, an attack if they stay. */
    void exile(ServerWorld world, ServerPlayerEntity player) {
        if (!enabled()) return;
        Zone zone = zoneAt(world, player.getBlockPos());
        if (zone == null) return;
        String key = zone.key() + "|" + player.getUuid();
        long now = world.getTime();
        Long since = exileSince.get(key);
        VillageAlarm.Alarm alarm = alarms.get(zone.key());
        if (since == null || alarm == null || !alarm.against(player.getUuid())) { exileSince.put(key, now); since = now; }
        againstPlayer(world, zone, player, now - since >= EXILE_ATTACK_TICKS ? VillageAlarm.Level.ATTACK : VillageAlarm.Level.THREAT,
                now - since >= EXILE_ATTACK_TICKS ? "изгнанник не уходит" : "изгнанник в деревне");
    }

    /** No trade while the village is under attack, and none for a player the alarm is against. */
    boolean refusesTrade(VillagerEntity villager, ServerPlayerEntity player) {
        if (!enabled() || !(villager.getWorld() instanceof ServerWorld world)) return false;
        Zone zone = zoneAt(world, villager.getBlockPos());
        VillageAlarm.Alarm alarm = zone == null ? null : alarms.get(zone.key());
        return alarm != null && (alarm.attack() || alarm.against(player.getUuid()));
    }

    /** /mobtalk village forgive: the village where the player stands drops its alarm against them, and its golems their anger. */
    void forgive(ServerPlayerEntity player) {
        Zone zone = zoneAt(player.getServerWorld(), player.getBlockPos());
        if (zone == null || !alarms.release(zone.key(), player.getUuid())) return;
        for (IronGolemEntity golem : player.getServerWorld().getEntitiesByClass(IronGolemEntity.class, zone.box(), g -> g.isAlive() && !g.isRemoved())) {
            if (player.getUuid().equals(golem.getAngryAt())) golem.stopAnger();
            if (golem.getTarget() == player) golem.setTarget(null);
        }
        player.sendMessage(Text.translatable("mobtalk.defense.released", zone.title()).formatted(Formatting.GREEN), false);
    }

    private void againstPlayer(ServerWorld world, Zone zone, ServerPlayerEntity player, VillageAlarm.Level level, String cause) {
        if (!bridge.config.alarmAgainstPlayers || player.isCreative() || player.isSpectator()) return;
        VillageAlarm.Change change = alarms.player(zone.key(), player.getUuid(), MobGameBridge.playerName(player), level, cause, world.getTime());
        announce(world, zone, change, player, null);
    }

    // ---- Words ----

    /** A new or worse alarm: a villager shouts, and for an attack or a player the village's players hear of it. */
    private void announce(ServerWorld world, Zone zone, VillageAlarm.Change change, ServerPlayerEntity enemy, Entity threat) {
        if (change == VillageAlarm.Change.NONE) return;
        VillageAlarm.Alarm alarm = alarms.get(zone.key());
        if (alarm == null) return;
        Entity near = enemy != null ? enemy : threat;
        String kind = enemy != null ? "player" : alarm.attack() ? "attack" : "monsters";
        if (change == VillageAlarm.Change.RAISED || change == VillageAlarm.Change.ESCALATED) towers.horn(world, zone, near == null ? zone.center() : near.getBlockPos());
        shout(world, zone, near == null ? zone.center() : near.getBlockPos(), kind, enemy == null ? null : MobGameBridge.playerName(enemy));
        if (enemy != null) enemy.sendMessage(Text.translatable("mobtalk.defense.raised.you", zone.title(), alarm.targets.get(enemy.getUuid()).cause).formatted(Formatting.RED), false);
        if (!bridge.config.alarmChat || (!alarm.attack() && enemy == null)) return;
        MutableText line = enemy != null ? Text.translatable("mobtalk.defense.raised.player", zone.title(), MobGameBridge.playerName(enemy), alarm.targets.get(enemy.getUuid()).cause)
                : Text.translatable(alarm.attack() ? "mobtalk.defense.raised.attack" : "mobtalk.defense.raised.monsters", zone.title(), alarm.cause);
        for (ServerPlayerEntity player : listeners(world, zone)) if (player != enemy) player.sendMessage(line.copy().formatted(Formatting.GOLD), false);
    }

    /** The alarm is over: the all-clear, and the residents who know the player remember how it went. */
    private void allClear(ServerWorld world, VillageAlarm.Alarm alarm) {
        exileSince.keySet().removeIf(key -> key.startsWith(alarm.village + "|"));
        lastBell.remove(alarm.village);
        Zone zone = null;
        for (Zone candidate : zones.getOrDefault(dimension(world), List.of())) if (candidate.key().equals(alarm.village)) zone = candidate;
        if (zone == null) return;
        shout(world, zone, zone.center(), "clear", null);
        boolean serious = alarm.attack() || !alarm.lost.isEmpty();
        if (bridge.config.alarmChat && serious)
            for (ServerPlayerEntity player : listeners(world, zone))
                player.sendMessage(Text.translatable("mobtalk.defense.clear", zone.title(), alarm.summary()).formatted(Formatting.GREEN), false);
        if (!serious && alarm.targets.isEmpty() && alarm.killed == 0) return;
        String memory = "Была тревога в деревне (" + (alarm.cause.isBlank() ? "угроза" : alarm.cause) + "). " + alarm.summary();
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby()))
            if (bridge.memory.known(villager.getUuid())) bridge.event(villager, memory);
    }

    /** One pre-written line from the villager nearest the spot, to the players within earshot; no request to a model. */
    private void shout(ServerWorld world, Zone zone, BlockPos at, String kind, String playerName) {
        long now = world.getTime();
        String key = zone.key() + "|" + kind;
        Long last = lastShout.get(key);
        if (last != null && now - last < SHOUT_TICKS) return;
        // At night the village sleeps: an alarm wakes the nearest villager to shout it, when nobody is awake (the all-clear wakes nobody).
        boolean wake = !kind.equals("clear");
        VillagerEntity speaker = null; double nearest = Double.MAX_VALUE;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby() && (wake || !v.isSleeping()))) {
            double distance = villager.getBlockPos().getSquaredDistance(at) + (villager.isSleeping() ? 1e9 : 0);
            if (distance < nearest) { nearest = distance; speaker = villager; }
        }
        if (speaker == null) return;
        if (speaker.isSleeping()) speaker.wakeUp();
        lastShout.put(key, now);
        String name = bridge.memory.displayName(speaker.getUuid());
        if (name == null || name.isBlank()) name = speaker.getName().getString();
        String lineKey = "mobtalk.defense.shout." + kind + "." + random.nextInt(3);
        Text line = Text.literal("<" + name + "> ").append(playerName == null ? Text.translatable(lineKey) : Text.translatable(lineKey, playerName));
        for (ServerPlayerEntity player : world.getPlayers()) if (player.squaredDistanceTo(speaker) <= 32 * 32) player.sendMessage(line, false);
    }

    /** Who hears of the village's alarm: players near it, and those the village counts as its own wherever they are in this world. */
    private List<ServerPlayerEntity> listeners(ServerWorld world, Zone zone) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        double range = bridge.config.alarmNoticeRange;
        long millis = System.currentTimeMillis();
        for (ServerPlayerEntity player : world.getPlayers()) {
            boolean near = player.getBlockPos().getSquaredDistance(zone.center()) <= range * range;
            boolean own = !zone.villageId().isBlank() && bridge.config.socialLayer
                    && bridge.memory.social().standing(zone.villageId(), player.getUuid(), millis).rank().ordinal() >= VillageStanding.Rank.FRIEND.ordinal();
            if (near || own) out.add(player);
        }
        return out;
    }

    // ---- The golem ----

    /** Every village golem gets its health from the settings; out of a fight it heals a little; a wounded one is mended by a smith. */
    private void golems(ServerWorld world) {
        boolean on = enabled();
        for (Zone zone : zones.getOrDefault(dimension(world), List.of())) {
            VillageAlarm.Alarm alarm = alarms.get(zone.key());
            for (IronGolemEntity golem : world.getEntitiesByClass(IronGolemEntity.class, zone.box(), g -> g.isAlive() && !g.isRemoved())) {
                sturdy(golem);
                if (!on) continue;
                boolean calm = golem.getTarget() == null && golem.age - golem.getLastAttackedTime() > 200;
                if (calm && golem.getHealth() < golem.getMaxHealth() && bridge.config.golemRegenPerMinute > 0)
                    golem.heal(bridge.config.golemRegenPerMinute * GOLEM_PERIOD / 1200f);
                if (calm && (alarm == null || !alarm.attack()) && bridge.config.golemRepairHealth > 0 && golem.getHealth() < golem.getMaxHealth() * 0.6f)
                    callSmith(world, zone, golem);
            }
        }
    }

    /** The golem's health modifier as the settings say: a village golem only, none for one the player built. */
    void sturdy(IronGolemEntity golem) {
        EntityAttributeInstance health = golem.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health == null) return;
        double value = golem.isPlayerCreated() || !enabled() ? 0 : bridge.config.golemHealthPercent / 100.0 - 1;
        EntityAttributeModifier existing = health.getModifier(GOLEM_HEALTH);
        if (existing == null ? value <= 0 : existing.value() == value) return;
        boolean full = golem.getHealth() >= golem.getMaxHealth();
        health.removeModifier(GOLEM_HEALTH);
        if (value > 0) health.addPersistentModifier(new EntityAttributeModifier(GOLEM_HEALTH, value, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        if (full && existing == null) golem.setHealth(golem.getMaxHealth());
        else if (golem.getHealth() > golem.getMaxHealth()) golem.setHealth(golem.getMaxHealth());
    }

    private void callSmith(ServerWorld world, Zone zone, IronGolemEntity golem) {
        for (Repair repair : repairs.values()) if (repair.golem() == golem) return;
        long now = world.getTime();
        VillagerEntity smith = null; double nearest = SMITH_REACH * SMITH_REACH;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby() && !v.isSleeping()
                && SMITHS.contains(v.getVillagerData().getProfession()))) {
            Long rest = smithRest.get(villager.getUuid());
            if (rest != null && now - rest < SMITH_REST_TICKS || repairs.containsKey(villager.getUuid()) || bridge.actions.busy(villager)) continue;
            double distance = villager.squaredDistanceTo(golem);
            if (distance < nearest) { nearest = distance; smith = villager; }
        }
        if (smith != null) repairs.put(smith.getUuid(), new Repair(smith, golem, now + REPAIR_TIMEOUT_TICKS));
    }

    /** Smiths on their way: they walk to the golem, and at arm's length mend it with an ingot. */
    private void repairs(ServerWorld world, long now) {
        for (Iterator<Repair> it = repairs.values().iterator(); it.hasNext(); ) {
            Repair repair = it.next();
            VillagerEntity smith = repair.smith(); IronGolemEntity golem = repair.golem();
            if (smith.getWorld() != world) continue;
            if (now > repair.until() || !smith.isAlive() || !golem.isAlive() || smith.isSleeping() || golem.getHealth() >= golem.getMaxHealth()) { it.remove(); continue; }
            if (smith.squaredDistanceTo(golem) <= 3 * 3) {
                golem.heal(bridge.config.golemRepairHealth);
                world.playSound(null, golem.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_REPAIR, SoundCategory.NEUTRAL, 1f, 1f);
                smith.swingHand(Hand.MAIN_HAND);
                smithRest.put(smith.getUuid(), now);
                if (bridge.memory.known(smith.getUuid())) bridge.event(smith, "Починил голема железом");
                it.remove();
                continue;
            }
            if (smith.squaredDistanceTo(golem) <= 6 * 6 && golem.getTarget() == null) golem.getNavigation().stop();
            if (MobActionController.registered(smith, MemoryModuleType.WALK_TARGET))
                smith.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityLookTarget(golem, false), 0.6F, 2), 40);
            if (MobActionController.registered(smith, MemoryModuleType.LOOK_TARGET))
                smith.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(golem, true), 40);
        }
    }

    // ---- Helpers ----

    private static boolean resident(LivingEntity entity) { return entity instanceof VillagerEntity || entity instanceof IronGolemEntity; }

    private String name(LivingEntity entity) {
        String name = bridge.memory.displayName(entity.getUuid());
        return name == null || name.isBlank() ? entity.getName().getString() : name;
    }

    void clearRuntime() {
        alarms.clear(); zones.clear(); lastBell.clear(); lastShout.clear(); exileSince.clear(); repairs.clear(); smithRest.clear();
        towers.clearRuntime(); guards.clearRuntime();
    }
}
