package io.github.divineblock.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/** The sky of wrath: from the second group of punishments on, the heavens turn red while the god speaks and while his
 * punishment falls, deeper for heavier groups, from a crimson dusk (К2) to a black-red end of days (К5). It rises over
 * two seconds, holds until some time after his last word and sinks back over three. Sky, clouds and fog are tinted. */
public final class SkyTint {
    /** Target colours per depth 1..4, and how much of the vanilla colour they replace. */
    private static final Vec3d[] COLOURS = { new Vec3d(0.62, 0.16, 0.10), new Vec3d(0.55, 0.08, 0.05), new Vec3d(0.42, 0.03, 0.03), new Vec3d(0.22, 0.0, 0.02) };
    private static final double[] STRENGTH = { 0.55, 0.7, 0.82, 0.92 };
    /** After the last word the sky stays red this long: most punishments are still falling. */
    private static final int HOLD_AFTER_WORDS = 20 * 25;

    private static int depth;
    private static long holdUntil = Long.MAX_VALUE;
    private static float amount, previous;
    private static ClientWorld world;

    private SkyTint() { }

    static void register() { ClientTickEvents.END_CLIENT_TICK.register(SkyTint::tick); }

    /** A new verdict: its depth (0 lets the sky clear). */
    static void verdict(MinecraftClient client, int sky) {
        if (sky <= 0) { holdUntil = 0; return; }
        depth = Math.min(4, sky);
        holdUntil = Long.MAX_VALUE;
    }

    static void wordsEnded(MinecraftClient client) {
        if (client.world != null && holdUntil == Long.MAX_VALUE) holdUntil = client.world.getTime() + HOLD_AFTER_WORDS;
    }

    private static void tick(MinecraftClient client) {
        if (client.world != world) { world = client.world; depth = 0; amount = previous = 0; holdUntil = Long.MAX_VALUE; }
        previous = amount;
        if (client.world == null) return;
        boolean on = depth > 0 && client.world.getTime() < holdUntil;
        amount = on ? Math.min(1f, amount + 1 / 40f) : Math.max(0f, amount - 1 / 60f);
        if (amount == 0 && !on) depth = 0;
    }

    /** For the self-test: how red the sky is now, 0..1, and how deep. */
    static float amount() { return amount; }
    static int depth() { return depth; }

    /** The vanilla colour with the wrath laid over it ({@code share} of the full tint: the fog takes a little less). */
    public static Vec3d tint(Vec3d colour, float tickDelta, double share) {
        float now = previous + (amount - previous) * tickDelta;
        if (now <= 0 || depth <= 0) return colour;
        double k = STRENGTH[depth - 1] * now * share;
        Vec3d target = COLOURS[depth - 1];
        return colour.multiply(1 - k).add(target.multiply(k));
    }
}
