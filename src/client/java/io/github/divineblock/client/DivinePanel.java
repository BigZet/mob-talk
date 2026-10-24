package io.github.divineblock.client;

import com.google.gson.Gson;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.DivineSettings;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;

/** The pages of the god's settings (F9) as one panel: «Бог», «Голос и микрофон», «Кары», «Боссы», «Деяния», «Бог на стриме» in the sidebar, each its
 * own screen over one shared config. Opened from F9, «Закрыть» and Esc save the config and leave the panel, «Применить» saves
 * and stays; a page opened on its own (a self-test) returns to its parent without saving, as before. */
abstract class DivinePanel extends PanelScreen {
    static final String TITLE = "Божественный блок — панель управления";
    private static final String[] SECTIONS = { "Бог", "Голос и микрофон", "Кары", "Боссы", "Деяния", "Бог на стриме" };
    private static final Gson GSON = new Gson();
    protected final DivineConfig config;
    /** F9 itself when this page was reached from it, else null. */
    protected final DivineSettingsScreen main;
    /** Where the panel goes when closed: the screen F9 or the page was opened from. */
    protected final Screen exit;
    /** The config as last saved, shared by the pages, for «Есть изменения». */
    private final String[] saved;

    DivinePanel(Screen parent, DivineConfig config) {
        super(parent, TITLE);
        this.config = config;
        this.exit = parent instanceof DivinePanel page ? page.exit : parent;
        if (parent instanceof DivinePanel page) { main = this instanceof DivineSettingsScreen self ? self : page.main; saved = page.saved; }
        else { main = this instanceof DivineSettingsScreen self ? self : null; saved = new String[] { GSON.toJson(config) }; }
    }

    /** A sidebar wide enough for «Голос и микрофон». */
    @Override protected void bounds() {
        super.bounds();
        left = Math.min(170, Math.max(136, width * 3 / 10));
    }

    /** This page's place in the sidebar. */
    protected abstract int section();

    @Override protected void chrome() {
        for (int i = 0; i < SECTIONS.length; i++) {
            int chosen = i;
            section(i, SECTIONS.length, SECTIONS[i], i == section(), () -> open(chosen));
        }
        search(left, 34, right - left);
        footer("Закрыть", 0, this::close);
        footer("Применить", 1, this::apply);
    }

    private void open(int index) {
        if (index == section()) return;
        switch (index) {
            case 0 -> client.setScreen(main != null ? main : new DivineSettingsScreen(this, config));
            case 1 -> client.setScreen(new VoiceSettingsScreen(this, config));
            case 2 -> client.setScreen(new WrathSettingsScreen(this, config));
            case 3 -> client.setScreen(new BossSettingsScreen(this, config));
            case 4 -> client.setScreen(new DeedsSettingsScreen(this, config));
            default -> client.setScreen(new StreamSettingsScreen(this, config));
        }
    }

    /** Writes the config and reloads it in the game, as closing F9 always did. */
    protected final boolean save() {
        try {
            // On a server only the personal fields are the player's; an operator's server fields go to the server.
            if (ServerSettings.remote()) ServerSettings.save(config);
            else { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); }
            io.github.divineblock.client.fx.FxQuality.reload();
            saved[0] = GSON.toJson(config);
            return true;
        } catch (IOException failure) {
            DivineBlockMod.LOG.warn("Божественный блок: настройки не сохранены", failure);
            notify("Настройки не сохранены: " + failure.getMessage(), true);
            return false;
        }
    }

    /** A control of a server field: inactive on a server for a player who is not an operator. */
    protected final <T extends net.minecraft.client.gui.widget.ClickableWidget> T server(T widget) {
        if (ServerSettings.locked()) widget.active = false;
        return widget;
    }

    /** A {@code flags} setting ({@link DivineSettings.Type#FLAGS}): its groups as headings, at the top of each «Вся группа»
     * with «Включить» and «Выключить», then a toggle per option. {@code off} is the config's list of ids switched off; the
     * panel's search finds options by title, help and group. */
    protected final void flags(String key, List<String> off) {
        DivineSettings.Setting setting = DivineSettings.setting(key);
        if (setting == null) return;
        String group = null;
        for (DivineSettings.Flag flag : setting.flags()) {
            if (!flag.group().equals(group)) {
                group = flag.group();
                heading(flag.groupTitle());
                String members = group;
                List<String> ids = setting.flags().stream().filter(f -> f.group().equals(members)).map(DivineSettings.Flag::id).toList();
                long on = ids.stream().filter(id -> !off.contains(id)).count();
                ButtonWidget all = server(button("Включить", 0, 0, 60, () -> { off.removeAll(ids); rebuild(); }));
                ButtonWidget none = server(button("Выключить", 0, 0, 60, () -> { for (String id : ids) if (!off.contains(id)) off.add(id); rebuild(); }));
                control("Вся группа: включено " + on + " из " + ids.size(), "Включить или выключить все деяния группы «" + flag.groupTitle() + "» разом.", all, none);
            }
            server(toggle(flag.title(), flag.help(), !off.contains(flag.id()), v -> { off.remove(flag.id()); if (!v) off.add(flag.id()); }));
        }
    }

    /** At the top of a page with server fields, on a server: who sets them. */
    protected final void serverNote() {
        if (ServerSettings.locked()) note("Сервер: настройки бога задаёт админ, здесь они только для чтения. Твои — голос, микрофон, эффекты и тряска.", MUTED);
        else if (ServerSettings.remote()) note("Сервер: ты оператор, серверные настройки бога уйдут на сервер при сохранении.", MUTED);
    }

    private void apply() { if (save()) notify("Сохранено", false); }

    private boolean dirty() { return !GSON.toJson(config).equals(saved[0]); }
    @Override protected String badge() { return dirty() ? "Есть изменения" : "Сохранено"; }
    @Override protected boolean badgeWarning() { return dirty(); }

    /** From a page reached through F9: save and leave the panel the way F9 does. */
    @Override public void close() {
        if (main != null && main != this) { main.close(); return; }
        if (client != null) client.setScreen(exit);
    }
}
