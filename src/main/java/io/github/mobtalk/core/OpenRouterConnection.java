package io.github.mobtalk.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.nio.file.Path;

/** Warms the shared connections before and between conversations: OpenRouter for speech (and chat), Venice when it answers.
 * Call from a worker: the first call loads the OpenRouter key. */
public final class OpenRouterConnection {
    // Memory only, like the engine's copy; cleared whenever the menu stores or removes a key.
    private static volatile String key;

    private OpenRouterConnection() { }

    public static void keepWarm(Path configDirectory, boolean venice) {
        // A small public listing: it warms the connection without sending the Venice key anywhere new.
        if (venice) BoundedHttp.keepWarm(HttpRequest.newBuilder(URI.create("https://api.venice.ai/api/v1/models?type=upscale"))
                .timeout(Duration.ofSeconds(20)).GET().build());
        String current = key;
        if (current == null) {
            try { current = OpenRouterToken.load(configDirectory); } catch (IOException missing) { return; }
            key = current;
        }
        BoundedHttp.keepWarm(HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/key")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + current).GET().build());
    }

    static void forgetKey() { key = null; }
}
