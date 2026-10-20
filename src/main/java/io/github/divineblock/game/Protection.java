package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Where the god's wrath must not touch blocks: everywhere when protection is on, and always near the altar and the bed. */
final class Protection {
    private Protection() { }

    static boolean spares(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos target, DivineConfig config) {
        if (config.protectBlocks) return true;
        double radius = config.protectRadius;
        if (altar != null && altar.isWithinDistance(target, radius)) return true;
        BlockPos bed = player.getSpawnPointPosition();
        return bed != null && player.getSpawnPointDimension() == world.getRegistryKey() && bed.isWithinDistance(target, radius);
    }
}
