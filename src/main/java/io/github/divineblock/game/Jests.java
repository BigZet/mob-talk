package io.github.divineblock.game;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

/** The god's bodily jests: the world turned upside down, the mortal shrunk to a mouse or grown to a giant. Every change is
 * temporary; the size is a temporary attribute modifier, so it never outlives the session. */
final class Jests {
    static final Identifier SCALE = Identifier.of("divineblock", "scale");

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

    /** Changes the mortal's size by {@code change} (−0.9 is a tenth, 3 is four times) for {@code ticks}. */
    static String scale(ServerPlayerEntity player, double change, int ticks, String done) {
        EntityAttributeInstance scale = player.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
        if (scale == null) return null;
        scale.removeModifier(SCALE);
        scale.addTemporaryModifier(new EntityAttributeModifier(SCALE, change, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        ServerWorld world = player.getServerWorld();
        Miracles.sound(world, player.getBlockPos(), change < 0 ? SoundEvents.ENTITY_PUFFER_FISH_BLOW_OUT : SoundEvents.ENTITY_PUFFER_FISH_BLOW_UP, 2f, 0.6f);
        world.spawnParticles(ParticleTypes.POOF, player.getX(), player.getY() + 1, player.getZ(), 40, 0.8, 0.8, 0.8, 0.05);
        Scheduler.later(ticks, () -> restore(player));
        return done;
    }

    static void restore(ServerPlayerEntity player) {
        EntityAttributeInstance scale = player.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
        if (scale != null && scale.removeModifier(SCALE) && !player.isRemoved())
            player.getServerWorld().spawnParticles(ParticleTypes.POOF, player.getX(), player.getY() + 1, player.getZ(), 30, 0.6, 0.6, 0.6, 0.05);
    }
}
