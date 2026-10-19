package io.github.mobtalk.core;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** The social layer inside the world memory (docs/mca-social-spec.md): a villager's sex, character and marks, the gestures of a
 * conversation with their outcomes decided in advance, and their effects on the relationship, the emotional state and the memory.
 * Every method that touches the world memory runs under the {@link ConversationStore} lock; the static helpers are called by the
 * store from inside its own locked methods. */
public final class SocialLedger {
    private final ConversationStore store;
    SocialLedger(ConversationStore store) { this.store = store; }

    /** Villagers only, and only while the layer is on. */
    static boolean applies(String type, MobTalkConfig config) { return config != null && config.socialLayer && "minecraft:villager".equals(type); }
    static boolean applies(ConversationStore.Profile p, MobTalkConfig config) { return p != null && applies(p.type, config); }

    /** Gives a villager its social profile the first time the layer sees it: sex, character and marks from its UUID, and the
     * character's one-time shift of the temperament. Returns whether anything changed. */
    static boolean adopt(ConversationStore.Profile p, MobTalkConfig config) {
        if (!applies(p, config) || p.social != null) return false;
        p.social = SocialProfile.of(p.id, false);
        Persona persona = p.social.persona();
        p.calm = shifted(p.calm, persona.calm); p.confidence = shifted(p.confidence, persona.confidence);
        p.curiosity = shifted(p.curiosity, persona.curiosity); p.sociability = shifted(p.sociability, persona.sociability);
        return true;
    }
    private static int shifted(int value, int shift) { return value < 0 ? value : Math.max(0, Math.min(100, value + shift)); }

    /** The rest point of the mood axis moved by the character (gloomy lower, cheerful higher). */
    static MobState.Baseline baseline(ConversationStore.Profile p, MobState.Baseline base) {
        return p == null || p.social == null ? base : base.moodShifted(p.social.persona().moodRest);
    }

    /** Whether the villager counts as grown up: not a vanilla baby, and past the teen stage when the family tree tracks its growth. */
    static boolean adult(ConversationStore.Profile p, MobSnapshot snapshot) {
        if (snapshot != null && Boolean.TRUE.equals(snapshot.mobDetails().get("baby"))) return false;
        return p.social == null || p.social.stage.isBlank() || ChildGrowth.Stage.of(p.social.stage) == ChildGrowth.Stage.ADULT;
    }

    /** Before the request: the outcome of every gesture the player could make now (see {@link SocialTurn}), into the detached
     * profile the prompt is built from. */
    static void prepare(ConversationStore.Profile p, ConversationStore.Profile detached, ConversationStore.Relation r, MobSnapshot snapshot,
                        MobState.View view, MobTalkConfig config, long now) {
        if (!applies(p, config) || p.social == null || snapshot.playerId() == null) return;
        Rapport rapport = r == null || r.rapport == null ? new Rapport() : r.rapport;
        boolean adult = adult(p, snapshot);
        Map<Gesture, GestureIntent> rolled = new LinkedHashMap<>();
        List<String> closed = new ArrayList<>();
        for (Gesture gesture : List.of(Gesture.JOKE, Gesture.COMPLIMENT, Gesture.HUG, Gesture.FLIRT, Gesture.KISS)) {
            if (gesture.romantic && !adult) continue;
            GestureTable.Context context = new GestureTable.Context(p.social.persona(), p.social.traits(), view.mood(), r == null ? 0 : r.score,
                    rapport.fatigue(now), rapport.repeats(gesture, now), rapport.courtship(), adult);
            rolled.putAll(SocialTurn.roll(context, List.of(gesture), () -> ThreadLocalRandom.current().nextDouble()));
        }
        if (!adult) closed.add(SocialPrompt.CHILD_ROMANCE);
        detached.socialTurn = new SocialTurn(rolled, closed);
    }

    /** After a delivered reply: the gesture's effects — relationship, fatigue and repeats, the emotional state, a memory line. */
    public void applyGesture(UUID mob, UUID player, String playerName, GestureIntent intent, long now) {
        if (mob == null || player == null || intent == null || !intent.present()) return;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(mob.toString());
            if (p == null) return;
            ConversationStore.Relation r = store.relation(p, player);
            if (r.rapport == null) r.rapport = new Rapport();
            r.score = ConversationStore.clamp(r.score + intent.hearts(), p.hostile());
            r.rapport.record(intent.gesture(), now);
            boolean gift = intent.gesture() == Gesture.GIFT;
            MobState.Event felt = intent.outcome().warm() ? (gift ? MobState.Event.GIFT_LIKED : MobState.Event.GESTURE_WARM)
                    : gift ? (intent.hearts() < 0 ? MobState.Event.GIFT_SPURNED : null) : MobState.Event.GESTURE_COLD;
            if (felt != null) ConversationStore.feel(p, r, felt, now);
            ConversationStore.event(p, now, SocialPrompt.memoryLine(intent, playerName, r.score), SocialPrompt.importance(intent));
            store.scheduleSave();
        }
    }

    /** One line about the selected villager's social side, for /mobtalk state; "" for anyone else. */
    public String describe(UUID mob, UUID player) {
        synchronized (store) {
            ConversationStore.Profile p = mob == null ? null : store.state.profiles.get(mob.toString());
            if (p == null || p.social == null) return "";
            ConversationStore.Relation r = player == null ? null : p.relations.get(player.toString());
            long now = System.currentTimeMillis();
            StringBuilder line = new StringBuilder(p.social.gender().adult).append(", ").append(p.social.persona().word);
            for (VillagerTrait trait : p.social.traits()) line.append(", ").append(trait.name().toLowerCase(Locale.ROOT));
            if (r != null && r.rapport != null) line.append("; усталость ").append(r.rapport.fatigue(now));
            return line.toString();
        }
    }
}
