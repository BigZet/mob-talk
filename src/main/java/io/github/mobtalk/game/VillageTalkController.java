package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Village talk on the integrated server: finds two villagers of one village standing together within the player's earshot and
 * hands the pair to the client ({@link VillageChatter}); picks a villager the player knows to write to the chat ({@link VillageNotices});
 * and, when the player comes to a villager who called for work, turns the call into a favour and lets the villager explain it
 * aloud (a game-raised initiative). Deterministic, a few checks every few seconds, within the budgets. Server thread only. */
public final class VillageTalkController {
    private static final long CHATTER_PERIOD = 100, CHATTER_PHASE = 37, NOTICE_PERIOD = 400, NOTICE_PHASE = 71, ARRIVE_PERIOD = 20, ARRIVE_PHASE = 13;
    /** How long the two keep facing each other once a talk is handed over: enough for three voiced lines. */
    private static final int HOLD_TICKS = 300;
    private final MobGameBridge bridge;
    private final Random random = new Random();
    private final VillageChatter.Budget budget = new VillageChatter.Budget();
    /** When each village last had an announcement (runtime: a restart may give one sooner). */
    private final Map<String, Long> noticed = new HashMap<>();
    private final ConcurrentLinkedQueue<VillageChatter.Cue> talks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<VillageNotices.Cue> notices = new ConcurrentLinkedQueue<>();
    /** Set by the client while a talk is being written or played: no new one is raised meanwhile. */
    public volatile boolean talking;

    VillageTalkController(MobGameBridge bridge) { this.bridge = bridge; }

    /** The next talk for the client, or null. */
    public VillageChatter.Cue pollTalk() { return talks.poll(); }
    /** The client dropped this talk unheard (a speaker out of earshot): its budget is given back on the next server tick. */
    public void refund(VillageChatter.Cue cue) { refunds.add(cue); }
    private final ConcurrentLinkedQueue<VillageChatter.Cue> refunds = new ConcurrentLinkedQueue<>();
    /** A pair is queued for the client to write. */
    public boolean waiting() { return !talks.isEmpty(); }
    /** The next announcement for the client, or null. */
    public VillageNotices.Cue pollNotice() { return notices.poll(); }

    void tick(ServerWorld world) {
        if (!bridge.config.enabled) return;
        long time = world.getTime();
        for (VillageChatter.Cue cue; (cue = refunds.poll()) != null; )
            budget.refund(cue.input().villageKey(), cue.input().a().id(), cue.input().b().id(), cue.createdMillis());
        if (bridge.config.villageChatter && time % CHATTER_PERIOD == CHATTER_PHASE) chatter(world);
        if (bridge.config.villageNotices && time % NOTICE_PERIOD == NOTICE_PHASE) notice(world);
        if (time % ARRIVE_PERIOD == ARRIVE_PHASE) arrivals(world);
    }

    // ---- Small talk. ----

    private void chatter(ServerWorld world) {
        if (talking || !talks.isEmpty() || bridge.initiative.playerBusy || bridge.wedding.active()) return;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            double range = bridge.config.villageChatterRange;
            List<VillagerEntity> around = world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(range),
                    v -> free(v) && v.squaredDistanceTo(player) <= range * range);
            Collections.shuffle(around, random);
            for (int i = 0; i < around.size(); i++) for (int j = i + 1; j < around.size(); j++) {
                VillagerEntity a = around.get(i), b = around.get(j);
                if (a.distanceTo(b) > VillageChatter.PAIR_RANGE || !a.canSee(b) || !b.canSee(a)) continue;
                ConversationStore.Village village = village(world, a), other = village(world, b);
                String key = key(world, a, village);
                if (!key.equals(key(world, b, other))) continue;
                if (!budget.allows(key, a.getUuid(), b.getUuid(), bridge.config.villageChatterSeconds, now)) continue;
                budget.spent(key, a.getUuid(), b.getUuid(), now);
                VillageChatter.Input input = bridge.memory.chatterInput(seat(a), seat(b), key, village == null ? "" : village.name(),
                        MobGameBridge.playerName(player), weather(world), random::nextDouble, now);
                face(a, b); face(b, a);
                talks.add(new VillageChatter.Cue(input, now));
                return;
            }
        }
    }
    /** A talk between two villagers sitting in the bar ({@link BarEvenings}), its pace kept by the bar; false while the floor is taken. */
    boolean barTalk(ServerWorld world, VillagerEntity a, VillagerEntity b, ServerPlayerEntity player) {
        if (talking || !talks.isEmpty() || bridge.initiative.playerBusy || bridge.wedding.active()) return false;
        long now = System.currentTimeMillis();
        ConversationStore.Village village = village(world, a);
        VillageChatter.Input input = bridge.memory.chatterInput(seat(a), seat(b), key(world, a, village), village == null ? "" : village.name(),
                MobGameBridge.playerName(player), "вечер", random::nextDouble, now, true);
        talks.add(new VillageChatter.Cue(input, now));
        return true;
    }
    private static VillageChatter.Seat seat(VillagerEntity villager) { return new VillageChatter.Seat(villager.getUuid(), MobGameBridge.type(villager), villager.getName().getString()); }
    /** A villager that may talk: grown, awake, not fleeing, fighting, riding, trading or held by the player's conversation. */
    private boolean free(VillagerEntity villager) {
        return villager.isAlive() && !villager.isRemoved() && !villager.isAiDisabled() && !villager.isBaby() && !villager.isSleeping() && !villager.isPanicking()
                && villager.getTarget() == null && !villager.hasVehicle() && !villager.hasCustomer() && !bridge.actions.busy(villager) && !bridge.wedding.involved(villager.getUuid())
                && !bridge.confession.hushes(villager); // Б4: у исповеди жители молчат.
    }
    /** The known village of this villager: by its village structure when the option reads sites, else the nearest within the radius. */
    private ConversationStore.Village village(ServerWorld world, VillagerEntity villager) {
        String dimension = world.getRegistryKey().getValue().toString();
        ConversationStore.Site site = bridge.config.villageSites ? VillageSites.of(world, villager) : null;
        return site != null ? bridge.memory.villageFor(dimension, villager.getBlockX(), villager.getBlockZ(), site)
                : bridge.memory.villageNear(dimension, villager.getBlockX(), villager.getBlockZ());
    }
    /** The budget key of a villager's talk: its known village, else its village structure, else a square of the village radius. */
    private String key(ServerWorld world, VillagerEntity villager, ConversationStore.Village village) {
        if (village != null) return village.id();
        ConversationStore.Site site = bridge.config.villageSites ? VillageSites.of(world, villager) : null;
        return site != null ? "site:" + site.key() : area(world.getRegistryKey().getValue().toString(), villager);
    }
    /** A village the world memory has not met yet still keeps its talk apart from the next one: a square of the village radius. */
    private static String area(String dimension, VillagerEntity villager) {
        int size = ConversationStore.VILLAGE_RADIUS;
        return "area:" + dimension + ":" + Math.floorDiv(villager.getBlockX(), size) + ":" + Math.floorDiv(villager.getBlockZ(), size);
    }
    private static String weather(ServerWorld world) { return world.isThundering() ? "гроза" : world.isRaining() ? "дождь" : world.isNight() ? "ночь" : "день"; }
    /** Stops and turns to the other for the talk; the brain may walk off afterwards. */
    private static void face(VillagerEntity villager, VillagerEntity other) {
        villager.getNavigation().stop();
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(other, true), HOLD_TICKS);
        villager.getLookControl().lookAt(other, 30f, 30f);
    }

    // ---- Announcements. ----

    private void notice(ServerWorld world) {
        if (bridge.wedding.active()) return;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            double range = bridge.config.noticeRange;
            ConversationStore.Village here = bridge.villageAt(world, player.getBlockPos());
            List<VillagerEntity> known = new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(Math.max(range, ConversationStore.VILLAGE_RADIUS)),
                    v -> v.isAlive() && !v.isRemoved() && !v.isBaby() && bridge.memory.acquainted(v.getUuid())
                            && (v.squaredDistanceTo(player) <= range * range || here != null && here.id().equals(bridge.memory.villageOf(v.getUuid())))));
            Collections.shuffle(known, random);
            for (VillagerEntity villager : known) {
                UUID id = villager.getUuid(), playerId = player.getUuid();
                String key = bridge.memory.villageOf(id).isBlank() ? key(world, villager, null) : bridge.memory.villageOf(id);
                Long last = noticed.get(key);
                if (last != null && now - last < bridge.config.noticeMinutes * 60_000L) continue;
                long own = bridge.memory.lastNotice(id);
                if (own >= 0 && now - own < VillageNotices.MOB_COOLDOWN_MILLIS) continue;
                if (bridge.memory.relationship(id, playerId) < 0 || bridge.memory.invited(id, playerId, now)) continue;
                VillageNotices.Kind kind = VillageNotices.kind(bridge.memory.favor(id, playerId) != null, bridge.config.noticeWorkPercent, random.nextDouble());
                VillageNotices.Work work = kind == VillageNotices.Kind.WORK ? work(villager, playerId) : null;
                if (kind == VillageNotices.Kind.WORK && work == null) kind = VillageNotices.Kind.NEWS;
                VillageNotices.Input input = bridge.memory.noticeInput(id, playerId, MobGameBridge.playerName(player), kind, work, now);
                if (input == null) continue;
                noticed.put(key, now);
                notices.add(new VillageNotices.Cue(input, now));
                return;
            }
        }
    }
    /** The work a villager asks for: one favour candidate, and a discount on one of its open deals. */
    private VillageNotices.Work work(VillagerEntity villager, UUID player) {
        for (FavorItems.Candidate candidate : FavorItems.draw(3, random)) {
            Item item = MobTrading.item(candidate.item());
            if (item == null) continue;
            String type = MobGameBridge.type(villager);
            List<TradeDeal> open = TradeCatalog.deals(type).stream()
                    .filter(deal -> !deal.secret() && Trading.visible(deal, bridge.memory.tradeState(villager.getUuid(), player, deal.id()))).toList();
            TradeDeal reward = open.isEmpty() ? null : open.get(random.nextInt(open.size()));
            return new VillageNotices.Work(candidate.item(), new ItemStack(item).getName().getString(), candidate.count(),
                    reward == null ? "" : reward.id(), reward == null ? "" : reward.name());
        }
        return null;
    }

    // ---- The player answers a call to work. ----

    private void arrivals(ServerWorld world) {
        if (bridge.initiative.playerBusy) return;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            for (VillageNotices.Invitation invitation : bridge.memory.invitations(player.getUuid(), now)) {
                if (!(world.getEntity(invitation.mob()) instanceof VillagerEntity villager) || !villager.isAlive()) continue;
                if (villager.distanceTo(player) > VillageNotices.ARRIVE_RANGE) continue;
                bridge.memory.takeInvitation(invitation.mob(), player.getUuid());
                if (bridge.memory.favor(villager.getUuid(), player.getUuid()) != null) continue; // Another favour came first; the call lapses.
                String message;
                try { synchronized (bridge.memory) { message = bridge.trading.apply(villager, player, DealIntent.none(), "", invitation.quest()); } }
                catch (RuntimeException refused) { continue; }
                if (!message.isBlank()) player.sendMessage(Text.literal("[Mob Talk] " + message), false);
                Favor favor = bridge.memory.favor(villager.getUuid(), player.getUuid());
                String name = bridge.memory.displayName(villager.getUuid());
                String reason = "ты сам позвал игрока в чате («" + invitation.text() + "»), и он подошёл: объясни ему поручение — принести "
                        + invitation.count() + "× " + invitation.itemName() + (favor == null ? "" : " («" + favor.title() + "»)") + " — и что дашь за это";
                bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), name.isBlank() ? villager.getName().getString() : name, Initiative.Trigger.GIFT, reason, List.of(), now));
                return;
            }
        }
    }

    void unload(UUID mob) { budget.forget(mob); }
    void clearRuntime() { budget.clear(); noticed.clear(); talks.clear(); notices.clear(); talking = false; }
}
