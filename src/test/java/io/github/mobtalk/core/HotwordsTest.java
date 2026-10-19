package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HotwordsTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    // Whisper hears the names the world invented: the mob spoken to first, then villages, then every known creature, within a budget.
    @Test void knownNamesAndVillagesBecomeHotwords() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID player = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        store.createVillage("minecraft:overworld", 0, 0, "Тихий Затон", "У реки.");
        store.recordEvent(a, "minecraft:cow", "Корова", "знакомство", 1000); store.setBackstory(a, "Зорька", "Ты корова.", "", "", "");
        store.recordEvent(b, "minecraft:villager", "Житель", "знакомство", 1000); store.setBackstory(b, "Ладко", "Ты житель.", "", "", "");
        String words = store.hotwords(List.of("Пахом"), 500);
        assertTrue(words.startsWith("Пахом, Тихий Затон"), words);
        assertTrue(words.contains("Зорька") && words.contains("Ладко"), words);
        assertFalse(words.contains("Корова"), "the observed species name is not a hotword: " + words);
        String cut = store.hotwords(List.of("Пахом"), 20);
        assertEquals("Пахом, Тихий Затон", cut);
    }
}
