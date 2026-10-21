package io.github.divineblock.game;

import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.FrogEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3f;

/** The plagues of Egypt and the fire of Sodom. Server thread only; every later step checks that the sinner is still here. */
final class Plagues {
    private static final String[] ORDINALS = { "первая", "вторая", "третья", "четвёртая", "пятая", "шестая", "седьмая" };
    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.02f, 0.02f), 1.8f);
    private static final DustParticleEffect SWARM = new DustParticleEffect(new Vector3f(0.12f, 0.1f, 0.03f), 1.4f);
    private static final DustParticleEffect SORES = new DustParticleEffect(new Vector3f(0.45f, 0.5f, 0.1f), 1.2f);
    private static final BlockStateParticleEffect SALT = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.WHITE_CONCRETE_POWDER.getDefaultState());

    private Plagues() { }

    private record Plague(String name, String omen, Runnable act) { }

    // ---- The plagues of Egypt. ----

    /** The plagues one after another, 12–20 s apart: blood in the water, frogs, locusts, three days of darkness, murrain.
     * Severity 3 adds the sores, severity 4 (or great power) the hail of fire. */
    static String plagues(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        List<Plague> list = new ArrayList<>();
        list.add(new Plague("кровь", "Вода обратилась в кровь", () -> blood(world, player, config)));
        list.add(new Plague("жабы", "Жабы падают с неба", () -> frogs(world, player, severity)));
        list.add(new Plague("саранча", "Саранча пожирает поля", () -> locusts(world, player, altar, power, severity, config)));
        if (severity >= 3) list.add(new Plague("язвы", "Язвы на теле твоём", () -> sores(world, player)));
        if (severity >= 4 || power >= 4) list.add(new Plague("град с огнём", "Град, смешанный с огнём", () -> hail(world, player, altar, severity, config)));
        list.add(new Plague("тьма", "Тьма египетская", () -> darkness(world, player, config)));
        list.add(new Plague("мор", "Мор на скот твой", () -> murrain(world, player, config)));

        int delay = 0;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Plague plague = list.get(i);
            names.add(plague.name());
            String title = "Казнь " + ORDINALS[Math.min(i, ORDINALS.length - 1)];
            if (i == 0) { omen(world, player, title, plague.omen()); plague.act().run(); continue; }
            delay += 20 * (12 + world.random.nextInt(9));
            Scheduler.later(delay, () -> {
                if (!here(world, player)) return;
                omen(world, player, title, plague.omen());
                plague.act().run();
            });
        }
        return "казни египетские: " + String.join(", ", names);
    }

    /** Thunder, a title and a cloud of smoke before each plague. */
    private static void omen(ServerWorld world, ServerPlayerEntity player, String title, String subtitle) {
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.5f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.6f);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 3, player.getZ(), 40, 2, 0.5, 2, 0.01);
        title(player, Text.literal(title).formatted(Formatting.DARK_RED, Formatting.BOLD), Text.literal(subtitle).formatted(Formatting.RED));
    }

    /** The water turns to blood: the lingering curse, and red mist over every pond around him now. */
    private static void blood(ServerWorld world, ServerPlayerEntity player, DivineConfig config) {
        BlockPos at = player.getBlockPos();
        Curses.add(player, Curses.Type.BLOOD, at, 48, config);
        for (int n = 0; n < 160; n++) {
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, at.add(world.random.nextInt(65) - 32, 0, world.random.nextInt(65) - 32)).down();
            if (world.getFluidState(top).isIn(FluidTags.WATER))
                world.spawnParticles(BLOOD, top.getX() + 0.5, top.getY() + 1.1, top.getZ() + 0.5, 12, 0.6, 0.2, 0.6, 0);
        }
        world.spawnParticles(BLOOD, player.getX(), player.getY() + 1, player.getZ(), 120, 4, 1, 4, 0);
        Miracles.sound(world, at, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 2f, 0.5f);
    }

    /** A rain of frogs from the sky around him, and a few angry slimes among them. */
    private static void frogs(ServerWorld world, ServerPlayerEntity player, int severity) {
        int frogs = 20 + world.random.nextInt(11) + 2 * severity, slimes = 2 + severity / 2, every = Math.max(2, (frogs + slimes) / slimes);
        Scheduler.repeat(2, frogs + slimes, i -> {
            if (!here(world, player)) return;
            BlockPos ground = surface(world, player.getBlockPos().add((int) (world.random.nextGaussian() * 7), 0, (int) (world.random.nextGaussian() * 7)), player);
            double y = world.isSkyVisible(ground) ? ground.getY() + 8 + world.random.nextInt(4) : ground.getY() + 1;
            MobEntity mob;
            if (i % every == 1) {
                SlimeEntity slime = EntityType.SLIME.create(world);
                if (slime == null) return;
                slime.setSize(2, true);
                slime.setTarget(player);
                mob = slime;
            } else {
                FrogEntity frog = EntityType.FROG.create(world);
                if (frog == null) return;
                Registries.FROG_VARIANT.getRandom(world.random).ifPresent(frog::setVariant);
                mob = frog;
            }
            mob.refreshPositionAndAngles(ground.getX() + 0.5, y, ground.getZ() + 0.5, world.random.nextFloat() * 360, 0);
            world.spawnEntity(mob);
            if (world.random.nextInt(3) == 0) world.playSound(null, ground, SoundEvents.ENTITY_FROG_AMBIENT, mob.getSoundCategory(), 1.5f, 0.8f + world.random.nextFloat() * 0.4f);
        });
    }

    /** A dark swarm: silverfish, endermites and angry bees, and the fields and trees within 24 blocks eaten bare. */
    private static void locusts(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        for (int i = 0; i < 3 + severity; i++) swarm(world, player, EntityType.SILVERFISH.create(world), 5, 0);
        for (int i = 0; i < 2 + severity / 2; i++) swarm(world, player, EntityType.ENDERMITE.create(world), 5, 0);
        for (int i = 0; i < 3 + power; i++) {
            BeeEntity bee = EntityType.BEE.create(world);
            if (bee == null) continue;
            bee.setAngryAt(player.getUuid()); bee.chooseRandomAngerTime();
            swarm(world, player, bee, 4, 3);
        }
        // The cloud rolls over him for a few seconds.
        Scheduler.repeat(4, 20, i -> {
            if (!here(world, player)) return;
            world.spawnParticles(SWARM, player.getX(), player.getY() + 2.5, player.getZ(), 120, 6, 1.5, 6, 0);
            if (i % 5 == 0) Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_BEE_LOOP_AGGRESSIVE, 2f, 0.5f);
        });
        if (config.protectBlocks) return;
        // Every column within 24 blocks, a slice each step, so the work is spread over a few seconds.
        BlockPos center = player.getBlockPos();
        int radius = 24;
        List<BlockPos> columns = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) if (dx * dx + dz * dz <= radius * radius) columns.add(center.add(dx, 0, dz));
        Collections.shuffle(columns, new java.util.Random(world.random.nextLong()));
        int slice = 120, steps = (columns.size() + slice - 1) / slice;
        Scheduler.repeat(3, steps, step -> {
            if (!here(world, player)) return;
            for (int n = step * slice; n < Math.min(columns.size(), (step + 1) * slice); n++) devour(world, player, altar, columns.get(n), config);
        });
    }

    /** One column under the locusts: the crop or plant on top is eaten, a patch of leaves stripped. */
    private static void devour(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos column, DivineConfig config) {
        if (!world.isChunkLoaded(column.getX() >> 4, column.getZ() >> 4)) return;
        BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, column);
        BlockPos below = top.down();
        if (world.getBlockState(below).isIn(BlockTags.LEAVES)) {
            if (world.random.nextInt(3) != 0) return;
            for (BlockPos pos : BlockPos.iterate(below.add(-1, -1, -1), below.add(1, 0, 1))) {
                if (!world.getBlockState(pos).isIn(BlockTags.LEAVES) || world.random.nextInt(5) < 2 || Protection.spares(world, player, altar, pos, config)) continue;
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            world.spawnParticles(SWARM, below.getX() + 0.5, below.getY() + 1, below.getZ() + 0.5, 6, 0.6, 0.4, 0.6, 0);
            return;
        }
        BlockState plant = world.getBlockState(top);
        boolean crop = plant.getBlock() instanceof CropBlock, food = crop || plant.getBlock() instanceof StemBlock || plant.getBlock() instanceof SweetBerryBushBlock;
        if (!food && !(plant.getBlock() instanceof PlantBlock)) return;
        if (Protection.spares(world, player, altar, top, config)) return;
        // A sprout here and there survives as stubble; the rest is gone.
        if (crop && world.random.nextInt(3) == 0) world.setBlockState(top, ((CropBlock) plant.getBlock()).withAge(0), Block.NOTIFY_ALL);
        else world.setBlockState(top, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        if (food) world.spawnParticles(SWARM, top.getX() + 0.5, top.getY() + 0.5, top.getZ() + 0.5, 8, 0.4, 0.3, 0.4, 0);
    }

    private static void swarm(ServerWorld world, ServerPlayerEntity player, MobEntity mob, int spread, int above) {
        if (mob == null) return;
        Vec3d at = player.getPos().add(world.random.nextGaussian() * spread, above, world.random.nextGaussian() * spread);
        mob.refreshPositionAndAngles(at.x, at.y, at.z, world.random.nextFloat() * 360, 0);
        mob.setTarget(player);
        world.spawnEntity(mob);
    }

    /** Boils: a short poison and a longer hunger, and sickly dust on the skin. */
    private static void sores(ServerWorld world, ServerPlayerEntity player) {
        Miracles.effect(player, StatusEffects.POISON, 20 * 8, 0);
        Miracles.effect(player, StatusEffects.HUNGER, 20 * 40, 1);
        Scheduler.repeat(10, 12, i -> { if (here(world, player)) world.spawnParticles(SORES, player.getX(), player.getY() + 1, player.getZ(), 10, 0.35, 0.6, 0.35, 0); });
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 0.5f);
    }

    /** Hail mingled with fire: fire charges from the sky all around him (never straight onto him) and ice shards. */
    private static void hail(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int severity, DivineConfig config) {
        Scheduler.repeat(4, 20 + 4 * severity, i -> {
            if (!here(world, player)) return;
            brimstone(world, player, altar, around(world, player, player.getBlockPos(), 4, 16), config);
            world.spawnParticles(ParticleTypes.ITEM_SNOWBALL, player.getX(), player.getY() + 6, player.getZ(), 30, 8, 3, 8, 0.3);
            if (i % 6 == 0) Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.8f);
        });
    }

    /** Three days of darkness: the lingering curse keeps the night (no blindness), and a cold wind blows now. */
    private static void darkness(ServerWorld world, ServerPlayerEntity player, DivineConfig config) {
        Curses.add(player, Curses.Type.DARKNESS, player.getBlockPos(), 64, config);
        long time = world.getTimeOfDay(), hour = time % 24_000;
        if (hour < 14_000 || hour > 22_000) world.setTimeOfDay(time - hour + 18_000);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ITEM_ELYTRA_FLYING, 1.5f, 0.5f);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.AMBIENT_CAVE, 2f, 0.6f);
        Scheduler.repeat(5, 16, i -> { if (here(world, player)) world.spawnParticles(ParticleTypes.SNOWFLAKE, player.getX(), player.getY() + 2, player.getZ(), 40, 5, 2, 5, 0.15); });
    }

    /** Murrain: the lingering curse on the beasts around him, and the first ones fall at once. Pets are left to the curse. */
    private static void murrain(ServerWorld world, ServerPlayerEntity player, DivineConfig config) {
        Curses.add(player, Curses.Type.MURRAIN, player.getBlockPos(), 48, config);
        List<AnimalEntity> beasts = world.getEntitiesByClass(AnimalEntity.class, new Box(player.getBlockPos()).expand(32, 16, 32),
                beast -> beast.isAlive() && !(beast instanceof Tameable tame && tame.getOwnerUuid() != null));
        Collections.shuffle(beasts, new java.util.Random(world.random.nextLong()));
        for (int i = 0; i < Math.min(1 + world.random.nextInt(3), beasts.size()); i++) {
            AnimalEntity beast = beasts.get(i);
            world.spawnParticles(ParticleTypes.SQUID_INK, beast.getX(), beast.getY() + beast.getHeight(), beast.getZ(), 20, 0.4, 0.4, 0.4, 0.02);
            world.playSound(null, beast.getBlockPos(), SoundEvents.ENTITY_COW_DEATH, beast.getSoundCategory(), 1.5f, 0.5f);
            beast.damage(world.getDamageSources().magic(), beast.getMaxHealth() + 10f);
        }
    }

    // ---- Sodom. ----

    /** Fire and brimstone over the whole neighbourhood for half a minute or more; the creatures within 32 blocks of him turn
     * into pillars of salt a few at a time. Spared from the salt: tamed or owned beasts (they did not sin), his mount, a beast on
     * his leash, and the great ones (bosses, golems: 100 health or more). Looking back at the altar during the fire slows him
     * and crusts him with salt for a moment, never to death. With block protection on, the fire still burns flesh but lights
     * nothing and leaves no pillars. */
    static String sodom(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        BlockPos center = player.getBlockPos();
        int radius = Math.min(64, 44 + 4 * severity), seconds = 30 + 2 * severity, steps = seconds * 4;
        title(player, Text.literal("Огонь и сера").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal("Не оглядывайся назад").formatted(Formatting.RED));
        Miracles.sound(world, center, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.4f);
        Miracles.sound(world, center, SoundEvents.ENTITY_BLAZE_SHOOT, 2f, 0.4f);
        Aftermath.ashRain(world, center, radius, seconds + 30);
        int[] glance = { -1000, 0 };
        Scheduler.repeat(5, steps, step -> {
            if (!here(world, player)) return;
            int shots = 1 + (world.random.nextInt(3) < severity ? 1 : 0);
            for (int s = 0; s < shots; s++) {
                // Mostly anywhere over the land; one in six close to him, but never onto his head.
                BlockPos target = world.random.nextInt(6) == 0 ? around(world, player, player.getBlockPos(), 4, 8) : around(world, player, center, 0, radius);
                brimstone(world, player, altar, target, config);
            }
            if (step % 8 == 0) for (MobEntity mob : doomed(world, player, 4)) salt(world, player, altar, mob, config);
            if (step % 4 == 0) lookBack(world, player, altar, step, glance);
            if (step % 24 == 12) Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.5f + world.random.nextFloat() * 0.3f);
            if (step % 40 == 20 && !config.protectBlocks)
                Aftermath.spreadFire(world, player, altar, around(world, player, center, 12, radius), 6, 20, config);
            // The last of the wicked when the fire ends.
            if (step == steps - 1) for (MobEntity mob : doomed(world, player, 24)) salt(world, player, altar, mob, config);
        });
        return "Содом: огонь и сера на " + seconds + " с, твари вокруг — соляные столпы";
    }

    /** The living within 32 blocks of him that the salt may take, at most {@code limit}. */
    private static List<MobEntity> doomed(ServerWorld world, ServerPlayerEntity player, int limit) {
        List<MobEntity> mobs = world.getEntitiesByClass(MobEntity.class, new Box(player.getBlockPos()).expand(32, 16, 32), mob -> mob.isAlive()
                && !(mob instanceof Tameable tame && tame.getOwnerUuid() != null) && mob != player.getVehicle()
                && !(mob instanceof EnderDragonEntity) && !(mob instanceof WitherEntity) && mob.getMaxHealth() < 100f
                && !(mob.isLeashed() && mob.getLeashHolder() == player));
        Collections.shuffle(mobs, new java.util.Random(world.random.nextLong()));
        return mobs.size() > limit ? mobs.subList(0, limit) : mobs;
    }

    /** A creature becomes a pillar of salt: white dust, a crumble, and a white column of its height where it stood (only on
     * dry ground; fliers and swimmers just crumble). */
    private static void salt(ServerWorld world, ServerPlayerEntity player, BlockPos altar, MobEntity mob, DivineConfig config) {
        BlockPos base = mob.getBlockPos();
        int height = MathHelper.clamp(MathHelper.ceil(mob.getHeight()), 1, 3);
        boolean grounded = mob.isOnGround() && !mob.isTouchingWater();
        world.spawnParticles(SALT, mob.getX(), mob.getY() + mob.getHeight() / 2, mob.getZ(), 50, mob.getWidth() / 2, mob.getHeight() / 2, mob.getWidth() / 2, 0.1);
        world.spawnParticles(ParticleTypes.WHITE_ASH, mob.getX(), mob.getY() + mob.getHeight(), mob.getZ(), 30, 0.5, 0.5, 0.5, 0.02);
        world.spawnParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + 0.5, mob.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
        Miracles.sound(world, base, SoundEvents.BLOCK_SAND_BREAK, 1.5f, 0.6f);
        Miracles.sound(world, base, SoundEvents.BLOCK_CALCITE_BREAK, 1.5f, 0.5f);
        mob.removeAllPassengers();
        mob.discard();
        if (!grounded || config.protectBlocks) return;
        for (int dy = 0; dy < height; dy++) {
            BlockPos pos = base.up(dy);
            BlockState state = world.getBlockState(pos);
            if (!state.isReplaceable() || !state.getFluidState().isEmpty() || Protection.spares(world, player, altar, pos, config)) break;
            boolean top = dy == height - 1 && height > 1;
            world.setBlockState(pos, (top ? Blocks.WHITE_CONCRETE_POWDER : Blocks.CALCITE).getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    /** Lot's wife: looking back at the altar from afar during the fire. The first glance is a warning, later ones (3 s apart)
     * slow him and crust him with salt for a few seconds. Never lethal. */
    private static void lookBack(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int step, int[] glance) {
        if (altar == null) return;
        Vec3d toAltar = Vec3d.ofCenter(altar).subtract(player.getEyePos());
        double distance = toAltar.length();
        if (distance < 8 || distance > 128 || player.getRotationVec(1f).dotProduct(toAltar.normalize()) < 0.85) return;
        if (step - glance[0] < 12) return;
        glance[0] = step;
        if (glance[1]++ == 0) {
            Miracles.effect(player, StatusEffects.SLOWNESS, 40, 0);
            player.sendMessage(Text.literal("Не оглядывайся назад!").formatted(Formatting.GOLD), true);
            Miracles.sound(world, player.getBlockPos(), SoundEvents.BLOCK_SAND_STEP, 1.5f, 0.5f);
            return;
        }
        Miracles.effect(player, StatusEffects.SLOWNESS, 80, 2);
        Miracles.effect(player, StatusEffects.WEAKNESS, 100, 0);
        world.spawnParticles(SALT, player.getX(), player.getY() + 1, player.getZ(), 40, 0.35, 0.8, 0.35, 0.05);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.BLOCK_CALCITE_BREAK, 1.5f, 0.6f);
        player.sendMessage(Text.literal("Соль проступает на коже — не оглядывайся").formatted(Formatting.WHITE), true);
    }

    // ---- Fire from the sky. ----

    /** A fire charge falling from the sky onto {@code target}. Where its impact is spared ({@link Protection}) it bursts just
     * above the ground: it burns the flesh near it but lights nothing. */
    private static void brimstone(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos target, DivineConfig config) {
        if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) return;
        double height = world.getDimension().hasCeiling() ? 10 : 26 + world.random.nextInt(10);
        double y = Math.min(target.getY() + height, world.getTopY() - 2);
        Vec3d from = new Vec3d(target.getX() + 0.5 + world.random.nextGaussian() * 4, y, target.getZ() + 0.5 + world.random.nextGaussian() * 4);
        SmallFireballEntity ball = new SmallFireballEntity(world, from.x, from.y, from.z, Vec3d.ofCenter(target).subtract(from));
        world.spawnEntity(ball);
        watch(world, player, altar, ball, config, 0);
    }

    /** Looks ahead of the falling charge each tick; a spared impact point puts it out before it lands. */
    private static void watch(ServerWorld world, ServerPlayerEntity player, BlockPos altar, SmallFireballEntity ball, DivineConfig config, int age) {
        Scheduler.later(1, () -> {
            if (ball.isRemoved()) return;
            if (age > 120) { ball.discard(); return; }
            Vec3d now = ball.getPos(), ahead = now.add(ball.getVelocity().multiply(2.5));
            HitResult hit = world.raycast(new RaycastContext(now, ahead, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ball));
            if (hit.getType() == HitResult.Type.MISS) { watch(world, player, altar, ball, config, age + 1); return; }
            BlockPos at = BlockPos.ofFloored(hit.getPos());
            if (!Protection.spares(world, player, altar, at, config)) return;
            ball.discard();
            Vec3d pos = hit.getPos();
            world.spawnParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.3, pos.z, 25, 0.5, 0.3, 0.5, 0.05);
            world.spawnParticles(ParticleTypes.LAVA, pos.x, pos.y + 0.3, pos.z, 4, 0.3, 0.2, 0.3, 0);
            world.playSound(null, at, SoundEvents.ENTITY_GENERIC_BURN, ball.getSoundCategory(), 1f, 0.8f);
            for (LivingEntity living : world.getEntitiesByClass(LivingEntity.class, new Box(pos, pos).expand(1.5), LivingEntity::isAlive)) {
                living.damage(world.getDamageSources().inFire(), 4f);
                living.setOnFireFor(4);
            }
        });
    }

    // ---- Helpers. ----

    /** The sinner is still with us: alive, in the game and in this world. */
    private static boolean here(ServerWorld world, ServerPlayerEntity player) {
        return !player.isRemoved() && player.isAlive() && player.getServerWorld() == world;
    }

    /** A point on the ground between {@code min} and {@code max} blocks from {@code center}. */
    private static BlockPos around(ServerWorld world, ServerPlayerEntity player, BlockPos center, int min, int max) {
        double angle = world.random.nextDouble() * Math.PI * 2, distance = min + Math.sqrt(world.random.nextDouble()) * (max - min);
        return surface(world, center.add((int) Math.round(Math.cos(angle) * distance), 0, (int) Math.round(Math.sin(angle) * distance)), player);
    }

    /** The first open block above the ground in this column; under a ceiling (the Nether) searched near his height. */
    private static BlockPos surface(ServerWorld world, BlockPos column, ServerPlayerEntity player) {
        if (!world.getDimension().hasCeiling()) return world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, column);
        int from = player.getBlockY() + 8;
        for (int y = from; y > from - 24; y--) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            if (world.getBlockState(pos).isAir() && !world.getBlockState(pos.down()).isAir()) return pos;
        }
        return column.withY(player.getBlockY());
    }

    private static void title(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 50, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }
}
