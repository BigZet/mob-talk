package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Roulette;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;

/** «Кары» of the panel: escalation, lingering curses, the eternal ban and what Mob Talk's villages hear; each act on or off is on «Деяния».
 * Edits the panel's config. */
public final class WrathSettingsScreen extends DivinePanel {
    public WrathSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 2; }

    /** «Рулетка кары…»: the server's switch, length, audience, tease and tails (read-only for a player on a server), and the
     * player's own picture, sound and 777 effects. */
    private void roulette() {
        heading("Рулетка кары…");
        server(toggle("Рулетка включена", "Перед карой три барабана крутятся и встают на её символ; 777 — конец света.", config.rouletteEnabled,
                v -> { config.rouletteEnabled = v; rebuild(); }));
        List<Roulette.Mode> modes = List.of(Roulette.Mode.SHORT, Roulette.Mode.NORMAL, Roulette.Mode.DRAMATIC);
        server(cycle("Длительность", "Короткая — около 3 с, обычная — около 6,5 с, драматичная — около 9 с (третий барабан тормозит 3 секунды).",
                modes, modes.contains(config.rouletteMode) ? config.rouletteMode : Roulette.Mode.NORMAL, m -> m.label, v -> config.rouletteMode = v));
        server(cycle("Показывать другим", "Кому ещё видна рулетка чужой кары: только грешнику, игрокам в радиусе или всем на сервере.",
                List.of(Roulette.Audience.values()), config.rouletteAudience, a -> a.label, v -> { config.rouletteAudience = v; rebuild(); }));
        List<Integer> radii = List.of(16, 32, 64, 96, 128, 192, 256);
        server(cycle("Радиус показа", "Игроки ближе видят рулетку чужой кары («Кара для …»).", radii, nearest(config.rouletteRadius, radii),
                v -> v + " блоков", v -> config.rouletteRadius = v));
        server(toggle("Дразнилка «почти 777»", "Для кар К3–К4 третий барабан иногда медленно проползает мимо семёрки.", config.rouletteTease,
                v -> config.rouletteTease = v));
        List<Integer> chances = List.of(5, 10, 25, 50, 75, 100);
        server(cycle("Шанс дразнилки", "Как часто третий барабан дразнит семёркой.", chances, nearest(config.rouletteTeaseChance, chances),
                v -> v + "%", v -> config.rouletteTeaseChance = v));
        List<Integer> mercies = List.of(0, 1, 2, 3, 5, 10, 15, 20, 25);
        server(cycle("Случайное помилование", "Как часто третий барабан в последний миг проскакивает на голубя и кара не падает; ступень гнева при этом не сбрасывается. На вечное изгнание не действует.",
                mercies, nearest(config.rouletteMercyChance, mercies), v -> v == 0 ? "никогда" : v + "%", v -> config.rouletteMercyChance = v));
        server(cycle("Продолжения", "Перед второй волной орды и разбойников и перед повторными толчками: без рулетки, мини (один барабан, 2 с) или полная.",
                List.of(Roulette.Tails.values()), config.rouletteTails, t -> t.label, v -> config.rouletteTails = v));
        server(toggle("Кары из админки", "Деяние гнева, посланное из веб-админки, тоже падает после рулетки.", config.rouletteAdmin, v -> config.rouletteAdmin = v));
        toggle("Показывать у меня", "Рисовать рулетку на моём экране; выкл оставляет строку в чате. Тайминг кары от этого не меняется.", config.rouletteShow,
                v -> config.rouletteShow = v);
        List<Integer> volumes = List.of(0, 25, 50, 75, 100, 150, 200);
        cycle("Громкость рулетки", "Рычаг, храповик, колокола, хор и гром рулетки.", volumes, nearest(config.rouletteVolume, volumes),
                v -> v == 0 ? "выкл" : v + "%", v -> config.rouletteVolume = v);
        cycle("Интенсивность эффектов", "Лучи, бегущие огни, искры, монеты и большие надписи рулетки; «выкл» оставляет простой автомат.",
                List.of(0, 1, 2, 3), config.rouletteFx, v -> new String[] { "выкл", "низко", "средне", "высоко" }[v], v -> config.rouletteFx = v);
        toggle("Вспышка и тряска на 777", "Золотая вспышка экрана и тряска камеры, когда выпадает конец света.", config.rouletteJackpotFx,
                v -> config.rouletteJackpotFx = v);
    }

    private static int nearest(int value, List<Integer> options) {
        int best = options.get(0);
        for (int option : options) if (Math.abs(option - value) < Math.abs(best - value)) best = option;
        return best;
    }

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

        heading("Жители Mob Talk");
        server(toggle("Сообщать жителям Mob Talk", "Каждое деяние бога доходит до деревень Mob Talk: жители судачат, боятся и винят грешника.", config.tellVillages, v -> config.tellVillages = v));
        server(toggle("Бог называет грешника вслух", "Деревня знает, за чьи грехи кара, даже если жители её не видели.", config.nameSinner, v -> config.nameSinner = v));
        roulette();
        note("Каждую кару, благо и шутку можно выключить на странице «Деяния».", MUTED);
    }
}
