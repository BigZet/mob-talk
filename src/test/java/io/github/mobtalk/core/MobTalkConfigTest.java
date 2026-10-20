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
        draft.speciesOverrides.get("minecraft:cow").archetypePrompts[0] = "changed";
        draft.speciesOverrides.get("minecraft:cow").voiceIds[0] = "zzzzzzzzzzzzzzzzzzzz";
        assertCustomized(original);
    }

    // A fine-tuned model is chosen by its folder name in models/ or an absolute path; junk falls back to large-v3.
    @Test void acceptsLocalWhisperModelsAndRejectsJunk() throws Exception {
        Path file = directory.resolve("whisper.json");
        String absolute = directory.resolve("models/mobtalk-small-ru").toAbsolutePath().toString();
        for (String model : new String[] {"large-v3-turbo", "mobtalk-small-ru", absolute}) {
            MobTalkConfig config = new MobTalkConfig();
            config.whisperModel = model;
            MobTalkConfig.save(file, config);
            assertEquals(model, MobTalkConfig.load(file).whisperModel);
        }
        for (String junk : new String[] {"../models/x", "openai/whisper-small", "-flag", "a\nb"}) {
            MobTalkConfig config = new MobTalkConfig();
            config.whisperModel = junk;
            MobTalkConfig.save(file, config);
            assertEquals("large-v3-turbo", MobTalkConfig.load(file).whisperModel, junk);
        }
    }

    // A typo in the provider must fall back to OpenRouter rather than send requests nowhere.
    @Test void normalizesTheChatProvider() throws Exception {
        Path file = directory.resolve("config/mobtalk.json");
        for (var entry : java.util.Map.of("VENICE", "venice", "bogus", "openrouter", "", "openrouter").entrySet()) {
            MobTalkConfig config = new MobTalkConfig(); config.llmProvider = entry.getKey(); config.veniceModel = " ";
            MobTalkConfig.save(file, config);
            MobTalkConfig loaded = MobTalkConfig.load(file);
            assertEquals(entry.getValue(), loaded.llmProvider);
            assertEquals("deepseek-v4-1-flash", loaded.veniceModel);
        }
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
        unsafe.maxReplyWords = 1000;
        unsafe.historyTurns = 100;
        unsafe.witnessReplies = 9;
        unsafe.maxMemoryChars = 10000;
        unsafe.requestTimeoutSeconds = 100;
        unsafe.maxDistance = -10;
        unsafe.rudeness = 9;
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
        unsafe.witnessReplies = -3;
        unsafe.maxMemoryChars = -1;
        unsafe.requestTimeoutSeconds = -1;
        unsafe.maxDistance = Double.NaN;
        unsafe.rudeness = -5;
        MobTalkConfig.save(file, unsafe);
        MobTalkConfig minimum = MobTalkConfig.load(file);
        assertEquals(1, minimum.maxRecordingSeconds);
        assertEquals(5, minimum.maxReplyWords);
        assertEquals(1, minimum.historyTurns);
        assertEquals(200, minimum.maxMemoryChars);
        assertEquals(5, minimum.requestTimeoutSeconds);
        assertEquals(16, minimum.maxDistance);
        assertEquals(0, minimum.rudeness);
        assertEquals(0, minimum.witnessReplies);
    }

    private static MobTalkConfig customized() {
        MobTalkConfig config = new MobTalkConfig();
        config.enabled = false;
        config.llmModel = "provider/custom-model";
        config.llmProvider = "venice";
        config.veniceModel = "venice-uncensored-1-2";
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
        config.commonPrompt = "Отвечай содержательно";
        config.whisperBeamSize = 3;
        config.whisperVadSilenceMs = 850;
        config.whisperCpuThreads = 2;
        config.whisperHotwords = "крипер, Майнкрафт";
        SpeciesCatalog.Override override = new SpeciesCatalog.Override();
        override.prompt = "Наблюдательная корова";
        override.archetypePrompts[0] = "Свой характер";
        override.voiceIds[0] = "abcdefghijklmnopqrst";
        config.speciesOverrides.put("minecraft:cow", override);
        return config;
    }

    private static void assertCustomized(MobTalkConfig config) {
        assertFalse(config.enabled);
        assertEquals("provider/custom-model", config.llmModel);
        assertTrue(config.usesVenice());
        assertEquals("venice-uncensored-1-2", config.chatModel());
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
        assertEquals("Отвечай содержательно", config.commonPrompt);
        assertEquals(3, config.whisperBeamSize);
        assertEquals(850, config.whisperVadSilenceMs);
        assertEquals(2, config.whisperCpuThreads);
        assertEquals("крипер, Майнкрафт", config.whisperHotwords);
        assertEquals("Наблюдательная корова", config.speciesOverrides.get("minecraft:cow").prompt);
        assertEquals("Свой характер", config.speciesOverrides.get("minecraft:cow").archetypePrompts[0]);
        assertEquals("abcdefghijklmnopqrst", config.speciesOverrides.get("minecraft:cow").voiceIds[0]);
    }

    private static void assertNormalized(MobTalkConfig config) {
        assertEquals(15, config.maxRecordingSeconds);
        assertEquals(120, config.maxReplyWords);
        assertEquals(64, config.historyTurns);
        assertEquals(4000, config.maxMemoryChars);
        assertEquals(90, config.requestTimeoutSeconds);
        assertEquals(2, config.maxDistance);
        assertEquals(3, config.rudeness);
        assertEquals(2, config.witnessReplies);
        assertEquals("openai/gpt-6-luna", config.llmModel);
        assertEquals("large-v3-turbo", config.whisperModel);
        assertEquals("auto", config.whisperDevice);
        assertEquals("ru", config.whisperLanguage);
        assertEquals("", config.microphoneName);
        assertEquals("rSfuQoQ3FY8SVKeraMAp", config.defaultVoice);
    }

    // Version 3: the old shipped reasoning, Whisper model and beam move to the faster values; deliberate choices stay.
    @Test void migratesToTheFasterDefaults() throws Exception {
        Path file = directory.resolve("mobtalk.json");
        Files.writeString(file, "{\"schemaVersion\":2,\"reasoningEffort\":\"minimal\",\"whisperModel\":\"large-v3\",\"whisperBeamSize\":5,\"requestTimeoutSeconds\":70}");
        MobTalkConfig migrated = MobTalkConfig.load(file);
        assertEquals("none", migrated.reasoningEffort);
        assertEquals("large-v3-turbo", migrated.whisperModel);
        assertEquals(3, migrated.whisperBeamSize);
        assertEquals(70, migrated.requestTimeoutSeconds);
        assertEquals(12, migrated.firstTokenSeconds);
        Files.writeString(file, "{\"schemaVersion\":2,\"reasoningEffort\":\"low\",\"whisperModel\":\"medium\",\"whisperBeamSize\":7}");
        MobTalkConfig kept = MobTalkConfig.load(file);
        assertEquals("low", kept.reasoningEffort); assertEquals("medium", kept.whisperModel); assertEquals(7, kept.whisperBeamSize);
        MobTalkConfig.save(file, migrated);
        Files.writeString(file, Files.readString(file).replace("\"none\"", "\"minimal\""));
        assertEquals("minimal", MobTalkConfig.load(file).reasoningEffort, "a version-3 file keeps a minimal chosen after the update");
    }

    // Updating the old shipped defaults must retain deliberate smaller history/memory and a custom Whisper model.
    @Test void migratesOldDefaultsAndRetainsUserChoices() throws Exception {
        Path file = directory.resolve("mobtalk.json");
        Files.writeString(file, "{\"historyTurns\":6,\"maxMemoryChars\":1200,\"whisperModel\":\"small\"}");
        MobTalkConfig migrated = MobTalkConfig.load(file);
        assertEquals(24, migrated.historyTurns);
        assertEquals(4000, migrated.maxMemoryChars);
        assertEquals("large-v3-turbo", migrated.whisperModel, "small went to large-v3 in version 2, then to turbo in version 3");
        Files.writeString(file, "{\"historyTurns\":4,\"maxMemoryChars\":700,\"whisperModel\":\"tiny\"}");
        MobTalkConfig retained = MobTalkConfig.load(file);
        assertEquals(4, retained.historyTurns);
        assertEquals(700, retained.maxMemoryChars);
        assertEquals("tiny", retained.whisperModel);
    }

}
