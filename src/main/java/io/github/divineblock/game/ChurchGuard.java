package io.github.divineblock.game;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** The church is inviolable: no punishment, boss, explosion, fire or flood may change a block of a church (the box of a
 * known {@link ChurchArea} site). What the player does with his own hands (breaking, placing, using items, commands other
 * than the god's) passes; so does a change of the same block's state (a candle put out, a door opened) and fire going out.
 * Called from the mixins on {@code World.setBlockState}, {@code World.breakBlock} and the explosion. */
public final class ChurchGuard {
    /** Depth of player actions on the server thread: the player's own hands may change the church. */
    private static final ThreadLocal<int[]> PLAYER = ThreadLocal.withInitial(() -> new int[1]);
    /** Refused changes since the start, for the self-test. */
    public static volatile int refused;

    private ChurchGuard() { }

    public static void playerBegins() { PLAYER.get()[0]++; }
    public static void playerEnds() { int[] depth = PLAYER.get(); if (depth[0] > 0) depth[0]--; }

    /** Whether to refuse setting {@code next} at pos. */
    public static boolean refuses(World world, BlockPos pos, BlockState next) {
        if (world.isClient() || PLAYER.get()[0] > 0 || !ChurchArea.isChurchBlock(world, pos)) return false;
        BlockState old = world.getBlockState(pos);
        if (old.isOf(next.getBlock()) || old.isOf(Blocks.FIRE) || old.isOf(Blocks.SOUL_FIRE)) return false;
        if (old.isAir() && next.isAir()) return false;
        refused++;
        return true;
    }

    /** Whether to refuse breaking the block at pos (the drops would fall before the change is refused). */
    public static boolean refusesBreak(World world, BlockPos pos) {
        if (world.isClient() || PLAYER.get()[0] > 0 || !ChurchArea.isChurchBlock(world, pos)) return false;
        BlockState old = world.getBlockState(pos);
        if (old.isAir() || old.isOf(Blocks.FIRE) || old.isOf(Blocks.SOUL_FIRE)) return false;
        refused++;
        return true;
    }

    /** An explosion spares the church's blocks (positions removed from its list before the world is affected). */
    public static boolean spares(World world, BlockPos pos) {
        return !world.isClient() && ChurchArea.isChurchBlock(world, pos) && !world.getBlockState(pos).isAir();
    }
}
