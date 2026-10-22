package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;

/** «Кары» of the panel: escalation, combos, lingering curses, the eternal ban and each of the cruel acts on or off.
 * Edits the panel's config. */
public final class WrathSettingsScreen extends DivinePanel {
    /** The cruel acts the player may switch off one by one (the ban has its own switch). */
    static final List<Miracle> CRUEL = List.of(Miracle.BLIGHT, Miracle.CAIN, Miracle.PLAGUES, Miracle.SODOM, Miracle.FLOOD,
            Miracle.BABEL, Miracle.MEMORY_WIPE, Miracle.JUDGEMENT);

    public WrathSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 2; }

    @Override protected void content() {
        heading("Как растёт гнев");
        toggle("Кара за карой хуже", "Каждая следующая кара тяжелее прежней.", config.escalation, v -> config.escalation = v);
        cycle("Продолжения", "Сколько кар может последовать за первой подряд.", List.of(0, 1, 2), config.comboActs, v -> v == 0 ? "нет" : "до " + v, v -> config.comboActs = v);
        toggle("Проклятия на дни", "Проклятия держатся несколько игровых дней.", config.lingeringCurses, v -> config.lingeringCurses = v);
        cycle("Срок проклятий", "Сколько игровых дней держится проклятие.", List.of(1, 2, 3, 4, 5, 6, 7), config.curseDays, v -> v + " дн.", v -> config.curseDays = v);
        // Craters, rifts, floods: off puts another act of the same group in their place.
        cycle("Кары меняют ландшафт", "Воронки, разломы, потопы; «нет» ставит на их место другую кару той же группы.", List.of(true, false), config.landscapeWrath,
                v -> v ? "да" : "нет", v -> config.landscapeWrath = v);

        heading("Вечное изгнание");
        toggle("Вечное изгнание", "За четвёртую хулу бог выкидывает игрока из мира навсегда.", config.allowEternalBan, v -> { config.allowEternalBan = v; rebuild(); });
        toggle("Копия мира", "Перед изгнанием сохраняется копия мира.", config.banBackup, v -> config.banBackup = v);
        note(config.allowEternalBan
                ? "Изгнание включено: за четвёртую хулу бог выкинет тебя из мира, и мир больше не впустит. Снять: выключить здесь, файл config/divineblock-unban.txt или -Ddivineblock.unban=1."
                : "Изгнание выключено: бог никогда не изгоняет, а изгнанный прежде снова может войти.", config.allowEternalBan ? 0xFF5555 : MUTED);

        heading("Жестокие кары");
        for (Miracle miracle : CRUEL)
            toggle(name(miracle), "Выключенная кара не выпадает.", !config.disabled.contains(miracle.id()),
                    v -> { config.disabled.remove(miracle.id()); if (!v) config.disabled.add(miracle.id()); });
    }

    static String name(Miracle miracle) {
        return switch (miracle) {
            case BLIGHT -> "Порча земли"; case CAIN -> "Печать Каина"; case PLAGUES -> "Казни египетские";
            case SODOM -> "Содом"; case FLOOD -> "Потоп"; case BABEL -> "Вавилон"; case MEMORY_WIPE -> "Стирание памяти"; case JUDGEMENT -> "Суд в небесах";
            default -> miracle.id();
        };
    }
}
