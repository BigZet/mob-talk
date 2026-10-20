package io.github.divineblock.game;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;

/** The god's bodily jests: the world turned upside down for a while. */
final class Jests {

    private Jests() { }

    /** The mortal and every creature around him walk upside down for a while (seen by the client, see FlipPayload). */
    static String flip(ServerWorld world, ServerPlayerEntity player, int power) {
        List<Integer> ids = new ArrayList<>();
        ids.add(player.getId());
        for (LivingEntity creature : world.getEntitiesByClass(LivingEntity.class, new Box(player.getBlockPos()).expand(32), LivingEntity::isAlive))
            if (creature != player) ids.add(creature.getId());
        int ticks = 20 * (power >= 2 ? 60 : 30);
        if (ServerPlayNetworking.canSend(player, FlipPayload.ID)) ServerPlayNetworking.send(player, new FlipPayload(ids, ticks));
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 2f, 0.7f);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 80, 2, 1, 2, 0.1);
        return "мир перевернулся: " + ids.size() + " существ вверх ногами";
    }
}
