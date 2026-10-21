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
    /** Lava blocks poured into rift floors per server tick. */
    static final int LAVA_PER_TICK = 1_200;
    /** The aftermath lasts about two minutes: twelve rounds ten seconds apart. */
    private static final int ROUNDS = 12, ROUND_TICKS = 200;

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
        carve(world, columns, true, () -> craterAftermath(world, player, altar, radius, config));
        return "великая воронка в " + radius * 2 + " блоков";
    }

    /** Once the bowl is open: lava runs over its floor, the rim burns, ash falls, the sky keeps thundering, and later the
     * ground beyond the rim cracks. */
    private static void craterAftermath(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int radius, DivineConfig config) {
        if (world.getServer() == null) return;
        Aftermath.lavaVeins(world, player, altar, altar, radius, config);
        int fires = 6 + world.random.nextInt(5);
        for (int i = 0; i < fires; i++) {
            BlockPos rim = rim(altar, radius + 2, world.random.nextDouble() * Math.PI * 2);
            Aftermath.spreadFire(world, player, altar, rim, 10 + world.random.nextInt(6), 40, config);
        }
        Aftermath.ashRain(world, altar, radius + 48, ROUNDS * ROUND_TICKS / 20);
        if (world.getRegistryKey() == World.OVERWORLD) world.setWeather(0, ROUNDS * ROUND_TICKS + 600, true, true);
        Scheduler.repeat(ROUND_TICKS * 3 / 4, ROUNDS, i -> {
            if (world.getServer() == null) return;
            BlockPos rim = rim(altar, radius + world.random.nextInt(12), world.random.nextDouble() * Math.PI * 2);
            BlockPos ground = Aftermath.surface(world, rim.getX(), rim.getZ());
            if (ground != null) Miracles.bolt(world, ground, true);
            Miracles.sound(world, altar, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 6f, 0.5f + world.random.nextFloat() * 0.2f);
        });
        Scheduler.later(20 * 30, () -> rimCracks(world, player, altar, radius, config));
    }

    /** Three to five cracks running outward from the rim, a dozen blocks deep. */
    private static void rimCracks(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int radius, DivineConfig config) {
        if (world.getServer() == null || config.protectBlocks) return;
        int count = 3 + world.random.nextInt(3);
        List<Column> columns = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int c = 0; c < count; c++) {
            double heading = world.random.nextDouble() * Math.PI * 2;
            double x = altar.getX() + Math.cos(heading) * (radius - 1), z = altar.getZ() + Math.sin(heading) * (radius - 1);
            int length = 15 + world.random.nextInt(21);
            for (int step = 0; step < length; step++) {
                heading += (world.random.nextDouble() - 0.5) * 0.4;
                x += Math.cos(heading); z += Math.sin(heading);
                int half = step < length * 2 / 3 && world.random.nextInt(3) > 0 ? 1 : 0;
                collect(world, player, altar, config, x, z, heading, half, seen, columns, 8 + world.random.nextInt(9));
            }
        }
        if (columns.isEmpty()) return;
        Miracles.sound(world, altar, SoundEvents.ENTITY_WARDEN_DIG, 6f, 0.4f);
        carve(world, columns, false, null);
    }

    private static BlockPos rim(BlockPos center, int radius, double angle) {
        return new BlockPos(center.getX() + (int) Math.round(Math.cos(angle) * radius), center.getY(), center.getZ() + (int) Math.round(Math.sin(angle) * radius));
    }

    /** The land splits: several rifts running out from near the sinner, 60–140 blocks long, down to the bedrock. */
    static String rifts(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int severity, DivineConfig config) {
        if (config.protectBlocks) return null;
        int count = Math.min(6, 2 + severity);
        List<Column> columns = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<double[]> ends = new ArrayList<>(); // x, z, heading: each rift's two growing tips
        for (int r = 0; r < count; r++) {
            double angle = world.random.nextDouble() * Math.PI * 2;
            double x = player.getX() + Math.cos(angle) * (8 + world.random.nextInt(20)), z = player.getZ() + Math.sin(angle) * (8 + world.random.nextInt(20));
            double heading = world.random.nextDouble() * Math.PI * 2;
            ends.add(new double[] { x, z, heading + Math.PI });
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
            ends.add(new double[] { x, z, heading });
        }
        columns.sort((a, b) -> Long.compare(dist(a, player.getBlockPos()), dist(b, player.getBlockPos())));
        Miracles.effect(player, StatusEffects.NAUSEA, 240, 0);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_ROAR, 4f, 0.4f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 4f, 0.3f);
        carve(world, columns, false, () -> riftsSpread(world, player, altar, config, columns, ends, seen));
        return count + " разломов до коренной породы";
    }

    /** For two minutes the rifts keep growing: every ten seconds each tip runs on a few blocks down to the bedrock, the new
     * floor and a share of the old one fill with lava, and dust rises with a rumble from the tips. */
    private static void riftsSpread(ServerWorld world, ServerPlayerEntity player, BlockPos altar, DivineConfig config,
                                    List<Column> carved, List<double[]> ends, java.util.Set<Long> seen) {
        Scheduler.repeat(ROUND_TICKS, ROUNDS, round -> {
            if (world.getServer() == null || config.protectBlocks) return;
            List<Column> grown = new ArrayList<>();
            for (double[] end : ends) {
                int steps = 4 + world.random.nextInt(5);
                for (int step = 0; step < steps; step++) {
                    end[2] += (world.random.nextDouble() - 0.5) * 0.35;
                    end[0] += Math.cos(end[2]); end[1] += Math.sin(end[2]);
                    int half = world.random.nextInt(round < ROUNDS / 2 ? 3 : 2);
                    collect(world, player, altar, config, end[0], end[1], end[2], half, seen, grown, -1);
                }
                BlockPos tip = Aftermath.surface(world, (int) Math.floor(end[0]), (int) Math.floor(end[1]));
                if (tip == null) continue;
                Aftermath.visible(world, ParticleTypes.CLOUD, tip.getX() + 0.5, tip.getY() + 1, tip.getZ() + 0.5, 40, 3, 1.5, 0.04);
                Aftermath.visible(world, ParticleTypes.LARGE_SMOKE, tip.getX() + 0.5, tip.getY(), tip.getZ() + 0.5, 15, 2, 1, 0.02);
                Miracles.sound(world, tip, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 3f, 0.3f);
            }
            if (!player.isRemoved()) Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_DIG, 3f, 0.4f);
            // The old floor floods a twelfth at a time, nearest the sinner first.
            int from = carved.size() * round / ROUNDS, to = carved.size() * (round + 1) / ROUNDS;
            fillLava(world, carved.subList(from, to));
            if (!grown.isEmpty()) carve(world, grown, false, () -> fillLava(world, grown));
        });
    }

    /** One step of a crack: the columns across it at the given half-width, to the bedrock or {@code deep} below the surface. */
    private static void collect(ServerWorld world, ServerPlayerEntity player, BlockPos altar, DivineConfig config, double x, double z, double heading,
                                int half, java.util.Set<Long> seen, List<Column> into, int deep) {
        for (int w = -half; w <= half; w++) {
            int cx = (int) Math.floor(x - Math.sin(heading) * w), cz = (int) Math.floor(z + Math.cos(heading) * w);
            if (!world.isChunkLoaded(cx >> 4, cz >> 4) || !seen.add(BlockPos.asLong(cx, 0, cz))) continue;
            if (Protection.spares(world, player, altar, new BlockPos(cx, altar.getY(), cz), config)) continue;
            int bottom = deep < 0 ? world.getBottomY() + 1 : world.getTopY(Heightmap.Type.WORLD_SURFACE, cx, cz) - deep;
            into.add(new Column(cx, cz, Math.max(world.getBottomY() + 1, bottom), false));
        }
    }

    /** A layer of lava on the floor of each column, a budgeted slice per tick. */
    private static void fillLava(ServerWorld world, List<Column> columns) {
        if (columns.isEmpty()) return;
        List<Column> work = new ArrayList<>(columns);
        int[] next = { 0 };
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (world.getServer() == null) return;
            int budget = LAVA_PER_TICK;
            BlockPos.Mutable pos = new BlockPos.Mutable();
            while (budget > 0 && next[0] < work.size()) {
                Column column = work.get(next[0]++);
                if (!world.isChunkLoaded(column.x() >> 4, column.z() >> 4)) continue;
                // The first open block above the bedrock teeth.
                for (int y = column.bottom(); y < column.bottom() + 8; y++) {
                    pos.set(column.x(), y, column.z());
                    if (!world.getBlockState(pos).isAir()) continue;
                    BlockState below = world.getBlockState(pos.down());
                    if (!below.isAir() && below.getFluidState().isEmpty()) {
                        world.setBlockState(pos, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
                        budget--;
                    }
                    break;
                }
            }
            if (next[0] < work.size()) Scheduler.later(1, step[0]);
        };
        step[0].run();
    }

    private static long dist(Column column, BlockPos center) {
        long dx = column.x() - center.getX(), dz = column.z() - center.getZ();
        return dx * dx + dz * dz;
    }

    /** Clears the columns a slice per tick; a crater's floor is scorched, a rift's walls crumble with dust. */
    private static void carve(ServerWorld world, List<Column> columns, boolean scorch, Runnable done) {
        int[] next = { 0 };
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (world.getServer() == null) return;
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
            else if (done != null) done.run();
        };
        if (!columns.isEmpty()) step[0].run();
        else if (done != null) done.run();
    }
}
