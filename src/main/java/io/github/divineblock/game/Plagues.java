package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** The plagues of Egypt and the fire of Sodom. Server thread only. */
final class Plagues {
    private Plagues() { }

    /** The plagues one after another with pauses: blood in the water, frogs, locusts, three days of darkness, murrain. */
    static String plagues(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) { return null; }

    /** Fire and brimstone over the whole neighbourhood; the creatures around turn into pillars of salt. */
    static String sodom(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) { return null; }
}
