package io.github.mobtalk.client.cards;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.mobtalk.core.cards.Card;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/** Рисование стола игры в 21: текстуры (атлас карт — пиксель-арт без сглаживания, стол, рамка, лампа и эффекты — гладко), четырёхугольник
 * с поворотом, оттенком и сложением цветов, карты, фишки, иконки, скруглённые плашки со свечением, кольцо таймера. Всё в координатах GUI.
 * Атлас и раскладка — tools/cards_art.py. */
final class TableDraw {
    private TableDraw() { }
    static final Identifier ATLAS = Identifier.of("mobtalk", "textures/gui/cards.png");
    static final Identifier TABLE = Identifier.of("mobtalk", "textures/gui/card_table.png");
    static final Identifier PORTRAIT = Identifier.of("mobtalk", "textures/gui/card_portrait.png");
    static final Identifier NAMEPLATE = Identifier.of("mobtalk", "textures/gui/card_nameplate.png");
    static final Identifier FX = Identifier.of("mobtalk", "textures/gui/cards_fx.png");
    static final Identifier RAYS = Identifier.of("mobtalk", "textures/gui/card_rays.png");
    static final int ATLAS_SIZE = 512, CARD_W = 32, CARD_H = 48, CHIP_W = 24, CHIP_H = 16;
    /** Ячейки атласа эффектов divine-block (4×4 по 128 из 512). */
    enum Sprite { GLOW, SPARK, STAR, HALO, FEATHER, CONFETTI, STRIP, ASH, SMOKE, BEAM, FLARE, EMBER }
    static final String[] ICONS = {"hit", "stand", "double", "vabank", "split", "catch", "leave", "deal", "unbet", "say", "sit"};

    /** Сглаживание: стол и эффекты гладкие, атлас карт — чёткие пиксели. Раз в кадр: перезагрузка ресурсов сбрасывает фильтр. */
    static void filters() {
        var textures = MinecraftClient.getInstance().getTextureManager();
        for (Identifier id : new Identifier[]{TABLE, PORTRAIT, NAMEPLATE, FX, RAYS}) textures.getTexture(id).setFilter(true, false);
        textures.getTexture(ATLAS).setFilter(false, false);
    }

    /** Прямоугольник текстуры {@code texture} (u, v, ширина, высота в долях 0..1) в (x, y, w, h) с поворотом {@code angle} (радианы)
     * вокруг центра, цветом ARGB и, если {@code additive}, сложением (свет, искры). */
    static void quad(DrawContext context, Identifier texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1,
                     int argb, boolean additive, float angle) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        context.draw(); // Сначала то, что DrawContext накопил: порядок слоёв.
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x + w / 2, y + h / 2, 0);
        if (angle != 0) matrices.multiply(new org.joml.Quaternionf().rotateZ(angle));
        Matrix4f m = matrices.peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        if (additive) RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        else RenderSystem.defaultBlendFunc();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        float hw = w / 2, hh = h / 2;
        b.vertex(m, -hw, -hh, 0).texture(u0, v0).color(argb);
        b.vertex(m, -hw, hh, 0).texture(u0, v1).color(argb);
        b.vertex(m, hw, hh, 0).texture(u1, v1).color(argb);
        b.vertex(m, hw, -hh, 0).texture(u1, v0).color(argb);
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.defaultBlendFunc();
        matrices.pop();
    }
    static void image(DrawContext context, Identifier texture, float x, float y, float w, float h, int argb) {
        quad(context, texture, x, y, w, h, 0, 0, 1, 1, argb, false, 0);
    }
    /** Кусок атласа в пикселях атласа. */
    static void atlas(DrawContext context, float x, float y, float w, float h, int u, int v, int uw, int vh, int argb, float angle) {
        quad(context, ATLAS, x, y, w, h, u / (float) ATLAS_SIZE, v / (float) ATLAS_SIZE, (u + uw) / (float) ATLAS_SIZE, (v + vh) / (float) ATLAS_SIZE, argb, false, angle);
    }
    /** Спрайт эффекта по центру (cx, cy) размером {@code size}, сложением. */
    static void sprite(DrawContext context, Sprite sprite, float cx, float cy, float size, int rgb, float alpha, float angle) {
        sprite(context, sprite, cx, cy, size, size, rgb, alpha, angle);
    }
    static void sprite(DrawContext context, Sprite sprite, float cx, float cy, float w, float h, int rgb, float alpha, float angle) {
        if (alpha <= 0.003f) return;
        int i = sprite.ordinal();
        float inset = 0.5f / 512f;
        float u0 = (i % 4) / 4f + inset, v0 = (i / 4) / 4f + inset, u1 = (i % 4 + 1) / 4f - inset, v1 = (i / 4 + 1) / 4f - inset;
        quad(context, FX, cx - w / 2, cy - h / 2, w, h, u0, v0, u1, v1, argb(rgb, alpha), true, angle);
    }
    /** Мягкая тень-подложка (свечение чёрным, обычное смешивание): под надписью и под сиянием, чтобы золото не зеленело на сукне. */
    static void shade(DrawContext context, float cx, float cy, float w, float h, float alpha) {
        if (alpha <= 0.003f) return;
        float inset = 0.5f / 512f;
        quad(context, FX, cx - w / 2, cy - h / 2, w, h, inset, inset, 0.25f - inset, 0.25f - inset, argb(0x000000, alpha), false, 0);
    }
    static int argb(int rgb, float alpha) { return Math.max(0, Math.min(255, Math.round(alpha * 255))) << 24 | (rgb & 0xFFFFFF); }

    // ---- Карты, фишки, иконки. ----

    /** Карта по коду ({@code code} < 0 — рубашкой) в (x, y) размера {@code scale}, наклон {@code angle}; {@code squeeze} — ширина при
     * перевороте (1 — плоско). Тень рисуется отдельно ({@link #cardShadow}). */
    static void card(DrawContext context, int code, float x, float y, float scale, float angle, float squeeze, int tint) {
        float w = CARD_W * scale, h = CARD_H * scale, sw = w * Math.max(0.02f, squeeze);
        float dx = (w - sw) / 2;
        Card card = Card.of(code);
        if (card == null) atlas(context, x + dx, y, sw, h, 0, 192, CARD_W, CARD_H, tint, angle);
        else atlas(context, x + dx, y, sw, h, (code % 13) * CARD_W, (code / 13) * CARD_H, CARD_W, CARD_H, tint, angle);
    }
    static void cardShadow(DrawContext context, float x, float y, float scale, float angle, float alpha) {
        atlas(context, x + 1.5f * scale - 2 * scale, y + 2.5f * scale - 2 * scale, 36 * scale, 52 * scale, 128, 192, 36, 52, argb(0xFFFFFF, alpha), angle);
    }
    /** Одна фишка номиналом 1, 5 или 9. */
    static void chip(DrawContext context, int value, float x, float y, float scale, int tint) {
        int column = value >= 9 ? 2 : value >= 5 ? 1 : 0;
        atlas(context, x, y, CHIP_W * scale, CHIP_H * scale, column * CHIP_W, 256, CHIP_W, CHIP_H, tint, 0);
    }
    /** Фишки, которыми выкладывают {@code amount}: девятки, пятёрки, единицы (крупные первыми). */
    static int[] chips(int amount) {
        int nines = amount / 9, fives = amount % 9 / 5, ones = amount % 9 % 5;
        int[] out = new int[nines + fives + ones];
        int i = 0;
        for (int k = 0; k < nines; k++) out[i++] = 9;
        for (int k = 0; k < fives; k++) out[i++] = 5;
        for (int k = 0; k < ones; k++) out[i++] = 1;
        return out;
    }
    /** Стопки фишек по центру (cx, cy — низ стопки): до 10 фишек в стопке, лишние — в соседнюю; тень под каждой стопкой. */
    static void stacks(DrawContext context, int amount, float cx, float cy, float scale, float alpha) {
        if (amount <= 0 || alpha <= 0) return;
        int[] chips = chips(amount);
        int perStack = 10, stacks = (chips.length + perStack - 1) / perStack;
        float step = CHIP_W * scale * 0.82f;
        float left = cx - (stacks - 1) * step / 2;
        int tint = argb(0xFFFFFF, alpha);
        for (int s = 0; s < stacks; s++) {
            float sx = left + s * step - CHIP_W * scale / 2;
            atlas(context, sx - 2 * scale, cy - 5 * scale, 28 * scale, 10 * scale, 72, 256, 28, 10, argb(0xFFFFFF, alpha * 0.9f), 0);
            int from = s * perStack, to = Math.min(chips.length, from + perStack);
            for (int i = from; i < to; i++) chip(context, chips[i], sx, cy - CHIP_H * scale + 4 * scale - (i - from) * 3 * scale, scale, tint);
        }
    }
    /** Иконка кнопки 12×12 по имени из {@link #ICONS}. */
    static void icon(DrawContext context, String name, float x, float y, float scale, int tint) {
        for (int i = 0; i < ICONS.length; i++)
            if (ICONS[i].equals(name)) { atlas(context, x, y, 12 * scale, 12 * scale, i * 16, 288, 12, 12, tint, 0); return; }
    }
    static void emerald(DrawContext context, float x, float y, float scale) { atlas(context, x, y, 10 * scale, 10 * scale, 0, 304, 10, 10, 0xFFFFFFFF, 0); }

    // ---- Плашки, свечение, кольцо. ----

    /** Скруглённый прямоугольник заливкой (радиус в пикселях GUI, ступеньками). */
    static void round(DrawContext context, int x0, int y0, int x1, int y1, int r, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        r = Math.max(0, Math.min(r, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt(r * r - (r - i - 0.5) * (r - i - 0.5)));
            context.fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, argb);
            context.fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, argb);
        }
        context.fill(x0, y0 + r, x1, y1 - r, argb);
    }
    /** Бейдж: тёмная плашка, золотая (или своего цвета) обводка и мягкое свечение вокруг ({@code glow} 0..1). */
    static void badge(DrawContext context, int x0, int y0, int x1, int y1, int edge, float glow) {
        int r = (y1 - y0) / 2;
        for (int k = 3; k >= 1; k--) {
            float a = glow * (0.22f / k);
            if (a > 0.01f) round(context, x0 - k * 2, y0 - k * 2, x1 + k * 2, y1 + k * 2, r + k * 2, argb(edge, a));
        }
        round(context, x0 - 1, y0 - 1, x1 + 1, y1 + 1, r + 1, argb(edge, 0.95f));
        round(context, x0, y0, x1, y1, r, 0xF0141820);
        context.fill(x0 + r, y0 + 1, x1 - r, y0 + 2, 0x30FFFFFF); // блик сверху
    }
    /** Кольцо: {@code fraction} дуги по часовой от верха, толщина {@code thick}, точками по окружности. */
    static void ring(DrawContext context, float cx, float cy, float radius, float thick, float fraction, int argb, int track) {
        int steps = Math.max(24, (int) (radius * 7));
        for (int i = 0; i < steps; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / steps;
            boolean lit = i < fraction * steps;
            int color = lit ? argb : track;
            if ((color >>> 24) == 0) continue;
            float px = cx + (float) Math.cos(a) * radius, py = cy + (float) Math.sin(a) * radius;
            context.fill(Math.round(px - thick / 2), Math.round(py - thick / 2), Math.round(px + thick / 2), Math.round(py + thick / 2), color);
        }
    }
    /** Текст крупно: масштаб {@code size}, тень, по центру (cx, cy). */
    static void bigText(DrawContext context, net.minecraft.client.font.TextRenderer font, String text, float cx, float cy, float size, int color, float alpha) {
        if (alpha <= 0.02f) return;
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(cx, cy, 0);
        matrices.scale(size, size, 1);
        int w = font.getWidth(text);
        int a = Math.max(4, Math.min(255, Math.round(alpha * 255))) << 24;
        // Тёмная обводка в четыре стороны и тень ниже: надпись читается на любом фоне.
        for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {1, 1}}) context.drawText(font, text, -w / 2 + o[0], -4 + o[1], a | 0x241404, false);
        context.drawText(font, text, -w / 2, -4, a | (color & 0xFFFFFF), false);
        matrices.pop();
    }
}
