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

        Verdict sinner = VerdictBuilder.build(context("дай алмазы", -40, 0, 3), config);
        assertEquals(Miracle.Kind.WRATH, sinner.inclination, sinner.breakdown());
        assertTrue(sinner.budget <= 3, "without blasphemy, apocalypse needs a truly damned soul: " + sinner.breakdown());
        assertTrue(sinner.reasons.stream().anyMatch(r -> r.text().contains("4-й раз")));
        Verdict blasphemer = VerdictBuilder.build(context("Ты тупой бог, дай алмазы", -40, 0, 3), config);
        assertTrue(blasphemer.reasons.stream().anyMatch(r -> r.text().contains("дерзость")));
        assertEquals(4, blasphemer.budget, "blasphemy: the god strikes at the ceiling");

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

        Verdict doom = new Verdict(); doom.inclination = Miracle.Kind.WRATH; doom.budget = 4; doom.favor = -24; doom.fury = true; doom.blasphemiesBefore = 3; doom.prayer = "визер";
        assertFalse(GodDecider.candidates(doom, config).contains(Miracle.WITHER), "the Wither needs its own flag");
        config.allowWither = true; config.maxTier = 4;
        GodDecider.Choice end = new RuleGodDecider().choose(doom, config);
        // The ladder is a weighted draw now: the worst sinner lands in the two heaviest groups, mostly the fifth.
        assertTrue(MiracleGroup.K5.members.contains(end.miracle()) || MiracleGroup.K4.members.contains(end.miracle()), "the rules lean to the heaviest open group: " + end);
        assertTrue(end.warn(), "serious wrath comes with thunder");

        Verdict quiet = new Verdict(); quiet.inclination = Miracle.Kind.OMEN; quiet.favor = 1;
        assertEquals(Miracle.OMEN_LIGHT, new RuleGodDecider().choose(quiet, config).miracle());
    }

    @Test void jevPicksAnOpenGroupAndTheActIsDrawnFromIt() throws Exception {
        DivineConfig config = config();
        Verdict verdict = new Verdict(); verdict.inclination = Miracle.Kind.WRATH; verdict.budget = 3; verdict.favor = -10; verdict.fury = true; verdict.prayer = "ну и что";
        List<MiracleGroup> open = MiracleGroup.open(verdict, config);
        assertEquals(List.of(MiracleGroup.K1, MiracleGroup.K2, MiracleGroup.K3), open, "a first insult opens К1–К3");
        JevGodDecider jev = new JevGodDecider("sk-test", new RuleGodDecider(), URI.create("http://127.0.0.1:9/"));
        var answers = JevGodDecider.parse("{\"answers\":{\"group\":{\"choice\":\"K3\"}},\"usage\":{\"cost\":0.00004}}");
        GodDecider.Choice choice = jev.interpret(answers, verdict, open, config);
        assertTrue(List.of("K1", "K2", "K3").contains(choice.group()), choice.toString());

        var closed = JevGodDecider.parse("{\"answers\":{\"group\":{\"choice\":\"K5\"}}}");
        for (int i = 0; i < 30; i++)
            assertTrue(open.stream().map(MiracleGroup::id).toList().contains(jev.interpret(closed, verdict, open, config).group()), "a closed group never comes out");
        // Jev's leaning is mixed with the ladder: over many draws more than one group comes out.
        var leaning = JevGodDecider.parse("{\"answers\":{\"group\":{\"choice\":\"K1\",\"probabilities\":{\"K1\":0.8,\"K2\":0.1,\"K3\":0.1}}}}");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 60; i++) seen.add(jev.interpret(leaning, verdict, open, config).group());
        assertTrue(seen.size() >= 2, "not categorical: " + seen);
        assertEquals("rules", jev.choose(verdict, config).decidedBy(), "an unreachable Jev hands the choice to the rules");

        var questions = JevGodDecider.questions(verdict, open, false);
        assertTrue(questions.getAsJsonObject("group").getAsJsonObject("criteria").has("K3"));
        assertFalse(JevGodDecider.state(verdict).toString().contains("sk-test"));

        // The third blasphemy opens the end of the world.
        verdict.blasphemiesBefore = 2;
        assertTrue(MiracleGroup.open(verdict, config).contains(MiracleGroup.K5));
    }

    @Test void theGodMayChooseSilence() {
        DivineConfig config = config();
        Verdict pest = VerdictBuilder.build(context("дай алмазы", 0, 0, 4), config);
        assertTrue(GodDecider.candidates(pest, config).contains(Miracle.IGNORE));
        assertEquals(Miracle.IGNORE, new RuleGodDecider().choose(pest, config).miracle(), pest.breakdown());
        config.disabled.add("ignore");
        assertNotEquals(Miracle.IGNORE, new RuleGodDecider().choose(pest, config).miracle(), "silence can be switched off");
    }

    @Test void onlyPrayersAreHeard() {
        assertTrue(PrayerTone.addressesGod("Господи, даруй мне сил"));
        assertTrue(PrayerTone.addressesGod("О великий, услышь"));
        assertTrue(PrayerTone.addressesGod("ты тупой бог"));
        assertFalse(PrayerTone.addressesGod("пойду накопаю угля и поставлю факелы"));
        assertFalse(PrayerTone.addressesGod("эй, Вася, глянь сюда"));
    }

    @Test void prayerToneAndWhy() {
        assertTrue(PrayerTone.isWhyQuestion("Почему?"));
        assertTrue(PrayerTone.isWhyQuestion("за что ты меня так"));
        assertFalse(PrayerTone.isWhyQuestion("почему бы тебе не дать мне алмазов и золота и железа побольше"));
        assertTrue(PrayerTone.of("Прости меня, великий").repents());
        assertTrue(PrayerTone.of("заткнись, идиот").rude());
        assertTrue(PrayerTone.of("да пошёл ты нахуй, бог").profane());
        assertTrue(PrayerTone.of("Сука, дай алмазы").profane());
        assertFalse(PrayerTone.of("я употребляю хлеб и строю себе дом").profane(), "no false alarm inside ordinary words");
    }

    @Test void swearingAtTheGodBringsRealWrath() {
        DivineConfig config = config();
        // Even a saint bearing diamonds: blasphemy throws the god into fury at once.
        Verdict verdict = VerdictBuilder.build(context("бля, ты бесполезный бог, дай алмазы", 80, 90, 0), config);
        assertTrue(verdict.fury, verdict.breakdown());
        assertEquals(Miracle.Kind.WRATH, verdict.inclination, verdict.breakdown());
        assertEquals(4, verdict.budget, verdict.breakdown());
        List<Miracle> punishments = GodDecider.candidates(verdict, config);
        assertTrue(punishments.contains(Miracle.METEOR) && !punishments.contains(Miracle.GREAT_CRATER), "a first insult opens К1–К3: " + punishments);
        assertFalse(punishments.contains(Miracle.IGNORE), "no silence in fury");
        GodDecider.Choice choice = new RuleGodDecider().choose(verdict, config);
        assertTrue(List.of("K1", "K2", "K3").contains(choice.group()), choice.toString());
        assertTrue(VerdictBuilder.build(context("ты тупой бог", 0, 0, 0), config).fury, "an insult without swearing is blasphemy too");
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
        assertTrue(GodSpeech.explainTemplate(verdict).startsWith("Спрашиваешь, почему?"));
        assertEquals("Трепещи.", GodSpeech.clean("«**Трепещи.**»"));
        assertEquals("Раз. Два! Три? Четыре.", GodSpeech.clean("Раз. Два! Три? Четыре. Пять."));
        assertEquals("Ныне раны затворятся.", GodSpeech.clean("Ныне раны затворятся. Внемли: за хулу в молитве и гр"), "a cut-off tail is dropped");
        assertNull(GodSpeech.clean("Внемли: за хулу в молитве и гр"), "a reply cut off before its first full stop falls back to the template");
        assertEquals("Да.", GodSpeech.parse(JsonParser.parseString("{\"choices\":[{\"message\":{\"content\":\"Да.\"}}]}").toString()));
        assertTrue(GodSpeech.announcement(verdict).contains("Главные причины"));
    }

    @Test void offeringsAndConfig() {
        assertEquals(30, Offerings.value("minecraft:diamond", 3));
        assertTrue(Offerings.insults("minecraft:rotten_flesh"));
        DivineConfig wild = new DivineConfig(); wild.maxTier = 9; wild.whimWeight = 9; wild.personality = null;
        wild.sanitized();
        assertEquals(4, wild.maxTier); assertEquals(3, wild.whimWeight); assertEquals(DivineConfig.Personality.CAPRICIOUS, wild.personality);
    }

    @Test void templeRingsOnWithoutClipping() {
        int rate = 24_000;
        byte[] pcm = new byte[rate * 2]; // One loud second: a click train at full scale.
        for (int i = 0; i < rate; i += 600) { pcm[2 * i] = (byte) 0xFF; pcm[2 * i + 1] = (byte) 0x7F; }
        byte[] wet = VoiceEffects.temple(pcm, rate);
        assertTrue(wet.length > pcm.length, "the hall rings on after the last word");
        assertTrue(wet.length <= pcm.length + (int) (VoiceEffects.TAIL_SECONDS * rate) * 2);
        int peak = 0, after = 0;
        for (int i = 0; i < wet.length / 2; i++) {
            int v = Math.abs((short) ((wet[2 * i] & 255) | (wet[2 * i + 1] << 8)));
            peak = Math.max(peak, v);
            if (i > rate + rate / 10 && i < rate + rate / 2) after = Math.max(after, v);
        }
        assertTrue(peak <= 31_200, "kept below clipping: " + peak);
        assertTrue(after > 100, "reverb and echo are heard after the dry sound ends: " + after);
    }

    @Test void tornadoFunnelLeansSpinsAndPulls() {
        TornadoFunnel funnel = new TornadoFunnel(0);
        assertEquals(35.2, funnel.topRadius(), 1e-9);
        for (int t = 0; t < 400; t++) funnel.tick(0.3 * t, 64, 0);
        assertTrue(funnel.y[TornadoFunnel.LAYERS - 1] - funnel.y[0] > 70, "a tall horn");
        double lag = funnel.x[0] - funnel.x[TornadoFunnel.LAYERS - 1];
        assertTrue(lag > 30 && lag < 60, "the top lags behind a moving base: " + lag);
        assertTrue(funnel.ringRadius(TornadoFunnel.LAYERS - 1) > 5 * funnel.ringRadius(10), "flares toward the top");
        double[] near = funnel.force(funnel.x[0] + 20, 65, funnel.z[0], 0, 1);
        assertTrue(near[1] > 0.08, "lift beats gravity near the funnel");
        double inward = -(near[0] * 20) / 20;
        assertTrue(inward > 0, "pulled inward at the ground");
        double[] far = funnel.force(funnel.x[0] + 130, 65, funnel.z[0], 0, 1);
        assertEquals(0, far[0], 1e-9); assertEquals(0, far[1], 1e-9);
    }

    @Test void wrathClimbsTheLadderAndNeverRepeats() {
        DivineConfig config = config();
        config.allowWither = true;
        // A first insult: heavy, but far from the end of the world.
        Verdict first = VerdictBuilder.build(context("ты тупой бог", 0, 0, 0), config);
        List<Miracle> early = GodDecider.candidates(first, config);
        assertFalse(early.contains(Miracle.GREAT_CRATER), "no apocalypse for a first insult");
        // A damned soul insulting again and again, pestering: the ladder's top.
        Verdict worst = VerdictBuilder.build(context("бля, сука, ты ничтожество", -90, 0, 5), config);
        assertTrue(worst.level() >= 7, worst.breakdown());
        assertTrue(GodDecider.candidates(worst, config).stream().anyMatch(m -> m.gravity() >= 9), "the worst wrath is within reach");
        // What was just done is set aside.
        first.recentActs = MiracleGroup.K3.members.stream().limit(3).map(Miracle::id).toList();
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < 50; i++)
            assertFalse(first.recentActs.contains(MiracleGroup.K3.draw(first, config, random).id()), "a recent act is not drawn again");
        // Six blasphemies in a row: the rules never pick the same act twice running.
        java.util.List<String> history = new java.util.ArrayList<>();
        String previous = "";
        for (int i = 0; i < 6; i++) {
            Verdict verdict = VerdictBuilder.build(context("ты тупой бог", -30, 0, i), config);
            verdict.recentActs = history.subList(Math.max(0, history.size() - 3), history.size());
            String chosen = new RuleGodDecider().choose(verdict, config).miracle().id();
            assertNotEquals(previous, chosen);
            history.add(chosen); previous = chosen;
        }
    }

    @Test void theRepentantBlasphemerIsForgiven() {
        DivineConfig config = config();
        for (String apology : List.of("Прости меня бог прости я слуга твой", "Прости меня бог за грехи мои")) {
            Verdict verdict = VerdictBuilder.build(new VerdictBuilder.Context(apology, PrayerTone.of(apology), 0, "", false, -30, "", List.of(), 1,
                    -100, 5, false, 2, false, false, 2), config);
            assertTrue(verdict.forgiveness, verdict.breakdown());
            assertNotEquals(Miracle.Kind.WRATH, verdict.inclination, verdict.breakdown());
            assertFalse(verdict.reasons.stream().anyMatch(r -> r.text().startsWith("просишь уже")), "an apology is not pestering");
        }
        // Swearing while "apologising" is still blasphemy.
        Verdict mock = VerdictBuilder.build(new VerdictBuilder.Context("прости, сука", PrayerTone.of("прости, сука"), 0, "", false, 0, "", List.of(), 1,
                0, 0, false, 2, false, false, 2), config);
        assertTrue(mock.fury, mock.breakdown());
    }

    @Test void eachPunishmentInARowIsARungWorse() {
        DivineConfig config = config();
        Verdict plain = VerdictBuilder.build(context("ты тупой бог", 0, 0, 0), config);
        Verdict again = VerdictBuilder.build(context("ты тупой бог", 0, 0, 0), config);
        VerdictBuilder.escalate(again, 2, config);
        assertTrue(again.level() >= Math.min(10, plain.level() + 4), "two punishments before: at least two groups up: " + again.breakdown());
        assertTrue(again.severity() > plain.severity());
        assertTrue(again.reasons.stream().anyMatch(r -> r.text().startsWith("кара за карой")), "escalation is a named reason");
        assertTrue(MiracleGroup.open(again, config).contains(MiracleGroup.K4), "the streak opens the armies");
        config.escalation = false;
        Verdict off = VerdictBuilder.build(context("ты тупой бог", 0, 0, 0), config);
        VerdictBuilder.escalate(off, 2, config);
        assertEquals(plain.level(), off.level(), "escalation can be switched off");
    }

    @Test void repentanceUnderABurdenIsForgivenEvenMidEscalation() {
        DivineConfig config = config();
        String apology = "Прости меня, господи, каюсь";
        Verdict verdict = VerdictBuilder.build(context(apology, -40, 0, 0), config);
        VerdictBuilder.forgiveBurden(verdict, PrayerTone.of(apology).repents(), true);
        VerdictBuilder.escalate(verdict, 3, config);
        assertTrue(verdict.forgiveness, verdict.breakdown());
        assertEquals(0, verdict.escalation, "forgiven, not escalated");
        Verdict rude = VerdictBuilder.build(context("прости, тупой бог", 0, 0, 0), config);
        VerdictBuilder.forgiveBurden(rude, true, true);
        assertFalse(rude.forgiveness, "an insult is no repentance");
    }

    @Test void theEternalBanIsOffUntilSwitchedOn() {
        DivineConfig config = config();
        assertFalse(new DivineConfig().allowEternalBan, "off by default");
        Verdict damned = new Verdict(); damned.inclination = Miracle.Kind.WRATH; damned.budget = 4; damned.favor = -30; damned.fury = true; damned.blasphemiesBefore = 3;
        assertFalse(MiracleGroup.open(damned, config).contains(MiracleGroup.K6));
        assertFalse(GodDecider.candidates(damned, config).contains(Miracle.ETERNAL_BAN));
        config.allowEternalBan = true;
        assertTrue(MiracleGroup.open(damned, config).contains(MiracleGroup.K6), "the fourth blasphemy opens the ban");
        damned.blasphemiesBefore = 1;
        assertFalse(MiracleGroup.open(damned, config).contains(MiracleGroup.K6), "not for the second");
        for (Miracle miracle : Miracle.values()) if (miracle.kind == Miracle.Kind.WRATH)
            assertTrue(java.util.Arrays.stream(MiracleGroup.values()).anyMatch(g -> g.members.contains(miracle)), "every punishment is in a group: " + miracle);
    }
}
