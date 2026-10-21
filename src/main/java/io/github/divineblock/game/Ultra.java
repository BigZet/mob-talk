package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** The flood, Babel, the judgement in the sky and the wiping of memory. Server thread only. */
final class Ultra {
    private Ultra() { }

    /** The water rises around the sinner up to the sky; it stays as a {@link Curses.Type#FLOOD} curse and goes down later. */
    static String flood(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) { return null; }

    /** The flood goes down: called when its curse runs out or is lifted. */
    static void recede(ServerWorld world, Curses.Curse flood) { }

    /** The buildings around fall from the top down. */
    static String babel(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) { return null; }

    /** The sinner is lifted high into the sky, sees the world, and falls. */
    static String judgement(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) { return null; }

    /** Bed, spawn point and recipes forgotten, belongings sealed in a chest at the altar, the sinner naked 5000 blocks away. */
    static String memoryWipe(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) { return null; }
}
