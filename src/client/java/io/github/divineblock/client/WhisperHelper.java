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
    /** The model is loaded once at game start and kept until the game closes: the first prayer never waits for it. */
    private static volatile String state = "не загружен";

    private WhisperHelper() { }

    /** Mob Talk runs the same helper on the same port and keeps it for the whole game: with it installed, only wait for it. */
    static boolean sharedWithMobTalk() { return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mobtalk"); }

    static String state() { return state; }

    /** At game start: loads the model in the background (or waits for Mob Talk's helper) so it is ready before any prayer. */
    static void warmUp(DivineConfig config) {
        Thread thread = new Thread(() -> {
            try {
                state = "загружается";
                ensureReady(config, line -> { });
                state = "готов";
                io.github.divineblock.DivineBlockMod.LOG.info("Божественный блок: Whisper готов на {}", config.whisperUrl);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception failure) {
                state = "ошибка: " + failure.getMessage();
                io.github.divineblock.DivineBlockMod.LOG.warn("Божественный блок: Whisper не поднялся: {}", failure.getMessage());
            }
        }, "divineblock-whisper-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    static void ensureReady(DivineConfig config, Consumer<String> progress) throws IOException, InterruptedException {
        URI base = LocalWhisper.local(config.whisperUrl);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JsonObject health = health(http, base);
        if (ready(health)) return;
        // With Mob Talk in the game its helper owns the port: never start a second copy of the model, wait for it instead.
        // An older Mob Talk starts its helper only at its first need, so after half a minute of silence start our own.
        if (health == null && sharedWithMobTalk()) {
            long waitMobTalk = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (health == null && System.nanoTime() < waitMobTalk) { progress.accept("Ждём Whisper из Mob Talk…"); Thread.sleep(1000); health = health(http, base); }
            if (ready(health)) return;
        }
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
        Path directory = home();
        Path[] found = python();
        Path python = found == null ? null : found[0], home = found == null ? null : found[1];
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

    private static Path localAppData() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(localAppData);
    }
    private static Path home() { return localAppData().resolve("DivineBlock"); }

    /** Whether a Whisper environment (this mod's or Mob Talk's) is installed: the voice path "local" can work here. */
    static boolean installed() {
        try { return python() != null; } catch (IOException unreadable) { return false; }
    }

    /** The Python of the helper and the folder it lives in, or null when Whisper is not installed. */
    private static Path[] python() throws IOException {
        Path local = localAppData(), directory = home();
        // This mod's own Python environment (scripts/setup-whisper.ps1); an existing Mob Talk environment is only a fallback,
        // so the 3 GB model is not downloaded twice on a machine that has both mods.
        // Mob Talk's setup may have run inside the Codex app, whose writes to LOCALAPPDATA land in its package folder;
        // a game started outside that app sees the real LOCALAPPDATA, so look there too (as Mob Talk's WhisperProcess does).
        java.util.List<Path> candidates = new java.util.ArrayList<>(java.util.List.of(directory, local.resolve("MobTalk")));
        Path packages = local.resolve("Packages");
        if (Files.isDirectory(packages)) {
            try (var entries = Files.list(packages)) {
                entries.filter(path -> path.getFileName().toString().startsWith("OpenAI.Codex_")).sorted()
                        .map(path -> path.resolve("LocalCache/Local/MobTalk")).forEach(candidates::add);
            }
        }
        for (Path candidate : candidates)
            for (String exe : new String[] { "venv/Scripts/pythonw.exe", "venv/Scripts/python.exe" })
                if (Files.isRegularFile(candidate.resolve(exe))) return new Path[] { candidate.resolve(exe), candidate };
        return null;
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
