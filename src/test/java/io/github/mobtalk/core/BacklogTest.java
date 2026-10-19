package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BacklogTest {
    @Test void httpStatusesSpeakToThePlayer() {
        assertTrue(BoundedHttp.httpMessage("OpenRouter LLM", 402).contains("кредиты"));
        assertTrue(BoundedHttp.httpMessage("OpenRouter LLM", 401).contains("ключ"));
        assertTrue(BoundedHttp.httpMessage("OpenRouter LLM", 429).contains("много запросов"));
        assertTrue(BoundedHttp.httpMessage("OpenRouter LLM", 503).contains("недоступен"));
        assertEquals("OpenRouter LLM: ошибка HTTP 418.", BoundedHttp.httpMessage("OpenRouter LLM", 418));
        assertTrue(BoundedHttp.httpMessage("OpenRouter TTS", 404).contains("HTTP 404"));
        assertTrue(BoundedHttp.httpMessage("OpenRouter LLM", 401).contains("401"));
    }

    @Test void aRepeatedLineIsCaughtAndAShortOneIsNot() {
        List<String> recent = List.of("Я ухожу отсюда, не трогай мою стаю, слышишь?", "Да.");
        assertNotNull(AnswerValidator.repeated("Не трогай мою стаю, слышишь, я ухожу отсюда!", recent));
        assertNull(AnswerValidator.repeated("Ладно, стой. Что тебе надо от нас на самом деле?", recent));
        assertNull(AnswerValidator.repeated("Да.", recent));
        assertNull(AnswerValidator.repeated("Я ухожу.", List.of("Я ухожу.")));
        assertNull(AnswerValidator.repeated("Что угодно", null));
    }

    @Test void failuresLoseTheirWrappersAndClassNames() {
        assertEquals("У вас нет 1× Щит (есть 0)", Failures.plain(new java.util.concurrent.CompletionException(new IllegalStateException("У вас нет 1× Щит (есть 0)"))));
        assertEquals("У вас нет 1× Щит (есть 0)", Failures.plain(new RuntimeException("java.lang.IllegalStateException: У вас нет 1× Щит (есть 0)")));
        assertEquals("Время: 12:30", Failures.plain(new RuntimeException("Время: 12:30")));
        assertEquals("", Failures.plain(new RuntimeException()));
    }

    @Test void fallbackLinesFollowTemperamentAndVary() {
        assertEquals("Гррр…", FallbackLines.line(true, 50, 0));
        assertNotEquals(FallbackLines.line(false, 80, 0), FallbackLines.line(false, 80, 1));
        assertTrue(FallbackLines.line(false, 10, 0).contains("!"));
        assertFalse(FallbackLines.line(false, 80, 3).isBlank());
    }
}
