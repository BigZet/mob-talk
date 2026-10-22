package io.github.mobtalk.server;

import java.util.UUID;

/** Учёт расходов и лимиты на игрока и сервер; реализация — трек C. По решению Дмитрия лимиты по умолчанию выключены (0 = без
 * ограничения), бюджет считает расходы и показывает их в админке. Заглушка: {@link AllowAllBudget}. */
public interface PlayerBudget {
    enum Kind { DIALOGUE, TRANSCRIPTION, SPEECH, WITNESS, VILLAGE_TALK }
    /** {@code reason} — понятная игроку причина отказа; пусто, если разрешено. */
    record Verdict(boolean allowed, String reason) {
        public static Verdict ok() { return new Verdict(true, ""); }
        public static Verdict no(String reason) { return new Verdict(false, reason); }
    }

    /** Спросить перед работой: вызывается до LLM, распознавания и озвучки. */
    Verdict allow(UUID player, Kind kind);
    /** Списать после работы: деньги по ответу провайдера и единицы (секунды аудио, символы, реплики). */
    void charge(UUID player, Kind kind, double usd, int units);
    /** Сводка для команд и админки. */
    String report();
}
