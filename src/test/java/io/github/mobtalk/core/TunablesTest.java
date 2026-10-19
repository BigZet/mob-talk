package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The numbers that used to be constants: their defaults are the old constants, the config clamps them, and the rules read them. */
class TunablesTest {
    @TempDir Path directory;
    @AfterEach void restore() throws Exception { Tuning.apply(null); ConversationStore.closeWorld(directory); }

    // A fresh config must behave exactly as the code did before the constants became settings.
    @Test void defaultsAreTheFormerConstants() {
        MobTalkConfig d = new MobTalkConfig();
        assertEquals(MobState.HALF_LIFE, d.stateHalfLifeMinutes * 60_000L);
        assertEquals(MobState.SLOW_HALF_LIFE, d.stateSlowHalfLifeMinutes * 60_000L);
        assertEquals(List.of(MobState.WARY_CALM, MobState.WARY_TRUST, MobState.KEEN_INTEREST, MobState.GENEROUS_MOOD, MobState.GENEROUS_TRUST),
                List.of(d.waryCalm, d.waryTrust, d.keenInterest, d.generousMood, d.generousTrust));
        assertEquals(ConversationStore.WITNESS_COOLDOWN, d.witnessCooldownSeconds * 1000L);
        assertEquals(RuleDecider.SMALL_TALK_CHANCE * 100, d.smallTalkChance);
        assertEquals(List.of(Hearts.FRIEND, Hearts.DATING, Hearts.MARRIAGE), List.of(d.heartsFriend, d.heartsDating, d.heartsMarriage));
        assertEquals(SocialLedger.FOOD_FIRST, d.foodGiftHearts);
        assertEquals(GiftCatalog.SATURATION * 100, d.giftSaturationPercent);
        assertEquals(Rapport.FATIGUE_STEP_MILLIS, d.gestureFatigueMinutes * 60_000L);
        assertEquals(Rapport.GIFT_FORGET_MILLIS, d.giftForgetMinutes * 60_000L);
        assertEquals(MobRelations.BOND_HALF_LIFE, d.bondHalfLifeHours * 3_600_000L);
        assertEquals(MobRelations.GRIEF_DURATION, d.griefMinutes * 60_000L);
        assertEquals(SocialRules.PLAYER_RANGE, d.socialPlayerRange);
        assertEquals(SocialRules.APPROACH_CHANCE * 100, d.socialApproachChance, 1e-9);
        assertEquals(List.of(SocialRules.APPROACH_MIN, SocialRules.APPROACH_MAX, SocialRules.RETREAT_RANGE),
                List.of((double) d.socialApproachMin, (double) d.socialApproachMax, (double) d.socialRetreatRange));
        assertEquals(List.of(VillageRumors.FORGET_DAYS, VillageRumors.MAX_PER_VILLAGE, VillageRumors.MAX_PER_VILLAGER, VillageRumors.PROMPT_LIMIT, VillageRumors.IMPORTANCE_THRESHOLD),
                List.of(d.rumorForgetDays, d.rumorsPerVillage, d.rumorsPerVillager, d.rumorsInPrompt, d.rumorChronicleImportance));
        assertEquals(VillageRumors.BASE_CHANCE * 100, d.rumorSpreadPercent, 1e-9);
        assertEquals(Initiative.MOB_COOLDOWN_MILLIS, d.initiativeMobCooldownMinutes * 60_000L);
        assertEquals(Initiative.RETURN_MILLIS, d.initiativeReturnMinutes * 60_000L);
        assertEquals(Initiative.SPEAK_RANGE, d.initiativeSpeakRange); assertEquals(Initiative.HEAR_RANGE, d.initiativeHearRange);
        assertEquals(Initiative.SCENE_MAX_LINES, d.sceneMaxLines);
        assertEquals(List.of(VillageStanding.EXILE_POINTS, VillageStanding.SUSPECT_POINTS, VillageStanding.FRIEND_POINTS, VillageStanding.GUARD_POINTS, VillageStanding.ELDER_POINTS),
                List.of(d.villageExilePoints, d.villageSuspectPoints, d.villageFriendPoints, d.villageGuardPoints, d.villageElderPoints));
        assertEquals(FriendActions.LEASE_TICKS, d.friendLeaseMinutes * 1200L);
        assertEquals(FriendActions.LEAVE_RANGE, d.friendLeaveRange);
        assertEquals(List.of(FriendActions.GUARD_NEAR, FriendActions.GUARD_FAR, FriendActions.GUARD_ALERT, FriendActions.GUARD_CHASE, FriendActions.STAY_DRIFT),
                List.of((double) d.guardNear, (double) d.guardFar, (double) d.guardAlert, (double) d.guardChase, d.stayDrift));
        assertEquals(SpokenOffer.LIFETIME_TICKS, d.spokenOfferMinutes * 1200L);
        assertEquals(Bargaining.COOLDOWN_TICKS, d.bargainCooldownMinutes * 1200L);
        assertEquals(MemoryRanking.HALF_LIFE_MILLIS, d.memoryHalfLifeDays * 86_400_000L);
        assertEquals(MemoryRanking.FLOOR * 100, d.memoryFloorPercent, 1e-9);
        assertEquals(MemoryRanking.MAX_FACTS, d.memoryMaxFacts);
        assertEquals(ConversationSummary.SILENCE_MILLIS, d.conversationSilenceMinutes * 60_000L);
        assertEquals(List.of(Reflection.REPLIES_BETWEEN, Reflection.IMPORTANCE_THRESHOLD), List.of(d.reflectionReplies, d.reflectionImportance));
        assertEquals(Reflection.COOLDOWN_MILLIS, d.reflectionCooldownSeconds * 1000L);
        for (Tunables.Tunable t : Tunables.ALL) {
            assertTrue(t.defaultValue() >= t.min() && t.defaultValue() <= t.max(), t.field() + " default within range");
            assertEquals(t.defaultValue(), t.normalize(t.defaultValue()), t.field() + " default on its step");
        }
    }

    // Junk in the file lands on the edges, NaN on the default, and ordered pairs keep their order.
    @Test void loadClampsEveryNumberAndKeepsOrder() throws Exception {
        Path file = directory.resolve("config/mobtalk.json");
        MobTalkConfig high = new MobTalkConfig(), low = new MobTalkConfig();
        for (Tunables.Tunable t : Tunables.ALL) { t.setter().accept(high, 1e9); t.setter().accept(low, -1e9); }
        MobTalkConfig.save(file, high);
        MobTalkConfig loadedHigh = MobTalkConfig.load(file);
        MobTalkConfig.save(file, low);
        MobTalkConfig loadedLow = MobTalkConfig.load(file);
        for (Tunables.Tunable t : Tunables.ALL) {
            assertTrue(t.get(loadedHigh) <= t.max() && t.get(loadedHigh) >= t.min(), t.field() + " high");
            assertTrue(t.get(loadedLow) >= t.min() && t.get(loadedLow) <= t.max(), t.field() + " low");
        }
        assertEquals(32, loadedHigh.witnessHearingRange);
        assertEquals(0, loadedLow.witnessCooldownSeconds);
        MobTalkConfig odd = new MobTalkConfig();
        odd.maxDistance = Double.NaN; odd.stayDrift = Double.POSITIVE_INFINITY;
        odd.heartsFriend = 80; odd.heartsDating = 50; odd.heartsMarriage = 20;
        odd.stateHalfLifeMinutes = 60; odd.stateSlowHalfLifeMinutes = 5;
        odd.socialApproachMin = 20; odd.socialApproachMax = 10;
        odd.villageExilePoints = -10; odd.villageSuspectPoints = -100;
        odd.villageFriendPoints = 900; odd.villageGuardPoints = 100; odd.villageElderPoints = 50;
        odd.guardNear = 8; odd.guardFar = 2; odd.guardAlert = 30; odd.guardChase = 4;
        odd.socialApproachChance = 12.3;
        MobTalkConfig.save(file, odd);
        MobTalkConfig loaded = MobTalkConfig.load(file);
        assertEquals(16, loaded.maxDistance); assertEquals(2.5, loaded.stayDrift);
        assertEquals(List.of(80, 80, 80), List.of(loaded.heartsFriend, loaded.heartsDating, loaded.heartsMarriage));
        assertEquals(60, loaded.stateSlowHalfLifeMinutes);
        assertEquals(21, loaded.socialApproachMax);
        assertTrue(loaded.villageExilePoints <= loaded.villageSuspectPoints);
        assertTrue(loaded.villageFriendPoints <= loaded.villageGuardPoints && loaded.villageGuardPoints <= loaded.villageElderPoints);
        assertTrue(loaded.guardNear <= loaded.guardFar && loaded.guardAlert <= loaded.guardChase);
        assertEquals(12.5, loaded.socialApproachChance, "rounded to the half-percent step");
    }

    // A draft must carry every new number, the place switches and the name, and saving must keep them.
    @Test void copyAndRoundTripCarryEveryTunable() throws Exception {
        MobTalkConfig custom = new MobTalkConfig();
        for (Tunables.Tunable t : Tunables.ALL) t.set(custom, t.max());
        custom.placeAnalysis = false; custom.placeClassifier = false; custom.playerName = "Лена";
        MobTalkConfig copy = custom.copy();
        Path file = directory.resolve("config/mobtalk.json");
        MobTalkConfig.save(file, custom);
        MobTalkConfig loaded = MobTalkConfig.load(file);
        for (MobTalkConfig other : List.of(copy, loaded)) {
            for (Tunables.Tunable t : Tunables.ALL) assertEquals(t.get(custom), t.get(other), t.field());
            assertFalse(other.placeAnalysis); assertFalse(other.placeClassifier); assertEquals("Лена", other.playerName);
        }
        Tunables.reset(copy, Tunables.Section.SOCIAL);
        assertEquals(40, copy.heartsFriend); assertEquals(5, copy.weddingChance);
        assertEquals(Tunables.find("witnessHearingRange").max(), copy.witnessHearingRange, "another tab keeps its values");
        // Every numeric field of the config is in the table: nothing is left out of the screen or the copy.
        for (java.lang.reflect.Field field : MobTalkConfig.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if ((type == int.class || type == double.class) && !List.of("schemaVersion", "rudeness").contains(field.getName()))
                assertNotNull(Tunables.find(field.getName()), field.getName() + " is in Tunables");
        }
    }

    // Bystanders take their ranges and drops from the settings.
    @Test void witnessesReadRangesAndPenalties() {
        MobTalkConfig config = new MobTalkConfig();
        assertEquals(16, Witness.HIT.range(config)); assertEquals(5, Witness.INSULT.range(config));
        assertEquals(List.of(10, 25, 5, 10), List.of(Witness.HIT.penalty(config), Witness.KILL.penalty(config), Witness.INSULT.penalty(config), Witness.THREAT.penalty(config)));
        config.witnessSightRange = 30; config.witnessHearingRange = 9; config.witnessKillPenalty = 60; config.witnessInsultPenalty = 0;
        assertEquals(30, Witness.KILL.range(config)); assertEquals(9, Witness.THREAT.range(config));
        assertEquals(60, Witness.KILL.penalty(config)); assertEquals(0, Witness.INSULT.penalty(config));
        assertEquals(Witness.THREAT, Witness.of(SocialIntent.THREAT)); assertNull(Witness.of(SocialIntent.NONE));
    }

    // A zero cooldown makes every repeated blow count; the store reads its own config.
    @Test void witnessCooldownComesFromTheConfig() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.witnessCooldownSeconds = 0;
        ConversationStore store = ConversationStore.open(directory, config);
        UUID witness = UUID.randomUUID(), player = UUID.randomUUID();
        store.recordWitnessed(witness, "minecraft:villager", "Житель", player, "Стив", "ударил Марфу (корова)", -10, null, 1000);
        store.recordWitnessed(witness, "minecraft:villager", "Житель", player, "Стив", "ударил Марфу (корова)", -10, null, 1500);
        assertEquals(-20, store.card(witness, player).relationship());
    }

    // The pure rules read the live tuning.
    @Test void rulesReadTheLiveTuning() {
        MobState.View calm40 = new MobState.View(50, 40, 50, 50, "");
        assertFalse(calm40.refusesDeals());
        MobTalkConfig tuned = new MobTalkConfig();
        tuned.waryCalm = 45; tuned.keenInterest = 50; tuned.heartsFriend = 10; tuned.villageFriendPoints = 30;
        tuned.friendLeaseMinutes = 1; tuned.socialApproachChance = 0; tuned.memoryFloorPercent = 100; tuned.rumorSpreadPercent = 0;
        Tuning.apply(tuned);
        assertTrue(calm40.refusesDeals()); assertTrue(calm40.keen());
        assertEquals("♥", Hearts.symbols(15));
        assertEquals(VillageStanding.Rank.FRIEND, VillageStanding.of(List.of(10, 10, 10), 10, VillageRumors.Reputation.NONE).rank());
        assertEquals(1200, new FriendActions.Lease(ActionType.STAY, "here", 0).expires());
        assertEquals(0, VillageRumors.chance(5, true));
        assertEquals(MemoryRanking.score(3, 0, 0), MemoryRanking.score(3, 0, 365L * 86_400_000L), 1e-9, "a floor of 100% never fades");
        UUID friend = UUID.randomUUID();
        MobRelations.View bond = new MobRelations.View(friend, "Друг", "minecraft:villager", 80, 0, java.util.Set.of(MobRelations.Tag.FRIEND), false);
        SocialRules.Actor self = new SocialRules.Actor(UUID.randomUUID(), false, false, 5);
        assertEquals(SocialRules.Move.NONE, SocialRules.decide(self, List.of(bond), id -> 10.0, 0).move(), "a zero chance never walks over");
        Tuning.apply(null);
        assertEquals(SocialRules.Move.APPROACH, SocialRules.decide(self, List.of(bond), id -> 10.0, 0).move());
    }

    // The name mobs know the player by: the setting when given, else the nick; one clean line.
    @Test void playerNameOverridesTheNick() {
        MobTalkConfig config = new MobTalkConfig();
        assertEquals("Steve", PlayerIdentity.name(config, "Steve"));
        config.playerName = "  Лена\nиз\tдеревни  ";
        assertEquals("Лена из деревни", PlayerIdentity.name(config, "Steve"));
        config.playerName = "x".repeat(100);
        assertEquals(PlayerIdentity.MAX_LENGTH, PlayerIdentity.name(config, "Steve").length());
        Tuning.apply(config);
        assertEquals(PlayerIdentity.MAX_LENGTH, PlayerIdentity.name("Steve").length());
    }
}
