package io.github.mobtalk.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/** «Новый мир» из админки: заявка, затем при старте старый мир уходит в резерв, сид и тип пишутся в server.properties. */
class NewWorldTest {
    @TempDir Path dir;

    @Test void theOldWorldMovesAsideAndTheNewSeedIsSet() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "motd=Mob Talk\nlevel-name=stream\nlevel-seed=123\n", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("stream/data/mobtalk"));
        Files.writeString(dir.resolve("stream/data/mobtalk/conversations.json"), "{}");
        assertEquals("", NewWorld.apply(dir, LocalDateTime.of(2026, 10, 23, 12, 0)), "без заявки ничего не делается");
        assertEquals("stream", NewWorld.levelName(dir));

        NewWorld.request(dir, "42", "minecraft:large_biomes", "admin");
        String done = NewWorld.apply(dir, LocalDateTime.of(2026, 10, 23, 12, 0, 5));
        assertTrue(done.contains("сидом 42"), done);
        assertFalse(Files.exists(dir.resolve("stream")), "старого мира на месте нет");
        assertTrue(Files.exists(dir.resolve("world-backups/stream-20261023-120005/data/mobtalk/conversations.json")), "память мобов ушла с миром");
        String properties = Files.readString(dir.resolve("server.properties"));
        assertTrue(properties.contains("level-seed=42") && properties.contains("level-type=minecraft\\:large_biomes") && properties.contains("motd=Mob Talk"), properties);
        assertFalse(Files.exists(dir.resolve(NewWorld.MARKER)), "заявка исполнена один раз");
    }

    @Test void aBadTypeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> NewWorld.request(dir, "", "minecraft:moon", "admin"));
    }
}
