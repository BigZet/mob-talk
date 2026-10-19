package io.github.mobtalk.core;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The gesture tables, fatigue and repeats: pure logic, no Minecraft. */
class GestureTableTest {
    private static GestureTable.Context context(Persona persona, int mood, int score, int fatigue, int repeats, Courtship courtship) {
        return new GestureTable.Context(persona, Set.of(), mood, score, fatigue, repeats, courtship, true);
    }

    // The spec's worked example: a plain villager at 0 slaps a flirt 72 % of the time; a flirty one says yes 41 % of the time.
    @Test void flirtMatchesTheSpecExample() {
        List<GestureTable.Option> plain = GestureTable.options(Gesture.FLIRT, context(Persona.PEACEFUL, 50, 0, 0, 0, Courtship.NONE));
        assertEquals(5, plain.get(0).weight(), 1e-9); assertEquals(3, plain.get(1).weight(), 1e-9); assertEquals(21, plain.get(2).weight(), 1e-9);
        assertEquals(21.0 / 29, GestureTable.chance(plain, Gesture.Outcome.SLAP), 1e-9);
        List<GestureTable.Option> flirty = GestureTable.options(Gesture.FLIRT, context(Persona.FLIRTY, 50, 0, 0, 0, Courtship.NONE));
        assertEquals(17.0 / 41, GestureTable.chance(flirty, Gesture.Outcome.SUCCESS), 1e-9);
        // A spouse never slaps a flirt.
        List<GestureTable.Option> married = GestureTable.options(Gesture.FLIRT, context(Persona.PEACEFUL, 50, 95, 0, 0, Courtship.MARRIED));
        assertEquals(0, GestureTable.chance(married, Gesture.Outcome.SLAP), 1e-9);
    }

    @Test void jokesLandBetterWithTheGloomyAndTheSadAndWorseWithTheGrumpy() {
        double plain = GestureTable.chance(GestureTable.options(Gesture.JOKE, context(Persona.PEACEFUL, 50, 0, 0, 0, Courtship.NONE)), Gesture.Outcome.SUCCESS);
        double gloomy = GestureTable.chance(GestureTable.options(Gesture.JOKE, context(Persona.GLOOMY, 50, 0, 0, 0, Courtship.NONE)), Gesture.Outcome.SUCCESS);
        double sad = GestureTable.chance(GestureTable.options(Gesture.JOKE, context(Persona.PEACEFUL, 10, 0, 0, 0, Courtship.NONE)), Gesture.Outcome.SUCCESS);
        double grumpy = GestureTable.chance(GestureTable.options(Gesture.JOKE, context(Persona.GRUMPY, 50, 0, 0, 0, Courtship.NONE)), Gesture.Outcome.SUCCESS);
        assertEquals(4.0 / 6, plain, 1e-9);
        assertTrue(gloomy > plain && sad > plain && grumpy < plain);
    }

    @Test void affectionMakesHugsWelcomeAndStrangersAvoidThem() {
        double stranger = GestureTable.chance(GestureTable.options(Gesture.HUG, context(Persona.PEACEFUL, 50, 0, 0, 0, Courtship.NONE)), Gesture.Outcome.SUCCESS);
        double friend = GestureTable.chance(GestureTable.options(Gesture.HUG, context(Persona.PEACEFUL, 50, 80, 0, 0, Courtship.NONE)), Gesture.Outcome.SUCCESS);
        double spouse = GestureTable.chance(GestureTable.options(Gesture.HUG, context(Persona.PEACEFUL, 50, 80, 0, 0, Courtship.MARRIED)), Gesture.Outcome.SUCCESS);
        assertEquals(5.0 / 13, stranger, 1e-9);
        assertTrue(friend > stranger && spouse > friend);
    }

    // Fatigue and repeats feed failure, and the same gesture again in a row gives less and less.
    @Test void fatigueAndRepeatsWearAGestureOut() {
        double fresh = GestureTable.chance(GestureTable.options(Gesture.JOKE, context(Persona.PEACEFUL, 50, 0, 0, 0, Courtship.NONE)), Gesture.Outcome.FAIL);
        double tired = GestureTable.chance(GestureTable.options(Gesture.JOKE, context(Persona.PEACEFUL, 50, 0, 4, 0, Courtship.NONE)), Gesture.Outcome.FAIL);
        assertTrue(tired > fresh);
        int[] warm = new int[5];
        for (int repeats = 0; repeats < warm.length; repeats++)
            warm[repeats] = GestureTable.options(Gesture.JOKE, context(Persona.PEACEFUL, 50, 0, 0, repeats, Courtship.NONE)).getFirst().hearts();
        assertArrayEquals(new int[]{5, 3, 2, 1, 1}, warm);
        // The sensitive lose twice as much.
        assertEquals(-4, GestureTable.options(Gesture.JOKE, context(Persona.SENSITIVE, 50, 0, 0, 0, Courtship.NONE)).get(1).hearts());
    }

    @Test void theDrawFollowsTheWeightsAndFallsBackToTheLast() {
        List<GestureTable.Option> options = List.of(new GestureTable.Option(Gesture.Outcome.SUCCESS, 1, 5), new GestureTable.Option(Gesture.Outcome.FAIL, 3, -2));
        assertEquals(Gesture.Outcome.SUCCESS, GestureTable.roll(options, 0.2).outcome());
        assertEquals(Gesture.Outcome.FAIL, GestureTable.roll(options, 0.3).outcome());
        List<GestureTable.Option> zero = List.of(new GestureTable.Option(Gesture.Outcome.SUCCESS, -1, 5), new GestureTable.Option(Gesture.Outcome.SLAP, 0, -5));
        assertEquals(Gesture.Outcome.SLAP, GestureTable.roll(zero, 0.1).outcome());
    }

    @Test void rapportTiresAndRecovers() {
        Rapport rapport = new Rapport();
        rapport.record(Gesture.JOKE, 0);
        rapport.record(Gesture.JOKE, 1_000);
        assertEquals(2, rapport.fatigue(1_000));
        assertEquals(2, rapport.repeats(Gesture.JOKE, 2_000));
        assertEquals(0, rapport.repeats(Gesture.HUG, 2_000));
        assertEquals(0, rapport.repeats(Gesture.JOKE, 1_000 + Rapport.REPEAT_WINDOW_MILLIS + 1));
        assertEquals(1, rapport.fatigue(1_000 + Rapport.FATIGUE_STEP_MILLIS));
        assertEquals(0, rapport.fatigue(1_000 + 3 * Rapport.FATIGUE_STEP_MILLIS));
        rapport.record(Gesture.GIFT, 5_000);
        assertEquals(2, rapport.fatigue(5_000), "gifts do not tire");
    }

    // The same villager has the same sex and character whenever the layer meets it; a child is never flirty.
    @Test void socialProfileIsStableAndChildrenAreNotFlirty() {
        UUID id = UUID.randomUUID();
        SocialProfile first = SocialProfile.of(id, false), second = SocialProfile.of(id, false);
        assertEquals(first.gender(), second.gender()); assertEquals(first.persona(), second.persona()); assertEquals(first.traits(), second.traits());
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < 500; i++) assertNotEquals(Persona.FLIRTY, Persona.pick(random, true));
    }
}
