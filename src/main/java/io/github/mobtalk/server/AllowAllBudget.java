package io.github.mobtalk.server;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** Пока трек C не сделал лимиты: всё разрешено, расходы только считаются по игрокам и видам работы для отчёта. */
public final class AllowAllBudget implements PlayerBudget {
    private record Key(UUID player, Kind kind) { }
    private final Map<Key, DoubleAdder> usd = new ConcurrentHashMap<>();
    private final Map<Key, LongAdder> units = new ConcurrentHashMap<>();

    @Override public Verdict allow(UUID player, Kind kind) { return Verdict.ok(); }

    @Override public void charge(UUID player, Kind kind, double money, int count) {
        Key key = new Key(player, kind);
        usd.computeIfAbsent(key, ignored -> new DoubleAdder()).add(Math.max(0, money));
        units.computeIfAbsent(key, ignored -> new LongAdder()).add(Math.max(0, count));
    }

    @Override public String report() {
        StringBuilder out = new StringBuilder("Бюджет: лимитов нет");
        double total = 0;
        for (Map.Entry<Key, DoubleAdder> entry : usd.entrySet()) {
            double sum = entry.getValue().sum();
            total += sum;
            long count = units.getOrDefault(entry.getKey(), new LongAdder()).sum();
            out.append("\n  ").append(entry.getKey().player()).append(' ').append(entry.getKey().kind().name().toLowerCase(Locale.ROOT))
               .append(": ").append(String.format(Locale.ROOT, "%.4f $", sum)).append(", ").append(count);
        }
        return out.append("\n  всего: ").append(String.format(Locale.ROOT, "%.4f $", total)).toString();
    }
}
