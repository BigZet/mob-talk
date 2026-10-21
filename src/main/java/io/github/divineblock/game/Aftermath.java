package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

/** What the great punishments leave behind: ash falling for a long while, fire spreading from the impact, lava veins in a
 * crater floor. Shared by the meteors, the great crater, the rifts and Sodom. Server thread only. */
final class Aftermath {
    /** Fires lit per half-second step, at most. */
    private static final int FIRES_PER_STEP = 18;

    private Aftermath() { }

    /** Grey ash and soot drifting down around {@code center} for {@code seconds}. Particles only. */
    static void ashRain(ServerWorld world, BlockPos center, int radius, int seconds) {
        long reach = (long) radius * radius;
        Scheduler.repeat(10, Math.max(1, seconds * 2), i -> {
            if (world.getServer() == null) return;
            // Particles travel only so far, so the ash is drawn around each player standing under it.
            for (ServerPlayerEntity player : world.getPlayers()) {
                double dx = player.getX() - center.getX() - 0.5, dz = player.getZ() - center.getZ() - 0.5;
                if (dx * dx + dz * dz > reach) continue;
                double y = player.getY() + 10;
                world.spawnParticles(player, ParticleTypes.ASH, true, player.getX(), y, player.getZ(), 60, 14, 5, 14, 0);
                world.spawnParticles(player, ParticleTypes.WHITE_ASH, true, player.getX(), y, player.getZ(), 30, 14, 5, 14, 0);
                world.spawnParticles(player, ParticleTypes.SMOKE, true, player.getX(), y + 4, player.getZ(), 8, 12, 2, 12, 0.01);
                if (world.random.nextInt(12) == 0)
                    Miracles.sound(world, player.getBlockPos().add(world.random.nextInt(9) - 4, 2, world.random.nextInt(9) - 4),
                            SoundEvents.BLOCK_FIRE_AMBIENT, 0.8f, 0.6f + world.random.nextFloat() * 0.3f);
            }
        });
    }

    /** Fire set on the burnable ground around {@code center}, spreading outward over {@code seconds}; respects {@link Protection}. */
    static void spreadFire(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos center, int radius, int seconds, DivineConfig config) {
        if (config.protectBlocks || radius <= 0) return;
        int steps = Math.max(1, seconds * 2);
        Scheduler.repeat(10, steps, i -> {
            if (world.getServer() == null) return;
            // The front creeps outward; behind it the fire is left to burn on its own.
            double outer = Math.max(1.5, radius * (i + 1.0) / steps), inner = Math.max(0, outer - Math.max(2.0, 2.0 * radius / steps));
            int lit = 0;
            for (int tries = 0; tries < FIRES_PER_STEP * 4 && lit < FIRES_PER_STEP; tries++) {
                double angle = world.random.nextDouble() * Math.PI * 2, distance = inner + world.random.nextDouble() * (outer - inner);
                int x = center.getX() + (int) Math.round(Math.cos(angle) * distance), z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
                BlockPos spot = surface(world, x, z);
                if (spot == null || !kindling(world, spot.down()) || Protection.spares(world, player, altar, spot, config)) continue;
                world.setBlockState(spot, AbstractFireBlock.getState(world, spot), Block.NOTIFY_ALL);
                if (++lit % 4 == 0) world.spawnParticles(ParticleTypes.FLAME, x + 0.5, spot.getY() + 0.5, z + 0.5, 6, 0.4, 0.4, 0.4, 0.02);
            }
            if (i % 8 == 0) Miracles.sound(world, center, SoundEvents.BLOCK_FIRE_AMBIENT, 2f, 0.5f);
        });
    }

    /** Lava veins running out from the centre of a crater floor of the given radius; respects {@link Protection}. */
    static void lavaVeins(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos center, int radius, DivineConfig config) {
        if (config.protectBlocks || radius <= 1) return;
        int count = 3 + world.random.nextInt(4);
        List<double[]> veins = new ArrayList<>(); // x, z, heading, steps left
        double start = world.random.nextDouble() * Math.PI * 2;
        for (int v = 0; v < count; v++) {
            double heading = start + Math.PI * 2 * v / count + (world.random.nextDouble() - 0.5) * 0.6;
            int length = Math.max(4, Math.min((int) (radius * 0.8), 6 + world.random.nextInt(Math.max(1, radius / 2))));
            veins.add(new double[] { center.getX() + 0.5, center.getZ() + 0.5, heading, length });
        }
        Miracles.sound(world, center, SoundEvents.BLOCK_LAVA_AMBIENT, 3f, 0.6f);
        // One block a vein every other tick, so the lava is seen running out of the centre.
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (world.getServer() == null) return;
            boolean running = false;
            for (double[] vein : veins) {
                if (vein[3] <= 0) continue;
                running = true;
                vein[2] += (world.random.nextDouble() - 0.5) * 0.7; // Lava meanders.
                vein[0] += Math.cos(vein[2]); vein[1] += Math.sin(vein[2]); vein[3]--;
                int x = (int) Math.floor(vein[0]), z = (int) Math.floor(vein[1]);
                BlockPos air = surface(world, x, z);
                if (air == null) continue;
                BlockPos bed = air.down();
                if (!meltable(world, bed) || Protection.spares(world, player, altar, bed, config)) continue;
                // Sunk one block into the floor, banked with magma so it stays in its channel.
                for (Direction side : Direction.Type.HORIZONTAL) {
                    BlockPos bank = bed.offset(side);
                    BlockState state = world.getBlockState(bank);
                    if (state.isAir() || state.getFluidState().isIn(FluidTags.LAVA) || state.isOf(Blocks.MAGMA_BLOCK)) continue;
                    if (world.random.nextInt(3) > 0 && meltable(world, bank) && !Protection.spares(world, player, altar, bank, config))
                        world.setBlockState(bank, Blocks.MAGMA_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
                world.setBlockState(bed, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
                if (world.random.nextInt(3) == 0) world.spawnParticles(ParticleTypes.LAVA, x + 0.5, air.getY() + 0.2, z + 0.5, 3, 0.3, 0.1, 0.3, 0);
            }
            if (running) Scheduler.later(2, step[0]);
        };
        step[0].run();
    }

    // ---- Helpers. ----

    /** The first open block above the ground of a column; null when its chunk is not loaded or the top is water. */
    static BlockPos surface(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z))) return null;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        if (y <= world.getBottomY() + 1) return null;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState here = world.getBlockState(pos);
        if (!(here.isAir() || here.isReplaceable()) || !here.getFluidState().isEmpty()) return null;
        if (!world.getBlockState(pos.down()).getFluidState().isEmpty()) return null;
        return pos;
    }

    /** Ground a fire can stand on and that the god means to burn: soil, wood, leaves, scorched stone. */
    private static boolean kindling(ServerWorld world, BlockPos ground) {
        BlockState state = world.getBlockState(ground);
        if (state.isIn(BlockTags.LEAVES)) return true;
        boolean burns = state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL) || state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.WOODEN_SLABS) || state.isIn(BlockTags.WOODEN_STAIRS) || state.isOf(Blocks.HAY_BLOCK)
                || state.isOf(Blocks.BOOKSHELF) || state.isOf(Blocks.NETHERRACK) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.BLACKSTONE);
        return burns && state.isSideSolidFullSquare(world, ground, Direction.UP);
    }

    private static boolean meltable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof AltarBlock || world.getBlockEntity(pos) != null) return false;
        float hardness = state.getHardness(world, pos);
        return hardness >= 0 && hardness < 50;
    }

    /** Particles drawn for every player within 512 blocks, not only the near ones. */
    static void visible(ServerWorld world, ParticleEffect particle, double x, double y, double z, int count, double spread, double rise, double speed) {
        for (ServerPlayerEntity player : world.getPlayers())
            if (player.squaredDistanceTo(x, y, z) < 512 * 512)
                world.spawnParticles(player, particle, true, x, y, z, count, spread, rise, spread, speed);
    }
}
