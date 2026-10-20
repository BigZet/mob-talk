package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.TornadoFunnel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.PillarBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The god's tornado, a clean-room reimplementation from a behavioural description of a storm mod: a bending funnel that
 * moves across the land, pulls living things in and flings them high, and peels exposed soft blocks (wood, leaves,
 * plants, glass, wool) off the surface in two-second waves, with the debris orbiting the funnel. Server side; the client
 * draws the funnel from {@link TornadoPayload}. */
public final class Tornadoes {
    private static final List<Tornado> ACTIVE = new ArrayList<>();
    private static int nextId = 1;
    /** Living debris cubes at once, for performance. */
    static final int MAX_DEBRIS = 80;

    private Tornadoes() { }

    /** A tornado of stage F{@code stage} appears {@code distance} blocks away and comes for the player for {@code ticks}. */
    static String summon(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int stage, int ticks, DivineConfig config) {
        double angle = world.random.nextDouble() * Math.PI * 2, distance = 45 + world.random.nextInt(15);
        Tornado tornado = new Tornado(nextId++, world, player, altar, stage, ticks, config,
                player.getX() + Math.cos(angle) * distance, player.getZ() + Math.sin(angle) * distance);
        ACTIVE.add(tornado);
        if (world.getRegistryKey() == World.OVERWORLD) world.setWeather(0, ticks + 200, true, true);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 4f, 0.5f);
        return "торнадо F" + stage;
    }

    /** The base of the newest tornado, for the self-test's camera; null when none. */
    public static net.minecraft.util.math.Vec3d newest() {
        if (ACTIVE.isEmpty()) return null;
        Tornado tornado = ACTIVE.get(ACTIVE.size() - 1);
        return new net.minecraft.util.math.Vec3d(tornado.x, tornado.funnel.y[0], tornado.z);
    }

    static void tick() {
        for (Iterator<Tornado> it = ACTIVE.iterator(); it.hasNext(); ) {
            Tornado tornado = it.next();
            if (!tornado.tick()) { tornado.end(); it.remove(); }
        }
    }

    /** The world closes: the debris displays are removed so they are not saved, and the tornadoes are forgotten. */
    static void clear() {
        for (Tornado tornado : ACTIVE) { for (Tornado.Debris piece : tornado.debris) piece.entity().discard(); tornado.debris.clear(); }
        ACTIVE.clear();
    }

    static final class Tornado {
        final int id, stage, life;
        final ServerWorld world;
        final ServerPlayerEntity target;
        final BlockPos altar;
        final DivineConfig config;
        final TornadoFunnel funnel;
        double x, z, heading;
        int age;
        final Set<BlockPos> queued = new HashSet<>();
        final List<BlockPos> rip = new ArrayList<>();
        final List<Debris> debris = new ArrayList<>();

        Tornado(int id, ServerWorld world, ServerPlayerEntity target, BlockPos altar, int stage, int life, DivineConfig config, double x, double z) {
            this.id = id; this.world = world; this.target = target; this.altar = altar; this.stage = stage; this.life = life; this.config = config;
            this.x = x; this.z = z;
            this.funnel = new TornadoFunnel(stage);
            this.heading = Math.atan2(target.getZ() - z, target.getX() - x);
        }

        double groundY() { return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(x), MathHelper.floor(z)); }

        boolean tick() {
            age++;
            if (age > life || world.getServer() == null) return false;
            move();
            // Touchdown: the base slides from the cloud down to the ground over the first five seconds.
            double ground = groundY();
            double baseY = ground + Math.max(0, 70 * (1 - age / 100.0));
            funnel.tick(x, baseY, z);
            sync(true);
            if (age > 60) { pull(); peel(); }
            debris();
            effects(ground);
            return true;
        }

        /** Toward the sinner, re-aimed every five seconds with a wander, slower as it strengthens. */
        private void move() {
            if (age % 100 == 1 && !target.isRemoved() && target.getServerWorld() == world)
                heading = Math.atan2(target.getZ() - z, target.getX() - x) + (world.random.nextDouble() - 0.5) * Math.toRadians(40);
            heading += (world.random.nextDouble() - 0.5) * Math.toRadians(6);
            double speed = stage <= 1 ? 0.25 : Math.max(0.08, 0.2 - 0.02 * stage);
            x += Math.cos(heading) * speed;
            z += Math.sin(heading) * speed;
        }

        private void sync(boolean alive) {
            TornadoPayload payload = new TornadoPayload(id, x, funnel.y[0], z, stage, alive);
            for (ServerPlayerEntity player : world.getPlayers())
                if (player.squaredDistanceTo(x, player.getY(), z) < 512 * 512 && ServerPlayNetworking.canSend(player, TornadoPayload.ID))
                    ServerPlayNetworking.send(player, payload);
        }

        /** Living things within 200 blocks that stand under open sky are pushed by the funnel's force model. */
        private void pull() {
            Box box = new Box(x - 200, funnel.y[0] - 228, z - 200, x + 200, funnel.y[0] + 228, z + 200);
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, box, LivingEntity::isAlive)) {
                if (entity instanceof ServerPlayerEntity player && (player.isCreative() || player.isSpectator())) continue;
                if (Math.hypot(entity.getX() - x, entity.getZ() - z) >= 200 || !outside(entity)) continue;
                Vec3d velocity = entity.getVelocity();
                double[] push = funnel.force(entity.getX(), entity.getY(), entity.getZ(), velocity.y, armour(entity));
                entity.setVelocity(velocity.add(push[0], push[1], push[2]));
                entity.velocityModified = true;
                // Fall damage counts only once falling fast: the funnel's own spin does not bruise.
                if (entity.getVelocity().y > -0.8) entity.fallDistance = 0;
            }
        }

        /** Out in the open: nothing overhead, or open sky within five blocks to any side. */
        private boolean outside(LivingEntity entity) {
            BlockPos feet = entity.getBlockPos();
            if (world.getTopY(Heightmap.Type.MOTION_BLOCKING, feet.getX(), feet.getZ()) < entity.getY() + 1) return true;
            for (Direction direction : Direction.Type.HORIZONTAL) {
                Vec3d from = entity.getPos().add(0, 1, 0).add(Vec3d.of(direction.getVector()));
                Vec3d to = entity.getPos().add(0, 1, 0).add(Vec3d.of(direction.getVector()).multiply(5));
                if (world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, entity)).getType()
                        != net.minecraft.util.hit.HitResult.Type.MISS) continue;
                BlockPos far = BlockPos.ofFloored(to);
                if (world.getTopY(Heightmap.Type.MOTION_BLOCKING, far.getX(), far.getZ()) < to.y) return true;
            }
            return false;
        }

        /** Iron, gold and diamond armour weigh the wearer down a little: up to a third less push for a full set. */
        private static double armour(LivingEntity entity) {
            double weight = 0;
            double[] pieces = { 0.1, 0.2, 0.15, 0.1 };
            EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
            for (int i = 0; i < 4; i++) {
                ItemStack stack = entity.getEquippedStack(slots[i]);
                if (stack.getItem() instanceof ArmorItem armor && (armor.getMaterial() == ArmorMaterials.IRON
                        || armor.getMaterial() == ArmorMaterials.GOLD || armor.getMaterial() == ArmorMaterials.DIAMOND)) weight += pieces[i];
            }
            return 1 + 1.2 * weight;
        }

        /** Random samples in a cylinder around the centre; exposed soft blocks are queued and removed every two seconds. */
        private void peel() {
            if (config.protectBlocks) return;
            int budget = (int) (300 * new double[] { 1, 1, 1.2, 1.5, 1.8, 2.5 }[stage]);
            int radius = (int) funnel.baseRadius() + 1, samples = (stage + 1) * 500, ripped = 0;
            int minY = Math.max(world.getBottomY(), (int) funnel.y[0] - 10), maxY = world.getTopY();
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int i = 0; i < samples && budget > 0 && ripped < 12; i++) {
                int dx = world.random.nextInt(radius * 2 + 1) - radius, dz = world.random.nextInt(radius * 2 + 1) - radius;
                if (dx * dx + dz * dz > radius * radius) continue;
                pos.set(MathHelper.floor(x) + dx, minY + world.random.nextInt(Math.max(1, maxY - minY)), MathHelper.floor(z) + dz);
                BlockState state = world.getBlockState(pos);
                if (state.isAir()) continue;
                if (state.isOf(Blocks.GRASS_BLOCK) && exposed(pos) && !spared(pos)) { world.setBlockState(pos, Blocks.DIRT.getDefaultState()); continue; }
                if (!grabbable(state, pos)) continue;
                budget--;
                if (queued.contains(pos) || !exposed(pos) || spared(pos)) continue;
                BlockPos fixed = pos.toImmutable();
                queued.add(fixed); rip.add(fixed); ripped++;
                spawnDebris(fixed, state);
                if (state.isIn(BlockTags.IMPERMEABLE)) world.playSound(null, fixed, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 5f, 1f);
            }
            if (age % 40 == 0) {
                for (BlockPos queuedPos : rip) world.setBlockState(queuedPos, Blocks.AIR.getDefaultState());
                rip.clear(); queued.clear();
            }
        }

        private boolean spared(BlockPos pos) { return Protection.spares(world, target, altar, pos, config); }

        private boolean exposed(BlockPos pos) {
            if (world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ()) - 1 == pos.getY()) return true;
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = pos.offset(direction);
                if (world.getTopY(Heightmap.Type.MOTION_BLOCKING, side.getX(), side.getZ()) <= pos.getY()) return true;
            }
            return false;
        }

        /** Soft or wooden things the wind can tear away; soil, sand, logs, stone, ice, snow, water and containers stay. */
        private boolean grabbable(BlockState state, BlockPos pos) {
            if (state.isOf(Blocks.FIRE) || state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.CHEST) || state.isOf(Blocks.JUKEBOX)) return false;
            if (state.isOf(Blocks.ICE) || state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.POWDER_SNOW)) return false;
            if (state.isIn(BlockTags.DIRT) || state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND) || state.isOf(Blocks.DIRT_PATH)) return false;
            MapColor color = state.getMapColor(world, pos);
            if (color == MapColor.WATER_BLUE) return false;
            if (state.getBlock() instanceof PillarBlock && color == MapColor.OAK_TAN) return false;
            if (state.getBlock() instanceof AltarBlock) return false;
            float hardness = state.getHardness(world, pos);
            if (hardness < 0) return false;
            double effective = hardness - (new ItemStack(Items.DIAMOND_AXE).getMiningSpeedMultiplier(state) - 1) / 4;
            return effective <= 0.74 || color == MapColor.OAK_TAN || color == MapColor.WHITE || color == MapColor.DARK_GREEN
                    || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL)
                    || state.isIn(BlockTags.FLOWERS) || state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS);
        }

        // ---- Debris: the torn blocks fly round the funnel as small block displays, then fall away. ----

        record Debris(DisplayEntity.BlockDisplayEntity entity, int layer, double offset, int born) { }

        private void spawnDebris(BlockPos pos, BlockState state) {
            if (debris.size() >= MAX_DEBRIS) return;
            DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world);
            if (display == null) return;
            display.setBlockState(state.isAir() ? Blocks.DIRT.getDefaultState() : state);
            float size = 0.6f + world.random.nextFloat() * 0.5f;
            display.setTransformation(new AffineTransformation(new Vector3f(-size / 2, -size / 2, -size / 2),
                    new Quaternionf().rotateXYZ(world.random.nextFloat() * 6f, world.random.nextFloat() * 6f, 0), new Vector3f(size), null));
            display.setTeleportDuration(3);
            display.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0, 0);
            world.spawnEntity(display);
            debris.add(new Debris(display, 0, world.random.nextDouble() * Math.PI * 2, age));
        }

        private void debris() {
            for (Iterator<Debris> it = debris.iterator(); it.hasNext(); ) {
                Debris piece = it.next();
                int lived = age - piece.born();
                if (piece.entity().isRemoved() || lived > 240) { piece.entity().discard(); it.remove(); continue; }
                // Rising one layer every two ticks, orbiting at the ring radius of its layer, clockwise from above.
                int layer = Math.min(TornadoFunnel.LAYERS - 1, lived / 2 + 3);
                double radius = funnel.ringRadius(layer) * (0.5 + 0.5 * Math.sin(piece.offset() * 3));
                double angle = Math.toRadians(funnel.rotation[layer]) + piece.offset();
                piece.entity().requestTeleport(funnel.x[layer] + Math.cos(angle) * radius, funnel.y[layer], funnel.z[layer] + Math.sin(angle) * radius);
            }
        }

        private void effects(double ground) {
            if (age % 4 == 0) {
                // Dust sucked inward along the ground.
                for (int i = 0; i < 6; i++) {
                    double angle = world.random.nextDouble() * Math.PI * 2, distance = funnel.baseRadius() + 4 + world.random.nextInt(18);
                    double px = x + Math.cos(angle) * distance, pz = z + Math.sin(angle) * distance;
                    world.spawnParticles(ParticleTypes.CLOUD, px, ground + 1, pz, 0, (x - px) * 0.05, 0.05, (z - pz) * 0.05, 1);
                }
            }
            if (age % 120 == 0) {
                for (ServerPlayerEntity player : world.getPlayers()) {
                    double distance = Math.hypot(player.getX() - x, player.getZ() - z);
                    if (distance > 200) continue;
                    float volume = (float) (distance < 120 ? 1.5 : 0.8);
                    world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.WEATHER, volume, 0.6f);
                    if (distance < 120) world.playSound(null, player.getBlockPos(), SoundEvents.WEATHER_RAIN_ABOVE, SoundCategory.WEATHER, 1.5f, 0.5f);
                }
            }
        }

        /** The funnel dissolves: everyone near is granted slow falling, the debris drops, the client fades it out. */
        void end() {
            sync(false);
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, new Box(x - 120, funnel.y[0] - 50, z - 120, x + 120, funnel.y[0] + 200, z + 120), LivingEntity::isAlive))
                entity.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(StatusEffects.SLOW_FALLING, 600, 0));
            for (Debris piece : debris) piece.entity().discard();
            debris.clear();
            for (BlockPos pos : rip) world.setBlockState(pos, Blocks.AIR.getDefaultState());
        }
    }
}
