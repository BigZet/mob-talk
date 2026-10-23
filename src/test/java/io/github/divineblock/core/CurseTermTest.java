package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/** No curse lasts longer than twenty real minutes: the settings, the cap in {@code Curses.add}, old saves and prolonging. */
class CurseTermTest {
    @Test void theSettingIsMinutesAndNeverMoreThanTwenty() {
        DivineConfig config = new DivineConfig();
        assertEquals(20, config.curseMinutes, "the longest term by default");
        assertEquals(24_000, CurseTerm.ticks(config, 3600));
        config.curseMinutes = 5;
        assertEquals(6000, CurseTerm.ticks(config, 3600));
        config.curseMinutes = 600;
        assertEquals(20, config.sanitized().curseMinutes, "clamped on load");
        assertEquals(24_000, CurseTerm.ticks(config, 3600));
        config.lingeringCurses = false;
        assertEquals(3600, CurseTerm.ticks(config, 3600), "short curses when lingering ones are off");
        DivineSettings.Setting setting = DivineSettings.setting("curseMinutes");
        assertEquals(1, setting.min()); assertEquals(20, setting.max());
        assertNull(DivineSettings.setting("curseDays"), "the old days are gone");
    }

    @Test void anOldConfigWithDaysGetsTwentyMinutes() {
        DivineConfig old = new Gson().fromJson("{\"curseDays\":7,\"lingeringCurses\":true}", DivineConfig.class).sanitized();
        assertEquals(20, old.curseMinutes);
    }

    @Test void everyTermIsCappedAndOldCursesAreTrimmed() {
        assertEquals(CurseTerm.MAX_TICKS, CurseTerm.cap(24_000L * 7), "a week of curse is twenty minutes");
        assertEquals(1200, CurseTerm.cap(1200));
        long now = 100_000;
        assertEquals(now + 24_000, CurseTerm.trim(now + 24_000 * 3, now), "saved for three days: cut to twenty minutes");
        assertEquals(now + 500, CurseTerm.trim(now + 500, now), "a shorter one keeps its end");
        assertEquals(now + 24_000, CurseTerm.prolong(now + 20_000, now, 6000), "prolonged, but not past the ceiling");
        assertEquals(now + 6000, CurseTerm.prolong(now - 10, now, 6000), "an expired one runs from now");
    }
}
