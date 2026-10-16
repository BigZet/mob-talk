package io.github.mobtalk.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
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
import java.util.function.BooleanSupplier;
import java.util.ArrayList;
import java.util.List;

/** Reuses an external local worker, and destroys only the process started by this instance. */
final class WhisperProcess {
    private Process owned;

    void ensureAvailable(MobTalkConfig config, BooleanSupplier cancelled) throws IOException, InterruptedException {
        Process started = null;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            JsonObject health = health(http, config.whisperUrl);
            checkCancelled(cancelled);
            if (isReady(health)) return;
            if (health == null) {
                if (!config.whisperAutoStart)
                    throw new IOException("Whisper не запущен: выполните scripts/setup-whisper.ps1 или включите whisperAutoStart");
                started = launch(config, cancelled);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                checkCancelled(cancelled);
                health = health(http, config.whisperUrl);
                if (isReady(health)) return;
                if (health != null && health.has("status") && "error".equals(health.get("status").getAsString()))
                    throw new IOException("Whisper не загрузил модель: повторите scripts/setup-whisper.ps1");
                synchronized (this) {
                    if (owned != null && !owned.isAlive()) {
                        owned = null;
                        throw new IOException("Whisper завершился при запуске: повторите scripts/setup-whisper.ps1");
                    }
                }
                Thread.sleep(250);
            }
            throw new IOException("Whisper не готов за 90 секунд: проверьте setup-whisper.ps1 и whisperModel");
        } finally {
            // Cancellation can race with ProcessBuilder.start(); clean up this request's process as well.
            if (started != null && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) stop(started);
        }
    }

    private synchronized Process launch(MobTalkConfig config, BooleanSupplier cancelled) throws IOException, InterruptedException {
        checkCancelled(cancelled);
        Path directory = findDirectory();
        Path python = directory.resolve("venv/Scripts/pythonw.exe");
        if (!Files.isRegularFile(python)) python = directory.resolve("venv/Scripts/python.exe");
        if (owned != null && owned.isAlive()) return null;
        Files.createDirectories(directory);
        Path script = directory.resolve("whisper_server.py");
        try (var resource = WhisperProcess.class.getResourceAsStream("/assets/mobtalk/helper/whisper_server.py")) {
            if (resource == null) throw new IOException("В JAR отсутствует Whisper helper: переустановите Mob Talk");
            Files.copy(resource, script, StandardCopyOption.REPLACE_EXISTING);
        }
        checkCancelled(cancelled);
        int port = URI.create(config.whisperUrl).getPort();
        owned = new ProcessBuilder(python.toString(), script.toString(),
                "--port", Integer.toString(port), "--model", config.whisperModel,
                "--device", config.whisperDevice, "--language", config.whisperLanguage,
                "--model-dir", directory.resolve("models").toString())
                .directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return owned;
    }

    private static Path findDirectory() throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path local = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(localAppData);
        List<Path> candidates = new ArrayList<>();
        candidates.add(local.resolve("MobTalk"));
        Path packages = local.resolve("Packages");
        if (Files.isDirectory(packages)) {
            try (var entries = Files.list(packages)) {
                entries.filter(path -> path.getFileName().toString().startsWith("OpenAI.Codex_"))
                        .sorted().map(path -> path.resolve("LocalCache/Local/MobTalk")).forEach(candidates::add);
            }
        }
        for (Path directory : candidates) {
            if (Files.isRegularFile(directory.resolve("venv/Scripts/python.exe"))) return directory;
        }
        throw new IOException("Whisper ещё не установлен: запустите scripts/setup-whisper.ps1 из архива Mob Talk");
    }

    private static JsonObject health(HttpClient client, String url) throws InterruptedException {
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/health")).timeout(Duration.ofSeconds(2)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 8_192) return null;
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean isReady(JsonObject health) {
        return health != null && health.has("ready") && health.get("ready").getAsBoolean();
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new InterruptedException("Cancelled");
    }

    synchronized void stopOwned() {
        Process process = owned;
        owned = null;
        if (process == null) return;
        stop(process);
    }

    private static void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
