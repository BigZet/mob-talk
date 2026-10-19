package io.github.mobtalk.core;

/** The live preferences for the pure rules that get no config passed in (state drift, hearts, rumours, memory ranking, leases):
 * one snapshot, swapped whole when the settings are loaded or saved. Defaults until then, so tests and a fresh start behave as
 * the constants used to. */
public final class Tuning {
    private static volatile MobTalkConfig live = new MobTalkConfig();
    private Tuning() { }

    /** Read-only by convention: the snapshot is shared, never edit it. */
    public static MobTalkConfig get() { return live; }
    /** Takes a private copy; null goes back to the defaults. */
    public static void apply(MobTalkConfig config) { live = config == null ? new MobTalkConfig() : config.copy(); }
}
