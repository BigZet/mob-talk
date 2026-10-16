package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterTokenTest {
    @TempDir Path localAppData;

    @Test void findsProtectedKeyInMobTalkDirectory() throws Exception {
        assertNull(OpenRouterToken.encryptedKey(localAppData));
        Path encrypted = localAppData.resolve("MobTalk/openrouter-key.dpapi");
        Files.createDirectories(encrypted.getParent());
        Files.writeString(encrypted, "encrypted-test-fixture");
        assertEquals(encrypted, OpenRouterToken.encryptedKey(localAppData));
    }
}
