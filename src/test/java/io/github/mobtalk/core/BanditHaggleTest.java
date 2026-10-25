package io.github.mobtalk.core;

import io.github.mobtalk.core.BanditHaggle.Kind;
import io.github.mobtalk.core.BanditHaggle.Move;
import io.github.mobtalk.core.BanditHaggle.State;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Talking a night bandit down: what the words mean, and what the gang does about them. */
class BanditHaggleTest {
    private static BanditHaggle.Outcome say(String words, State state) { return BanditHaggle.decide(BanditHaggle.read(words), state); }

    @Test void wordsBecomeMoves() {
        assertEquals(new BanditHaggle.Said(Move.OFFER, 5), BanditHaggle.read("Дам пять, больше нет"));
        assertEquals(new BanditHaggle.Said(Move.OFFER, 8), BanditHaggle.read("8"));
        assertEquals(new BanditHaggle.Said(Move.OFFER, 12), BanditHaggle.read("Ладно, возьми 12 изумрудов"));
        assertEquals(Move.THREAT, BanditHaggle.read("Я сейчас полицию позову!").move());
        assertEquals(Move.THREAT, BanditHaggle.read("Стража! Помогите!").move());
        assertEquals(Move.EMPTY, BanditHaggle.read("У меня нет изумрудов, честно").move());
        assertEquals(Move.EMPTY, BanditHaggle.read("я пустой").move());
        assertEquals(Move.PLEA, BanditHaggle.read("Пожалуйста, отпусти, у меня дети").move());
        assertEquals(Move.PLEA, BanditHaggle.read("Скинь немного").move());
        assertEquals(Move.NONE, BanditHaggle.read("Как тебя зовут?").move());
        assertEquals(Move.NONE, BanditHaggle.read("Подожди момент, где документы?").move());
        assertEquals(Move.NONE, BanditHaggle.read("").move());
    }

    @Test void anOfferOfThreeFifthsIsTakenOnce() {
        State state = new State(20, 0, 40, false, 0);
        assertEquals(12, BanditHaggle.floor(state));
        BanditHaggle.Outcome taken = say("дам 12", state);
        assertEquals(Kind.LOWER, taken.kind()); assertEquals(12, taken.demand());
        assertEquals(Kind.REFUSE, say("дам 11", state).kind());
        State after = new State(taken.demand(), 0, 40, false, taken.used());
        assertEquals(Kind.REFUSE, say("дам 8", after).kind(), "a second haggle is refused");
        assertEquals(Kind.NONE, say("держи 20", state).kind(), "the whole debt: just pay");
    }

    @Test void anOfferCountsWhatIsAlreadyPaid() {
        BanditHaggle.Outcome taken = say("дам 6", new State(20, 10, 40, false, 0));
        assertEquals(Kind.LOWER, taken.kind()); assertEquals(16, taken.demand());
    }

    @Test void aPleaSoftensOnlyThePoor() {
        BanditHaggle.Outcome poor = say("пожалуйста, пощади", new State(12, 0, 20, false, 0));
        assertEquals(Kind.LOWER, poor.kind()); assertEquals(9, poor.demand());
        assertEquals(Kind.REFUSE, say("пожалуйста", new State(12, 0, 64, false, 0)).kind());
        assertEquals(Kind.REFUSE, say("пожалуйста", new State(9, 0, 20, false, poor.used())).kind(), "only once");
    }

    @Test void aLieRaisesThePriceAndTheTruthTakesWhatThereIs() {
        BanditHaggle.Outcome lie = say("нет у меня ничего", new State(10, 0, 30, false, 0));
        assertEquals(Kind.RAISE, lie.kind()); assertEquals(15, lie.demand());
        BanditHaggle.Outcome truth = say("нет денег", new State(10, 0, 4, false, 0));
        assertEquals(Kind.LOWER, truth.kind()); assertEquals(4, truth.demand());
        assertEquals(Kind.FIGHT, say("пустой я", new State(10, 0, 0, false, 0)).kind());
    }

    @Test void aThreatWorksOnlyNearAPost() {
        assertEquals(Kind.LEAVE, say("позову полицию", new State(10, 0, 30, true, 0)).kind());
        BanditHaggle.Outcome bluff = say("позову полицию", new State(10, 0, 30, false, 0));
        assertEquals(Kind.RAISE, bluff.kind()); assertEquals(12, bluff.demand());
        assertEquals(Kind.FIGHT, say("стража!", new State(12, 0, 30, false, bluff.used())).kind());
        assertEquals(64, say("полиция", new State(60, 0, 64, false, 0)).demand(), "never above a stack");
    }

    @Test void thePromptNamesTheFloorAndThePost() {
        String prompt = BanditHaggle.prompt(new State(20, 0, 40, true, 0), "Стив");
        assertTrue(prompt.contains("20 изумр.") && prompt.contains("не меньше 12") && prompt.contains("уходишь"), prompt);
        assertEquals("", BanditHaggle.prompt(new State(5, 5, 0, false, 0), "Стив"));
    }
}
