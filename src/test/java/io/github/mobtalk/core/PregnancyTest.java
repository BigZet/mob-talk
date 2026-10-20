package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Трек N: кто носит, срок по третям, строка под именем, шанс ночи, особый вечер и правило тона, от согласия через ночь к родам,
 * и прежний путь для однополой пары и выключенной беременности. Без Minecraft. */
class PregnancyTest {
    private static final long DAY = ChildGrowth.DAY_TICKS;
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    @Test void theWomanOfTheCoupleCarries() {
        assertEquals(Pregnancy.Carrier.VILLAGER, Pregnancy.carrier(Gender.FEMALE, Gender.MALE));
        assertEquals(Pregnancy.Carrier.VILLAGER, Pregnancy.carrier(Gender.FEMALE, null), "the player's sex unset: the villager woman carries");
        assertEquals(Pregnancy.Carrier.PLAYER, Pregnancy.carrier(Gender.MALE, Gender.FEMALE));
        assertEquals(Pregnancy.Carrier.PLAYER, Pregnancy.carrier(Gender.MALE, null));
        assertEquals(Pregnancy.Carrier.NONE, Pregnancy.carrier(Gender.MALE, Gender.MALE));
        assertEquals(Pregnancy.Carrier.NONE, Pregnancy.carrier(Gender.FEMALE, Gender.FEMALE));
    }

    @Test void theTermGoesByThirdsAndShowsUnderTheName() {
        assertEquals(Pregnancy.Term.EARLY, Pregnancy.term(1000, 1000, 6));
        assertEquals(Pregnancy.Term.MIDDLE, Pregnancy.term(0, 2 * DAY, 6));
        assertEquals(Pregnancy.Term.LATE, Pregnancy.term(0, 4 * DAY, 6));
        assertEquals(Pregnancy.Term.DUE, Pregnancy.term(0, 6 * DAY, 6));
        assertEquals(1, Pregnancy.day(0, 0, 5));
        assertEquals(3, Pregnancy.day(0, 2 * DAY + 5, 5));
        assertEquals(5, Pregnancy.day(0, 40 * DAY, 5), "never past the last day");
        assertFalse(Pregnancy.known(0, DAY - 1, 5), "nobody knows on the first day");
        assertTrue(Pregnancy.known(0, DAY, 5));
        assertTrue(Pregnancy.known(0, DAY / 3 + 1, 1), "a one-day term is known after a third of it");
        assertEquals("ждёт малыша · 3 из 5 дн.", Pregnancy.status(Pregnancy.Term.MIDDLE, 3, 5));
        assertEquals("ждёт малыша · скоро роды", Pregnancy.status(Pregnancy.Term.LATE, 5, 5));
        assertEquals(0f, Pregnancy.belly(Pregnancy.Term.EARLY));
        assertTrue(Pregnancy.belly(Pregnancy.Term.MIDDLE) > 0 && Pregnancy.belly(Pregnancy.Term.MIDDLE) < Pregnancy.belly(Pregnancy.Term.LATE));
    }

    @Test void aSpecialNightIsLikelierTheCosierTheHome() {
        assertEquals(0.15, Pregnancy.chance(false, 6, 15, 40, 80), 1e-9, "an ordinary night ignores the room");
        assertEquals(0.40, Pregnancy.chance(true, 0, 15, 40, 80), 1e-9);
        assertEquals(0.80, Pregnancy.chance(true, Pregnancy.Cozy.MAX, 15, 40, 80), 1e-9);
        assertEquals(0.60, Pregnancy.chance(true, 3, 15, 40, 80), 1e-9);
        Pregnancy.Cozy cozy = new Pregnancy.Cozy(true, true, false, true, false, true);
        assertEquals(4, cozy.score());
        assertTrue(cozy.words().contains("в проигрывателе играет пластинка"));
        assertEquals(Pregnancy.craving(3), Pregnancy.craving(3), "the wish of a day holds all day");
    }

    @Test void theEveningIsCalledAndTheToneHolds() {
        assertTrue(SpecialEvening.invites("Пойдём домой, у меня для тебя сюрприз"));
        assertTrue(SpecialEvening.invites("давай устроим особый вечер"));
        assertTrue(SpecialEvening.invites("Романтический ужин при свечах?"));
        assertFalse(SpecialEvening.invites("пойдём спать"));
        assertFalse(SpecialEvening.explicit("Иди ко мне. Обними меня крепче и поцелуй."), "tenderness passes");
        assertFalse(SpecialEvening.explicit("Ты член нашей семьи, и я прижмусь к твоей груди."), "family words are not explicit");
        assertTrue(SpecialEvening.explicit("Давай займёмся сексом"));
        assertTrue(SpecialEvening.explicit("Она раздевается"));
        String fact = SpecialEvening.fact(List.of("свечи", "цветы"), false, true);
        assertTrue(fact.startsWith(SpecialEvening.MARK) && fact.contains("свечи") && fact.contains("без порядка и сценария") && fact.contains("затемнением"));
        assertTrue(SpecialEvening.fact(List.of(), true, false).contains("шепчетесь в темноте"));
    }

    @Test void theRuleRewritesAnExplicitReplyOnlyOnTheEvening() throws Exception {
        SocialTurn evening = new SocialTurn(java.util.Map.of(), List.of(), List.of(SpecialEvening.fact(List.of(), false, false)));
        assertThrows(AnswerValidator.Rejected.class, () -> AnswerValidator.checkTender("Сначала разденься", evening));
        AnswerValidator.checkTender("Иди ко мне", evening);
        AnswerValidator.checkTender("Сначала разденься", new SocialTurn(java.util.Map.of(), List.of(), List.of()));
    }

    /** Брак, согласие, особый вечер, ночь — зачатие; срок в промпте; роды очищают беременность. */
    @Test void fromAgreementThroughASpecialNightToBirth() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male"; config.weddingCeremony = false;
        ConversationStore store = ConversationStore.open(directory, config);
        UUID wife = UUID.randomUUID(), player = UUID.randomUUID(), baby = UUID.randomUUID();
        married(store, wife, player, Gender.FEMALE);
        assertTrue(store.children().trying(wife, player));
        assertTrue(store.children().handles(wife), "the pregnancy, not the old one-day way, leads this child");
        assertTrue(facts(store, wife, player).contains(Pregnancy.TRYING), "the spouse may suggest a special evening");
        assertNull(store.children().night(wife, player, 5 * DAY, 0.5), "an ordinary night at 15 %: a roll of 0.5 is not enough");
        store.children().startEvening(wife, player, new Pregnancy.Cozy(true, true, true, true, true, true), 1000);
        assertTrue(facts(store, wife, player).stream().anyMatch(f -> f.startsWith(SpecialEvening.MARK) && f.contains("торт на столе")), "the evening goes into the prompt");
        assertEquals(Pregnancy.Carrier.VILLAGER, store.children().night(wife, player, 5 * DAY, 0.5), "a cosy special night at 80 %");
        store.children().dark(wife, 1500);
        assertFalse(store.children().trying(wife, player));
        ChildLedger.Pregnant pregnant = store.children().pregnancies().getFirst();
        assertEquals(5 * DAY + 5 * DAY, pregnant.due(), "five game days by default");
        store.state.profiles.get(wife.toString()).relations.get(player.toString()).score = 90;
        int before = store.relationship(wife, player);
        assertTrue(store.children().morning(wife, player, "Стив", 2000));
        assertEquals(before + config.specialNightHearts, store.relationship(wife, player));
        assertNull(store.children().evening(wife), "the morning ends the evening");
        // Before anyone knows, the prompt only hints; once known, the term speaks.
        assertTrue(facts(store, wife, player).stream().anyMatch(f -> f.contains("мутит") && f.contains("ещё не знаешь")));
        store.children().progress(wife, Pregnancy.Term.MIDDLE, 3);
        assertTrue(store.children().news(wife, 1, "Ты узнала, что ждёшь ребёнка", 3000));
        assertFalse(store.children().news(wife, 1, "", 3000), "a stage is told once");
        assertTrue(facts(store, wife, player).stream().anyMatch(f -> f.contains("3-й день из 5") && f.contains("думаешь об имени")));
        ChildLedger.Overview overview = store.children().overview(player, 8 * DAY, 8 * DAY, 4000);
        assertEquals(1, overview.spouses().size());
        assertTrue(overview.spouses().getFirst().pregnancy().contains("из 5 дн."), overview.spouses().getFirst().pregnancy());
        store.social().born(baby, wife, player, "Стив", 10 * DAY, 5000);
        assertTrue(store.children().pregnancies().isEmpty());
        assertFalse(store.children().handles(wife));
        ChildLedger.Overview after = store.children().overview(player, 10 * DAY + 100, 10 * DAY + 100, 6000);
        assertEquals(1, after.children().size());
        assertEquals("младенец", after.children().getFirst().stage());
        assertTrue(after.children().getFirst().nextTicks() > 0);
    }

    @Test void aSameSexCoupleAndTheSwitchKeepTheOldWay() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male"; config.weddingCeremony = false;
        ConversationStore store = ConversationStore.open(directory, config);
        UUID husband = UUID.randomUUID(), player = UUID.randomUUID();
        married(store, husband, player, Gender.MALE);
        assertFalse(store.children().handles(husband), "two men: the old one-day way (adoption is part 2)");
        assertNull(store.children().night(husband, player, DAY, 0.0));
        config.playerGender = "female"; ConversationStore.open(directory, config);
        assertTrue(store.children().handles(husband), "the player carries");
        config.pregnancy = false; ConversationStore.open(directory, config);
        assertFalse(store.children().handles(husband), "pregnancy off: the old way");
        assertNull(store.children().night(husband, player, DAY, 0.0));
    }

    private static void married(ConversationStore store, UUID villager, UUID player, Gender gender) throws Exception {
        store.observe(SocialLayerTest.villager(villager, player, 0, false, "minecraft:air"));
        ConversationStore.Profile p = store.state.profiles.get(villager.toString());
        p.social.gender = gender.name(); p.social.traits.clear(); p.social.traits.add(VillagerTrait.BISEXUAL.name());
        p.relations.get(player.toString()).score = 95;
        store.setBackstory(villager, "Марфа", "Ты родилась в Ольховке.", "", "", "");
        store.social().applyGesture(villager, player, "Стив", store.profile(SocialLayerTest.villager(villager, player, store.revision(villager), false, "minecraft:gold_nugget"))
                .socialTurn.intent(Gesture.PROPOSE), 1000);
        GestureIntent child = store.profile(SocialLayerTest.villager(villager, player, store.revision(villager), false, "minecraft:air")).socialTurn.intent(Gesture.CHILD);
        assertNotNull(child, "married: a child can be asked for");
        assertEquals(Gesture.Outcome.ACCEPTED, child.outcome(), child.note());
        store.social().applyGesture(villager, player, "Стив", child, 2000);
    }
    private static List<String> facts(ConversationStore store, UUID villager, UUID player) throws Exception {
        return store.profile(SocialLayerTest.villager(villager, player, store.revision(villager), false, "minecraft:air")).socialTurn.facts();
    }
}
