package io.github.divineblock.client;

import io.github.divineblock.game.FlipPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;

/** Which entities the god turned upside down, and until when (client world time). */
public final class Flipped {
    private static final Map<Integer, Long> UNTIL = new HashMap<>();

    private Flipped() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(FlipPayload.ID, (payload, context) -> {
            if (context.client().world == null) return;
            long until = context.client().world.getTime() + payload.ticks();
            for (int id : payload.entities()) UNTIL.put(id, until);
        });
    }

    public static boolean is(LivingEntity entity) {
        if (UNTIL.isEmpty()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || entity.getWorld() != client.world) { UNTIL.clear(); return false; }
        Long until = UNTIL.get(entity.getId());
        if (until == null) return false;
        if (client.world.getTime() > until) { UNTIL.remove(entity.getId()); return false; }
        return true;
    }
}
