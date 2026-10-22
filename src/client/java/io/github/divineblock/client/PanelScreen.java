package io.github.divineblock.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

/** A settings panel in the look of Viewer Parrots' control panel: a dark backdrop, «Разделы» on the left with the open one
 * dimmed, a gold title, a search over the open section, rows «label — control» with thin separators, gold group headers that
 * fold on a click (as in Mob Talk's F7), a gold scrollbar, the buttons bottom right and the status bottom left.
 * Rows outside the visible band hide their widgets, so a click never reaches a control that is not drawn. */
abstract class PanelScreen extends Screen {
    static final int GOLD = 0xF6CE6B, TEXT = 0xE4E8EF, MUTED = 0xAFC0D5, DIM = 0x8592A3, ERROR = 0xFF8F86, OK = 0xB6E9AE;
    private static final int ROW = 24, HEAD = 22, LINE = 11;
    /** Folded groups («screen class/group»), kept until the game closes. */
    private static final Set<String> FOLDED = new HashSet<>();

    protected final Screen parent;
    protected int left, top, right, bottom;
    protected double scroll;
    protected String query = "";
    protected String message = "";
    protected boolean messageError;
    private final List<Row> rows = new ArrayList<>();
    private final List<ClickableWidget> fixed = new ArrayList<>();
    private boolean rebuilding;
    private Row group;
    private int total;

    private enum Kind { HEADING, ROW, NOTE }

    protected static final class Row {
        private final Kind kind;
        private final String label, help;
        private final List<ClickableWidget> widgets = new ArrayList<>();
        private int offset, height, color = TEXT, valueColor = MUTED, count;
        private Supplier<String> value;
        private String key;
        private boolean folded;
        private Row(Kind kind, String label, String help, int height) { this.kind = kind; this.label = label; this.help = help; this.height = height; }
    }

    protected PanelScreen(Screen parent, String title) { super(Text.literal(title)); this.parent = parent; }

    @Override protected void init() { rebuild(); }

    protected void bounds() {
        left = Math.min(157, Math.max(119, width / 4));
        right = width - 13;
        top = 60;
        bottom = height - 50;
    }

    protected final void rebuild() {
        if (client == null || rebuilding) return;
        rebuilding = true;
        clearChildren();
        rows.clear();
        fixed.clear();
        group = null;
        bounds();
        chrome();
        content();
        finish();
        layoutRows();
        rebuilding = false;
    }

    /** The sidebar, the search and the footer buttons. */
    protected abstract void chrome();
    /** The rows of the open section. */
    protected abstract void content();
    /** «Сохранено» / «Есть изменения» under the sidebar, or empty. */
    protected String badge() { return ""; }
    protected boolean badgeWarning() { return false; }
    protected String sidebarTitle() { return "РАЗДЕЛЫ"; }

    // ---- Chrome ----

    /** One section button of the sidebar; the open one is dimmed, as in the parrots' panel. */
    protected final ButtonWidget section(int index, int count, String label, boolean open, Runnable action) {
        int step = Math.max(17, Math.min(24, (height - 95) / Math.max(1, count)));
        ButtonWidget button = fixed(button(label, 8, 47 + index * step, left - 21, action));
        button.setHeight(Math.min(20, step - 1));
        button.active = !open;
        return button;
    }

    /** A footer button counted from the right edge: slot 0 is the rightmost. */
    protected final ButtonWidget footer(String label, int slot, Runnable action) {
        int size = footerWidth();
        return fixed(button(label, width - 12 - (slot + 1) * size - slot * 6, height - 28, size, action));
    }
    private int footerWidth() { return Math.max(60, Math.min(100, (width - left - 30) / 3)); }

    protected final void search(int x, int y, int size) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, size, 20, Text.literal("Поиск"));
        field.setMaxLength(60);
        field.setPlaceholder(Text.literal("Поиск в этом разделе…").withColor(DIM));
        field.setText(query);
        field.setChangedListener(value -> {
            // The field also reports a cursor move while focus is restored.
            if (query.equals(value)) return;
            query = value;
            scroll = 0;
            rebuild();
            for (ClickableWidget widget : fixed)
                if (widget instanceof TextFieldWidget text && text.getMessage().getString().equals("Поиск")) { setFocused(text); text.setCursorToEnd(false); break; }
        });
        fixed(field);
    }

    protected final <T extends ClickableWidget> T fixed(T widget) { fixed.add(widget); return addDrawableChild(widget); }

    protected final ButtonWidget button(String label, int x, int y, int size, Runnable action) {
        return ButtonWidget.builder(Text.literal(label), pressed -> action.run()).dimensions(x, y, Math.max(18, size), 20).build();
    }

    protected final void notify(String value, boolean error) { message = value == null ? "" : value; messageError = error; }

    // ---- Rows ----

    private static String normalized(String text) { return text.toLowerCase(Locale.ROOT).replace('ё', 'е').strip(); }
    protected final boolean matches(String text) {
        if (query.isBlank()) return true;
        String haystack = normalized(text);
        for (String word : normalized(query).split("\\s+")) if (!word.isEmpty() && !haystack.contains(word)) return false;
        return true;
    }

    /** A gold group header; a click folds or unfolds the rows under it. */
    protected final void heading(String label) {
        Row row = new Row(Kind.HEADING, label, "", HEAD);
        row.key = getClass().getSimpleName() + "/" + label;
        row.folded = FOLDED.contains(row.key);
        rows.add(row);
        group = row;
    }

    /** A row of this group, or null when the search hides it or its group is folded. */
    protected final Row row(String label, String help) { return row(label, help, ""); }
    /** {@code extra}: more text the search finds the row by (the captions of its controls), not shown in the tooltip. */
    private Row row(String label, String help, String extra) {
        if (group != null) group.count++;
        boolean shown = matches(label + " " + help + " " + extra) || group != null && !query.isBlank() && matches(group.label);
        if (!shown || query.isBlank() && group != null && group.folded) return null;
        Row row = new Row(Kind.ROW, label, help, ROW);
        rows.add(row);
        return row;
    }

    protected final void info(String label, String value) { info(label, "", () -> value); }
    /** An info row whose value has its own colour (a warning, an error). */
    protected final void info(String label, String value, int valueColor) {
        Row row = row(label, "", value);
        if (row != null) { row.value = () -> value; row.valueColor = valueColor; }
    }
    protected final void info(String label, String help, Supplier<String> value) {
        Row row = row(label, help, value.get());
        if (row != null) row.value = value;
    }

    /** Explanation text across the panel, wrapped into lines. */
    protected final void note(String text, int color) {
        if (group != null) group.count++;
        boolean shown = matches(text) || group != null && !query.isBlank() && matches(group.label);
        if (!shown || query.isBlank() && group != null && group.folded) return;
        for (OrderedText line : textRenderer.wrapLines(Text.literal(text), Math.max(60, right - left - 14))) {
            StringBuilder plain = new StringBuilder();
            line.accept((index, style, codePoint) -> { plain.appendCodePoint(codePoint); return true; });
            Row row = new Row(Kind.NOTE, plain.toString(), "", LINE);
            row.color = color;
            rows.add(row);
        }
    }

    /** «Label — control(s)»: the controls share the right part of the row. */
    protected final Row control(String label, String help, ClickableWidget... widgets) {
        StringBuilder text = new StringBuilder();
        for (ClickableWidget widget : widgets) text.append(' ').append(widget.getMessage().getString());
        Row row = row(label, help, text.toString());
        if (row == null) return null;
        place(row, controlX(), right - 6 - controlX(), widgets);
        return row;
    }

    /** Controls across the whole row, without a label. */
    protected final Row wide(String help, ClickableWidget... widgets) {
        StringBuilder text = new StringBuilder();
        for (ClickableWidget widget : widgets) text.append(' ').append(widget.getMessage().getString());
        Row row = row(text.toString().strip(), help);
        if (row == null) return null;
        place(row, left + 4, right - 10 - left, widgets);
        return row;
    }

    /** Lays the widgets side by side, each sharing the room in proportion to the width it was built with. */
    private void place(Row row, int x, int size, ClickableWidget... widgets) {
        int gap = 4, free = size - gap * (widgets.length - 1), sum = 0, cursor = x;
        for (ClickableWidget widget : widgets) sum += Math.max(1, widget.getWidth());
        for (int i = 0; i < widgets.length; i++) {
            ClickableWidget widget = widgets[i];
            int each = i == widgets.length - 1 ? x + size - cursor : free * Math.max(1, widget.getWidth()) / sum;
            widget.setX(cursor);
            widget.setWidth(each);
            cursor += each + gap;
            if (!row.help.isBlank() && widget.getTooltip() == null) widget.setTooltip(Tooltip.of(Text.literal(row.label + "\n" + row.help)));
            row.widgets.add(addDrawableChild(widget));
        }
    }

    protected final int controlX() { return left + Math.min(230 * labelPercent() / 45, Math.max(100, (right - left) * labelPercent() / 100)); }
    /** The share of a row the labels take, in percent. */
    protected int labelPercent() { return 45; }

    // ---- Typed controls ----

    protected final CyclingButtonWidget<Boolean> toggle(String label, String help, boolean value, Consumer<Boolean> changed) {
        CyclingButtonWidget<Boolean> widget = CyclingButtonWidget.onOffBuilder(value).omitKeyText()
                .build(0, 0, 100, 20, Text.literal(label), (button, chosen) -> changed.accept(chosen));
        control(label, help, widget);
        return widget;
    }

    protected final <T> CyclingButtonWidget<T> cycle(String label, String help, List<T> values, T initial, Function<T, String> name, Consumer<T> changed) {
        CyclingButtonWidget<T> widget = CyclingButtonWidget.<T>builder(value -> Text.literal(name.apply(value))).values(values)
                .initially(values.contains(initial) ? initial : values.get(0)).omitKeyText()
                .build(0, 0, 100, 20, Text.literal(label), (button, chosen) -> changed.accept(chosen));
        control(label, help, widget);
        return widget;
    }

    protected final ButtonWidget action(String label, String help, String caption, Runnable run) {
        ButtonWidget widget = button(caption, 0, 0, 100, run);
        control(label, help, widget);
        return widget;
    }

    // ---- Layout, scrolling and drawing ----

    private void finish() {
        if (!query.isBlank()) {
            // A header stays only above its matches.
            List<Row> kept = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.kind == Kind.HEADING && (i + 1 >= rows.size() || rows.get(i + 1).kind == Kind.HEADING)) continue;
                kept.add(row);
            }
            rows.clear();
            rows.addAll(kept);
            if (rows.isEmpty()) rows.add(new Row(Kind.NOTE, "Ничего не найдено: «" + query.strip() + "»", "", LINE));
        }
        total = 0;
        for (Row row : rows) { row.offset = total; total += row.height; }
    }

    private void layoutRows() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - (bottom - top))));
        for (Row row : rows) {
            int y = top + row.offset - (int) scroll;
            for (ClickableWidget widget : row.widgets) {
                widget.setY(y + (row.height - 20) / 2);
                widget.visible = y >= top && y + row.height <= bottom;
            }
        }
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= left - 3 && mouseX <= right + 3 && mouseY >= top && mouseY <= bottom) {
            scroll -= verticalAmount * ROW;
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && query.isBlank() && mouseX >= left && mouseX <= right && mouseY >= top && mouseY < bottom) {
            for (Row row : rows) {
                int y = top + row.offset - (int) scroll;
                if (row.kind == Kind.HEADING && mouseY >= y && mouseY < y + row.height) {
                    if (!FOLDED.remove(row.key)) FOLDED.add(row.key);
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    rebuild();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, 0xDD11151C);
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(title.getString(), right - left), left, 14, GOLD);
        context.drawTextWithShadow(textRenderer, sidebarTitle(), 11, 32, MUTED);
        context.fill(left - 3, top - 3, right + 3, bottom + 3, 0xB019202A);
        layoutRows();
        context.enableScissor(left, top, right, bottom);
        Row hovered = null;
        for (Row row : rows) {
            int y = top + row.offset - (int) scroll;
            // Only whole rows are drawn: a cut label without its control reads as a broken row.
            if (y < top || y + row.height > bottom) continue;
            boolean over = mouseX >= left && mouseX < right && mouseY >= Math.max(y, top) && mouseY < Math.min(y + row.height, bottom);
            switch (row.kind) {
                case HEADING -> {
                    if (over && query.isBlank()) context.fill(left, y + 1, right - 4, y + row.height - 2, 0x18FFFFFF);
                    String side = row.count == 0 ? "" : String.valueOf(row.count);
                    int sideWidth = textRenderer.getWidth(side);
                    context.drawTextWithShadow(textRenderer, side, right - 10 - sideWidth, y + 8, DIM);
                    String mark = query.isBlank() ? (row.folded ? "▶ " : "▼ ") : "";
                    context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(mark + row.label, right - left - 24 - sideWidth), left + 5, y + 8, GOLD);
                    context.fill(left + 5, y + row.height - 2, right - 8, y + row.height - 1, 0x665D6570);
                }
                case NOTE -> context.drawTextWithShadow(textRenderer, row.label, left + 5, y + 2, row.color);
                case ROW -> {
                    context.fill(left + 5, y + row.height - 1, right - 8, y + row.height, 0x303F4857);
                    boolean labeled = row.widgets.isEmpty() || row.widgets.get(0).getX() >= controlX() - 1;
                    if (labeled) context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(row.label, Math.max(20, controlX() - left - 12)), left + 5, y + (row.height - 8) / 2, row.color);
                    if (row.value != null) context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(row.value.get(), right - controlX() - 8), controlX(), y + (row.height - 8) / 2, row.valueColor);
                    boolean cut = textRenderer.getWidth(row.label) > Math.max(20, controlX() - left - 12);
                    if (over && labeled && mouseX < controlX() && (cut || !row.help.isBlank())) hovered = row;
                }
            }
            for (ClickableWidget widget : row.widgets) if (widget.visible) widget.render(context, mouseX, mouseY, delta);
        }
        context.disableScissor();
        for (ClickableWidget widget : fixed) widget.render(context, mouseX, mouseY, delta);
        if (total > bottom - top) {
            int track = bottom - top, thumb = Math.max(12, track * track / total);
            int y = top + (int) (scroll / Math.max(1, total - track) * (track - thumb));
            context.fill(right, top, right + 2, bottom, 0x884A5362);
            context.fill(right, y, right + 2, y + thumb, 0xFF000000 | GOLD);
        }
        String badge = badge();
        int firstFooter = fixed.stream().filter(widget -> widget.getY() >= height - 35).mapToInt(ClickableWidget::getX).min().orElse(width - 10);
        if (!badge.isBlank()) context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(badge, Math.max(24, firstFooter - 18)), 11, height - 22, badgeWarning() ? GOLD : MUTED);
        if (!message.isBlank()) {
            // Under the list, above the footer, where the parrots' panel shows its errors.
            int max = right - left;
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(message, max), left, height - 42, messageError ? ERROR : OK);
            if (mouseY >= height - 45 && mouseY < height - 32 && mouseX >= left && textRenderer.getWidth(message) > max)
                context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(message), Math.min(320, width - 20)), mouseX, mouseY);
        }
        if (hovered != null)
            context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(hovered.help.isBlank() ? hovered.label : hovered.label + "\n" + hovered.help), Math.min(320, width - 20)), mouseX, mouseY);
    }

    @Override public boolean shouldPause() { return true; }
}
