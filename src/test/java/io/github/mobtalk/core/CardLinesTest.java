package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import io.github.mobtalk.core.cards.DealerStyle;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Слова жителя за столом игры в 21: заготовки на каждое событие и стиль, разбор ответа модели и поле game в схеме ответа. */
class CardLinesTest {
    // Every event has a stand-in for every style, short and spoken.
    @Test void everyEventHasStandInLines() {
        for (CardLines.Event event : CardLines.Event.values())
            for (DealerStyle style : DealerStyle.values()) {
                CardLines.Line line = CardLines.fallback(event, style, 3);
                assertFalse(line.text().isBlank(), event + " " + style);
                assertTrue(line.text().length() <= CardLines.MAX_CHARS);
            }
        assertNotEquals(CardLines.fallback(CardLines.Event.START, DealerStyle.RECKLESS, 0).text(), CardLines.fallback(CardLines.Event.START, DealerStyle.RECKLESS, 1).text());
    }

    // The model's answer is cut to 120 characters, stripped of quotes; an unknown emotion is calm; an empty line is refused.
    @Test void parsesTheModelLine() throws IOException {
        CardLines.Line line = CardLines.parse("{\"line\":\"«Ну ты и везунчик!»\",\"emotion\":\"surprised\"}");
        assertEquals("Ну ты и везунчик!", line.text());
        assertEquals(CardLines.Emotion.SURPRISED, line.emotion());
        assertEquals(CardLines.Emotion.CALM, CardLines.parse("{\"line\":\"Ладно.\",\"emotion\":\"BORED\"}").emotion());
        assertThrows(IOException.class, () -> CardLines.parse("{\"line\":\"...\",\"emotion\":\"CALM\"}"));
        assertEquals(CardLines.MAX_CHARS, CardLines.parse("{\"line\":\"" + "а".repeat(300) + "\",\"emotion\":\"CALM\"}").text().length());
    }

    // The game field is offered only to a grown villager who is not a priest, and only when the server has the game on.
    @Test void gameFieldOnlyForVillagersWhoPlay() {
        Map<String, Object> on = Map.of("cardsEnabled", true);
        assertTrue(ReplySchema.offersCards(snapshot("minecraft:villager", false, "minecraft:farmer", on)));
        assertFalse(ReplySchema.offersCards(snapshot("minecraft:villager", true, "minecraft:none", on)), "children do not play");
        assertFalse(ReplySchema.offersCards(snapshot("minecraft:villager", false, "minecraft:cleric", on)), "the priest never plays");
        assertFalse(ReplySchema.offersCards(snapshot("minecraft:villager", false, "minecraft:farmer", Map.of("cardsEnabled", false))));
        assertFalse(ReplySchema.offersCards(snapshot("minecraft:cow", false, "", on)));
        JsonObject format = ReplySchema.replyFormat(snapshot("minecraft:villager", false, "minecraft:farmer", on));
        ReplySchema.addGame(format, snapshot("minecraft:villager", false, "minecraft:farmer", on));
        JsonObject schema = format.getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertTrue(schema.getAsJsonObject("properties").has("game"));
        assertTrue(schema.getAsJsonArray("required").toString().contains("game"));
    }

    private static MobSnapshot snapshot(String type, boolean baby, String profession, Map<String, Object> cardGame) {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put("baby", baby);
        details.put("profession", profession);
        details.put("cardGame", cardGame);
        return new MobSnapshot(java.util.UUID.randomUUID(), type, "Житель", 20, 20, "minecraft:overworld", "plains", "day", "clear", 2, "minecraft:air",
                java.util.List.of(), java.util.List.of(), java.util.UUID.randomUUID(), Map.of(), details, Map.of(), java.util.List.of(), java.util.List.of(ActionType.NONE), 0);
    }
}