package io.github.divineblock.client.fx;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;

/** The "Эффекты" setting: 0 off (vanilla particles only, as before), 1 low, 2 medium, 3 high. It scales how many sprites an
 * effect throws and caps how many live at once; camera shake has its own switch. Re-read from the config now and then. */
public final class FxQuality {
    private static final int[] BUDGET = { 0, 900, 2800, 6500 };
    private static final float[] SCALE = { 0f, 0.35f, 0.7f, 1f };
    private static volatile int level = new DivineConfig().effects;
    private static volatile boolean shake = true;
    private static int sinceRead = 1_000;

    private FxQuality() { }

    static void tick() {
        if (++sinceRead < 40) return;
        sinceRead = 0;
        DivineConfig config = DivineBlockMod.config();
        level = config.effects;
        shake = config.cameraShake;
    }

    /** The settings screen saved: read again on the next tick. */
    public static void reload() { sinceRead = 1_000; }

    public static int level() { return level; }
    public static boolean on() { return level > 0; }
    static boolean shake() { return shake && level > 0; }
    static int budget() { return BUDGET[level]; }
    /** {@code full} sprites at high quality, fewer below (at least one while effects are on). */
    static int count(double full) {
        if (level == 0 || full <= 0) return 0;
        double scaled = full * SCALE[level];
        int whole = (int) scaled;
        return Math.max(1, whole + (Math.random() < scaled - whole ? 1 : 0));
    }
}
