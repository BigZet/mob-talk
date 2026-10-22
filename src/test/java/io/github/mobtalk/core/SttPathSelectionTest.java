package io.github.mobtalk.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Личный sttPath ∩ пути сервера ∩ установлен ли Whisper → фактический путь и подсказка (трек B). */
class SttPathSelectionTest {
    private static final List<String> ALL = List.of("local", "server", "text");

    @Test void autoPrefersLocalThenServerThenText() {
        assertEquals(new SttRoute.Choice("local", ""), SttRoute.choose("auto", ALL, true));
        assertEquals(new SttRoute.Choice("server", ""), SttRoute.choose("auto", ALL, false));
        assertEquals(new SttRoute.Choice("text", ""), SttRoute.choose("auto", List.of("local", "text"), false));
    }

    @Test void explicitChoiceIsKeptWhenAllowed() {
        assertEquals(new SttRoute.Choice("server", ""), SttRoute.choose("server", ALL, true));
        assertEquals(new SttRoute.Choice("text", ""), SttRoute.choose("text", ALL, true));
        assertEquals(new SttRoute.Choice("local", ""), SttRoute.choose("local", ALL, true));
    }

    @Test void unavailableChoiceFallsBackWithHint() {
        SttRoute.Choice noServer = SttRoute.choose("server", List.of("local", "text"), true);
        assertEquals("local", noServer.path());
        assertTrue(noServer.hint().contains("сервере выключено"), noServer.hint());
        SttRoute.Choice noWhisper = SttRoute.choose("local", ALL, false);
        assertEquals("server", noWhisper.path());
        assertTrue(noWhisper.hint().contains("не установлен"), noWhisper.hint());
        SttRoute.Choice nothing = SttRoute.choose("server", List.of("local", "text"), false);
        assertEquals("text", nothing.path());
        assertFalse(nothing.voice());
    }

    @Test void serverWithoutModOrAnswerLeavesLocalAndText() {
        assertEquals("local", SttRoute.choose("auto", List.of(), true).path());
        assertEquals("text", SttRoute.choose("server", List.of(), false).path());
    }

    @Test void garbageMeansAuto() {
        assertEquals("auto", SttRoute.normalize(null));
        assertEquals("auto", SttRoute.normalize("Облако"));
        assertEquals("server", SttRoute.normalize(" SERVER "));
        assertTrue(SttRoute.validModel("mistralai/voxtral-mini-transcribe-3-0"));
        assertFalse(SttRoute.validModel("voxtral"));
        assertFalse(SttRoute.validModel("a/b c"));
    }
}
