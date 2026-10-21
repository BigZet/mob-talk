package io.github.divineblock.client.fx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/** Camera shake (blows of wrath, bolts, meteors) and the white flash of a strike, both fading by themselves. Shake has its
 * own switch in the settings; both are off when effects are off. */
public final class FxCamera {
    private static float shake, shakeDecay, flash, flashDecay;
    private static int flashColor = 0xFFFFFF;
    private static long seed;

    private FxCamera() { }

    /** Shake strength 0..1, weaker with distance from the source, gone past {@code reach} blocks. */
    static void shakeFrom(double x, double y, double z, float strength, int ticks, double reach) {
        float near = near(x, y, z, reach);
        if (near <= 0 || !FxQuality.shake()) return;
        float s = strength * near;
        if (s > shake) { shake = Math.min(1, s); shakeDecay = shake / Math.max(1, ticks); }
    }

    static void flashFrom(double x, double y, double z, int color, float strength, double reach) {
        float near = near(x, y, z, reach);
        if (near <= 0 || !FxQuality.on()) return;
        float f = strength * near;
        if (f > flash) { flash = Math.min(0.85f, f); flashDecay = flash / 7; flashColor = color & 0xFFFFFF; }
    }

    private static float near(double x, double y, double z, double reach) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;
        double distance = Math.sqrt(client.player.squaredDistanceTo(x, y, z));
        return distance >= reach ? 0 : (float) (1 - distance / reach);
    }

    static void tick() {
        shake = Math.max(0, shake - shakeDecay);
        flash = Math.max(0, flash - flashDecay);
        seed++;
    }

    static void clear() { shake = flash = 0; }

    /** For the camera mixin: degrees to add to yaw and pitch this frame. */
    public static float[] offsets(float delta) {
        if (shake <= 0) return null;
        float t = seed + delta, s = shake * shake * 4.5f;
        return new float[] { s * (MathHelper.sin(t * 1.9f) * 0.6f + MathHelper.sin(t * 5.3f) * 0.4f),
                s * (MathHelper.cos(t * 2.3f) * 0.6f + MathHelper.sin(t * 6.1f + 1) * 0.4f) };
    }

    static void drawFlash(DrawContext context) {
        if (flash <= 0.003f) return;
        int alpha = MathHelper.clamp((int) (flash * 255), 0, 255);
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), alpha << 24 | flashColor);
    }
}
