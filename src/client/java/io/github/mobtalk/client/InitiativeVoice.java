package io.github.mobtalk.client;

import io.github.mobtalk.client.game.MobGameBridge;
import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.DecisionState;
import io.github.mobtalk.core.Initiative;
import net.minecraft.entity.mob.MobEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Creatures speaking first, on the client: takes the occasions the server noticed ({@link Initiative.Cue}), lets the decider say
 * whether the creature speaks (off the game thread), sends the line through the bystander pipeline so it never talks over a
 * voice, and runs a short scene between creatures (two to four lines, the next speaker chosen by the decider). Also kicks the
 * village chronicle once in a while. Everything here runs on the client thread except the deciding. */
final class InitiativeVoice {
    private final MobTalkClient owner;
    private final Random random = new Random();
    /** The occasion behind each first line still in flight, by the creature. */
    private final Map<UUID, Initiative.Cue> cues = new HashMap<>();
    private Initiative.Scene scene;
    private long sceneGeneration = -1;

    InitiativeVoice(MobTalkClient owner) { this.owner = owner; }

    /** Once a second: tell the server whether the floor is free, take the next occasion; twice a minute: the chronicle. */
    void tick(long ticks) {
        if (ticks % 20 != 5 || !owner.enabled() || owner.client.player == null || owner.world == null || owner.client.getServer() == null) return;
        MobGameBridge game = MobGameBridge.active(owner.client.getServer());
        if (game == null) return;
        long now = System.currentTimeMillis();
        // Повод, на который за {@link #STALE_MILLIS} не пришло ни ответа, ни отказа, снимается: потерянный где-то по дороге не должен
        // навсегда занять очередь (так молчали реплики семьи в самотесте трека N).
        for (var it = cues.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Long since = taken.get(entry.getKey());
            if (since != null && now - since > STALE_MILLIS) { LOGGER.warn("Mob Talk initiative: {} gave no line in {} s, dropped", entry.getValue().mobName(), STALE_MILLIS / 1000); it.remove(); taken.remove(entry.getKey()); }
        }
        boolean busy = owner.busyTalking() || owner.witnessPending() || scene != null || !cues.isEmpty() || owner.village.active() || owner.wedding.active();
        game.initiative.playerBusy = busy;
        if (busy && game.initiative.pending() > 0) {
            if (waitingSince == 0) waitingSince = now;
            else if (now - waitingSince > 15_000 && !waitLogged) {
                waitLogged = true;
                LOGGER.info("Mob Talk initiative: {} occasion(s) wait for the floor: {} scene={} cues={} village={} wedding={}", game.initiative.pending(),
                        owner.busyReason(), scene != null, cues.keySet(), owner.village.active(), owner.wedding.active());
            }
        } else { waitingSince = 0; waitLogged = false; }
        if (ticks % 600 == 5) chronicle(game);
        if (busy) return; // Ordinary occasions are only raised while initiative is on; gifts and family events always are.
        Initiative.Cue cue = game.initiative.poll();
        if (cue == null) return;
        MobEntity mob = MobSpeechSound.find(owner.world, cue.mob());
        if (mob == null || !owner.withinRange(mob)) { game.initiative.refund(cue); return; }
        cues.put(cue.mob(), cue); taken.put(cue.mob(), now);
        LOGGER.info("Mob Talk initiative: {} takes the floor ({})", cue.mobName(), cue.trigger());
        UUID player = owner.client.player.getUuid();
        owner.startSpontaneous(cue.mob(), cue.mobName(), () -> {
            DecisionState state = game.memory.decision(cue.mob(), player, cue, "", "", false);
            // A reaction the game owes (a gift, a family event) is always spoken; the decider only shapes ordinary occasions.
            Initiative.Verdict verdict = cue.trigger() == Initiative.Trigger.GIFT ? new Initiative.Verdict(true, Initiative.Reaction.REMARK, 1)
                    : state == null ? Initiative.Verdict.QUIET : owner.decider().speaksUp(state);
            if (!verdict.speak()) { game.initiative.refund(cue); owner.client.execute(() -> cues.remove(cue.mob())); return null; }
            return ConversationEngine.Overheard.initiative(Initiative.cueText(cue, verdict));
        });
    }

    /** The village the player stands in gets its chronicle written when due; the engine does it in the background. */
    private void chronicle(MobGameBridge game) {
        ConversationEngine engine = owner.engine();
        if (engine == null) return;
        var player = owner.client.player;
        ConversationStore.Village village = game.memory.villageNear(owner.world.getRegistryKey().getValue().toString(), (int) player.getX(), (int) player.getZ());
        if (village == null) return;
        long day = Math.floorDiv(owner.world.getTimeOfDay(), 24_000L);
        String name = MobGameBridge.playerName(player);
        owner.maintenance.execute(() -> engine.chronicleIfDue(village.id(), name, day));
    }

    /** Когда повод взят в работу; через столько без ответа он снимается. */
    private final Map<UUID, Long> taken = new HashMap<>();
    static final long STALE_MILLIS = 120_000;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mobtalk");
    private long waitingSince; private boolean waitLogged;
    /** Последняя реплика каждого, кто заговорил первым или ответил в сцене; "" — решил промолчать (для самотеста). */
    final Map<UUID, String> lastLines = new HashMap<>();
    /** A first line or an answer was delivered: start or continue the scene. */
    void spoken(UUID target, ConversationEngine.Overheard overheard, long generation, ConversationEngine.Reply reply) {
        Initiative.Cue cue = cues.remove(target); taken.remove(target);
        LOGGER.info("Mob Talk initiative: {} {}", reply.speakerName(), reply.silent() ? "kept silent" : "spoke");
        lastLines.put(target, reply.silent() ? "" : reply.responseText());
        if (reply.silent()) { if (overheard.kind() == ConversationEngine.Overheard.Kind.DIALOGUE || cue != null) scene = null; return; }
        if (overheard.kind() == ConversationEngine.Overheard.Kind.INITIATIVE) {
            if (cue == null || cue.partners().isEmpty()) { scene = null; return; }
            List<Initiative.Candidate> cast = new ArrayList<>();
            cast.add(new Initiative.Candidate(target, reply.speakerName(), 0, 0)); cast.addAll(cue.partners());
            scene = new Initiative.Scene(cast, Initiative.SCENE_MIN_LINES + random.nextInt(Math.max(Initiative.SCENE_MIN_LINES, owner.config().sceneMaxLines) - Initiative.SCENE_MIN_LINES + 1));
            sceneGeneration = generation;
        } else if (scene == null || generation != sceneGeneration) return;
        scene.linesLeft--;
        scene.lastSpeaker = target; scene.lastSpeakerName = reply.speakerName(); scene.lastLine = reply.responseText();
        if (scene.over()) { scene = null; return; }
        continueScene();
    }

    /** The decider picks who answers the last line, off the game thread; the answer goes through the same pipeline. */
    private void continueScene() {
        Initiative.Scene current = scene;
        MobGameBridge game = owner.client.getServer() == null ? null : MobGameBridge.active(owner.client.getServer());
        if (current == null || game == null || owner.client.player == null) { scene = null; return; }
        List<Initiative.Candidate> candidates = new ArrayList<>();
        for (Initiative.Candidate candidate : current.others()) {
            MobEntity mob = MobSpeechSound.find(owner.world, candidate.id());
            if (mob != null && owner.withinRange(mob)) candidates.add(candidate);
        }
        if (candidates.isEmpty()) { scene = null; return; }
        UUID player = owner.client.player.getUuid();
        long generation = sceneGeneration;
        owner.background(() -> {
            DecisionState state = game.memory.decision(current.lastSpeaker, player, null, current.lastSpeakerName, current.lastLine, false);
            UUID next = state == null ? null : owner.decider().nextSpeaker(state, candidates);
            owner.client.execute(() -> {
                if (scene != current || owner.generation() != generation) return;
                Initiative.Candidate chosen = next == null ? null : candidates.stream().filter(c -> c.id().equals(next)).findFirst().orElse(null);
                if (chosen == null) { scene = null; return; }
                owner.startSpontaneous(chosen.id(), chosen.name(), () -> ConversationEngine.Overheard.dialogue(current.lastSpeakerName, current.lastLine));
            });
        });
    }

    /** A spontaneous line failed or was dropped: nothing waits on it any more. */
    void ended(UUID mob) {
        if (cues.remove(mob) != null) LOGGER.info("Mob Talk initiative: the occasion of {} ended without a line", mob);
        taken.remove(mob);
        if (scene != null && (scene.lastSpeaker == null || scene.participants.stream().anyMatch(c -> c.id().equals(mob)))) scene = null;
    }
    /** The player spoke or the world changed: whatever scene was on is over. */
    void cancel() { cues.clear(); taken.clear(); scene = null; }
    boolean sceneOn() { return scene != null; }
}
