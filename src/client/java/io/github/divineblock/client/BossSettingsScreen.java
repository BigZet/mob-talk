package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;

/** «Боссы» of the panel, the sacred bosses: each one on or off by where he comes from, a whole group at once (off brings a
 * buffed vanilla mob instead), their strength and whether their ultimates may break blocks. Edits the panel's config. */
public final class BossSettingsScreen extends DivinePanel {
    public BossSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 3; }

    @Override protected void content() {
        serverNote();
        note("Выключенный босс приходит обычным мобом с его именем; без бога с булавой остаются его молнии и ударные волны.", MUTED);
        // Every boss by where he comes from first; «Бог с булавой» (the god himself) with the end of the world.
        flags("disabledBosses", config.disabledBosses);

        heading("Сила");
        server(cycle("Сила боссов", "Здоровье и урон боссов относительно обычного.", List.of(50, 75, 100, 150, 200, 300), nearest(config.bossDifficulty),
                v -> v + "%", v -> config.bossDifficulty = v));
        server(toggle("Ломать блоки", "Удары боссов могут разрушать блоки.", config.bossBlockDamage, v -> config.bossBlockDamage = v));
    }

    private static int nearest(int value) {
        int best = 100;
        for (int option : new int[] { 50, 75, 100, 150, 200, 300 }) if (Math.abs(option - value) < Math.abs(best - value)) best = option;
        return best;
    }
}
