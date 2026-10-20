package io.github.mobtalk.core;

import java.util.List;
import java.util.UUID;

/** The small choices the mod makes about who speaks and whether: a creature speaking first, a bystander cutting in, the next
 * voice in a scene between creatures. {@link RuleDecider} answers from rules and chance; {@link JevDecider} asks a small
 * decision model and falls back to the rules. Every method may take up to a couple of seconds: never on the game thread. */
public interface Decider {
    /** Whether and how the creature speaks first on the occasion in the state. */
    Initiative.Verdict speaksUp(DecisionState state);
    /** Which candidate answers the last line of a scene; null when nobody does. */
    UUID nextSpeaker(DecisionState state, List<Initiative.Candidate> candidates);
    /** Whether a bystander puts in a word after an exchange it overheard. */
    boolean bystanderAnswers(DecisionState state);
    /** Трек L: согласился ли игрок на брак своим ответом на вопрос ведущего. Отказ — только явный ({@link Wedding#refuses}); шутка,
     * сомнение или молчание — согласие. */
    default boolean consents(String question, String answer) { return !Wedding.refuses(answer); }
    /** For the log: "rules" or "jev". */
    String name();
}
