package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Кто сейчас говорит вслух: все голоса мобов, которые ещё звучат, а не только последний запущенный. Фоновый голос (очевидец,
 * инициатива, болтовня, свадьба) начинается только на свободном полу, ответ на вопрос игрока сначала гасит остальные — так
 * одновременно звучит не больше одного голоса и у моба нет двух реплик сразу. {@code V} — звук клиента; чистая логика,
 * клиентский поток. */
public final class VoiceFloor<V> {
    private record Entry<V>(V voice, UUID mob, boolean background) { }
    private final List<Entry<V>> live = new ArrayList<>();
    /** Самотест: наибольшее число голосов разом и сколько раз голос начался поверх звучащего. */
    private int peak, overlaps;

    /** Можно ли начать голос: фоновый — только когда никто не говорит. */
    public boolean mayStart(boolean background) { return !background || live.isEmpty(); }
    /** Голос запущен. Звучащий в этот момент другой голос — наложение (его считают для самотеста). */
    public void started(V voice, UUID mob, boolean background) {
        live.removeIf(entry -> entry.voice == voice);
        if (!live.isEmpty()) overlaps++;
        live.add(new Entry<>(voice, mob, background));
        peak = Math.max(peak, live.size());
    }
    /** Убирает отзвучавшие ({@code playing} ложь), каждому из них — {@code ended}. */
    public void prune(Predicate<V> playing, Consumer<V> ended) {
        List<V> gone = new ArrayList<>();
        for (var it = live.iterator(); it.hasNext(); ) {
            Entry<V> entry = it.next();
            if (playing.test(entry.voice)) continue;
            it.remove();
            gone.add(entry.voice);
        }
        gone.forEach(ended);
    }
    /** Гасит все голоса (ответ игроку, смена мира, отмена разговора). */
    public void stopAll(Consumer<V> stop) {
        List<Entry<V>> stopping = new ArrayList<>(live);
        live.clear();
        for (Entry<V> entry : stopping) stop.accept(entry.voice);
    }
    /** Гасит голоса одного моба (он погиб или выгрузился). */
    public void stopMob(UUID mob, Consumer<V> stop) {
        List<V> stopping = new ArrayList<>();
        for (var it = live.iterator(); it.hasNext(); ) {
            Entry<V> entry = it.next();
            if (!entry.mob.equals(mob)) continue;
            it.remove();
            stopping.add(entry.voice);
        }
        stopping.forEach(stop);
    }
    public boolean free() { return live.isEmpty(); }
    public int count() { return live.size(); }
    /** Говорит ли этот моб. */
    public boolean speaking(UUID mob) { return live.stream().anyMatch(entry -> entry.mob.equals(mob)); }
    public List<V> voices() { return live.stream().map(Entry::voice).toList(); }
    public int peak() { return peak; }
    public int overlaps() { return overlaps; }
    /** Самотест: счётчики с нуля перед проверкой. */
    public void resetStats() { peak = live.size(); overlaps = 0; }
}
