package io.github.mobtalk.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Инициатива мобов после переноса на сервер (трек A): поводы, решения и сцены между существами ведёт {@code SpontaneousVoices}
 * на сервере; клиент лишь помнит последнюю реплику каждого, кто заговорил первым ({@code MobSpeechS2C} вида {@code initiative}),
 * для журнала и самотеста (семья, утро, подарок). */
final class InitiativeVoice {
    private final MobTalkClient owner;
    /** Последняя реплика каждого, кто заговорил первым или ответил в сцене; "" — решил промолчать. */
    final Map<UUID, String> lastLines = new HashMap<>();

    InitiativeVoice(MobTalkClient owner) { this.owner = owner; }

    void record(UUID mob, String line) { lastLines.put(mob, line == null ? "" : line); }
    /** Сцены идут на сервере: клиент их не видит. */
    boolean sceneOn() { return false; }
    void cancel() { }
}
