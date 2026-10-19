package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** What a {@link Decider} sees: one creature at one moment, compact and free of secrets — who it is, how it feels (in words and
 * numbers), its temperament, how it stands with the player, its bonds, what the occasion is, what happened lately and what was
 * just said. Built off the game thread from the world memory. */
public record DecisionState(UUID mob, String name, String species, MobState.View state, int sociability, int curiosity, int relationship,
                            List<MobRelations.View> bonds, Initiative.Trigger trigger, String reason, List<String> recentEvents, String lastSaid,
                            String speaker, boolean eventful) {
    public DecisionState {
        name = name == null ? "" : name; species = species == null ? "" : species; state = state == null ? MobState.View.NEUTRAL : state;
        bonds = bonds == null ? List.of() : List.copyOf(bonds); reason = reason == null ? "" : reason;
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents); lastSaid = lastSaid == null ? "" : lastSaid; speaker = speaker == null ? "" : speaker;
    }

    /** The state as the Decisions API reads it. */
    public JsonObject json() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name); json.addProperty("species", species);
        JsonObject mood = new JsonObject();
        for (MobState.Axis axis : MobState.Axis.values()) {
            MobState.Descriptor word = state.descriptor(axis);
            mood.addProperty(axis.name().toLowerCase(Locale.ROOT), state.of(axis) + (word.word().isEmpty() ? "" : " (" + word.word() + ")"));
        }
        json.add("state", mood);
        if (!state.thought().isEmpty()) json.addProperty("thought", state.thought());
        json.addProperty("sociability", sociability); json.addProperty("curiosity", curiosity);
        json.addProperty("relationshipWithPlayer", relationship + " (" + MobPrompt.attitude(relationship) + ")");
        JsonArray kin = new JsonArray();
        for (MobRelations.View bond : bonds.stream().limit(4).toList()) kin.add(bond.describe(false));
        if (!kin.isEmpty()) json.add("bonds", kin);
        if (trigger != null) json.addProperty("occasion", trigger.name().toLowerCase(Locale.ROOT) + ": " + reason);
        JsonArray events = new JsonArray();
        for (String event : recentEvents.stream().limit(5).toList()) events.add(TextBounds.characters(event, 160));
        if (!events.isEmpty()) json.add("recentEvents", events);
        if (!lastSaid.isEmpty()) json.addProperty("justSaid", (speaker.isEmpty() ? "" : speaker + ": ") + TextBounds.characters(lastSaid, 300));
        if (!speaker.isEmpty() && lastSaid.isEmpty()) json.addProperty("speaker", speaker);
        json.addProperty("eventful", eventful);
        return json;
    }

    /** A state for a creature speaking first. */
    public static DecisionState forCue(UUID mob, String name, String species, MobState.View state, int sociability, int curiosity, int relationship,
                                       List<MobRelations.View> bonds, Initiative.Cue cue, List<String> recentEvents) {
        return new DecisionState(mob, name, species, state, sociability, curiosity, relationship, bonds, cue == null ? null : cue.trigger(), cue == null ? "" : cue.reason(), recentEvents, "", "", false);
    }
    /** A state for a bystander or a creature answering another: what was just said and by whom. */
    public static DecisionState forHeard(UUID mob, String name, String species, MobState.View state, int sociability, int curiosity, int relationship,
                                         List<MobRelations.View> bonds, String speaker, String said, boolean eventful, List<String> recentEvents) {
        return new DecisionState(mob, name, species, state, sociability, curiosity, relationship, bonds, null, "", recentEvents, said, speaker, eventful);
    }
}
