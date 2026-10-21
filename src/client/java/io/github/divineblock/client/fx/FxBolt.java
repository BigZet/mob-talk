package io.github.divineblock.client.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/** The god's lightning: a jagged channel from high in the sky, branches forking off it, a white-hot core in a coloured
 * glow. It strikes, flickers and re-strikes along a new path twice, then leaves an afterglow on the ground. A vanilla
 * lightning entity landing on the same spot is hidden ({@link #claims}), so only this one is seen. */
final class FxBolt implements FxEffect {
    private static final float[] BRIGHTNESS = { 1f, 1f, 0.55f, 0.95f, 0.75f, 0.3f, 1f, 0.8f, 0.45f, 0.3f, 0.2f, 0.12f, 0.06f };
    private record Claim(Vec3d at, long until) { }
    private static final List<Claim> CLAIMS = new ArrayList<>();

    private final double x, y, z;
    private final int color;
    private final Random random = new Random();
    private final List<double[][]> paths = new ArrayList<>();
    private final List<Float> widths = new ArrayList<>();
    private int age;

    FxBolt(ClientWorld world, double x, double y, double z, int color) {
        this.x = x; this.y = y; this.z = z; this.color = color;
        shape();
        CLAIMS.add(new Claim(new Vec3d(x, y, z), world.getTime() + 40));
        land(world);
    }

    /** Whether a vanilla lightning bolt here belongs to one of ours (and is not drawn). */
    static boolean claims(ClientWorld world, Vec3d at) {
        long now = world.getTime();
        CLAIMS.removeIf(claim -> claim.until() < now);
        for (Claim claim : CLAIMS) if (claim.at().squaredDistanceTo(at) < 9) return true;
        return false;
    }

    private void shape() {
        paths.clear(); widths.clear();
        double height = 70 + random.nextDouble() * 30;
        double sx = x + (random.nextDouble() - 0.5) * 14, sz = z + (random.nextDouble() - 0.5) * 14;
        double[][] main = jag(sx, y + height, sz, x, y, z, 6, height * 0.09);
        paths.add(main); widths.add(1f);
        int branches = 4 + random.nextInt(4);
        for (int i = 0; i < branches; i++) {
            int from = 4 + random.nextInt(main[0].length * 2 / 3);
            double bx = main[0][from], by = main[1][from], bz = main[2][from];
            double length = 8 + random.nextDouble() * 18, angle = random.nextDouble() * Math.PI * 2;
            double ex = bx + Math.cos(angle) * length * 0.7, ez = bz + Math.sin(angle) * length * 0.7, ey = by - length * (0.5 + random.nextDouble() * 0.5);
            paths.add(jag(bx, by, bz, ex, ey, ez, 4, length * 0.12)); widths.add(0.45f);
            if (random.nextBoolean()) {
                double[][] branch = paths.get(paths.size() - 1);
                int at = 2 + random.nextInt(branch[0].length - 4);
                double l2 = length * 0.5, a2 = angle + (random.nextDouble() - 0.5) * 1.5;
                paths.add(jag(branch[0][at], branch[1][at], branch[2][at], branch[0][at] + Math.cos(a2) * l2, branch[1][at] - l2 * 0.7,
                        branch[2][at] + Math.sin(a2) * l2, 3, l2 * 0.15)); widths.add(0.25f);
            }
        }
    }

    /** A jagged line by midpoint displacement: 2^depth segments. */
    private double[][] jag(double x0, double y0, double z0, double x1, double y1, double z1, int depth, double offset) {
        int n = (1 << depth) + 1;
        double[][] p = new double[3][n];
        p[0][0] = x0; p[1][0] = y0; p[2][0] = z0; p[0][n - 1] = x1; p[1][n - 1] = y1; p[2][n - 1] = z1;
        for (int step = n - 1; step > 1; step /= 2, offset *= 0.55) {
            for (int i = step / 2; i < n; i += step) {
                int a = i - step / 2, b = i + step / 2;
                p[0][i] = (p[0][a] + p[0][b]) / 2 + (random.nextDouble() - 0.5) * 2 * offset;
                p[1][i] = (p[1][a] + p[1][b]) / 2 + (random.nextDouble() - 0.5) * offset * 0.6;
                p[2][i] = (p[2][a] + p[2][b]) / 2 + (random.nextDouble() - 0.5) * 2 * offset;
            }
        }
        return p;
    }

    /** Where it lands: a burst of sparks, a ring, the flash and the shake. */
    private void land(ClientWorld world) {
        FxEngine.addEffect(new Ring(x, y + 0.15, z, 7, color, 22));
        for (int i = FxQuality.count(40); i > 0; i--) {
            FxParticle p = FxEngine.add(FxSprite.SPARK, true, x, y + 0.3, z, 14 + random.nextInt(20));
            double a = random.nextDouble() * Math.PI * 2, speed = 0.2 + random.nextDouble() * 0.45;
            p.vx = Math.cos(a) * speed; p.vz = Math.sin(a) * speed; p.vy = 0.2 + random.nextDouble() * 0.4;
            p.gravity = 0.04f; p.drag = 0.93f; p.size = 0.09f + random.nextFloat() * 0.08f; p.color = FxDraw.mix(color, 0xFFFFFF, 0.6f);
        }
        for (int i = FxQuality.count(8); i > 0; i--) {
            FxParticle p = FxEngine.add(FxSprite.smoke(random), false, x + FxEngine.spread(1.5), y + 0.4, z + FxEngine.spread(1.5), 50 + random.nextInt(40));
            p.vy = 0.02 + random.nextDouble() * 0.03; p.size = 0.6f; p.endSize = 2.2f; p.color = 0x5A5654; p.alpha = 0.45f; p.roll = random.nextFloat() * 6.28f;
            p.rollSpeed = 0.01f; p.fadeIn = 0.15f; p.fadeOut = 0.6f;
        }
        FxCamera.flashFrom(x, y, z, 0xE8F0FF, 0.55f, 90);
        FxCamera.shakeFrom(x, y, z, 0.45f, 18, 48);
    }

    @Override public boolean tick(ClientWorld world) {
        age++;
        if (age == 6 || age == 9) shape();
        return age < BRIGHTNESS.length;
    }

    @Override public void render(FxDraw draw, float delta, boolean glow) {
        if (!glow) return;
        float bright = BRIGHTNESS[Math.min(BRIGHTNESS.length - 1, age)];
        for (int i = 0; i < paths.size(); i++) {
            double[][] p = paths.get(i);
            int n = p[0].length;
            float w = widths.get(i);
            float[] halo = new float[n], core = new float[n], alphaHalo = new float[n], alphaCore = new float[n];
            int[] haloColor = new int[n], coreColor = new int[n];
            for (int k = 0; k < n; k++) {
                float taper = i == 0 ? 1 : 1 - 0.8f * k / (n - 1);
                halo[k] = 3.2f * w * taper; core[k] = 0.32f * w * taper + 0.05f;
                alphaHalo[k] = 0.42f * bright * taper; alphaCore[k] = bright * taper;
                haloColor[k] = color & 0xFFFFFF; coreColor[k] = FxDraw.mix(color, 0xFFFFFF, 0.85f);
            }
            draw.ribbon(FxSprite.BEAM, p[0], p[1], p[2], halo, haloColor, alphaHalo, n, FxDraw.FULL_BRIGHT);
            draw.ribbon(FxSprite.BEAM, p[0], p[1], p[2], core, coreColor, alphaCore, n, FxDraw.FULL_BRIGHT);
        }
        // The afterglow where it struck.
        float ground = Math.max(0, 1 - (age + delta) / BRIGHTNESS.length);
        draw.sprite(FxSprite.FLARE, x, y + 0.6, z, 5 * ground + 1, 0, 1, FxDraw.mix(color, 0xFFFFFF, 0.6f), 0.9f * ground, FxDraw.FULL_BRIGHT);
        draw.sprite(FxSprite.GLOW, x, y + 0.6, z, 9 * ground, 0, 1, color & 0xFFFFFF, 0.5f * ground * bright, FxDraw.FULL_BRIGHT);
    }
}
