package io.github.divineblock.client.fx;

import java.util.Random;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** The god's rays out of the altar while he speaks, drawn in light instead of the beacon's beam: each ray a white-hot core
 * in a soft halo of the verdict's colour that thins into the sky, all of them spinning. Sparks run up the rays, a ring of
 * light rolls across the ground at the first word and then with every breath of his speech, and a last big one at his
 * last word, when the rays fade. */
final class AltarRays implements FxEffect {
    private static final int LENGTH = 48, POINTS = 6, BREATH = 40;
    final BlockPos altar;
    private final int color;
    private final float[] elevations, azimuths;
    private final float spin;
    private final Random random = new Random();
    private final double[] xs = new double[POINTS], ys = new double[POINTS], zs = new double[POINTS];
    private final float[] widths = new float[POINTS], alphas = new float[POINTS];
    private final int[] colors = new int[POINTS];
    private int age, end;

    AltarRays(BlockPos altar, int ticks, int color, float[] elevations, float[] azimuths, float spin) {
        this.altar = altar; this.color = color & 0xFFFFFF; this.elevations = elevations; this.azimuths = azimuths; this.spin = spin;
        end = Math.max(20, ticks);
        FxEngine.addEffect(new Ring(cx(), altar.getY() + 1.05, cz(), 12, this.color, 34));
    }

    private double cx() { return altar.getX() + 0.5; }
    private double cz() { return altar.getZ() + 0.5; }

    /** The last word: fade over half a second, with a last ring and a burst of sparks. */
    void fade() {
        if (end <= age + 10) return;
        end = age + 10;
        FxEngine.addEffect(new Ring(cx(), altar.getY() + 1.05, cz(), 18, color, 40));
        FxEngine.sparks(cx(), altar.getY() + 1.5, cz(), color, 4);
    }

    boolean alive() { return age < end; }

    @Override public boolean tick(ClientWorld world) {
        age++;
        if (age % BREATH == 0 && end - age > 15) FxEngine.addEffect(new Ring(cx(), altar.getY() + 1.05, cz(), 8, color, 30));
        float length = LENGTH * grow(0);
        for (int i = FxQuality.count(8); i > 0 && length > 4; i--) {
            int beam = random.nextInt(elevations.length);
            float[] dir = direction(beam, 0);
            double d = 1 + random.nextDouble() * length * 0.55, speed = 0.7 + random.nextDouble() * 0.6;
            FxParticle p = FxEngine.add(FxSprite.SPARK, true, cx() + dir[0] * d, altar.getY() + 1 + dir[1] * d, cz() + dir[2] * d, 10 + random.nextInt(8));
            p.vx = dir[0] * speed; p.vy = dir[1] * speed; p.vz = dir[2] * speed; p.drag = 1f;
            p.size = 0.12f + random.nextFloat() * 0.12f; p.color = FxDraw.mix(color, 0xFFFFFF, 0.55f);
        }
        return age < end;
    }

    /** The rays grow out over the first second and draw back over the last half second. */
    private float grow(float delta) {
        return Math.min(1f, (age + delta) / 20f) * Math.min(1f, (end - age - delta) / 10f + 0.05f);
    }

    private float[] direction(int beam, float delta) {
        float azimuth = (azimuths[beam] + (age + delta) * spin) * MathHelper.RADIANS_PER_DEGREE, elevation = elevations[beam] * MathHelper.RADIANS_PER_DEGREE;
        float flat = MathHelper.cos(elevation);
        return new float[] { flat * MathHelper.cos(azimuth), MathHelper.sin(elevation), flat * MathHelper.sin(azimuth) };
    }

    @Override public void render(FxDraw draw, float delta, boolean glow) {
        if (!glow) return;
        float grow = grow(delta), length = Math.max(2, LENGTH * grow), time = age + delta;
        double baseX = cx(), baseY = altar.getY() + 1.0, baseZ = cz();
        int core = FxDraw.mix(color, 0xFFFFFF, 0.75f);
        for (int beam = 0; beam < elevations.length; beam++) {
            float[] dir = direction(beam, delta);
            float flicker = 0.82f + 0.18f * MathHelper.sin(time * 0.9f + beam * 1.7f);
            for (int i = 0; i < POINTS; i++) {
                float t = i / (float) (POINTS - 1);
                xs[i] = baseX + dir[0] * length * t; ys[i] = baseY + dir[1] * length * t; zs[i] = baseZ + dir[2] * length * t;
                widths[i] = 1.1f + 2.2f * t; colors[i] = color; alphas[i] = 0.42f * flicker * (1 - t) * (1 - t * 0.4f);
            }
            draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, POINTS, FxDraw.FULL_BRIGHT);
            for (int i = 0; i < POINTS; i++) {
                float t = i / (float) (POINTS - 1);
                widths[i] = 0.22f + 0.3f * t; colors[i] = core; alphas[i] = flicker * (1 - t);
            }
            draw.ribbon(FxSprite.BEAM, xs, ys, zs, widths, colors, alphas, POINTS, FxDraw.FULL_BRIGHT);
        }
        float pulse = 0.85f + 0.15f * MathHelper.sin(time * 0.35f);
        draw.sprite(FxSprite.GLOW, baseX, baseY + 0.3, baseZ, 6 * grow * pulse, 0, 1, color, 0.55f, FxDraw.FULL_BRIGHT);
        draw.sprite(FxSprite.FLARE, baseX, baseY + 0.3, baseZ, 2.6f * grow * pulse, time * 0.03f, 1, core, 0.9f, FxDraw.FULL_BRIGHT);
    }
}
