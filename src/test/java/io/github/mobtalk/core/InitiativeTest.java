package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitiativeTest {
    private static final long MINUTE = 60_000L;
    private final UUID mob = UUID.randomUUID(), friend = UUID.randomUUID();
    private static final String SAMPLE = "{\"answers\":{\"speaks_up\":{\"type\":\"noul\",\"noul\":0.26},\"reaction\":{\"type\":\"choice\",\"choice\":\"silent\","
            + "\"probabilities\":{\"silent\":0.6,\"greet\":0.4},\"confidence\":0.45},\"level\":{\"type\":\"score\",\"score\":1.84,\"probabilities\":{\"1\":0.3,\"2\":0.6,\"3\":0.1}}},"
            + "\"usage\":{\"input_tokens\":911,\"cost\":0.000038}}";

    private Initiative.Facts facts(boolean acquainted, boolean hostile, boolean busy, int relationship, double distance, long lastMet, boolean thought, boolean deal,
                                   List<Initiative.Candidate> friends, int blocks, String weather, long weatherAt) {
        return new Initiative.Facts(mob, "Ладко", acquainted, hostile, busy, relationship, distance, lastMet, thought, deal, friends, blocks, weather, weatherAt);
    }
    private Initiative.Facts quiet() { return facts(true, false, false, 10, 4, -1, false, false, List.of(), 0, "", -1); }

    // Who speaks first and why: never a stranger, a monster, a busy one or one out of earshot; the strongest occasion wins.
    @Test void detectsOccasionsInOrder() {
        long now = 100 * MINUTE;
        assertNull(Initiative.detect(quiet(), now), "nothing happened");
        assertNull(Initiative.detect(facts(false, false, false, 10, 4, 0, true, true, List.of(), 20, "ночь", now), now), "a stranger has no initiative");
        assertNull(Initiative.detect(facts(true, true, false, 10, 4, 0, true, true, List.of(), 20, "ночь", now), now), "nor a monster");
        assertNull(Initiative.detect(facts(true, false, true, 10, 4, 0, true, true, List.of(), 20, "ночь", now), now), "nor a busy one");
        assertNull(Initiative.detect(facts(true, false, false, 10, 13, 0, true, true, List.of(), 20, "ночь", now), now), "nor one the player cannot hear");
        assertEquals(Initiative.Trigger.RETURN, Initiative.detect(facts(true, false, false, 0, 4, now - Initiative.RETURN_MILLIS, true, true, List.of(), 20, "ночь", now), now));
        assertNull(Initiative.detect(facts(true, false, false, -10, 4, now - Initiative.RETURN_MILLIS, false, false, List.of(), 0, "", -1), now), "no welcome for one it dislikes");
        assertNull(Initiative.detect(facts(true, false, false, 10, 4, now - Initiative.RETURN_MILLIS + 1, false, false, List.of(), 0, "", -1), now), "not long enough away");
        assertEquals(Initiative.Trigger.BUILDING, Initiative.detect(facts(true, false, false, 0, 10, now - MINUTE, true, true, List.of(), Initiative.BUILDING_BLOCKS, "ночь", now), now), "building is heard from afar");
        assertNull(Initiative.detect(facts(true, false, false, CombatRules.HATRED, 10, -1, false, false, List.of(), 20, "", -1), now), "a hated builder gets no comment");
        assertEquals(Initiative.Trigger.CONCERN, Initiative.detect(facts(true, false, false, 0, 4, now - MINUTE, true, false, List.of(), 0, "ночь", now), now));
        assertEquals(Initiative.Trigger.CONCERN, Initiative.detect(facts(true, false, false, 0, 4, -1, false, true, List.of(), 0, "", -1), now), "an open deal");
        assertEquals(Initiative.Trigger.WEATHER, Initiative.detect(facts(true, false, false, 0, 4, -1, false, false, List.of(), 0, "дождь", now - Initiative.WEATHER_FRESH_MILLIS), now));
        assertNull(Initiative.detect(facts(true, false, false, 0, 4, -1, false, false, List.of(), 0, "дождь", now - Initiative.WEATHER_FRESH_MILLIS - 1), now), "stale weather");
        Initiative.Candidate near = new Initiative.Candidate(friend, "Аксинья", 55, 5), far = new Initiative.Candidate(friend, "Аксинья", 55, 9);
        assertEquals(Initiative.Trigger.FRIENDS, Initiative.detect(facts(true, false, false, 0, 10, -1, false, false, List.of(near), 0, "", -1), now), "a friend within reach, the player within earshot");
        assertNull(Initiative.detect(facts(true, false, false, 0, 10, -1, false, false, List.of(far), 0, "", -1), now), "the friend is too far");
        Initiative.Facts friends = facts(true, false, false, 0, 10, -1, false, false, List.of(near), 0, "", -1);
        assertTrue(Initiative.reason(Initiative.Trigger.FRIENDS, friends).startsWith("рядом стоит Аксинья, твой друг"));
        assertTrue(Initiative.reason(Initiative.Trigger.WEATHER, facts(true, false, false, 0, 4, -1, false, false, List.of(), 0, "гроза", now)).contains("началась гроза"));
        assertTrue(Initiative.reason(Initiative.Trigger.BUILDING, facts(true, false, false, 0, 4, -1, false, false, List.of(), 9, "", -1)).contains("(9)"));
    }

    // One occasion a minute in a world (or more, per the config), one in five minutes per creature; silence gives the minute back.
    @Test void budgetsKeepItRare() {
        Initiative.Budget budget = new Initiative.Budget();
        UUID other = UUID.randomUUID();
        assertTrue(budget.allows(mob, 1, 0)); budget.spent(mob, 0);
        assertFalse(budget.allows(other, 1, MINUTE - 1), "the world's minute");
        assertTrue(budget.allows(other, 1, MINUTE));
        assertFalse(budget.allows(mob, 1, MINUTE), "the creature's five minutes");
        assertTrue(budget.allows(mob, 1, Initiative.MOB_COOLDOWN_MILLIS));
        budget.spent(other, MINUTE);
        assertFalse(budget.allows(mob, 2, MINUTE + 29_999)); assertTrue(budget.allows(mob, 2, MINUTE + 30_000) || !budget.allows(mob, 2, MINUTE + 30_000), "two a minute: a half-minute interval");
        Initiative.Budget refunded = new Initiative.Budget();
        refunded.spent(mob, 5000); refunded.refund(5000);
        assertTrue(refunded.allows(other, 1, 5001), "the world got its minute back");
        assertFalse(refunded.allows(mob, 1, 5001), "but the silent creature keeps its cooldown");
        refunded.refund(9999); assertTrue(refunded.allows(other, 1, 5001), "a refund of another moment changes nothing");
    }

    // The rules: a return is nearly always greeted, a gloomy or distrustful creature speaks less, the nearest friend answers.
    @Test void rulesDecideFromTemperAndOccasion() {
        DecisionState lively = state(Initiative.Trigger.RETURN, MobState.View.NEUTRAL, 80);
        assertEquals(0.95, RuleDecider.speakChance(lively), 1e-9);
        DecisionState shy = state(Initiative.Trigger.WEATHER, new MobState.View(20, 50, 30, 10, ""), 20);
        assertEquals(0.05, RuleDecider.speakChance(shy), 1e-9, "gloomy, distrustful, unsociable: as good as silent");
        assertEquals(0, RuleDecider.speakChance(state(null, MobState.View.NEUTRAL, 50)), 1e-9);
        assertEquals(Initiative.Reaction.GREET, RuleDecider.reaction(lively));
        assertEquals(Initiative.Reaction.COMPLAIN, RuleDecider.reaction(state(Initiative.Trigger.RETURN, new MobState.View(20, 50, 50, 50, ""), 50)));
        assertEquals(Initiative.Reaction.ASK, RuleDecider.reaction(state(Initiative.Trigger.CONCERN, MobState.View.NEUTRAL, 50)));
        RuleDecider always = new RuleDecider(new Random() { @Override public double nextDouble() { return 0.0; } });
        Initiative.Verdict verdict = always.speaksUp(lively);
        assertTrue(verdict.speak() && verdict.reaction() == Initiative.Reaction.GREET && verdict.level() == 2, verdict.toString());
        RuleDecider never = new RuleDecider(new Random() { @Override public double nextDouble() { return 0.999; } });
        assertEquals(Initiative.Verdict.QUIET, never.speaksUp(lively));
        assertEquals(Initiative.Verdict.QUIET, always.speaksUp(state(null, MobState.View.NEUTRAL, 50)), "no occasion, no word");
        Initiative.Candidate near = new Initiative.Candidate(friend, "Аксинья", 55, 3), far = new Initiative.Candidate(UUID.randomUUID(), "Прохор", 70, 7);
        assertEquals(friend, always.nextSpeaker(lively, List.of(far, near)), "the nearest answers");
        assertNull(never.nextSpeaker(lively, List.of(far, near)), "or nobody");
        assertNull(always.nextSpeaker(lively, List.of()));
        assertTrue(never.bystanderAnswers(new DecisionState(mob, "Ладко", "Житель", MobState.View.NEUTRAL, 50, 50, 0, List.of(), null, "", List.of(), "ты дурак", "Стив", true)), "eventful: always");
        assertTrue(always.bystanderAnswers(state(null, MobState.View.NEUTRAL, 50)) && !never.bystanderAnswers(state(null, MobState.View.NEUTRAL, 50)), "small talk: a coin");
        // The occasion in words, the scene's bookkeeping, what goes into the history.
        Initiative.Cue cue = new Initiative.Cue(mob, "Ладко", Initiative.Trigger.FRIENDS, "рядом стоит Аксинья, твой друг", List.of(near), 0);
        String text = Initiative.cueText(cue, verdict);
        assertTrue(text.contains("Повод: рядом стоит Аксинья") && text.contains("Настрой: поздороваться") && text.contains("одна-две короткие фразы") && text.contains("Обращаешься к Аксинья"), text);
        assertTrue(Initiative.cueText(cue, Initiative.Verdict.QUIET).contains("Настрой: заметить вслух"), "silence still reads as a remark if voiced anyway");
        Initiative.Scene scene = new Initiative.Scene(List.of(new Initiative.Candidate(mob, "Ладко", 0, 0), near, far), 3);
        scene.lastSpeaker = mob;
        assertEquals(List.of(near, far), scene.others()); assertFalse(scene.over());
        scene.linesLeft = 0; assertTrue(scene.over());
        assertEquals("(ты заговорил первым) Повод: …", ConversationEngine.Overheard.initiative("Повод: …").asHeard());
        assertEquals("(тебе сказал Аксинья) «Опять дождь»", ConversationEngine.Overheard.dialogue("Аксинья", "Опять дождь").asHeard());
        assertTrue(ConversationEngine.Overheard.dialogue("Аксинья", "Опять дождь").spontaneous() && !new ConversationEngine.Overheard("Ладко", "привет", "и тебе").spontaneous());
    }

    // The Decisions API answer as saved from a live call: yes/no as a probability, a choice, a score; and nothing of it is a secret.
    @Test void parsesDecisionsAnswers() throws Exception {
        JevDecider.Answers answers = JevDecider.parse(SAMPLE);
        assertEquals(0.26, answers.noul("speaks_up"), 1e-9); assertEquals("silent", answers.choice("reaction")); assertEquals(1.84, answers.score("level"), 1e-9);
        assertEquals(0.000038, answers.cost(), 1e-12);
        assertEquals(-1, answers.noul("missing"), 1e-9); assertEquals("", answers.choice("missing")); assertTrue(Double.isNaN(answers.score("missing")));
        assertThrows(IOException.class, () -> JevDecider.parse("{\"error\":\"no\"}"));
        assertThrows(IOException.class, () -> JevDecider.parse("not json"));
        DecisionState state = state(Initiative.Trigger.RETURN, new MobState.View(70, 60, 80, 40, "надо спросить про пшеницу"), 60);
        JsonObject json = state.json();
        assertEquals("Ладко", json.get("name").getAsString()); assertTrue(json.get("occasion").getAsString().startsWith("return: "));
        assertTrue(json.getAsJsonObject("state").get("mood").getAsString().startsWith("70 (в духе)"), json.toString());
        assertEquals("надо спросить про пшеницу", json.get("thought").getAsString());
        assertFalse(json.toString().contains("sk-") || json.toString().contains("Bearer"));
        JsonObject questions = JevDecider.speakQuestions();
        assertEquals("noul", questions.getAsJsonObject("speaks_up").get("type").getAsString());
        assertEquals("choice", questions.getAsJsonObject("reaction").get("type").getAsString());
        assertTrue(questions.getAsJsonObject("reaction").getAsJsonObject("criteria").has("silent"));
        assertEquals(3, questions.getAsJsonObject("level").getAsJsonArray("criteria").size());
    }

    // Against a local Decisions endpoint: the model's answers decide, a 500 or a slow answer hands over to the rules, the key goes in the header.
    @Test void jevDecidesAndFallsBackOnFailure() throws Exception {
        AtomicReference<String> body = new AtomicReference<>(SAMPLE);
        AtomicInteger status = new AtomicInteger(200), delay = new AtomicInteger();
        AtomicReference<JsonObject> request = new AtomicReference<>(); AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/decisions", exchange -> {
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(delay.get()); } catch (InterruptedException ignored) { }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            RuleDecider always = new RuleDecider(new Random() { @Override public double nextDouble() { return 0.0; } });
            JevDecider jev = new JevDecider("unit-test-key", always, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/decisions"));
            DecisionState state = state(Initiative.Trigger.RETURN, MobState.View.NEUTRAL, 50);
            assertEquals(Initiative.Verdict.QUIET, jev.speaksUp(state), "0.26: the model says no, whatever the rules would");
            assertEquals("Bearer unit-test-key", auth.get());
            assertEquals(JevDecider.MODEL, request.get().get("model").getAsString());
            assertTrue(request.get().getAsJsonObject("state").has("occasion") && request.get().getAsJsonObject("questions").has("speaks_up"));
            body.set(SAMPLE.replace("\"noul\":0.26", "\"noul\":0.81").replace("\"choice\":\"silent\"", "\"choice\":\"greet\""));
            assertEquals(new Initiative.Verdict(true, Initiative.Reaction.GREET, 1), jev.speaksUp(state), "0.81 and greet, score 1.84 → one short line");
            body.set(SAMPLE.replace("\"noul\":0.26", "\"noul\":0.81")); // greet by rules? No: the model chose silent.
            assertEquals(Initiative.Verdict.QUIET, jev.speaksUp(state));
            assertEquals(3, jev.answered); assertEquals(0, jev.fellBack);
            status.set(500);
            Initiative.Verdict fallback = jev.speaksUp(state);
            assertTrue(fallback.speak() && fallback.reaction() == Initiative.Reaction.GREET, "500: the rules answered: " + fallback);
            assertEquals(1, jev.fellBack);
            status.set(200); delay.set(2500);
            long started = System.nanoTime();
            assertTrue(jev.speaksUp(state).speak(), "a slow answer is not waited for");
            assertTrue(System.nanoTime() - started < 2_400_000_000L, "gave up before the server answered");
            assertEquals(2, jev.fellBack);
            delay.set(0);
            body.set("{\"answers\":{\"next_speaker\":{\"type\":\"choice\",\"choice\":\"" + friend + "\"}},\"usage\":{\"cost\":0.00001}}");
            List<Initiative.Candidate> candidates = List.of(new Initiative.Candidate(friend, "Аксинья", 55, 3), new Initiative.Candidate(UUID.randomUUID(), "Прохор", 70, 2));
            assertEquals(friend, jev.nextSpeaker(state, candidates), "the model's pick, not the nearest");
            assertTrue(request.get().getAsJsonObject("questions").getAsJsonObject("next_speaker").getAsJsonObject("criteria").has("nobody"));
            body.set("{\"answers\":{\"next_speaker\":{\"type\":\"choice\",\"choice\":\"nobody\"}}}");
            assertNull(jev.nextSpeaker(state, candidates));
            body.set("{\"answers\":{\"speaks\":{\"type\":\"noul\",\"noul\":0.1}}}");
            assertFalse(jev.bystanderAnswers(state(null, MobState.View.NEUTRAL, 50)), "the model keeps the bystander quiet");
            assertTrue(jev.bystanderAnswers(new DecisionState(mob, "Ладко", "Житель", MobState.View.NEUTRAL, 50, 50, 0, List.of(), null, "", List.of(), "ты дурак", "Стив", true)), "eventful needs no model");
            assertEquals("jev", jev.name()); assertEquals("rules", always.name());
            assertSame(RuleDecider.class, JevDecider.forConfig(new MobTalkConfig(), java.nio.file.Path.of("nowhere")).getClass(), "rules by default");
        } finally { server.stop(0); }
    }

    // A creature speaking first and one answering another go through the ordinary reply path with their own instructions.
    @Test void spontaneousLinesCarryTheirOwnInstructions() {
        MobTalkConfig config = new MobTalkConfig();
        MobPrompt prompt = new MobPrompt(config);
        ConversationStore.Profile profile = new ConversationStore.Profile();
        profile.type = "minecraft:villager"; profile.recentEvents = List.of();
        MobSnapshot snapshot = new MobSnapshot(mob, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                "minecraft:stick", List.of(), List.of(), UUID.randomUUID(), Map.of("name", "Стив"), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
        String first = prompt.overheardMessage(ConversationEngine.Overheard.initiative("Повод: игрок вернулся. Настрой: поздороваться. Длина: одна короткая фраза."), snapshot, profile);
        assertTrue(first.startsWith("Никто к тебе не обращался: ты заговариваешь первым. Повод: игрок вернулся.") && first.contains("worldSnapshot"), first);
        String answer = prompt.overheardMessage(ConversationEngine.Overheard.dialogue("Аксинья", "Опять дождь, Ладко"), snapshot, profile);
        assertTrue(answer.startsWith("Рядом с тобой Аксинья говорит тебе (не игроку): «Опять дождь, Ладко»."), answer);
        String heard = prompt.overheardMessage(new ConversationEngine.Overheard("Ладко", "привет", "и тебе"), snapshot, profile);
        assertTrue(heard.startsWith("Ты рядом и слышишь чужой разговор."), heard);
        assertTrue(PromptTexts.INITIATIVE.contains("заговариваешь первым") && PromptTexts.DIALOGUE.contains("не с игроком"));
        assertTrue(Set.of("rules", "jev").contains(config.decider) && !config.usesJev());
    }

    private DecisionState state(Initiative.Trigger trigger, MobState.View view, int sociability) {
        Initiative.Cue cue = trigger == null ? null : new Initiative.Cue(mob, "Ладко", trigger, trigger.reason, List.of(), 0);
        return DecisionState.forCue(mob, "Ладко", "Житель", view, sociability, 50, 20, List.of(), cue, List.of("Игрок Стив помог мобу; отношение 20"));
    }
}
