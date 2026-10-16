package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterTokenTest {
    @TempDir Path localAppData;

    // Omitting MSIX redirection would hide the same user's existing protected key from external Java.
    @Test void findsMsixProtectedKeyWhenNormalLocationIsAbsent() throws Exception {
        assertNull(OpenRouterToken.encryptedKey(localAppData));
        Path redirected = localAppData.resolve("Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/ViewerParrots/openrouter-key.dpapi");
        Files.createDirectories(redirected.getParent());
        Files.writeString(redirected, "encrypted-test-fixture");
        assertEquals(redirected, OpenRouterToken.encryptedKey(localAppData));
        Path standard = localAppData.resolve("ViewerParrots/openrouter-key.dpapi");
        Files.createDirectories(standard.getParent());
        Files.writeString(standard, "encrypted-test-fixture");
        assertEquals(standard, OpenRouterToken.encryptedKey(localAppData));
    }
}
