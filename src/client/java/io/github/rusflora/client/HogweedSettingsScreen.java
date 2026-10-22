package io.github.rusflora.client;

import io.github.rusflora.hogweed.HogweedConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** The hogweed's settings, drawn like the Viewer Parrots menu (dark panel, gold headings, a search field, rows of label and
 * control) with the settings folded into groups as in the Mob Talk menu: a click on a group's heading folds or unfolds it,
 * and the heading counts its settings and those moved off their defaults. Opens with a parent screen, so the pack's mod hub
 * can call {@code new HogweedSettingsScreen(parent)}. Edits a copy; "Готово" saves it to config/rusflora-hogweed.json and,
 * in single player, applies it at once. */
public final class HogweedSettingsScreen extends Screen {
    private static final int ROW = 30, GOLD = 0xF6CE6B;
    /** Groups shown unfolded; kept until the game closes. */
    private static final Set<String> EXPANDED = new HashSet<>(Set.of("spread", "burn"));
    private static final HogweedConfig DEFAULTS = new HogweedConfig();

    private final Screen parent;
    private final HogweedConfig draft = HogweedConfig.get().copy();
    private final List<Row> rows = new ArrayList<>();
    private final List<ClickableWidget> fixed = new ArrayList<>();
    private int left, top, right, bottom;
    private double scroll;
    private String query = "";

    private static final class Row {
        final int offset;
        final String label, help, group;
        final boolean heading;
        String side = "";
        ClickableWidget control;
        Row(int offset, String label, String help, String group, boolean heading) {
            this.offset = offset; this.label = label; this.help = help; this.group = group; this.heading = heading;
        }
    }

    public HogweedSettingsScreen(Screen parent) {
        super(Text.literal("Борщевик Сосновского"));
        this.parent = parent;
    }

    @Override protected void init() { rebuild(); }

    private void rebuild() {
        clearChildren();
        rows.clear();
        fixed.clear();
        left = Math.max(10, (width - 660) / 2);
        right = width - left;
        top = 61;
        bottom = height - 43;
        search();
        fixed(ButtonWidget.builder(Text.literal("По умолчанию"), b -> { HogweedConfig.apply(new HogweedConfig()); reopen(); })
                .dimensions(right - 226, height - 29, 110, 20).tooltip(Tooltip.of(Text.literal("Вернуть все настройки борщевика к исходным"))).build());
        fixed(ButtonWidget.builder(Text.literal("Готово"), b -> close()).dimensions(right - 110, height - 29, 110, 20).build());

        group("spread", "Разрастание", new boolean[] {draft.spread != DEFAULTS.spread, draft.spreadRadius != DEFAULTS.spreadRadius,
                draft.seedsPerTick != DEFAULTS.seedsPerTick, draft.maxPlantsNearby != DEFAULTS.maxPlantsNearby, draft.growthSpeed != DEFAULTS.growthSpeed});
        toggle("spread", "Разбрасывает семена", "Зрелые зонтики разбрасывают семена, и заросль захватывает землю вокруг.", draft.spread, v -> draft.spread = v);
        slider("spread", "Дальность разлёта", "На сколько блоков летят семена; у воды вдвое дальше.", 1, 16, draft.spreadRadius, " бл.", v -> draft.spreadRadius = v);
        slider("spread", "Семян за раз", "Сколько семян бросает зрелое растение за один случайный тик.", 1, 8, draft.seedsPerTick, "", v -> draft.seedsPerTick = v);
        slider("spread", "Теснота 7×7", "Сколько растений может стоять в квадрате 7×7 вокруг места, куда упало семя.", 1, 49, draft.maxPlantsNearby, "", v -> draft.maxPlantsNearby = v);
        slider("spread", "Скорость роста", "Во сколько раз быстрее обычного растёт: 100% — как задумано.", 10, 400,
                (int) Math.round(draft.growthSpeed * 100), "%", v -> draft.growthSpeed = v / 100.0);

        group("limit", "Предел заросли", new boolean[] {draft.maxPlantsPerChunk != DEFAULTS.maxPlantsPerChunk, draft.maxPlantsPerRegion != DEFAULTS.maxPlantsPerRegion});
        slider("limit", "На чанк", "Больше этого числа растений в одном чанке не будет.", 1, 256, draft.maxPlantsPerChunk, "", v -> draft.maxPlantsPerChunk = v);
        slider("limit", "На область 112×112", "Предел на квадрат 7×7 чанков вокруг упавшего семени: заросль большая, но ограниченная.",
                1, 3000, draft.maxPlantsPerRegion, "", v -> draft.maxPlantsPerRegion = v);

        group("smother", "Вытеснение", new boolean[] {draft.suppressPlants != DEFAULTS.suppressPlants, draft.suppressCrops != DEFAULTS.suppressCrops});
        toggle("smother", "Глушит траву и цветы", "Тень листьев убивает траву, цветы и саженцы, дёрн становится голой землёй.", draft.suppressPlants, v -> draft.suppressPlants = v);
        toggle("smother", "Губит посевы", "Пшеница и другие посевы рядом с зарослью вянут.", draft.suppressCrops, v -> draft.suppressCrops = v);

        group("burn", "Ожог", new boolean[] {draft.burns != DEFAULTS.burns, draft.burnDelaySeconds != DEFAULTS.burnDelaySeconds});
        toggle("burn", "Сок обжигает", "Касание или поломка рукой оставляет сок; потом минуту кожа горит на солнце.", draft.burns, v -> draft.burns = v);
        slider("burn", "До ожога", "Сколько секунд сок ждёт, прежде чем начать обжигать; таймер виден в эффектах.", 0, 120, draft.burnDelaySeconds, " с", v -> draft.burnDelaySeconds = v);

        group("enemies", "Кто с ним борется", new boolean[] {draft.villagersMow != DEFAULTS.villagersMow, draft.cowsEat != DEFAULTS.cowsEat});
        toggle("enemies", "Жители косят", "Жители выкашивают борщевик в 16 блоках от себя, поэтому в деревнях его нет.", draft.villagersMow, v -> draft.villagersMow = v);
        toggle("enemies", "Коровы едят", "Корова замечает борщевик в 10 блоках, подходит и съедает.", draft.cowsEat, v -> draft.cowsEat = v);
        layout();
    }

    /** For the self-test's screenshots: every group unfolded. */
    static void unfoldAll() { EXPANDED.addAll(List.of("spread", "limit", "smother", "burn", "enemies")); }

    /** For the self-test's screenshots: types a search. */
    void searchFor(String text) {
        TextFieldWidget search = null;
        for (ClickableWidget widget : fixed) if (widget instanceof TextFieldWidget field) search = field;
        if (search != null) search.setText(text); // rebuilds the screen, so not inside the loop
    }

    private void reopen() { if (client != null) client.setScreen(new HogweedSettingsScreen(parent)); }

    // ---- Rows ----

    private boolean matches(String text) { return query.isBlank() || text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }
    /** A search shows matching settings of every group, unfolded, without the headings. */
    private boolean shown(String group) { return !query.isBlank() || EXPANDED.contains(group); }

    private void group(String id, String label, boolean[] changed) {
        if (!query.isBlank()) return;
        Row row = new Row(rows.size() * ROW, (EXPANDED.contains(id) ? "▼ " : "▶ ") + label, "", id, true);
        int moved = 0;
        for (boolean c : changed) if (c) moved++;
        row.side = changed.length + " " + (changed.length < 5 ? "настройки" : "настроек") + (moved == 0 ? "" : ", изменено " + moved);
        rows.add(row);
    }

    private Row setting(String group, String label, String help) {
        if (!shown(group) || !matches(label + " " + help)) return null;
        Row row = new Row(rows.size() * ROW, label, help, group, false);
        rows.add(row);
        return row;
    }

    private int controlX() { return left + Math.min(260, Math.max(90, (right - left) * 45 / 100)); }
    private int controlWidth() { return Math.max(60, right - controlX() - 10); }

    private void toggle(String group, String label, String help, boolean value, Consumer<Boolean> changed) {
        Row row = setting(group, label, help);
        if (row == null) return;
        row.control = CyclingButtonWidget.onOffBuilder(Text.literal("вкл"), Text.literal("выкл")).initially(value).omitKeyText()
                .build(controlX(), 0, controlWidth(), 20, Text.literal(label), (b, v) -> changed.accept(v));
        attach(row);
    }

    private void slider(String group, String label, String help, int min, int max, int value, String unit, IntConsumer changed) {
        Row row = setting(group, label, help);
        if (row == null) return;
        row.control = new IntSlider(controlX(), controlWidth(), min, max, value, unit, changed);
        attach(row);
    }

    private void attach(Row row) {
        row.control.setTooltip(Tooltip.of(Text.literal(row.label + "\n" + row.help)));
        addDrawableChild(row.control);
    }

    private <T extends ClickableWidget> T fixed(T widget) { fixed.add(widget); return addDrawableChild(widget); }

    private void search() {
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, 33, right - left, 20, Text.literal("Поиск"));
        field.setPlaceholder(Text.literal("Поиск по настройкам борщевика…"));
        field.setText(query);
        field.setChangedListener(value -> {
            if (query.equals(value)) return;
            query = value;
            scroll = 0;
            rebuild();
            for (ClickableWidget widget : List.copyOf(fixed)) if (widget instanceof TextFieldWidget text) { setFocused(text); text.setCursorToEnd(false); }
        });
        fixed(field);
    }

    private int contentHeight() { return rows.size() * ROW; }

    private void layout() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight() - (bottom - top))));
        for (Row row : rows) {
            if (row.control == null) continue;
            int y = top + row.offset - (int) scroll;
            row.control.setY(y + 5);
            row.control.visible = y >= top && y + 26 <= bottom;
        }
    }

    // ---- Input ----

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
            for (Row row : rows) {
                int y = top + row.offset - (int) scroll;
                if (row.heading && mouseY >= y && mouseY < y + ROW) {
                    if (!EXPANDED.remove(row.group)) EXPANDED.add(row.group);
                    rebuild();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
            scroll -= verticalAmount * ROW;
            layout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---- Drawing (the Viewer Parrots look) ----

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, 0xDD11151C);
        context.drawTextWithShadow(textRenderer, title, left, 14, GOLD);
        context.fill(left - 3, top - 3, right + 3, bottom + 3, 0xB019202A);
        layout();
        context.enableScissor(left, top, right, bottom);
        Row hovered = null;
        for (Row row : rows) {
            int y = top + row.offset - (int) scroll;
            if (y + ROW < top || y > bottom) continue;
            boolean over = mouseX >= left && mouseX <= right && mouseY >= Math.max(y, top) && mouseY < Math.min(y + ROW, bottom);
            if (row.heading) {
                if (over) context.fill(left, y + 2, right - 5, y + ROW - 2, 0x22F6CE6B);
                context.drawTextWithShadow(textRenderer, row.label, left + 5, y + 10, GOLD);
                context.drawTextWithShadow(textRenderer, row.side, right - 10 - textRenderer.getWidth(row.side), y + 10, 0x8C97A8);
                context.fill(left + 5, y + 24, right - 6, y + 25, 0x665D6570);
            } else {
                context.fill(left, y + ROW - 1, right - 5, y + ROW, 0x303F4857);
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(row.label, Math.max(20, controlX() - left - 22)), left + 17, y + 11, 0xE4E8EF);
                if (over && mouseX < controlX()) hovered = row;
            }
            if (row.control != null && row.control.visible) row.control.render(context, mouseX, mouseY, delta);
        }
        context.disableScissor();
        for (ClickableWidget widget : fixed) widget.render(context, mouseX, mouseY, delta);
        if (contentHeight() > bottom - top) {
            int track = bottom - top, thumb = Math.max(12, track * track / contentHeight());
            int y = top + (int) (scroll / Math.max(1, contentHeight() - track) * (track - thumb));
            context.fill(right, top, right + 2, bottom, 0x884A5362);
            context.fill(right, y, right + 2, y + thumb, 0xFF000000 | GOLD);
        }
        if (rows.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, "Ничего не найдено", (left + right) / 2, top + 14, 0x8C97A8);
        String footer = "config/rusflora-hogweed.json";
        if (textRenderer.getWidth(footer) <= right - 236 - left)
            context.drawTextWithShadow(textRenderer, footer, left, height - 23, 0x8C97A8);
        if (hovered != null) context.drawTooltip(textRenderer, Text.literal(hovered.label + "\n" + hovered.help), mouseX, mouseY);
    }

    @Override public boolean shouldPause() { return true; }

    @Override public void close() {
        HogweedConfig.apply(draft);
        if (client != null) client.setScreen(parent);
    }

    /** A whole-number slider showing its value and unit. */
    private static final class IntSlider extends SliderWidget {
        private final int min, max;
        private final String unit;
        private final IntConsumer changed;

        IntSlider(int x, int width, int min, int max, int value, String unit, IntConsumer changed) {
            super(x, 0, width, 20, Text.empty(), (double) (Math.clamp(value, min, max) - min) / (max - min));
            this.min = min; this.max = max; this.unit = unit; this.changed = changed;
            updateMessage();
        }

        private int current() { return min + (int) Math.round(value * (max - min)); }
        @Override protected void updateMessage() { setMessage(Text.literal(current() + unit)); }
        @Override protected void applyValue() { changed.accept(current()); }
    }
}
