package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** The landscape-scale punishments: a crater of 30 to 150 blocks blown out of the altar itself, and rifts that split the land
 * down to the bedrock. Millions of blocks are too many for one tick, so the work is cut into columns and done a slice per
 * tick, outward from the centre, as a blast or a crack would spread. Only the master "spare blocks" setting stops them. */
final class Cataclysm {
    /** Blocks changed per server tick: enough to see it spread, not enough to freeze the game. */
    static final int BLOCKS_PER_TICK = 48_000;
    static final int[] CRATER_RADIUS = { 30, 55, 80, 110, 150 };

    private Cataclysm() { }

    /** A column of blocks to clear, from {@code bottom} up to the surface, then a scorched floor. */
    private record Column(int x, int z, int bottom, boolean floor) { }

    /** The great explosion: the altar is the epicentre, the bowl is 30–150 blocks across by severity, and nothing in it
     * survives, the altar included. */
    static String greatCrater(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int severity, DivineConfig config) {
        if (config.protectBlocks) return null;
        int radius = CRATER_RADIUS[Math.max(1, Math.min(5, severity)) - 1];
        int depth = (int) (radius * 0.55);
        List<Column> columns = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat > radius) continue;
            int bottom = altar.getY() - (int) Math.round(depth * Math.sqrt(1 - (flat / radius) * (flat / radius)));
            columns.add(new Column(altar.getX() + dx, altar.getZ() + dz, Math.max(world.getBottomY() + 1, bottom), true));
        }
        // Outward from the epicentre, as a blast travels.
        columns.sort((a, b) -> Long.compare(dist(a, altar), dist(b, altar)));
        world.createExplosion(null, altar.getX() + 0.5, altar.getY() + 1, altar.getZ() + 0.5, 12f, true, World.ExplosionSourceType.TNT);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, altar.getX() + 0.5, altar.getY() + 2, altar.getZ() + 0.5, 12, 6, 4, 6, 0);
        world.spawnParticles(ParticleTypes.FLASH, altar.getX() + 0.5, altar.getY() + 4, altar.getZ() + 0.5, 3, 0, 0, 0, 0);
        Miracles.sound(world, altar, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 8f, 0.3f);
        Miracles.sound(world, altar, SoundEvents.ENTITY_WITHER_DEATH, 6f, 0.5f);
        Miracles.effect(player, StatusEffects.NAUSEA, 200, 0);
        carve(world, columns, true);
        return "великая воронка в " + radius * 2 + " блоков";
    }

    /** The land splits: several rifts running out from near the sinner, 60–140 blocks long, down to the bedrock. */
    static String rifts(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int severity, DivineConfig config) {
        if (config.protectBlocks) return null;
        int count = Math.min(6, 2 + severity);
        List<Column> columns = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int r = 0; r < count; r++) {
            double angle = world.random.nextDouble() * Math.PI * 2;
            double x = player.getX() + Math.cos(angle) * (8 + world.random.nextInt(20)), z = player.getZ() + Math.sin(angle) * (8 + world.random.nextInt(20));
            double heading = world.random.nextDouble() * Math.PI * 2;
            int length = 60 + world.random.nextInt(81);
            for (int step = 0; step < length; step++) {
                heading += (world.random.nextDouble() - 0.5) * 0.35; // A crack wanders.
                x += Math.cos(heading); z += Math.sin(heading);
                // Wide in the middle, narrowing to a hairline at the ends.
                double taper = Math.sin(Math.PI * step / length);
                int half = Math.max(0, (int) Math.round((1.5 + 2.5 * taper) + world.random.nextGaussian() * 0.4));
                for (int w = -half; w <= half; w++) {
                    int cx = (int) Math.floor(x - Math.sin(heading) * w), cz = (int) Math.floor(z + Math.cos(heading) * w);
                    if (!seen.add(BlockPos.asLong(cx, 0, cz))) continue;
                    BlockPos column = new BlockPos(cx, altar.getY(), cz);
                    if (Protection.spares(world, player, altar, column, config)) continue;
                    columns.add(new Column(cx, cz, world.getBottomY() + 1, false));
                }
            }
        }
        columns.sort((a, b) -> Long.compare(dist(a, player.getBlockPos()), dist(b, player.getBlockPos())));
        Miracles.effect(player, StatusEffects.NAUSEA, 240, 0);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_ROAR, 4f, 0.4f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 4f, 0.3f);
        carve(world, columns, false);
        return count + " разломов до коренной породы";
    }

    private static long dist(Column column, BlockPos center) {
        long dx = column.x() - center.getX(), dz = column.z() - center.getZ();
        return dx * dx + dz * dz;
    }

    /** Clears the columns a slice per tick; a crater's floor is scorched, a rift's walls crumble with dust. */
    private static void carve(ServerWorld world, List<Column> columns, boolean scorch) {
        int[] next = { 0 };
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            int budget = BLOCKS_PER_TICK;
            BlockPos.Mutable pos = new BlockPos.Mutable();
            while (budget > 0 && next[0] < columns.size()) {
                Column column = columns.get(next[0]++);
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, column.x(), column.z());
                for (int y = top; y >= column.bottom(); y--) {
                    pos.set(column.x(), y, column.z());
                    BlockState state = world.getBlockState(pos);
                    budget--;
                    if (state.isAir() || state.getHardness(world, pos) < 0) continue; // Bedrock and the like stay.
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
                if (scorch && column.floor()) {
                    pos.set(column.x(), column.bottom() - 1, column.z());
                    BlockState floor = world.getBlockState(pos);
                    if (!floor.isAir() && floor.getHardness(world, pos) >= 0) {
                        int roll = world.random.nextInt(10);
                        world.setBlockState(pos, (roll < 2 ? Blocks.MAGMA_BLOCK : roll < 5 ? Blocks.BLACKSTONE : Blocks.NETHERRACK).getDefaultState(),
                                Block.NOTIFY_LISTENERS);
                        if (roll == 9 && world.getBlockState(pos.up()).isAir()) world.setBlockState(pos.up(), Blocks.FIRE.getDefaultState());
                    }
                }
                if (next[0] % 400 == 0)
                    world.spawnParticles(scorch ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CLOUD, column.x() + 0.5, column.bottom() + 2, column.z() + 0.5,
                            20, 2, 2, 2, 0.05);
            }
            if (next[0] % 4000 < 200) Miracles.sound(world, new BlockPos(columns.get(Math.min(next[0], columns.size()) - 1).x(), 64,
                    columns.get(Math.min(next[0], columns.size()) - 1).z()), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 3f, 0.4f);
            if (next[0] < columns.size()) Scheduler.later(1, step[0]);
        };
        if (!columns.isEmpty()) step[0].run();
    }
}
