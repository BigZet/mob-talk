package io.github.mobtalk.client;

import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobStateS2C;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Что сервер рассказал клиенту о мобах рядом (трек A): состояние для HUD ({@link MobStateS2C}) и подпись ({@link MobLabelS2C}).
 * Сервер шлёт только изменения и раз в несколько секунд повтор; запись без повтора дольше {@link #STALE_MILLIS} считается устаревшей
 * и снимается. Только поток клиента. */
final class MobViews {
    static final long STALE_MILLIS = 15_000;
    private final Map<UUID, MobStateS2C> states = new HashMap<>();
    private final Map<UUID, MobLabelS2C> labels = new HashMap<>();
    private final Map<UUID, Long> seen = new HashMap<>();

    void accept(MobStateS2C packet) { states.put(packet.mob(), packet); seen.put(packet.mob(), System.currentTimeMillis()); }
    void accept(MobLabelS2C packet) {
        if (packet.name().isEmpty()) { labels.remove(packet.mob()); states.remove(packet.mob()); seen.remove(packet.mob()); return; }
        labels.put(packet.mob(), packet); seen.put(packet.mob(), System.currentTimeMillis());
    }
    MobStateS2C state(UUID mob) { return mob == null ? null : states.get(mob); }
    MobLabelS2C label(UUID mob) { return mob == null ? null : labels.get(mob); }
    /** Имя из памяти мира, если сервер его прислал; пусто для незнакомца. */
    String name(UUID mob) { MobLabelS2C label = label(mob); return label == null ? "" : label.name(); }
    /** Имена всех мобов с подписью: подсказки локальному Whisper. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        for (MobLabelS2C label : labels.values()) if (!label.name().isBlank()) names.add(label.name());
        return names;
    }
    void prune() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= STALE_MILLIS) return false;
            states.remove(entry.getKey()); labels.remove(entry.getKey()); return true;
        });
    }
    void clear() { states.clear(); labels.clear(); seen.clear(); }
}
