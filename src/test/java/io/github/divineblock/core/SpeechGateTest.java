package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The god speaks first, the roulette after him: the reels wait for his voice to end (or a deadline), the punishment falls
 * exactly when the reels end, a pardon while he speaks takes it all back; and his word before a punishment is short. */
class SpeechGateTest {
    private static final UUID SINNER = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Test void theReelsWaitForTheWordAndThePunishmentForTheReels() {
        SpeechGate gate = new SpeechGate();
        RouletteQueue queue = new RouletteQueue();
        Roulette.Timing timing = Roulette.Timing.of(Roulette.Mode.NORMAL, 3);
        List<String> events = new ArrayList<>();
        long[] tick = { 1000 };
        gate.open(SINNER, tick[0], SpeechGate.timeoutTicks(20), () -> queue.offer(SINNER, tick[0], timing, new RouletteQueue.Hooks() {
            @Override public void started(RouletteQueue.Spin spin) { events.add("spin@" + spin.start); }
            @Override public void act(RouletteQueue.Spin spin) { events.add("act@" + tick[0]); }
        }));
        assertTrue(gate.waiting(SINNER));
        // The voice plays for six seconds: nothing starts meanwhile.
        for (; tick[0] < 1120; tick[0]++) { gate.tick(tick[0]); queue.tick(tick[0]); }
        assertTrue(events.isEmpty(), "no reels while the god speaks: " + events);
        gate.done(SINNER, tick[0]);
        long released = tick[0] + SpeechGate.TAIL_TICKS;
        for (; tick[0] < 1600; tick[0]++) { gate.tick(tick[0]); queue.tick(tick[0]); }
        assertEquals(List.of("spin@" + released, "act@" + (released + timing.act())), events, "the reels after the echo's pause, the act exactly after them");
        assertFalse(gate.waiting(SINNER));
    }

    @Test void aSilentClientIsNotWaitedForBeyondTheDeadline() {
        SpeechGate gate = new SpeechGate();
        List<Long> started = new ArrayList<>();
        int timeout = SpeechGate.timeoutTicks(10);
        long[] tick = { 0 };
        gate.open(SINNER, 0, timeout, () -> started.add(tick[0]));
        for (; tick[0] < 1000; tick[0]++) gate.tick(tick[0]);
        assertEquals(List.of((long) timeout), started, "the reels go at the deadline");
        assertEquals(Math.round((4 + 2) * 20), SpeechGate.timeoutTicks(10), "ten words: four seconds of speech and two more");
        assertEquals(Math.round((1.5 + 2) * 20), SpeechGate.timeoutTicks(1), "never less than a second and a half of speech");
        assertEquals(SpeechGate.MAX_WAIT_TICKS, SpeechGate.timeoutTicks(200), "fifteen seconds at most");
        assertEquals(20 * 15, SpeechGate.MAX_WAIT_TICKS);
        // A deadline asked beyond the maximum is held to it too.
        SpeechGate other = new SpeechGate();
        List<Long> late = new ArrayList<>();
        other.open(SINNER, 0, 99_999, () -> late.add(tick[0]));
        for (tick[0] = 0; tick[0] < 1000; tick[0]++) other.tick(tick[0]);
        assertEquals(List.of((long) SpeechGate.MAX_WAIT_TICKS), late);
    }

    @Test void aPardonWhileTheGodSpeaksStopsEverything() {
        SpeechGate gate = new SpeechGate();
        List<String> events = new ArrayList<>();
        gate.open(SINNER, 0, 200, () -> events.add("spin"), () -> events.add("cancelled"));
        gate.tick(10);
        assertTrue(gate.cancel(SINNER), "repented before the reels");
        gate.done(SINNER, 20);
        for (long t = 0; t < 500; t++) gate.tick(t);
        assertEquals(List.of("cancelled"), events, "no roulette, no punishment");
        assertFalse(gate.cancel(SINNER), "nothing left");
        assertEquals(0, SpeechGate.words(""));
        assertEquals(3, SpeechGate.words(" Гнев мой  грядёт "));
    }

    @Test void theWordBeforeAPunishmentIsShort() {
        String sermon = "Над башней твоей кружат вороны, и тень их длиннее твоих дней. Ты жёг дома соседей, как жгут сухую траву, и пепел их стучит в мои врата. "
                + "Ныне чаша полна. Колокол бьёт в последний раз.";
        String brief = GodSpeech.shorten(sermon, GodSpeech.BRIEF_WORDS);
        assertTrue(SpeechGate.words(brief) <= GodSpeech.BRIEF_WORDS, brief);
        assertTrue(brief.endsWith("."), "whole sentences: " + brief);
        assertEquals("Над башней твоей кружат вороны, и тень их длиннее твоих дней.", brief, "the sentences that fit, from the start");
        assertEquals("Пожни посеянное.", GodSpeech.shorten("Пожни посеянное.", 25), "a short line stays");
        String endless = "Слово ".repeat(40).strip() + ".";
        String cut = GodSpeech.shorten(endless, 25);
        assertEquals(25, SpeechGate.words(cut), "one endless sentence is cut at the limit");
        assertTrue(cut.endsWith("…"));
        // Only punishments are shortened, and only while the settings ask for it.
        DivineConfig config = new DivineConfig();
        assertEquals(DivineConfig.SpeechLength.SHORT, config.wrathSpeech, "short by default");
        Verdict wrath = new Verdict(); wrath.inclination = Miracle.Kind.WRATH; wrath.miracle = Miracle.METEOR.id();
        Verdict mercy = new Verdict(); mercy.inclination = Miracle.Kind.HELP; mercy.miracle = Miracle.HEAL.id();
        Verdict jest = new Verdict(); jest.inclination = Miracle.Kind.JEST; jest.miracle = Miracle.FLIP.id();
        Verdict forgiven = new Verdict(); forgiven.inclination = Miracle.Kind.OMEN; forgiven.forgiveness = true;
        assertTrue(GodSpeech.brief(wrath, config));
        assertFalse(GodSpeech.brief(mercy, config), "mercy is not cut");
        assertFalse(GodSpeech.brief(jest, config), "jests are not cut");
        assertFalse(GodSpeech.brief(forgiven, config));
        config.wrathSpeech = DivineConfig.SpeechLength.NORMAL;
        assertFalse(GodSpeech.brief(wrath, config), "the long line when the settings ask for it");
        assertNotNull(DivineSettings.setting("wrathSpeech"));
        assertTrue(GodSpeech.BRIEF.contains("одна-две"), "the prompt asks for one or two sentences");
        // The templates of wrath are a single short sentence.
        Verdict warned = new Verdict(); warned.inclination = Miracle.Kind.WRATH; warned.miracle = Miracle.METEOR.id(); warned.warned = true;
        warned.reasons.add(new Verdict.Reason("хула на бога", -5, 2));
        assertTrue(SpeechGate.words(GodSpeech.template(warned)) <= 10, GodSpeech.template(warned));
    }
}
