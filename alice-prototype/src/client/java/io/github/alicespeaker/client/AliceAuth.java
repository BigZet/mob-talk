package io.github.alicespeaker.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's Yandex session lives outside the game folder (~/.alice-speaker), or in YA_COOKIE / YA_AUTH.
 * sync.json is the site's own first WebSocket message (System.SynchronizeState), which carries the login.
 * None of it is ever written to configs or logs.
 */
record AliceAuth(String cookie, String authToken, String sync, String oauthToken) {
    static final Path DIR = Path.of(System.getProperty("user.home"), ".alice-speaker");

    static AliceAuth load() {
        return new AliceAuth(read("YA_COOKIE", "cookies.txt").replaceAll("[\\r\\n]+", " "), read("YA_AUTH", "auth_token.txt"), read("YA_SYNC", "sync.json"), "");
    }

    boolean hasLogin() { return !cookie.isBlank() || !sync.isBlank(); }

    private static String read(String env, String file) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) return value.trim();
        // Notepad may save a byte-order mark; it is not part of the value.
        try { return Files.readString(DIR.resolve(file), StandardCharsets.UTF_8).replace("\uFEFF", "").trim(); }
        catch (IOException missing) { return ""; }
    }
}
