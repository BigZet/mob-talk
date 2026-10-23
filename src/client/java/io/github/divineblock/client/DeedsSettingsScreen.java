package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.client.gui.screen.Screen;

/** «Деяния» of the panel: every act of the god — punishments, mercies, jests and omens — on or off, a whole kind at once,
 * with the panel's search over titles and descriptions. Edits the panel's config (the {@code disabled} list). */
public final class DeedsSettingsScreen extends DivinePanel {
    public DeedsSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 4; }

    @Override protected void content() {
        serverNote();
        note("Выключенное деяние не выпадает: бог берёт другое из той же группы. Визеры, драконы и вечное изгнание ещё и под своими выключателями.", MUTED);
        flags("disabled", config.disabled);
    }
}
