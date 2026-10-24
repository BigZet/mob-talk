package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;

/** «Исповедь» of the panel: the rectors of the churches and how much a priest's absolution forgives (Mob Talk hears the
 * confession; the god keeps the sins and the numbers). Edits the panel's config. */
public final class ConfessionSettingsScreen extends DivinePanel {
    public ConfessionSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 6; }

    @Override protected void content() {
        serverNote();
        heading("Исповедь и покаяние");
        server(toggle("Настоятели в церквях", "В каждой церкви у аналоя появляется настоятель с именем из святцев; у него исповедуются в храме.", config.abbots, v -> config.abbots = v));
        List<Integer> percents = List.of(0, 25, 40, 50, 60, 75, 90, 100);
        server(cycle("Лёгкий грех в храме", "Сколько названного лёгкого греха отпускает настоятель храма.", percents, nearest(config.confessLightTemple, percents), v -> v + " %", v -> config.confessLightTemple = v));
        server(cycle("Лёгкий грех в селе", "Сколько названного лёгкого греха отпускает сельский батюшка.", percents, nearest(config.confessLightVillage, percents), v -> v + " %", v -> config.confessLightVillage = v));
        server(cycle("Тяжкий грех в храме", "Сколько тяжкого греха отпускается сразу; остаток — после епитимьи и возвращения к священнику.", percents, nearest(config.confessHeavyTemple, percents), v -> v + " %", v -> config.confessHeavyTemple = v));
        server(cycle("Тяжкий грех в селе", "То же у сельского батюшки.", percents, nearest(config.confessHeavyVillage, percents), v -> v + " %", v -> config.confessHeavyVillage = v));
        List<Integer> weights = List.of(8, 10, 12, 15, 20, 25);
        server(cycle("Порог тяжкого греха", "Убийство жителя 15, ребёнка 20, священника 25, осквернение храма или кладбища 12; хула 8, убийство питомца 10.",
                weights, nearest(config.confessHeavyWeight, weights), String::valueOf, v -> config.confessHeavyWeight = v));
        List<Integer> bonus = List.of(0, 10, 25, 50);
        server(cycle("Бонус искренности", "Искренняя исповедь отпускает больше, но не больше самого греха.", bonus, nearest(config.confessSincerityBonus, bonus), v -> "+" + v + " %", v -> config.confessSincerityBonus = v));
        server(toggle("Снимает личное проклятие", "Проклятие кончается, когда все грехи, бывшие на душе в день проклятия, отпущены. Мировые кары, бан и боссы — нет.", config.confessLiftsCurse, v -> config.confessLiftsCurse = v));
        server(toggle("Бог помнит скрытое", "Грех, утаённый на исповеди, остаётся на душе с пометкой, и бог называет его в вердикте.", config.confessRemembersHidden, v -> config.confessRemembersHidden = v));
        server(cycle("Повторный грех", "Тот же грех в два игровых дня после разрешения весит во столько раз больше и всегда тяжкий.", List.of(1, 2, 3, 4),
                Math.max(1, Math.min(4, config.confessRelapseMultiplier)), v -> "×" + v, v -> config.confessRelapseMultiplier = v));
        note("Исповедь ведёт Mob Talk (F7 → Социум → Исповедь): кто исповедует, тайна, откат, епитимьи. Бог хранит грехи и числа.", MUTED);
    }

    private static int nearest(int value, List<Integer> options) {
        int best = options.get(0);
        for (int option : options) if (Math.abs(option - value) < Math.abs(best - value)) best = option;
        return best;
    }
}
