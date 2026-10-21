package io.github.divineblock.client.boss;

import io.github.divineblock.boss.GodAvatarEntity;
import io.github.divineblock.client.SkyTint;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.util.math.Box;

/** The sky while the god is over the clouds: it darkens as he comes and clears as he goes, read from his figure (no packet).
 * As he comes, the camera of a player looking away is drawn gently toward him (only with «Тряска камеры» on). */
final class GodSky {
    private GodSky() { }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) { SkyTint.god(0); return; }
            float wanted = 0;
            for (GodAvatarEntity god : client.world.getEntitiesByClass(GodAvatarEntity.class, new Box(client.player.getBlockPos()).expand(256), g -> !g.isRemoved()))
                wanted = Math.max(wanted, god.stage() == GodAvatarEntity.LEAVE ? 1f - god.stageProgress(0) : 1f);
            SkyTint.god(wanted);
            nudge(client);
        });
    }

    private static void nudge(net.minecraft.client.MinecraftClient client) {
        if (client.isPaused() || client.currentScreen != null || !io.github.divineblock.DivineBlockMod.config().cameraShake) return;
        for (GodAvatarEntity god : client.world.getEntitiesByClass(GodAvatarEntity.class, new Box(client.player.getBlockPos()).expand(96),
                g -> g.stage() == GodAvatarEntity.ARRIVE && g.stageAge() > 20 && g.stageAge() < 70)) {
            double dx = god.getX() - client.player.getX(), dz = god.getZ() - client.player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float delta = net.minecraft.util.math.MathHelper.wrapDegrees(yaw - client.player.getYaw());
            if (Math.abs(delta) > 50) client.player.setYaw(client.player.getYaw() + delta * 0.03f);
            return;
        }
    }
}
