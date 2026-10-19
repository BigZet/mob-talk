package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MobStateTest {
    private static final long MINUTE = 60_000L;
    /** A middling temperament and a neutral relationship: mood 50, calm 52, interest 45, trust 45. */
    private static final MobState.Baseline REST = MobState.Baseline.of(50, 50, 50, 50, 0);

    // Nothing happened: every axis sits at its rest point, and the rest point follows the temperament and the relationship.
    @Test void restsAtTheBaselineUntilSomethingHappens() {
        MobState state = new MobState();
        MobState.View view = state.view(REST, 123_456);
        assertEquals(REST.mood(), view.mood()); assertEquals(REST.calm(), view.calm());
        assertEquals(REST.interest(), view.interest()); assertEquals(REST.trust(), view.trust());
        assertEquals(MobState.Baseline.of(50, 50, 50, 50, 0), REST);
        MobState.Baseline nervous = MobState.Baseline.of(0, 0, 0, 0, -80);
        assertTrue(nervous.calm() > MobState.WARY_CALM, "no creature is born unable to trade: " + nervous);
        assertTrue(nervous.trust() < 20 && MobState.Baseline.of(50, 50, 50, 50, 60).trust() > 60, "trust rests where the relationship is");
        assertTrue(state.view(nervous, 0).refusesDeals(), "a hostile stranger trusts nobody; the game closes its trades at that score anyway");
        MobState.Baseline hated = MobState.Baseline.of(0, 0, 0, 0, CombatRules.HATRED);
        assertFalse(state.view(hated, 0).refusesDeals() || state.view(REST, 0).keen() || state.view(REST, 0).generous(), "at rest the state alone never blocks a trade the score allows");
    }

    // A blow is forgotten slowly, a chat quickly: half of each deviation per half-life, back to rest within a point eventually.
    @Test void decaysExponentiallyTowardsTheBaseline() {
        MobState state = new MobState();
        state.shift(MobState.Event.HIT, REST, 0);
        int shaken = state.view(REST, 0).calm();
        assertEquals(REST.calm() - 35, shaken);
        assertTrue(state.view(REST, 0).refusesDeals());
        int afterHalf = state.view(REST, MobState.SLOW_HALF_LIFE).calm();
        assertEquals(REST.calm() - 18, afterHalf, 1, "half of the deviation after one slow half-life");
        assertEquals(REST.calm() - 35, state.view(REST, 0).calm(), "reading never moves anything");
        assertEquals(REST.calm(), state.view(REST, 24 * 60 * MINUTE).calm(), "gone after a day");
        assertEquals(REST.trust(), state.view(REST, 24 * 60 * MINUTE).trust());
        MobState chat = new MobState();
        chat.shift(MobState.Event.TALK, REST, 0);
        assertEquals(REST.interest() + 12, chat.view(REST, 0).interest());
        assertEquals(REST.interest() + 6, chat.view(REST, MobState.HALF_LIFE).interest());
        // Time running backwards (a clock change) counts as no time at all.
        assertEquals(REST.interest() + 12, chat.view(REST, -MINUTE).interest());
        // Once settled within a point of rest, the axis forgets its slow half-life and follows a moving baseline again.
        state.settle(REST, 24 * 60 * MINUTE);
        assertEquals(MobState.HALF_LIFE, state.calm.halfLife); assertTrue(state.calm.value < 0);
        assertEquals(70, state.view(MobState.Baseline.of(50, 50, 50, 50, 70), 24 * 60 * MINUTE).trust());
    }

    // Three friendly chats make a mob keen; a favour kept makes it trusting; a kill seen makes it wary for a long time.
    @Test void eventsMoveTheAxes() {
        MobState state = new MobState();
        for (int i = 0; i < 3; i++) { state.shift(MobState.Event.TALK, REST, i * MINUTE); state.shift(MobState.Event.KIND, REST, i * MINUTE); }
        MobState.View chatty = state.view(REST, 2 * MINUTE);
        assertTrue(chatty.keen(), "interest after three chats: " + chatty.interest());
        assertTrue(chatty.mood() > REST.mood() && chatty.trust() > REST.trust());
        state.shift(MobState.Event.FAVOR_DONE, REST, 3 * MINUTE);
        state.shift(MobState.Event.PURCHASE, REST, 3 * MINUTE);
        assertTrue(state.view(REST, 3 * MINUTE).generous(), "cheerful and trusting after a kept promise: " + state.view(REST, 3 * MINUTE));
        assertEquals(MobState.SLOW_HALF_LIFE, state.trust.halfLife);
        MobState witness = new MobState();
        witness.shift(MobState.Event.WITNESSED_KILL, REST, 0);
        MobState.View grim = witness.view(REST, 0);
        assertTrue(grim.refusesDeals() && grim.mood() < 40 && grim.calm() < 25 && grim.trust() < 25, grim.toString());
        assertTrue(witness.view(REST, 10 * MINUTE).refusesDeals(), "ten minutes later it still refuses");
        assertFalse(witness.view(REST, 30 * MINUTE).refusesDeals(), "half an hour later it trades again, shaken");
        assertEquals(-1, witness.view(REST, 30 * MINUTE).marker().sign(), witness.view(REST, 30 * MINUTE).marker().toString());
        assertEquals("calm", witness.view(REST, 3 * 60 * MINUTE).marker().key(), "three hours later nothing is left to see");
        // Reassurance does not cushion a blow: the hit lands its full weight below rest whatever came before.
        MobState reassured = new MobState();
        for (int i = 0; i < 4; i++) reassured.shift(MobState.Event.KIND, REST, 0);
        reassured.safe(REST, 0);
        assertEquals(REST.calm() + 20, reassured.view(REST, 0).calm(), "safety never lifts calm above its rest, and kind words already did");
        MobState shaken = new MobState();
        shaken.shift(MobState.Event.INSULT, REST, 0);
        shaken.safe(REST, 0);
        assertEquals(REST.calm(), shaken.view(REST, 0).calm(), "safety brings calm back up to rest, not past it");
        reassured.shift(MobState.Event.HIT, REST, 0);
        assertEquals(REST.calm() - 35, reassured.view(REST, 0).calm());
        assertEquals(REST.trust() - 35, reassured.view(REST, 0).trust(), "nor does earned trust survive it");
        assertTrue(List.of("panicked", "distrustful").contains(reassured.view(REST, 0).marker().key()), reassured.view(REST, 0).marker().toString());
        MobState insulted = new MobState();
        insulted.shift(MobState.Event.INSULT, REST, 0); insulted.shift(MobState.Event.THREAT, REST, 0);
        assertTrue(insulted.view(REST, 0).calm() < REST.calm() - 35 && insulted.view(REST, 0).trust() < REST.trust() - 30);
        insulted.shift(MobState.Event.APOLOGY, REST, 0);
        assertTrue(insulted.view(REST, 0).trust() > REST.trust() - 30);
        MobState calmed = new MobState();
        calmed.shift(MobState.Event.HIT, REST, 0);
        assertTrue(calmed.safe(REST, MINUTE) && !calmed.safe(REST, 2 * MINUTE), "safety counts once per cooldown");
        assertEquals(REST.calm() - 35 + 15, calmed.view(REST, MINUTE).calm(), 1);
    }

    // Numbers become words by thresholds; the HUD gets the one that stands out most, calm when nothing does.
    @Test void translatesAxesIntoWords() {
        MobState.View anxious = new MobState.View(50, 30, 45, 45, "");
        assertEquals("anxious", anxious.marker().key()); assertEquals("встревожен", anxious.marker().word()); assertEquals(-1, anxious.marker().sign());
        MobState.View panicking = new MobState.View(50, 10, 85, 15, "");
        assertEquals("panicked", panicking.marker().key(), "calm is further from the middle than trust");
        assertEquals(List.of("panicked", "even", "distrustful", "fascinated"), panicking.words().stream().map(MobState.Descriptor::key).toList());
        MobState.View cheerful = new MobState.View(72, 55, 50, 70, "");
        assertEquals("cheerful", cheerful.marker().key()); assertEquals(1, cheerful.marker().sign());
        assertEquals(List.of("calm", "cheerful", "trusting"), cheerful.words().stream().map(MobState.Descriptor::key).toList());
        MobState.View neutral = new MobState.View(50, 50, 50, 50, "");
        assertEquals("calm", neutral.marker().key()); assertEquals(0, neutral.marker().sign()); assertEquals("спокоен", neutral.marker().word());
        assertEquals(List.of("calm", "even"), neutral.words().stream().map(MobState.Descriptor::key).toList(), "interest and trust are silent in the middle");
        assertEquals("bored", new MobState.View(50, 50, 5, 50, "").marker().key());
        assertEquals("devoted", new MobState.View(50, 50, 50, 90, "").marker().key());
        assertEquals("gloomy", new MobState.View(10, 50, 50, 50, "").marker().key());
        assertEquals("serene", new MobState.View(50, 95, 50, 50, "").marker().key());
    }

    // The model reads words, never numbers, plus the thought; a thought is kept clean and never blank.
    @Test void describesTheStateForTheModel() {
        MobState.View view = new MobState.View(85, 30, 80, 15, "Надо бы проверить, не лезет ли он к моему сундуку");
        String text = view.describe();
        assertTrue(text.startsWith("Твоё состояние сейчас:"), text);
        assertTrue(text.contains("душа поёт") && text.contains("встревожен") && text.contains("по-настоящему увлёк") && text.contains("ни слову"), text);
        assertTrue(text.contains("«Надо бы проверить, не лезет ли он к моему сундуку»"), text);
        assertFalse(text.matches("(?s).*\\d.*"), "no numbers: " + text);
        assertFalse(new MobState.View(50, 50, 50, 50, "").describe().contains("мысль"));
        assertEquals("Хочу тишины", MobState.cleanThought("  «Хочу тишины.»".replace(".", "") + "\""));
        assertEquals("", MobState.cleanThought("...")); assertEquals("", MobState.cleanThought(null));
        assertEquals(MobState.THOUGHT_LIMIT, MobState.cleanThought("а".repeat(500)).length());
        MobState state = new MobState();
        state.think("Первая мысль"); state.think(""); state.think("—");
        assertEquals("Первая мысль", state.view(REST, 0).thought());
        assertEquals("", PromptTexts.stateTrade(new MobState.View(50, 50, 50, 50, "")));
        assertTrue(PromptTexts.stateTrade(new MobState.View(50, 10, 50, 50, "")).contains("никаких OFFER"));
        assertTrue(PromptTexts.stateTrade(new MobState.View(80, 50, 50, 80, "")).contains("щедрее"));
    }

    private static MobSnapshot villager(UUID player) {
        Map<String,Object> bargaining = Map.ofEntries(Map.entry("open", true), Map.entry("selectedIndex", 1), Map.entry("selectedOffer", 1),
                Map.entry("fingerprint", "fixture"), Map.entry("baseCount", 32), Map.entry("vanillaCount", 32), Map.entry("currentPrice", 32),
                Map.entry("maximumDiscount", 3), Map.entry("allowedPrices", List.of(31, 30, 29)), Map.entry("available", true),
                Map.entry("firstBuy", Map.of("id", "minecraft:stick", "name", "Палка", "count", 32)), Map.entry("secondBuy", Map.of()),
                Map.entry("sell", Map.of("id", "minecraft:emerald", "name", "Изумруд", "count", 1)), Map.entry("serverTick", 1000),
                Map.entry("reason", ""), Map.entry("state", Map.of("status", "NONE")));
        List<Map<String,Object>> offers = List.of(Map.of("buy", Map.of("name", "Палка", "count", 32), "secondBuy", Map.of("count", 0), "sell", Map.of("name", "Изумруд", "count", 1)));
        return new MobSnapshot(UUID.randomUUID(), "minecraft:villager", "Торговец", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                "minecraft:stick", List.of(), List.of(), player, Map.of("name", "Стив"), Map.of("bargaining", bargaining, "offers", offers), Map.of(), List.of(),
                List.of(ActionType.NONE), 0);
    }
    private static byte[] completion(String reply, String bargainType, int price, String thought) {
        JsonObject answer = new JsonObject();
        answer.addProperty("assessment", "торг"); answer.addProperty("reply", reply); answer.addProperty("socialIntent", "NONE"); answer.addProperty("newFacts", "");
        answer.add("action", JsonParser.parseString("{\"type\":\"NONE\",\"target\":\"\"}"));
        JsonObject bargain = new JsonObject(); bargain.addProperty("type", bargainType); bargain.addProperty("offerIndex", 1); bargain.addProperty("price", price);
        answer.add("bargain", bargain);
        if (thought != null) answer.addProperty("thought", thought);
        JsonObject message = new JsonObject(); message.addProperty("content", answer.toString());
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonObject response = new JsonObject(); com.google.gson.JsonArray choices = new com.google.gson.JsonArray(); choices.add(choice); response.add("choices", choices);
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }

    // The thought comes through cleaned and may be absent; an anxious mob's concession is rejected so it refuses in words instead.
    @Test void parsesTheThoughtAndRefusesConcessionsWhileWary() throws Exception {
        MobSnapshot snapshot = villager(UUID.randomUUID());
        AnswerValidator.Bounds calm = new AnswerValidator.Bounds(45, "", 4000, 0, false, "test", false, false);
        var answer = AnswerValidator.parse(completion("Ладно, тридцать.", "OFFER", 30, " «Пусть берёт, лишь бы отстал» "), snapshot, calm);
        assertEquals(BargainIntent.Type.OFFER, answer.bargain().type());
        assertEquals("Пусть берёт, лишь бы отстал", answer.thought());
        assertEquals("", AnswerValidator.parse(completion("Нет.", "DECLINE", 0, null), snapshot, calm).thought());
        AnswerValidator.Bounds wary = new AnswerValidator.Bounds(45, "", 4000, 0, false, "test", false, true);
        AnswerValidator.Rejected rejected = assertThrows(AnswerValidator.Rejected.class,
                () -> AnswerValidator.parse(completion("Ладно, тридцать.", "OFFER", 30, "ой"), snapshot, wary));
        assertEquals(PromptTexts.WARY_TRADE, rejected.getMessage());
        assertTrue(rejected.trade && !rejected.own, "a bargain correction, not an own-deal one");
        assertEquals(BargainIntent.Type.DECLINE, AnswerValidator.parse(completion("Не сегодня.", "DECLINE", 0, null), snapshot, wary).bargain().type());
        // The strict schema itself drops OFFER for a wary mob and carries the thought field for everyone.
        JsonObject open = ReplySchema.replyFormat(snapshot, false, false).getAsJsonObject("json_schema").getAsJsonObject("schema").getAsJsonObject("properties");
        JsonObject narrowed = ReplySchema.replyFormat(snapshot, false, true).getAsJsonObject("json_schema").getAsJsonObject("schema").getAsJsonObject("properties");
        assertTrue(open.getAsJsonObject("bargain").getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum").toString().contains("OFFER"));
        assertFalse(narrowed.getAsJsonObject("bargain").getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum").toString().contains("OFFER"));
        assertFalse(narrowed.getAsJsonObject("deal").getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum").toString().contains("OFFER"));
        assertTrue(open.has("thought") && narrowed.has("thought"));
    }

    // The system prompt carries the state block, the rule not to voice it, and the trading consequence; Situation panics from the current calm.
    @Test void promptCarriesTheStateInWords() {
        MobTalkConfig config = new MobTalkConfig();
        try (OpenRouterConversationClient client = new OpenRouterConversationClient("unit-test-key", config, URI.create("http://127.0.0.1:1/chat"))) {
            ConversationStore.Profile profile = new ConversationStore.Profile();
            profile.type = "minecraft:villager"; profile.name = "Житель"; profile.personality = ""; profile.voiceId = "x"; profile.variantSlot = 0;
            profile.calm = 90; profile.confidence = 50; profile.curiosity = 50; profile.sociability = 50; profile.preference = "тишина"; profile.fear = "зомби";
            profile.customPersonality = ""; profile.relationship = 0; profile.hits = 1; profile.recentEvents = List.of("Игрок Стив ударил моба; отношение -80");
            profile.stateView = new MobState.View(30, 12, 70, 20, "Он опять с мечом");
            MobSnapshot snapshot = villager(UUID.randomUUID());
            String system = client.systemPrompt(SpeciesCatalog.get("minecraft:villager"), profile, snapshot);
            assertTrue(system.contains("Твоё состояние сейчас: настроение — не в духе; нервы — паника"), system);
            assertTrue(system.contains("«Он опять с мечом»") && system.contains(PromptTexts.STATE_RULE), system);
            assertTrue(system.contains("не в том состоянии, чтобы уступать"), "a wary villager with offers hears it: " + system);
            assertTrue(system.contains("паникуешь"), "Situation reads the current calm, not the serene temperament: " + system);
            profile.stateView = null;
            String rested = client.systemPrompt(SpeciesCatalog.get("minecraft:villager"), profile, snapshot);
            assertTrue(rested.contains("нервы — невозмутим") && rested.contains("держишься ровно"), "no view: computed from the temperament: " + rested);
            assertFalse(rested.contains("не в том состоянии"));
        }
    }

    // A real world file: a blow shakes the mob, the thought is remembered across a reload, the HUD card carries the view.
    @TempDir Path directory;
    @Test void storeShiftsAndPersistsTheState() throws Exception {
        TradeFixtures.install();
        try {
            UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
            ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
            MobSnapshot snapshot = new MobSnapshot(mob, "minecraft:cow", "Корова", 10, 10, "minecraft:overworld", "minecraft:plains", "day", "clear", 2,
                    "minecraft:stick", List.of(), List.of(), player, Map.of("name", "Игрок"), Map.of("lastAttacker", "none"), Map.of(), List.of(), List.of(ActionType.NONE), 0);
            ConversationStore.Profile first = store.profile(snapshot);
            assertNotNull(first.stateView); assertFalse(first.stateView.refusesDeals());
            int restingInterest = first.stateView.interest();
            // The store reads the state against the real clock, so the events carry real timestamps too.
            long now = System.currentTimeMillis();
            assertTrue(store.commit(snapshot, "Привет", "Му", "", SocialIntent.KIND, ActionIntent.none(), now, null, "Этот вроде безобидный"));
            MobState.View talked = store.stateView(mob, player);
            assertTrue(talked.interest() > restingInterest && talked.trust() > first.stateView.trust(), talked.toString());
            assertEquals("Этот вроде безобидный", talked.thought());
            store.recordDamage(mob, "minecraft:cow", "Корова", player, "Игрок", now + 1000);
            MobState.View hit = store.stateView(mob, player);
            assertTrue(hit.refusesDeals() && hit.calm() <= 40 && hit.trust() < 10, "a blow: at least 35 below any rest calm, trust gone: " + hit);
            assertEquals(-1, store.card(mob, player).state().marker().sign(), "the HUD card carries the view");
            assertNull(store.stateView(UUID.randomUUID(), player), "a stranger has no state");
            store.recordWitnessed(mob, player, "Игрок", "убил Марфу (корова)", -25, now + 10_000);
            assertTrue(store.stateView(mob, player).mood() < hit.mood());
            ConversationStore.closeWorld(directory);
            ConversationStore reopened = ConversationStore.open(directory, new MobTalkConfig());
            MobState.View loaded = reopened.stateView(mob, player);
            assertEquals("Этот вроде безобидный", loaded.thought());
            assertTrue(loaded.refusesDeals(), "the state survives a reload: " + loaded);
            ConversationStore.closeWorld(directory);
        } finally { TradeFixtures.reset(); }
    }
}
