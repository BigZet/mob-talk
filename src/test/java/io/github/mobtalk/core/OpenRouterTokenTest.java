package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterTokenTest {
    private static final String KEY = "sk-or-v1-0123456789abcdef0123456789abcdef";
    @TempDir Path localAppData;
    @TempDir Path config;

    @Test void findsProtectedKeyInMobTalkDirectory() throws Exception {
        assertNull(OpenRouterToken.encryptedKey(localAppData));
        Path encrypted = localAppData.resolve("MobTalk/openrouter-key.dpapi");
        Files.createDirectories(encrypted.getParent());
        Files.writeString(encrypted, "encrypted-test-fixture");
        assertEquals(encrypted, OpenRouterToken.encryptedKey(localAppData));
    }

    @Test void menuKeyFallsBackToLocalFileWithoutWindows() throws Exception {
        var plain = new OpenRouterToken.Environment(null, null, null);
        assertEquals(OpenRouterToken.Source.NONE, OpenRouterToken.status(config, plain).source());
        OpenRouterToken.store(config, "  " + KEY + "\n", plain);
        assertEquals(KEY, Files.readString(config.resolve(OpenRouterToken.PLAIN_FILE)));
        OpenRouterToken.Status status = OpenRouterToken.status(config, plain);
        assertEquals(OpenRouterToken.Source.FILE, status.source());
        assertEquals("sk-or-v1…cdef", status.hint());
        assertFalse(status.hint().contains("0123456789"));
        OpenRouterToken.remove(config, plain);
        assertEquals(OpenRouterToken.Source.NONE, OpenRouterToken.status(config, plain).source());
    }

    // The Venice key lives next to the OpenRouter one; neither may shadow or remove the other.
    @Test void keepsVeniceAndOpenRouterKeysApart() throws Exception {
        var plain = new OpenRouterToken.Environment(null, null, null);
        OpenRouterToken.store(config, OpenRouterToken.Provider.VENICE, "VENICE_INFERENCE_KEY_unit-test-0000000000", plain);
        assertTrue(Files.exists(config.resolve("mobtalk-venice-token.txt")));
        assertEquals(OpenRouterToken.Source.NONE, OpenRouterToken.status(config, OpenRouterToken.Provider.OPENROUTER, plain).source());
        assertEquals(OpenRouterToken.Source.FILE, OpenRouterToken.status(config, OpenRouterToken.Provider.VENICE, plain).source());
        OpenRouterToken.store(config, KEY, plain);
        OpenRouterToken.remove(config, OpenRouterToken.Provider.VENICE, plain);
        assertEquals(OpenRouterToken.Source.NONE, OpenRouterToken.status(config, OpenRouterToken.Provider.VENICE, plain).source());
        assertEquals(OpenRouterToken.Source.FILE, OpenRouterToken.status(config, plain).source());
    }

    @Test void rejectsKeysWithSpaces() {
        var plain = new OpenRouterToken.Environment(null, null, null);
        assertThrows(IOException.class, () -> OpenRouterToken.store(config, "sk-or v1", plain));
        assertThrows(IOException.class, () -> OpenRouterToken.store(config, "   ", plain));
        assertFalse(Files.exists(config.resolve(OpenRouterToken.PLAIN_FILE)));
    }

    @Test void environmentVariableWins() throws Exception {
        Files.writeString(config.resolve(OpenRouterToken.PLAIN_FILE), KEY);
        var environment = new OpenRouterToken.Environment("sk-or-v1-environment-0000000000", null, null);
        assertEquals(OpenRouterToken.Source.ENVIRONMENT, OpenRouterToken.status(config, environment).source());
    }

    @Test @EnabledOnOs(OS.WINDOWS) void windowsEncryptsMenuKeyAndReplacesPlainFile() throws Exception {
        var windows = new OpenRouterToken.Environment(null, localAppData, Path.of(System.getenv("SystemRoot")));
        Files.writeString(config.resolve(OpenRouterToken.PLAIN_FILE), "sk-or-v1-stale-key-000000000000");
        OpenRouterToken.store(config, KEY, windows);
        assertFalse(Files.exists(config.resolve(OpenRouterToken.PLAIN_FILE)));
        Path encrypted = OpenRouterToken.encryptedKey(localAppData);
        assertNotNull(encrypted);
        assertFalse(Files.readString(encrypted).contains(KEY));
        assertEquals(OpenRouterToken.Source.ENCRYPTED, OpenRouterToken.status(config, windows).source());
        OpenRouterToken.remove(config, windows);
        assertNull(OpenRouterToken.encryptedKey(localAppData));
    }
}
