package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;

/** «Кары» of the panel: escalation, lingering curses and the eternal ban; each act on or off is on «Деяния».
 * Edits the panel's config. */
public final class WrathSettingsScreen extends DivinePanel {
    public WrathSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 2; }

    @Override protected void content() {
        serverNote();
        heading("Как растёт гнев");
        server(toggle("Кара за карой хуже", "Каждая следующая кара тяжелее прежней.", config.escalation, v -> config.escalation = v));
        server(toggle("Долгие проклятия", "Проклятия держатся срок ниже; выкл — три минуты.", config.lingeringCurses, v -> config.lingeringCurses = v));
        List<Integer> minutes = List.of(1, 3, 5, 10, 15, 20);
        server(cycle("Срок проклятий", "Сколько минут реального времени держится проклятие; дольше 20 минут не бывает.", minutes,
                minutes.contains(config.curseMinutes) ? config.curseMinutes : 20, v -> v + " мин", v -> config.curseMinutes = v));
        // Craters, rifts, floods: off puts another act of the same group in their place.
        server(cycle("Кары меняют ландшафт", "Воронки, разломы, потопы; «нет» ставит на их место другую кару той же группы.", List.of(true, false), config.landscapeWrath,
                v -> v ? "да" : "нет", v -> config.landscapeWrath = v));

        heading("Вечное изгнание");
        server(toggle("Вечное изгнание", "За четвёртую хулу бог выкидывает игрока из мира навсегда.", config.allowEternalBan, v -> { config.allowEternalBan = v; rebuild(); }));
        server(toggle("Копия мира", "Перед изгнанием сохраняется копия мира.", config.banBackup, v -> config.banBackup = v));
        note(config.allowEternalBan
                ? "Изгнание включено: за четвёртую хулу бог выкинет тебя из мира, и мир больше не впустит. Снять: выключить здесь, файл config/divineblock-unban.txt или -Ddivineblock.unban=1."
                : "Изгнание выключено: бог никогда не изгоняет, а изгнанный прежде снова может войти.", config.allowEternalBan ? 0xFF5555 : MUTED);
        note("Каждую кару, благо и шутку можно выключить на странице «Деяния».", MUTED);
    }
}
