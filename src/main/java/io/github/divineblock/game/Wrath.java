package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SkeletonHorseEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.Potions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** The great punishments: meteors that fall where the sinner looks and leave real craters, the earth opening, armies,
 * fire spirits, the Warden, exile to the Nether. Each one has a tail: aftershocks, a crumbling rim, a second volley, the
 * dead rising again, the void spreading. Blocks are changed only outside the altar and the home (see {@link Protection});
 * everything else is real. Server thread only; every delayed step checks that the sinner is still here. */
final class Wrath {
    private Wrath() { }

    /** He is still here to be punished: alive, not respawned into a new body, in this world. */
    private static boolean present(ServerWorld world, ServerPlayerEntity player) {
        return !player.isRemoved() && player.isAlive() && player.getServerWorld() == world;
    }

    /** The ground {@code distance} blocks where he looks. */
    private static BlockPos ahead(ServerWorld world, ServerPlayerEntity player, double distance) {
        Vec3d look = player.getRotationVector().multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
        look = look.normalize();
        return world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.ofFloored(player.getX() + look.x * distance, 0, player.getZ() + look.z * distance));
    }

    /** The ground at a random spot {@code near}–{@code far} blocks from him, or from where he stood if he is gone. */
    private static BlockPos around(ServerWorld world, ServerPlayerEntity player, BlockPos origin, int near, int far) {
        Vec3d at = present(world, player) ? player.getPos() : Vec3d.ofCenter(origin);
        double angle = world.random.nextDouble() * Math.PI * 2, distance = near + world.random.nextInt(Math.max(1, far - near + 1));
        return world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.ofFloored(at.x + Math.cos(angle) * distance, 0, at.z + Math.sin(angle) * distance));
    }

    // ---- Meteors and craters. ----

    /** A meteor in front of the sinner's eyes: a burning stone falls from the sky, explodes, carves a crater that burns
     * outward, and leaves a smouldering core that magma cubes crawl out of; ash falls for half a minute. */
    static String meteor(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        fall(world, player, altar, ahead(world, player, 14 + world.random.nextInt(6)), power, config, 30);
        return "метеорит";
    }

    /** Many meteors around the sinner, one after another, under a minute of ash; the last one falls right beside him. */
    static String meteorShower(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        int count = 5 + world.random.nextInt(4);
        BlockPos origin = player.getBlockPos();
        Aftermath.ashRain(world, origin, 40, 60 + count * 14 / 20);
        Scheduler.repeat(14, count, i -> {
            boolean last = i == count - 1;
            fall(world, player, altar, last ? around(world, player, origin, 4, 6) : around(world, player, origin, 10, 38), last ? 4 : 3, config, 0);
        });
        return "метеоритный дождь: " + count + " камней, последний — у ног";
    }

    /** One burning stone from far away in the sky, beyond where it lands, flying in towards the sinner: a faint spark over
     * the horizon at first, a fireball rushing down at the end; on impact an explosion, a crater and what it leaves. */
    private static void fall(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos ground, int power, DivineConfig config, int ashSeconds) {
        Vec3d end = Vec3d.ofCenter(ground);
        Vec3d away = end.subtract(player.getPos()).multiply(1, 0, 1);
        if (away.lengthSquared() < 4) away = player.getRotationVector().multiply(1, 0, 1);
        if (away.lengthSquared() < 1e-4) away = new Vec3d(0, 0, 1);
        double turn = (world.random.nextDouble() - 0.5) * 1.2, distance = 340 + world.random.nextInt(100);
        away = away.normalize().rotateY((float) turn);
        Vec3d start = end.add(away.multiply(distance)).add(0, 200 + world.random.nextInt(60), 0);
        int steps = 110;
        // The stone, its tail and its smoke are drawn by the clients (Fx); here the sounds and the landing.
        Fx.meteor(world, start, end, steps, 0.8f + 0.3f * power);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.4f);
        Scheduler.later(steps - 30, () -> {
            Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WITHER_SHOOT, 3f, 0.4f);
            Miracles.sound(world, player.getBlockPos(), SoundEvents.ITEM_TRIDENT_THUNDER.value(), 2f, 0.6f);
        });
        Scheduler.later(steps, () -> { if (world.isChunkLoaded(ground)) impact(world, player, altar, ground, power, config, ashSeconds); });
    }

    private static void impact(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos ground, int power, DivineConfig config, int ashSeconds) {
        boolean spare = Protection.spares(world, player, altar, ground, config);
        Vec3d at = Vec3d.ofCenter(ground);
        world.createExplosion(null, at.x, at.y, at.z, 4f + 2 * power, !spare, spare ? World.ExplosionSourceType.NONE : World.ExplosionSourceType.TNT);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 3, 2, 1, 2, 0);
        Fx.impact(world, at, Fx.FIRE, 1 + power * 0.6f);
        if (ashSeconds > 0) Aftermath.ashRain(world, ground, 16 + 2 * power, ashSeconds);
        if (spare) return;
        int radius = 3 + 2 * power;
        crater(world, player, altar, ground, radius, config);
        BlockPos floor = ground.down(radius * 2 / 3 + 1);
        // The crater does not cool: fire runs out over the land, lava seeps through the floor, the stone itself smoulders.
        Aftermath.lavaVeins(world, player, altar, floor, radius, config);
        Aftermath.spreadFire(world, player, altar, ground, radius + 8, 20 + 5 * power, config);
        core(world, player, altar, floor.up(), power, config);
    }

    /** The meteorite itself, lying on the crater floor: a lump of magma and blackstone that smokes for a minute and a half,
     * and magma cubes crawling out of it. */
    private static void core(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos rest, int power, DivineConfig config) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx * dx + dy * dy + dz * dz > 2 || (dy == 1 && dx * dx + dz * dz > 1 && world.random.nextBoolean())) continue;
            pos.set(rest.getX() + dx, rest.getY() + dy, rest.getZ() + dz);
            BlockState state = world.getBlockState(pos);
            if (!(state.isAir() || state.isReplaceable()) || Protection.spares(world, player, altar, pos, config)) continue;
            world.setBlockState(pos, (world.random.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.MAGMA_BLOCK).getDefaultState(), Block.NOTIFY_ALL);
        }
        Vec3d top = Vec3d.ofBottomCenter(rest.up(2));
        Scheduler.repeat(20, 90, i -> {
            if (!world.isChunkLoaded(rest)) return;
            world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, top.x, top.y, top.z, 3, 0.6, 0.2, 0.6, 0.02);
            if (i % 4 == 0) world.spawnParticles(ParticleTypes.LAVA, top.x, top.y, top.z, 2, 0.5, 0.2, 0.5, 0);
            if (i % 8 == 0) Miracles.sound(world, rest, SoundEvents.BLOCK_LAVA_AMBIENT, 1.5f, 0.6f);
        });
        int cubes = 1 + power / 2;
        Scheduler.repeat(50, cubes, i -> {
            if (!world.isChunkLoaded(rest) || !(EntityType.MAGMA_CUBE.create(world) instanceof net.minecraft.entity.mob.MagmaCubeEntity cube)) return;
            cube.setSize(power >= 4 && i == 0 ? 3 : 2, true);
            cube.refreshPositionAndAngles(top.x, top.y, top.z, world.random.nextFloat() * 360, 0);
            cube.setPersistent();
            if (present(world, player)) cube.setTarget(player);
            world.spawnEntity(cube);
            world.spawnParticles(ParticleTypes.FLAME, top.x, top.y, top.z, 30, 0.5, 0.5, 0.5, 0.05);
            Miracles.sound(world, rest, SoundEvents.ENTITY_MAGMA_CUBE_JUMP, 2f, 0.6f);
        });
    }

    /** A bowl of the given radius: earth removed, the floor scorched to magma and blackstone, an ore core, fire at the rim. */
    static void crater(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos center, int radius, DivineConfig config) {
        int depth = radius * 2 / 3;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat > radius) continue;
            int bottom = (int) Math.round(-depth * Math.sqrt(1 - (flat / radius) * (flat / radius)));
            for (int dy = bottom; dy <= radius; dy++) {
                pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                BlockState state = world.getBlockState(pos);
                if (state.isAir() || !breakable(world, pos, state) || Protection.spares(world, player, altar, pos, config)) continue;
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
            pos.set(center.getX() + dx, center.getY() + bottom - 1, center.getZ() + dz);
            BlockState floor = world.getBlockState(pos);
            if (!floor.isAir() && breakable(world, pos, floor) && !Protection.spares(world, player, altar, pos, config)) {
                Block scorched = flat < radius * 0.35 ? Blocks.MAGMA_BLOCK : world.random.nextInt(3) == 0 ? Blocks.BLACKSTONE : Blocks.NETHERRACK;
                world.setBlockState(pos, scorched.getDefaultState(), Block.NOTIFY_LISTENERS);
                if (scorched == Blocks.NETHERRACK && world.random.nextInt(4) == 0 && world.getBlockState(pos.up()).isAir())
                    world.setBlockState(pos.up(), Blocks.FIRE.getDefaultState());
            }
        }
        BlockPos core = center.down(depth + 1);
        if (!Protection.spares(world, player, altar, core, config) && breakable(world, core, world.getBlockState(core))) {
            world.setBlockState(core, Blocks.ANCIENT_DEBRIS.getDefaultState());
            if (breakable(world, core.down(), world.getBlockState(core.down()))) world.setBlockState(core.down(), Blocks.DIAMOND_ORE.getDefaultState());
        }
    }

    private static boolean breakable(ServerWorld world, BlockPos pos, BlockState state) {
        return !(state.getBlock() instanceof AltarBlock) && state.getHardness(world, pos) >= 0 && state.getHardness(world, pos) < 50;
    }

    // ---- The earth. ----

    /** The ground under the sinner gives way: a shaft 25 blocks deep, water at the bottom (lava at power 4); then for twenty
     * seconds the rim keeps crumbling in ever wider rings, and the slope drags him toward the hole. */
    static String sinkhole(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        BlockPos feet = player.getBlockPos();
        if (Protection.spares(world, player, altar, feet.down(), config)) return null;
        int radius = 3, depth = Math.min(28, feet.getY() - world.getBottomY() - 4);
        if (depth < 6) return null;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dy = 1; dy <= depth; dy++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius + 1) continue;
            pos.set(feet.getX() + dx, feet.getY() - dy, feet.getZ() + dz);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && breakable(world, pos, state) && !Protection.spares(world, player, altar, pos, config))
                world.setBlockState(pos, dy == depth ? (power >= 4 ? Blocks.LAVA : Blocks.WATER).getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        world.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, player.getX(), player.getY(), player.getZ(), 40, 2, 0.5, 2, 0.02);
        Miracles.sound(world, feet, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 2f, 0.5f);
        Miracles.sound(world, feet, SoundEvents.BLOCK_GRAVEL_BREAK, 3f, 0.5f);
        Scheduler.repeat(20, 20, i -> crumble(world, player, altar, feet, radius + 1 + i / 3, depth, config));
        return "земля разверзлась на " + depth + " блоков, и края осыпаются";
    }

    /** One second of the rim giving way: columns on a ring around the shaft sink in a funnel, their tops sliding down. */
    private static void crumble(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos feet, int ring, int depth, DivineConfig config) {
        if (!world.isChunkLoaded(feet)) return;
        int floor = feet.getY() - Math.max(2, depth - (ring - 3) * 4); // Shallower further out: a funnel.
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int p = 0; p < 6 + ring; p++) {
            double angle = world.random.nextDouble() * Math.PI * 2;
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.ofFloored(feet.getX() + 0.5 + Math.cos(angle) * ring, 0, feet.getZ() + 0.5 + Math.sin(angle) * ring)).down();
            if (top.getY() <= floor || Protection.spares(world, player, altar, top, config)) continue;
            for (int y = top.getY() - 1; y > floor; y--) {
                pos.set(top.getX(), y, top.getZ());
                BlockState state = world.getBlockState(pos);
                if (!state.isAir() && breakable(world, pos, state) && !Protection.spares(world, player, altar, pos, config))
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
            BlockState surface = world.getBlockState(top);
            if (!surface.isAir() && breakable(world, top, surface)) FallingBlockEntity.spawnFromBlock(world, top, surface).dropItem = false;
            world.spawnParticles(ParticleTypes.CLOUD, top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, 4, 0.4, 0.2, 0.4, 0.02);
        }
        Miracles.sound(world, feet, SoundEvents.BLOCK_GRAVEL_BREAK, 2.5f, 0.4f + world.random.nextFloat() * 0.2f);
        // The slope slides toward the hole and takes him with it.
        if (present(world, player) && player.isOnGround()) {
            Vec3d pull = Vec3d.ofBottomCenter(feet).subtract(player.getPos()).multiply(1, 0, 1);
            if (pull.lengthSquared() > 1 && pull.lengthSquared() < (ring + 2) * (ring + 2)) {
                player.addVelocity(pull.normalize().multiply(0.18));
                player.velocityModified = true;
            }
        }
    }

    /** Ten seconds of quaking: nausea, cracks opening, the surface sliding down as falling blocks. Half a minute later an
     * aftershock widens the cracks; half a minute after that, another. */
    static String earthquake(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        Miracles.effect(player, StatusEffects.NAUSEA, 260, 0);
        Miracles.effect(player, StatusEffects.SLOWNESS, 200, 1);
        BlockPos center = player.getBlockPos();
        int radius = 8 + 4 * power;
        List<BlockPos> cracks = new ArrayList<>();
        shake(world, player, altar, center, radius, 40, 4 + power, 0, cracks, config);
        for (int level = 1; level <= 2; level++) {
            int widen = level;
            Scheduler.later(600 * level, () -> {
                if (present(world, player) && player.getBlockPos().isWithinDistance(center, radius * 2)) {
                    Miracles.effect(player, StatusEffects.NAUSEA, 140, 0);
                    Miracles.effect(player, StatusEffects.SLOWNESS, 100, 0);
                }
                Miracles.sound(world, center, SoundEvents.ENTITY_WARDEN_ROAR, 2f, 0.3f);
                shake(world, player, altar, center, radius * (4 - widen) / 4, 24 - 6 * widen, Math.max(2, (4 + power) / 2), widen, cracks, config);
            });
        }
        return "землетрясение и два афтершока";
    }

    /** {@code steps} quarter-seconds of quaking around a fixed centre; with {@code widen} above zero the old cracks also
     * grow by that many blocks on each side. */
    private static void shake(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos center, int radius, int steps, int perStep,
                              int widen, List<BlockPos> cracks, DivineConfig config) {
        int share = widen > 0 ? cracks.size() / steps + 1 : 0;
        Scheduler.repeat(5, steps, i -> {
            if (!world.isChunkLoaded(center)) return;
            Miracles.sound(world, center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2f, 0.4f);
            if (i % 4 == 0) Miracles.sound(world, center, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1f, 0.3f);
            for (int n = 0; n < perStep; n++) {
                BlockPos column = center.add(world.random.nextInt(radius * 2 + 1) - radius, 0, world.random.nextInt(radius * 2 + 1) - radius);
                BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column).down();
                if (Protection.spares(world, player, altar, top, config)) continue;
                crack(world, top, 2 + world.random.nextInt(4), true);
                if (cracks.size() < 160) cracks.add(top);
                world.spawnParticles(ParticleTypes.CLOUD, top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, 6, 0.4, 0.2, 0.4, 0.02);
            }
            for (int k = 0; k < share; k++) {
                int index = i * share + k;
                if (index >= cracks.size()) break;
                BlockPos old = cracks.get(index);
                for (int dx = -widen; dx <= widen; dx++) for (int dz = -widen; dz <= widen; dz++) {
                    if ((dx == 0 && dz == 0) || Math.abs(dx) + Math.abs(dz) > widen + 1) continue;
                    BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, old.add(dx, 0, dz)).down();
                    if (Protection.spares(world, player, altar, top, config)) continue;
                    crack(world, top, 3 + 2 * widen, world.random.nextInt(3) == 0);
                }
                world.spawnParticles(ParticleTypes.CLOUD, old.getX() + 0.5, old.getY() + 1, old.getZ() + 0.5, 8, widen, 0.2, widen, 0.02);
            }
        });
    }

    /** A narrow slot opens under {@code top}; the surface block slides into it as a falling block, if asked. */
    private static void crack(ServerWorld world, BlockPos top, int deep, boolean slide) {
        for (int d = 1; d <= deep; d++) {
            BlockPos under = top.down(d);
            if (breakable(world, under, world.getBlockState(under))) world.setBlockState(under, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        BlockState surface = world.getBlockState(top);
        if (surface.isAir() || !breakable(world, top, surface)) return;
        if (slide) FallingBlockEntity.spawnFromBlock(world, top, surface).dropItem = false;
        else world.setBlockState(top, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    // ---- Things falling from the sky. ----

    /** Anvils that follow him: each is dropped where he is heading, ever tighter, and the last ones fall all at once in a
     * close ring around him and straight on his head. */
    static String anvilRain(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        int count = 6 + 4 * power;
        Vec3d[] previous = { player.getPos() };
        Scheduler.repeat(3, count, i -> {
            if (!present(world, player)) return;
            // Where he will be when it lands, about twenty ticks from now.
            Vec3d lead = player.getPos().subtract(previous[0]).multiply(20 / 3.0, 0, 20 / 3.0);
            previous[0] = player.getPos();
            double spread = 3.5 - 2.5 * i / count;
            anvil(world, player.getX() + lead.x + world.random.nextGaussian() * spread, player.getEyeY(),
                    player.getZ() + lead.z + world.random.nextGaussian() * spread);
        });
        Scheduler.later(3 * count + 20, () -> {
            if (!present(world, player)) return;
            for (int k = 0; k < 8; k++) {
                double angle = Math.PI * 2 * k / 8;
                anvil(world, player.getX() + Math.cos(angle) * 1.6, player.getEyeY(), player.getZ() + Math.sin(angle) * 1.6);
            }
            anvil(world, player.getX(), player.getEyeY(), player.getZ());
            Miracles.sound(world, player.getBlockPos(), SoundEvents.BLOCK_ANVIL_PLACE, 2f, 0.5f);
        });
        return count + " наковален вдогонку и кольцо последних над головой";
    }

    /** A damaged anvil in the open air above (x, z), as high as the sky lets it, up to 24 blocks. */
    private static void anvil(ServerWorld world, double x, double fromY, double z) {
        BlockPos.Mutable pos = new BlockPos.Mutable(MathHelper.floor(x), MathHelper.floor(fromY) + 1, MathHelper.floor(z));
        if (!world.getBlockState(pos).isAir()) return;
        int climbed = 0;
        while (climbed < 24 && world.getBlockState(pos.up()).isAir()) { pos.move(0, 1, 0); climbed++; }
        if (climbed < 4) return; // A roof over him: no room to fall.
        BlockPos sky = pos.toImmutable();
        world.setBlockState(sky, Blocks.DAMAGED_ANVIL.getDefaultState(), Block.NOTIFY_LISTENERS);
        FallingBlockEntity anvil = FallingBlockEntity.spawnFromBlock(world, sky, Blocks.DAMAGED_ANVIL.getDefaultState());
        anvil.dropItem = false;
        anvil.setHurtEntities(2f, 40);
    }

    /** Burning dynamite around him; when that is over, a second volley lands where he ran to. */
    static String tntRain(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        if (Protection.spares(world, player, altar, player.getBlockPos(), config)) return null;
        int count = power >= 4 ? 16 : 9;
        Scheduler.repeat(4, count, i -> {
            if (present(world, player)) tnt(world, player, altar, player.getX(), player.getY(), player.getZ(), 6, 50 + world.random.nextInt(30), config);
        });
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_TNT_PRIMED, 3f, 0.6f);
        int second = count / 2 + 4;
        Scheduler.later(4 * count + 90, () -> {
            if (!present(world, player)) return;
            Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_TNT_PRIMED, 3f, 0.4f);
            Scheduler.repeat(3, second, i -> {
                if (present(world, player)) tnt(world, player, altar, player.getX(), player.getY(), player.getZ(), 3, 35 + world.random.nextInt(20), config);
            });
        });
        return count + " горящих динамитов с неба и второй залп вдогонку";
    }

    private static void tnt(ServerWorld world, ServerPlayerEntity player, BlockPos altar, double cx, double cy, double cz, double spread, int fuse, DivineConfig config) {
        double x = cx + world.random.nextGaussian() * spread, z = cz + world.random.nextGaussian() * spread;
        BlockPos landing = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, BlockPos.ofFloored(x, 0, z));
        if (Protection.spares(world, player, altar, landing, config)) return;
        TntEntity tnt = new TntEntity(world, x, cy + 20, z, null);
        tnt.setFuse(fuse);
        world.spawnEntity(tnt);
    }

    // ---- Armies and monsters. ----

    /** Zombies (by their UUID) that will rise once more where they fall, mapped to the sinner they hunt. */
    private static final Map<UUID, UUID> REVENANTS = new HashMap<>();
    private static boolean revenantsHooked;

    /** The dead rise in three waves, a quarter-minute apart, the last in diamond; every other zombie gets up again once. */
    static String undeadHorde(ServerWorld world, ServerPlayerEntity player, int power) {
        hookRevenants();
        int count = power >= 4 ? 20 : 12;
        int first = count / 2, second = count / 4, third = count - first - second;
        wave(world, player, first, power >= 4);
        Scheduler.later(300, () -> { if (present(world, player)) wave(world, player, second, power >= 4); });
        Scheduler.later(600, () -> { if (present(world, player)) wave(world, player, third, true); });
        return "восстала орда нежити: " + count + " в три волны, и павшие встают снова";
    }

    private static void wave(ServerWorld world, ServerPlayerEntity player, int count, boolean diamond) {
        for (int i = 0; i < count; i++) {
            MobEntity mob = (i % 3 == 2 ? EntityType.SKELETON : EntityType.ZOMBIE).create(world);
            if (mob == null) continue;
            armour(mob, diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET, diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE);
            if (mob.getType() == EntityType.SKELETON) mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            else mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(diamond ? Items.DIAMOND_SWORD : Items.IRON_SWORD));
            ring(world, player, mob, 5, 12, ParticleTypes.SOUL);
            if (mob instanceof ZombieEntity && i % 2 == 0) {
                if (REVENANTS.size() > 1024) REVENANTS.clear(); // Long-lost ones; never let it grow.
                REVENANTS.put(mob.getUuid(), player.getUuid());
            }
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_AMBIENT, 3f, 0.5f);
    }

    private static void hookRevenants() {
        if (revenantsHooked) return;
        revenantsHooked = true;
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            UUID sinner = REVENANTS.remove(entity.getUuid());
            if (sinner == null || !(entity.getWorld() instanceof ServerWorld world)) return;
            BlockPos at = entity.getBlockPos();
            ItemStack head = entity.getEquippedStack(EquipmentSlot.HEAD).copy(), chest = entity.getEquippedStack(EquipmentSlot.CHEST).copy(),
                    hand = entity.getEquippedStack(EquipmentSlot.MAINHAND).copy();
            world.spawnParticles(ParticleTypes.SOUL, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 20, 0.4, 0.3, 0.4, 0.02);
            Scheduler.later(60, () -> {
                if (!world.isChunkLoaded(at) || !(EntityType.ZOMBIE.create(world) instanceof ZombieEntity again)) return;
                again.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, world.random.nextFloat() * 360, 0);
                again.equipStack(EquipmentSlot.HEAD, head.isEmpty() ? new ItemStack(Items.IRON_HELMET) : head);
                again.equipStack(EquipmentSlot.CHEST, chest);
                again.equipStack(EquipmentSlot.MAINHAND, hand);
                for (EquipmentSlot slot : EquipmentSlot.values()) again.setEquipmentDropChance(slot, 0f);
                again.setPersistent();
                again.setCanPickUpLoot(false);
                ServerPlayerEntity target = world.getServer().getPlayerManager().getPlayer(sinner);
                if (target != null && present(world, target)) again.setTarget(target);
                world.spawnEntity(again);
                world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 40, 0.4, 0.9, 0.4, 0.03);
                world.spawnParticles(ParticleTypes.SOUL, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 30, 0.5, 1, 0.5, 0.05);
                Miracles.sound(world, at, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), 3f, 0.5f);
                Miracles.sound(world, at, SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE, 1.5f, 0.5f);
            });
        });
    }

    /** The raiders, and half a minute later the second wave: vexes and evokers, under the horn again. */
    static String raid(ServerWorld world, ServerPlayerEntity player, int power) {
        int spawned = 0;
        EntityType<?>[] army = power >= 4
                ? new EntityType<?>[] { EntityType.RAVAGER, EntityType.RAVAGER, EntityType.EVOKER, EntityType.EVOKER, EntityType.VINDICATOR, EntityType.VINDICATOR,
                        EntityType.VINDICATOR, EntityType.PILLAGER, EntityType.PILLAGER, EntityType.PILLAGER, EntityType.PILLAGER, EntityType.WITCH }
                : new EntityType<?>[] { EntityType.RAVAGER, EntityType.EVOKER, EntityType.VINDICATOR, EntityType.VINDICATOR, EntityType.PILLAGER,
                        EntityType.PILLAGER, EntityType.PILLAGER };
        for (EntityType<?> type : army) {
            if (!(type.create(world) instanceof MobEntity mob)) continue;
            ring(world, player, mob, 10, 16, ParticleTypes.SMOKE);
            spawned++;
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.EVENT_RAID_HORN.value(), 4f, 1f);
        Scheduler.later(600, () -> {
            if (!present(world, player)) return;
            for (int i = 0; i < (power >= 4 ? 8 : 5); i++) {
                if (!(EntityType.VEX.create(world) instanceof net.minecraft.entity.mob.VexEntity vex)) continue;
                ring(world, player, vex, 4, 9, ParticleTypes.WITCH);
                vex.setPosition(vex.getX(), vex.getY() + 3, vex.getZ());
                vex.setLifeTicks(20 * (50 + world.random.nextInt(30)));
            }
            for (int i = 0; i < (power >= 4 ? 2 : 1); i++) if (EntityType.EVOKER.create(world) instanceof MobEntity evoker) ring(world, player, evoker, 8, 13, ParticleTypes.SMOKE);
            if (power >= 4 && EntityType.RAVAGER.create(world) instanceof MobEntity ravager) ring(world, player, ravager, 10, 15, ParticleTypes.SMOKE);
            Miracles.sound(world, player.getBlockPos(), SoundEvents.EVENT_RAID_HORN.value(), 4f, 0.8f);
        });
        return "войско разбойников: " + spawned + ", за ним вторая волна";
    }

    /** Fire spirits, and a ring of fire on netherrack around the sinner that will not go out (two rings in great fury). */
    static String blazeHorde(ServerWorld world, ServerPlayerEntity player, int power) {
        int count = power >= 4 ? 20 : 8;
        for (int i = 0; i < count; i++) {
            MobEntity blaze = EntityType.BLAZE.create(world);
            if (blaze == null) continue;
            ring(world, player, blaze, 6, 14, ParticleTypes.FLAME);
            blaze.setPosition(blaze.getX(), blaze.getY() + 3 + world.random.nextInt(4), blaze.getZ());
        }
        DivineConfig config = DivineBlockMod.config();
        int fires = fireRing(world, player, player.getBlockPos(), 5, config);
        if (power >= 4) fires += fireRing(world, player, player.getBlockPos(), 9, config);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_AMBIENT, 4f, 0.6f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ITEM_FIRECHARGE_USE, 2f, 0.5f);
        return "огненные духи: " + count + " ифритов" + (fires > 0 ? " и кольцо вечного огня" : "");
    }

    /** Netherrack laid into the ground on a circle and set alight, so the fire burns until someone breaks it. */
    private static int fireRing(ServerWorld world, ServerPlayerEntity player, BlockPos center, int radius, DivineConfig config) {
        Set<Long> seen = new HashSet<>();
        int placed = 0;
        for (int k = 0, n = (int) (Math.PI * 2 * radius * 1.5); k < n; k++) {
            double angle = Math.PI * 2 * k / n;
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.ofFloored(center.getX() + 0.5 + Math.cos(angle) * radius, 0, center.getZ() + 0.5 + Math.sin(angle) * radius));
            if (!seen.add(top.asLong())) continue;
            BlockPos ground = top.down();
            BlockState state = world.getBlockState(ground);
            world.spawnParticles(ParticleTypes.FLAME, top.getX() + 0.5, top.getY() + 0.3, top.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.02);
            if (Protection.spares(world, player, null, ground, config) || !state.isSolidBlock(world, ground) || !breakable(world, ground, state)
                    || !(world.getBlockState(top).isAir() || world.getBlockState(top).isReplaceable())) continue;
            world.setBlockState(ground, Blocks.NETHERRACK.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(top, Blocks.FIRE.getDefaultState(), Block.NOTIFY_ALL);
            placed++;
        }
        return placed;
    }

    /** The Warden from the depths, and the deep dark coming up with him: sculk spreads around the sinner for fifteen seconds,
     * with sensors, and a second Warden if he stays on it. */
    static String warden(ServerWorld world, ServerPlayerEntity player) {
        BlockPos at = ahead(world, player, 7);
        WardenEntity warden = EntityType.WARDEN.spawn(world, at, SpawnReason.TRIGGERED);
        if (warden == null) return null;
        warden.increaseAngerAt(player, 150, false);
        warden.setPersistent();
        DivineConfig config = DivineBlockMod.config();
        BlockPos origin = player.getBlockPos();
        Scheduler.repeat(10, 30, i -> {
            if (!world.isChunkLoaded(origin)) return;
            BlockPos center = present(world, player) && player.getBlockPos().isWithinDistance(origin, 24) ? player.getBlockPos() : origin;
            int reach = 3 + i / 3;
            BlockPos last = null;
            for (int n = 0; n < 12; n++) {
                BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        center.add(world.random.nextInt(reach * 2 + 1) - reach, 0, world.random.nextInt(reach * 2 + 1) - reach)).down();
                BlockState state = world.getBlockState(top);
                if (state.isOf(Blocks.SCULK)) { last = top; continue; }
                if (!state.isOpaqueFullCube(world, top) || !breakable(world, top, state) || Protection.spares(world, player, null, top, config)) continue;
                world.setBlockState(top, Blocks.SCULK.getDefaultState(), Block.NOTIFY_ALL);
                world.spawnParticles(ParticleTypes.SCULK_SOUL, top.getX() + 0.5, top.getY() + 1.1, top.getZ() + 0.5, 2, 0.3, 0.1, 0.3, 0.02);
                last = top;
            }
            if (i % 4 == 0) Miracles.sound(world, center, SoundEvents.BLOCK_SCULK_SPREAD, 2f, 0.6f);
            // Sensors to hear him (no shriekers: their darkness is the blindness the author does not want).
            if (last != null && (i == 6 || i == 12 || i == 18 || i == 24)) {
                BlockPos above = last.up();
                BlockState free = world.getBlockState(above);
                if ((free.isAir() || free.isReplaceable()) && !Protection.spares(world, player, null, above, config))
                    world.setBlockState(above, Blocks.SCULK_SENSOR.getDefaultState(), Block.NOTIFY_ALL);
            }
        });
        // The deep dark calls a second one if the sinner is still on its sculk after forty seconds.
        Scheduler.later(20 * 40, () -> {
            if (!present(world, player) || !player.getBlockPos().isWithinDistance(origin, 24)) return;
            WardenEntity second = EntityType.WARDEN.spawn(world, ahead(world, player, 9), SpawnReason.TRIGGERED);
            if (second == null) return;
            second.increaseAngerAt(player, 150, false);
            second.setPersistent();
            Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, 3f, 0.8f);
        });
        return "Хранитель восстал из глубин, и скалк ползёт за ним";
    }

    /** Exile to the Nether: the sinner wakes in the Nether at the matching spot, on solid ground, without a portal home,
     * and before he finds his feet two or three ghasts come to greet him. */
    static String hellExile(ServerWorld world, ServerPlayerEntity player) {
        ServerWorld nether = world.getServer().getWorld(World.NETHER);
        if (nether == null || world == nether) return null;
        int x = MathHelper.floor(player.getX() / 8), z = MathHelper.floor(player.getZ() / 8);
        BlockPos safe = null;
        for (int attempt = 0; attempt < 24 && safe == null; attempt++) {
            int ax = x + (attempt == 0 ? 0 : world.random.nextInt(33) - 16), az = z + (attempt == 0 ? 0 : world.random.nextInt(33) - 16);
            for (int y = 100; y > 32 && safe == null; y--) {
                BlockPos feet = new BlockPos(ax, y, az);
                if (nether.getBlockState(feet).isAir() && nether.getBlockState(feet.up()).isAir()
                        && nether.getBlockState(feet.down()).isSolidBlock(nether, feet.down()) && nether.getFluidState(feet.down()).isEmpty()) safe = feet;
            }
        }
        if (safe == null) {
            // No ledge found: the god carves a pocket in the netherrack and lays a floor of obsidian.
            safe = new BlockPos(x, 70, z);
            for (BlockPos pos : BlockPos.iterate(safe.add(-1, 0, -1), safe.add(1, 2, 1))) nether.setBlockState(pos, Blocks.AIR.getDefaultState());
            for (BlockPos pos : BlockPos.iterate(safe.add(-1, -1, -1), safe.add(1, -1, 1))) nether.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState());
        }
        Miracles.burst(world, player.getBlockPos(), ParticleTypes.SOUL_FIRE_FLAME, 80);
        player.teleport(nether, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, player.getYaw(), player.getPitch());
        Miracles.sound(nether, safe, SoundEvents.AMBIENT_NETHER_WASTES_MOOD.value(), 3f, 0.6f);
        Miracles.sound(nether, safe, SoundEvents.ENTITY_GHAST_SCREAM, 2f, 0.5f);
        Scheduler.later(60, () -> {
            if (!present(nether, player)) return;
            int ghasts = 2 + nether.random.nextInt(2), met = 0;
            for (int attempt = 0; attempt < ghasts * 4 && met < ghasts; attempt++) {
                if (!(EntityType.GHAST.create(nether) instanceof net.minecraft.entity.mob.GhastEntity ghast)) continue;
                double angle = nether.random.nextDouble() * Math.PI * 2, distance = 14 + nether.random.nextInt(10);
                ghast.refreshPositionAndAngles(player.getX() + Math.cos(angle) * distance, player.getY() + 6 + nether.random.nextInt(8),
                        player.getZ() + Math.sin(angle) * distance, nether.random.nextFloat() * 360, 0);
                if (!nether.isSpaceEmpty(ghast)) continue;
                ghast.setTarget(player);
                ghast.setPersistent();
                nether.spawnEntity(ghast);
                met++;
            }
            if (met > 0) Miracles.sound(nether, player.getBlockPos(), SoundEvents.ENTITY_GHAST_WARN, 3f, 0.7f);
        });
        return "изгнан в Ад, где его встречают гасты";
    }

    /** Puts a mob on the ground somewhere in a ring around the player, hostile to him, with a puff of the given particle. */
    private static void ring(ServerWorld world, ServerPlayerEntity player, MobEntity mob, int near, int far, ParticleEffect puff) {
        double angle = world.random.nextDouble() * Math.PI * 2, distance = near + world.random.nextInt(Math.max(1, far - near + 1));
        BlockPos at = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.ofFloored(player.getX() + Math.cos(angle) * distance, 0, player.getZ() + Math.sin(angle) * distance));
        mob.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, world.random.nextFloat() * 360, 0);
        mob.setTarget(player);
        mob.setPersistent();
        if (mob instanceof HostileEntity) mob.setCanPickUpLoot(false);
        world.spawnEntity(mob);
        world.spawnParticles(puff, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 20, 0.4, 0.8, 0.4, 0.02);
    }

    private static void armour(MobEntity mob, Item helmet, Item chest) {
        mob.equipStack(EquipmentSlot.HEAD, new ItemStack(helmet));
        mob.equipStack(EquipmentSlot.CHEST, new ItemStack(chest));
        mob.setEquipmentDropChance(EquipmentSlot.HEAD, 0.05f);
        mob.setEquipmentDropChance(EquipmentSlot.CHEST, 0.05f);
    }

    // ---- Exiles, bosses and the void. ----

    /** Exile to the End: the sinner lands on the obsidian platform before the dragon's island; enraged endermen come for
     * him there, and two shulkers on obsidian spurs wait to lift him off it. */
    static String endExile(ServerWorld world, ServerPlayerEntity player) {
        ServerWorld end = world.getServer().getWorld(World.END);
        if (end == null || world == end) return null;
        BlockPos spawn = ServerWorld.END_SPAWN_POS;
        for (BlockPos pos : BlockPos.iterate(spawn.add(-2, -1, -2), spawn.add(2, -1, 2))) end.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState());
        for (BlockPos pos : BlockPos.iterate(spawn.add(-2, 0, -2), spawn.add(2, 2, 2))) end.setBlockState(pos, Blocks.AIR.getDefaultState());
        // Two spurs off the platform's sides, for the shulkers to sit on.
        for (int side = -1; side <= 1; side += 2) for (int d = 3; d <= 4; d++) end.setBlockState(spawn.add(side * d, -1, 0), Blocks.OBSIDIAN.getDefaultState());
        Miracles.burst(world, player.getBlockPos(), ParticleTypes.REVERSE_PORTAL, 120);
        player.teleport(end, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYaw(), player.getPitch());
        Miracles.sound(end, spawn, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 4f, 0.8f);
        Scheduler.later(60, () -> {
            if (!present(end, player)) return;
            for (int side = -1; side <= 1; side += 2) {
                if (!(EntityType.SHULKER.create(end) instanceof net.minecraft.entity.mob.ShulkerEntity shulker)) continue;
                BlockPos seat = spawn.add(side * 4, 0, 0);
                if (!end.getBlockState(seat).isAir()) continue;
                shulker.refreshPositionAndAngles(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 0, 0);
                shulker.setPersistent();
                shulker.setTarget(player);
                end.spawnEntity(shulker);
            }
            int[][] corners = { { 2, 2 }, { -2, -2 }, { 2, -2 }, { -2, 2 } };
            for (int[] corner : corners) {
                if (!(EntityType.ENDERMAN.create(end) instanceof net.minecraft.entity.mob.EndermanEntity enderman)) continue;
                enderman.refreshPositionAndAngles(spawn.getX() + corner[0] + 0.5, spawn.getY(), spawn.getZ() + corner[1] + 0.5, end.random.nextFloat() * 360, 0);
                enderman.setPersistent();
                end.spawnEntity(enderman);
                enderman.setTarget(player); // Angers him: the stare, the scream.
                end.spawnParticles(ParticleTypes.PORTAL, enderman.getX(), enderman.getY() + 1.5, enderman.getZ(), 40, 0.4, 1, 0.4, 0.3);
            }
            Miracles.sound(end, spawn, SoundEvents.ENTITY_ENDERMAN_SCREAM, 3f, 0.7f);
        });
        return "изгнан в Край, к разъярённым эндерменам и шалкерам";
    }

    /** One wither or, in great fury, up to five, rising around the altar; the ground around the altar turns to soul sand
     * sown with wither roses. */
    static String withers(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int severity) {
        int spawned = 0;
        for (int i = 0; i < severity; i++) {
            net.minecraft.entity.boss.WitherEntity wither = EntityType.WITHER.create(world);
            if (wither == null) continue;
            double angle = Math.PI * 2 * i / severity;
            BlockPos at = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    altar.add((int) Math.round(Math.cos(angle) * 10), 0, (int) Math.round(Math.sin(angle) * 10))).up(2);
            wither.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            wither.onSummoned();
            world.spawnEntity(wither);
            spawned++;
        }
        if (spawned == 0) return null;
        int roses = blight(world, player, altar, DivineBlockMod.config());
        String withers = spawned == 1 ? "визер" : spawned + " визеров";
        return roses > 0 ? withers + " и кольцо роз иссушения у алтаря" : withers;
    }

    /** A band of soul sand 5–7 blocks out from the altar, half of it sown with wither roses. */
    private static int blight(ServerWorld world, ServerPlayerEntity player, BlockPos altar, DivineConfig config) {
        int roses = 0;
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) {
            int flat = dx * dx + dz * dz;
            if (flat < 25 || flat > 49) continue;
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, altar.add(dx, 0, dz));
            BlockPos ground = top.down();
            BlockState state = world.getBlockState(ground);
            if (Protection.spares(world, player, altar, ground, config) || !state.isSolidBlock(world, ground) || !breakable(world, ground, state)) continue;
            world.setBlockState(ground, Blocks.SOUL_SAND.getDefaultState(), Block.NOTIFY_ALL);
            world.spawnParticles(ParticleTypes.SOUL, top.getX() + 0.5, top.getY() + 0.3, top.getZ() + 0.5, 3, 0.3, 0.2, 0.3, 0.02);
            BlockState free = world.getBlockState(top);
            if (world.random.nextBoolean() && (free.isAir() || free.isReplaceable()) && !Protection.spares(world, player, altar, top, config)) {
                world.setBlockState(top, Blocks.WITHER_ROSE.getDefaultState(), Block.NOTIFY_ALL);
                roses++;
            }
        }
        return roses;
    }

    /** One ender dragon or up to five, circling the sinner as their fight's centre; for a minute and a half their fireballs
     * rain down around him, leaving clouds of dragon's breath. */
    static String dragons(ServerWorld world, ServerPlayerEntity player, int severity) {
        int spawned = 0;
        LivingEntity owner = null;
        for (int i = 0; i < severity; i++) {
            net.minecraft.entity.boss.dragon.EnderDragonEntity dragon = EntityType.ENDER_DRAGON.create(world);
            if (dragon == null) continue;
            double angle = Math.PI * 2 * i / severity;
            dragon.refreshPositionAndAngles(player.getX() + Math.cos(angle) * 30, player.getY() + 40 + i * 6, player.getZ() + Math.sin(angle) * 30,
                    world.random.nextFloat() * 360, 0);
            dragon.setFightOrigin(player.getBlockPos());
            dragon.setPersistent();
            world.spawnEntity(dragon);
            if (owner == null) owner = dragon;
            spawned++;
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 5f, 0.7f);
        if (spawned == 0) return null;
        LivingEntity shooter = owner;
        int perVolley = 1 + severity / 2;
        Scheduler.repeat(40, 45, i -> {
            if (!present(world, player)) return;
            for (int k = 0; k < perVolley; k++) {
                if (!(EntityType.DRAGON_FIREBALL.create(world) instanceof net.minecraft.entity.projectile.DragonFireballEntity ball)) continue;
                double x = player.getX() + world.random.nextGaussian() * 7, z = player.getZ() + world.random.nextGaussian() * 7;
                ball.refreshPositionAndAngles(x, player.getY() + 28, z, 0, 0);
                ball.setVelocity(world.random.nextGaussian() * 0.05, -1, world.random.nextGaussian() * 0.05);
                ball.accelerationPower = 0.08;
                if (shooter != null && !shooter.isRemoved()) ball.setOwner(shooter);
                world.spawnEntity(ball);
            }
            if (i % 3 == 0) Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_SHOOT, 3f, 0.7f);
        });
        return (spawned == 1 ? "дракон Края" : spawned + " драконов Края") + " и дождь драконьего огня";
    }

    /** Whole chunks cut out down through the bedrock into the void, layer by layer from the sky down, the one before the
     * sinner first and, in great fury, the ones around it. Then the void spreads: every half-minute one more neighbouring
     * chunk crumbles, the one nearest the sinner. The altar and the home stay. */
    static String chunkVoid(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int severity, DivineConfig config) {
        if (config.protectBlocks) return null;
        ChunkPos ahead = new ChunkPos(ahead(world, player, 20));
        int[][] order = { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int count = Math.max(1, Math.min(5, severity));
        Set<Long> cut = new HashSet<>();
        for (int c = 0; c < count; c++) {
            ChunkPos chunk = new ChunkPos(ahead.x + order[c][0], ahead.z + order[c][1]);
            cut.add(chunk.toLong());
            cutChunk(world, player, altar, chunk, c * 20, config);
        }
        int spreads = 2 + count;
        Scheduler.repeat(600, spreads, s -> {
            ChunkPos next = spread(world, player, cut);
            if (next == null) return;
            cut.add(next.toLong());
            Miracles.sound(world, next.getCenterAtY(player.getBlockY()), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 3f, 0.4f);
            cutChunk(world, player, altar, next, 0, config);
        });
        return (count == 1 ? "земля вырезана до бездны" : count + " участков земли вырезаны до бездны") + ", и бездна расползается";
    }

    /** The loaded chunk next to the void that is nearest the sinner (any, if he is gone). */
    private static ChunkPos spread(ServerWorld world, ServerPlayerEntity player, Set<Long> cut) {
        List<ChunkPos> edge = new ArrayList<>();
        int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (long packed : cut) {
            ChunkPos chunk = new ChunkPos(packed);
            for (int[] side : sides) {
                ChunkPos next = new ChunkPos(chunk.x + side[0], chunk.z + side[1]);
                if (!cut.contains(next.toLong()) && world.isChunkLoaded(next.x, next.z) && !edge.contains(next)) edge.add(next);
            }
        }
        if (edge.isEmpty()) return null;
        if (!present(world, player)) return edge.get(world.random.nextInt(edge.size()));
        ChunkPos his = player.getChunkPos();
        edge.sort((a, b) -> Integer.compare(a.getSquaredDistance(his), b.getSquaredDistance(his)));
        return edge.get(0);
    }

    private static void cutChunk(ServerWorld world, ServerPlayerEntity player, BlockPos altar, ChunkPos chunk, int delay, DivineConfig config) {
        int top = world.getTopY() - 1, bottom = world.getBottomY();
        int layersPerTick = 6;
        int[] y = { top };
        Scheduler.repeat(1, (top - bottom) / layersPerTick + 2 + delay, step -> {
            if (step < delay || !world.isChunkLoaded(chunk.x, chunk.z)) return;
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int layer = 0; layer < layersPerTick && y[0] >= bottom; layer++, y[0]--) {
                for (int x = chunk.getStartX(); x <= chunk.getEndX(); x++) for (int z = chunk.getStartZ(); z <= chunk.getEndZ(); z++) {
                    pos.set(x, y[0], z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir() || state.getBlock() instanceof AltarBlock || Protection.spares(world, player, altar, pos, config)) continue;
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
            }
            if (step % 10 == 0) {
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, chunk.getCenterX(), y[0] + 2, chunk.getCenterZ(), 120, 6, 2, 6, 0.1);
                Miracles.sound(world, new BlockPos(chunk.getCenterX(), y[0], chunk.getCenterZ()), SoundEvents.BLOCK_END_PORTAL_SPAWN, 3f, 0.5f);
            }
        });
    }

    /** A rain of meteors under a long fall of ash: the first where the sinner looks, the rest all around him, each with its
     * burning crater and smouldering core; the last falls right beside him. Up to twenty. */
    static String meteors(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        int count = power >= 4 ? 20 : power == 3 ? 12 : 6;
        BlockPos origin = player.getBlockPos();
        Aftermath.ashRain(world, origin, 48, 60 + count * 6 / 20);
        fall(world, player, altar, ahead(world, player, 14 + world.random.nextInt(6)), Math.min(4, power), config, 0);
        Scheduler.repeat(6, count - 1, i -> {
            boolean last = i == count - 2;
            fall(world, player, altar, last ? around(world, player, origin, 4, 6) : around(world, player, origin, 12, 46),
                    last ? Math.min(4, power) : Math.min(3, power), config, 0);
        });
        return count + " метеоритов, последний — у ног";
    }

    /** The horsemen: four named riders lead them, glowing, each with his own curse on whoever comes near — Мор sheds poison
     * clouds and shoots poisoned arrows, Война fights with a sword and fires up the host, Голод starves, Смерть withers. The
     * rest, skeletons in helmets on skeleton horses, ride behind. Up to thirty. */
    static String skeletonRiders(ServerWorld world, ServerPlayerEntity player, int power, int severity) {
        int count = power >= 4 ? Math.min(30, 20 + 2 * severity) : 12;
        MobEntity[] four = {
                horseman(world, player, EntityType.SKELETON, "Мор", Items.BOW, PotionContentsComponent.createStack(Items.TIPPED_ARROW, Potions.POISON)),
                horseman(world, player, EntityType.SKELETON, "Война", Items.IRON_SWORD, ItemStack.EMPTY),
                horseman(world, player, EntityType.SKELETON, "Голод", Items.BOW, ItemStack.EMPTY),
                horseman(world, player, EntityType.WITHER_SKELETON, "Смерть", Items.STONE_SWORD, ItemStack.EMPTY) };
        if (four[1] != null) four[1].addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, StatusEffectInstance.INFINITE, 1));
        int followers = 0;
        for (int i = 4; i < count; i++) {
            SkeletonHorseEntity horse = EntityType.SKELETON_HORSE.create(world);
            net.minecraft.entity.mob.SkeletonEntity rider = EntityType.SKELETON.create(world);
            if (horse == null || rider == null) continue;
            horse.setTame(true);
            ring(world, player, horse, 20, 30, ParticleTypes.SOUL_FIRE_FLAME);
            armour(rider, Items.IRON_HELMET, Items.CHAINMAIL_CHESTPLATE);
            rider.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            rider.refreshPositionAndAngles(horse.getX(), horse.getY(), horse.getZ(), horse.getYaw(), 0);
            rider.setTarget(player);
            rider.setPersistent();
            world.spawnEntity(rider);
            rider.startRiding(horse, true);
            followers++;
        }
        // Their auras, once a second for two and a half minutes, while each still rides and the sinner is here.
        Scheduler.repeat(20, 150, i -> {
            if (!present(world, player)) return;
            for (int h = 0; h < 4; h++) {
                MobEntity rider = four[h];
                if (rider == null || rider.isRemoved() || !rider.isAlive()) continue;
                double near = rider.squaredDistanceTo(player);
                switch (h) {
                    case 0 -> {
                        if (i % 3 != 0) break;
                        AreaEffectCloudEntity cloud = new AreaEffectCloudEntity(world, rider.getX(), rider.getY(), rider.getZ());
                        cloud.setOwner(rider);
                        cloud.setRadius(2.5f);
                        cloud.setRadiusGrowth(-0.02f);
                        cloud.setDuration(80);
                        cloud.setWaitTime(0);
                        cloud.addEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 0));
                        world.spawnEntity(cloud);
                    }
                    case 1 -> {
                        for (AbstractSkeletonEntity ally : world.getEntitiesByClass(AbstractSkeletonEntity.class, rider.getBoundingBox().expand(10), LivingEntity::isAlive))
                            if (ally != rider) ally.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 60, 0));
                        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, rider.getX(), rider.getY() + 2.5, rider.getZ(), 1, 0.3, 0.2, 0.3, 0);
                    }
                    case 2 -> {
                        if (near < 64) Miracles.effect(player, StatusEffects.HUNGER, 100, 2);
                        world.spawnParticles(ParticleTypes.ASH, rider.getX(), rider.getY() + 1.5, rider.getZ(), 12, 1.5, 1, 1.5, 0.01);
                    }
                    default -> {
                        if (near < 16) Miracles.effect(player, StatusEffects.WITHER, 60, 1);
                        world.spawnParticles(ParticleTypes.SOUL, rider.getX(), rider.getY() + 1.5, rider.getZ(), 4, 0.5, 0.8, 0.5, 0.02);
                    }
                }
            }
        });
        int named = 0;
        for (MobEntity rider : four) if (rider != null) named++;
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_SKELETON_HORSE_AMBIENT, 5f, 0.6f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.5f);
        return (named == 4 ? "четыре всадника — Мор, Война, Голод и Смерть" : "всадники смерти: " + named) + " — и за ними " + followers + " конных";
    }

    /** A named, glowing rider in diamond on a glowing skeleton horse, a little ahead of the host. */
    private static MobEntity horseman(ServerWorld world, ServerPlayerEntity player, EntityType<? extends AbstractSkeletonEntity> type, String name,
                                      Item weapon, ItemStack offhand) {
        SkeletonHorseEntity horse = EntityType.SKELETON_HORSE.create(world);
        AbstractSkeletonEntity rider = type.create(world);
        if (horse == null || rider == null) return null;
        horse.setTame(true);
        horse.setGlowing(true);
        ring(world, player, horse, 14, 20, ParticleTypes.SOUL_FIRE_FLAME);
        armour(rider, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE);
        rider.equipStack(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        rider.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        if (!offhand.isEmpty()) {
            rider.equipStack(EquipmentSlot.OFFHAND, offhand);
            rider.setEquipmentDropChance(EquipmentSlot.OFFHAND, 0f);
        }
        rider.setCustomName(Text.literal(name));
        rider.setCustomNameVisible(true);
        rider.setGlowing(true);
        rider.refreshPositionAndAngles(horse.getX(), horse.getY(), horse.getZ(), horse.getYaw(), 0);
        rider.setTarget(player);
        rider.setPersistent();
        world.spawnEntity(rider);
        rider.startRiding(horse, true);
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, horse.getX(), horse.getY() + 1, horse.getZ(), 60, 0.6, 1.2, 0.6, 0.04);
        return rider;
    }
}
