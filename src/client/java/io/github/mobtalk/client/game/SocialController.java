package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/** The physical side of the villagers' social layer on the integrated server: what a gesture does in the world after the reply
 * passed its checks (a slap stings). The relationship, the state and the memory change when the reply is committed
 * ({@link SocialLedger#applyGesture}). Server thread only. */
final class SocialController {
    private final MobGameBridge bridge;
    SocialController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer; }

    /** Inside the delivery, under the memory lock: the gesture's effect on the world; "" or a line for the chat. */
    String apply(MobEntity mob, ServerPlayerEntity player, GestureIntent gesture) {
        if (gesture == null || !gesture.present() || !enabled()) return "";
        if (gesture.outcome() == Gesture.Outcome.SLAP && mob.getWorld() instanceof ServerWorld world) {
            player.damage(world.getDamageSources().mobAttack(mob), 1.0f);
            return "Пощёчина!";
        }
        return "";
    }
}
