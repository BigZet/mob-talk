package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Трек J: ревность пары игрока (сила поводов, паузы, затухание, внимание, строка для промпта) и сон вместе — чистые правила и память
 * мира, без Minecraft. */
class JealousyTest {
    @TempDir Path directory;
    private final MobTalkConfig config = new MobTalkConfig();
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    @Test void giftIsTheStrongestCauseAndSightBeatsRumour() {
        int talk = Jealousy.amount(Jealousy.Cause.TALK, 0, Jealousy.Source.SAW, 0, 16, false, config);
        int gift = Jealousy.amount(Jealousy.Cause.GIFT, 0, Jealousy.Source.SAW, 0, 16, false, config);
        int precious = Jealousy.amount(Jealousy.Cause.GIFT, 10, Jealousy.Source.SAW, 0, 16, false, config);
        assertEquals(config.jealousyTalk, talk);
        assertTrue(gift > talk && precious > gift, "a dearer gift hurts more: " + gift + " < " + precious);
        int near = Jealousy.amount(Jealousy.Cause.GIFT, 4, Jealousy.Source.SAW, 1, 16, false, config);
        int far = Jealousy.amount(Jealousy.Cause.GIFT, 4, Jealousy.Source.SAW, 16, 16, false, config);
        int rumour = Jealousy.amount(Jealousy.Cause.GIFT, 4, Jealousy.Source.RUMOR, 0, 16, false, config);
        assertTrue(near > far, "seen up close weighs more than from afar");
        assertEquals(Math.round((config.jealousyGift + 8) * 0.5), far, "at the edge of sight — half");
        assertEquals(Math.round((config.jealousyGift + 8) * config.jealousyRumorPercent / 100.0), rumour);
        assertEquals(Math.round(near * config.jealousyMarriedPercent / 100.0),
                Jealousy.amount(Jealousy.Cause.GIFT, 4, Jealousy.Source.SAW, 1, 16, true, config), 1, "a spouse is more jealous");
        MobTalkConfig off = new MobTalkConfig(); off.jealousyGift = 0;
        assertEquals(0, Jealousy.amount(Jealousy.Cause.GIFT, 10, Jealousy.Source.SAW, 0, 16, false, off), "a zero base turns the cause off");
    }

    @Test void talkPausesGiftsDoNotAndEverythingFades() {
        Jealousy j = new Jealousy();
        String rival = UUID.randomUUID().toString();
        long t = 1_000_000;
        assertEquals(3, j.feel(Jealousy.Cause.TALK, rival, "Фёкла", Gender.FEMALE, "", 3, Jealousy.Source.SAW, t));
        assertEquals(0, j.feel(Jealousy.Cause.TALK, rival, "Фёкла", Gender.FEMALE, "", 3, Jealousy.Source.SAW, t + 60_000), "one chat per two minutes");
        assertEquals(3, j.feel(Jealousy.Cause.TALK, rival, "Фёкла", Gender.FEMALE, "", 3, Jealousy.Source.SAW, t + Jealousy.TALK_COOLDOWN_MILLIS));
        assertEquals(12, j.feel(Jealousy.Cause.GIFT, rival, "Фёкла", Gender.FEMALE, "minecraft:diamond", 12, Jealousy.Source.SAW, t + Jealousy.TALK_COOLDOWN_MILLIS));
        assertEquals(12, j.feel(Jealousy.Cause.GIFT, rival, "Фёкла", Gender.FEMALE, "minecraft:diamond", 12, Jealousy.Source.SAW, t + Jealousy.TALK_COOLDOWN_MILLIS), "every gift counts");
        long now = t + Jealousy.TALK_COOLDOWN_MILLIS;
        assertEquals(30, j.level(now));
        assertEquals(15, j.level(now + config.jealousyHalfLifeMinutes * 60_000L), "half gone after the half-life");
        assertEquals(10, j.soothe(10, now));
        assertEquals(20, j.level(now));
        assertEquals(100, j.feel(Jealousy.Cause.GIFT, rival, "Фёкла", Gender.FEMALE, "", 500, Jealousy.Source.SAW, now) + 20, "capped at 100");
        j.soothe(1000, now);
        assertEquals(0, j.level(now)); assertEquals("", j.rival(), "soothed away, the rival is forgotten");
    }

    @Test void theHintNamesTheRivalAndTheDeed() {
        Jealousy j = new Jealousy();
        long now = 5_000_000;
        j.feel(Jealousy.Cause.TALK, UUID.randomUUID().toString(), "Фёкла", Gender.FEMALE, "", 5, Jealousy.Source.SAW, now);
        assertEquals("", j.hint(Gender.FEMALE, Gender.MALE, false, now), "below the threshold the villager does not show it");
        j.feel(Jealousy.Cause.TALK, UUID.randomUUID().toString(), "Фёкла", Gender.FEMALE, "", 30, Jealousy.Source.SAW, now);
        String hint = j.hint(Gender.FEMALE, Gender.MALE, true, now);
        assertTrue(hint.contains("Фёкла") && hint.contains("опять с ней болтал") && hint.contains("соперница") && hint.contains("ты видела это сама")
                && hint.contains("в браке"), hint);
        assertTrue(j.hint(Gender.FEMALE, Gender.FEMALE, false, now).contains("болтала"), "a woman player");
        Jealousy him = new Jealousy();
        him.feel(Jealousy.Cause.GIFT, UUID.randomUUID().toString(), "Пахом", Gender.MALE, "minecraft:emerald", 40, Jealousy.Source.RUMOR, now);
        String gift = him.hint(Gender.MALE, Gender.FEMALE, false, now);
        assertTrue(gift.contains("соперник — Пахом") && gift.contains("подарила ему minecraft:emerald") && gift.contains("донесли"), gift);
        assertTrue(him.describe(now).contains("ревность 40"));
    }

    @Test void coSleepWordsBedsAndWhoComes() {
        for (String said : List.of("Пойдём спать", "пойдем спать, дорогая", "Ложись со мной", "пора в кровать", "Давай спать уже", "спать пошли"))
            assertTrue(CoSleep.invites(said), said);
        for (String said : java.util.Arrays.asList("Я спать не хочу", "кровать сломалась", "пойдём на рынок", "", null))
            assertFalse(CoSleep.invites(said), String.valueOf(said));
        assertNull(CoSleep.bed(List.of()), "no free bed: the player's own");
        assertArrayEquals(new int[]{1, 0, 0}, CoSleep.bed(List.of(new int[]{2, 0, 2}, new int[]{1, 0, 0}, new int[]{0, 0, 0})));
        assertNull(CoSleep.bed(List.<int[]>of(new int[]{3, 0, 0})), "farther than two blocks does not count");
        assertTrue(CoSleep.comes(true, true, false, 10, false, 12));
        assertFalse(CoSleep.comes(true, true, false, 20, false, 12));
        assertTrue(CoSleep.comes(true, true, false, 20, true, 12), "called by name, it comes from twice as far");
        assertFalse(CoSleep.comes(false, true, false, 1, true, 12)); assertFalse(CoSleep.comes(true, false, false, 1, true, 12)); assertFalse(CoSleep.comes(true, true, true, 1, true, 12));
    }

    /** Жительница-жена игрока, соперница того же пола в той же деревне, соперник другого пола и соперница из чужой деревни. */
    private record World(ConversationStore store, UUID player, UUID wife, UUID rival, UUID man, UUID stranger) { }
    private World world() throws Exception {
        ConversationStore store = ConversationStore.open(directory, config);
        UUID player = UUID.randomUUID(), wife = UUID.randomUUID(), rival = UUID.randomUUID(), man = UUID.randomUUID(), stranger = UUID.randomUUID();
        synchronized (store) {
            villager(store, wife, "Марфа", Gender.FEMALE, "v1");
            villager(store, rival, "Фёкла", Gender.FEMALE, "v1");
            villager(store, man, "Пахом", Gender.MALE, "v1");
            villager(store, stranger, "Глаша", Gender.FEMALE, "v2");
            ConversationStore.Relation r = store.relation(store.state.profiles.get(wife.toString()), player);
            r.rapport = new Rapport(); r.rapport.courtship(Courtship.MARRIED); r.score = 95;
        }
        return new World(store, player, wife, rival, man, stranger);
    }
    private static void villager(ConversationStore store, UUID id, String name, Gender gender, String village) {
        ConversationStore.Profile p = store.ensure(id, "minecraft:villager", "Житель");
        p.social.gender = gender.name(); p.givenName = name; p.villageId = village;
    }

    @Test void onlyARivalOfTheSameSexSeenOrHeardOfCounts() throws Exception {
        World w = world();
        long now = System.currentTimeMillis();
        JealousyLedger ledger = w.store().jealousy();
        assertEquals(List.of(w.wife()), ledger.partners(w.player()).stream().map(JealousyLedger.Partner::id).toList());
        int trustBefore = w.store().stateView(w.wife(), w.player()).trust();
        assertTrue(ledger.noticed(w.man(), w.player(), "Дима", Jealousy.Cause.GIFT, 5, "minecraft:diamond", Map.of(w.wife(), 2.0), now).isEmpty(),
                "a man is no rival to a wife");
        assertTrue(ledger.noticed(w.stranger(), w.player(), "Дима", Jealousy.Cause.GIFT, 5, "minecraft:diamond", Map.of(), now).isEmpty(),
                "another village's gossip does not reach her");
        List<JealousyLedger.Notice> rumour = ledger.noticed(w.rival(), w.player(), "Дима", Jealousy.Cause.FAVOR, 0, "", Map.of(), now);
        assertEquals(1, rumour.size()); assertEquals(Jealousy.Source.RUMOR, rumour.getFirst().source());
        List<JealousyLedger.Notice> seen = ledger.noticed(w.rival(), w.player(), "Дима", Jealousy.Cause.GIFT, 6, "minecraft:diamond", Map.of(w.wife(), 2.0), now);
        assertEquals(Jealousy.Source.SAW, seen.getFirst().source());
        assertEquals("подарил ей minecraft:diamond", seen.getFirst().deed()); assertEquals("Фёкла", seen.getFirst().rival());
        int level = ledger.level(w.wife(), w.player(), now);
        assertEquals(rumour.getFirst().amount() + seen.getFirst().amount(), level);
        assertTrue(level > 20, "a gift seen up close in a marriage is a strong cause: " + level);
        assertTrue(w.store().relationship(w.wife(), w.player()) < 95, "hearts go down");
        assertTrue(w.store().stateView(w.wife(), w.player()).trust() < trustBefore, "trust goes down");
        assertTrue(w.store().events(w.wife()).stream().anyMatch(e -> e.contains("Ревность") && e.contains("Фёкла")));
        // Разговор с самой женой гасит ревность; с соперницей до паузы — не копится.
        assertEquals(config.jealousyAttention, ledger.attention(w.wife(), w.player(), config.jealousyAttention, now));
        assertEquals(level - config.jealousyAttention, ledger.level(w.wife(), w.player(), now));
        assertEquals(0, ledger.attention(w.rival(), w.player(), 10, now), "a rival has no jealousy to soothe");
    }

    // Talking to the village is no injury: rumoured chats with a neighbour raise a little jealousy but neither embitter the wife nor
    // fill her memory; an apology soothes the jealousy itself.
    @Test void rumouredChatsDoNotEmbitterAndAnApologySoothes() throws Exception {
        World w = world();
        long now = System.currentTimeMillis();
        JealousyLedger ledger = w.store().jealousy();
        int trust = w.store().stateView(w.wife(), w.player()).trust(), events = w.store().events(w.wife()).size();
        for (int i = 0; i < 4; i++)
            ledger.noticed(w.rival(), w.player(), "Дима", Jealousy.Cause.TALK, 0, "", Map.of(), now + i * Jealousy.TALK_COOLDOWN_MILLIS);
        long later = now + 4 * Jealousy.TALK_COOLDOWN_MILLIS;
        assertTrue(ledger.level(w.wife(), w.player(), later) > 0, "a little jealousy");
        MobState.View view = w.store().stateView(w.wife(), w.player());
        assertTrue(view.trust() >= trust - 2, "chats heard of barely touch trust: " + trust + " → " + view.trust());
        assertNotEquals(MoodSpell.ANGER, view.spell());
        assertEquals(events, w.store().events(w.wife()).size(), "nor do they fill her memory");
        ledger.noticed(w.rival(), w.player(), "Дима", Jealousy.Cause.GIFT, 5, "minecraft:diamond", Map.of(w.wife(), 2.0), later);
        int jealous = ledger.level(w.wife(), w.player(), later);
        MobSnapshot snapshot = SocialLayerTest.villager(w.wife(), w.player(), w.store().revision(w.wife()), false, "minecraft:air");
        assertTrue(w.store().commit(snapshot, "Прости меня, я был неправ", "Ладно.", "", SocialIntent.APOLOGY, ActionIntent.none(), later));
        assertTrue(ledger.level(w.wife(), w.player(), later) <= jealous - 3 * config.jealousyAttention, "an apology soothes the jealousy");
    }

    @Test void theWifeSpeaksOfItAndItSurvivesTheReload() throws Exception {
        World w = world();
        long now = System.currentTimeMillis();
        w.store().jealousy().noticed(w.rival(), w.player(), "Дима", Jealousy.Cause.TALK, 0, "", Map.of(w.wife(), 1.0), now);
        w.store().jealousy().noticed(w.rival(), w.player(), "Дима", Jealousy.Cause.SLEEP, 0, "", Map.of(w.wife(), 1.0), now);
        MobSnapshot snapshot = SocialLayerTest.villager(w.wife(), w.player(), w.store().revision(w.wife()), false, "minecraft:air");
        ConversationStore.Profile detached = w.store().profile(snapshot);
        String facts = String.join(" ", detached.socialTurn.facts());
        assertTrue(facts.contains("Фёкла") && facts.contains("спал рядом с ней") && facts.contains("ревнуешь"), facts);
        assertTrue(w.store().social().describe(w.wife(), w.player()).contains("ревность"));
        int level = w.store().jealousy().level(w.wife(), w.player(), now);
        ConversationStore.closeWorld(directory);
        ConversationStore reopened = ConversationStore.open(directory, config);
        assertEquals(level, reopened.jealousy().level(w.wife(), w.player(), now), "jealousy is saved with the relation");
    }

    @Test void aNightTogetherWarmsSoothesAndGivesOneMorningLine() throws Exception {
        World w = world();
        long now = System.currentTimeMillis();
        JealousyLedger ledger = w.store().jealousy();
        ledger.noticed(w.rival(), w.player(), "Дима", Jealousy.Cause.GIFT, 4, "minecraft:poppy", Map.of(w.wife(), 1.0), now);
        int jealous = ledger.level(w.wife(), w.player(), now), score = w.store().relationship(w.wife(), w.player());
        assertFalse(ledger.sleptTogether(w.rival(), w.player(), "Дима", now), "only a partner sleeps beside the player");
        assertTrue(ledger.sleptTogether(w.wife(), w.player(), "Дима", now));
        assertFalse(ledger.sleptTogether(w.wife(), w.player(), "Дима", now + 1000), "one night counts once");
        assertEquals(Math.min(100, score + config.coSleepHearts), w.store().relationship(w.wife(), w.player()));
        assertTrue(ledger.level(w.wife(), w.player(), now) < jealous, "a night together soothes jealousy");
        assertTrue(ledger.morning(w.wife(), w.player())); assertFalse(ledger.morning(w.wife(), w.player()), "one morning line");
        MobSnapshot snapshot = SocialLayerTest.villager(w.wife(), w.player(), w.store().revision(w.wife()), false, "minecraft:air");
        assertTrue(String.join(" ", w.store().profile(snapshot).socialTurn.facts()).contains("этой ночью вы спали рядом"));
    }
}
