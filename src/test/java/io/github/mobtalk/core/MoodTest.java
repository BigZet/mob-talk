package io.github.mobtalk.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The moods: lasting factors of the world, the long moods they and big events lead to, and what the model hears of them. */
class MoodTest {
    private static final long MINUTE = 60_000L;
    private static final MobState.Baseline REST = MobState.Baseline.of(50, 50, 50, 50, 0);

    // The rain holds the mood down while it falls and is the reason the mob names; after it the mood comes back.
    @Test void rainKeepsTheMoodDownWhileItFalls() {
        MobState state = new MobState();
        int dry = state.view(REST, 0).mood();
        assertTrue(state.surroundings(EnumSet.of(MoodFactor.RAIN), REST, 0));
        assertFalse(state.surroundings(EnumSet.of(MoodFactor.RAIN), REST, MINUTE), "the same rain is no news");
        MobState.View wet = state.view(REST, 60 * MINUTE);
        assertEquals(dry - 6, wet.mood(), "an hour later the rain still weighs: " + wet);
        assertEquals("дождь", wet.reasons().getFirst().text());
        assertTrue(wet.reasons().getFirst().lasting());
        state.surroundings(EnumSet.noneOf(MoodFactor.class), REST, 60 * MINUTE);
        assertEquals(dry, state.view(REST, 4 * 60 * MINUTE).mood(), "gone a few hours after the rain stopped");
        assertTrue(state.view(REST, 4 * 60 * MINUTE).reasons().isEmpty());
    }

    // An alarm hits at once (no drift), and a villager in it closes its trades.
    @Test void anAlarmFrightensAtOnce() {
        MobState state = new MobState();
        int calm = state.view(REST, 0).calm();
        state.surroundings(EnumSet.of(MoodFactor.ALARM), REST, 0);
        MobState.View alarmed = state.view(REST, 0);
        assertEquals(calm - 35, alarmed.calm());
        assertTrue(alarmed.refusesDeals());
        assertTrue(alarmed.reasons().stream().anyMatch(r -> r.text().equals("тревога в деревне")));
    }

    // A gloom that holds becomes a depression: short replies, a word of its own over the head, no initiative; kind deeds cure it.
    @Test void aLastingGloomTurnsIntoDepression() {
        MobState state = new MobState();
        state.shift(MobState.Event.INSULT, REST, 0);
        state.surroundings(EnumSet.of(MoodFactor.HUNGRY, MoodFactor.HOMELESS, MoodFactor.CURSE, MoodFactor.RAIN), REST, 0);
        long t = 0;
        for (; t <= 40 * MINUTE && state.spell() != MoodSpell.DEPRESSION; t += MINUTE / 6) state.evolve(REST, t);
        assertEquals(MoodSpell.DEPRESSION, state.spell(), "depressed after a gloom of " + t / MINUTE + " min: " + state.view(REST, t));
        MobState.View low = state.view(REST, t);
        assertEquals("depressed", low.marker().key());
        assertTrue(low.lengthPercent() < 50 && !low.keen() && low.grudging() && !low.generous(), low.toString());
        assertTrue(low.describe().contains("У тебя депрессия"), low.describe());
        assertTrue(low.voice().startsWith("депрессия"));
        // The world brightens, but the long mood stays until enough kindness came and its shortest time ran out.
        state.surroundings(EnumSet.noneOf(MoodFactor.class), REST, t);
        state.evolve(REST, t + 10 * MINUTE);
        assertEquals(MoodSpell.DEPRESSION, state.spell(), "not over in ten minutes without help");
        for (int i = 0; i < 12; i++) state.shift(MobState.Event.KIND, REST, t + 11 * MINUTE + i, "Стив");
        state.evolve(REST, t + 2 * 60 * MINUTE);
        assertNull(state.spell(), "kindness and time cure it");
    }

    // A death in the family brings grief at once; a grief that never healed sinks into depression.
    @Test void griefFollowsABereavementAndMaySinkDeeper() {
        MobState state = new MobState();
        state.shift(MobState.Event.BEREAVED, REST, 0, "Ольга");
        MobState.View mourning = state.view(REST, 0);
        assertEquals(MoodSpell.GRIEF, mourning.spell());
        assertEquals("смерть родного (Ольга)", mourning.reasons().getFirst().text());
        state.surroundings(EnumSet.of(MoodFactor.HUNGRY, MoodFactor.CURSE, MoodFactor.HOMELESS), REST, 0);
        state.evolve(REST, 3 * 60 * MINUTE);
        assertEquals(MoodSpell.DEPRESSION, state.spell(), "a grief left alone in misery turns into depression: " + state.view(REST, 3 * 60 * MINUTE));
    }

    // Three frights in a quarter of an hour make a mob dread everyone: it trades with no one and does not turn to the player.
    @Test void frightsAddUpToDread() {
        MobState state = new MobState();
        for (int i = 0; i < 3; i++) state.shift(MobState.Event.THREAT, REST, i * MINUTE);
        state.evolve(REST, 3 * MINUTE);
        MobState.View scared = state.view(REST, 3 * MINUTE);
        assertEquals(MoodSpell.DREAD, scared.spell());
        assertTrue(scared.refusesDeals() && !scared.keen());
    }

    // A wedding brings love, a long happy stretch joy; a blow breaks a joy.
    @Test void brightMoods() {
        MobState love = new MobState();
        love.shift(MobState.Event.ROMANCE, REST, 0);
        assertEquals(MoodSpell.LOVE, love.spell());
        assertTrue(love.view(REST, 0).generous());
        MobState happy = new MobState();
        happy.surroundings(EnumSet.of(MoodFactor.FRIEND_NEAR, MoodFactor.FAMILY_NEAR, MoodFactor.SUNNY), REST, 0);
        for (int i = 0; i < 4; i++) happy.shift(MobState.Event.FAVOR_DONE, REST, i);
        for (long t = 0; t <= 10 * MINUTE; t += MINUTE) happy.evolve(REST, t);
        assertEquals(MoodSpell.JOY, happy.spell(), happy.view(REST, 10 * MINUTE).toString());
        for (int i = 0; i < 4; i++) happy.shift(MobState.Event.HIT, REST, 31 * MINUTE + i);
        happy.evolve(REST, 31 * MINUTE + 10);
        assertNotEquals(MoodSpell.JOY, happy.spell(), "blows end a joy");
    }

    // Trust and interest are kept per player: a blow from one leaves the mob wary of that one only; its nerves are its own for all.
    @Test void trustIsPerPlayer() {
        MobState state = new MobState();
        MobState.Baseline steve = REST.toward("steve"), alex = REST.toward("alex");
        state.shift(MobState.Event.HIT, steve, 0);
        assertEquals(REST.trust() - 35, state.view(steve, 0).trust());
        assertEquals(REST.trust(), state.view(alex, 0).trust(), "the other player is not to blame");
        assertEquals(state.view(steve, 0).calm(), state.view(alex, 0).calm(), "the fright is the mob's own");
        state.shift(MobState.Event.KIND, alex, MINUTE);
        assertTrue(state.view(alex, MINUTE).trust() > REST.trust() && state.view(steve, MINUTE).trust() < REST.trust() - 20);
        assertEquals(REST.trust(), state.view(steve, 24 * 60 * MINUTE).trust(), "forgotten in a day");
        state.settle(steve, 24 * 60 * MINUTE);
        assertTrue(state.regards.isEmpty(), "a regard back at rest is dropped");
    }

    // Off in the settings: no long moods, no world.
    @Test void switchedOff() {
        MobTalkConfig config = new MobTalkConfig();
        config.moodSpellPercent = 0; config.moodWorldPercent = 0;
        Tuning.apply(config);
        try {
            MobState state = new MobState();
            state.shift(MobState.Event.BEREAVED, REST, 0);
            assertNull(state.spell());
            int mood = state.view(REST, 0).mood();
            state.surroundings(EnumSet.of(MoodFactor.RAIN), REST, 0);
            assertEquals(mood, state.view(REST, 0).mood());
        } finally { Tuning.apply(null); }
    }

    // The model hears the long mood, its reasons, a shorter word limit and the state as the very last word.
    @Test void thePromptCarriesTheLongMood() {
        MobTalkConfig config = new MobTalkConfig();
        try (OpenRouterConversationClient client = new OpenRouterConversationClient("unit-test-key", config, URI.create("http://127.0.0.1:1/chat"))) {
            ConversationStore.Profile profile = new ConversationStore.Profile();
            profile.type = "minecraft:villager"; profile.name = "Житель"; profile.personality = ""; profile.voiceId = "x"; profile.variantSlot = 0;
            profile.calm = 50; profile.confidence = 50; profile.curiosity = 50; profile.sociability = 50; profile.preference = "тишина"; profile.fear = "зомби";
            profile.customPersonality = ""; profile.relationship = 0; profile.recentEvents = List.of();
            profile.stateView = new MobState.View(15, 45, 30, 45, 25, MoodSpell.DEPRESSION, 30 * MINUTE,
                    List.of(new MobState.Reason("смерть соседа (Пётр)", -1, 20, false), new MobState.Reason("голод", -1, 15, true)), "");
            MobSnapshot snapshot = new MobSnapshot(UUID.randomUUID(), "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                    "minecraft:stick", List.of(), List.of(), UUID.randomUUID(), Map.of("name", "Игрок"), Map.of("lastAttacker", "none"), Map.of(), List.of(), List.of(ActionType.NONE), 0);
            String system = client.systemPrompt(SpeciesCatalog.get("minecraft:villager"), profile, snapshot);
            assertTrue(system.contains(MoodSpell.DEPRESSION.prompt), system);
            assertTrue(system.contains("смерть соседа (Пётр); голод"), system);
            int limit = Math.round(config.maxReplyWords * 0.4f);
            assertTrue(system.contains("Длина: не больше " + limit + " слов"), system);
            assertTrue(system.strip().endsWith("и в том, на что ты соглашаешься."), "the state is the last word: " + system.substring(system.length() - 300));
        }
    }

    // The world watch in the store: hunger after an empty larder, a meal when food shows up, a sleepless night until it sleeps.
    @TempDir Path directory;
    @Test void theStoreTracksHungerAndSleep() throws Exception {
        UUID mob = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        store.recordEvent(mob, "minecraft:villager", "Житель", "Знакомство", 0);
        long now = System.currentTimeMillis();
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, false, 5, false), now);
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, false, 5, false), now + 61 * MINUTE);
        assertTrue(store.stateView(mob, null).reasons().stream().anyMatch(r -> r.text().equals("голод")), store.stateView(mob, null).toString());
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 5, false), now + 62 * MINUTE);
        assertTrue(store.stateView(mob, null).reasons().stream().anyMatch(r -> r.text().equals("сытная еда")));
        // Night 6: seen awake again and again, never asleep — the next day it is sleepless; a sleep ends that.
        for (int i = 0; i < 7; i++) store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 6, true), now + 63 * MINUTE + i * 10_000L);
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 6, false), now + 70 * MINUTE);
        assertTrue(store.stateView(mob, null).reasons().stream().anyMatch(r -> r.text().equals("бессонная ночь") && r.lasting()));
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, true, true, 7, true), now + 75 * MINUTE);
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 7, false), now + 80 * MINUTE);
        MobState.View morning = store.stateView(mob, null);
        assertTrue(morning.reasons().stream().anyMatch(r -> r.text().equals("хороший сон")), morning.toString());
        assertTrue(morning.reasons().stream().noneMatch(r -> r.text().equals("бессонная ночь") && r.lasting()));
    }
}
