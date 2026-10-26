package io.github.mobtalk.server;

import io.github.mobtalk.core.MobClock;
import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.DecisionState;
import io.github.mobtalk.core.Initiative;
import io.github.mobtalk.core.VillageChatter;
import io.github.mobtalk.core.VillageNotices;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.GameBridge.MobView;
import io.github.mobtalk.game.GameBridge.PlayerView;
import io.github.mobtalk.server.DialogueService.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Реплики, которых игрок не просил, на сервере (перенос {@code InitiativeVoice} и {@code VillageTalkVoice} клиента): поводы
 * заговорить первым ({@link Initiative.Cue}) и сцены между существами, болтовня пар жителей ({@link VillageChatter.Cue}) и объявления
 * в чат ({@link VillageNotices.Cue}). Адресат повода — ближайший игрок к мобу в радиусе разговора: его сессия даёт эфир и очередь.
 * Поток сервера, кроме решений и текстов (пул). */
final class SpontaneousVoices {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    static final long STALE_MILLIS = 120_000;
    private final DialogueService service;
    private final GameBridge bridge;
    private final Random random = new Random();
    /** Повод каждого моба в работе и чья сессия его ведёт; когда взят. */
    private final Map<UUID, Initiative.Cue> cues = new HashMap<>();
    private final Map<UUID, Session> owners = new HashMap<>();
    private final Map<UUID, Long> taken = new HashMap<>();
    private final Map<Session, Initiative.Scene> scenes = new HashMap<>();
    private final Map<Session, Long> sceneGenerations = new HashMap<>();
    /** Последняя реплика каждого, кто заговорил первым или ответил в сцене; "" — промолчал (для журнала и самотеста). */
    final Map<UUID, String> lastLines = new HashMap<>();
    /** Болтовня: написанные строки, кто их играет, следующая, записана ли в память. */
    private List<ConversationEngine.ChatterLine> lines;
    private VillageChatter.Input input;
    private Session talkOwner;
    private int next;
    private boolean recorded, writing, announcing;
    private long waitingSince; private boolean waitLogged;

    SpontaneousVoices(DialogueService service, GameBridge bridge) { this.service = service; this.bridge = bridge; }

    boolean sceneOn(Session session) { return scenes.get(session) != null; }
    /** Болтовня этой сессии ещё звучит: очевидцы ждут. */
    boolean voicing(Session session) { return lines != null && talkOwner == session; }
    boolean talkActive() { return writing || lines != null; }
    private boolean anyBusy() {
        for (Session session : service.sessions()) if (session.busyTalking() || session.witnessPending()) return true;
        return !cues.isEmpty() || !scenes.isEmpty() || talkActive() || bridge.weddingActive();
    }

    void tick(long ticks) {
        if (ticks % 20 == 5) initiative();
        if (ticks % 10 == 3) village(ticks);
    }

    // ---- Инициатива. ----

    private void initiative() {
        if (!bridge.config().enabled) return;
        long now = MobClock.now();
        for (var it = cues.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Long since = taken.get(entry.getKey());
            if (since != null && now - since > STALE_MILLIS) {
                LOGGER.warn("Mob Talk initiative: {} gave no line in {} s, dropped", entry.getValue().mobName(), STALE_MILLIS / 1000);
                it.remove(); taken.remove(entry.getKey()); owners.remove(entry.getKey());
            }
        }
        boolean busy = anyBusy();
        bridge.floorBusy(busy);
        if (busy && bridge.pendingInitiatives() > 0) {
            if (waitingSince == 0) waitingSince = now;
            else if (now - waitingSince > 15_000 && !waitLogged) { waitLogged = true; LOGGER.info("Mob Talk initiative: {} occasion(s) wait for the floor", bridge.pendingInitiatives()); }
        } else { waitingSince = 0; waitLogged = false; }
        // Обычные поводы только при свободном эфире; подарок и семейный повод берутся и при занятом (gifts first), но не на свадьбе.
        if (busy && bridge.weddingActive()) return;
        Initiative.Cue cue = bridge.pollInitiative(busy, cues.keySet());
        if (cue == null) return;
        Initiative.Cue taking = cue;
        nearest(cue.mob()).whenComplete((found, error) -> service.onServer(() -> {
            if (error != null || found == null) { bridge.refundInitiative(taking); return; }
            Session session = found.session();
            cues.put(taking.mob(), taking); owners.put(taking.mob(), session); taken.put(taking.mob(), MobClock.now());
            LOGGER.info("Mob Talk initiative: {} takes the floor ({}) for {}", taking.mobName(), taking.trigger(), session.name);
            UUID player = session.player;
            boolean owed = taking.trigger() == Initiative.Trigger.GIFT;
            service.startSpontaneous(session, taking.mob(), taking.mobName(), owed && bridge.config().giftsFirst, () -> {
                DecisionState state = bridge.memory().decision(taking.mob(), player, taking, "", "", false);
                Initiative.Verdict verdict = owed ? new Initiative.Verdict(true, Initiative.Reaction.REMARK, Initiative.merged(taking) ? 2 : 1)
                        : state == null ? Initiative.Verdict.QUIET : service.decider().speaksUp(state);
                if (!verdict.speak()) { bridge.refundInitiative(taking); service.onServer(() -> { cues.remove(taking.mob()); owners.remove(taking.mob()); taken.remove(taking.mob()); }); return null; }
                return ConversationEngine.Overheard.initiative(Initiative.cueText(taking, verdict));
            });
        }));
    }

    private record Found(Session session, MobView mob) { }
    /** Сессия ближайшего игрока к мобу в радиусе разговора; null, если никого. */
    private CompletableFuture<Found> nearest(UUID mobId) {
        List<Session> candidates = new ArrayList<>(service.sessions());
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(null);
        return find(mobId, candidates, 0, null, Double.MAX_VALUE);
    }
    private CompletableFuture<Found> find(UUID mobId, List<Session> candidates, int index, Found best, double bestDistance) {
        if (index >= candidates.size()) return CompletableFuture.completedFuture(best);
        Session session = candidates.get(index);
        return bridge.player(session.player).thenCompose(player -> bridge.mob(mobId, player.dimension()).thenApply(mob -> {
            double distance = mob.squaredDistanceTo(player);
            double limit = bridge.config().maxDistance * bridge.config().maxDistance;
            return player.alive() && distance <= limit && distance < bestDistance ? new Object[] { new Found(session, mob), distance } : null;
        })).handle((closer, error) -> closer == null ? new Object[] { best, bestDistance } : closer)
          .thenCompose(pair -> find(mobId, candidates, index + 1, (Found) pair[0], (Double) pair[1]));
    }

    /** Первая реплика или ответ в сцене доставлены: сцена начинается или продолжается. */
    void spoken(Session session, UUID target, ConversationEngine.Overheard overheard, long generation, ConversationEngine.Reply reply) {
        Initiative.Cue cue = cues.remove(target); taken.remove(target); owners.remove(target);
        LOGGER.info("Mob Talk initiative: {} {}", reply.speakerName(), reply.silent() ? "kept silent" : "spoke");
        lastLines.put(target, reply.silent() ? "" : reply.responseText());
        Initiative.Scene scene = scenes.get(session);
        if (reply.silent()) { if (overheard.kind() == ConversationEngine.Overheard.Kind.DIALOGUE || cue != null) scenes.remove(session); return; }
        if (overheard.kind() == ConversationEngine.Overheard.Kind.INITIATIVE) {
            if (cue == null || cue.partners().isEmpty()) { scenes.remove(session); return; }
            List<Initiative.Candidate> cast = new ArrayList<>();
            cast.add(new Initiative.Candidate(target, reply.speakerName(), 0, 0)); cast.addAll(cue.partners());
            scene = new Initiative.Scene(cast, Initiative.SCENE_MIN_LINES + random.nextInt(Math.max(Initiative.SCENE_MIN_LINES, bridge.config().sceneMaxLines) - Initiative.SCENE_MIN_LINES + 1));
            scenes.put(session, scene); sceneGenerations.put(session, generation);
        } else if (scene == null || generation != sceneGenerations.getOrDefault(session, -1L)) return;
        scene.linesLeft--;
        scene.lastSpeaker = target; scene.lastSpeakerName = reply.speakerName(); scene.lastLine = reply.responseText();
        if (scene.over()) { scenes.remove(session); return; }
        continueScene(session, scene);
    }

    private void continueScene(Session session, Initiative.Scene current) {
        bridge.player(session.player).whenComplete((player, error) -> service.onServer(() -> {
            if (error != null || scenes.get(session) != current) { scenes.remove(session); return; }
            List<CompletableFuture<Initiative.Candidate>> checks = new ArrayList<>();
            for (Initiative.Candidate candidate : current.others())
                checks.add(bridge.mob(candidate.id(), player.dimension()).handle((mob, failure) ->
                        failure == null && mob.squaredDistanceTo(player) <= bridge.config().maxDistance * bridge.config().maxDistance ? candidate : null));
            CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenRun(() -> service.onServer(() -> {
                List<Initiative.Candidate> candidates = checks.stream().map(CompletableFuture::join).filter(c -> c != null).toList();
                if (candidates.isEmpty() || scenes.get(session) != current) { scenes.remove(session); return; }
                long generation = sceneGenerations.getOrDefault(session, -1L);
                service.pool().submit(DialoguePool.Priority.WITNESS, () -> {
                    DecisionState state = bridge.memory().decision(current.lastSpeaker, session.player, null, current.lastSpeakerName, current.lastLine, false);
                    UUID next = state == null ? null : service.decider().nextSpeaker(state, candidates);
                    service.onServer(() -> {
                        if (scenes.get(session) != current || session.generation != generation) return;
                        Initiative.Candidate chosen = next == null ? null : candidates.stream().filter(c -> c.id().equals(next)).findFirst().orElse(null);
                        if (chosen == null) { scenes.remove(session); return; }
                        service.startSpontaneous(session, chosen.id(), chosen.name(), false, () -> ConversationEngine.Overheard.dialogue(current.lastSpeakerName, current.lastLine));
                    });
                });
            }));
        }));
    }

    /** Посторонняя реплика сорвалась: на неё больше ничего не ждёт. */
    void ended(Session session, UUID mob) {
        if (cues.remove(mob) != null) LOGGER.info("Mob Talk initiative: the occasion of {} ended without a line", mob);
        taken.remove(mob); owners.remove(mob);
        Initiative.Scene scene = scenes.get(session);
        if (scene != null && (scene.lastSpeaker == null || scene.participants.stream().anyMatch(c -> c.id().equals(mob)))) scenes.remove(session);
    }
    /** Игрок заговорил, вышел или сменил мир: его сцена и поводы сняты; его болтовня обрывается. */
    void cancel(Session session) {
        for (var it = owners.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue() == session) { cues.remove(entry.getKey()); taken.remove(entry.getKey()); it.remove(); }
        }
        scenes.remove(session); sceneGenerations.remove(session);
        if (talkOwner == session) cancelTalk();
    }

    // ---- Болтовня деревни и объявления. ----

    private double range() { return bridge.config().villageChatterRange + 4; }

    private void village(long ticks) {
        if (!bridge.config().enabled) return;
        bridge.talking(talkActive());
        if (bridge.weddingActive()) { cancelTalk(); return; }
        if (lines != null) { advance(); return; }
        if (ticks % 20 != 3) return;
        announce();
        boolean floorBusy = writing || anyBusy();
        if (floorBusy && bridge.talkWaiting()) {
            long now = MobClock.now();
            if (waitingSince == 0) waitingSince = now;
        }
        if (!floorBusy) write();
    }

    private void write() {
        VillageChatter.Cue cue = bridge.pollTalk();
        if (cue == null) return;
        nearest(cue.input().a().id()).whenComplete((found, error) -> service.onServer(() -> {
            if (error != null || found == null) { LOGGER.info("Mob Talk: small talk of {} and {} dropped: nobody in earshot", cue.input().a().name(), cue.input().b().name()); bridge.refundTalk(cue); return; }
            Session session = found.session();
            long generation = session.generation;
            writing = true; bridge.talking(true); talkOwner = session;
            service.pool().submit(DialoguePool.Priority.VILLAGE, () -> {
                List<ConversationEngine.ChatterLine> written = List.of();
                try {
                    ConversationEngine engine = service.engineOrNull();
                    if (engine != null) written = engine.chatter(cue.input());
                } catch (Exception failure) {
                    LOGGER.info("Mob Talk: village small talk skipped ({})", String.valueOf(failure.getMessage()));
                } finally { Thread.interrupted(); }
                List<ConversationEngine.ChatterLine> done = written;
                service.onServer(() -> {
                    writing = false;
                    if (done.isEmpty()) LOGGER.info("Mob Talk: small talk of {} and {} came back empty", cue.input().a().name(), cue.input().b().name());
                    if (generation != session.generation || done.isEmpty()) { discard(done, 0); talkOwner = null; return; }
                    lines = done; input = cue.input(); next = 0; recorded = false;
                });
            });
        }));
    }

    private void advance() {
        Session session = talkOwner;
        if (session == null || session.busyTalking()) { if (session == null) cancelTalk(); return; }
        if (next >= lines.size()) { lines = null; input = null; talkOwner = null; return; }
        ConversationEngine.ChatterLine line = lines.get(next);
        int index = next;
        bridge.player(session.player).thenCompose(player -> bridge.mob(line.speaker(), player.dimension()).thenApply(mob -> new Object[] { player, mob }))
                .whenComplete((pair, error) -> service.onServer(() -> {
            if (lines == null || next != index || talkOwner != session) return;
            if (error != null) { cancelTalk(); return; }
            PlayerView player = (PlayerView) pair[0]; MobView speaker = (MobView) pair[1];
            if (speaker.squaredDistanceTo(player) > range() * range()) { cancelTalk(); return; }
            next++;
            if (!recorded && input != null) {
                recorded = true;
                LOGGER.info("Mob Talk: болтовня {} и {}, деревня {}", input.a().name(), input.b().name(), input.villageKey());
                VillageChatter.Input talk = input;
                bridge.memory().recordChatter(talk, lines.stream().map(l -> new VillageChatter.Line(l.speaker().equals(talk.a().id()) ? 0 : 1, l.text())).toList(), MobClock.now());
            }
            service.playOverheard(session, speaker, line.name(), line.text(), line.audio(), range());
            // A line without a voice (the bar's talk, a failed voice) is read, not heard: the next waits as long as reading takes.
            if (line.audio() == null) session.floorUntil = Math.max(session.floorUntil, MobClock.now() + 1800 + 55L * line.text().length());
        }));
    }

    private void announce() {
        if (announcing || talkActive()) return;
        VillageNotices.Cue cue = bridge.pollNotice();
        if (cue == null) return;
        announcing = true;
        service.pool().submit(DialoguePool.Priority.VILLAGE, () -> {
            String text = "";
            try {
                ConversationEngine engine = service.engineOrNull();
                if (engine != null) text = engine.announce(cue.input());
            } catch (Exception failure) {
                LOGGER.info("Mob Talk: villager announcement skipped ({})", String.valueOf(failure.getMessage()));
            } finally { Thread.interrupted(); }
            String written = text;
            service.onServer(() -> {
                announcing = false;
                if (written.isBlank()) return;
                bridge.memory().recordNotice(cue.input(), written, MobClock.now());
                Session session = service.sessions().stream().filter(s -> s.player.equals(cue.input().player())).findFirst().orElse(null);
                if (session == null) session = service.sessions().stream().findFirst().orElse(null);
                if (session == null) return;
                Session owner = session;
                bridge.player(owner.player).thenCompose(player -> bridge.mob(cue.input().mob(), player.dimension())).whenComplete((mob, error) -> service.onServer(() -> {
                    if (error != null) { service.out().chat(owner.player, "§e" + cue.input().name() + "§r: " + written); return; }
                    service.announce(owner, mob, cue.input().name(), written, cue.input().kind() == VillageNotices.Kind.WORK);
                }));
            });
        });
    }

    private static void discard(List<ConversationEngine.ChatterLine> lines, int from) {
        for (int i = from; i < lines.size(); i++) if (lines.get(i).audio() != null) lines.get(i).audio().close();
    }
    private void cancelTalk() {
        if (lines != null) discard(lines, next);
        lines = null; input = null; talkOwner = null;
    }
    /** Деревня, где стоит игрок, получает хронику, когда пора (движок пишет её в фоне). */
    void chronicle(Session session, PlayerView player, ConversationStore.Village village) {
        ConversationEngine engine = service.engine();
        if (engine == null || village == null) return;
        String name = player.name(); long day = player.day();
        service.pool().submit(DialoguePool.Priority.MAINTENANCE, () -> engine.chronicleIfDue(village.id(), name, day));
    }
}
