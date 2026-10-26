package io.github.mobtalk.client.cards;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.DrawContext;

/** Эффекты стола в координатах экрана: искры, звёзды, пылинки в луче лампы, вспышки, лучи золотого очка. Каждый слой — спрайт атласа
 * эффектов, сложение цветов; время в миллисекундах. Частицы с отложенным стартом ждут своего момента в таймлайне. */
final class TableFx {
    private final Random random = new Random();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Burst> bursts = new ArrayList<>();
    /** Множитель скорости эффектов: 1 обычная, больше — быстрее; 0 — без анимаций (эффекты не создаются). */
    float speed = 1;
    /** Масштаб интерфейса окна: размеры надписей кратны пикселю. */
    float gui = 2;

    static final class Particle {
        TableDraw.Sprite sprite;
        float x, y, vx, vy, gravity, size, grow, angle, spin, drag = 1;
        int rgb;
        float alpha;
        long start, life;
        boolean flicker;
    }
    /** Крупный эффект во времени: лучи, сияние, вспышка, надпись. */
    record Burst(Kind kind, float x, float y, float size, int rgb, long start, long life, String text) {
        enum Kind { RAYS, GLOW, FLASH, TEXT, RING }
    }

    void clear() { particles.clear(); bursts.clear(); }
    boolean busy(long now) { for (Burst b : bursts) if (now < b.start + b.life) return true; return false; }

    Particle add(TableDraw.Sprite sprite, float x, float y, float vx, float vy, float size, int rgb, float alpha, long start, long life) {
        Particle p = new Particle();
        p.sprite = sprite; p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.size = size; p.rgb = rgb; p.alpha = alpha;
        p.start = start; p.life = Math.max(1, (long) (life / Math.max(speed, 0.01f))); p.angle = random.nextFloat() * 6.28f;
        if (speed > 0) particles.add(p); // Анимации выключены (F7) — без искр.
        return p;
    }
    void burst(Burst.Kind kind, float x, float y, float size, int rgb, long start, long life, String text) {
        // Без анимаций остаются только надписи («Перебор!», «+5»), у них обычная длительность.
        if (speed <= 0 && kind != Burst.Kind.TEXT) return;
        bursts.add(new Burst(kind, x, y, size, rgb, start, Math.max(1, (long) (life / (speed <= 0 ? 1 : speed))), text));
    }

    /** Искры во все стороны: золото, звёзды и угольки с гравитацией. */
    void sparks(float x, float y, int count, float power, int rgb, long start) {
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            float v = power * (0.35f + random.nextFloat());
            TableDraw.Sprite s = i % 4 == 0 ? TableDraw.Sprite.STAR : i % 3 == 0 ? TableDraw.Sprite.EMBER : TableDraw.Sprite.SPARK;
            Particle p = add(s, x, y, (float) Math.cos(a) * v, (float) Math.sin(a) * v - power * 0.3f, 6 + random.nextFloat() * 8,
                    i % 5 == 0 ? 0xFFFFFF : rgb, 1, start + random.nextInt(120), 500 + random.nextInt(700));
            p.gravity = power * 0.9f;
            p.drag = 0.985f;
            p.spin = (random.nextFloat() - 0.5f) * 8;
            p.flicker = true;
        }
    }
    /** Пылинка в луче лампы: медленно плывёт, мерцает. */
    void dust(float x0, float x1, float y0, float y1, long now) {
        Particle p = add(TableDraw.Sprite.EMBER, x0 + random.nextFloat() * (x1 - x0), y0 + random.nextFloat() * (y1 - y0),
                (random.nextFloat() - 0.5f) * 4, 2 + random.nextFloat() * 5, 4f + random.nextFloat() * 4f, 0xFFE6B4, 0.55f + random.nextFloat() * 0.35f,
                now, 3000 + random.nextInt(3000));
        p.flicker = true;
    }
    int dustCount() { int n = 0; for (Particle p : particles) if (p.sprite == TableDraw.Sprite.EMBER && p.gravity == 0) n++; return n; }

    /** Рисует всё, что идёт сейчас; отжившее удаляется. */
    /** Слой под картами: лучи, сияние, кольца. Рисуется до карт, чтобы свет не засвечивал их. */
    void renderUnder(DrawContext context, net.minecraft.client.font.TextRenderer font, long now) {
        for (Burst b : bursts) {
            if (now < b.start || !under(b)) continue;
            float t = (now - b.start) / (float) b.life;
            if (t < 1) drawBurst(context, font, b, t, now);
        }
    }
    private static boolean under(Burst b) { return b.kind() == Burst.Kind.RAYS || b.kind() == Burst.Kind.GLOW || b.kind() == Burst.Kind.RING; }

    void render(DrawContext context, net.minecraft.client.font.TextRenderer font, long now, float frameSeconds) {
        for (Iterator<Burst> it = bursts.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            if (now < b.start) continue;
            if (under(b)) { if (now >= b.start + b.life) it.remove(); continue; }
            float t = (now - b.start) / (float) b.life;
            if (t >= 1) { it.remove(); continue; }
            drawBurst(context, font, b, t, now);
        }
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            if (now < p.start) continue;
            float t = (now - p.start) / (float) p.life;
            if (t >= 1) { it.remove(); continue; }
            p.vy += p.gravity * frameSeconds;
            p.vx *= p.drag; p.vy *= p.drag;
            p.x += p.vx * frameSeconds; p.y += p.vy * frameSeconds;
            p.angle += p.spin * frameSeconds;
            float fade = t < 0.15f ? t / 0.15f : 1 - (t - 0.15f) / 0.85f;
            if (p.flicker) fade *= 0.7f + 0.3f * (float) Math.sin(now / 45.0 + p.x);
            TableDraw.sprite(context, p.sprite, p.x, p.y, p.size * (1 + p.grow * t), p.rgb, p.alpha * fade, p.angle);
        }
    }

    private void drawBurst(DrawContext context, net.minecraft.client.font.TextRenderer font, Burst b, float t, long now) {
        float in = Math.min(1, t / 0.15f), out = t > 0.7f ? 1 - (t - 0.7f) / 0.3f : 1;
        float a = in * out;
        switch (b.kind()) {
            case RAYS -> {
                // Солнце лучей: тень-подложка, два слоя навстречу друг другу, ядро.
                float turn = (now - b.start()) / 2600f, grow = 0.55f + 0.45f * in;
                // Тёмная подложка гасит зелень сукна под лучами: сложение даёт золото, а не жёлто-зелёный.
                TableDraw.shade(context, b.x(), b.y(), b.size() * 2.2f, b.size() * 1.8f, a * 0.35f);
                TableDraw.quad(context, TableDraw.RAYS, b.x() - b.size() * grow, b.y() - b.size() * grow, b.size() * 2 * grow, b.size() * 2 * grow, 0, 0, 1, 1, TableDraw.argb(b.rgb(), a * 0.95f), false, turn); // обычное смешивание: золото не зеленеет на сукне
                TableDraw.quad(context, TableDraw.RAYS, b.x() - b.size() * 0.75f * grow, b.y() - b.size() * 0.75f * grow, b.size() * 1.5f * grow, b.size() * 1.5f * grow, 0, 0, 1, 1, TableDraw.argb(0xFFE08A, a * 0.75f), false, -turn * 1.6f + 0.2f);
                TableDraw.sprite(context, TableDraw.Sprite.GLOW, b.x(), b.y(), b.size() * 0.9f, 0xFFF0C0, a * 0.5f, 0);
            }            case GLOW -> {
                float pulse = 1 + 0.08f * (float) Math.sin(now / 90.0);
                TableDraw.shade(context, b.x(), b.y(), b.size() * 1.6f, b.size() * 1.3f, a * 0.3f);
                TableDraw.sprite(context, TableDraw.Sprite.GLOW, b.x(), b.y(), b.size() * 1.6f * pulse, b.rgb(), a * 0.75f, 0);
                TableDraw.sprite(context, TableDraw.Sprite.GLOW, b.x(), b.y(), b.size() * 0.9f * pulse, 0xFFFFFF, a * 0.45f, 0);
                TableDraw.sprite(context, TableDraw.Sprite.FLARE, b.x(), b.y(), b.size() * 0.8f, b.rgb(), a * 0.6f, now / 600f);
            }
            case FLASH -> TableDraw.sprite(context, TableDraw.Sprite.GLOW, b.x(), b.y(), b.size() * (1 + t), b.rgb(), (1 - t) * 0.9f, 0);
            case RING -> TableDraw.sprite(context, TableDraw.Sprite.HALO, b.x(), b.y(), b.size() * (0.3f + 1.2f * t), b.rgb(), (1 - t) * 0.9f, 0);
            case TEXT -> {
                // Надпись выпрыгивает, чуть покачивается и тает.
                float pop = t < 0.12f ? 0.4f + 0.75f * (t / 0.12f) : t < 0.2f ? 1.15f - (t - 0.12f) / 0.08f * 0.15f : 1;
                float size = (float) (Math.max(1, Math.round(b.size() * pop * gui)) / gui);
                TableDraw.shade(context, b.x(), b.y(), font.getWidth(b.text()) * size * 2.0f, 10 * size * 3.4f, a * 0.55f);
                TableDraw.sprite(context, TableDraw.Sprite.GLOW, b.x(), b.y(), font.getWidth(b.text()) * size * 1.6f, 10 * size * 2.6f, b.rgb(), a * 0.25f, 0);
                TableDraw.bigText(context, font, b.text(), b.x(), b.y() + (float) Math.sin(now / 160.0) * 1.5f, size, b.rgb(), a);
            }
        }
    }
}
