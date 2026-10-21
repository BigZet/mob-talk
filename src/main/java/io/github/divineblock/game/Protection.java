package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Where the god's wrath must not touch blocks: everywhere when protection is on; otherwise only the altar itself (a few
 * blocks around it, so the god keeps his seat) and the player's home around the bed. The player at the altar is not spared:
 * craters open where he stands. */
public final class Protection {
    static final int ALTAR_RADIUS = 3;

    private Protection() { }

    public static boolean spares(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos target, DivineConfig config) {
        if (config.protectBlocks) return true;
        if (altar != null && altar.isWithinDistance(target, ALTAR_RADIUS)) return true;
        BlockPos bed = player.getSpawnPointPosition();
        return bed != null && player.getSpawnPointDimension() == world.getRegistryKey() && bed.isWithinDistance(target, config.protectRadius);
    }
}
