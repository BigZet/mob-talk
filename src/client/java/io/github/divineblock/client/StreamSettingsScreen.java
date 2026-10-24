package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.client.gui.screen.Screen;

/** «Бог на стриме» of the panel: what the OBS overlay of Mob Talk's admin may show of the god — anything at all, the reasons of
 * a verdict, every soul's mercy or only the streamer's, the sins, and which punishments and mercies it calls only «Тайное
 * деяние». Edits the panel's config. */
public final class StreamSettingsScreen extends DivinePanel {
    public StreamSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 5; }

    @Override protected void content() {
        serverNote();
        note("Оверлей стрима в OBS: настроение бога, вердикт, милость душ, кары и реплики. Никаких координат и текстов молитв.", MUTED);
        server(toggle("Оверлей для OBS", "Оверлей видит бога; выкл — не видит ничего.", config.overlayEnabled, v -> { config.overlayEnabled = v; rebuild(); }));
        if (!config.overlayEnabled) return;
        server(toggle("Причины вердикта", "До трёх главных причин, почему бог так решил.", config.overlayReasons, v -> config.overlayReasons = v));
        server(toggle("Милость всех игроков", "Милость каждого в сети; выкл — только стримера.", config.overlayOthersMercy, v -> config.overlayOthersMercy = v));
        server(toggle("Число грехов", "Сколько грехов на душе.", config.overlaySins, v -> config.overlaySins = v));
        note("Выключенная здесь кара или благо на оверлее зовётся «Тайное деяние»: зрители узнают, что пало, только в игре.", MUTED);
        flags("overlayHidden", config.overlayHidden);
    }
}
