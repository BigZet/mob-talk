package io.github.divineblock.client.fx;

import net.minecraft.client.world.ClientWorld;

/** A drawn effect bigger than one sprite (a ring, a bolt, a meteor, a beam of light): ticked and drawn by the engine. */
interface FxEffect {
    /** False when it is done. */
    boolean tick(ClientWorld world);
    /** Draws itself into one layer: {@code glow} for the additive light, otherwise the see-through layer. */
    void render(FxDraw draw, float delta, boolean glow);

    /** A shining ring running out across the ground and fading. */
    final class Ring implements FxEffect {
        final double x, y, z; final float radius; final int color, life; int age;
        Ring(double x, double y, double z, float radius, int color, int life) { this.x = x; this.y = y; this.z = z; this.radius = radius; this.color = color; this.life = life; }
        @Override public boolean tick(ClientWorld world) { return ++age < life; }
        @Override public void render(FxDraw draw, float delta, boolean glow) {
            if (!glow) return;
            float t = Math.min(1, (age + delta) / life), ease = 1 - (1 - t) * (1 - t) * (1 - t);
            float r = radius * ease, fade = (float) Math.pow(1 - t, 1.4);
            int segments = Math.max(24, Math.min(96, (int) (r * 6)));
            draw.ring(x, y, z, r, 0.5f + radius * 0.16f * (1 - t * 0.6f), color, fade, segments);
            draw.ring(x, y, z, r * 0.94f, 0.25f + radius * 0.05f, 0xFFFFFF, fade * 0.8f, segments);
        }
    }

    /** A column of light from the sky onto a spot: rings slide down it and sparks rise in it. */
    final class Pillar implements FxEffect {
        final double x, y, z; final int color, life; int age;
        private final double[] xs = new double[2], ys = new double[2], zs = new double[2];
        private final float[] widths = new float[2], alphas = new float[2]; private final int[] colors = new int[2];
        Pillar(double x, double y, double z, int color, int life) { this.x = x; this.y = y; this.z = z; this.color = color; this.life = Math.max(20, life); }
        @Override public boolean tick(ClientWorld world) {
            if (age % 8 == 0) FxEngine.addEffect(new Slide(x, y, z, color));
            for (int i = FxQuality.count(3); i > 0; i--) {
                FxParticle p = FxEngine.add(FxSprite.SPARK, true, x + FxEngine.spread(0.9), y + Math.random() * 2, z + FxEngine.spread(0.9), 30 + (int) (Math.random() * 20));
                p.vy = 0.12 + Math.random() * 0.15; p.drag = 1f; p.size = 0.08f + (float) Math.random() * 0.08f; p.color = FxDraw.mix(color, 0xFFFFFF, 0.5f);
            }
            return ++age < life;
        }
        @Override public void render(FxDraw draw, float delta, boolean glow) {
            if (!glow) return;
            float t = (age + delta) / life, fade = Math.min(1, t * 6) * Math.min(1, (1 - t) * 4);
            xs[0] = xs[1] = x; zs[0] = zs[1] = z; ys[0] = y; ys[1] = y + 90;
            colors[0] = colors[1] = color; alphas[0] = 0.55f * fade; alphas[1] = 0;
            widths[0] = 3.2f; widths[1] = 5f;
            draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, 2, FxDraw.FULL_BRIGHT);
            colors[0] = colors[1] = 0xFFFFFF; alphas[0] = 0.9f * fade; alphas[1] = 0.05f * fade;
            widths[0] = 0.9f; widths[1] = 1.4f;
            draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, 2, FxDraw.FULL_BRIGHT);
            draw.sprite(FxSprite.FLARE, x, y + 0.2, z, 2.4f + 0.3f * (float) Math.sin((age + delta) * 0.4), (age + delta) * 0.02f, 1, color, 0.8f * fade, FxDraw.FULL_BRIGHT);
        }
    }

    /** A ring of light sliding down a pillar, narrowing as it lands. */
    final class Slide implements FxEffect {
        final double x, y, z; final int color; int age;
        Slide(double x, double y, double z, int color) { this.x = x; this.y = y; this.z = z; this.color = color; }
        @Override public boolean tick(ClientWorld world) { return ++age < 30; }
        @Override public void render(FxDraw draw, float delta, boolean glow) {
            if (!glow) return;
            float t = (age + delta) / 30;
            draw.ring(x, y + 14 * (1 - t) * (1 - t) + 0.1, z, 1.9f - t * 0.8f, 0.4f, color, Math.min(1, t * 4) * (1 - t), 32);
        }
    }

    /** Something that keeps throwing sprites for a while (embers over a spot). */
    final class Emitter implements FxEffect {
        final java.util.function.IntConsumer step; final int life; int age;
        Emitter(int life, java.util.function.IntConsumer step) { this.life = life; this.step = step; }
        @Override public boolean tick(ClientWorld world) { step.accept(age); return ++age < life; }
        @Override public void render(FxDraw draw, float delta, boolean glow) { }
    }
}
