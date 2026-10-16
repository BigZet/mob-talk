package io.github.mobtalk.core;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public static MobTalkConfig load(Path file) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        if (!Files.exists(file)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, gson.toJson(new MobTalkConfig()));
        }
        MobTalkConfig config = gson.fromJson(Files.readString(file), MobTalkConfig.class);
        if (config == null) throw new IOException("Mob Talk config is empty");
        config.maxRecordingSeconds = Math.max(1, Math.min(15, config.maxRecordingSeconds));
        config.maxReplyWords = Math.max(5, Math.min(45, config.maxReplyWords));
        config.historyTurns = Math.max(1, Math.min(6, config.historyTurns));
        config.maxMemoryChars = Math.max(200, Math.min(1200, config.maxMemoryChars));
        config.requestTimeoutSeconds = Math.max(5, Math.min(90, config.requestTimeoutSeconds));
        config.maxDistance = Double.isFinite(config.maxDistance) ? Math.max(2, Math.min(64, config.maxDistance)) : 16;
        if (config.llmModel == null || config.llmModel.isBlank()) config.llmModel = "google/gemini-2.5-flash-lite";
        if (config.whisperUrl == null || !config.whisperUrl.matches("http://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}"))
            throw new IOException("Whisper must use a local HTTP address");
        return config;
    }
}
