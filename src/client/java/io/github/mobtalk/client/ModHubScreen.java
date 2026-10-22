package io.github.mobtalk.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

/** One place in the pause menu for every mod of the pack: each mod's own settings screen, and every non-vanilla hotkey
 * with a warning when two bindings share a key. The other mods are optional and reached without depending on them:
 * Divine Block's screen by its public constructor, Viewer Parrots' panel by its own hotkey. */
final class ModHubScreen extends Screen {
    private static final Set<String> VANILLA = Set.of(KeyBinding.MOVEMENT_CATEGORY, KeyBinding.MISC_CATEGORY,
            KeyBinding.MULTIPLAYER_CATEGORY, KeyBinding.GAMEPLAY_CATEGORY, KeyBinding.INVENTORY_CATEGORY,
            KeyBinding.CREATIVE_CATEGORY, KeyBinding.UI_CATEGORY);
    private static final String DIVINE_SCREEN = "io.github.divineblock.client.DivineSettingsScreen";
    private static final String PARROT_PANEL_KEY = "Панель попугаев";

    private final Screen parent;
    private final Runnable mobTalkSettings;
    private final List<Line> lines = new ArrayList<>();
    private int keysTop;

    private record Line(Text text, boolean conflict) { }

    ModHubScreen(Screen parent, Runnable mobTalkSettings) {
        super(Text.literal("Моды сборки"));
        this.parent = parent;
        this.mobTalkSettings = mobTalkSettings;
    }

    /** True when another mod of the pack is installed, so the pause menu shows the hub instead of the single Mob Talk button. */
    static boolean packInstalled() {
        FabricLoader loader = FabricLoader.getInstance();
        return Arrays.asList("divineblock", "viewerparrots", "rusflora", "parrotcages").stream().anyMatch(loader::isModLoaded);
    }

    @Override protected void init() {
        int x = width / 2 - 110, y = 34;
        addDrawableChild(ButtonWidget.builder(Text.literal("Mob Talk: настройки (F7)"), b -> mobTalkSettings.run())
                .dimensions(x, y, 220, 20).build());
        y += 24;
        if (FabricLoader.getInstance().isModLoaded("divineblock")) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Божественный блок: настройки (F9)"), b -> openDivine())
                    .dimensions(x, y, 220, 20).build());
            y += 24;
        }
        KeyBinding parrots = find(PARROT_PANEL_KEY);
        if (FabricLoader.getInstance().isModLoaded("viewerparrots") && parrots != null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Попугаи: панель (" + parrots.getBoundKeyLocalizedText().getString() + ")"),
                    b -> pressLater(parrots)).dimensions(x, y, 220, 20).build());
            y += 24;
        }
        // Борщевик своего экрана не имеет: его настройки — файл, открываем его в системном редакторе.
        java.nio.file.Path hogweed = FabricLoader.getInstance().getConfigDir().resolve("rusflora-hogweed.json");
        if (FabricLoader.getInstance().isModLoaded("rusflora") && java.nio.file.Files.isRegularFile(hogweed)) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Борщевик: файл настроек"), b -> net.minecraft.util.Util.getOperatingSystem().open(hogweed.toFile()))
                    .dimensions(x, y, 220, 20).build());
            y += 24;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close()).dimensions(x, height - 28, 220, 20).build());
        keysTop = y + 8;
        collectKeys();
    }

    private void collectKeys() {
        lines.clear();
        KeyBinding[] all = client.options.allKeys;
        Map<String, List<KeyBinding>> byCategory = new LinkedHashMap<>();
        for (KeyBinding key : all) if (!VANILLA.contains(key.getCategory())) byCategory.computeIfAbsent(key.getCategory(), c -> new ArrayList<>()).add(key);
        for (var entry : byCategory.entrySet()) {
            lines.add(new Line(Text.translatable(entry.getKey()).copy().append(":"), false));
            for (KeyBinding key : entry.getValue()) {
                String clash = clash(key, all);
                String bound = key.isUnbound() ? "не назначена" : key.getBoundKeyLocalizedText().getString();
                lines.add(new Line(Text.literal("  " + Text.translatable(key.getTranslationKey()).getString() + " — " + bound
                        + (clash == null ? "" : "  (совпадает с «" + clash + "»)")), clash != null));
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

    private void openDivine() {
        try {
            Screen screen = (Screen) Class.forName(DIVINE_SCREEN).getConstructor(Screen.class).newInstance(this);
            client.setScreen(screen);
        } catch (ReflectiveOperationException | ClassCastException missing) {
            KeyBinding settings = find("key.divineblock.settings");
            if (settings != null) pressLater(settings);
        }
    }

    /** Closes the menu and presses the mod's own hotkey on the next tick, as the player would. */
    private void pressLater(KeyBinding key) {
        client.setScreen(null);
        InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(key);
        client.execute(() -> KeyBinding.onKeyPressed(bound));
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        int top = keysTop, bottom = height - 34;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Клавиши модов (переназначить: Настройки → Управление)"), width / 2, top, 0xFFD27F);
        top += 12;
        // Two columns when the list does not fit in one.
        int rows = Math.max(1, (bottom - top) / 10);
        int columns = lines.size() > rows && width >= 520 ? 2 : 1;
        int columnWidth = columns == 2 ? (width - 24) / 2 : Math.min(width - 16, 360);
        int left = columns == 2 ? 12 : (width - columnWidth) / 2;
        for (int i = 0; i < lines.size(); i++) {
            int column = i / rows, row = i % rows;
            if (column >= columns) { context.drawTextWithShadow(textRenderer, Text.literal("…"), left + columnWidth * (columns - 1), bottom, 0xA0A0A0); break; }
            Line line = lines.get(i);
            context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(line.text().getString(), columnWidth - 6)),
                    left + column * columnWidth, top + row * 10, line.conflict() ? 0xFF6060 : 0xE0E0E0);
        }
    }

    @Override public void close() { client.setScreen(parent); }
}
