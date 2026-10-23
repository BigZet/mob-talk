package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Where the god's wrath must not touch blocks: everywhere when protection is on; otherwise only the altar itself (a few
 * blocks around it, so the god keeps his seat) and the generated churches with their
 * yard walls. The player at the altar is not spared:
 * craters open where he stands. */
public final class Protection {
    static final int ALTAR_RADIUS = 3;

    private Protection() { }

    public static boolean spares(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos target, DivineConfig config) {
        if (config.protectBlocks) return true;
        // The church, its walled yard and its cemetery: no punishment changes the land there.
        if (ChurchArea.inZone(world, target) || io.github.divineblock.world.ChurchSites.isChurchBlock(world, target)) return true;
        // The altar itself is spared — only a real one: an act taken home or into open land gets its strike point in the altar's
        // place, and sparing that point left the burning dynamite and the craters without a single block to fall on.
        if (altar != null && altar.isWithinDistance(target, ALTAR_RADIUS) && world.getBlockState(altar).isOf(io.github.divineblock.DivineBlockMod.ALTAR)) return true;
        // The home is not spared: «ступай в дом свой и узри гнев мой» is meant to wreck it (Dmitriy, 23 Oct).
        return false;
    }
}
