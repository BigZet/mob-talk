package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Notices, on the integrated server, the occasions for a creature to speak first ({@link Initiative}): once a second it looks at
 * the acquainted, free, peaceful creatures within earshot of the player, gathers the facts the rules need, and hands at most one
 * occasion at a time to the client, within the budgets. The client decides (rules or Jev, off the game thread) and voices it. */
public final class InitiativeController {
    private static final long PERIOD = 20, PHASE = 11;
    /** How long a block change nearby still counts as "just built". */
    private static final long BUILDING_MILLIS = 3 * 60_000L;
    /** How far a creature notices building, and how far from the player a creature counts as met. */
    private static final double BUILDING_RANGE = 12, MET_RANGE = 16;
    private final MobGameBridge bridge;
    private final Initiative.Budget budget = new Initiative.Budget();
    /** When the player was last near each creature this session (real millis). */
    private final Map<UUID, Long> lastNear = new HashMap<>();
    /** The occasion waiting for the client, if any. */
    private final ConcurrentLinkedQueue<Initiative.Cue> cues = new ConcurrentLinkedQueue<>();
    /** Set by the client: the player is recording, a reply is in flight or a voice plays — no occasion is raised meanwhile. */
    public volatile boolean playerBusy;
    private static final class Sky { boolean night, rain, thunder; String change = ""; long changedMillis = -1; }
    private final Map<RegistryKey<World>, Sky> skies = new HashMap<>();

    InitiativeController(MobGameBridge bridge) { this.bridge = bridge; }

    /** The next occasion for the client, or null. */
    public Initiative.Cue poll() { return cues.poll(); }
    /** The creature chose silence: the world's minute is given back so another may speak sooner. */
    public synchronized void refund(Initiative.Cue cue) { if (cue != null) budget.refund(cue.createdMillis()); }

    void tick(ServerWorld world) {
        if (!bridge.config.enabled || !bridge.config.initiative || world.getTime() % PERIOD != PHASE) return;
        long now = System.currentTimeMillis();
        Sky sky = watchSky(world, now);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            List<MobEntity> around = world.getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(MET_RANGE),
                    mob -> mob.isAlive() && !mob.isRemoved() && !mob.isAiDisabled() && bridge.memory.known(mob.getUuid()));
            for (MobEntity mob : around) {
                double distance = mob.distanceTo(player);
                Long met = lastNear.get(mob.getUuid());
                long lastMet = met == null ? bridge.memory.lastTurnMillis(mob.getUuid()) : Math.max(met, bridge.memory.lastTurnMillis(mob.getUuid()));
                if (distance <= MET_RANGE) lastNear.put(mob.getUuid(), now);
                if (playerBusy || !cues.isEmpty() || !bridge.memory.acquainted(mob.getUuid())) continue;
                Initiative.Facts facts = facts(world, mob, player, distance, lastMet, sky, now);
                Initiative.Trigger trigger = Initiative.detect(facts, now);
                if (trigger == null) continue;
                synchronized (this) {
                    if (!budget.allows(mob.getUuid(), bridge.config.initiativesPerMinute, now)) continue;
                    budget.spent(mob.getUuid(), now);
                }
                cues.add(new Initiative.Cue(mob.getUuid(), facts.name(), trigger, Initiative.reason(trigger, facts), partners(facts, trigger), now));
                if (trigger == Initiative.Trigger.WEATHER) sky.changedMillis = -1; // One remark per change in a world.
            }
        }
    }

    private Initiative.Facts facts(ServerWorld world, MobEntity mob, ServerPlayerEntity player, double distance, long lastMet, Sky sky, long now) {
        UUID id = mob.getUuid(), playerId = player.getUuid();
        boolean busy = bridge.actions.busy(mob) || mob.getTarget() != null || mob instanceof PathAwareEntity aware && aware.isPanicking() || mob.hasVehicle();
        boolean hostile = SpeciesCatalog.get(MobGameBridge.type(mob)).hostile();
        MobState.View state = bridge.memory.stateView(id, playerId);
        boolean thought = state != null && !state.thought().isEmpty();
        boolean deal = bridge.memory.favor(id, playerId) != null || bridge.memory.bargain(id, playerId) != null;
        List<Initiative.Candidate> friends = new ArrayList<>();
        for (MobRelations.View bond : bridge.memory.bonds(id, now)) {
            if (!bond.friend() || bond.mourns()) continue;
            if (!(world.getEntity(bond.other()) instanceof MobEntity friend) || !friend.isAlive() || friend.isRemoved() || friend.isAiDisabled()) continue;
            if (bridge.actions.busy(friend) || friend.getTarget() != null || !bridge.memory.acquainted(friend.getUuid())) continue;
            double apart = mob.distanceTo(friend);
            if (apart <= Initiative.FRIEND_RANGE && friend.distanceTo(player) <= Initiative.HEAR_RANGE)
                friends.add(new Initiative.Candidate(friend.getUuid(), name(friend), bond.score(), apart));
        }
        int blocks = bridge.changes.countNear(world, mob.getBlockPos(), BUILDING_RANGE, BUILDING_MILLIS);
        String name = name(mob);
        return new Initiative.Facts(id, name, true, hostile, busy, bridge.memory.relationship(id, playerId), distance, lastMet, thought, deal, friends, blocks,
                sky.changedMillis < 0 ? "" : sky.change, sky.changedMillis);
    }
    /** The friend addressed first, then the rest: the scene's cast. */
    private static List<Initiative.Candidate> partners(Initiative.Facts facts, Initiative.Trigger trigger) {
        if (trigger != Initiative.Trigger.FRIENDS) return List.of();
        return facts.friendsNearby().stream().sorted(Comparator.comparingDouble(Initiative.Candidate::distance)).limit(3).toList();
    }
    private String name(MobEntity mob) { String given = bridge.memory.displayName(mob.getUuid()); return given.isBlank() ? mob.getName().getString() : given; }

    /** Night, rain and thunder as they set in: the moment and the word for it. */
    private Sky watchSky(ServerWorld world, long now) {
        Sky sky = skies.computeIfAbsent(world.getRegistryKey(), ignored -> { Sky s = new Sky(); s.night = world.isNight(); s.rain = world.isRaining(); s.thunder = world.isThundering(); return s; });
        boolean night = world.isNight(), rain = world.isRaining(), thunder = world.isThundering();
        String change = thunder && !sky.thunder ? "гроза" : rain && !sky.rain ? "дождь" : night && !sky.night ? "ночь" : !night && sky.night ? "рассвет" : "";
        if (!change.isEmpty()) { sky.change = change; sky.changedMillis = now; }
        else if (sky.changedMillis >= 0 && now - sky.changedMillis > Initiative.WEATHER_FRESH_MILLIS) sky.changedMillis = -1;
        sky.night = night; sky.rain = rain; sky.thunder = thunder;
        return sky;
    }

    void unload(MobEntity mob) { lastNear.remove(mob.getUuid()); synchronized (this) { budget.forget(mob.getUuid()); } }
    void clearRuntime() { lastNear.clear(); cues.clear(); skies.clear(); synchronized (this) { budget.clear(); } }
}
