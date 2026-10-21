package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** What the great punishments leave behind: ash falling for a long while, fire spreading from the impact, lava veins in a
 * crater floor. Shared by the meteors, the great crater, the rifts and Sodom. Server thread only. */
final class Aftermath {
    private Aftermath() { }

    /** Grey ash and soot drifting down around {@code center} for {@code seconds}. Particles only. */
    static void ashRain(ServerWorld world, BlockPos center, int radius, int seconds) { }

    /** Fire set on the burnable ground around {@code center}, spreading outward over {@code seconds}; respects {@link Protection}. */
    static void spreadFire(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos center, int radius, int seconds, DivineConfig config) { }

    /** Lava veins running out from the centre of a crater floor of the given radius; respects {@link Protection}. */
    static void lavaVeins(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos center, int radius, DivineConfig config) { }
}
