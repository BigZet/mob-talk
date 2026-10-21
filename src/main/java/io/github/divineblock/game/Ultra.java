package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.component.ComponentChanges;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** The flood, Babel, the judgement in the sky and the wiping of memory. Server thread only. */
final class Ultra {
    /** Blocks looked at per tick by a rising or falling water line. */
    private static final int WATER_BUDGET = 3072;
    /** Blocks brought down per tick by Babel. */
    private static final int BABEL_BUDGET = 160;

    private Ultra() { }

    // ---- The flood. ----

    /** The water rises around the sinner up to the sky; it stays as a {@link Curses.Type#FLOOD} curse and goes down later. */
    static String flood(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        if (config.protectBlocks) return null;
        // No rain where there is no sky, and water boils away in the Nether.
        if (world.getDimension().ultrawarm() || world.getDimension().hasCeiling() || !world.getDimension().hasSkyLight()) return null;
        BlockPos center = player.getBlockPos();
        int radius = Math.min(64, 40 + 8 * Math.max(0, severity - 1));
        // The water line starts on the lowest ground around, never below the sea (the sea is not ours to drain later).
        int base = Math.max(Math.max(lowestGround(world, center, radius) - 1, center.getY() - 24), world.getSeaLevel() - 1);
        base = Math.max(base, world.getBottomY() + 1);
        int top = Math.min(world.getTopY() - 1, Math.max(center.getY() + 40, base + 16));
        if (top <= base + 1) return null;

        Curses.Curse curse = Curses.add(player, Curses.Type.FLOOD, center, radius, config);
        curse.base = base;
        curse.top = base;
        DivineState state = DivineState.of(world.getServer());
        state.markDirty();
        long rain = Math.max(6000, Math.min(Integer.MAX_VALUE, curse.until - curse.start));
        world.setWeather(0, (int) rain, true, true);
        Miracles.sound(world, center, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 3f, 0.5f);
        Miracles.sound(world, center, SoundEvents.AMBIENT_UNDERWATER_ENTER, 2f, 0.4f);

        int first = base + 1;
        UUID id = player.getUuid();
        BooleanSupplier go = () -> state.curses.active.contains(curse) && !player.isDisconnected();
        sweep(world, center, radius, first, top, 14, false, go, pos -> {
            if (spared(world, player, altar, pos, config)) return;
            BlockState here = world.getBlockState(pos);
            if (!here.isAir() && !(here.isReplaceable() && here.getFluidState().isEmpty())) return;
            // Water lies on something: the ground, a floor, or the layer below; the first layer covers pits too.
            if (pos.getY() > first && world.getBlockState(pos.down()).isAir()) return;
            world.setBlockState(pos, Blocks.WATER.getDefaultState(), Block.NOTIFY_LISTENERS);
        }, y -> {
            curse.top = y;
            state.markDirty();
            if (y % 3 == 0) Miracles.sound(world, new BlockPos(center.getX(), y, center.getZ()), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.5f, 0.5f + world.random.nextFloat() * 0.2f);
            if (y % 7 == 0) {
                double angle = world.random.nextDouble() * Math.PI * 2, distance = world.random.nextInt(radius);
                BlockPos column = BlockPos.ofFloored(center.getX() + Math.cos(angle) * distance, 0, center.getZ() + Math.sin(angle) * distance);
                if (world.isChunkLoaded(column.getX() >> 4, column.getZ() >> 4)) Miracles.bolt(world, world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, column), true);
            }
            ServerPlayerEntity online = world.getServer().getPlayerManager().getPlayer(id);
            if (online != null && online.getServerWorld() == world && y == first + 3) Prayers.hint(online, "Вода поднимается. Ищи ковчег.");
        }, () -> { });
        DivineBlockMod.LOG.info("Божественный блок: потоп радиусом {} от y={} до y={}", radius, first, top);
        return "потоп: вода поднимается на " + (top - base) + " блоков в радиусе " + radius + ", сойдёт через " + days(curse);
    }

    /** The flood goes down: called when its curse runs out or is lifted. */
    static void recede(ServerWorld world, Curses.Curse flood) {
        if (flood.top <= flood.base) return;
        BlockPos center = flood.center();
        MinecraftServer server = world.getServer();
        UUID id = parse(flood.player);
        ServerPlayerEntity owner = id == null ? null : server.getPlayerManager().getPlayer(id);
        // His own pond at home is not the flood's water.
        BlockPos home = owner != null && owner.getSpawnPointPosition() != null && owner.getSpawnPointDimension() == world.getRegistryKey() ? owner.getSpawnPointPosition() : null;
        int homeRadius = DivineBlockMod.config() == null ? 16 : DivineBlockMod.config().protectRadius;
        world.setWeather(12000, 0, false, false);
        Miracles.sound(world, center, SoundEvents.AMBIENT_UNDERWATER_EXIT, 2f, 0.6f);
        // Down from the water line, a layer at a time; chunks far from anyone are loaded as the water reaches them.
        sweep(world, center, flood.radius, flood.top, flood.base + 1, 4, true, () -> true, pos -> {
            if (home != null && home.isWithinDistance(pos, homeRadius)) return;
            if (!world.getBlockState(pos).isOf(Blocks.WATER)) return;
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            // A memory of the water on the ground it covered.
            BlockPos below = pos.down();
            BlockState ground = world.getBlockState(below);
            if (ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.COARSE_DIRT) || ground.isOf(Blocks.PODZOL) || ground.isOf(Blocks.ROOTED_DIRT)) {
                int roll = world.random.nextInt(14);
                Block patch = roll < 3 ? Blocks.MUD : roll == 3 ? Blocks.CLAY : roll == 4 ? Blocks.SAND : null;
                if (patch != null) world.setBlockState(below, patch.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }, y -> { if (y % 4 == 0) Miracles.sound(world, new BlockPos(center.getX(), y, center.getZ()), SoundEvents.BLOCK_WATER_AMBIENT, 2f, 0.5f); },
                () -> DivineBlockMod.LOG.info("Божественный блок: потоп сошёл"));
    }

    private static UUID parse(String id) {
        try { return UUID.fromString(id); } catch (IllegalArgumentException bad) { return null; }
    }

    /** The altar and the home stay dry, with a margin so the water does not run in from the side. */
    private static boolean spared(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos pos, DivineConfig config) {
        if (Protection.spares(world, player, altar, pos, config)) return true;
        for (Direction side : Direction.Type.HORIZONTAL) if (Protection.spares(world, player, altar, pos.offset(side, 8), config)) return true;
        return false;
    }

    /** The lowest ground surface among a few dozen columns of the disc (loaded chunks only). */
    private static int lowestGround(ServerWorld world, BlockPos center, int radius) {
        int lowest = center.getY();
        for (int n = 0; n < 48; n++) {
            double angle = n * Math.PI * 2 / 16, distance = radius * ((n / 16) + 1) / 3.0;
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * distance), z = (int) Math.floor(center.getZ() + Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            lowest = Math.min(lowest, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z));
        }
        return lowest;
    }

    private static String days(Curses.Curse curse) {
        long ticks = curse.until - curse.start;
        return ticks >= 24_000 ? ticks / 24_000 + " дн." : ticks / 1200 + " мин";
    }

    private interface Cell { void visit(BlockPos pos); }

    /** Walks a disc layer by layer from {@code from} to {@code to} (either way), at most {@link #WATER_BUDGET} blocks a tick
     * and a layer no sooner than every {@code pace} ticks. Columns in unloaded chunks are passed over, or loaded when
     * {@code load} (a loaded chunk costs a good part of the tick's budget). Stops when {@code go} says no. */
    private static void sweep(ServerWorld world, BlockPos center, int radius, int from, int to, int pace, boolean load, BooleanSupplier go,
                              Cell cell, IntConsumer layerDone, Runnable finished) {
        new Sweep(world, center, radius, from, to, pace, load, go, cell, layerDone, finished).schedule();
    }

    private static final class Sweep implements Runnable {
        final ServerWorld world; final BlockPos center; final int to, pace, step; final int[] offsets; final boolean load;
        final BooleanSupplier go; final Cell cell; final IntConsumer layerDone; final Runnable finished;
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        int y, cursor, ticks, layerStarted;

        Sweep(ServerWorld world, BlockPos center, int radius, int from, int to, int pace, boolean load, BooleanSupplier go,
              Cell cell, IntConsumer layerDone, Runnable finished) {
            this.world = world; this.center = center; this.to = to; this.pace = pace; this.step = to >= from ? 1 : -1; this.load = load;
            this.go = go; this.cell = cell; this.layerDone = layerDone; this.finished = finished; this.y = from;
            List<Integer> list = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
                if (dx * dx + dz * dz <= radius * radius) list.add((dx + 512) << 10 | (dz + 512));
            offsets = list.stream().mapToInt(Integer::intValue).toArray();
        }

        void schedule() { Scheduler.later(1, this); }

        @Override public void run() {
            ticks++;
            if (!go.getAsBoolean()) return;
            if (cursor == 0 && ticks > 1 && ticks - layerStarted < pace) { schedule(); return; }
            if (cursor == 0) layerStarted = ticks;
            int budget = WATER_BUDGET;
            while (budget > 0 && cursor < offsets.length) {
                int packed = offsets[cursor++];
                int x = center.getX() + (packed >> 10) - 512, z = center.getZ() + (packed & 1023) - 512;
                budget--;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    if (!load) continue;
                    world.getChunk(x >> 4, z >> 4);
                    budget -= 512;
                }
                pos.set(x, y, z);
                cell.visit(pos);
            }
            if (cursor < offsets.length) { schedule(); return; }
            layerDone.accept(y);
            if (y == to) { finished.run(); return; }
            y += step;
            cursor = 0;
            schedule();
        }
    }

    // ---- Babel. ----

    /** The buildings around fall from the top down. */
    static String babel(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        if (config.protectBlocks) return null;
        BlockPos center = player.getBlockPos();
        int radius = Math.min(48, 32 + 4 * Math.max(0, severity - 1));
        int floor = Math.max(world.getBottomY(), center.getY() - 16);
        List<BlockPos> built = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        // Each column from its top down to the first natural ground: walls, roofs and floors on the way are the works of men.
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius) continue;
            int x = center.getX() + dx, z = center.getZ() + dz;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
            for (int y = top, seen = 0; y >= floor && seen < 64; y--, seen++) {
                pos.set(x, y, z);
                BlockState state = world.getBlockState(pos);
                if (state.isAir() || state.isOf(Blocks.WATER)) continue;
                if (built(state)) {
                    if (!state.hasBlockEntity() && !(state.getBlock() instanceof AltarBlock) && state.getHardness(world, pos) >= 0 && state.getHardness(world, pos) < 50
                            && !Protection.spares(world, player, altar, pos, config)) built.add(pos.toImmutable());
                    continue;
                }
                if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS) || state.isReplaceable() || state.hasBlockEntity() || !state.isSolidBlock(world, pos)) continue;
                break;
            }
        }
        // A torch and a fence post are not a tower.
        if (built.size() < 16) return null;
        built.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        int total = built.size();
        int[] index = { 0 };
        Miracles.sound(world, center, SoundEvents.ENTITY_WARDEN_EMERGE, 3f, 0.5f);
        Miracles.sound(world, center, SoundEvents.ENTITY_GENERIC_EXPLODE, 2f, 0.3f);
        Prayers.hint(player, "Земля гудит. Всё построенное рушится.");
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (player.isDisconnected() || index[0] >= built.size()) return;
            // The highest layer left comes down; a big roof takes a few steps.
            int layer = built.get(index[0]).getY(), done = 0;
            while (index[0] < built.size() && built.get(index[0]).getY() == layer && done < BABEL_BUDGET) {
                BlockPos at = built.get(index[0]++);
                if (!world.isChunkLoaded(at.getX() >> 4, at.getZ() >> 4)) continue;
                BlockState state = world.getBlockState(at);
                if (state.isAir() || !built(state) || state.hasBlockEntity()) continue;
                done++;
                if (state.isFullCube(world, at) && world.random.nextInt(3) != 0) {
                    FallingBlockEntity falling = FallingBlockEntity.spawnFromBlock(world, at, state);
                    falling.dropItem = world.random.nextInt(3) == 0;
                } else {
                    world.breakBlock(at, false);
                }
                if (world.random.nextInt(6) == 0)
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, state), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 6, 0.6, 0.4, 0.6, 0.02);
            }
            if (world.random.nextInt(4) == 0) {
                world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, center.getX() + 0.5, layer + 1, center.getZ() + 0.5, 12, radius / 3.0, 1, radius / 3.0, 0.01);
                Miracles.sound(world, new BlockPos(center.getX(), layer, center.getZ()), SoundEvents.BLOCK_STONE_BREAK, 3f, 0.4f);
            }
            if (world.random.nextInt(10) == 0) Miracles.sound(world, center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 3f, 0.4f);
            if (index[0] < built.size()) Scheduler.later(2, step[0]);
        };
        Scheduler.later(20, step[0]);
        DivineBlockMod.LOG.info("Божественный блок: Вавилон — рушится {} блоков построек", total);
        return "Вавилон: постройки вокруг рушатся сверху вниз (" + total + " блоков)";
    }

    /** The works of men: worked wood and stone, glass, wool, clay, lamps, ladders and shelves. Logs, stone and earth are the world's. */
    private static boolean built(BlockState state) {
        if (state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.STAIRS) || state.isIn(BlockTags.SLABS) || state.isIn(BlockTags.WALLS)
                || state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.FENCE_GATES) || state.isIn(BlockTags.DOORS) || state.isIn(BlockTags.TRAPDOORS)
                || state.isIn(BlockTags.WOOL) || state.isIn(BlockTags.WOOL_CARPETS) || state.isIn(BlockTags.TERRACOTTA) || state.isIn(BlockTags.STONE_BRICKS)
                || state.isIn(BlockTags.CANDLES) || state.isIn(BlockTags.BUTTONS) || state.isIn(BlockTags.PRESSURE_PLATES)) return true;
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (path.endsWith("_concrete_powder")) return false;
        return path.contains("glass") || path.contains("bricks") || path.endsWith("_concrete") || path.contains("glazed_terracotta")
                || path.contains("lantern") || path.contains("torch") || path.startsWith("stripped_") || path.startsWith("polished_")
                || path.startsWith("cut_") || path.startsWith("chiseled_") || path.startsWith("smooth_") || path.contains("cobblestone")
                || path.equals("ladder") || path.equals("bookshelf") || path.equals("crafting_table") || path.equals("quartz_block")
                || path.equals("quartz_pillar") || path.equals("hay_block") || path.equals("iron_bars") || path.equals("chain")
                || path.equals("scaffolding") || path.equals("glowstone");
    }

    // ---- The judgement. ----

    /** The sinner is lifted high into the sky, sees the world, and falls. */
    static String judgement(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        BlockPos feet = player.getBlockPos();
        int ground = Math.min(feet.getY(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()));
        int limit = Math.min(ground + 120 + 10 * Math.max(0, Math.min(4, severity - 1)), world.getTopY() - 3);
        // Only as high as the open air above his head goes (a roof, a cave, the Nether's ceiling).
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int peak = limit;
        for (int y = feet.getY() + 2; y <= limit + 2; y++) {
            pos.set(feet.getX(), y, feet.getZ());
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) { peak = Math.min(limit, y - 3); break; }
        }
        double startY = player.getY(), rise = peak - startY;
        if (rise < 12) return null;
        double x = player.getX(), z = player.getZ();
        int lift = 160, hold = 120;
        boolean[] over = { false };
        Miracles.sound(world, feet, SoundEvents.BLOCK_BELL_USE, 3f, 0.6f);
        Miracles.sound(world, feet, SoundEvents.BLOCK_BEACON_ACTIVATE, 2f, 0.5f);
        Scheduler.repeat(1, lift + hold, tick -> {
            if (over[0]) return;
            if (player.isRemoved() || !player.isAlive() || player.isDisconnected() || player.getServerWorld() != world) { over[0] = true; return; }
            double y;
            if (tick < lift) {
                // Up, slow at first and slow at the end, eight seconds.
                double t = (tick + 1) / (double) lift, eased = t * t * (3 - 2 * t);
                y = startY + rise * eased;
                if (tick % 10 == 0) world.spawnParticles(ParticleTypes.END_ROD, x, y, z, 12, 0.5, 0.8, 0.5, 0.02);
                if (tick % 40 == 0) Miracles.sound(world, BlockPos.ofFloored(x, y, z), SoundEvents.BLOCK_BELL_USE, 3f, 0.5f + tick / 400f);
            } else {
                // Held over the world with nothing hiding it, while the writing is read.
                y = startY + rise;
                int held = tick - lift;
                if (held == 0) {
                    Miracles.effect(player, StatusEffects.SLOW_FALLING, hold + 20, 0);
                    player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, hold - 20, 20));
                    player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Взвешен — и найден очень лёгким").formatted(Formatting.GOLD)));
                    player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("Мене, мене, текел, упарсин").formatted(Formatting.DARK_RED, Formatting.BOLD)));
                }
                if (held % 20 == 0) {
                    Miracles.sound(world, BlockPos.ofFloored(x, y, z), SoundEvents.BLOCK_BELL_USE, 3f, 0.5f);
                    Miracles.sound(world, BlockPos.ofFloored(x, y, z), SoundEvents.BLOCK_BELL_RESONATE, 2f, 0.6f);
                }
            }
            player.teleport(world, x, y, z, EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT), 0f, 0f);
            player.setVelocity(Vec3d.ZERO);
            player.velocityModified = true;
            player.fallDistance = 0;
            if (tick == lift + hold - 1) {
                // The scales are read: he is let go, and the fall is his own.
                over[0] = true;
                player.removeStatusEffect(StatusEffects.SLOW_FALLING);
                Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.6f);
                Miracles.sound(world, player.getBlockPos(), SoundEvents.ITEM_ELYTRA_FLYING, 1f, 1f);
            }
        });
        return "суд: вознесён на " + (int) rise + " блоков, взвешен и отпущен падать";
    }

    // ---- The wiping of memory. ----

    /** Bed, spawn point and recipes forgotten, belongings sealed in a chest at the altar, the sinner naked 5000 blocks away. */
    static String memoryWipe(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, int severity, DivineConfig config) {
        MinecraftServer server = world.getServer();
        // Everything he carries, worn and held; the experience stays with him.
        List<ItemStack> stacks = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        for (List<ItemStack> list : List.of(inventory.main, inventory.armor, inventory.offHand))
            for (ItemStack stack : list) if (!stack.isEmpty()) stacks.add(stack.copy());
        int sealed = stacks.isEmpty() ? 0 : seal(world, altar, stacks);
        // No room under the altar: the rest lie at its foot rather than vanish.
        for (ItemStack stack : stacks.subList(sealed, stacks.size())) Miracles.drop(world, Vec3d.ofCenter(altar.up()), stack);
        inventory.clear();
        player.currentScreenHandler.sendContentUpdates();

        // The bed he slept in breaks (the bed only); his spawn and his crafts are forgotten.
        boolean bedBroken = false;
        BlockPos bed = player.getSpawnPointPosition();
        if (bed != null && !config.protectBlocks) {
            ServerWorld bedWorld = server.getWorld(player.getSpawnPointDimension());
            if (bedWorld != null && bedWorld.getBlockState(bed).getBlock() instanceof BedBlock) bedBroken = bedWorld.breakBlock(bed, false);
        }
        player.setSpawnPoint(World.OVERWORLD, null, 0f, false, false);
        int forgotten = player.lockRecipes(server.getRecipeManager().values());

        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.5f);
        Miracles.sound(world, altar, SoundEvents.BLOCK_ENDER_CHEST_CLOSE, 2f, 0.4f);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 120, 0.6, 1.2, 0.6, 0.1);
        Miracles.effect(player, StatusEffects.NAUSEA, 120, 0);
        ServerWorld overworld = server.getOverworld();
        Scheduler.later(40, () -> throwFar(overworld, player, 0));
        DivineBlockMod.LOG.info("Божественный блок: память {} стёрта, {} стопок запечатано у алтаря", player.getName().getString(), sealed);
        return "память стёрта: " + sealed + " стопок запечатано у алтаря" + (bedBroken ? ", кровать разрушена" : "")
                + ", забыто рецептов: " + forgotten + ", заброшен за 5000 блоков";
    }

    /** Lays the stacks into double chests named «Память грешника» from three blocks under the altar down, each walled in
     * obsidian. Returns how many stacks went in. */
    private static int seal(ServerWorld world, BlockPos altar, List<ItemStack> stacks) {
        int index = 0;
        Text name = Text.literal("Память грешника").formatted(Formatting.DARK_PURPLE);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = altar.getY() - 3, vault = 0; vault < 8 && index < stacks.size() && y > world.getBottomY() + 1; vault++, y -= 3) {
            BlockPos left = new BlockPos(altar.getX(), y, altar.getZ()), right = left.east();
            // A vault from an earlier wipe stays shut: the next one goes deeper.
            if (world.getBlockState(left).hasBlockEntity() || world.getBlockState(right).hasBlockEntity()
                    || world.getBlockState(left).getHardness(world, left) < 0 || world.getBlockState(right).getHardness(world, right) < 0) continue;
            for (int dx = -1; dx <= 2; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dy == 0 && dz == 0 && (dx == 0 || dx == 1)) continue;
                pos.set(left.getX() + dx, y + dy, left.getZ() + dz);
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() instanceof AltarBlock || state.getHardness(world, pos) < 0 || state.hasBlockEntity()) continue;
                world.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
            }
            BlockState chest = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
            world.setBlockState(left, chest.with(ChestBlock.CHEST_TYPE, ChestType.LEFT), Block.NOTIFY_LISTENERS);
            world.setBlockState(right, chest.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT), Block.NOTIFY_LISTENERS);
            for (BlockPos half : List.of(left, right)) {
                if (!(world.getBlockEntity(half) instanceof ChestBlockEntity box)) continue;
                box.readComponents(ComponentMap.builder().add(DataComponentTypes.CUSTOM_NAME, name).build(), ComponentChanges.EMPTY);
                for (int slot = 0; slot < box.size() && index < stacks.size(); slot++) box.setStack(slot, stacks.get(index++));
                box.markDirty();
            }
        }
        return index;
    }

    /** Casts him onto dry land about 5000 blocks away (from the overworld's spawn when he is in another world): one try a
     * tick, each loading its chunk; after eight, a small island is made where the last one fell. */
    private static void throwFar(ServerWorld overworld, ServerPlayerEntity player, int attempt) {
        if (player.isRemoved() || player.isDisconnected() || !player.isAlive()) return;
        boolean here = player.getServerWorld() == overworld;
        double fromX = here ? player.getX() : overworld.getSpawnPos().getX(), fromZ = here ? player.getZ() : overworld.getSpawnPos().getZ();
        double angle = overworld.random.nextDouble() * Math.PI * 2, distance = 4800 + overworld.random.nextInt(400);
        int x = (int) Math.floor(fromX + Math.cos(angle) * distance), z = (int) Math.floor(fromZ + Math.sin(angle) * distance);
        // The heightmap of a chunk nobody has loaded reads as the bottom of the world: load it first.
        overworld.getChunk(x >> 4, z >> 4);
        BlockPos top = overworld.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)), under = top.down();
        boolean dry = overworld.getFluidState(under).isEmpty() && overworld.getFluidState(top).isEmpty()
                && overworld.getBlockState(under).isSolidBlock(overworld, under) && top.getY() > overworld.getBottomY() + 1;
        if (!dry && attempt < 7) { Scheduler.later(1, () -> throwFar(overworld, player, attempt + 1)); return; }
        if (!dry) {
            // All water or lava out there: a small island of earth on the surface, with air over it.
            int surface = Math.max(top.getY(), overworld.getSeaLevel());
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                pos.set(x + dx, surface - 1, z + dz);
                overworld.setBlockState(pos, (dx == 0 && dz == 0 ? Blocks.GRASS_BLOCK : Blocks.DIRT).getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy < 3; dy++) {
                    pos.set(x + dx, surface + dy, z + dz);
                    if (!overworld.getBlockState(pos).isAir()) overworld.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
            top = new BlockPos(x, surface, z);
        }
        player.teleport(overworld, top.getX() + 0.5, top.getY() + 0.1, top.getZ() + 0.5, player.getYaw(), player.getPitch());
        player.fallDistance = 0;
        Miracles.effect(player, StatusEffects.SLOW_FALLING, 160, 0);
        Miracles.effect(player, StatusEffects.NAUSEA, 140, 0);
        Miracles.sound(overworld, top, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 30));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Всё, что было твоим, запечатано у алтаря").formatted(Formatting.GRAY)));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("Ты забыл, кто ты").formatted(Formatting.DARK_PURPLE, Formatting.BOLD)));
    }
}
