package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** "Кары…" from the settings (F9): escalation, combos, lingering curses, the eternal ban and each of the cruel acts on or
 * off. Edits the parent's config; the parent saves it on "Готово". */
public final class WrathSettingsScreen extends Screen {
    /** The cruel acts the player may switch off one by one (the ban has its own switch). */
    static final List<Miracle> CRUEL = List.of(Miracle.BLIGHT, Miracle.CAIN, Miracle.PLAGUES, Miracle.SODOM, Miracle.FLOOD,
            Miracle.BABEL, Miracle.MEMORY_WIPE, Miracle.JUDGEMENT);
    private final Screen parent;
    private final DivineConfig config;

    public WrathSettingsScreen(Screen parent, DivineConfig config) { super(Text.literal("Кары")); this.parent = parent; this.config = config; }

    @Override protected void init() {
        int column = 150, gap = 8, left = this.width / 2 - column - gap / 2, right = this.width / 2 + gap / 2, y = 28, row = 22;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.escalation).build(left, y, column, 20, Text.literal("Кара за карой хуже"), (b, v) -> config.escalation = v));
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(v == 0 ? "нет" : "до " + v)).values(List.of(0, 1, 2))
                .initially(config.comboActs).build(right, y, column, 20, Text.literal("Продолжения"), (b, v) -> config.comboActs = v));
        y += row;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.lingeringCurses).build(left, y, column, 20, Text.literal("Проклятия на дни"), (b, v) -> config.lingeringCurses = v));
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(v + " дн.")).values(List.of(1, 2, 3, 4, 5, 6, 7))
                .initially(config.curseDays).build(right, y, column, 20, Text.literal("Срок"), (b, v) -> config.curseDays = v));
        y += row;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.allowEternalBan).build(left, y, column, 20, Text.literal("Вечное изгнание"), (b, v) -> config.allowEternalBan = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.banBackup).build(right, y, column, 20, Text.literal("Копия мира"), (b, v) -> config.banBackup = v));
        y += row + 32;
        for (int i = 0; i < CRUEL.size(); i++) {
            Miracle miracle = CRUEL.get(i);
            int x = i % 2 == 0 ? left : right;
            addDrawableChild(CyclingButtonWidget.onOffBuilder(!config.disabled.contains(miracle.id())).build(x, y, column, 20, Text.literal(name(miracle)),
                    (b, v) -> { config.disabled.remove(miracle.id()); if (!v) config.disabled.add(miracle.id()); }));
            if (i % 2 == 1) y += row;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close()).dimensions(this.width / 2 - 75, this.height - 28, 150, 20).build());
    }

    static String name(Miracle miracle) {
        return switch (miracle) {
            case BLIGHT -> "Порча земли"; case CAIN -> "Печать Каина"; case PLAGUES -> "Казни египетские";
            case SODOM -> "Содом"; case FLOOD -> "Потоп"; case BABEL -> "Вавилон"; case MEMORY_WIPE -> "Стирание памяти"; case JUDGEMENT -> "Суд в небесах";
            default -> miracle.id();
        };
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title.copy().formatted(Formatting.DARK_RED), this.width / 2, 12, 0xFFFFFF);
        int y = 28 + 22 * 3 + 2;
        String ban = config.allowEternalBan
                ? "Изгнание включено: за четвёртую хулу бог выкинет тебя из мира, и мир больше не впустит. Снять: выключить здесь, файл config/divineblock-unban.txt или -Ddivineblock.unban=1."
                : "Изгнание выключено: бог никогда не изгоняет, а изгнанный прежде снова может войти.";
        context.drawTextWrapped(textRenderer, Text.literal(ban), this.width / 2 - 154, y, 308, config.allowEternalBan ? 0xFF5555 : 0xAAAAAA);
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
