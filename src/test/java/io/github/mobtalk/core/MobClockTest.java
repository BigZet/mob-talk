package io.github.mobtalk.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MobClockTest {
    @TempDir Path directory;

    @AfterEach void reset() { MobClock.offset = 0; }

    /** «skip» проматывает часы мода и переживает перезапуск мира; закрытый мир сдвиг не оставляет. */
    @Test void skipWindsTheClockOnAndIsKeptWithTheWorld() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        long before = MobClock.now();
        assertEquals(30 * 60_000L, store.skip(30 * 60_000L));
        assertTrue(MobClock.now() - before >= 30 * 60_000L);
        store.skip(-5);
        assertEquals(30 * 60_000L, MobClock.offset(), "the clock never goes back");
        ConversationStore.closeWorld(directory);
        assertEquals(0, MobClock.offset());
        ConversationStore.open(directory, new MobTalkConfig());
        assertEquals(30 * 60_000L, MobClock.offset(), "kept in the world's memory");
        ConversationStore.closeWorld(directory);
    }
}
