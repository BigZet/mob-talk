package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.server.Permissions.Level;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Уровни прав и таблица корней команд (трек C): операторы и {@code admins} — ADMIN, {@code streamer} — STREAMER, ванильные команды не
 * фильтруются, неизвестный корень мода — ADMIN, {@code commandLevels} настроек поверх таблицы. */
class PermissionsTest {
    private final UUID admin = UUID.randomUUID(), streamer = UUID.randomUUID(), player = UUID.randomUUID();
    private final MobTalkConfig config = new MobTalkConfig();
    private final PermissionsImpl permissions = new PermissionsImpl(() -> config, () -> streamer, () -> Set.of(admin));

    @Test void levelsComeFromOperatorsAdminsAndStreamer() {
        assertEquals(Level.ADMIN, permissions.level(player, true), "an operator is an admin");
        assertEquals(Level.ADMIN, permissions.level(admin, false));
        assertEquals(Level.STREAMER, permissions.level(streamer, false));
        assertEquals(Level.PLAYER, permissions.level(player, false));
        assertEquals(Level.PLAYER, permissions.level(null, false));
    }

    @Test void defaultTableOfRoots() {
        assertEquals(Level.ADMIN, permissions.required("mobtalk", "admin"));
        assertEquals(Level.ADMIN, permissions.required("mobtalk", "budget"));
        assertEquals(Level.STREAMER, permissions.required("mobtalk", "state"));
        assertEquals(Level.STREAMER, permissions.required("mobtalk", null));
        assertEquals(Level.STREAMER, permissions.required("divineblock", "list"));
        assertEquals(Level.STREAMER, permissions.required("divineblock", "curses"));
        assertEquals(Level.ADMIN, permissions.required("divineblock", "unban"));
        assertEquals(Level.ADMIN, permissions.required("divineblock", "something-new"), "an unknown subcommand takes the root's level");
        assertEquals(Level.ADMIN, permissions.required("vp", "come"));
        assertEquals(Level.ADMIN, permissions.required("viewerparrot", null));
        assertEquals(Level.ADMIN, permissions.required("rusflora", null));
        assertNull(permissions.required("tp", null), "vanilla commands are not ours");
        assertNull(permissions.required("whitelist", "add"));
    }

    @Test void playerGetsNoModCommandStreamerSomeAdminAll() {
        assertFalse(permissions.allows(Level.PLAYER, "divineblock", "list"));
        assertFalse(permissions.allows(Level.PLAYER, "mobtalk", "state"));
        assertTrue(permissions.allows(Level.PLAYER, "help", null), "vanilla passes");
        assertTrue(permissions.allows(Level.STREAMER, "divineblock", "list"));
        assertFalse(permissions.allows(Level.STREAMER, "divineblock", "act"));
        assertFalse(permissions.allows(Level.STREAMER, "mobtalk", "admin"));
        assertTrue(permissions.allows(Level.STREAMER, "mobtalk", "state"));
        for (String root : PermissionsImpl.MOD_ROOTS) assertTrue(permissions.allows(Level.ADMIN, root, "anything"), root);
    }

    @Test void configOverridesTheTable() {
        config.commandLevels.put("divineblock list", "PLAYER");
        config.commandLevels.put("mobtalk", "admin");
        config.commandLevels.put("vp", "nonsense");
        assertTrue(permissions.allows(Level.PLAYER, "divineblock", "list"));
        assertEquals(Level.ADMIN, permissions.required("mobtalk", "state"));
        assertEquals(Level.ADMIN, permissions.required("vp", null), "an unreadable level falls back to the table");
    }

    @Test void refusalTexts() {
        assertEquals(PermissionsImpl.REFUSAL, permissions.refusal("divineblock", "list"));
        assertEquals(PermissionsImpl.ADMIN_ONLY, permissions.refusal("mobtalk", "admin"));
    }

    @Test void commandLineSplitsIntoRootAndSub() {
        assertArrayEquals(new String[]{"divineblock", "list"}, CommandFilter.split("/divineblock list"));
        assertArrayEquals(new String[]{"mobtalk", "admin"}, CommandFilter.split("MobTalk Admin streamer Тест1"));
        assertArrayEquals(new String[]{"vp", null}, CommandFilter.split("vp"));
        assertArrayEquals(new String[]{"", null}, CommandFilter.split(""));
    }
}
