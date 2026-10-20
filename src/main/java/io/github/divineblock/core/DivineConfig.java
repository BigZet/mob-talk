package io.github.divineblock.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Settings from config/divineblock.json. Never holds keys: those live in {@link ApiKeys}. Every value is clamped on load. */
public final class DivineConfig {
    public enum Personality {
        MERCIFUL("милостивый", 1, 0), CAPRICIOUS("капризный", 0, 1), OLD_TESTAMENT("ветхозаветный", -1, 1), TRICKSTER("трикстер", 0, 0);
        public final String label;
        /** A standing favor bonus and a standing force bonus: a named reason in every verdict. */
        public final int favor, force;
        Personality(String label, int favor, int force) { this.label = label; this.favor = favor; this.force = force; }
    }
    public enum Provider { OPENROUTER, VENICE }

    public Personality personality = Personality.CAPRICIOUS;
    /** The strongest act the god may perform, 0..4. */
    public int maxTier = 3;
    public boolean allowWither = false;
    /** Destructive acts (fire, meteors) spare blocks entirely; off lets meteors leave craters outside the protected radius. */
    public boolean protectBlocks = true;
    /** No block changes within this many blocks of the altar and of the player's bed. */
    public int protectRadius = 24;
    /** The weight of the god's named whims (storm, moon); 0 makes him fully predictable. */
    public int whimWeight = 1;
    public int cooldownSeconds = 20;
    /** Seconds between the omen and a serious punishment: time to repent or run. */
    public int warnSeconds = 12;
    public boolean useJev = true;
    public Provider provider = Provider.OPENROUTER;
    public String chatModel = "openai/gpt-6-luna";
    public String veniceModel = "deepseek-v4-1-flash";
    public int speechTimeoutSeconds = 12;
    /** The god speaks aloud (OpenRouter text to speech); off leaves subtitles only. */
    public boolean voice = true;
    public String voiceId = GodVoice.VOICES[0][0];
    public String ttsModel = "elevenlabs/eleven-flash-v2.5";
    /** Prayers by voice: the local Whisper helper (shared with Mob Talk) and the microphone; "" is the system default. */
    public String whisperUrl = "http://127.0.0.1:63919";
    public boolean whisperAutoStart = true;
    public String microphoneName = "";
    /** Ids of acts the player switched off. */
    public List<String> disabled = new ArrayList<>();
    public boolean debug = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static DivineConfig load(Path file) {
        DivineConfig config = null;
        try { if (Files.isRegularFile(file)) config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DivineConfig.class); }
        catch (IOException | RuntimeException broken) { config = null; }
        return (config == null ? new DivineConfig() : config).sanitized();
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(sanitized()), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public DivineConfig sanitized() {
        if (personality == null) personality = Personality.CAPRICIOUS;
        if (provider == null) provider = Provider.OPENROUTER;
        maxTier = clamp(maxTier, 0, 4);
        protectRadius = clamp(protectRadius, 0, 256);
        whimWeight = clamp(whimWeight, 0, 3);
        cooldownSeconds = clamp(cooldownSeconds, 0, 600);
        warnSeconds = clamp(warnSeconds, 3, 60);
        speechTimeoutSeconds = clamp(speechTimeoutSeconds, 3, 60);
        if (chatModel == null || chatModel.isBlank()) chatModel = new DivineConfig().chatModel;
        if (veniceModel == null || veniceModel.isBlank()) veniceModel = new DivineConfig().veniceModel;
        if (disabled == null) disabled = new ArrayList<>();
        if (!GodVoice.validVoiceId(voiceId)) voiceId = GodVoice.VOICES[0][0];
        if (ttsModel == null || ttsModel.isBlank()) ttsModel = new DivineConfig().ttsModel;
        try { LocalWhisper.local(whisperUrl); } catch (IllegalArgumentException invalid) { whisperUrl = new DivineConfig().whisperUrl; }
        if (microphoneName == null) microphoneName = "";
        return this;
    }

    public DivineConfig copy() { return GSON.fromJson(GSON.toJson(this), DivineConfig.class).sanitized(); }

    /** Whether the settings let the god perform this act at all. */
    public boolean allows(Miracle miracle) {
        if (miracle == null || disabled.contains(miracle.id())) return false;
        if (miracle == Miracle.WITHER && !allowWither) return false;
        return miracle.minTier <= maxTier;
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
