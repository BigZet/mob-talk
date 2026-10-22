package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.PlayerSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** {@code /mobtalk admin} без Minecraft (трек C, раздел 16.2): каждая подкоманда меняет {@link ServerConfig} и зовёт слушателей,
 * {@code set} отвергает личные поля и зажимает числа; плюс хранилище личных настроек по UUID. */
class AdminCommandsTest {
    @TempDir Path dir;
    private final List<MobTalkConfig> heard = new ArrayList<>();
    @BeforeEach void fresh() throws Exception { ServerConfig.resetForTests(); ServerConfig.init(dir); ServerConfig.listen(heard::add); heard.clear(); }
    @AfterEach void cleanup() { ServerConfig.resetForTests(); }

    @Test void streamerIsOneAndPersists() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals("Стример не назначен", AdminActions.showStreamer(id -> "?"));
        assertEquals("Стример теперь Тест1", AdminActions.streamer(a, "Тест1"));
        assertEquals("Тест1 уже стример", AdminActions.streamer(a, "Тест1"));
        assertEquals("Стример теперь Тест2", AdminActions.streamer(b, "Тест2"));
        assertEquals(b, ServerConfig.streamer(), "one streamer per server");
        assertTrue(Files.readString(dir.resolve(ServerConfig.FILE)).contains(b.toString()));
        assertFalse(heard.isEmpty(), "listeners heard the change");
        assertEquals("Стример снят", AdminActions.clearStreamer());
        assertNull(ServerConfig.streamer());
        assertEquals("Игрок не найден", AdminActions.streamer(null, "кто"));
    }

    @Test void adminsAddAndRemove() {
        UUID a = UUID.randomUUID();
        assertTrue(AdminActions.listAdmins(id -> "?").contains("только операторы"));
        assertEquals("Дима теперь администратор", AdminActions.addAdmin(a, "Дима"));
        assertEquals("Дима уже администратор", AdminActions.addAdmin(a, "Дима"));
        assertTrue(ServerConfig.admins().contains(a));
        assertTrue(AdminActions.listAdmins(id -> "Дима").contains("Дима"));
        assertEquals("Дима больше не администратор", AdminActions.removeAdmin(a, "Дима"));
        assertTrue(AdminActions.removeAdmin(a, "Дима").contains("не в списке"));
    }

    @Test void setChangesServerFieldsClampsAndRefusesPersonalOnes() {
        assertEquals("maxDistance = 64.0", AdminActions.set("maxDistance", "500"), "clamped into the table's range");
        assertEquals(64.0, ServerConfig.get().maxDistance);
        assertEquals("budgetPlayerPerMinute = 3", AdminActions.set("budgetplayerperminute", "3"));
        assertEquals("llmModel = test/model", AdminActions.set("llmModel", "test/model"));
        assertEquals("villageChatter = false", AdminActions.set("villageChatter", "off"));
        assertTrue(AdminActions.set("playerNamesNeutral", "Дима").contains("личная настройка"));
        assertTrue(AdminActions.set("whisperModel", "small").contains("личная настройка"));
        assertTrue(AdminActions.set("nope", "1").startsWith("Нет такой настройки"));
        assertTrue(AdminActions.set("maxDistance", "далеко").contains("нужно число"));
        assertTrue(AdminActions.set("commandLevels", "x").contains("составная"));
        assertTrue(AdminActions.get("budgetPlayerPerMinute").endsWith("= 3"));
        assertFalse(AdminActions.settable().contains("whisperModel"));
        assertTrue(AdminActions.settable().contains("maxConcurrentDialogues"));
        assertEquals(4, heard.size(), "each accepted change told the listeners once");
    }

    @Test void featureAndModel() {
        assertEquals("villageNotices = false", AdminActions.feature("villageNotices", false));
        assertFalse(ServerConfig.get().villageNotices);
        assertTrue(AdminActions.feature("maxDistance", true).startsWith("Нет такой функции"));
        assertTrue(AdminActions.feature("showStateHud", true).contains("личная"));
        assertEquals("Модель диалога: openai/gpt-6-luna", AdminActions.model(" openai/gpt-6-luna "));
        assertEquals("openai/gpt-6-luna", ServerConfig.get().llmModel);
        assertTrue(AdminActions.model("два слова").startsWith("Нужен идентификатор"));
    }

    @Test void playerSettingsAreKeptByPlayerAndReplacedOnRejoin(@TempDir Path world) {
        UUID dima = UUID.randomUUID(), test2 = UUID.randomUUID();
        PlayerSettingsStore.open(world);
        try {
            PlayerSettingsStore.put(dima, PlayerSettings.fromJson("{\"playerNamesNeutral\":\"Дима\",\"llmModel\":\"hack/model\",\"unknown\":1}"));
            PlayerSettingsStore.put(test2, PlayerSettings.fromJson("{\"playerNamesNeutral\":\"Тестер\"}"));
            assertEquals("\"Дима\"", PlayerSettingsStore.of(dima).value("playerNamesNeutral").toString());
            assertNull(PlayerSettingsStore.of(dima).value("llmModel"), "server fields from a client are dropped");
            assertNull(PlayerSettingsStore.of(dima).value("unknown"), "unknown fields are dropped");
            PlayerSettingsStore.put(dima, PlayerSettings.fromJson("{\"playerNamesNeutral\":\"Димон\"}"));
            assertEquals("\"Димон\"", PlayerSettingsStore.of(dima).value("playerNamesNeutral").toString());
            assertTrue(PlayerSettingsStore.find(test2).isPresent());
            assertTrue(PlayerSettingsStore.find(UUID.randomUUID()).isEmpty());
            PlayerSettingsStore.flush();
        } finally { PlayerSettingsStore.close(); }
        PlayerSettingsStore.open(world);
        try { assertEquals("\"Димон\"", PlayerSettingsStore.of(dima).value("playerNamesNeutral").toString(), "kept on disk"); }
        finally { PlayerSettingsStore.close(); }
    }
}
