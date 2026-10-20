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
        boolean busy = owner.busyTalking() || owner.witnessPending() || scene != null || !cues.isEmpty() || owner.village.active() || owner.wedding.active();
        game.initiative.playerBusy = busy;
        if (ticks % 600 == 5) chronicle(game);
        if (busy) return; // Ordinary occasions are only raised while initiative is on; gifts and family events always are.
        Initiative.Cue cue = game.initiative.poll();
        if (cue == null) return;
        MobEntity mob = MobSpeechSound.find(owner.world, cue.mob());
        if (mob == null || !owner.withinRange(mob)) { game.initiative.refund(cue); return; }
        cues.put(cue.mob(), cue);
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

    /** A first line or an answer was delivered: start or continue the scene. */
    void spoken(UUID target, ConversationEngine.Overheard overheard, long generation, ConversationEngine.Reply reply) {
        Initiative.Cue cue = cues.remove(target);
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
        cues.remove(mob);
        if (scene != null && (scene.lastSpeaker == null || scene.participants.stream().anyMatch(c -> c.id().equals(mob)))) scene = null;
    }
    /** The player spoke or the world changed: whatever scene was on is over. */
    void cancel() { cues.clear(); scene = null; }
    boolean sceneOn() { return scene != null; }
}
