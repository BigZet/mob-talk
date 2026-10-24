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
    public int schemaVersion = 4;
    public boolean enabled = true;
    /** Who answers in conversations: "openrouter" or "venice". */
    public String llmProvider = "openrouter";
    public String llmModel = "openai/gpt-6-luna";
    public String veniceModel = "deepseek-v4-1-flash";
    /** OpenRouter reasoning models think before answering; "none" asks for no thinking at all (measured: "minimal" still
     * thinks on most replies and costs 1.5–2.5 s before the first token). "" sends nothing and leaves it to the provider. */
    public String reasoningEffort = "none";
    /** A cheap model for background work nobody waits for (conversation summaries, reflections); "" uses the chat model. */
    public String backgroundModel = "";
    /** Who voices the replies: "openrouter" (ElevenLabs voices by id) or "venice" (one Kokoro voice for every mob). */
    public String ttsProvider = "openrouter";
    public String ttsModel = "elevenlabs/eleven-flash-v2.5";
    public String veniceTtsModel = "tts-kokoro";
    /** The Venice voice every mob speaks with; Kokoro has no Russian voices, so this is an accent at best. */
    public String veniceVoice = "af_sky";
    /** Как распознаётся речь этого игрока (раздел 3 плана сервера, личная настройка): {@code auto} — локальный Whisper, если он
     * установлен, иначе сервер, иначе текст; {@code local}, {@code server}, {@code text} — явно (см. {@link SttRoute}). */
    public String sttPath = SttRoute.AUTO;
    /** Распознавание на сервере через OpenRouter: модель и запасная, на которую уходит одна повторная попытка (разведка F). */
    public String sttModel = "mistralai/voxtral-mini-transcribe-3-0";
    public String sttFallbackModel = "openai/whisper-large-v3";
    /** Сервер принимает аудио игроков и распознаёт его через OpenRouter (путь {@code server}). */
    public boolean sttServer = true;
    /** Сервер с видеокартой распознаёт аудио игроков своим локальным Whisper ({@code server-local}); OpenRouter — запасной. */
    public boolean sttServerLocal = false;
    /** Своё ожидание OpenRouter STT: у voxtral бывают выбросы по 25 с, лучше сразу спросить запасную модель. */
    public int sttTimeoutSeconds = 8;
    public String whisperUrl = "http://127.0.0.1:63919";
    public boolean whisperAutoStart = true;
    public String whisperModel = "large-v3-turbo";
    public String whisperDevice = "auto";
    public String whisperLanguage = "ru";
    public String microphoneName = "";
    public int whisperBeamSize = 3;
    public int whisperVadSilenceMs = 500;
    public int whisperCpuThreads = 4;
    public String whisperHotwords = "крипер, зомби, скелет, пиглин, эндермен, варден, тихоня, нюхач, лавомерка";
    /** The shipped hotwords before version 4: turbo looped on «Майнкрафт» instead of the player's speech. */
    static final String OLD_HOTWORDS = "Minecraft, Майнкрафт, крипер, зомби, скелет, пиглин, эндермен, варден, тихоня, нюхач, лавомерка";
    public String commonPrompt = "";
    /** 0 — без мата, 1 — грубовато, 2 — мат моментами, 3 — без тормозов. */
    public int rudeness = 2;
    public Map<String, SpeciesCatalog.Override> speciesOverrides = new LinkedHashMap<>();
    public int maxRecordingSeconds = 15;
    public int maxReplyWords = 45;
    public int historyTurns = 24;
    public int maxMemoryChars = 4000;
    public int requestTimeoutSeconds = 45;
    /** How long a streamed reply may stay silent before its first token; a stalled provider is cancelled and asked once more. 0 waits the whole timeout. */
    public int firstTokenSeconds = 12;
    public double maxDistance = 16;
    /** Голос моба звучит из моба: до {@code voiceFullRange} блоков в полную силу, к {@code voiceSilentRange} плавно стихает (VoiceFalloff). */
    public double voiceFullRange = 4, voiceSilentRange = 48;
    /** Сколько секунд моб, потерявший игрока из виду или из дальности (дерево, угол, шаг назад), ещё держит внимание и разговор (Grace). */
    public int voiceGraceSeconds = 6;
    /** Bystanders that may answer after the mob spoken to, one after another (0–2): three voices in a row at most. */
    public int witnessReplies = 2;
    /** One word of the selected mob's emotional state («встревожен», «в духе») in the Mob Talk line. */
    public boolean showStateHud = true;
    /** When a screenshot of the player's view goes with the question: off, unusual (odd place, "look", first meeting) or always. */
    public String vision = "unusual";
    /** A line over every known creature: name, word of its state, relationship. */
    public boolean showNameTags = true;
    /** Трек M: жители — люди в духе MCA Reborn (кожа, лицо, одежда, волосы, гены); выключено — ванильная модель. */
    public boolean humanVillagers = true;
    /** Creatures the player talked to, gave to or helped never despawn. */
    public boolean keepAcquainted = true;
    /** One word of how the haggle goes («склоняется», «не уверен», «против») in the Mob Talk line while a bargain is on. */
    public boolean showBargainHud = true;
    /** Mobs act on what they think of each other (friends drift together, enemies keep away, a friend visits a mourner). */
    public boolean socialBehavior = true;
    /** At most this many social walks per minute in a world: a village should not look choreographed. */
    public int socialActionsPerMinute = 6;
    /** Creatures speak first on an occasion (a return, an open deal, a friend nearby, a building, the weather), at most this often. */
    public boolean initiative = true;
    public int initiativesPerMinute = 1;
    /** A reaction the game owes (a gift, a family event) goes ahead of bystanders and small talk, and gifts given in a row to one
     * creature get one reaction instead of a queue of them. */
    public boolean giftsFirst = true;
    /** Villagers of one village talk among themselves within the player's earshot, without knowing the player (village small talk). */
    public boolean villageChatter = true;
    /** Villagers the player knows write to the chat in their own name: village news, or a call to come over for work. */
    public boolean villageNotices = true;
    /** A village is the structure its residents live in (vanilla or a village mod such as CTOV), not only a point and a radius:
     * two neighbouring villages stay apart and a large one stays whole. Off: the old rule, the nearest known village within 160 blocks. */
    public boolean villageSites = true;
    /** Village defence (stage 1): the village raises an alarm at monsters and at a player who strikes its people, rings the bell,
     * hides its people and sends the golem; the golem is sturdier, heals between fights and the smith mends it. */
    public boolean villageDefense = true;
    /** No monster spawns inside a village that has a golem (its structure's bounds plus {@link #defenseZoneMargin}). */
    public boolean villageNoSpawn = true;
    /** The alarm also turns the village on a player: a blow at a villager or the golem, a hated or exiled player in the village. */
    public boolean alarmAgainstPlayers = true;
    /** The village's players hear of an alarm in the chat. */
    public boolean alarmChat = true;
    /** Villages build watchtowers at their edge over a game day, and an archer comes to each tower's post. */
    public boolean villageTowers = true;
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
    /** Village defence: the margin around a village's bounds that still counts as the village (blocks), how close to a resident a
     * monster raises the alarm (blocks), how many monsters at once make it an attack, how long a quiet village keeps its alarm
     * (seconds) and a player who left it the alarm against them (minutes), how far from the bounds a player counts as gone (blocks)
     * and who hears of the alarm in the chat (blocks from the village). */
    public int defenseZoneMargin = 16, alarmMonsterRange = 16, alarmAttackThreats = 5, alarmClearSeconds = 60, alarmPlayerClearMinutes = 3;
    public int alarmPlayerRange = 48, alarmNoticeRange = 160;
    /** The village golem: health in percent of vanilla, health it regains per minute out of a fight, health a smith's ingot mends. */
    public int golemHealthPercent = 150, golemRegenPerMinute = 12, golemRepairHealth = 25;
    /** Watchtowers: one per this many residents, at most this many per village, built over this many real minutes; the archers see and
     * shoot this far (blocks), loose an arrow every this many tenths of a second, and a post empty this long (minutes) gets a newcomer. */
    public int towerResidents = 8, towerMax = 4, towerBuildMinutes = 20, archerRange = 32, archerShotTenths = 20, archerReplaceMinutes = 20;
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
    /** Ревность (трек J): сила поводов — разговор, сон рядом, услуга, подарок (база, плюс ценность подарка), доля силы по слуху и
     * в браке (проценты), какая доля ревности снимается с сердечек (процент), полураспад (минуты) и сколько гасит разговор с ревнующим. */
    public int jealousyTalk = 3, jealousySleep = 20, jealousyFavor = 8, jealousyGift = 12, jealousyRumorPercent = 50, jealousyMarriedPercent = 150;
    public int jealousyHeartsPercent = 30, jealousyHalfLifeMinutes = 60, jealousyAttention = 4;
    /** Сон вместе: влюблённый житель ложится рядом, когда игрок спит; откуда он приходит (блоки) и сколько сердечек даёт ночь. */
    public boolean coSleep = true;
    public int coSleepRange = 12, coSleepHearts = 3;
    /** Сколько секунд ночь не пропускается, пока игрок лежит рядом с парой (0 — ванильные 5 с). */
    public int coSleepNightDelay = 60;
    /** Свадьба у колокола (трек L): помолвка кольцом, свидетели, служба при деревне. Запись клятвы игрока (секунды, только пока
     * держат кнопку), ожидание клятвы до переспроса и сбор у колокола (секунды), сколько гостей зовут и сколько из них говорят
     * пожелания, откуда гостей зовут (блоки), какая доля свадеб жителей при игроке становится сценой (процент) и сколько дней
     * пара носит венцы. */
    public boolean weddingCeremony = true;
    public int weddingVowSeconds = 60, weddingVowWaitSeconds = 60, weddingGatherSeconds = 60, weddingGuests = 16, weddingWishes = 12, weddingRange = 48;
    public int weddingScenePercent = 30, weddingAttireDays = 1;
    /** Ребёнок в семье (трек N): беременность как у людей (выключено — малыш через игровой день, как раньше), живот на фигурке,
     * строка под именем и мягкие затемнения особой ночи и родов. Длина срока (игровые дни), шанс зачатия за обычную ночь вместе
     * и за особую (от и до по уюту дома, проценты), сердечки за особую ночь и сколько секунд тишины в кровати её заканчивают. */
    public boolean pregnancy = true, pregnancyBelly = true, pregnancyStatus = true, familyFade = true;
    public int pregnancyDays = 5, conceptionChance = 15, specialNightChanceMin = 40, specialNightChanceMax = 80, specialNightHearts = 5, specialNightQuietSeconds = 30;
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
    /** Village small talk: one talk per village in this many seconds, heard within this many blocks of the player. */
    public int villageChatterSeconds = 120, villageChatterRange = 16;
    /** Chat announcements: one per village in this many minutes, the share of calls to work (percent), only within this many blocks. */
    public int noticeMinutes = 4, noticeWorkPercent = 40, noticeRange = 64;
    /** The village's view: points at or below which the player is an exile or a suspect, and from which a friend, guard or head. */
    public int villageExilePoints = -150, villageSuspectPoints = -40, villageFriendPoints = 120, villageGuardPoints = 300, villageElderPoints = 600;
    /** Friendly duties: a lease (minutes), how far the player may go (blocks), the guard's distances and the stay drift (blocks). */
    public int friendLeaseMinutes = 10, friendLeaveRange = 48, guardNear = 3, guardFar = 6, guardAlert = 8, guardChase = 16;
    public double stayDrift = 2.5;
    /** Trade: how long a spoken offer stays in the window, and the pause before a new haggle (minutes). */
    public int spokenOfferMinutes = 10, bargainCooldownMinutes = 20;
    /** Server (track C): spending limits, 0 = none (Dmitriy pays and watches the costs himself): requests per minute and dollars a day,
     * per player and for the whole server; the streamer gets {@code budgetStreamerMultiplier} times a player's limits; at most
     * {@code maxConcurrentDialogues} answers are written at once on the server (and two per player, always). */
    public int budgetPlayerPerMinute = 0, budgetServerPerMinute = 0, maxConcurrentDialogues = 6;
    public double budgetPlayerDailyUsd = 0, budgetServerDailyUsd = 0, budgetStreamerMultiplier = 2;
    /** Server: who may run a mod command, by root or «root sub» → PLAYER, STREAMER or ADMIN; empty keeps the defaults of
     * {@code PermissionsImpl}. Vanilla commands are never filtered. */
    public Map<String, String> commandLevels = new LinkedHashMap<>();
    /** The place around a mob: analysed at all (enclosure, exits, height), and named by the classifier («похоже на шахту»). */
    public boolean placeAnalysis = true, placeClassifier = true;
    /** How mobs address the player, three comma-separated lists picked by the relationship: hostile or mocking, ordinary, close.
     * Empty lists fall back to the ordinary one, then to the Minecraft nick ({@link PlayerIdentity}). */
    public String playerNamesHostile = "", playerNamesNeutral = "", playerNamesClose = "";

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
        copy.sttPath = sttPath; copy.sttModel = sttModel; copy.sttFallbackModel = sttFallbackModel; copy.sttServer = sttServer; copy.sttServerLocal = sttServerLocal;
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
        copy.firstTokenSeconds = firstTokenSeconds;
        copy.maxDistance = maxDistance;
        copy.witnessReplies = witnessReplies;
        copy.showStateHud = showStateHud; copy.vision = vision; copy.showNameTags = showNameTags; copy.humanVillagers = humanVillagers; copy.keepAcquainted = keepAcquainted;
        copy.showBargainHud = showBargainHud;
        copy.socialBehavior = socialBehavior; copy.socialActionsPerMinute = socialActionsPerMinute;
        copy.initiative = initiative; copy.initiativesPerMinute = initiativesPerMinute; copy.giftsFirst = giftsFirst; copy.decider = decider;
        copy.villageChatter = villageChatter; copy.villageNotices = villageNotices; copy.villageSites = villageSites;
        copy.villageDefense = villageDefense; copy.villageNoSpawn = villageNoSpawn; copy.alarmAgainstPlayers = alarmAgainstPlayers; copy.alarmChat = alarmChat; copy.villageTowers = villageTowers;
        copy.defaultVoice = defaultVoice;
        copy.socialLayer = socialLayer; copy.playerGender = playerGender;
        copy.familyLife = familyLife; copy.weddingChance = weddingChance; copy.birthChance = birthChance; copy.childGrowthDays = childGrowthDays;
        copy.coSleep = coSleep; copy.weddingCeremony = weddingCeremony;
        copy.pregnancy = pregnancy; copy.pregnancyBelly = pregnancyBelly; copy.pregnancyStatus = pregnancyStatus; copy.familyFade = familyFade;
        Tunables.copy(this, copy);
        copy.commandLevels = commandLevels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(commandLevels);
        copy.placeAnalysis = placeAnalysis; copy.placeClassifier = placeClassifier; copy.playerNamesHostile = playerNamesHostile; copy.playerNamesNeutral = playerNamesNeutral; copy.playerNamesClose = playerNamesClose;
        return copy;
    }

    public static MobTalkConfig load(Path file) throws IOException {
        if (!Files.exists(file)) save(file, new MobTalkConfig());
        MobTalkConfig config;
        try {
            String contents = Files.readString(file);
            config = new GsonBuilder().create().fromJson(contents, MobTalkConfig.class);
            // The version the file was written with: a missing field means the oldest, whatever the field's default says.
            var stored = config == null ? null : JsonParser.parseString(contents).getAsJsonObject().get("schemaVersion");
            int version = stored == null || !stored.isJsonPrimitive() ? 0 : stored.getAsInt();
            if (config != null && version == 0) {
                if (config.historyTurns == 6) config.historyTurns = 24;
                if (config.maxMemoryChars == 1200) config.maxMemoryChars = 4000;
                if ("small".equals(config.whisperModel)) config.whisperModel = "large-v3";
            }
            // Version 3 (speed): the old shipped values move to the faster ones; anything the player chose differently stays.
            if (config != null && version < 3) {
                if ("minimal".equals(config.reasoningEffort)) config.reasoningEffort = "none";
                if ("large-v3".equals(config.whisperModel)) config.whisperModel = "large-v3-turbo";
                if (config.whisperBeamSize == 5) config.whisperBeamSize = 3;
            }
            if (config != null && version < 4 && OLD_HOTWORDS.equals(config.whisperHotwords))
                config.whisperHotwords = new MobTalkConfig().whisperHotwords;
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
        config.schemaVersion = 4;
        config.whisperHotwords = TextBounds.characters(config.whisperHotwords, 2000);
        config.commonPrompt = config.commonPrompt == null ? "" : config.commonPrompt.strip();
        config.rudeness = Math.max(0, Math.min(3, config.rudeness));
        if (config.speciesOverrides == null) config.speciesOverrides = new LinkedHashMap<>();
        if (config.commandLevels == null) config.commandLevels = new LinkedHashMap<>();
        config.commandLevels.entrySet().removeIf(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null);
        config.playerGender = config.playerGender == null ? "any" : config.playerGender.strip().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.List.of("male", "female", "any").contains(config.playerGender)) config.playerGender = "any";
        config.playerNamesHostile = PlayerIdentity.clean(config.playerNamesHostile);
        config.playerNamesNeutral = PlayerIdentity.clean(config.playerNamesNeutral);
        config.playerNamesClose = PlayerIdentity.clean(config.playerNamesClose);
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
        config.sttPath = SttRoute.normalize(config.sttPath);
        config.sttModel = textOrDefault(config.sttModel, defaults.sttModel);
        if (!SttRoute.validModel(config.sttModel)) config.sttModel = defaults.sttModel;
        config.sttFallbackModel = config.sttFallbackModel == null ? "" : config.sttFallbackModel.strip();
        if (!config.sttFallbackModel.isEmpty() && !SttRoute.validModel(config.sttFallbackModel)) config.sttFallbackModel = defaults.sttFallbackModel;
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
