package io.github.mobtalk.core;

/** Только для игрового самотеста свадьбы (трек L): нарочно «роняет» модель, озвучку или распознавание речи, чтобы проверить, что
 * служба всё равно доходит до брака на запасных текстах. В обычной игре всегда выключено. */
public final class WeddingFaults {
    private WeddingFaults() { }
    public static volatile boolean llm, tts, whisper;
    public static void clear() { llm = false; tts = false; whisper = false; }
}
