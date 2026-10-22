package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The god knows who is on the stream and whether the one praying is the streamer, only when a streamer is set. */
class StreamerPromptTest {
    private static Verdict verdict(String streamer, boolean byStreamer) {
        Verdict verdict = new Verdict();
        verdict.prayer = "дай хлеба"; verdict.miracle = Miracle.FEED.id(); verdict.power = 1;
        verdict.streamer = streamer; verdict.byStreamer = byStreamer;
        return verdict;
    }

    @Test void noStreamerNoWord() {
        String prompt = GodSpeech.announcement(verdict("", false), List.of(), GodSpeech.FORMS[0]);
        assertFalse(prompt.contains("трансляц"), prompt);
    }

    @Test void theStreamerPrays() {
        String prompt = GodSpeech.announcement(verdict("BigZet", true), List.of(), GodSpeech.FORMS[0]);
        assertTrue(prompt.contains("BigZet"), prompt);
        assertTrue(prompt.contains("Молится сам ведущий трансляции"), prompt);
    }

    @Test void anotherMortalPrays() {
        String prompt = GodSpeech.announcement(verdict("BigZet", false), List.of(), GodSpeech.FORMS[0]);
        assertTrue(prompt.contains("BigZet"), prompt);
        assertTrue(prompt.contains("Молится не ведущий трансляции"), prompt);
        assertTrue(prompt.contains("Ни милости, ни гнева"), "no special reaction in the first version");
    }
}
