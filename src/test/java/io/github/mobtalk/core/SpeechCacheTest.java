package io.github.mobtalk.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Кэш озвучки сервера: ключ (голос, модель, текст), LRU в памяти, диск переживает перезапуск (трек B). */
class SpeechCacheTest {
    @TempDir Path directory;

    @Test void hitsOnSameVoiceModelAndTextOnly() {
        SpeechCache cache = new SpeechCache(200, null);
        cache.put("voiceA", "eleven", "Привет", new byte[]{1, 2});
        assertArrayEquals(new byte[]{1, 2}, cache.get("voiceA", "eleven", " Привет ").orElseThrow());
        assertTrue(cache.get("voiceB", "eleven", "Привет").isEmpty(), "другой голос — промах");
        assertTrue(cache.get("voiceA", "kokoro", "Привет").isEmpty(), "другая модель — промах");
        assertTrue(cache.get("voiceA", "eleven", "Пока").isEmpty());
    }

    @Test void memoryKeepsTheLastCapacityClips() {
        SpeechCache cache = new SpeechCache(200, null);
        for (int i = 0; i < 250; i++) cache.put("v", "m", "реплика " + i, new byte[]{(byte) i, 0});
        assertEquals(200, cache.size());
        assertTrue(cache.get("v", "m", "реплика 10").isEmpty());
        assertTrue(cache.get("v", "m", "реплика 249").isPresent());
    }

    @Test void diskSurvivesRestartAndIgnoresBrokenFiles() throws Exception {
        new SpeechCache(200, directory).put("v", "m", "сохранить", new byte[]{5, 6, 7, 8});
        SpeechCache restarted = new SpeechCache(200, directory);
        assertArrayEquals(new byte[]{5, 6, 7, 8}, restarted.get("v", "m", "сохранить").orElseThrow());
        Files.write(directory.resolve(SpeechCache.key("v", "m", "битый") + ".pcm"), new byte[]{1});
        assertTrue(restarted.get("v", "m", "битый").isEmpty());
    }
}
