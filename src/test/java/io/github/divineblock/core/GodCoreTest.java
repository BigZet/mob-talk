package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class GodCoreTest {
    private static VerdictBuilder.Context context(String prayer, double righteousness, int offering, int recent) {
        return new VerdictBuilder.Context(prayer, PrayerTone.of(prayer), offering, offering > 0 ? "Алмаз ×3" : "", false, righteousness,
                "", List.of(), 10, 0, recent, false, 2, false);
    }
    private static DivineConfig config() { DivineConfig config = new DivineConfig(); config.personality = DivineConfig.Personality.TRICKSTER; return config; }

    @Test void deedsAreCappedPerDayAndFade() {
        Righteousness r = new Righteousness();
        for (int i = 0; i < 100; i++) r.record(Righteousness.Deed.PLANTED, 1, "");
        assertEquals(3, r.score, 1e-9, "a hundred saplings do not make a saint");
        r.record(Righteousness.Deed.VILLAGER_KILLED, 1, "");
        assertEquals(-12, r.score, 1e-9);
        r.drift(11);
        assertTrue(r.score > -12 && r.score < -6, "the score drifts toward zero: " + r.score);
        assertEquals(1, r.notable(11, 30, 5).stream().filter(m -> m.deed == Righteousness.Deed.VILLAGER_KILLED).count());
        Righteousness saint = new Righteousness(); saint.score = 70; assertEquals("святой", saint.label());
    }

    @Test void mercyWrathAndBudgetComeFromNamedReasons() {
        DivineConfig config = config();
        Verdict saint = VerdictBuilder.build(context("Пожалуйста, исцели меня", 70, 90, 0), config);
        assertEquals(Miracle.Kind.HELP, saint.inclination);
        assertTrue(saint.budget >= 2, saint.breakdown());
        assertTrue(saint.reasons.stream().anyMatch(r -> r.text().startsWith("подношение")));

        Verdict sinner = VerdictBuilder.build(context("Ты тупой бог, дай алмазы", -40, 0, 3), config);
        assertEquals(Miracle.Kind.WRATH, sinner.inclination, sinner.breakdown());
        assertTrue(sinner.budget <= 3, "apocalypse needs a truly damned soul: " + sinner.breakdown());
        assertTrue(sinner.reasons.stream().anyMatch(r -> r.text().contains("хамство")));
        assertTrue(sinner.reasons.stream().anyMatch(r -> r.text().contains("4-й раз")));

        config.maxTier = 1;
        assertEquals(1, VerdictBuilder.build(context("Пожалуйста, исцели меня", 70, 90, 0), config).budget, "the settings cap the budget");
    }

    @Test void sameDeedsAndPrayerGiveSameVerdict() {
        Verdict a = VerdictBuilder.build(context("дай хлеба", 25, 0, 0), config()), b = VerdictBuilder.build(context("дай хлеба", 25, 0, 0), config());
        RuleGodDecider rules = new RuleGodDecider();
        assertEquals(rules.choose(a, config()), rules.choose(b, config()));
    }

    @Test void whimIsANamedReasonAndCanBeOff() {
        DivineConfig config = config();
        VerdictBuilder.Context storm = new VerdictBuilder.Context("слава тебе", PrayerTone.of("слава тебе"), 0, "", false, 0, "", List.of(), 1, 0, 0, true, 2, false);
        assertTrue(VerdictBuilder.build(storm, config).reasons.stream().anyMatch(r -> r.text().contains("грозу")));
        config.whimWeight = 0;
        assertTrue(VerdictBuilder.build(storm, config).reasons.stream().noneMatch(r -> r.text().contains("грозу")));
    }

    @Test void rulesFollowThePrayerWithinTheBudgetAndFlags() {
        DivineConfig config = config();
        Verdict verdict = VerdictBuilder.build(context("Пожалуйста, исцели меня", 70, 90, 0), config);
        GodDecider.Choice choice = new RuleGodDecider().choose(verdict, config);
        assertEquals(Miracle.HEAL, choice.miracle());
        assertTrue(choice.power() <= verdict.budget && choice.power() >= Miracle.HEAL.minTier);

        Verdict doom = new Verdict(); doom.inclination = Miracle.Kind.WRATH; doom.budget = 4; doom.prayer = "визер";
        assertFalse(GodDecider.candidates(doom, config).contains(Miracle.WITHER), "the Wither needs its own flag");
        config.allowWither = true; config.maxTier = 4;
        GodDecider.Choice wither = new RuleGodDecider().choose(doom, config);
        assertEquals(Miracle.WITHER, wither.miracle());
        assertTrue(wither.warn(), "serious wrath always comes with an omen");

        Verdict quiet = new Verdict(); quiet.inclination = Miracle.Kind.OMEN; quiet.favor = 1;
        assertEquals(Miracle.OMEN_LIGHT, new RuleGodDecider().choose(quiet, config).miracle());
    }

    @Test void jevIsHeldToTheListAndTheBudget() throws Exception {
        DivineConfig config = config();
        Verdict verdict = new Verdict(); verdict.inclination = Miracle.Kind.WRATH; verdict.budget = 2; verdict.prayer = "ну и что";
        List<Miracle> candidates = GodDecider.candidates(verdict, config);
        JevGodDecider jev = new JevGodDecider("sk-test", new RuleGodDecider(), URI.create("http://127.0.0.1:9/"));
        var answers = JevGodDecider.parse("{\"answers\":{\"miracle\":{\"choice\":\"meteor\"},\"power\":{\"score\":5},\"warn\":{\"noul\":0.1}},\"usage\":{\"cost\":0.00004}}");
        GodDecider.Choice choice = jev.interpret(answers, verdict, candidates, config);
        assertEquals(Miracle.METEOR, choice.miracle());
        assertEquals(2, choice.power(), "Jev's power is cut to the budget");
        assertTrue(choice.warn());

        var invented = JevGodDecider.parse("{\"answers\":{\"miracle\":{\"choice\":\"summon_herobrine\"}}}");
        assertEquals("rules", jev.interpret(invented, verdict, candidates, config).decidedBy());
        assertEquals("rules", jev.choose(verdict, config).decidedBy(), "an unreachable Jev hands the choice to the rules");

        var questions = JevGodDecider.questions(verdict, candidates);
        assertTrue(questions.getAsJsonObject("miracle").getAsJsonObject("criteria").has("lightning"));
        assertFalse(JevGodDecider.state(verdict).toString().contains("sk-test"));
    }

    @Test void prayerToneAndWhy() {
        assertTrue(PrayerTone.isWhyQuestion("Почему?"));
        assertTrue(PrayerTone.isWhyQuestion("за что ты меня так"));
        assertFalse(PrayerTone.isWhyQuestion("почему бы тебе не дать мне алмазов и золота и железа побольше"));
        assertTrue(PrayerTone.of("Прости меня, великий").repents());
        assertTrue(PrayerTone.of("заткнись, идиот").rude());
    }

    @Test void ledgerSurvivesTheSave() {
        GodLedger ledger = new GodLedger();
        GodLedger.Soul soul = ledger.soul(java.util.UUID.randomUUID());
        soul.righteousness.record(Righteousness.Deed.RAID_WON, 3, "");
        Verdict verdict = VerdictBuilder.build(context("дай хлеба", 25, 0, 0), config());
        verdict.miracle = "feed";
        soul.remember(verdict);
        GodLedger loaded = GodLedger.fromJson(ledger.toJson());
        GodLedger.Soul back = loaded.souls.values().iterator().next();
        assertEquals(20, back.righteousness.score, 1e-9);
        assertEquals(Miracle.FEED, back.lastVerdict().chosen());
        assertEquals(verdict.reasons, back.lastVerdict().reasons);
        assertNotNull(GodLedger.fromJson("{broken"));
    }

    @Test void speechNamesAReasonWithoutAModel() throws Exception {
        Verdict verdict = VerdictBuilder.build(context("Пожалуйста, исцели меня", 70, 90, 0), config());
        verdict.miracle = "heal";
        String line = GodSpeech.template(verdict);
        assertTrue(verdict.mainReasons(1).stream().anyMatch(r -> line.toLowerCase().contains(r.text().toLowerCase())), line);
        assertTrue(GodSpeech.explainTemplate(verdict).startsWith("Ты спрашиваешь почему?"));
        assertEquals("Трепещи.", GodSpeech.clean("«**Трепещи.**»"));
        assertEquals("Да.", GodSpeech.parse(JsonParser.parseString("{\"choices\":[{\"message\":{\"content\":\"Да.\"}}]}").toString()));
        assertTrue(GodSpeech.announcement(verdict).contains("Главные причины"));
    }

    @Test void offeringsAndConfig() {
        assertEquals(30, Offerings.value("minecraft:diamond", 3));
        assertTrue(Offerings.insults("minecraft:rotten_flesh"));
        DivineConfig wild = new DivineConfig(); wild.maxTier = 9; wild.warnSeconds = 0; wild.personality = null;
        wild.sanitized();
        assertEquals(4, wild.maxTier); assertEquals(3, wild.warnSeconds); assertEquals(DivineConfig.Personality.CAPRICIOUS, wild.personality);
    }
}
