package io.github.divineblock.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.LocalWhisper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The local Whisper helper, adapted from Mob Talk's WhisperProcess. A helper already running on the port (Mob Talk's own,
 * whatever model it loaded) is simply used; otherwise one is started from Mob Talk's installed Python environment with this
 * mod's copy of the script. Only a process started here is stopped here. Never on the game thread. */
final class WhisperHelper {
    private static Process owned;

    private WhisperHelper() { }

    static void ensureReady(DivineConfig config, Consumer<String> progress) throws IOException, InterruptedException {
        URI base = LocalWhisper.local(config.whisperUrl);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JsonObject health = health(http, base);
        if (ready(health)) return;
        if (health == null) {
            if (!config.whisperAutoStart) throw new IOException("Whisper не запущен: включите автозапуск или запустите Mob Talk.");
            launch(base);
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (System.nanoTime() < deadline) {
            health = health(http, base);
            if (ready(health)) return;
            if (health != null && health.has("status") && "error".equals(health.get("status").getAsString()))
                throw new IOException("Whisper не загрузил модель: проверьте установку Whisper в Mob Talk.");
            synchronized (WhisperHelper.class) {
                if (owned != null && !owned.isAlive()) { owned = null; throw new IOException("Whisper завершился при запуске."); }
            }
            progress.accept("Whisper загружает модель…");
            Thread.sleep(500);
        }
        throw new IOException("Whisper так и не загрузился.");
    }

    private static synchronized void launch(URI base) throws IOException {
        if (owned != null && owned.isAlive()) return;
        String localAppData = System.getenv("LOCALAPPDATA");
        Path local = localAppData == null ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(localAppData);
        Path directory = local.resolve("DivineBlock");
        // This mod's own Python environment (scripts/setup-whisper.ps1); an existing Mob Talk environment is only a fallback,
        // so the 3 GB model is not downloaded twice on a machine that has both mods.
        Path home = null, python = null;
        for (Path candidate : new Path[] { directory, local.resolve("MobTalk") }) {
            for (String exe : new String[] { "venv/Scripts/pythonw.exe", "venv/Scripts/python.exe" })
                if (python == null && Files.isRegularFile(candidate.resolve(exe))) { python = candidate.resolve(exe); home = candidate; }
        }
        if (python == null) throw new IOException("Whisper не установлен: выполните scripts/setup-whisper.ps1 из папки Божественного блока.");
        Path mobTalk = home;
        Files.createDirectories(directory);
        Path script = directory.resolve("whisper_server.py");
        try (var resource = WhisperHelper.class.getResourceAsStream("/assets/divineblock/helper/whisper_server.py")) {
            if (resource == null) throw new IOException("В JAR нет помощника Whisper.");
            Files.copy(resource, script, StandardCopyOption.REPLACE_EXISTING);
        }
        owned = new ProcessBuilder(python.toString(), script.toString(), "--port", Integer.toString(base.getPort()),
                "--model", "large-v3", "--device", "auto", "--language", "ru", "--model-dir", mobTalk.resolve("models").toString())
                .directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    static synchronized void stopOwned() {
        Process process = owned;
        owned = null;
        if (process == null) return;
        process.destroy();
        try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }

    private static JsonObject health(HttpClient client, URI base) throws InterruptedException {
        try {
            var response = client.send(HttpRequest.newBuilder(base.resolve("/health")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 8_192) return null;
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (IOException | RuntimeException unavailable) { return null; }
    }
    private static boolean ready(JsonObject health) { return health != null && health.has("ready") && health.get("ready").getAsBoolean(); }
}
