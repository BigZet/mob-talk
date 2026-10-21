package io.github.divineblock.client.boss;

import io.github.divineblock.boss.GodAvatarEntity;
import io.github.divineblock.client.SkyTint;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.util.math.Box;

/** The sky while the god is over the clouds: it darkens as he comes and clears as he goes, read from his figure (no packet). */
final class GodSky {
    private GodSky() { }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) { SkyTint.god(0); return; }
            float wanted = 0;
            for (GodAvatarEntity god : client.world.getEntitiesByClass(GodAvatarEntity.class, new Box(client.player.getBlockPos()).expand(256), g -> !g.isRemoved()))
                wanted = Math.max(wanted, god.stage() == GodAvatarEntity.LEAVE ? 1f - god.stageProgress(0) : 1f);
            SkyTint.god(wanted);
        });
    }
}
