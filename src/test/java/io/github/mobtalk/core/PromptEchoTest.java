package io.github.mobtalk.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromptEchoTest {
    @Test void findsCopiesQuotesMotifsAndOpenings() {
        String prompt = "Ты пекарь в Ольховке и печёшь хлеб на всю деревню. Отвечаешь вяло: «не знаю», «какая разница».";
        assertEquals(List.of("печешь хлеб на всю деревню"), PromptEcho.copied("Ну, я печёшь хлеб на всю деревню, вот.", prompt, "Привет", 4));
        assertTrue(PromptEcho.copied("Печёшь хлеб на всю деревню?", prompt, "Ты печёшь хлеб на всю деревню?", 4).isEmpty(), "the player's own words are not a copy");
        assertEquals(List.of("какая разница"), PromptEcho.quoted("Да какая разница, Стив.", prompt));
        List<String> replies = List.of("Хлеб остыл.", "Пеку хлеба много.", "Хлебушек бери.", "Пока.");
        assertFalse(PromptEcho.motifs(replies, prompt, "Привет", 75).isEmpty());
        assertTrue(PromptEcho.motifs(replies, prompt, "Дай хлеба", 75).isEmpty(), "a word the player brought up is not a motif");
        assertEquals("ну что", PromptEcho.sameOpening(List.of("Ну что, бери.", "Ну что ж.", "Ну, что ещё?")));
        assertEquals("", PromptEcho.sameOpening(List.of("Да.", "Нет.", "Может.")));
    }
}
