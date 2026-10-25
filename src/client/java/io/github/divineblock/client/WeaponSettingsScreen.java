package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;

/** «Оружие» of the panel: each weapon of the god on or off (the war sword is off until someone wants it), whether the
 * trumpets of Jericho bring walls down, and the weapons' own strength. Edits the panel's config. */
public final class WeaponSettingsScreen extends DivinePanel {
    public WeaponSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 4; }

    @Override protected void content() {
        serverNote();
        note("Оружие куётся из реликвий боссов на верстаке. Выключенное в руке не действует и говорит «бог не велит». Крест, иконы и кадило оружием не бывают.", MUTED);
        flags("disabledWeapons", config.disabledWeapons);

        heading("Сила");
        server(cycle("Сила оружия", "Собственный урон оружия бога (бонусы по нечисти, змиям, Голиафу) относительно обычного.", List.of(50, 75, 100, 125, 150), nearest(config.weaponPower),
                v -> v + "%", v -> config.weaponPower = v));
        server(toggle("Трубы Иерихона ломают стены", "Стеновые блоки перед трубачом рассыпаются (ещё нужны mobGriefing и выключенное «Беречь блоки»).", config.jerichoBreaksBlocks, v -> config.jerichoBreaksBlocks = v));
    }

    private static int nearest(int value) {
        int best = 100;
        for (int option : new int[] { 50, 75, 100, 125, 150 }) if (Math.abs(option - value) < Math.abs(best - value)) best = option;
        return best;
    }
}
