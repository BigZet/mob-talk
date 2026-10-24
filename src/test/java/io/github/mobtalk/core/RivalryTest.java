package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Соперники за одно сердце (П3): до помолвки житель видит всех ухажёров, пару можно отбить, кольцо при сильном сопернике решает
 * счёт выбора, проигравший получает штраф и паузу, помолвка закрывает сердце. Память мира, без Minecraft. */
class RivalryTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    private final UUID villager = UUID.randomUUID(), dima = UUID.randomUUID(), petya = UUID.randomUUID();

    private ConversationStore world(MobTalkConfig config) throws Exception {
        config.playerGender = "male";
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(villager(dima, "minecraft:air"));
        store.observe(villager(petya, "minecraft:air"));
        ConversationStore.Profile p = store.state.profiles.get(villager.toString());
        p.social.gender = Gender.FEMALE.name(); p.social.traits.clear(); p.social.persona = Persona.FRIENDLY.name();
        store.state.players.put(dima.toString(), "Дима"); store.state.players.put(petya.toString(), "Петя");
        return store;
    }
    private MobSnapshot villager(UUID player, String held) { return SocialLayerTest.villager(villager, player, 0, false, held); }
    private void hearts(ConversationStore store, UUID player, int score) { store.state.profiles.get(villager.toString()).relations.get(player.toString()).score = score; }
    private GestureIntent offer(ConversationStore store, UUID player, String held, Gesture gesture) {
        return store.profile(SocialLayerTest.villager(villager, player, store.revision(villager), false, held)).socialTurn.intent(gesture);
    }
    private void flirt(ConversationStore store, UUID player, String name) {
        store.social().applyGesture(villager, player, name, new GestureIntent(Gesture.FLIRT, Gesture.Outcome.SUCCESS, 0, ""), System.currentTimeMillis());
    }
    private Courtship courtship(ConversationStore store, UUID player) {
        Rapport rapport = store.state.profiles.get(villager.toString()).relations.get(player.toString()).rapport;
        return rapport == null ? Courtship.NONE : rapport.courtship();
    }

    @Test void choiceAndStealMarginArePureRules() {
        Rivalry.Candidate a = new Rivalry.Candidate(dima, "Дима", 90), b = new Rivalry.Candidate(petya, "Петя", 84);
        assertEquals(dima, Rivalry.choose(List.of(b, a)).winner().player());
        assertTrue(Rivalry.choose(List.of(a, new Rivalry.Candidate(petya, "Петя", 88))).tie(), "разница меньше трёх — ничья");
        assertEquals(5, Rivalry.stealMargin(Persona.FLIRTY, 15));
        assertEquals(25, Rivalry.stealMargin(Persona.SENSITIVE, 15));
        assertEquals(15, Rivalry.stealMargin(Persona.FRIENDLY, 15));
        assertEquals(90 + 2 * Rivalry.TRIAL_WEIGHT - 3 + 1, Rivalry.score(90, 2, 30, 1));
        assertEquals(Rivalry.Standing.AHEAD, Rivalry.Standing.of(90, 80));
        assertEquals(Rivalry.Standing.EVEN, Rivalry.Standing.of(81, 80));
    }

    @Test void aSecondSuitorCanStealTheVillagerBeforeTheEngagement() throws Exception {
        ConversationStore store = world(new MobTalkConfig());
        hearts(store, dima, 75); hearts(store, petya, 75);
        flirt(store, dima, "Дима"); flirt(store, petya, "Петя");
        assertTrue(store.rivalry().drain().size() >= 2, "оба узнали о сопернике");
        GestureIntent date = offer(store, dima, "minecraft:poppy", Gesture.DATE);
        assertEquals(Gesture.Outcome.ACCEPTED, date.outcome(), date.note());
        store.social().applyGesture(villager, dima, "Дима", date, System.currentTimeMillis());
        assertEquals(Courtship.DATING, courtship(store, dima));

        // Пете мало быть наравне: нужно на 15 сердечек больше, чем у пары.
        GestureIntent early = offer(store, petya, "minecraft:poppy", Gesture.DATE);
        assertEquals(Gesture.Outcome.REFUSED, early.outcome());
        assertTrue(early.note().contains("Дима"), early.note());

        hearts(store, petya, 100);
        String prompt = String.join("\n", store.profile(villager(petya, "minecraft:air")).socialTurn.facts());
        assertTrue(prompt.contains("уйдёшь к нему от Дима"), prompt);
        GestureIntent steal = offer(store, petya, "minecraft:poppy", Gesture.DATE);
        assertEquals(Gesture.Outcome.ACCEPTED, steal.outcome(), steal.note());
        store.social().applyGesture(villager, petya, "Петя", steal, System.currentTimeMillis());
        assertEquals(Courtship.DATING, courtship(store, petya));
        assertEquals(Courtship.NONE, courtship(store, dima), "Дима больше не пара");
        assertTrue(store.rivalry().drain().stream().anyMatch(n -> n.player().equals(dima) && n.text().contains("ушла к другому: Петя")));
        String told = String.join("\n", store.profile(villager(dima, "minecraft:air")).socialTurn.facts());
        assertTrue(told.contains("ты ушла от этого игрока к Петя"), told);
    }

    @Test void withoutRivalsTheFirstBouquetLocksTheHeart() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.rivals = false;
        ConversationStore store = world(config);
        hearts(store, dima, 75); hearts(store, petya, 100);
        store.social().applyGesture(villager, dima, "Дима", offer(store, dima, "minecraft:poppy", Gesture.DATE), System.currentTimeMillis());
        GestureIntent late = offer(store, petya, "minecraft:poppy", Gesture.DATE);
        assertEquals(Gesture.Outcome.REFUSED, late.outcome());
        assertTrue(late.note().contains("сердце уже занято"), late.note());
    }

    @Test void aRingBeforeAStrongerRivalIsRefusedWithAPenaltyAndAPause() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.rivalBellScene = false;
        ConversationStore store = world(config);
        hearts(store, dima, 95); hearts(store, petya, 90);
        flirt(store, dima, "Дима"); flirt(store, petya, "Петя");
        GestureIntent ring = offer(store, petya, "minecraft:gold_ingot", Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.REFUSED, ring.outcome());
        assertTrue(ring.note().contains("Дима"), ring.note());
        assertEquals(-config.rivalLoserPenalty, ring.hearts());
        store.social().applyGesture(villager, petya, "Петя", ring, System.currentTimeMillis());
        assertEquals(90 - config.rivalLoserPenalty, store.relationship(villager, petya));
        GestureIntent again = offer(store, petya, "minecraft:poppy", Gesture.DATE);
        assertEquals(Gesture.Outcome.REFUSED, again.outcome());
        assertTrue(again.note().contains("недавно выбрала другого"), again.note());
        // Дима теперь без сильного соперника: кольцо принимается, помолвка закрывает сердце.
        GestureIntent yes = offer(store, dima, "minecraft:gold_ingot", Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.ACCEPTED, yes.outcome(), yes.note());
        store.social().applyGesture(villager, dima, "Дима", yes, System.currentTimeMillis());
        assertEquals(Courtship.ENGAGED, courtship(store, dima));
        hearts(store, petya, 100);
        store.state.profiles.get(villager.toString()).rivalry.suitor(petya).pausedUntil = -1;
        GestureIntent closed = offer(store, petya, "minecraft:poppy", Gesture.DATE);
        assertEquals(Gesture.Outcome.REFUSED, closed.outcome());
        assertTrue(closed.note().contains("сердце занято") && closed.note().contains("Дима"), closed.note());
    }

    @Test void theWinnersRingCostsTheStrongLoserAndATieWaits() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.rivalBellScene = false;
        ConversationStore store = world(config);
        hearts(store, dima, 90); hearts(store, petya, 89);
        flirt(store, dima, "Дима"); flirt(store, petya, "Петя");
        GestureIntent tie = offer(store, dima, "minecraft:gold_ingot", Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.REFUSED, tie.outcome());
        assertEquals(0, tie.hearts(), "ничья без штрафа");
        assertTrue(tie.note().contains("не можешь выбрать"), tie.note());

        hearts(store, dima, 96);
        GestureIntent ring = offer(store, dima, "minecraft:gold_ingot", Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.ACCEPTED, ring.outcome(), ring.note());
        store.rivalry().drain();
        store.social().applyGesture(villager, dima, "Дима", ring, System.currentTimeMillis());
        assertEquals(89 - config.rivalLoserPenalty, store.relationship(villager, petya));
        assertTrue(store.rivalry().drain().stream().anyMatch(n -> n.player().equals(petya) && n.text().contains("выбрала другого: Дима")));
        assertTrue(store.state.profiles.get(villager.toString()).rivalry.paused(petya, System.currentTimeMillis()));
    }

    @Test void theVillagerComparesItsSuitorsInThePrompt() throws Exception {
        ConversationStore store = world(new MobTalkConfig());
        hearts(store, dima, 60); hearts(store, petya, 80);
        flirt(store, dima, "Дима");
        store.social().applyGesture(villager, petya, "Петя", new GestureIntent(Gesture.KISS, Gesture.Outcome.SUCCESS, 0, ""), System.currentTimeMillis());
        String facts = String.join("\n", store.profile(villager(dima, "minecraft:air")).socialTurn.facts());
        assertTrue(facts.contains("Петя тебе пока милее"), facts);
        assertTrue(facts.contains("Петя недавно поцеловал тебя"), facts);
        RivalryLedger.Rival rival = store.rivalry().rival(villager, dima, System.currentTimeMillis());
        assertEquals("Петя", rival.name());
        assertEquals(Rivalry.Standing.AHEAD, rival.standing());
        // Ухажёр ниже дружбы — не ухажёр.
        hearts(store, dima, 20);
        assertNull(store.rivalry().rival(villager, petya, System.currentTimeMillis()));
    }

    @Test void aRingBeforeAStrongRivalCallsEveryoneToTheBell() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.rivalTrials = false;
        ConversationStore store = world(config);
        hearts(store, dima, 98); hearts(store, petya, 90);
        flirt(store, dima, "Дима"); flirt(store, petya, "Петя");
        store.rivalry().drain();
        GestureIntent ring = offer(store, petya, "minecraft:gold_ingot", Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.REFUSED, ring.outcome());
        assertEquals(0, ring.hearts(), "ответ у колокола, не штраф");
        assertTrue(ring.note().startsWith(RivalryLedger.BELL_NOTE), ring.note());
        long now = System.currentTimeMillis();
        store.social().applyGesture(villager, petya, "Петя", ring, now);
        List<RivalryLedger.Scene> scenes = store.rivalry().scenes(now);
        assertEquals(1, scenes.size());
        assertEquals(petya, scenes.getFirst().proposer());
        assertTrue(store.rivalry().drain().stream().anyMatch(n -> n.player().equals(dima) && n.text().contains("у колокола")));
        GestureIntent again = offer(store, dima, "minecraft:gold_ingot", Gesture.PROPOSE);
        assertTrue(again.note().contains("ответишь там"), again.note());

        RivalryLedger.SceneResult result = store.rivalry().resolveScene(villager, now);
        assertEquals(dima, result.winner());
        assertEquals(List.of(petya), result.losers());
        assertEquals(Courtship.DATING, courtship(store, dima));
        assertEquals(90 - config.rivalLoserPenalty, store.relationship(villager, petya));
        assertTrue(store.rivalry().scenes(now).isEmpty());
        // Петя на паузе — теперь кольцо Димы принимается сразу.
        GestureIntent yes = offer(store, dima, "minecraft:gold_ingot", Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.ACCEPTED, yes.outcome(), yes.note());
    }

    @Test void aTrialIsWonByTheFirstWhoBringsEverything() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        ConversationStore store = world(config);
        hearts(store, dima, 60); hearts(store, petya, 60);
        flirt(store, dima, "Дима"); flirt(store, petya, "Петя");
        long now = System.currentTimeMillis();
        List<RivalryLedger.TrialView> trials = store.rivalry().trials(now);
        assertEquals(1, trials.size(), "двое ухажёров — испытание");
        RivalryLedger.TrialView trial = trials.getFirst();
        assertEquals(2, trial.entries().size());
        assertTrue(store.rivalry().drain().stream().anyMatch(n -> n.item().equals(trial.item()) && n.text().contains(RivalryLedger.ITEM)));
        String facts = String.join("\n", store.profile(villager(dima, "minecraft:air")).socialTurn.facts());
        assertTrue(facts.contains("испытание"), facts);
        assertNull(store.rivalry().deliver(villager, dima, "Дима", "minecraft:stick_of_nothing", 5, now), "чужой предмет — обычный подарок");
        if (trial.count() > 1) {
            RivalryLedger.Delivery part = store.rivalry().deliver(villager, petya, "Петя", trial.item(), 1, now);
            assertEquals(1, part.taken()); assertFalse(part.won());
        }
        RivalryLedger.Delivery all = store.rivalry().deliver(villager, dima, "Дима", trial.item(), 64, now);
        assertEquals(trial.count(), all.taken(), "берёт сколько не хватает");
        assertTrue(all.won());
        assertEquals(60 + config.rivalTrialWin, store.relationship(villager, dima));
        assertEquals(60 + (trial.count() > 1 ? config.rivalTrialTry : 0), store.relationship(villager, petya));
        assertTrue(store.rivalry().trials(now).isEmpty());
        assertNull(store.rivalry().deliver(villager, petya, "Петя", trial.item(), 1, now));
        assertTrue(store.rivalry().drain().stream().anyMatch(n -> n.player().equals(petya) && n.text().contains("испытание выиграл Дима")));
        // Следующее — не раньше игровых суток.
        flirt(store, petya, "Петя");
        assertTrue(store.rivalry().trials(now).isEmpty());
    }

    @Test void anExpiredTrialGoesToWhoBroughtMore() throws Exception {
        ConversationStore store = world(new MobTalkConfig());
        hearts(store, dima, 60); hearts(store, petya, 60);
        flirt(store, dima, "Дима"); flirt(store, petya, "Петя");
        long now = System.currentTimeMillis();
        RivalryLedger.TrialView trial = store.rivalry().trials(now).getFirst();
        if (trial.count() > 1) store.rivalry().deliver(villager, petya, "Петя", trial.item(), 1, now);
        store.rivalry().expire(trial.endsAt());
        assertTrue(store.rivalry().trials(now).isEmpty());
        if (trial.count() > 1) assertTrue(store.relationship(villager, petya) > 60);
        assertEquals(60, store.relationship(villager, dima));
    }
}
