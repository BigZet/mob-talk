package io.github.mobtalk.client;

import io.github.mobtalk.client.settings.PanelScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

/** One place in the pause menu for every mod of the pack, in the panel style of Viewer Parrots: the mods on the left, each
 * opening its own settings, and on the right every non-vanilla hotkey under its mod's folding header, red when two bindings
 * share a key. The other mods are optional and reached without depending on them: Divine Block's and Rusflora's screens by
 * their public {@code (Screen parent)} constructors, Viewer Parrots' panel by its own hotkey. */
final class ModHubScreen extends PanelScreen {
    private static final Set<String> VANILLA = Set.of(KeyBinding.MOVEMENT_CATEGORY, KeyBinding.MISC_CATEGORY,
            KeyBinding.MULTIPLAYER_CATEGORY, KeyBinding.GAMEPLAY_CATEGORY, KeyBinding.INVENTORY_CATEGORY,
            KeyBinding.CREATIVE_CATEGORY, KeyBinding.UI_CATEGORY);
    private static final String DIVINE_SCREEN = "io.github.divineblock.client.DivineSettingsScreen";
    /** Rusflora's settings screen, opened by reflection with its {@code (Screen parent)} constructor; with an older Rusflora
     * that lacks it, the button opens the settings file as before. */
    static final String HOGWEED_SCREEN = "io.github.rusflora.client.HogweedSettingsScreen";
    private static final String PARROT_PANEL_KEY = "Панель попугаев";

    private final Runnable mobTalkSettings;

    ModHubScreen(Screen parent, Runnable mobTalkSettings) {
        super(parent, "Моды сборки — панель управления");
        this.mobTalkSettings = mobTalkSettings;
    }

    /** True when another mod of the pack is installed, so the pause menu shows the hub instead of the single Mob Talk button. */
    static boolean packInstalled() {
        FabricLoader loader = FabricLoader.getInstance();
        return Arrays.asList("divineblock", "viewerparrots", "rusflora", "parrotcages").stream().anyMatch(loader::isModLoaded);
    }

    /** A wider sidebar than the settings panels: it holds the mods' names. */
    @Override protected void bounds() {
        super.bounds();
        left = Math.min(180, Math.max(140, width * 3 / 10));
    }

    @Override protected String sidebarTitle() { return "МОДЫ"; }
    /** Action names are long and keys short. */
    @Override protected int labelPercent() { return 66; }

    @Override protected void chrome() {
        // The labels start with the mod's name: the pack self-test presses them by it.
        List<String> labels = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Mob Talk"); hints.add("Настройки Mob Talk (" + bound("key.mobtalk.settings", "F7") + ")"); actions.add(mobTalkSettings);
        if (FabricLoader.getInstance().isModLoaded("divineblock")) {
            labels.add("Божественный блок"); hints.add("Настройки бога (" + bound("key.divineblock.settings", "F9") + ")"); actions.add(this::openDivine);
        }
        KeyBinding parrots = find(PARROT_PANEL_KEY);
        if (FabricLoader.getInstance().isModLoaded("viewerparrots") && parrots != null) {
            labels.add("Попугаи"); hints.add("Панель Viewer Parrots (" + parrots.getBoundKeyLocalizedText().getString() + ")"); actions.add(() -> pressLater(parrots));
        }
        Path hogweed = FabricLoader.getInstance().getConfigDir().resolve("rusflora-hogweed.json");
        if (FabricLoader.getInstance().isModLoaded("rusflora") && (exists(HOGWEED_SCREEN) || Files.isRegularFile(hogweed))) {
            labels.add("Борщевик"); hints.add(exists(HOGWEED_SCREEN) ? "Настройки борщевика" : "Файл настроек борщевика: откроется в редакторе"); actions.add(() -> openHogweed(hogweed));
        }
        for (int i = 0; i < labels.size(); i++) {
            ButtonWidget button = section(i, Math.max(4, labels.size()), labels.get(i), false, actions.get(i));
            button.setTooltip(Tooltip.of(Text.literal(hints.get(i))));
        }
        search(left, 34, right - left);
        footer("Закрыть", 0, this::close);
    }

    @Override protected void content() {
        note("Клавиши модов сборки. Переназначить: Настройки → Управление. Красным — клавиша занята другим действием.", MUTED);
        KeyBinding[] all = client.options.allKeys;
        Map<String, List<KeyBinding>> byCategory = new LinkedHashMap<>();
        for (KeyBinding key : all) if (!VANILLA.contains(key.getCategory())) byCategory.computeIfAbsent(key.getCategory(), c -> new ArrayList<>()).add(key);
        for (var entry : byCategory.entrySet()) {
            heading(Text.translatable(entry.getKey()).getString());
            for (KeyBinding key : entry.getValue()) {
                String clash = clash(key, all);
                String bound = key.isUnbound() ? "не назначена" : key.getBoundKeyLocalizedText().getString();
                String name = Text.translatable(key.getTranslationKey()).getString();
                if (clash == null) info(name, bound, key.isUnbound() ? DIM : MUTED);
                else info(name, bound + " — совпадает с «" + clash + "»", ERROR);
            }
        }
    }

    /** The first other binding on the same key, if any. */
    private static String clash(KeyBinding key, KeyBinding[] all) {
        if (key.isUnbound()) return null;
        for (KeyBinding other : all)
            if (other != key && !other.isUnbound() && other.equals(key)) return Text.translatable(other.getTranslationKey()).getString();
        return null;
    }

    private static KeyBinding find(String translationKey) {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        for (KeyBinding key : client.options.allKeys) if (translationKey.equals(key.getTranslationKey())) return key;
        return null;
    }

    private static String bound(String translationKey, String fallback) {
        KeyBinding key = find(translationKey);
        return key == null ? fallback : key.isUnbound() ? "клавиша не назначена" : key.getBoundKeyLocalizedText().getString();
    }

    static boolean exists(String className) {
        try { Class.forName(className, false, ModHubScreen.class.getClassLoader()); return true; }
        catch (ClassNotFoundException | LinkageError missing) { return false; }
    }

    private void openDivine() {
        try {
            Screen screen = (Screen) Class.forName(DIVINE_SCREEN).getConstructor(Screen.class).newInstance(this);
            client.setScreen(screen);
        } catch (ReflectiveOperationException | ClassCastException missing) {
            KeyBinding settings = find("key.divineblock.settings");
            if (settings != null) pressLater(settings);
        }
    }

    /** Rusflora's own screen when it has one; otherwise its settings file in the system editor. */
    private void openHogweed(Path file) {
        try {
            client.setScreen((Screen) Class.forName(HOGWEED_SCREEN).getConstructor(Screen.class).newInstance(this));
        } catch (ReflectiveOperationException | ClassCastException | LinkageError missing) {
            if (Files.isRegularFile(file)) net.minecraft.util.Util.getOperatingSystem().open(file.toFile());
            else notify("У Борщевика нет ни экрана настроек, ни файла config/rusflora-hogweed.json", true);
        }
    }

    /** Closes the menu and presses the mod's own hotkey on the next tick, as the player would. */
    private void pressLater(KeyBinding key) {
        client.setScreen(null);
        InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(key);
        client.execute(() -> KeyBinding.onKeyPressed(bound));
    }

    @Override public void close() { client.setScreen(parent); }
}
