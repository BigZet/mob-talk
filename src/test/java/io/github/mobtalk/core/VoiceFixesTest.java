package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Голос из моба: кривая затухания, отсрочка потери из виду и единственный голос на полу. */
class VoiceFixesTest {
    // ---- Затухание по расстоянию.

    @Test void fullUpCloseThenFadesSmoothlyToSilence() {
        VoiceFalloff.Curve curve = new VoiceFalloff.Curve(4, 48);
        assertEquals(1, curve.gain(0)); assertEquals(1, curve.gain(4));
        assertEquals(0, curve.gain(48)); assertEquals(0, curve.gain(100));
        double previous = 1;
        for (double d = 4.5; d < 48; d += 0.5) {
            double g = curve.gain(d);
            assertTrue(g < previous && g > 0, "falls at " + d);
            assertTrue(previous - g < 0.03, "no step at " + d); // Без ступенек: полблока — не больше 3 % громкости.
            previous = g;
        }
        // Там, где старый звук уже молчал (16 блоков линейного затухания OpenAL) и обрывался по дальности разговора, голос ещё слышен.
        assertTrue(curve.gain(16) > 0.4); assertTrue(curve.gain(33) > 0.05);
        assertFalse(curve.beyond(47.9)); assertTrue(curve.beyond(48));
    }

    @Test void badRangesStillGiveACurve() {
        VoiceFalloff.Curve flipped = new VoiceFalloff.Curve(10, 5);
        assertEquals(1, flipped.gain(10)); assertEquals(0, flipped.gain(11));
        VoiceFalloff.Curve odd = new VoiceFalloff.Curve(Double.NaN, Double.POSITIVE_INFINITY);
        assertEquals(1, odd.gain(0)); assertEquals(1, odd.gain(Double.NaN));
        MobTalkConfig defaults = new MobTalkConfig();
        VoiceFalloff.Curve configured = VoiceFalloff.Curve.of(defaults);
        assertEquals(defaults.voiceFullRange, configured.full()); assertEquals(defaults.voiceSilentRange, configured.silent());
        assertTrue(configured.silent() > defaults.maxDistance, "the voice is heard past the conversation range");
    }

    @Test void volumeMovesInSmallSteps() {
        assertEquals(0.5, VoiceFalloff.approach(0.45, 0.5, 0.08), 1e-9);
        assertEquals(0.92, VoiceFalloff.approach(1, 0, 0.08), 1e-9);
        assertEquals(0.3, VoiceFalloff.approach(Double.NaN, 0.3, 0.08), 1e-9);
        double v = 1; int ticks = 0;
        while (v > VoiceFalloff.INAUDIBLE) { v = VoiceFalloff.approach(v, 0, VoiceFalloff.STEP_PER_TICK); ticks++; }
        assertTrue(ticks >= 10 && ticks <= 20, "a teleport fades out in about half a second, not at once: " + ticks);
    }

    // ---- Отсрочка потери из виду.

    @Test void briefLossIsHeldThenLetsGo() {
        Grace<String> sight = new Grace<>();
        assertFalse(sight.hold("cow", false, 0, 6000), "never seen: nothing to hold");
        assertTrue(sight.hold("cow", true, 1000, 6000));
        assertTrue(sight.hold("cow", false, 3000, 6000), "behind a tree for two seconds");
        assertTrue(sight.hold("cow", false, 7000, 6000), "six seconds is still held");
        assertFalse(sight.hold("cow", false, 7001, 6000), "past the grace it is lost");
        assertTrue(sight.hold("cow", true, 8000, 6000), "seen again");
        assertTrue(sight.recent("cow", 9000, 6000));
        sight.forget("cow");
        assertFalse(sight.recent("cow", 8000, 6000), "a real reason (death, unload) ends it at once");
        assertFalse(sight.hold("pig", false, 8000, 0));
        assertTrue(sight.hold("pig", true, 8000, 0));
        assertFalse(sight.hold("pig", false, 8001, 0), "zero grace: the old immediate behaviour");
    }

    @Test void graceForgetsOldEntries() {
        Grace<Integer> grace = new Grace<>();
        for (int i = 0; i < 600; i++) grace.hold(i, true, i, 1000);
        grace.hold(10_000, true, 200_000, 1000);
        assertTrue(grace.size() < 600, "far past entries are swept: " + grace.size());
        assertTrue(grace.recent(10_000, 200_000, 1000));
    }

    // ---- Один голос на полу.

    @Test void backgroundWaitsWhileAnyVoiceSounds() {
        VoiceFloor<String> floor = new VoiceFloor<>();
        UUID villager = UUID.randomUUID(), cow = UUID.randomUUID();
        assertTrue(floor.mayStart(true));
        floor.started("old line", villager, true);
        assertFalse(floor.mayStart(true), "a new background line waits for the old one");
        assertTrue(floor.mayStart(false), "the player's own answer may come");
        assertTrue(floor.speaking(villager)); assertFalse(floor.speaking(cow));
        Set<String> playing = new HashSet<>(Set.of("old line"));
        List<String> ended = new ArrayList<>();
        floor.prune(playing::contains, ended::add);
        assertFalse(floor.free(), "still sounding");
        playing.clear();
        floor.prune(playing::contains, ended::add);
        assertEquals(List.of("old line"), ended);
        assertTrue(floor.free()); assertTrue(floor.mayStart(true));
        floor.started("new line", cow, true);
        assertEquals(1, floor.peak()); assertEquals(0, floor.overlaps());
    }

    // The bug: after death and respawn the client lost its one reference to the voice and the next line started over it.
    @Test void anOverlapIsCountedAndStopAllLeavesOneVoice() {
        VoiceFloor<String> floor = new VoiceFloor<>();
        UUID villager = UUID.randomUUID(), cow = UUID.randomUUID();
        floor.started("before death", villager, true);
        floor.started("after respawn", cow, true);
        assertEquals(2, floor.peak()); assertEquals(1, floor.overlaps());
        List<String> stopped = new ArrayList<>();
        floor.stopAll(stopped::add);
        assertEquals(List.of("before death", "after respawn"), stopped);
        assertTrue(floor.free());
        floor.resetStats();
        assertEquals(0, floor.peak()); assertEquals(0, floor.overlaps());
        floor.started("answer", villager, false);
        floor.started("answer", villager, false); // The same sound twice is one voice.
        assertEquals(1, floor.count()); assertEquals(0, floor.overlaps());
        floor.started("cow", cow, true);
        floor.stopMob(villager, stopped::add);
        assertEquals("answer", stopped.getLast()); assertEquals(1, floor.count()); assertFalse(floor.speaking(villager));
    }
}
