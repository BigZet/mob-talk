package io.github.mobtalk.server.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Смерти игроков на карте админки: метки своего мира, переживают перезапуск. */
class DeathsTest {
    @TempDir Path dir;

    @Test void deathsBecomeMarkersOfTheirWorldAndSurviveARestart() {
        Path file = dir.resolve("config/mobtalk-deaths.json");
        Deaths deaths = new Deaths(file);
        deaths.add(1000, UUID.randomUUID(), "BigZet", "minecraft:overworld", 12, 64, -40, "BigZet упал с высоты");
        deaths.add(2000, UUID.randomUUID(), "Test2", "minecraft:the_nether", 1, 70, 2, "Test2 сгорел");
        var markers = new Deaths(file).markers("minecraft:overworld");
        assertEquals(1, markers.size());
        assertEquals("death", Json.string(markers.getFirst(), "kind"));
        assertEquals("† BigZet: BigZet упал с высоты", Json.string(markers.getFirst(), "name"));
        assertEquals(-40, markers.getFirst().get("z").getAsInt());
    }
}
