package io.github.mobtalk.core;

import java.util.List;

/** What a mob says when the model is unreachable: one short line by temperament, no network, no voice. The line is a
 * stand-in, not an answer, so it never carries facts and never goes into memory. */
public final class FallbackLines {
    private FallbackLines() { }

    private static final List<String> CALM = List.of("Погоди… не расслышал.", "М-м. Повтори, что ли.", "Задумался. Ещё раз?", "Сейчас, собираюсь с мыслями…");
    private static final List<String> NERVOUS = List.of("А? Что? Повтори!", "Не понял… не понял!", "Ты что-то сказал? Ещё раз, быстро.", "Погоди-погоди, не так сразу.");
    private static final List<String> HOSTILE = List.of("Гррр…", "…", "Хр-р. Чего?", "Р-р-р… повтори.");

    /** {@code calm} 0–100 from the state; {@code seed} picks a line so the same mob does not say the same thing twice in a row. */
    public static String line(boolean hostile, int calm, long seed) {
        List<String> pool = hostile ? HOSTILE : calm < 40 ? NERVOUS : CALM;
        return pool.get((int) Math.floorMod(seed, pool.size()));
    }
}
