package io.github.mobtalk.client.cards;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.text.Text;

/** Кнопка стола в стиле меню модов (тёмно-синяя плашка, золотой кант): иконка из атласа, подпись, подсветка и свечение при наведении,
 * мягкое мерцание у кнопки, которую ждут (ход игрока), щелчок. {@code chip} > 0 — вместо иконки фишка этого номинала. */
final class TableButton extends ButtonWidget {
    private static final int GOLD = 0xF6CE6B;
    private final String icon;
    private final int chip;
    /** Кнопку ждут: мягкое золотое мерцание. */
    boolean urgent;
    /** Красная кнопка (Ловлю!). */
    boolean alarm;

    TableButton(int x, int y, int w, int h, String label, String icon, int chip, PressAction press) {
        super(x, y, w, h, Text.literal(label), press, DEFAULT_NARRATION_SUPPLIER);
        this.icon = icon; this.chip = chip;
    }

    @Override protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int x0 = getX(), y0 = getY(), x1 = x0 + width, y1 = y0 + height;
        boolean hover = active && isHovered();
        long now = System.currentTimeMillis();
        int edge = alarm ? 0xFF6A5A : GOLD;
        float glow = !active ? 0 : hover ? 1 : urgent ? 0.45f + 0.35f * (float) Math.sin(now / 220.0) : 0;
        if (glow > 0) for (int k = 3; k >= 1; k--) TableDraw.round(context, x0 - k, y0 - k, x1 + k, y1 + k, 4 + k, TableDraw.argb(edge, glow * 0.18f / k * 2));
        TableDraw.round(context, x0, y0, x1, y1, 4, active ? TableDraw.argb(edge, hover ? 1f : 0.8f) : 0xFF3A404C);
        int fill = !active ? 0xF01A1D24 : alarm ? (hover ? 0xF0602028 : 0xF0401820) : hover ? 0xF0263246 : 0xF0141A26;
        TableDraw.round(context, x0 + 1, y0 + 1, x1 - 1, y1 - 1, 3, fill);
        context.fill(x0 + 3, y0 + 2, x1 - 3, y0 + 3, active ? 0x30FFFFFF : 0x10FFFFFF);
        var font = MinecraftClient.getInstance().textRenderer;
        String label = getMessage().getString();
        // Узкая кнопка: подпись обрезается, иконка остаётся.
        int room = width - 8 - (icon != null || chip > 0 ? 13 : 0);
        var fontForTrim = MinecraftClient.getInstance().textRenderer;
        if (fontForTrim.getWidth(label) > room) label = fontForTrim.trimToWidth(label, Math.max(0, room - fontForTrim.getWidth("…"))) + "…";
        int textW = font.getWidth(label);
        boolean hasIcon = icon != null || chip > 0;
        int iconW = hasIcon ? 13 : 0;
        int start = x0 + (width - textW - iconW) / 2;
        int tint = active ? 0xFFFFFFFF : 0x80FFFFFF;
        if (chip > 0) TableDraw.chip(context, chip, start - 1, y0 + (height - 11) / 2f, 0.55f, tint);
        else if (icon != null) TableDraw.icon(context, icon, start, y0 + (height - 12) / 2f, 1, tint);
        int color = !active ? 0xFF7A808C : hover ? 0xFFFFF1C6 : alarm ? 0xFFFFD0C8 : 0xFFE4E8EF;
        context.drawTextWithShadow(font, label, start + iconW, y0 + (height - 8) / 2, color);
    }

    @Override public void playDownSound(SoundManager sounds) { TableSounds.click(); }
}
