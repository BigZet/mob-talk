package io.github.divineblock.client;

import io.github.divineblock.game.CursePayload;
import io.github.divineblock.game.Curses;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/** The first plague on the client: while the curse lasts, all water is the colour of blood. The chunks are rebuilt when it
 * starts and ends so the new colour shows at once. */
public final class BloodWater {
    private static volatile boolean active;

    private BloodWater() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CursePayload.ID, (payload, context) -> set(context.client(), (payload.flags() & Curses.FLAG_BLOOD) != 0));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = false);
    }

    private static void set(MinecraftClient client, boolean on) {
        if (active == on) return;
        active = on;
        if (client.worldRenderer != null && client.world != null) client.worldRenderer.reload();
    }

    public static boolean active() { return active; }

    /** Blood over the biome's water colour. */
    public static int tint(int colour) {
        int r = (colour >> 16) & 0xFF, g = (colour >> 8) & 0xFF, b = colour & 0xFF;
        r = (int) (r * 0.15 + 0x8A * 0.85); g = (int) (g * 0.15 + 0x07 * 0.85); b = (int) (b * 0.15 + 0x07 * 0.85);
        return (colour & 0xFF000000) | r << 16 | g << 8 | b;
    }
}
