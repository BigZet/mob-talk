package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.divineblock.core.PrayerVoicePath.Route;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Молитва голосом идёт тем же путём, что речь в Mob Talk; без Mob Talk — локально, как раньше (трек B). */
class PrayerVoicePathTest {
    @TempDir Path config;

    @Test void followsTheMobTalkChoice() {
        assertEquals(Route.LOCAL, PrayerVoicePath.choose("auto", true, true));
        assertEquals(Route.SERVER, PrayerVoicePath.choose("auto", false, true));
        assertEquals(Route.LOCAL, PrayerVoicePath.choose("auto", false, false));
        assertEquals(Route.SERVER, PrayerVoicePath.choose("server", true, true));
        assertEquals(Route.LOCAL, PrayerVoicePath.choose("server", true, false));
        assertEquals(Route.TEXT, PrayerVoicePath.choose("text", true, true));
        assertEquals(Route.LOCAL, PrayerVoicePath.choose("", false, true));
    }

    @Test void readsTheChoiceFromMobTalkConfig() throws Exception {
        assertEquals("", PrayerVoicePath.mobTalkPath(config));
        Files.writeString(config.resolve("mobtalk.json"), "{\"enabled\":true}");
        assertEquals("auto", PrayerVoicePath.mobTalkPath(config));
        Files.writeString(config.resolve("mobtalk.json"), "{\"sttPath\":\"server\"}");
        assertEquals("server", PrayerVoicePath.mobTalkPath(config));
        Files.writeString(config.resolve("mobtalk.json"), "{битый");
        assertEquals("", PrayerVoicePath.mobTalkPath(config));
    }
}
