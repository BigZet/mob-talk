package io.github.mobtalk.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Local preferences only; the API key is loaded separately. */
public final class MobTalkConfig {
    public int schemaVersion = 2;
    public boolean enabled = true;
    /** Who answers in conversations: "openrouter" or "venice". */
    public String llmProvider = "openrouter";
    public String llmModel = "openai/gpt-6-luna";
    public String veniceModel = "deepseek-v4-1-flash";
    /** OpenRouter reasoning models think before answering; "minimal" skips that (seconds saved per reply). "" sends nothing. */
    public String reasoningEffort = "minimal";
    /** A cheap model for background work nobody waits for (conversation summaries, reflections); "" uses the chat model. */
    public String backgroundModel = "";
    /** Who voices the replies: "openrouter" (ElevenLabs voices by id) or "venice" (one Kokoro voice for every mob). */
    public String ttsProvider = "openrouter";
    public String ttsModel = "elevenlabs/eleven-flash-v2.5";
    public String veniceTtsModel = "tts-kokoro";
    /** The Venice voice every mob speaks with; Kokoro has no Russian voices, so this is an accent at best. */
    public String veniceVoice = "af_sky";
    public String whisperUrl = "http://127.0.0.1:63919";
    public boolean whisperAutoStart = true;
    public String whisperModel = "large-v3";
    public String whisperDevice = "auto";
    public String whisperLanguage = "ru";
    public String microphoneName = "";
    public int whisperBeamSize = 5;
    public int whisperVadSilenceMs = 500;
    public int whisperCpuThreads = 4;
    public String whisperHotwords = "Minecraft, Майнкрафт, крипер, зомби, скелет, пиглин, эндермен, варден, тихоня, нюхач, лавомерка";
    public String commonPrompt = "";
    /** 0 — без мата, 1 — грубовато, 2 — мат моментами, 3 — без тормозов. */
    public int rudeness = 2;
    public Map<String, SpeciesCatalog.Override> speciesOverrides = new LinkedHashMap<>();
    public int maxRecordingSeconds = 15;
    public int maxReplyWords = 45;
    public int historyTurns = 24;
    public int maxMemoryChars = 4000;
    public int requestTimeoutSeconds = 45;
    public double maxDistance = 16;
    /** Bystanders that may answer after the mob spoken to, one after another (0–2): three voices in a row at most. */
    public int witnessReplies = 2;
    /** One word of the selected mob's emotional state («встревожен», «в духе») in the Mob Talk line. */
    public boolean showStateHud = true;
    /** When a screenshot of the player's view goes with the question: off, unusual (odd place, "look", first meeting) or always. */
    public String vision = "unusual";
    /** One word of how the haggle goes («склоняется», «не уверен», «против») in the Mob Talk line while a bargain is on. */
    public boolean showBargainHud = true;
    /** Mobs act on what they think of each other (friends drift together, enemies keep away, a friend visits a mourner). */
    public boolean socialBehavior = true;
    /** At most this many social walks per minute in a world: a village should not look choreographed. */
    public int socialActionsPerMinute = 6;
    /** Creatures speak first on an occasion (a return, an open deal, a friend nearby, a building, the weather), at most this often. */
    public boolean initiative = true;
    public int initiativesPerMinute = 1;
    /** Who decides whether a creature speaks up, who answers next and whether a bystander cuts in: "rules" or "jev" (OpenRouter Decisions API). */
    public String decider = "rules";
    public String defaultVoice = "rSfuQoQ3FY8SVKeraMAp";
    /** The villagers' social layer (characters, gestures, gifts, family, village standing; docs/mca-social-spec.md). */
    public boolean socialLayer = true;
    /** The player's sex for courtship: "male", "female" or "any" (not counted). */
    public String playerGender = "any";
    /** Villagers marry and have children only in couples; while on, vanilla villager breeding is off. */
    public boolean familyLife = true;
    /** The chance per minute (percent) that a village near the player holds a wedding, and that a married couple has a child. */
    public int weddingChance = 5, birthChance = 5;
    /** Game days from a child's birth to adulthood (four stages); vanilla takes one. */
    public int childGrowthDays = 4;

    // ---- Tuning that used to be constants; ranges and settings-screen groups live in Tunables. ----
    /** Bystanders: how far a mob hears an insult or a threat and sees a blow (blocks), how often one witness counts a deed (seconds),
     * how much the relationship drops for each deed seen, and the chance (percent) a bystander chips in without an occasion. */
    public int witnessHearingRange = 5, witnessSightRange = 16, witnessCooldownSeconds = 5;
    public int witnessHitPenalty = 10, witnessKillPenalty = 25, witnessInsultPenalty = 5, witnessThreatPenalty = 10;
    public int smallTalkChance = 50;
    /** The emotional state: half-lives of an ordinary and a strong impression (minutes), and the thresholds of its axes (0–100). */
    public int stateHalfLifeMinutes = 10, stateSlowHalfLifeMinutes = 30;
    public int waryCalm = 25, waryTrust = 25, keenInterest = 75, generousMood = 65, generousTrust = 65;
    /** Memory: half-life of a fact (days), the floor of its freshness (percent), how many facts are kept, the silence that ends a
     * conversation (minutes), and when a mob thinks the player over (replies, importance, seconds between). */
    public int memoryHalfLifeDays = 7, memoryFloorPercent = 30, memoryMaxFacts = 60, conversationSilenceMinutes = 3;
    public int reflectionReplies = 4, reflectionImportance = 8, reflectionCooldownSeconds = 60;
    /** Gifts: hearts for a creature's favourite food, how fast repeats bore (percent), value-to-hearts rate (percent), gesture
     * fatigue and gift memory (minutes per step). */
    public int foodGiftHearts = 8, giftSaturationPercent = 50, giftHeartsPercent = 33, gestureFatigueMinutes = 4, giftForgetMinutes = 20;
    /** Hearts: the relationship from which a villager is a friend, agrees to court, agrees to marry. */
    public int heartsFriend = 40, heartsDating = 70, heartsMarriage = 90;
    /** Bonds between mobs: how far from the player they act (blocks), the chance a friend walks over (percent per check), the
     * walking band and retreat distance (blocks), how fast a bond fades (hours) and how long grief lasts (minutes). */
    public int socialPlayerRange = 32;
    public double socialApproachChance = 12.5;
    public int socialApproachMin = 6, socialApproachMax = 24, socialRetreatRange = 4, bondHalfLifeHours = 6, griefMinutes = 60;
    /** Rumours: forgotten after (game days), kept per village and per villager, passing chance (percent), shown to the model, and the
     * importance that makes the village talk before the day is out. */
    public int rumorForgetDays = 5, rumorsPerVillage = 10, rumorsPerVillager = 6, rumorSpreadPercent = 45, rumorsInPrompt = 3, rumorChronicleImportance = 12;
    /** Speaking first: one creature's pause and the absence that counts as a return (minutes), the ranges (blocks), scene length. */
    public int initiativeMobCooldownMinutes = 5, initiativeReturnMinutes = 20, initiativeSpeakRange = 6, initiativeHearRange = 12, initiativeMetRange = 16;
    public int sceneMaxLines = 4;
    /** The village's view: points at or below which the player is an exile or a suspect, and from which a friend, guard or head. */
    public int villageExilePoints = -150, villageSuspectPoints = -40, villageFriendPoints = 120, villageGuardPoints = 300, villageElderPoints = 600;
    /** Friendly duties: a lease (minutes), how far the player may go (blocks), the guard's distances and the stay drift (blocks). */
    public int friendLeaseMinutes = 10, friendLeaveRange = 48, guardNear = 3, guardFar = 6, guardAlert = 8, guardChase = 16;
    public double stayDrift = 2.5;
    /** Trade: how long a spoken offer stays in the window, and the pause before a new haggle (minutes). */
    public int spokenOfferMinutes = 10, bargainCooldownMinutes = 20;
    /** The place around a mob: analysed at all (enclosure, exits, height), and named by the classifier («похоже на шахту»). */
    public boolean placeAnalysis = true, placeClassifier = true;
    /** How mobs call the player in prompts and memories; "" uses the Minecraft nick ({@link PlayerIdentity}). */
    public String playerName = "";

    /** A menu draft or worker snapshot; editing it does not change the live preferences. */
    public MobTalkConfig copy() {
        MobTalkConfig copy = new MobTalkConfig();
        copy.schemaVersion = schemaVersion;
        copy.enabled = enabled;
        copy.llmProvider = llmProvider;
        copy.llmModel = llmModel;
        copy.veniceModel = veniceModel;
        copy.ttsProvider = ttsProvider; copy.ttsModel = ttsModel; copy.veniceTtsModel = veniceTtsModel; copy.veniceVoice = veniceVoice;
        copy.reasoningEffort = reasoningEffort;
        copy.backgroundModel = backgroundModel;
        copy.whisperUrl = whisperUrl;
        copy.whisperAutoStart = whisperAutoStart;
        copy.whisperModel = whisperModel;
        copy.whisperDevice = whisperDevice;
        copy.whisperLanguage = whisperLanguage;
        copy.microphoneName = microphoneName;
        copy.whisperBeamSize = whisperBeamSize;
        copy.whisperVadSilenceMs = whisperVadSilenceMs;
        copy.whisperCpuThreads = whisperCpuThreads;
        copy.whisperHotwords = whisperHotwords;
        copy.commonPrompt = commonPrompt;
        copy.rudeness = rudeness;
        copy.speciesOverrides = new LinkedHashMap<>();
        if (speciesOverrides != null) speciesOverrides.forEach((type, value) -> {
            if (value == null) return;
            SpeciesCatalog.Override replacement = new SpeciesCatalog.Override();
            replacement.prompt = value.prompt;
            replacement.archetypePrompts = value.archetypePrompts == null ? null : value.archetypePrompts.clone();
            replacement.voiceIds = value.voiceIds == null ? null : value.voiceIds.clone();
            copy.speciesOverrides.put(type, replacement);
        });
        copy.maxRecordingSeconds = maxRecordingSeconds;
        copy.maxReplyWords = maxReplyWords;
        copy.historyTurns = historyTurns;
        copy.maxMemoryChars = maxMemoryChars;
        copy.requestTimeoutSeconds = requestTimeoutSeconds;
        copy.maxDistance = maxDistance;
        copy.witnessReplies = witnessReplies;
        copy.showStateHud = showStateHud; copy.vision = vision;
        copy.showBargainHud = showBargainHud;
        copy.socialBehavior = socialBehavior; copy.socialActionsPerMinute = socialActionsPerMinute;
        copy.initiative = initiative; copy.initiativesPerMinute = initiativesPerMinute; copy.decider = decider;
        copy.defaultVoice = defaultVoice;
        copy.socialLayer = socialLayer; copy.playerGender = playerGender;
        copy.familyLife = familyLife; copy.weddingChance = weddingChance; copy.birthChance = birthChance; copy.childGrowthDays = childGrowthDays;
        Tunables.copy(this, copy);
        copy.placeAnalysis = placeAnalysis; copy.placeClassifier = placeClassifier; copy.playerName = playerName;
        return copy;
    }

    public static MobTalkConfig load(Path file) throws IOException {
        if (!Files.exists(file)) save(file, new MobTalkConfig());
        MobTalkConfig config;
        try {
            String contents = Files.readString(file);
            config = new GsonBuilder().create().fromJson(contents, MobTalkConfig.class);
            if (config != null && !JsonParser.parseString(contents).getAsJsonObject().has("schemaVersion")) {
                if (config.historyTurns == 6) config.historyTurns = 24;
                if (config.maxMemoryChars == 1200) config.maxMemoryChars = 4000;
                if ("small".equals(config.whisperModel)) config.whisperModel = "large-v3";
            }
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
        // Every number (recording, reply, history, memory, Whisper, distances, chances, thresholds) into its range: see Tunables.
        Tunables.normalize(config);
        config.schemaVersion = 2;
        config.whisperHotwords = TextBounds.characters(config.whisperHotwords, 2000);
        config.commonPrompt = config.commonPrompt == null ? "" : config.commonPrompt.strip();
        config.rudeness = Math.max(0, Math.min(3, config.rudeness));
        if (config.speciesOverrides == null) config.speciesOverrides = new LinkedHashMap<>();
        config.playerGender = config.playerGender == null ? "any" : config.playerGender.strip().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.List.of("male", "female", "any").contains(config.playerGender)) config.playerGender = "any";
        config.playerName = PlayerIdentity.clean(config.playerName);
        config.decider = "jev".equalsIgnoreCase(config.decider == null ? "" : config.decider.strip()) ? "jev" : "rules";
        config.vision = Vision.normalize(config.vision);
        MobTalkConfig defaults = new MobTalkConfig();
        config.llmModel = textOrDefault(config.llmModel, defaults.llmModel);
        config.veniceModel = textOrDefault(config.veniceModel, defaults.veniceModel);
        config.llmProvider = "venice".equalsIgnoreCase(textOrDefault(config.llmProvider, defaults.llmProvider)) ? "venice" : "openrouter";
        config.ttsProvider = "venice".equalsIgnoreCase(textOrDefault(config.ttsProvider, defaults.ttsProvider)) ? "venice" : "openrouter";
        config.ttsModel = textOrDefault(config.ttsModel, defaults.ttsModel);
        config.reasoningEffort = config.reasoningEffort == null ? "" : config.reasoningEffort.strip().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.List.of("", "none", "minimal", "low", "medium", "high").contains(config.reasoningEffort)) config.reasoningEffort = defaults.reasoningEffort;
        config.backgroundModel = config.backgroundModel == null ? "" : config.backgroundModel.strip();
        config.veniceTtsModel = textOrDefault(config.veniceTtsModel, defaults.veniceTtsModel);
        config.veniceVoice = textOrDefault(config.veniceVoice, defaults.veniceVoice);
        if (!config.veniceVoice.matches("[A-Za-z0-9_.-]{1,64}")) throw new IOException("Голос Venice: только латиница, цифры, _ . -");
        config.whisperModel = textOrDefault(config.whisperModel, defaults.whisperModel);
        // A catalogue name, a fine-tuned model folder in Whisper's models directory, or its absolute path.
        if (!validWhisperModel(config.whisperModel)) config.whisperModel = defaults.whisperModel;
        config.whisperDevice = textOrDefault(config.whisperDevice, defaults.whisperDevice);
        config.whisperLanguage = textOrDefault(config.whisperLanguage, defaults.whisperLanguage);
        config.microphoneName = config.microphoneName == null ? "" : config.microphoneName;
        config.defaultVoice = textOrDefault(config.defaultVoice, defaults.defaultVoice);
        SpeciesCatalog.validateOverrides(config);
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

    /** Whether decisions go to the Decisions API (OpenRouter key) instead of the rules. */
    public boolean usesJev() { return "jev".equals(decider); }
    public boolean usesVenice() { return "venice".equals(llmProvider); }
    /** The chat model of the selected provider. */
    public String chatModel() { return usesVenice() ? veniceModel : llmModel; }
    public boolean usesVeniceSpeech() { return "venice".equals(ttsProvider); }
    /** Venice's ElevenLabs model takes the same raw ElevenLabs voice ids as OpenRouter: every mob keeps its own voice there. */
    public boolean veniceSpeechKeepsVoices() { return usesVeniceSpeech() && veniceTtsModel != null && veniceTtsModel.startsWith("tts-elevenlabs"); }
    /** The speech model of the selected speech provider. */
    public String speechModel() { return usesVeniceSpeech() ? veniceTtsModel : ttsModel; }
    /** Whether any part of the pipeline (chat or speech) goes through that provider. */
    public boolean needsOpenRouter() { return !usesVenice() || !usesVeniceSpeech(); }
    public boolean needsVenice() { return usesVenice() || usesVeniceSpeech(); }

    static boolean validWhisperModel(String model) {
        if (model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) return true;
        try {
            return model.length() <= 260 && model.chars().noneMatch(Character::isISOControl)
                    && java.nio.file.Path.of(model).isAbsolute();
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
