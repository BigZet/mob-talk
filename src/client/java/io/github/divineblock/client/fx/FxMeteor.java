package io.github.divineblock.client.fx;

import java.util.Random;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** A burning stone coming in from far away in the sky, as in life: first a faint spark high over the horizon that seems
 * to creep, then a growing fireball whose tail of fire and smoke lengthens, rushing faster the nearer it gets, and the
 * blast on landing ({@link FxEngine#impact}). It flies the server's own path ({@link #progress}), so it lands when the
 * crater opens. With effects off it is drawn with vanilla particles. */
final class FxMeteor implements FxEffect {
    private static final int TAIL = 30;
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
        trail[0] = new double[] { from.x, from.y, from.z };
        kept = 1;
    }

    /** How far along its path, 0..1, after {@code t} of {@code ticks}: a steady flight that quickens towards the ground.
     * The same curve as the server's {@code Fx.meteorProgress}. */
    static double progress(double t) { t = MathHelper.clamp(t, 0, 1); return 0.55 * t + 0.45 * t * t; }

    private Vec3d at(float tick) { return from.lerp(to, progress(tick / ticks)); }

    /** The stone catches fire as it enters the air: faint at first, full after a fifth of its flight. */
    private float burning(float tick) { return MathHelper.clamp(tick / (ticks * 0.2f), 0.15f, 1f); }

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
        float fire = burning(age);
        // Smoke left hanging along the path: thin and grey high up, thicker near the ground.
        for (int i = FxQuality.count(3 * size * fire); i > 0; i--) {
            double f = random.nextDouble();
            double x = back.x + (head.x - back.x) * f, y = back.y + (head.y - back.y) * f, z = back.z + (head.z - back.z) * f;
            float spread = size * (0.6f + 1.4f * age / ticks);
            FxParticle smoke = FxEngine.add(FxSprite.smoke(random), false, x + FxEngine.spread(spread * 0.4), y + FxEngine.spread(spread * 0.4), z + FxEngine.spread(spread * 0.4),
                    120 + random.nextInt(100));
            smoke.vy = 0.01; smoke.drag = 1f; smoke.size = spread; smoke.endSize = spread * 3.5f;
            int grey = 0x6A + random.nextInt(0x28); smoke.color = grey << 16 | (grey - 4) << 8 | (grey - 10);
            smoke.alpha = 0.4f; smoke.roll = random.nextFloat() * MathHelper.TAU; smoke.rollSpeed = (random.nextFloat() - 0.5f) * 0.01f;
            smoke.fadeIn = 0.08f; smoke.fadeOut = 0.7f;
        }
        for (int i = FxQuality.count(4 * size * fire); i > 0; i--) {
            FxParticle spark = FxEngine.add(random.nextInt(3) == 0 ? FxSprite.SPARK : FxSprite.EMBER, true,
                    head.x + FxEngine.spread(size * 0.6), head.y + FxEngine.spread(size * 0.6), head.z + FxEngine.spread(size * 0.6), 10 + random.nextInt(16));
            spark.vx = FxEngine.spread(0.15); spark.vy = FxEngine.spread(0.15); spark.vz = FxEngine.spread(0.15);
            spark.gravity = 0.03f; spark.drag = 0.94f; spark.size = (0.12f + random.nextFloat() * 0.15f) * apparent(head); spark.color = 0xFFC060; spark.endColor = 0xC02A08;
        }
        if (age >= ticks) { FxEngine.impact(to.x, to.y, to.z, 0xFF6A1A, size); return false; }
        return true;
    }

    /** Far off, a stone a block wide would vanish: sprites are kept at least a few pixels big, like a bright star. */
    private static float apparent(Vec3d at) {
        var camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        double distance = camera.getPos().distanceTo(at);
        return (float) Math.max(1, distance / 60);
    }

    @Override public void render(FxDraw draw, float delta, boolean glow) {
        if (!glow || !FxQuality.on()) return;
        float time = age + delta, fire = burning(time);
        Vec3d head = at(time);
        float far = apparent(head);
        int n = Math.min(kept + 1, TAIL);
        xs[0] = head.x; ys[0] = head.y; zs[0] = head.z;
        for (int i = 1; i < n; i++) { xs[i] = trail[i - 1][0]; ys[i] = trail[i - 1][1]; zs[i] = trail[i - 1][2]; }
        for (int i = 0; i < n; i++) {
            float t = i / (float) (TAIL - 1);
            widths[i] = (2.6f * size * (1 - t) + 0.15f) * far;
            colors[i] = t < 0.2f ? FxDraw.mix(0xFFE8B0, 0xFF8A2A, t / 0.2f) : FxDraw.mix(0xFF8A2A, 0x6A1004, (t - 0.2f) / 0.8f);
            alphas[i] = 0.85f * fire * (1 - t) * (1 - t);
        }
        draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, n, FxDraw.FULL_BRIGHT);
        for (int i = 0; i < n; i++) { widths[i] *= 0.3f; colors[i] = FxDraw.mix(colors[i], 0xFFFFFF, 0.6f); }
        draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, n, FxDraw.FULL_BRIGHT);
        float flicker = 0.9f + 0.1f * MathHelper.sin(time * 2.3f);
        draw.sprite(FxSprite.GLOW, head.x, head.y, head.z, 3.6f * size * flicker * far, 0, 1, 0xFF7A20, 0.65f * fire, FxDraw.FULL_BRIGHT);
        draw.sprite(FxSprite.FLARE, head.x, head.y, head.z, 2.2f * size * far, time * 0.15f, 1, 0xFFE8B0, fire, FxDraw.FULL_BRIGHT);
        draw.sprite(FxSprite.SPARK, head.x, head.y, head.z, 1.0f * size * far, 0, 1, 0xFFFFFF, 1f, FxDraw.FULL_BRIGHT);
    }
}
