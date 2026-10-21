package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** The great punishments: meteors that fall where the sinner looks and leave real craters, the earth opening, armies,
 * fire spirits, the Warden, exile to the Nether. Blocks are changed only outside the altar and the home (see
 * {@link Protection}); everything else is real. Server thread only. */
final class Wrath {
    private Wrath() { }

    // ---- Meteors and craters. ----

    /** A meteor in front of the sinner's eyes: a burning stone falls from the sky, explodes, and carves a crater. */
    static String meteor(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        Vec3d look = player.getRotationVector().multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
        look = look.normalize();
        double distance = 14 + world.random.nextInt(6);
        BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.ofFloored(player.getX() + look.x * distance, 0, player.getZ() + look.z * distance));
        fall(world, player, altar, ground, power, config);
        return "метеорит";
    }

    /** Many meteors around the sinner, one after another. */
    static String meteorShower(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        int count = 5 + world.random.nextInt(4);
        Scheduler.repeat(14, count, i -> {
            double angle = world.random.nextDouble() * Math.PI * 2, distance = 10 + world.random.nextInt(28);
            BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.ofFloored(player.getX() + Math.cos(angle) * distance, 0, player.getZ() + Math.sin(angle) * distance));
            fall(world, player, altar, ground, 3, config);
        });
        return "метеоритный дождь: " + count + " камней";
    }

    /** One burning stone from 70 blocks up and aslant, with a trail of fire; on impact an explosion and a crater. */
    private static void fall(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos ground, int power, DivineConfig config) {
        Vec3d end = Vec3d.ofCenter(ground);
        Vec3d start = end.add(world.random.nextBoolean() ? 25 : -25, 70, world.random.nextBoolean() ? 25 : -25);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WITHER_SHOOT, 3f, 0.4f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ITEM_TRIDENT_THUNDER.value(), 2f, 0.6f);
        int steps = 40;
        // The stone, its tail and its smoke are drawn by the clients (Fx), faster as it falls (t²); here only the landing.
        Fx.meteor(world, start, end, steps, 0.8f + 0.3f * power);
        Scheduler.later(steps, () -> impact(world, player, altar, ground, power, config));
    }

    private static void impact(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos ground, int power, DivineConfig config) {
        boolean spare = Protection.spares(world, player, altar, ground, config);
        Vec3d at = Vec3d.ofCenter(ground);
        world.createExplosion(null, at.x, at.y, at.z, 4f + 2 * power, !spare, spare ? World.ExplosionSourceType.NONE : World.ExplosionSourceType.TNT);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 3, 2, 1, 2, 0);
        Fx.impact(world, at, Fx.FIRE, 1 + power * 0.6f);
        if (!spare) crater(world, player, altar, ground, 3 + 2 * power, config);
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

    /** The ground under the sinner gives way: a shaft 25 blocks deep, water at the bottom (lava at power 4). */
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
        return "земля разверзлась на " + depth + " блоков";
    }

    /** Ten seconds of quaking: nausea, cracks opening, the surface sliding down as falling blocks. */
    static String earthquake(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        Miracles.effect(player, StatusEffects.NAUSEA, 260, 0);
        Miracles.effect(player, StatusEffects.SLOWNESS, 200, 1);
        BlockPos center = player.getBlockPos();
        int radius = 8 + 4 * power;
        Scheduler.repeat(5, 40, i -> {
            Miracles.sound(world, center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2f, 0.4f);
            if (i % 4 == 0) Miracles.sound(world, center, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1f, 0.3f);
            for (int n = 0; n < 4 + power; n++) {
                BlockPos column = center.add(world.random.nextInt(radius * 2 + 1) - radius, 0, world.random.nextInt(radius * 2 + 1) - radius);
                BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column).down();
                if (Protection.spares(world, player, altar, top, config)) continue;
                // A crack: a narrow slot opens, and the block above it slides down as a falling block.
                for (int d = 1; d <= 2 + world.random.nextInt(4); d++) {
                    BlockPos under = top.down(d);
                    if (breakable(world, under, world.getBlockState(under))) world.setBlockState(under, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
                BlockState surface = world.getBlockState(top);
                if (!surface.isAir() && breakable(world, top, surface)) {
                    FallingBlockEntity falling = FallingBlockEntity.spawnFromBlock(world, top, surface);
                    falling.dropItem = false;
                }
                world.spawnParticles(ParticleTypes.CLOUD, top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, 6, 0.4, 0.2, 0.4, 0.02);
            }
        });
        return "землетрясение";
    }

    // ---- Things falling from the sky. ----

    static String anvilRain(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        int count = 6 + 4 * power;
        Scheduler.repeat(3, count, i -> {
            BlockPos sky = BlockPos.ofFloored(player.getX() + world.random.nextGaussian() * 3, player.getY() + 22 + world.random.nextInt(6),
                    player.getZ() + world.random.nextGaussian() * 3);
            if (!world.getBlockState(sky).isAir()) return;
            world.setBlockState(sky, Blocks.DAMAGED_ANVIL.getDefaultState(), Block.NOTIFY_LISTENERS);
            FallingBlockEntity anvil = FallingBlockEntity.spawnFromBlock(world, sky, Blocks.DAMAGED_ANVIL.getDefaultState());
            anvil.dropItem = false;
            anvil.setHurtEntities(2f, 40);
        });
        return count + " наковален с неба";
    }

    static String tntRain(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        if (Protection.spares(world, player, altar, player.getBlockPos(), config)) return null;
        int count = power >= 4 ? 16 : 9;
        Scheduler.repeat(4, count, i -> {
            double x = player.getX() + world.random.nextGaussian() * 6, z = player.getZ() + world.random.nextGaussian() * 6;
            BlockPos landing = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, BlockPos.ofFloored(x, 0, z));
            if (Protection.spares(world, player, altar, landing, config)) return;
            TntEntity tnt = new TntEntity(world, x, player.getY() + 20, z, null);
            tnt.setFuse(50 + world.random.nextInt(30));
            world.spawnEntity(tnt);
        });
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_TNT_PRIMED, 3f, 0.6f);
        return count + " горящих динамитов с неба";
    }

    // ---- Armies and monsters. ----

    static String undeadHorde(ServerWorld world, ServerPlayerEntity player, int power) {
        int count = power >= 4 ? 20 : 12;
        for (int i = 0; i < count; i++) {
            MobEntity mob = (i % 3 == 2 ? EntityType.SKELETON : EntityType.ZOMBIE).create(world);
            if (mob == null) continue;
            armour(mob, power >= 4 ? Items.DIAMOND_HELMET : Items.IRON_HELMET, power >= 4 ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE);
            if (mob.getType() == EntityType.SKELETON) mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            else mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(power >= 4 ? Items.DIAMOND_SWORD : Items.IRON_SWORD));
            ring(world, player, mob, 5, 12, ParticleTypes.SOUL);
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_AMBIENT, 3f, 0.5f);
        return "восстала орда нежити: " + count;
    }

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
        return "войско разбойников: " + spawned;
    }

    static String blazeHorde(ServerWorld world, ServerPlayerEntity player, int power) {
        int count = power >= 4 ? 20 : 8;
        for (int i = 0; i < count; i++) {
            MobEntity blaze = EntityType.BLAZE.create(world);
            if (blaze == null) continue;
            ring(world, player, blaze, 6, 14, ParticleTypes.FLAME);
            blaze.setPosition(blaze.getX(), blaze.getY() + 3 + world.random.nextInt(4), blaze.getZ());
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_AMBIENT, 4f, 0.6f);
        return "огненные духи: " + count + " ифритов";
    }

    static String warden(ServerWorld world, ServerPlayerEntity player) {
        Vec3d look = player.getRotationVector().multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
        look = look.normalize().multiply(7);
        BlockPos at = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, BlockPos.ofFloored(player.getX() + look.x, 0, player.getZ() + look.z));
        WardenEntity warden = EntityType.WARDEN.spawn(world, at, SpawnReason.TRIGGERED);
        if (warden == null) return null;
        warden.increaseAngerAt(player, 150, false);
        warden.setPersistent();
        return "Хранитель восстал из глубин";
    }

    /** Exile to the Nether: the sinner wakes in the Nether at the matching spot, on solid ground, without a portal home. */
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
        return "изгнан в Ад";
    }

    /** Puts a mob on the ground somewhere in a ring around the player, hostile to him, with a puff of the given particle. */
    private static void ring(ServerWorld world, ServerPlayerEntity player, MobEntity mob, int near, int far, net.minecraft.particle.ParticleEffect puff) {
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

    private static void armour(MobEntity mob, net.minecraft.item.Item helmet, net.minecraft.item.Item chest) {
        mob.equipStack(EquipmentSlot.HEAD, new ItemStack(helmet));
        mob.equipStack(EquipmentSlot.CHEST, new ItemStack(chest));
        mob.setEquipmentDropChance(EquipmentSlot.HEAD, 0.05f);
        mob.setEquipmentDropChance(EquipmentSlot.CHEST, 0.05f);
    }

    // ---- Exiles, bosses and the void. ----

    /** Exile to the End: the sinner lands on the obsidian platform before the dragon's island. */
    static String endExile(ServerWorld world, ServerPlayerEntity player) {
        ServerWorld end = world.getServer().getWorld(World.END);
        if (end == null || world == end) return null;
        BlockPos spawn = ServerWorld.END_SPAWN_POS;
        for (BlockPos pos : BlockPos.iterate(spawn.add(-2, -1, -2), spawn.add(2, -1, 2))) end.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState());
        for (BlockPos pos : BlockPos.iterate(spawn.add(-2, 0, -2), spawn.add(2, 2, 2))) end.setBlockState(pos, Blocks.AIR.getDefaultState());
        Miracles.burst(world, player.getBlockPos(), ParticleTypes.REVERSE_PORTAL, 120);
        player.teleport(end, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYaw(), player.getPitch());
        Miracles.sound(end, spawn, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 4f, 0.8f);
        return "изгнан в Край";
    }

    /** One wither or, in great fury, up to five, rising around the altar. */
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
        return spawned == 0 ? null : spawned == 1 ? "визер" : spawned + " визеров";
    }

    /** One ender dragon or up to five, circling the sinner as their fight's centre. */
    static String dragons(ServerWorld world, ServerPlayerEntity player, int severity) {
        int spawned = 0;
        for (int i = 0; i < severity; i++) {
            net.minecraft.entity.boss.dragon.EnderDragonEntity dragon = EntityType.ENDER_DRAGON.create(world);
            if (dragon == null) continue;
            double angle = Math.PI * 2 * i / severity;
            dragon.refreshPositionAndAngles(player.getX() + Math.cos(angle) * 30, player.getY() + 40 + i * 6, player.getZ() + Math.sin(angle) * 30,
                    world.random.nextFloat() * 360, 0);
            dragon.setFightOrigin(player.getBlockPos());
            dragon.setPersistent();
            world.spawnEntity(dragon);
            spawned++;
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 5f, 0.7f);
        return spawned == 0 ? null : spawned == 1 ? "дракон Края" : spawned + " драконов Края";
    }

    /** Whole chunks cut out down through the bedrock into the void, layer by layer from the sky down, the one before the
     * sinner first and, in great fury, the ones around it. The altar and the home stay. */
    static String chunkVoid(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int severity, DivineConfig config) {
        if (config.protectBlocks) return null;
        Vec3d look = player.getRotationVector().multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
        look = look.normalize();
        net.minecraft.util.math.ChunkPos ahead = new net.minecraft.util.math.ChunkPos(BlockPos.ofFloored(player.getX() + look.x * 20, 0, player.getZ() + look.z * 20));
        int[][] order = { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int count = Math.max(1, Math.min(5, severity));
        int top = world.getTopY() - 1, bottom = world.getBottomY();
        for (int c = 0; c < count; c++) {
            net.minecraft.util.math.ChunkPos chunk = new net.minecraft.util.math.ChunkPos(ahead.x + order[c][0], ahead.z + order[c][1]);
            int layersPerTick = 6, delay = c * 20;
            int[] y = { top };
            Scheduler.repeat(1, (top - bottom) / layersPerTick + 2 + delay, step -> {
                if (step < delay) return;
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
        return count == 1 ? "земля вырезана до бездны" : count + " участков земли вырезаны до бездны";
    }

    /** A rain of meteors: the first where the sinner looks, the rest all around him, each with its crater; up to twenty. */
    static String meteors(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        int count = power >= 4 ? 20 : power == 3 ? 12 : 6;
        meteor(world, player, altar, Math.min(4, power), config);
        Scheduler.repeat(6, count - 1, i -> {
            double angle = world.random.nextDouble() * Math.PI * 2, distance = 12 + world.random.nextInt(34);
            BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.ofFloored(player.getX() + Math.cos(angle) * distance, 0, player.getZ() + Math.sin(angle) * distance));
            fall(world, player, altar, ground, Math.min(3, power), config);
        });
        return count + " метеоритов";
    }

    /** The horsemen: skeletons in helmets on skeleton horses, riding at the sinner; up to thirty. */
    static String skeletonRiders(ServerWorld world, ServerPlayerEntity player, int power, int severity) {
        int count = power >= 4 ? Math.min(30, 20 + 2 * severity) : 12;
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            net.minecraft.entity.mob.SkeletonHorseEntity horse = EntityType.SKELETON_HORSE.create(world);
            net.minecraft.entity.mob.SkeletonEntity rider = EntityType.SKELETON.create(world);
            if (horse == null || rider == null) continue;
            horse.setTame(true);
            ring(world, player, horse, 14, 26, ParticleTypes.SOUL_FIRE_FLAME);
            armour(rider, Items.IRON_HELMET, Items.CHAINMAIL_CHESTPLATE);
            rider.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            rider.refreshPositionAndAngles(horse.getX(), horse.getY(), horse.getZ(), horse.getYaw(), 0);
            rider.setTarget(player);
            rider.setPersistent();
            world.spawnEntity(rider);
            rider.startRiding(horse, true);
            spawned++;
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_SKELETON_HORSE_AMBIENT, 5f, 0.6f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.5f);
        return "всадники смерти: " + spawned;
    }
}
