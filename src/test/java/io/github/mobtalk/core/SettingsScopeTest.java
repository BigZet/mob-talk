package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Раздел 16.2 плана: каждое поле настроек отнесено к слою, личный список совпадает с разделом 12.2, слои не смешиваются. */
class SettingsScopeTest {
    @Test void everyFieldHasALayer() {
        int seen = 0;
        for (Field field : MobTalkConfig.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            seen++;
            assertTrue(SettingsScope.fields().contains(field.getName()), field.getName() + " известно SettingsScope");
            assertTrue(SettingsScope.personalFields().contains(field.getName()) ^ SettingsScope.serverFields().contains(field.getName()),
                    field.getName() + " ровно в одном слое");
        }
        assertEquals(seen, SettingsScope.fields().size());
        assertEquals(seen, SettingsScope.personalFields().size() + SettingsScope.serverFields().size());
    }

    @Test void personalListMatchesThePlan() {
        List<String> personal = SettingsScope.personalFields();
        // Раздел 12.2: whisper*, микрофон, HUD и подписи, обращения к игроку, пол игрока, жители-люди.
        for (String name : List.of("whisperUrl", "whisperAutoStart", "whisperModel", "whisperDevice", "whisperLanguage", "whisperBeamSize",
                "whisperVadSilenceMs", "whisperCpuThreads", "whisperHotwords", "microphoneName", "showStateHud", "showBargainHud", "showNameTags",
                "playerNamesHostile", "playerNamesNeutral", "playerNamesClose", "playerGender", "humanVillagers"))
            assertTrue(personal.contains(name), name + " личное");
        for (String name : List.of("enabled", "llmModel", "ttsProvider", "maxRecordingSeconds", "maxDistance", "rudeness", "commonPrompt",
                "speciesOverrides", "vision", "decider", "defaultVoice", "socialLayer", "weddingCeremony", "placeClassifier", "schemaVersion"))
            assertFalse(personal.contains(name), name + " серверное");
        // Будущие поля треков B и C попадают в личные по префиксу, не трогая серверные с похожим началом.
        assertTrue(SettingsScope.personal("micGain")); assertTrue(SettingsScope.personal("hudScale")); assertTrue(SettingsScope.personal("keysTalk"));
        assertTrue(SettingsScope.personal("labelsHistory")); assertTrue(SettingsScope.personal("sttPath")); assertTrue(SettingsScope.personal("voiceVolume"));
        assertTrue(SettingsScope.personal("tableSpeed")); assertTrue(SettingsScope.personal("tableInvite"));
        assertFalse(SettingsScope.personal("cardsVariant")); assertFalse(SettingsScope.personal("cards"));
        assertFalse(SettingsScope.personal("microwave")); assertFalse(SettingsScope.personal("whisper")); assertFalse(SettingsScope.personal("voiceFullRange"));
    }

    @Test void serverOnlyDropsPersonalValuesAndKeepsServerOnes() {
        MobTalkConfig full = new MobTalkConfig();
        full.whisperModel = "small"; full.microphoneName = "USB"; full.playerGender = "male"; full.showNameTags = false; full.playerNamesClose = "Димка";
        full.llmModel = "test/model"; full.maxDistance = 9; full.rudeness = 0; full.speciesOverrides.put("minecraft:cow", new SpeciesCatalog.Override());
        MobTalkConfig server = SettingsScope.serverOnly(full);
        MobTalkConfig defaults = new MobTalkConfig();
        assertEquals(defaults.whisperModel, server.whisperModel); assertEquals("", server.microphoneName); assertEquals("any", server.playerGender);
        assertTrue(server.showNameTags); assertEquals("", server.playerNamesClose);
        assertEquals("test/model", server.llmModel); assertEquals(9, server.maxDistance); assertEquals(0, server.rudeness);
        assertTrue(server.speciesOverrides.containsKey("minecraft:cow"));
        assertEquals("small", full.whisperModel, "исходник не тронут");
    }

    @Test void mergePutsThePlayerOverTheServer() {
        MobTalkConfig server = new MobTalkConfig(); server.llmModel = "srv"; server.whisperModel = "ignored-by-server";
        MobTalkConfig personal = new MobTalkConfig(); personal.whisperModel = "mine"; personal.llmModel = "mine-too";
        MobTalkConfig merged = SettingsScope.merge(server, personal);
        assertEquals("mine", merged.whisperModel); assertEquals("srv", merged.llmModel);
        MobTalkConfig back = SettingsScope.withServerFields(personal, server);
        assertEquals("mine", back.whisperModel); assertEquals("srv", back.llmModel);
    }

    @Test void playerSettingsCarryOnlyPersonalFields() {
        MobTalkConfig mine = new MobTalkConfig(); mine.whisperModel = "small"; mine.playerGender = "female"; mine.llmModel = "hack";
        PlayerSettings settings = PlayerSettings.of(mine);
        assertEquals("small", settings.value("whisperModel").getAsString());
        assertNull(settings.value("llmModel"), "серверное поле не уходит с клиента");
        PlayerSettings parsed = PlayerSettings.fromJson(settings.toJson());
        assertEquals(settings, parsed);
        assertEquals(PlayerSettings.defaults(), PlayerSettings.fromJson("не json"));
        assertEquals(PlayerSettings.defaults(), PlayerSettings.fromJson("[1,2]"));
        PlayerSettings hostile = PlayerSettings.fromJson("{\"llmModel\":\"hack\",\"unknown\":1,\"whisperModel\":\"tiny\"}");
        assertNull(hostile.value("llmModel")); assertNull(hostile.value("unknown")); assertEquals("tiny", hostile.value("whisperModel").getAsString());
        MobTalkConfig server = new MobTalkConfig(); server.llmModel = "srv"; server.whisperModel = "server-whisper";
        MobTalkConfig applied = parsed.applyTo(server);
        assertEquals("small", applied.whisperModel); assertEquals("female", applied.playerGender); assertEquals("srv", applied.llmModel);
    }

    @Test void serverFeaturesJsonRoundTrip() {
        ServerFeatures features = new ServerFeatures("0.7.0", true, false, false, true, List.of("local", "text"), 15, "STREAMER", "BigZet");
        assertEquals(features, ServerFeatures.fromJson(features.toJson()));
        assertEquals(ServerFeatures.none(), ServerFeatures.fromJson("мусор"));
        assertEquals(ServerFeatures.none(), ServerFeatures.fromJson(""));
        assertEquals("PLAYER", new ServerFeatures(null, false, false, false, false, null, 0, null, null).level());
    }
}
