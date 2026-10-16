package io.github.mobtalk.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Local preferences only; the API key is loaded separately. */
public final class MobTalkConfig {
    public boolean enabled = true;
    public String llmModel = "google/gemini-2.5-flash-lite";
    public String whisperUrl = "http://127.0.0.1:63919";
    public boolean whisperAutoStart = true;
    public String whisperModel = "small";
    public String whisperDevice = "auto";
    public String whisperLanguage = "ru";
    public String microphoneName = "";
    public int maxRecordingSeconds = 15;
    public int maxReplyWords = 45;
    public int historyTurns = 6;
    public int maxMemoryChars = 1200;
    public int requestTimeoutSeconds = 45;
    public double maxDistance = 16;
    public String defaultVoice = "rSfuQoQ3FY8SVKeraMAp";

    /** A menu draft or worker snapshot; editing it does not change the live preferences. */
    public MobTalkConfig copy() {
        MobTalkConfig copy = new MobTalkConfig();
        copy.enabled = enabled;
        copy.llmModel = llmModel;
        copy.whisperUrl = whisperUrl;
        copy.whisperAutoStart = whisperAutoStart;
        copy.whisperModel = whisperModel;
        copy.whisperDevice = whisperDevice;
        copy.whisperLanguage = whisperLanguage;
        copy.microphoneName = microphoneName;
        copy.maxRecordingSeconds = maxRecordingSeconds;
        copy.maxReplyWords = maxReplyWords;
        copy.historyTurns = historyTurns;
        copy.maxMemoryChars = maxMemoryChars;
        copy.requestTimeoutSeconds = requestTimeoutSeconds;
        copy.maxDistance = maxDistance;
        copy.defaultVoice = defaultVoice;
        return copy;
    }

    public static MobTalkConfig load(Path file) throws IOException {
        if (!Files.exists(file)) save(file, new MobTalkConfig());
        MobTalkConfig config;
        try {
            config = new GsonBuilder().create().fromJson(Files.readString(file), MobTalkConfig.class);
        } catch (JsonParseException invalid) {
            throw new IOException("Mob Talk config contains invalid JSON");
        }
        if (config == null) throw new IOException("Mob Talk config is empty");
        normalize(config);
        return config;
    }

    /** Blocking disk I/O; call from a worker. Invalid settings leave the previous file intact. */
    public static void save(Path file, MobTalkConfig config) throws IOException {
        if (config == null) throw new IOException("Mob Talk config is empty");
        MobTalkConfig snapshot = config.copy();
        normalize(snapshot);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(snapshot);
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, json);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void normalize(MobTalkConfig config) throws IOException {
        config.maxRecordingSeconds = Math.max(1, Math.min(15, config.maxRecordingSeconds));
        config.maxReplyWords = Math.max(5, Math.min(45, config.maxReplyWords));
        config.historyTurns = Math.max(1, Math.min(6, config.historyTurns));
        config.maxMemoryChars = Math.max(200, Math.min(1200, config.maxMemoryChars));
        config.requestTimeoutSeconds = Math.max(5, Math.min(90, config.requestTimeoutSeconds));
        config.maxDistance = Double.isFinite(config.maxDistance) ? Math.max(2, Math.min(64, config.maxDistance)) : 16;
        MobTalkConfig defaults = new MobTalkConfig();
        config.llmModel = textOrDefault(config.llmModel, defaults.llmModel);
        config.whisperModel = textOrDefault(config.whisperModel, defaults.whisperModel);
        config.whisperDevice = textOrDefault(config.whisperDevice, defaults.whisperDevice);
        config.whisperLanguage = textOrDefault(config.whisperLanguage, defaults.whisperLanguage);
        config.microphoneName = config.microphoneName == null ? "" : config.microphoneName;
        config.defaultVoice = textOrDefault(config.defaultVoice, defaults.defaultVoice);
        URI whisper;
        try {
            whisper = URI.create(config.whisperUrl);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("Whisper must use a local HTTP address");
        }
        if (!"http".equals(whisper.getScheme())
                || !("127.0.0.1".equals(whisper.getHost()) || "localhost".equals(whisper.getHost()))
                || whisper.getPort() < 1 || whisper.getPort() > 65535 || whisper.getRawUserInfo() != null
                || whisper.getQuery() != null || whisper.getFragment() != null || !whisper.getPath().isEmpty())
            throw new IOException("Whisper must use a local HTTP address");
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
