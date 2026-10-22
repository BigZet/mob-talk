package io.github.mobtalk.game;

import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.BargainIntent;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.DealIntent;
import io.github.mobtalk.core.FriendActions;
import io.github.mobtalk.core.GestureIntent;
import io.github.mobtalk.core.Initiative;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.QuestIntent;
import io.github.mobtalk.core.SocialIntent;
import io.github.mobtalk.core.VillageChatter;
import io.github.mobtalk.core.VillageNotices;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Мост без Minecraft для тестов серверной логики (раздел 16 плана): отдаёт заданные снимки и итоги, записывает вызовы.
 * {@code onServer} выполняет работу сразу на вызывающем потоке. Мобы и игроки — {@link #mobs} и {@link #players}: тест ставит их
 * туда с позициями, а расстояния считаются по ним. */
public final class FakeGameBridge implements GameBridge {
    public final ConversationStore memory;
    public volatile MobTalkConfig config;
    public final Map<UUID, MobSnapshot> snapshots = new ConcurrentHashMap<>();
    public final Map<UUID, Outcome> outcomes = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> witnesses = new ConcurrentHashMap<>();
    public final Map<UUID, ConversationStore.Village> villages = new ConcurrentHashMap<>();
    public final Map<UUID, MobView> mobs = new ConcurrentHashMap<>();
    public final Map<UUID, PlayerView> players = new ConcurrentHashMap<>();
    public final Map<UUID, Integer> inHand = new ConcurrentHashMap<>();
    public final Deque<Initiative.Cue> initiatives = new ArrayDeque<>();
    public final Deque<VillageChatter.Cue> talks = new ArrayDeque<>();
    public final Deque<VillageNotices.Cue> notices = new ArrayDeque<>();
    public final List<String> calls = new ArrayList<>();
    public volatile boolean floorBusy, talking, wedding;
    /** Причина, с которой падают все вызовы (моб ушёл, мир сменился); null — всё работает. */
    public volatile String failure;
    /** Что отвечает {@link #afterWords}. */
    public volatile String afterWords = "";

    public FakeGameBridge(ConversationStore memory, MobTalkConfig config) { this.memory = memory; this.config = config; }

    private synchronized void record(String call) { calls.add(call); }
    public synchronized List<String> calls() { return List.copyOf(calls); }
    public synchronized boolean called(String prefix) { return calls.stream().anyMatch(c -> c.startsWith(prefix)); }
    private <T> CompletableFuture<T> result(String call, T value) {
        record(call);
        return failure == null ? CompletableFuture.completedFuture(value) : CompletableFuture.failedFuture(new IllegalStateException(failure));
    }
    private <T> CompletableFuture<T> fail(String call, String why) { record(call); return CompletableFuture.failedFuture(new IllegalStateException(why)); }

    /** Моб с таким сетевым номером на месте; имя — для пакетов и промпта. */
    public MobView addMob(UUID id, int entityId, String name, String type, double x, double y, double z) {
        MobView view = new MobView(entityId, id, name, type, "minecraft:overworld", x, y, z);
        mobs.put(id, view); return view;
    }
    public PlayerView addPlayer(UUID id, String name, double x, double y, double z) {
        PlayerView view = new PlayerView(id, name, "minecraft:overworld", x, y, z, true, null, 0);
        players.put(id, view); return view;
    }

    @Override public ConversationStore memory() { return memory; }
    @Override public MobTalkConfig config() { return config; }
    @Override public CompletableFuture<MobSnapshot> capture(UUID mobId, UUID playerId, String dimension) {
        MobSnapshot snapshot = snapshots.get(mobId);
        if (snapshot == null && failure == null) return fail("capture " + mobId, "Собеседник или игрок недоступен");
        return result("capture " + mobId, snapshot);
    }
    @Override public CompletableFuture<String> apply(UUID mobId, UUID playerId, String dimension, ActionIntent intent, MobSnapshot observed,
            long expectedRevision, BooleanSupplier valid) {
        return applyWithRevision(mobId, playerId, dimension, intent, BargainIntent.none(), DealIntent.none(), "", QuestIntent.none(), SocialIntent.NONE,
                GestureIntent.none(), observed, expectedRevision, valid).thenApply(Outcome::message);
    }
    @Override public CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent, BargainIntent bargain,
            DealIntent deal, String unlock, QuestIntent quest, SocialIntent social, GestureIntent gesture, MobSnapshot observed, long expectedRevision,
            BooleanSupplier valid) {
        return result("apply " + mobId + " " + (intent == null ? "none" : intent.type()), outcomes.getOrDefault(mobId, new Outcome("", memory.revision(mobId))));
    }
    @Override public CompletableFuture<String> giftFromHand(UUID mobId, UUID playerId) { return result("gift " + mobId, "Подарок принят"); }
    @Override public CompletableFuture<Void> openTrade(UUID mobId, UUID playerId, String dimension) { return result("trade " + mobId, null); }
    @Override public CompletableFuture<List<UUID>> witnesses(UUID speakerId, UUID playerId, String dimension, int limit) {
        List<UUID> all = witnesses.getOrDefault(speakerId, List.of());
        return result("witnesses " + speakerId, all.subList(0, Math.min(limit, all.size())));
    }
    @Override public ConversationStore.Village villageAt(UUID playerId, String dimension, int x, int z) { record("village " + playerId); return villages.get(playerId); }
    @Override public CompletableFuture<String> info(UUID mobId, UUID playerId, String dimension, int x, int z, boolean secrets) { return result("info " + mobId, "info"); }
    @Override public void event(UUID mobId, String text) { record("event " + mobId + ": " + text); }
    /** Сразу на вызывающем потоке, под замком моста: как один поток сервера, где задачи пула не пересекаются с тиком. */
    @Override public synchronized <T> CompletableFuture<T> onServer(Supplier<T> work) {
        if (failure != null) return CompletableFuture.failedFuture(new IllegalStateException(failure));
        try { return CompletableFuture.completedFuture(work.get()); } catch (Throwable thrown) { return CompletableFuture.failedFuture(thrown); }
    }

    @Override public CompletableFuture<MobView> mobByEntityId(UUID playerId, int entityId) {
        if (!players.containsKey(playerId)) return fail("mobById " + entityId, "Игрок вышел");
        for (MobView mob : mobs.values()) if (mob.entityId() == entityId) return result("mobById " + entityId, mob);
        return fail("mobById " + entityId, "Моб погиб или выгрузился");
    }
    @Override public CompletableFuture<MobView> mob(UUID mobId, String dimension) {
        MobView mob = mobs.get(mobId);
        return mob == null ? fail("mob " + mobId, "Моб погиб или выгрузился") : result("mob " + mobId, mob);
    }
    @Override public CompletableFuture<PlayerView> player(UUID playerId) {
        PlayerView player = players.get(playerId);
        return player == null ? fail("player " + playerId, "Игрок вышел") : result("player " + playerId, player);
    }
    @Override public CompletableFuture<List<UUID>> playersNear(UUID mobId, String dimension, double range) {
        MobView mob = mobs.get(mobId);
        List<UUID> near = new ArrayList<>();
        if (mob != null) for (PlayerView player : players.values()) if (player.alive() && mob.squaredDistanceTo(player) <= range * range) near.add(player.id());
        return result("playersNear " + mobId, near);
    }
    @Override public CompletableFuture<List<UUID>> knownMobsNear(UUID playerId, double range) {
        PlayerView player = players.get(playerId);
        List<UUID> near = new ArrayList<>();
        if (player != null) for (MobView mob : mobs.values()) if (mob.squaredDistanceTo(player) <= range * range && memory.acquainted(mob.id())) near.add(mob.id());
        return result("knownMobsNear " + playerId, near);
    }
    @Override public CompletableFuture<Void> attend(UUID mobId, UUID playerId, int ticks) { return result("attend " + mobId, null); }
    @Override public CompletableFuture<UUID> currentMerchant(UUID playerId) {
        PlayerView player = players.get(playerId);
        return player == null ? fail("merchant " + playerId, "Игрок вышел") : result("merchant " + playerId, player.merchant());
    }
    @Override public CompletableFuture<String> afterWords(UUID mobId, UUID playerId, String said, GestureIntent gesture) { return result("afterWords " + mobId, afterWords); }
    @Override public CompletableFuture<String> stopAllFollowers(UUID playerId) { return result("stopAll " + playerId, "Следование всех мобов отменено"); }
    @Override public CompletableFuture<String> forgiveVillage(UUID playerId) { return result("forgive " + playerId, "Деревня простила"); }
    @Override public CompletableFuture<Integer> countInHand(UUID playerId, String itemId) { return result("count " + itemId, inHand.getOrDefault(playerId, 0)); }
    @Override public List<FriendActions.Status> friendStatus(UUID mobId) { return List.of(); }

    @Override public synchronized Initiative.Cue pollInitiative(boolean floorBusy, Set<UUID> inFlight) {
        if (!floorBusy) return initiatives.poll();
        for (var it = initiatives.iterator(); it.hasNext(); ) {
            Initiative.Cue cue = it.next();
            if (cue.trigger() == Initiative.Trigger.GIFT && !inFlight.contains(cue.mob())) { it.remove(); return cue; }
        }
        return null;
    }
    @Override public synchronized void refundInitiative(Initiative.Cue cue) { record("refundInitiative " + (cue == null ? "null" : cue.mob())); }
    @Override public synchronized int pendingInitiatives() { return initiatives.size(); }
    @Override public void floorBusy(boolean busy) { floorBusy = busy; }
    @Override public synchronized VillageChatter.Cue pollTalk() { return talks.poll(); }
    @Override public synchronized void refundTalk(VillageChatter.Cue cue) { record("refundTalk"); }
    @Override public synchronized boolean talkWaiting() { return !talks.isEmpty(); }
    @Override public void talking(boolean on) { talking = on; }
    @Override public synchronized VillageNotices.Cue pollNotice() { return notices.poll(); }
    @Override public boolean weddingActive() { return wedding; }
}
