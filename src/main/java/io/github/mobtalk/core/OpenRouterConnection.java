package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.Path;

/** Warms the shared OpenRouter connection before and between conversations. Call from a worker: the first call loads the key. */
public final class OpenRouterConnection {
    // Memory only, like the engine's copy; cleared whenever the menu stores or removes a key.
    private static volatile String key;

    private OpenRouterConnection() { }

    public static void keepWarm(Path configDirectory) {
        String current = key;
        if (current == null) {
            try { current = OpenRouterToken.load(configDirectory); } catch (IOException missing) { return; }
            key = current;
        }
        BoundedHttp.keepWarm(current);
    }

    static void forgetKey() { key = null; }
}
