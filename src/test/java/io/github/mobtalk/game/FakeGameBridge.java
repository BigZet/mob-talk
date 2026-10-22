package io.github.mobtalk.game;

import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.BargainIntent;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.DealIntent;
import io.github.mobtalk.core.GestureIntent;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.QuestIntent;
import io.github.mobtalk.core.SocialIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Мост без Minecraft для тестов серверной логики (раздел 16 плана): отдаёт заданные снимки и итоги, записывает вызовы.
 * {@code onServer} выполняет работу сразу на вызывающем потоке. */
public final class FakeGameBridge implements GameBridge {
    public final ConversationStore memory;
    public volatile MobTalkConfig config;
    public final Map<UUID, MobSnapshot> snapshots = new ConcurrentHashMap<>();
    public final Map<UUID, Outcome> outcomes = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> witnesses = new ConcurrentHashMap<>();
    public final Map<UUID, ConversationStore.Village> villages = new ConcurrentHashMap<>();
    public final List<String> calls = new ArrayList<>();
    /** Причина, с которой падают все вызовы (моб ушёл, мир сменился); null — всё работает. */
    public volatile String failure;

    public FakeGameBridge(ConversationStore memory, MobTalkConfig config) { this.memory = memory; this.config = config; }

    private synchronized void record(String call) { calls.add(call); }
    private <T> CompletableFuture<T> result(String call, T value) {
        record(call);
        return failure == null ? CompletableFuture.completedFuture(value) : CompletableFuture.failedFuture(new IllegalStateException(failure));
    }

    @Override public ConversationStore memory() { return memory; }
    @Override public MobTalkConfig config() { return config; }
    @Override public CompletableFuture<MobSnapshot> capture(UUID mobId, UUID playerId, String dimension) {
        MobSnapshot snapshot = snapshots.get(mobId);
        if (snapshot == null && failure == null) { record("capture " + mobId); return CompletableFuture.failedFuture(new IllegalStateException("Собеседник или игрок недоступен")); }
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
        return result("apply " + mobId + " " + (intent == null ? "none" : intent.type()), outcomes.getOrDefault(mobId, new Outcome("", expectedRevision + 1)));
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
    @Override public <T> CompletableFuture<T> onServer(Supplier<T> work) {
        if (failure != null) return CompletableFuture.failedFuture(new IllegalStateException(failure));
        try { return CompletableFuture.completedFuture(work.get()); } catch (Throwable thrown) { return CompletableFuture.failedFuture(thrown); }
    }
}
