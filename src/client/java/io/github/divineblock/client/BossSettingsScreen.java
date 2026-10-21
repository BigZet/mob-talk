package io.github.divineblock.client;

import io.github.divineblock.boss.BossKind;
import io.github.divineblock.core.DivineConfig;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The sacred bosses (from F9): each one on or off (off brings a buffed vanilla mob instead), their strength and whether
 * their ultimates may break blocks. Edits the parent's config, which is saved when F9 closes. */
public final class BossSettingsScreen extends Screen {
    private final Screen parent;
    private final DivineConfig config;

    public BossSettingsScreen(Screen parent, DivineConfig config) { super(Text.literal("Святые стражи")); this.parent = parent; this.config = config; }

    @Override protected void init() {
        int columns = 3, column = 120, gap = 4, row = 22, y = 30;
        int left = this.width / 2 - (columns * column + (columns - 1) * gap) / 2;
        BossKind[] kinds = BossKind.values();
        for (int i = 0; i < kinds.length; i++) {
            BossKind kind = kinds[i];
            int x = left + (i % columns) * (column + gap), at = y + (i / columns) * row;
            addDrawableChild(CyclingButtonWidget.onOffBuilder(!config.disabledBosses.contains(kind.id)).omitKeyText()
                    .build(x, at, column, 20, Text.literal(kind.title), (b, on) -> {
                        config.disabledBosses.remove(kind.id);
                        if (!on) config.disabledBosses.add(kind.id);
                        b.setMessage(label(kind, on));
                    })).setMessage(label(kind, !config.disabledBosses.contains(kind.id)));
        }
        y += ((kinds.length + columns - 1) / columns) * row + 8;
        int half = (columns * column + (columns - 1) * gap - gap) / 2;
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(v + "%")).values(List.of(50, 75, 100, 150, 200, 300))
                .initially(nearest(config.bossDifficulty)).build(left, y, half, 20, Text.literal("Сила боссов"), (b, v) -> config.bossDifficulty = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.bossBlockDamage).build(left + half + gap, y, half, 20, Text.literal("Ломать блоки"),
                (b, v) -> config.bossBlockDamage = v));
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close()).dimensions(this.width / 2 - 75, this.height - 28, 150, 20).build());
    }

    private static Text label(BossKind kind, boolean on) {
        int cut = kind.title.indexOf(" с ");
        return Text.literal(cut > 0 ? kind.title.substring(0, cut) : kind.title).formatted(on ? Formatting.GOLD : Formatting.DARK_GRAY);
    }

    private static int nearest(int value) {
        int best = 100;
        for (int option : new int[] { 50, 75, 100, 150, 200, 300 }) if (Math.abs(option - value) < Math.abs(best - value)) best = option;
        return best;
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title.copy().formatted(Formatting.GOLD), this.width / 2, 12, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Выключенный босс приходит обычным мобом с его именем").formatted(Formatting.GRAY),
                this.width / 2, this.height - 42, 0xFFFFFF);
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
