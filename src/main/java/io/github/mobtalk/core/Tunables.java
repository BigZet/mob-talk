package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/** Every numeric preference of {@link MobTalkConfig} in one table: its tab and group in the settings screen, its range and step.
 * The same table clamps a loaded file, copies a draft and builds the sliders, so a new number needs one line here, a field and
 * two lang keys ({@code mobtalk.tune.<field>} with the value and unit, and {@code .tooltip}). */
public final class Tunables {
    private Tunables() { }

    /** The settings tabs that hold sliders. */
    public enum Section { GENERAL, VOICE, MODEL, TALK, SOCIAL, ACTIONS }

    public record Tunable(String field, Section section, String group, double min, double max, double step,
                          ToDoubleFunction<MobTalkConfig> getter, ObjDoubleConsumer<MobTalkConfig> setter) {
        public double get(MobTalkConfig config) { return getter.applyAsDouble(config); }
        /** Clamped into the range and rounded to the step; NaN or infinity falls back to the default. */
        public void set(MobTalkConfig config, double value) { setter.accept(config, normalize(value)); }
        public double normalize(double value) {
            if (!Double.isFinite(value)) return defaultValue();
            double clamped = Math.max(min, Math.min(max, value));
            double stepped = min + Math.round((clamped - min) / step) * step;
            return Math.max(min, Math.min(max, stepped));
        }
        public double defaultValue() { return getter.applyAsDouble(DEFAULTS); }
        /** {@code mobtalk.tune.<field>}: the label with a %s for the value; {@code .tooltip} explains it. */
        public String langKey() { return "mobtalk.tune." + field; }
    }

    private static final MobTalkConfig DEFAULTS = new MobTalkConfig();
    private static final List<Tunable> TABLE = new ArrayList<>();

    private static void integer(String field, Section section, String group, int min, int max, int step,
                                ToIntFunction<MobTalkConfig> get, ObjIntConsumer<MobTalkConfig> set) {
        TABLE.add(new Tunable(field, section, group, min, max, step, get::applyAsInt, (config, value) -> set.accept(config, (int) Math.round(value))));
    }
    private static void decimal(String field, Section section, String group, double min, double max, double step,
                                ToDoubleFunction<MobTalkConfig> get, ObjDoubleConsumer<MobTalkConfig> set) {
        TABLE.add(new Tunable(field, section, group, min, max, step, get, set));
    }

    static {
        Section v = Section.VOICE, m = Section.MODEL, t = Section.TALK, s = Section.SOCIAL, a = Section.ACTIONS;
        // The numbers the config always had, with the same ranges.
        decimal("maxDistance", t, "reply", 2, 64, 0.5, c -> c.maxDistance, (c, x) -> c.maxDistance = x);
        integer("maxReplyWords", t, "reply", 5, 120, 1, c -> c.maxReplyWords, (c, x) -> c.maxReplyWords = x);
        integer("maxRecordingSeconds", v, "recording", 1, 15, 1, c -> c.maxRecordingSeconds, (c, x) -> c.maxRecordingSeconds = x);
        // Голос из моба: затухание по расстоянию и отсрочка потери из виду.
        decimal("voiceFullRange", v, "voiceDistance", 1, 16, 0.5, c -> c.voiceFullRange, (c, x) -> c.voiceFullRange = x);
        decimal("voiceSilentRange", v, "voiceDistance", 8, 96, 1, c -> c.voiceSilentRange, (c, x) -> c.voiceSilentRange = x);
        integer("voiceGraceSeconds", v, "voiceDistance", 0, 20, 1, c -> c.voiceGraceSeconds, (c, x) -> c.voiceGraceSeconds = x);
        integer("whisperBeamSize", v, "whisper", 1, 10, 1, c -> c.whisperBeamSize, (c, x) -> c.whisperBeamSize = x);
        integer("whisperVadSilenceMs", v, "whisper", 150, 1500, 1, c -> c.whisperVadSilenceMs, (c, x) -> c.whisperVadSilenceMs = x);
        integer("whisperCpuThreads", v, "whisper", 1, 16, 1, c -> c.whisperCpuThreads, (c, x) -> c.whisperCpuThreads = x);
        integer("sttTimeoutSeconds", v, "sttServer", 3, 30, 1, c -> c.sttTimeoutSeconds, (c, x) -> c.sttTimeoutSeconds = x);
        integer("requestTimeoutSeconds", m, "requests", 5, 90, 1, c -> c.requestTimeoutSeconds, (c, x) -> c.requestTimeoutSeconds = x);
        integer("firstTokenSeconds", m, "requests", 0, 60, 1, c -> c.firstTokenSeconds, (c, x) -> c.firstTokenSeconds = x);

        // Bystanders: who hears and sees what the player does, and what it costs.
        integer("witnessReplies", t, "witness", 0, 2, 1, c -> c.witnessReplies, (c, x) -> c.witnessReplies = x);
        integer("witnessHearingRange", t, "witness", 1, 32, 1, c -> c.witnessHearingRange, (c, x) -> c.witnessHearingRange = x);
        integer("witnessSightRange", t, "witness", 2, 64, 1, c -> c.witnessSightRange, (c, x) -> c.witnessSightRange = x);
        integer("witnessCooldownSeconds", t, "witness", 0, 120, 1, c -> c.witnessCooldownSeconds, (c, x) -> c.witnessCooldownSeconds = x);
        integer("witnessHitPenalty", t, "witness", 0, 50, 1, c -> c.witnessHitPenalty, (c, x) -> c.witnessHitPenalty = x);
        integer("witnessKillPenalty", t, "witness", 0, 100, 1, c -> c.witnessKillPenalty, (c, x) -> c.witnessKillPenalty = x);
        integer("witnessInsultPenalty", t, "witness", 0, 50, 1, c -> c.witnessInsultPenalty, (c, x) -> c.witnessInsultPenalty = x);
        integer("witnessThreatPenalty", t, "witness", 0, 50, 1, c -> c.witnessThreatPenalty, (c, x) -> c.witnessThreatPenalty = x);
        integer("smallTalkChance", t, "witness", 0, 100, 1, c -> c.smallTalkChance, (c, x) -> c.smallTalkChance = x);

        // The emotional state (MobState).
        integer("stateHalfLifeMinutes", t, "state", 1, 240, 1, c -> c.stateHalfLifeMinutes, (c, x) -> c.stateHalfLifeMinutes = x);
        integer("stateSlowHalfLifeMinutes", t, "state", 1, 720, 1, c -> c.stateSlowHalfLifeMinutes, (c, x) -> c.stateSlowHalfLifeMinutes = x);
        integer("waryCalm", t, "state", 0, 100, 1, c -> c.waryCalm, (c, x) -> c.waryCalm = x);
        integer("waryTrust", t, "state", 0, 100, 1, c -> c.waryTrust, (c, x) -> c.waryTrust = x);
        integer("keenInterest", t, "state", 0, 100, 1, c -> c.keenInterest, (c, x) -> c.keenInterest = x);
        integer("generousMood", t, "state", 0, 100, 1, c -> c.generousMood, (c, x) -> c.generousMood = x);
        integer("generousTrust", t, "state", 0, 100, 1, c -> c.generousTrust, (c, x) -> c.generousTrust = x);

        // Memory: history, facts, summaries, reflections.
        integer("historyTurns", t, "memory", 1, 64, 1, c -> c.historyTurns, (c, x) -> c.historyTurns = x);
        integer("maxMemoryChars", t, "memory", 200, 4000, 1, c -> c.maxMemoryChars, (c, x) -> c.maxMemoryChars = x);
        integer("memoryHalfLifeDays", t, "memory", 1, 90, 1, c -> c.memoryHalfLifeDays, (c, x) -> c.memoryHalfLifeDays = x);
        integer("memoryFloorPercent", t, "memory", 0, 100, 1, c -> c.memoryFloorPercent, (c, x) -> c.memoryFloorPercent = x);
        integer("memoryMaxFacts", t, "memory", 10, 300, 1, c -> c.memoryMaxFacts, (c, x) -> c.memoryMaxFacts = x);
        integer("conversationSilenceMinutes", t, "memory", 1, 30, 1, c -> c.conversationSilenceMinutes, (c, x) -> c.conversationSilenceMinutes = x);
        integer("reflectionReplies", t, "memory", 1, 20, 1, c -> c.reflectionReplies, (c, x) -> c.reflectionReplies = x);
        integer("reflectionImportance", t, "memory", 0, 50, 1, c -> c.reflectionImportance, (c, x) -> c.reflectionImportance = x);
        integer("reflectionCooldownSeconds", t, "memory", 0, 3600, 10, c -> c.reflectionCooldownSeconds, (c, x) -> c.reflectionCooldownSeconds = x);

        // Villagers' families.
        integer("weddingChance", s, "family", 0, 100, 1, c -> c.weddingChance, (c, x) -> c.weddingChance = x);
        integer("birthChance", s, "family", 0, 100, 1, c -> c.birthChance, (c, x) -> c.birthChance = x);
        integer("childGrowthDays", s, "family", 1, 16, 1, c -> c.childGrowthDays, (c, x) -> c.childGrowthDays = x);
        // Gifts and gestures.
        integer("foodGiftHearts", s, "gifts", 0, 20, 1, c -> c.foodGiftHearts, (c, x) -> c.foodGiftHearts = x);
        integer("giftSaturationPercent", s, "gifts", 0, 200, 1, c -> c.giftSaturationPercent, (c, x) -> c.giftSaturationPercent = x);
        integer("giftHeartsPercent", s, "gifts", 1, 100, 1, c -> c.giftHeartsPercent, (c, x) -> c.giftHeartsPercent = x);
        integer("gestureFatigueMinutes", s, "gifts", 1, 60, 1, c -> c.gestureFatigueMinutes, (c, x) -> c.gestureFatigueMinutes = x);
        integer("giftForgetMinutes", s, "gifts", 1, 240, 1, c -> c.giftForgetMinutes, (c, x) -> c.giftForgetMinutes = x);
        // Hearts: steps of the relationship with a villager.
        integer("heartsFriend", s, "hearts", 1, 100, 1, c -> c.heartsFriend, (c, x) -> c.heartsFriend = x);
        integer("heartsDating", s, "hearts", 1, 100, 1, c -> c.heartsDating, (c, x) -> c.heartsDating = x);
        integer("heartsMarriage", s, "hearts", 1, 100, 1, c -> c.heartsMarriage, (c, x) -> c.heartsMarriage = x);
        // Ревность и сон вместе (трек J).
        integer("jealousyTalk", s, "jealousy", 0, 30, 1, c -> c.jealousyTalk, (c, x) -> c.jealousyTalk = x);
        integer("jealousySleep", s, "jealousy", 0, 60, 1, c -> c.jealousySleep, (c, x) -> c.jealousySleep = x);
        integer("jealousyFavor", s, "jealousy", 0, 40, 1, c -> c.jealousyFavor, (c, x) -> c.jealousyFavor = x);
        integer("jealousyGift", s, "jealousy", 0, 60, 1, c -> c.jealousyGift, (c, x) -> c.jealousyGift = x);
        integer("jealousyRumorPercent", s, "jealousy", 0, 100, 1, c -> c.jealousyRumorPercent, (c, x) -> c.jealousyRumorPercent = x);
        integer("jealousyMarriedPercent", s, "jealousy", 100, 300, 5, c -> c.jealousyMarriedPercent, (c, x) -> c.jealousyMarriedPercent = x);
        integer("jealousyHeartsPercent", s, "jealousy", 0, 100, 1, c -> c.jealousyHeartsPercent, (c, x) -> c.jealousyHeartsPercent = x);
        integer("jealousyHalfLifeMinutes", s, "jealousy", 5, 600, 5, c -> c.jealousyHalfLifeMinutes, (c, x) -> c.jealousyHalfLifeMinutes = x);
        integer("jealousyAttention", s, "jealousy", 0, 30, 1, c -> c.jealousyAttention, (c, x) -> c.jealousyAttention = x);
        integer("coSleepRange", s, "jealousy", 2, 32, 1, c -> c.coSleepRange, (c, x) -> c.coSleepRange = x);
        integer("coSleepHearts", s, "jealousy", 0, 20, 1, c -> c.coSleepHearts, (c, x) -> c.coSleepHearts = x);
        integer("coSleepNightDelay", s, "jealousy", 0, 300, 5, c -> c.coSleepNightDelay, (c, x) -> c.coSleepNightDelay = x);
        // Соперники за одно сердце (П3).
        integer("rivalWindowHours", s, "rivals", 1, 168, 1, c -> c.rivalWindowHours, (c, x) -> c.rivalWindowHours = x);
        integer("rivalMaxSuitors", s, "rivals", 2, 5, 1, c -> c.rivalMaxSuitors, (c, x) -> c.rivalMaxSuitors = x);
        integer("rivalStealMargin", s, "rivals", 0, 50, 1, c -> c.rivalStealMargin, (c, x) -> c.rivalStealMargin = x);
        integer("rivalTrialWin", s, "rivals", 0, 30, 1, c -> c.rivalTrialWin, (c, x) -> c.rivalTrialWin = x);
        integer("rivalTrialTry", s, "rivals", 0, 10, 1, c -> c.rivalTrialTry, (c, x) -> c.rivalTrialTry = x);
        integer("rivalStrong", s, "rivals", 40, 100, 1, c -> c.rivalStrong, (c, x) -> c.rivalStrong = x);
        integer("rivalGatherMinutes", s, "rivals", 1, 15, 1, c -> c.rivalGatherMinutes, (c, x) -> c.rivalGatherMinutes = x);
        integer("rivalLoserPenalty", s, "rivals", 0, 30, 1, c -> c.rivalLoserPenalty, (c, x) -> c.rivalLoserPenalty = x);
        integer("rivalLoserPauseHours", s, "rivals", 0, 24, 1, c -> c.rivalLoserPauseHours, (c, x) -> c.rivalLoserPauseHours = x);
        // Свадьба у колокола (трек L).
        integer("weddingVowSeconds", s, "wedding", 15, 120, 5, c -> c.weddingVowSeconds, (c, x) -> c.weddingVowSeconds = x);
        integer("weddingVowWaitSeconds", s, "wedding", 10, 180, 5, c -> c.weddingVowWaitSeconds, (c, x) -> c.weddingVowWaitSeconds = x);
        integer("weddingGatherSeconds", s, "wedding", 20, 180, 5, c -> c.weddingGatherSeconds, (c, x) -> c.weddingGatherSeconds = x);
        integer("weddingGuests", s, "wedding", 0, 32, 1, c -> c.weddingGuests, (c, x) -> c.weddingGuests = x);
        integer("weddingWishes", s, "wedding", 0, 24, 1, c -> c.weddingWishes, (c, x) -> c.weddingWishes = x);
        integer("weddingRange", s, "wedding", 16, 96, 4, c -> c.weddingRange, (c, x) -> c.weddingRange = x);
        integer("weddingScenePercent", s, "wedding", 0, 100, 5, c -> c.weddingScenePercent, (c, x) -> c.weddingScenePercent = x);
        integer("weddingAttireDays", s, "wedding", 0, 30, 1, c -> c.weddingAttireDays, (c, x) -> c.weddingAttireDays = x);
        // Ребёнок в семье (трек N).
        integer("pregnancyDays", s, "child", 1, 20, 1, c -> c.pregnancyDays, (c, x) -> c.pregnancyDays = x);
        integer("conceptionChance", s, "child", 0, 100, 1, c -> c.conceptionChance, (c, x) -> c.conceptionChance = x);
        integer("specialNightChanceMin", s, "child", 0, 100, 1, c -> c.specialNightChanceMin, (c, x) -> c.specialNightChanceMin = x);
        integer("specialNightChanceMax", s, "child", 0, 100, 1, c -> c.specialNightChanceMax, (c, x) -> c.specialNightChanceMax = x);
        integer("specialNightHearts", s, "child", 0, 20, 1, c -> c.specialNightHearts, (c, x) -> c.specialNightHearts = x);
        integer("specialNightQuietSeconds", s, "child", 5, 300, 5, c -> c.specialNightQuietSeconds, (c, x) -> c.specialNightQuietSeconds = x);
        // Bonds between mobs and their walks.
        integer("socialActionsPerMinute", s, "bonds", 1, 30, 1, c -> c.socialActionsPerMinute, (c, x) -> c.socialActionsPerMinute = x);
        integer("socialPlayerRange", s, "bonds", 8, 96, 1, c -> c.socialPlayerRange, (c, x) -> c.socialPlayerRange = x);
        decimal("socialApproachChance", s, "bonds", 0, 100, 0.5, c -> c.socialApproachChance, (c, x) -> c.socialApproachChance = x);
        integer("socialApproachMin", s, "bonds", 1, 32, 1, c -> c.socialApproachMin, (c, x) -> c.socialApproachMin = x);
        integer("socialApproachMax", s, "bonds", 2, 64, 1, c -> c.socialApproachMax, (c, x) -> c.socialApproachMax = x);
        integer("socialRetreatRange", s, "bonds", 1, 16, 1, c -> c.socialRetreatRange, (c, x) -> c.socialRetreatRange = x);
        integer("bondHalfLifeHours", s, "bonds", 1, 72, 1, c -> c.bondHalfLifeHours, (c, x) -> c.bondHalfLifeHours = x);
        integer("griefMinutes", s, "bonds", 1, 600, 1, c -> c.griefMinutes, (c, x) -> c.griefMinutes = x);
        // Village rumours.
        integer("rumorForgetDays", s, "rumors", 1, 30, 1, c -> c.rumorForgetDays, (c, x) -> c.rumorForgetDays = x);
        integer("rumorsPerVillage", s, "rumors", 1, 50, 1, c -> c.rumorsPerVillage, (c, x) -> c.rumorsPerVillage = x);
        integer("rumorsPerVillager", s, "rumors", 1, 20, 1, c -> c.rumorsPerVillager, (c, x) -> c.rumorsPerVillager = x);
        integer("rumorSpreadPercent", s, "rumors", 0, 100, 1, c -> c.rumorSpreadPercent, (c, x) -> c.rumorSpreadPercent = x);
        integer("rumorsInPrompt", s, "rumors", 0, 10, 1, c -> c.rumorsInPrompt, (c, x) -> c.rumorsInPrompt = x);
        integer("rumorChronicleImportance", s, "rumors", 1, 100, 1, c -> c.rumorChronicleImportance, (c, x) -> c.rumorChronicleImportance = x);
        // Creatures speaking first.
        integer("initiativesPerMinute", s, "initiative", 1, 6, 1, c -> c.initiativesPerMinute, (c, x) -> c.initiativesPerMinute = x);
        integer("initiativeMobCooldownMinutes", s, "initiative", 1, 60, 1, c -> c.initiativeMobCooldownMinutes, (c, x) -> c.initiativeMobCooldownMinutes = x);
        integer("initiativeReturnMinutes", s, "initiative", 1, 240, 1, c -> c.initiativeReturnMinutes, (c, x) -> c.initiativeReturnMinutes = x);
        integer("initiativeSpeakRange", s, "initiative", 2, 16, 1, c -> c.initiativeSpeakRange, (c, x) -> c.initiativeSpeakRange = x);
        integer("initiativeHearRange", s, "initiative", 4, 32, 1, c -> c.initiativeHearRange, (c, x) -> c.initiativeHearRange = x);
        integer("initiativeMetRange", s, "initiative", 4, 48, 1, c -> c.initiativeMetRange, (c, x) -> c.initiativeMetRange = x);
        integer("sceneMaxLines", s, "initiative", Initiative.SCENE_MIN_LINES, 8, 1, c -> c.sceneMaxLines, (c, x) -> c.sceneMaxLines = x);
        // Village small talk and the villagers' chat announcements.
        integer("villageChatterSeconds", s, "chatter", 30, 900, 10, c -> c.villageChatterSeconds, (c, x) -> c.villageChatterSeconds = x);
        integer("villageChatterRange", s, "chatter", 4, 32, 1, c -> c.villageChatterRange, (c, x) -> c.villageChatterRange = x);
        integer("noticeMinutes", s, "chatter", 1, 30, 1, c -> c.noticeMinutes, (c, x) -> c.noticeMinutes = x);
        integer("noticeWorkPercent", s, "chatter", 0, 100, 5, c -> c.noticeWorkPercent, (c, x) -> c.noticeWorkPercent = x);
        integer("noticeRange", s, "chatter", 16, 128, 4, c -> c.noticeRange, (c, x) -> c.noticeRange = x);
        // The village's view of the player.
        integer("villageExilePoints", s, "village", -1000, 0, 10, c -> c.villageExilePoints, (c, x) -> c.villageExilePoints = x);
        integer("villageSuspectPoints", s, "village", -500, 0, 5, c -> c.villageSuspectPoints, (c, x) -> c.villageSuspectPoints = x);
        integer("villageFriendPoints", s, "village", 0, 1000, 10, c -> c.villageFriendPoints, (c, x) -> c.villageFriendPoints = x);
        integer("villageGuardPoints", s, "village", 0, 2000, 10, c -> c.villageGuardPoints, (c, x) -> c.villageGuardPoints = x);
        integer("villageElderPoints", s, "village", 0, 3000, 10, c -> c.villageElderPoints, (c, x) -> c.villageElderPoints = x);

        // Friendly duties (STAY, GUARD, SPARE).
        integer("friendLeaseMinutes", a, "friends", 1, 60, 1, c -> c.friendLeaseMinutes, (c, x) -> c.friendLeaseMinutes = x);
        integer("friendLeaveRange", a, "friends", 8, 128, 1, c -> c.friendLeaveRange, (c, x) -> c.friendLeaveRange = x);
        integer("guardNear", a, "friends", 1, 8, 1, c -> c.guardNear, (c, x) -> c.guardNear = x);
        integer("guardFar", a, "friends", 2, 16, 1, c -> c.guardFar, (c, x) -> c.guardFar = x);
        integer("guardAlert", a, "friends", 2, 32, 1, c -> c.guardAlert, (c, x) -> c.guardAlert = x);
        integer("guardChase", a, "friends", 4, 64, 1, c -> c.guardChase, (c, x) -> c.guardChase = x);
        decimal("stayDrift", a, "friends", 0.5, 8, 0.5, c -> c.stayDrift, (c, x) -> c.stayDrift = x);
        // Trade.
        integer("spokenOfferMinutes", a, "trade", 1, 60, 1, c -> c.spokenOfferMinutes, (c, x) -> c.spokenOfferMinutes = x);
        integer("bargainCooldownMinutes", a, "trade", 1, 120, 1, c -> c.bargainCooldownMinutes, (c, x) -> c.bargainCooldownMinutes = x);
        // Сервер (трек C): лимиты расходов, 0 — без ограничения; одновременные ответы на сервере.
        integer("budgetPlayerPerMinute", m, "budget", 0, 60, 1, c -> c.budgetPlayerPerMinute, (c, x) -> c.budgetPlayerPerMinute = x);
        decimal("budgetPlayerDailyUsd", m, "budget", 0, 20, 0.05, c -> c.budgetPlayerDailyUsd, (c, x) -> c.budgetPlayerDailyUsd = x);
        integer("budgetServerPerMinute", m, "budget", 0, 300, 1, c -> c.budgetServerPerMinute, (c, x) -> c.budgetServerPerMinute = x);
        decimal("budgetServerDailyUsd", m, "budget", 0, 100, 0.1, c -> c.budgetServerDailyUsd, (c, x) -> c.budgetServerDailyUsd = x);
        decimal("budgetStreamerMultiplier", m, "budget", 1, 10, 0.5, c -> c.budgetStreamerMultiplier, (c, x) -> c.budgetStreamerMultiplier = x);
        integer("maxConcurrentDialogues", m, "budget", 1, 32, 1, c -> c.maxConcurrentDialogues, (c, x) -> c.maxConcurrentDialogues = x);
    }

    public static final List<Tunable> ALL = List.copyOf(TABLE);

    public static List<Tunable> of(Section section) { return ALL.stream().filter(t -> t.section() == section).toList(); }
    public static Tunable find(String field) { return ALL.stream().filter(t -> t.field().equals(field)).findFirst().orElse(null); }

    /** Every number back into its range, then the pairs that must keep their order (a step below the next one). */
    static void normalize(MobTalkConfig config) {
        for (Tunable tunable : ALL) tunable.set(config, tunable.get(config));
        config.stateSlowHalfLifeMinutes = Math.max(config.stateSlowHalfLifeMinutes, config.stateHalfLifeMinutes);
        config.heartsDating = Math.max(config.heartsDating, config.heartsFriend);
        config.heartsMarriage = Math.max(config.heartsMarriage, config.heartsDating);
        config.socialApproachMax = Math.max(config.socialApproachMax, config.socialApproachMin + 1);
        config.initiativeHearRange = Math.max(config.initiativeHearRange, config.initiativeSpeakRange);
        config.villageExilePoints = Math.min(config.villageExilePoints, config.villageSuspectPoints);
        config.villageGuardPoints = Math.max(config.villageGuardPoints, config.villageFriendPoints);
        config.villageElderPoints = Math.max(config.villageElderPoints, config.villageGuardPoints);
        config.guardFar = Math.max(config.guardFar, config.guardNear);
        config.guardChase = Math.max(config.guardChase, config.guardAlert);
    }

    /** Copies every number as it is, unclamped: a draft keeps what the file said until it is saved. */
    static void copy(MobTalkConfig from, MobTalkConfig to) { for (Tunable tunable : ALL) tunable.setter().accept(to, tunable.get(from)); }

    /** The numbers of one tab back to their defaults. */
    public static void reset(MobTalkConfig config, Section section) { for (Tunable tunable : of(section)) tunable.set(config, tunable.defaultValue()); }

    /** «Сбросить раздел»: the numbers, switches and choices of one tab back to their defaults. What the player wrote or picked
     * by hand stays: the microphone, the common style prompt, the names, the API keys (never in the config anyway). */
    public static void resetSection(MobTalkConfig config, Section section) {
        reset(config, section);
        MobTalkConfig d = DEFAULTS;
        switch (section) {
            case GENERAL -> { config.enabled = d.enabled; config.vision = d.vision; config.showStateHud = d.showStateHud; config.showBargainHud = d.showBargainHud; config.showNameTags = d.showNameTags; config.humanVillagers = d.humanVillagers; }
            case VOICE -> {
                config.whisperModel = d.whisperModel; config.whisperDevice = d.whisperDevice; config.whisperLanguage = d.whisperLanguage;
                config.whisperAutoStart = d.whisperAutoStart; config.whisperUrl = d.whisperUrl; config.whisperHotwords = d.whisperHotwords;
                config.ttsProvider = d.ttsProvider; config.ttsModel = d.ttsModel; config.veniceTtsModel = d.veniceTtsModel; config.veniceVoice = d.veniceVoice;
            }
            case MODEL -> {
                config.llmProvider = d.llmProvider; config.llmModel = d.llmModel; config.veniceModel = d.veniceModel;
                config.reasoningEffort = d.reasoningEffort; config.backgroundModel = d.backgroundModel; config.decider = d.decider;
            }
            case TALK -> config.rudeness = d.rudeness;
            case SOCIAL -> {
                config.socialLayer = d.socialLayer; config.playerGender = d.playerGender; config.familyLife = d.familyLife; config.coSleep = d.coSleep; config.weddingCeremony = d.weddingCeremony;
                config.rivals = d.rivals; config.rivalSteal = d.rivalSteal; config.rivalTrials = d.rivalTrials; config.rivalBellScene = d.rivalBellScene;
                config.pregnancy = d.pregnancy; config.pregnancyBelly = d.pregnancyBelly; config.pregnancyStatus = d.pregnancyStatus; config.familyFade = d.familyFade;
                config.socialBehavior = d.socialBehavior; config.initiative = d.initiative;
                config.villageChatter = d.villageChatter; config.villageNotices = d.villageNotices; config.villageSites = d.villageSites;
            }
            case ACTIONS -> { config.placeAnalysis = d.placeAnalysis; config.placeClassifier = d.placeClassifier; }
        }
    }
}
