package io.github.mobtalk.core;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MobTalkConfigTest {
    @TempDir Path directory;

    // A default-filled or shared copy would discard preferences or apply cancelled menu edits.
    @Test void copyPreservesEveryPreferenceAndAllowsIndependentEdits() {
        MobTalkConfig original = customized();
        MobTalkConfig draft = original.copy();
        assertNotSame(original, draft);
        assertCustomized(draft);
        draft.enabled = true;
        draft.llmModel = "edited/model";
        draft.maxDistance = 4;
        assertCustomized(original);
    }

    // Saving only menu fields would silently reset the worker, limits, and voice preferences.
    @Test void roundTripPreservesAllPreferences() throws Exception {
        Path file = directory.resolve("config/mobtalk.json");
        MobTalkConfig.save(file, customized());
        assertCustomized(MobTalkConfig.load(file));
    }

    // Validating after truncation or a partial write would destroy the last working configuration.
    @Test void invalidSaveKeepsPreviousFileUnchanged() throws Exception {
        Path file = directory.resolve("mobtalk.json");
        Files.writeString(file, "{\"enabled\":false,\"whisperUrl\":\"http://localhost:63919\"}");
        String previous = Files.readString(file);
        for (String invalid : new String[]{null, "http://127.0.0.1:0", "http://localhost:65536",
                "https://127.0.0.1:63919", "http://example.com:63919",
                "http://secret@localhost:63919", "http://localhost:63919?secret=value",
                "http://localhost:63919/transcribe", "bad endpoint"}) {
            MobTalkConfig config = customized();
            config.whisperUrl = invalid;
            assertThrows(IOException.class, () -> MobTalkConfig.save(file, config));
            assertEquals(previous, Files.readString(file));
        }
        assertThrows(IOException.class, () -> MobTalkConfig.save(file, null));
        assertEquals(previous, Files.readString(file));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    // A five-digit regex alone accepts unusable ports, so loading must enforce the same URI bounds.
    @Test void loadRejectsPortsOutsideTheLocalWorkerRange() throws Exception {
        Path file = directory.resolve("mobtalk.json");
        for (String invalid : new String[]{"http://127.0.0.1:0", "http://localhost:65536", "http://127.0.0.1:99999"}) {
            Files.writeString(file, "{\"whisperUrl\":\"" + invalid + "\"}");
            assertThrows(IOException.class, () -> MobTalkConfig.load(file));
        }
    }

    // Divergent load/save normalization would show safe values until the next launch or break helper startup.
    @Test void loadAndSaveShareBoundsAndNullSafeDefaults() throws Exception {
        MobTalkConfig unsafe = new MobTalkConfig();
        unsafe.maxRecordingSeconds = 100;
        unsafe.maxReplyWords = 100;
        unsafe.historyTurns = 100;
        unsafe.maxMemoryChars = 10000;
        unsafe.requestTimeoutSeconds = 100;
        unsafe.maxDistance = -10;
        unsafe.llmModel = null;
        unsafe.whisperModel = null;
        unsafe.whisperDevice = null;
        unsafe.whisperLanguage = null;
        unsafe.microphoneName = null;
        unsafe.defaultVoice = null;
        Path file = directory.resolve("mobtalk.json");
        Files.writeString(file, new GsonBuilder().serializeNulls().create().toJson(unsafe));
        assertNormalized(MobTalkConfig.load(file));
        MobTalkConfig.save(file, unsafe);
        assertNormalized(MobTalkConfig.load(file));
        assertEquals(100, unsafe.maxRecordingSeconds);
        assertNull(unsafe.whisperModel);

        unsafe.maxRecordingSeconds = -1;
        unsafe.maxReplyWords = -1;
        unsafe.historyTurns = -1;
        unsafe.maxMemoryChars = -1;
        unsafe.requestTimeoutSeconds = -1;
        unsafe.maxDistance = Double.NaN;
        MobTalkConfig.save(file, unsafe);
        MobTalkConfig minimum = MobTalkConfig.load(file);
        assertEquals(1, minimum.maxRecordingSeconds);
        assertEquals(5, minimum.maxReplyWords);
        assertEquals(1, minimum.historyTurns);
        assertEquals(200, minimum.maxMemoryChars);
        assertEquals(5, minimum.requestTimeoutSeconds);
        assertEquals(16, minimum.maxDistance);
    }

    private static MobTalkConfig customized() {
        MobTalkConfig config = new MobTalkConfig();
        config.enabled = false;
        config.llmModel = "provider/custom-model";
        config.whisperUrl = "http://localhost:12345";
        config.whisperAutoStart = false;
        config.whisperModel = "tiny";
        config.whisperDevice = "cpu";
        config.whisperLanguage = "en";
        config.microphoneName = "USB microphone";
        config.maxRecordingSeconds = 9;
        config.maxReplyWords = 22;
        config.historyTurns = 4;
        config.maxMemoryChars = 700;
        config.requestTimeoutSeconds = 30;
        config.maxDistance = 12.5;
        config.defaultVoice = "custom-voice";
        return config;
    }

    private static void assertCustomized(MobTalkConfig config) {
        assertFalse(config.enabled);
        assertEquals("provider/custom-model", config.llmModel);
        assertEquals("http://localhost:12345", config.whisperUrl);
        assertFalse(config.whisperAutoStart);
        assertEquals("tiny", config.whisperModel);
        assertEquals("cpu", config.whisperDevice);
        assertEquals("en", config.whisperLanguage);
        assertEquals("USB microphone", config.microphoneName);
        assertEquals(9, config.maxRecordingSeconds);
        assertEquals(22, config.maxReplyWords);
        assertEquals(4, config.historyTurns);
        assertEquals(700, config.maxMemoryChars);
        assertEquals(30, config.requestTimeoutSeconds);
        assertEquals(12.5, config.maxDistance);
        assertEquals("custom-voice", config.defaultVoice);
    }

    private static void assertNormalized(MobTalkConfig config) {
        assertEquals(15, config.maxRecordingSeconds);
        assertEquals(45, config.maxReplyWords);
        assertEquals(6, config.historyTurns);
        assertEquals(1200, config.maxMemoryChars);
        assertEquals(90, config.requestTimeoutSeconds);
        assertEquals(2, config.maxDistance);
        assertEquals("google/gemini-2.5-flash-lite", config.llmModel);
        assertEquals("small", config.whisperModel);
        assertEquals("auto", config.whisperDevice);
        assertEquals("ru", config.whisperLanguage);
        assertEquals("", config.microphoneName);
        assertEquals("rSfuQoQ3FY8SVKeraMAp", config.defaultVoice);
    }

}
