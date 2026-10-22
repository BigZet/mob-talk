package io.github.mobtalk.server;

import java.util.UUID;

/** Учёт расходов и лимиты на игрока и сервер; реализация — {@link PlayerBudgetImpl} (трек C). По решению Дмитрия лимиты по умолчанию
 * выключены (0 = без ограничения), бюджет считает расходы и показывает их в админке. Заглушка: {@link AllowAllBudget}. */
public interface PlayerBudget {
    enum Kind { DIALOGUE, TRANSCRIPTION, SPEECH, WITNESS, VILLAGE_TALK }
    /** {@code reason} — понятная игроку причина отказа; пусто, если разрешено. */
    record Verdict(boolean allowed, String reason) {
        public static Verdict ok() { return new Verdict(true, ""); }
        public static Verdict no(String reason) { return new Verdict(false, reason); }
    }

    /** Место в очереди одновременных ответов: взять перед запросом к модели, закрыть после (в {@code finally}). */
    interface Slot extends AutoCloseable {
        boolean granted();
        /** Почему места нет, для {@code TalkStatusS2C}; пусто, если есть. */
        String reason();
        @Override void close();
        Slot FREE = new Slot() {
            @Override public boolean granted() { return true; }
            @Override public String reason() { return ""; }
            @Override public void close() { }
        };
    }

    /** Спросить перед работой: вызывается до LLM, распознавания и озвучки. */
    Verdict allow(UUID player, Kind kind);
    /** Списать после работы: деньги по ответу провайдера и единицы (секунды аудио, символы, реплики). */
    void charge(UUID player, Kind kind, double usd, int units);
    /** Сводка для команд и админки. */
    String report();

    /** Техническая защита от бесконечной очереди: не больше двух ответов на игрока сразу и {@code maxConcurrentDialogues} на сервер.
     * Слот закрывают и при отказе — это ничего не стоит. */
    default Slot enter(UUID player) { return Slot.FREE; }
    /** Сколько игрок потратил сегодня, строкой для F7 и HUD («0,0123 $»). */
    default String spentToday(UUID player) { return ""; }
}
