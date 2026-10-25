package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeechTagsTest {
    @Test void chatNeverShowsTheTags() {
        assertEquals("Слушай, только никому. Ну ты даёшь!", SpeechTags.strip("[шёпотом] Слушай, только никому. [смеётся] Ну ты даёшь!"));
        assertEquals("Без пометок.", SpeechTags.strip("Без пометок."));
        assertEquals("Ох, тяжко.", SpeechTags.strip("Ох [вздыхает], тяжко."));
    }

    @Test void taggedModelsGetEnglishTags() {
        assertEquals("[whispers] Слушай, только никому. [laughs] Ну ты даёшь!",
                SpeechTags.forSpeech("[шёпотом] Слушай, только никому. [смеётся] Ну ты даёшь!", "elevenlabs/eleven-v4-turbo", true));
        assertEquals("[sighs] Ладно.", SpeechTags.forSpeech("[Вздыхает] Ладно.", "elevenlabs/eleven-v3-conversational", true));
        assertEquals("[whispers] Тсс.", SpeechTags.forSpeech("[whispers] Тсс.", "elevenlabs/eleven-v3", true), "английский из набора проходит");
        assertEquals("Хм.", SpeechTags.forSpeech("[танцует] Хм.", "elevenlabs/eleven-v3", true), "неизвестная пометка не звучит");
        String many = SpeechTags.forSpeech("[смеётся] а [вздыхает] б [плачет] в [кричит] г", "elevenlabs/eleven-v3", true);
        assertEquals(SpeechTags.MAX_TAGS, many.split("\\[").length - 1, many);
    }

    @Test void otherModelsNeverReadTagsAloud() {
        assertEquals("Тсс.", SpeechTags.forSpeech("[шёпотом] Тсс.", "elevenlabs/eleven-flash-v2.5", true));
        assertEquals("Тсс.", SpeechTags.forSpeech("[шёпотом] Тсс.", "elevenlabs/eleven-v3", false), "выключено в F9");
        assertEquals("Тсс.", SpeechTags.forSpeech("[шёпотом] Тсс.", "tts-kokoro", true));
    }

    @Test void promptOnlyWhenTheVoiceCanPlayThem() {
        MobTalkConfig c = new MobTalkConfig();
        assertEquals("", SpeechTags.prompt(c), "Flash по умолчанию: модель зря не просим");
        c.ttsModel = "elevenlabs/eleven-v4-turbo";
        assertTrue(SpeechTags.prompt(c).contains("[шёпотом]"));
        c.ttsTags = false;
        assertEquals("", SpeechTags.prompt(c));
        c.ttsTags = true; c.ttsProvider = "venice";
        assertEquals("", SpeechTags.prompt(c));
        assertTrue(c.copy().ttsTags);
    }

    @Test void aLeadingTagSurvivesTheReplyCleanup() {
        assertEquals("[шёпотом] Тсс, стражник рядом.", AnswerValidator.spokenText("[шёпотом] Тсс, стражник рядом."));
        assertEquals("Привет", AnswerValidator.spokenText(": Привет"));
    }
}
