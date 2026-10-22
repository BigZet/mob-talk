package io.github.mobtalk.core;

import java.util.HashMap;
import java.util.Map;

/** Отсрочка потери: моб, на миг потерявший игрока из виду (дерево, угол) или из дальности, ещё несколько секунд считается видящим
 * и слышащим. {@link #hold} каждый раз получает текущий ответ «видит/рядом» и возвращает его с учётом отсрочки: истина, пока
 * последнее «да» было не дольше {@code graceMillis} назад. Ключ, для которого «да» не было ни разу, сразу ложь. Потокобезопасно:
 * сервер и клиент держат по своему экземпляру. */
public final class Grace<K> {
    /** Сколько ключей держать без уборки: мобов в мире больше, чем возле игрока, старые записи выметаются. */
    private static final int PRUNE_ABOVE = 512;
    private final Map<K, Long> lastOk = new HashMap<>();

    /** {@code ok} — видит ли (рядом ли) сейчас; ответ с отсрочкой. */
    public synchronized boolean hold(K key, boolean ok, long now, long graceMillis) {
        if (ok) {
            lastOk.put(key, now);
            if (lastOk.size() > PRUNE_ABOVE) prune(now, Math.max(graceMillis * 4, 60_000));
            return true;
        }
        return recent(key, now, graceMillis);
    }
    /** Было ли «да» не дольше {@code graceMillis} назад (без нового наблюдения). */
    public synchronized boolean recent(K key, long now, long graceMillis) {
        Long last = lastOk.get(key);
        return last != null && now - last <= graceMillis;
    }
    /** Настоящая причина (смерть, выгрузка, смена мира): отсрочки больше нет. */
    public synchronized void forget(K key) { lastOk.remove(key); }
    public synchronized void clear() { lastOk.clear(); }
    public synchronized int size() { return lastOk.size(); }
    private void prune(long now, long olderThan) { lastOk.values().removeIf(at -> now - at > olderThan); }
}
