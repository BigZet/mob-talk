package io.github.mobtalk.core;

import java.util.List;
import java.util.UUID;

/** Detached immutable UI view; relationship is for the requested player only. */
public record MobCard(UUID id, String type, String name, String personality, String voiceId, int variantSlot,
                      int relationship, String memory, List<ConversationStore.Turn> turns,
                      List<String> events, long revision, ActionIntent action,
                      int calm, int confidence, int curiosity, int sociability,
                      String preference, String fear, String customPersonality, MobState.View state, String age) {
    public MobCard { turns = List.copyOf(turns); events = List.copyOf(events); if (state == null) state = MobState.View.NEUTRAL; if (age == null) age = ""; }
    public MobCard(UUID id, String type, String name, String personality, String voiceId, int variantSlot,
                   int relationship, String memory, List<ConversationStore.Turn> turns,
                   List<String> events, long revision, ActionIntent action) {
        this(id, type, name, personality, voiceId, variantSlot, relationship, memory, turns, events, revision,
                action, 50, 50, 50, 50, "", "", "", MobState.View.NEUTRAL, "");
    }
}
