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

    // Insults anger a mob, they do not frighten it: three rude words make it embittered, even a nervous one, not panicked.
    @Test void insultsAddUpToAnger() {
        MobState.Baseline nervous = MobState.Baseline.of(50, 25, 50, 50, 0);
        MobState state = new MobState();
        for (int i = 0; i < 3; i++) state.shift(MobState.Event.INSULT, nervous, i * MINUTE);
        state.evolve(nervous, 3 * MINUTE);
        MobState.View sore = state.view(nervous, 3 * MINUTE);
        assertEquals(MoodSpell.ANGER, sore.spell());
        assertEquals("embittered", sore.marker().key());
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
            assertTrue(system.contains(MoodSpell.DEPRESSION.prompt(false)), system);
            assertTrue(system.contains("Ты голоден"), system);
            assertTrue(system.contains("смерть соседа (Пётр); голод"), system);
            int limit = Math.round(config.maxReplyWords * 0.4f);
            assertTrue(system.contains("Длина: не больше " + limit + " слов"), system);
            assertTrue(system.strip().endsWith("и в том, на что ты соглашаешься."), "the state is the last word: " + system.substring(system.length() - 300));
        }
    }

    // The world watch in the store: hunger grows from the last meal, a meal ends it at once, a sleepless night until it sleeps.
    @TempDir Path directory;
    @Test void theStoreTracksHungerAndSleep() throws Exception {
        UUID mob = UUID.randomUUID();
        MobTalkConfig config = new MobTalkConfig();
        int span = config.moodHungerMinutes;
        ConversationStore store = ConversationStore.open(directory, config);
        store.recordEvent(mob, "minecraft:villager", "Житель", "Знакомство", 0);
        long now = System.currentTimeMillis();
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 5, false), now);
        assertEquals(Hunger.Level.FED, store.hunger(mob, now + (span - 1) * MINUTE));
        assertEquals(Hunger.Level.PECKISH, store.hunger(mob, now + (span + 1) * MINUTE));
        assertEquals(Hunger.Level.HUNGRY, store.hunger(mob, now + (2 * span + 1) * MINUTE));
        assertEquals(Hunger.Level.STARVING, store.hunger(mob, now + (4 * span + 1) * MINUTE));
        long late = now + (4 * span + 1) * MINUTE;
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, false, 5, false), late);
        MobState.View starving = store.stateView(mob, null);
        assertTrue(starving.hungry(MoodFactor.STARVING), starving.toString());
        assertTrue(starving.reasons().stream().anyMatch(r -> r.text().equals("пустые закрома в деревне")), starving.toString());
        assertTrue(starving.describe().contains("давно ничего не ел"), starving.describe());
        // A player feeds it bread: full again, the hunger is gone at once, and that player is trusted more.
        UUID player = UUID.randomUUID();
        int trust = store.stateView(mob, player).trust();
        store.meal(mob, Hunger.meal("minecraft:bread"), "Дима", player, "Дима", late + 1000);
        assertEquals(Hunger.Level.FED, store.hunger(mob, late + 2000));
        MobState.View fed = store.stateView(mob, player);
        assertFalse(fed.hungry(MoodFactor.STARVING) || fed.hungry(MoodFactor.HUNGRY), fed.toString());
        assertTrue(fed.trust() > trust, trust + " → " + fed.trust());
        assertTrue(fed.reasons().stream().anyMatch(r -> r.text().startsWith("накормили в голод")), fed.toString());
        // A raw carrot fills half as long, a pie half as long again; wheat is not a meal.
        assertEquals(Hunger.Meal.MEAGER, Hunger.meal("carrot"));
        assertEquals(Hunger.Meal.TREAT, Hunger.meal("minecraft:pumpkin_pie"));
        assertNull(Hunger.meal("minecraft:wheat"));
        assertEquals(Hunger.Level.PECKISH, Hunger.level(1, Hunger.fullFor(Hunger.Meal.MEAGER, span), 1 + span * MINUTE, span));
        // Night 6: seen awake again and again, never asleep — the next day it is sleepless; a sleep ends that.
        long t = late + 3000;
        for (int i = 0; i < 7; i++) store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 6, true), t + i * 10_000L);
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 6, false), t + 7 * MINUTE);
        assertTrue(store.stateView(mob, null).reasons().stream().anyMatch(r -> r.text().equals("бессонная ночь") && r.lasting()));
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, true, true, 7, true), t + 12 * MINUTE);
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 7, false), t + 17 * MINUTE);
        MobState.View morning = store.stateView(mob, null);
        assertTrue(morning.reasons().stream().anyMatch(r -> r.text().equals("хороший сон")), morning.toString());
        assertTrue(morning.reasons().stream().noneMatch(r -> r.text().equals("бессонная ночь") && r.lasting()));
        store.flush();
    }

    // Apathy is its own long mood: a depression that ran its course unhelped burns out into it, and so does long starving;
    // it is not a depression on top of a depression.
    // Food handed to a hungry villager is a meal, not only a present: the hunger goes, even when the same food bored it before.
    @Test void foodHandedToAHungryVillagerFeedsIt() throws Exception {
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        MobTalkConfig config = new MobTalkConfig();
        ConversationStore store = ConversationStore.open(directory, config);
        store.recordEvent(mob, "minecraft:villager", "Светлана", "Знакомство", 0);
        long now = System.currentTimeMillis();
        store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, true, 5, false), now);
        long late = now + (4 * config.moodHungerMinutes + 1) * MINUTE;
        assertEquals(Hunger.Level.STARVING, store.hunger(mob, late));
        for (int i = 0; i < 4; i++) store.social().giftFromHand(mob, "minecraft:villager", "Светлана", player, "Дима", "minecraft:bread", "minecraft:none", false, false, late + i);
        assertEquals(Hunger.Level.FED, store.hunger(mob, late + 10));
        long again = late + 10 + (4 * config.moodHungerMinutes + 1) * MINUTE;
        assertEquals(Hunger.Level.STARVING, store.hunger(mob, again));
        GestureIntent bored = store.social().giftFromHand(mob, "minecraft:villager", "Светлана", player, "Дима", "minecraft:bread", "minecraft:none", false, false, again);
        assertTrue(bored.takesItem(), bored.toString());
        assertEquals(Hunger.Level.FED, store.hunger(mob, again + 1));
    }

    @Test void apathyFollowsAnUnhelpedDepressionOrStarving() {
        MobState state = new MobState();
        long now = 1_000_000L;
        state.view(REST, now);
        assertTrue(state.begin(MoodSpell.DEPRESSION, REST, now));
        state.evolve(REST, now + 400 * MINUTE);
        assertEquals(MoodSpell.APATHY, state.view(REST, now + 400 * MINUTE).spell());
        assertTrue(MoodSpell.APATHY.bedridden() && !MoodSpell.DEPRESSION.bedridden() && !MoodSpell.GRIEF.bedridden());
        assertEquals(16, state.view(REST, now + 400 * MINUTE).face());

        MobState hungry = new MobState();
        hungry.view(REST, now);
        hungry.surroundings(EnumSet.of(MoodFactor.STARVING), REST, now);
        for (int m = 1; m <= 40; m++) hungry.evolve(REST, now + m * MINUTE);
        assertTrue(Set.of(MoodSpell.APATHY, MoodSpell.EXHAUSTION).contains(hungry.view(REST, now + 40 * MINUTE).spell()), hungry.view(REST, now + 40 * MINUTE).toString());
    }

    // A woman's state speaks in the feminine, on the nameplate and in the prompt.
    @Test void womenGetFeminineWords() {
        MobState.Baseline her = REST.asFemale(true);
        MobState state = new MobState();
        long now = 1_000_000L;
        state.view(her, now);
        state.begin(MoodSpell.DREAD, her, now);
        MobState.View view = state.view(her, now + MINUTE);
        assertTrue(view.female());
        assertEquals("запугана", view.marker().word());
        assertTrue(view.describe().contains("Ты запугана"), view.describe());
        assertEquals("запуган", MoodSpell.DREAD.word(false));
        assertEquals("влюблена", MoodSpell.LOVE.word(true));
        assertEquals("спокойна", new MobState.View(50, 50, 50, 50, 60, null, 0, List.of(), "", true).descriptor(MobState.Axis.CALM).word());
        assertFalse(MoodSpell.APATHY.prompt(true).contains("{"));
    }

    // The face over the head follows the long mood first, then panic and tiredness, then the mood itself.
    @Test void theFaceFollowsTheState() {
        assertEquals(0, new MobState.View(90, 60, 50, 50, 60, null, 0, List.of(), "").face());
        assertEquals(2, new MobState.View(50, 60, 50, 50, 60, null, 0, List.of(), "").face());
        assertEquals(4, new MobState.View(10, 60, 50, 50, 60, null, 0, List.of(), "").face());
        assertEquals(5, new MobState.View(50, 10, 50, 50, 60, null, 0, List.of(), "").face());
        assertEquals(6, new MobState.View(50, 60, 50, 50, 10, null, 0, List.of(), "").face());
        assertEquals(7, new MobState.View(50, 60, 50, 50, 60, MoodSpell.GRIEF, 0, List.of(), "").face());
        assertEquals(15, new MobState.View(50, 60, 50, 50, 60, MoodSpell.GRACE, 0, List.of(), "").face());
        assertEquals(MoodSpell.LOVE, MoodSpell.byKey("in_love"));
    }

    // The admin sees the village as a whole: averages, the low ones, long moods and the reasons they share.
    @Test void theVillageMoodSumsItsResidents() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        long now = System.currentTimeMillis();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), stranger = UUID.randomUUID();
        for (UUID mob : List.of(a, b, stranger)) store.recordEvent(mob, "minecraft:villager", "Житель", "Знакомство", now);
        store.setBackstory(a, "", "", "", "", "v1");
        store.setBackstory(b, "", "", "", "", "v1");
        store.setBackstory(stranger, "", "", "", "", "v2");
        for (UUID mob : List.of(a, b)) store.surroundings(mob, null, new MoodSample(EnumSet.of(MoodFactor.STORM), Set.of(), true, false, true, 5, false), now);
        store.recordMood(a, MobState.Event.BEREAVED, "Ольга", now);
        ConversationStore.VillageMood mood = store.villageMood("v1", now + 1000);
        assertEquals(2, mood.counted());
        assertEquals(1, mood.spells().get("grief"));
        assertEquals("гроза", mood.reasons().getFirst().text());
        assertEquals(2, mood.reasons().getFirst().count());
        assertTrue(mood.reasons().getFirst().sign() < 0);
        assertEquals(0, store.villageMood("nowhere", now).counted());
        store.flush(); // The save runs behind; finish it before the temporary folder is removed.
    }

    // From the live probe: a threat from the very player in front of it is his, not a third person's; a starving one is hungry, not indifferent.
    @Test void reasonsFromTheSpeakerAndHungerOnTheNameplate() {
        MobState.View scared = new MobState.View(30, 5, 50, 20, 60, MoodSpell.DREAD, 0, List.of(new MobState.Reason("угроза (Стив)", -1, 60, false)), "", true);
        assertTrue(scared.describe("Стив").contains("угроза — от него, игрока Стив, с которым ты сейчас говоришь"), scared.describe("Стив"));
        assertTrue(scared.describe("Стив").contains("именно тот, кто сейчас перед тобой"));
        assertTrue(scared.describe("Алекс").contains("угроза (Стив)") && !scared.describe("Алекс").contains("именно тот"));
        MobState.View starving = new MobState.View(45, 38, 20, 50, 61, null, 0, List.of(new MobState.Reason(MoodFactor.STARVING.text, -1, 60, true)), "", true);
        assertEquals("starving", starving.marker().key());
        assertEquals("изголодалась", starving.marker().word());
    }
}
