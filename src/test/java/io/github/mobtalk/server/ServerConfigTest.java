package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.PlayerSettings;
import io.github.mobtalk.core.SettingsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Раздел 16.2 плана: чтение и запись {@code mobtalk-server.json}, миграция из {@code mobtalk.json} одиночки, слушатели при
 * {@code update}, неизменяемость снимка; плюс хранилище личных настроек по UUID. */
class ServerConfigTest {
    @TempDir Path dir;
    @BeforeEach void fresh() { ServerConfig.resetForTests(); }
    @AfterEach void cleanup() { ServerConfig.resetForTests(); }

    @Test void createsTheFileWithDefaultsAndNoPersonalKeys() throws Exception {
        ServerConfig.init(dir);
        Path file = dir.resolve(ServerConfig.FILE);
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("\"llmModel\""));
        for (String personal : SettingsScope.personalFields()) assertFalse(json.contains("\"" + personal + "\""), personal + " не пишется");
        assertTrue(json.contains("\"streamer\"")); assertTrue(json.contains("\"admins\""));
        assertEquals(new MobTalkConfig().llmModel, ServerConfig.get().llmModel);
        assertTrue(ServerConfig.persistent());
    }

    @Test void migratesServerFieldsFromTheSingleplayerFile() throws Exception {
        MobTalkConfig single = new MobTalkConfig();
        single.llmModel = "test/migrated"; single.maxDistance = 11; single.whisperModel = "small"; single.playerGender = "male";
        MobTalkConfig.save(dir.resolve("mobtalk.json"), single);
        ServerConfig.init(dir);
        assertEquals("test/migrated", ServerConfig.get().llmModel);
        assertEquals(11, ServerConfig.get().maxDistance);
        assertEquals(new MobTalkConfig().whisperModel, ServerConfig.get().whisperModel, "личное значение не переехало");
        assertEquals("any", ServerConfig.get().playerGender);
        assertTrue(Files.exists(dir.resolve(ServerConfig.FILE)), "серверный файл создан");
        // Повторный старт читает уже серверный файл, а не одиночный.
        single.llmModel = "test/changed-later"; MobTalkConfig.save(dir.resolve("mobtalk.json"), single);
        ServerConfig.resetForTests(); ServerConfig.init(dir);
        assertEquals("test/migrated", ServerConfig.get().llmModel);
    }

    @Test void updateSavesNotifiesAndKeepsOldSnapshotsIntact() throws Exception {
        ServerConfig.init(dir);
        List<MobTalkConfig> seen = new ArrayList<>();
        ServerConfig.listen(seen::add);
        MobTalkConfig before = ServerConfig.get();
        double oldDistance = before.maxDistance;
        ServerConfig.update(c -> { c.maxDistance = 7; c.whisperModel = "sneaky"; return c; });
        assertEquals(1, seen.size());
        assertEquals(7, ServerConfig.get().maxDistance);
        assertEquals(7, seen.get(0).maxDistance);
        assertEquals(oldDistance, before.maxDistance, "старый снимок не изменился");
        assertNotSame(before, ServerConfig.get());
        assertEquals(new MobTalkConfig().whisperModel, ServerConfig.get().whisperModel, "личное поле в update отбрасывается");
        ServerConfig.resetForTests(); ServerConfig.init(dir);
        assertEquals(7, ServerConfig.get().maxDistance, "записано на диск");
    }

    @Test void streamerAndAdminsSurviveRestart() throws Exception {
        ServerConfig.init(dir);
        UUID star = UUID.randomUUID(), admin = UUID.randomUUID();
        ServerConfig.setStreamer(star); ServerConfig.setAdmins(Set.of(admin));
        ServerConfig.resetForTests(); ServerConfig.init(dir);
        assertEquals(star, ServerConfig.streamer());
        assertEquals(Set.of(admin), ServerConfig.admins());
        ServerConfig.setStreamer(null);
        ServerConfig.resetForTests(); ServerConfig.init(dir);
        assertNull(ServerConfig.streamer());
    }

    @Test void brokenFileFailsLoudlyInsteadOfSilentDefaults() throws Exception {
        Files.writeString(dir.resolve(ServerConfig.FILE), "{ not json");
        assertThrows(java.io.IOException.class, () -> ServerConfig.init(dir));
    }

    @Test void adoptKeepsTheIntegratedServerOutOfTheFile() {
        MobTalkConfig client = new MobTalkConfig(); client.llmModel = "client/model"; client.whisperModel = "client-whisper";
        List<MobTalkConfig> seen = new ArrayList<>();
        ServerConfig.listen(seen::add);
        ServerConfig.adopt(client);
        assertEquals("client/model", ServerConfig.get().llmModel);
        assertEquals(new MobTalkConfig().whisperModel, ServerConfig.get().whisperModel);
        assertFalse(ServerConfig.persistent());
        assertEquals(1, seen.size());
        ServerConfig.update(c -> { c.maxDistance = 3; return c; });
        assertFalse(Files.exists(dir.resolve(ServerConfig.FILE)), "в одиночке файл сервера не пишется");
    }

    @Test void playerSettingsStoreKeepsEveryPlayerByUuid() throws Exception {
        Path world = dir.resolve("world");
        PlayerSettingsStore.open(world);
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        assertEquals(PlayerSettings.defaults(), PlayerSettingsStore.of(one));
        PlayerSettingsStore.put(one, PlayerSettings.fromJson("{\"whisperModel\":\"small\",\"llmModel\":\"hack\"}"));
        PlayerSettingsStore.put(two, PlayerSettings.fromJson("{\"playerGender\":\"female\"}"));
        PlayerSettingsStore.put(one, PlayerSettings.fromJson("{\"whisperModel\":\"tiny\"}")); // Повторный вход заменяет.
        PlayerSettingsStore.flush();
        Path file = world.resolve("data").resolve("mobtalk").resolve(PlayerSettingsStore.FILE);
        assertTrue(Files.exists(file));
        assertFalse(Files.readString(file).contains("llmModel"));
        PlayerSettingsStore.close();
        assertEquals(0, PlayerSettingsStore.size());
        PlayerSettingsStore.open(world);
        assertEquals(2, PlayerSettingsStore.size());
        assertEquals("tiny", PlayerSettingsStore.of(one).value("whisperModel").getAsString());
        assertEquals("female", PlayerSettingsStore.of(two).value("playerGender").getAsString());
        PlayerSettingsStore.close();
        // Повреждённый файл не роняет сервер: пустое хранилище.
        Files.writeString(file, "{{{");
        PlayerSettingsStore.open(world);
        assertEquals(0, PlayerSettingsStore.size());
        PlayerSettingsStore.close();
    }
}
