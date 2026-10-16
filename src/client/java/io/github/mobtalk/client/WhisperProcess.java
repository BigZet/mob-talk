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
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;

/** Reuses an external local worker, and destroys only the process started by this instance. */
final class WhisperProcess {
    private Process owned;
    private volatile boolean initiatedLoad;

    void ensureAvailable(MobTalkConfig config, BooleanSupplier cancelled) throws IOException, InterruptedException {
        ensureAvailable(config, cancelled, ignored -> { });
    }

    void ensureAvailable(MobTalkConfig config, BooleanSupplier cancelled, Consumer<String> progress) throws IOException, InterruptedException {
        Process started = null;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            JsonObject health = health(http, config.whisperUrl);
            checkCancelled(cancelled);
            if (isReady(health) && matches(health, config)) { initiatedLoad = false; return; }
            if (health == null) {
                if (!config.whisperAutoStart)
                    throw new IOException("Whisper не запущен: выполните scripts/setup-whisper.ps1 или включите whisperAutoStart");
                started = launch(config, cancelled);
                initiatedLoad = started != null;
            } else if (!matches(health, config) || health.has("status")
                    && List.of("cancelled", "error").contains(health.get("status").getAsString())) {
                if (!health.has("protocol") || health.get("protocol").getAsInt() < 2)
                    throw new IOException("Работает старый Whisper helper. Перезапустите игру или обновите scripts/setup-whisper.ps1.");
                JsonObject settings = new JsonObject();
                settings.addProperty("model", config.whisperModel);
                settings.addProperty("device", config.whisperDevice);
                settings.addProperty("language", config.whisperLanguage);
                settings.addProperty("beamSize", config.whisperBeamSize);
                settings.addProperty("vadSilenceMs", config.whisperVadSilenceMs);
                settings.addProperty("cpuThreads", config.whisperCpuThreads);
                settings.addProperty("hotwords", config.whisperHotwords);
                var configure = HttpRequest.newBuilder(URI.create(config.whisperUrl + "/configure"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(settings.toString())).build();
                int code = http.send(configure, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (code == 409) throw new IOException("Whisper занят загрузкой или распознаванием. Дождитесь завершения или отмените текущую загрузку.");
                if (code != 202) throw new IOException("Whisper не принял настройки модели.");
                initiatedLoad = true;
            }
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
            while (System.nanoTime() < deadline) {
                checkCancelled(cancelled);
                health = health(http, config.whisperUrl);
                if (isReady(health) && matches(health, config)) { initiatedLoad = false; return; }
                if (health != null) progress.accept(progressText(health));
                if (health != null && health.has("status") && "error".equals(health.get("status").getAsString()))
                    throw new IOException(health.has("error") && "gpu_out_of_memory".equals(health.get("error").getAsString())
                            ? "Не хватает видеопамяти. Выберите large-v3-turbo, medium или CPU."
                            : "Whisper не загрузил модель: проверьте CUDA и scripts/setup-whisper.ps1");
                synchronized (this) {
                    if (owned != null && !owned.isAlive()) {
                        owned = null;
                        throw new IOException("Whisper завершился при запуске: повторите scripts/setup-whisper.ps1");
                    }
                }
                Thread.sleep(250);
            }
            throw new IOException("Загрузка Whisper заняла более 30 минут. Проверьте доступ к huggingface.co.");
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
                "--model-dir", directory.resolve("models").toString(),
                "--cpu-threads", Integer.toString(config.whisperCpuThreads),
                "--beam-size", Integer.toString(config.whisperBeamSize),
                "--vad-silence-ms", Integer.toString(config.whisperVadSilenceMs),
                "--hotwords", config.whisperHotwords)
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

    private static boolean matches(JsonObject health, MobTalkConfig config) {
        if (health == null || !health.has("model") || !config.whisperModel.equals(health.get("model").getAsString())) return false;
        if (!health.has("protocol")) return "small".equals(config.whisperModel) && config.whisperBeamSize == 1;
        return config.whisperDevice.equals(health.get("configuredDevice").getAsString())
                && config.whisperLanguage.equals(health.get("language").getAsString())
                && config.whisperBeamSize == health.get("beamSize").getAsInt()
                && config.whisperVadSilenceMs == health.get("vadSilenceMs").getAsInt()
                && config.whisperCpuThreads == health.get("cpuThreads").getAsInt()
                && config.whisperHotwords.equals(health.get("hotwords").getAsString());
    }

    private static String progressText(JsonObject health) {
        String model = health.has("model") ? health.get("model").getAsString() : "Whisper";
        String stage = health.has("progress") ? health.getAsJsonObject("progress").get("stage").getAsString() : "loading";
        String label = switch (stage) {
            case "downloading" -> "Загрузка файлов";
            case "loading_cuda" -> "Подготовка CUDA";
            case "loading_cpu" -> "Подготовка CPU";
            default -> "Подготовка модели";
        };
        String counts = "";
        if (health.has("progress")) {
            var value = health.getAsJsonObject("progress");
            int total = value.get("total").getAsInt();
            if (total > 0) counts = " (" + value.get("completed").getAsInt() + "/" + total + " файлов)";
        }
        return label + " " + model + counts;
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

    /** Worker-only cancellation of a load initiated by this object; unrelated helpers are left alone. */
    void cancelLoading(MobTalkConfig config) {
        if (!initiatedLoad) return;
        initiatedLoad = false;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            JsonObject value = health(http, config.whisperUrl);
            if (value != null && value.has("status") && "loading".equals(value.get("status").getAsString()))
                http.send(HttpRequest.newBuilder(URI.create(config.whisperUrl + "/cancel-load"))
                        .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException unavailable) { }
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
