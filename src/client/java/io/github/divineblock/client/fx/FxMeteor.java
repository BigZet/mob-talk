package io.github.divineblock.client.fx;

import java.util.Random;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;

/** A burning stone across the sky: a white-hot head in an orange blaze, a tapering tail of fire that cools to red, smoke
 * and sparks shed along the way; on landing the blast ({@link FxEngine#impact}). It follows the server's own path (from,
 * to, t² over the same ticks), so it lands when the crater opens. With effects off it is drawn with vanilla particles. */
final class FxMeteor implements FxEffect {
    private static final int TAIL = 26;
    private final Vec3d from, to;
    private final int ticks;
    private final float size;
    private final Random random = new Random();
    private final double[] xs = new double[TAIL], ys = new double[TAIL], zs = new double[TAIL];
    private final float[] widths = new float[TAIL], alphas = new float[TAIL];
    private final int[] colors = new int[TAIL];
    private final double[][] trail = new double[TAIL][3];
    private int age, kept;

    FxMeteor(Vec3d from, Vec3d to, int ticks, float size) {
        this.from = from; this.to = to; this.ticks = Math.max(1, ticks); this.size = Math.max(0.6f, size);
        Vec3d start = at(0);
        trail[0] = new double[] { start.x, start.y, start.z };
        kept = 1;
    }

    private Vec3d at(float tick) {
        double t = Math.min(1, tick / ticks);
        return from.lerp(to, t * t);
    }

    @Override public boolean tick(ClientWorld world) {
        age++;
        Vec3d head = at(age);
        System.arraycopy(trail, 0, trail, 1, TAIL - 1);
        trail[0] = new double[] { head.x, head.y, head.z };
        kept = Math.min(TAIL, kept + 1);
        if (!FxQuality.on()) {
            world.addParticle(ParticleTypes.FLAME, head.x, head.y, head.z, 0, 0, 0);
            world.addParticle(ParticleTypes.LARGE_SMOKE, head.x, head.y, head.z, 0, 0.02, 0);
            if (age >= ticks) world.addParticle(ParticleTypes.EXPLOSION_EMITTER, to.x, to.y + 1, to.z, 0, 0, 0);
            return age < ticks;
        }
        Vec3d back = at(age - 1);
        for (int i = FxQuality.count(4 * size); i > 0; i--) {
            double f = random.nextDouble();
            FxParticle smoke = FxEngine.add(FxSprite.SMOKE, false, back.x + (head.x - back.x) * f + FxEngine.spread(size),
                    back.y + (head.y - back.y) * f + FxEngine.spread(size), back.z + (head.z - back.z) * f + FxEngine.spread(size), 50 + random.nextInt(50));
            smoke.vy = 0.02; smoke.size = 0.7f * size; smoke.endSize = 3.2f * size; smoke.color = random.nextBoolean() ? 0x2A2624 : 0x4A4440;
            smoke.alpha = 0.75f; smoke.rollSpeed = (random.nextFloat() - 0.5f) * 0.06f; smoke.fadeIn = 0.05f;
        }
        for (int i = FxQuality.count(5 * size); i > 0; i--) {
            FxParticle spark = FxEngine.add(random.nextInt(3) == 0 ? FxSprite.SPARK : FxSprite.EMBER, true,
                    head.x + FxEngine.spread(size * 0.8), head.y + FxEngine.spread(size * 0.8), head.z + FxEngine.spread(size * 0.8), 12 + random.nextInt(18));
            spark.vx = FxEngine.spread(0.2); spark.vy = FxEngine.spread(0.2); spark.vz = FxEngine.spread(0.2);
            spark.gravity = 0.03f; spark.drag = 0.94f; spark.size = 0.1f + random.nextFloat() * 0.15f; spark.color = 0xFFC060; spark.endColor = 0xC02A08;
        }
        if (age >= ticks) { FxEngine.impact(to.x, to.y, to.z, 0xFF6A1A, size); return false; }
        return true;
    }

    @Override public void render(FxDraw draw, float delta, boolean glow) {
        if (!glow || !FxQuality.on()) return;
        Vec3d head = at(age + delta);
        int n = Math.min(kept + 1, TAIL);
        xs[0] = head.x; ys[0] = head.y; zs[0] = head.z;
        for (int i = 1; i < n; i++) { xs[i] = trail[i - 1][0]; ys[i] = trail[i - 1][1]; zs[i] = trail[i - 1][2]; }
        for (int i = 0; i < n; i++) {
            float t = i / (float) (TAIL - 1);
            widths[i] = 3.4f * size * (1 - t) + 0.2f;
            colors[i] = t < 0.25f ? FxDraw.mix(0xFFE2A0, 0xFF8A2A, t / 0.25f) : FxDraw.mix(0xFF8A2A, 0x8A1004, (t - 0.25f) / 0.75f);
            alphas[i] = 0.9f * (1 - t) * (1 - t);
        }
        draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, n, FxDraw.FULL_BRIGHT);
        for (int i = 0; i < n; i++) { widths[i] *= 0.3f; colors[i] = FxDraw.mix(colors[i], 0xFFFFFF, 0.6f); }
        draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, n, FxDraw.FULL_BRIGHT);
        float flicker = 0.9f + 0.1f * (float) Math.sin((age + delta) * 2.3);
        draw.sprite(FxSprite.GLOW, head.x, head.y, head.z, 4.5f * size * flicker, 0, 1, 0xFF7A20, 0.7f, FxDraw.FULL_BRIGHT);
        draw.sprite(FxSprite.FLARE, head.x, head.y, head.z, 2.6f * size, (age + delta) * 0.15f, 1, 0xFFE8B0, 1f, FxDraw.FULL_BRIGHT);
        draw.sprite(FxSprite.SPARK, head.x, head.y, head.z, 1.2f * size, 0, 1, 0xFFFFFF, 1f, FxDraw.FULL_BRIGHT);
    }
}
