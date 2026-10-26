package io.github.mobtalk.game;

import io.github.mobtalk.core.MobClock;
import io.github.mobtalk.core.Graft;
import io.github.mobtalk.core.Initiative;
import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.Officialdom;
import io.github.mobtalk.core.VillageRumors;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.FrogEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;
import net.minecraft.world.Heightmap;
import org.joml.Vector3f;
import java.util.*;

/** The head's graft in the world (track «чиновники», stages 3–4, docs/officials.md): the dacha {@link Graft} grows outside the
 * village, heaven's punishments for a corrupt head, the «Охрана!» of an official who is hit, the black ledger in the dacha's safe
 * and the revizor it brings. Server thread only. */
final class GraftController {
    static final long PERIOD = 1200, PHASE = 431, FX_PERIOD = 40, FX_PHASE = 19, VISIT_PERIOD = 20, VISIT_PHASE = 3;
    /** The plot: 13 wide, 16 deep; it stands this far outside the village's edge. */
    static final int WIDTH = 13, DEPTH = 16, GAP = 14;
    /** Toads live this long; an official shouts «Охрана!» at most this often. */
    static final long TOAD_TICKS = 20 * 120, SHOUT_TICKS = 200;
    static final String TOAD = "mobtalk_toad", REVIZOR = "mobtalk_revizor";

    private final MobGameBridge bridge;
    private final Random random = new Random();
    private final Map<UUID, Long> toads = new HashMap<>(), shouted = new HashMap<>();
    /** The revizor's inspection in progress, by village key. */
    private final Map<String, Inspection> inspections = new HashMap<>();

    private static final class Inspection {
        final String zone; final UUID revizor; final long until; long leave = -1; boolean judged;
        Inspection(String zone, UUID revizor, long until) { this.zone = zone; this.revizor = revizor; this.until = until; }
    }

    GraftController(MobGameBridge bridge) { this.bridge = bridge; }

    boolean enabled() { return bridge.officials.enabled(); }
    static long day(ServerWorld world) { return Math.floorDiv(world.getServer().getOverworld().getTimeOfDay(), 24_000L); }

    void tick(ServerWorld world, List<VillageDefenseController.Zone> zones) {
        if (!enabled()) return;
        long now = world.getTime();
        if (now % PERIOD == PHASE) for (VillageDefenseController.Zone zone : zones) round(world, zone);
        if (now % FX_PERIOD == FX_PHASE) effects(world);
        if (now % VISIT_PERIOD == VISIT_PHASE) { revizors(world, zones); toads(world, now); }
    }

    // ---- The dacha ----

    /** A village's corrupt head: a day in office, the dacha grows one piece, heaven may strike. */
    void round(ServerWorld world, VillageDefenseController.Zone zone) {
        if (!bridge.config.officialDacha && !bridge.config.officialSmite) return;
        List<VillagerEntity> heads = bridge.officials.officials(world, zone).getOrDefault(Officialdom.Rank.HEAD, List.of());
        if (heads.isEmpty()) return;
        VillagerEntity head = heads.getFirst();
        if (Officialdom.honest(head.getUuid(), bridge.config.officialHonestPercent)) return;
        long day = day(world);
        bridge.memory.estate(head.getUuid(), bridge.officials.name(head), zone.key());
        Graft.Estate estate = bridge.memory.estateUpdate(head.getUuid(), e -> Graft.days(e, day));
        if (estate.confiscated()) return;
        if (bridge.config.officialDacha) grow(world, zone, head, estate);
        if (bridge.config.officialSmite) smite(world, zone, head, bridge.memory.estate(head.getUuid(), null, null), day);
    }

    /** Builds the next piece the points have earned (one a round), finds the plot first. */
    void grow(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity head, Graft.Estate estate) {
        Graft.Stage due = Graft.stage(estate.points(), bridge.config.dachaPoints);
        if (due.ordinal() <= estate.built()) { ledger(world, head, estate); return; }
        if (!estate.placed()) {
            BlockPos[] spot = plot(world, zone, head.getUuid());
            if (spot == null) return;
            int facing = Direction.fromVector(spot[1].getX(), 0, spot[1].getZ()).getHorizontal();
            estate = bridge.memory.estateUpdate(head.getUuid(), e -> e.place(spot[0].getX(), spot[0].getY(), spot[0].getZ(), facing));
        }
        Graft.Stage next = Graft.Stage.values()[estate.built() + 1];
        Plot plot = new Plot(world, estate);
        if (!plot.loaded()) return;
        build(plot, next);
        estate = bridge.memory.estateUpdate(head.getUuid(), e -> e.built(next.ordinal()));
        world.playSound(null, plot.at(6, 0, 7), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.6f, 1.2f);
        String headName = bridge.officials.name(head);
        rumor(world, zone, Graft.rumor(next, headName), 4);
        announce(world, zone, Text.translatable("mobtalk.graft.built." + next.name().toLowerCase(Locale.ROOT), headName));
        ledger(world, head, estate);
    }

    /** A spot outside the village: 8 directions tried from the head's own; returns {plot origin, front direction}. */
    private BlockPos[] plot(ServerWorld world, VillageDefenseController.Zone zone, UUID head) {
        double r = Math.max(zone.box().getLengthX(), zone.box().getLengthZ()) / 2;
        int start = Math.floorMod(head.hashCode(), 8);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * ((start + i) % 8);
            int cx = zone.center().getX() + (int) Math.round(Math.cos(a) * (r + GAP)), cz = zone.center().getZ() + (int) Math.round(Math.sin(a) * (r + GAP));
            Direction front = Direction.getFacing(zone.center().getX() - cx, 0, zone.center().getZ() - cz);
            if (front.getAxis().isVertical()) front = Direction.NORTH;
            BlockRotation rotation = rotation(front);
            BlockPos origin = new BlockPos(cx, 0, cz).subtract(new BlockPos(WIDTH / 2, 0, DEPTH / 2).rotate(rotation));
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE; boolean ok = true;
            for (int[] c : new int[][] {{0, 0}, {WIDTH - 1, 0}, {0, DEPTH - 1}, {WIDTH - 1, DEPTH - 1}, {WIDTH / 2, DEPTH / 2}}) {
                BlockPos p = origin.add(new BlockPos(c[0], 0, c[1]).rotate(rotation));
                if (!world.isChunkLoaded(p)) { ok = false; break; }
                int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                if (!world.getFluidState(new BlockPos(p.getX(), top - 1, p.getZ())).isEmpty() || zone.holds(new BlockPos(p.getX(), top, p.getZ()))) { ok = false; break; }
                min = Math.min(min, top); max = Math.max(max, top);
            }
            if (!ok || max - min > 6) continue;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz);
            return new BlockPos[] {new BlockPos(origin.getX(), y, origin.getZ()), new BlockPos(front.getOffsetX(), 0, front.getOffsetZ())};
        }
        return null;
    }
    static BlockRotation rotation(Direction front) {
        return switch (front) { case EAST -> BlockRotation.CLOCKWISE_90; case SOUTH -> BlockRotation.CLOCKWISE_180; case WEST -> BlockRotation.COUNTERCLOCKWISE_90; default -> BlockRotation.NONE; };
    }

    /** The plot in the world: local x (0..12, left to right seen from the gate), y (0 = ground level, -1 the ground), z (0 = the
     * fence with the gate, toward the village; 15 = the back fence); blueprints are drawn with the front to the north. */
    static final class Plot {
        final ServerWorld world; final BlockPos origin; final BlockRotation rotation;
        final List<BlockPos> placed = new ArrayList<>();
        Plot(ServerWorld world, Graft.Estate e) {
            this.world = world; this.origin = new BlockPos(e.x(), e.y(), e.z());
            this.rotation = rotation(Direction.fromHorizontal(e.facing()));
        }
        BlockPos at(int x, int y, int z) { return origin.add(new BlockPos(x, y, z).rotate(rotation)); }
        void set(int x, int y, int z, BlockState state) {
            BlockPos p = at(x, y, z);
            world.setBlockState(p, state.rotate(rotation), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            placed.add(p);
        }
        BlockState get(int x, int y, int z) { return world.getBlockState(at(x, y, z)); }
        boolean loaded() {
            for (int[] c : new int[][] {{0, 0}, {WIDTH - 1, 0}, {0, DEPTH - 1}, {WIDTH - 1, DEPTH - 1}}) if (!world.isChunkLoaded(at(c[0], 0, c[1]))) return false;
            return true;
        }
        /** Fences, panes and stairs join their neighbours, as a structure does when it is placed. */
        void settle() {
            for (BlockPos p : placed) {
                BlockState s = world.getBlockState(p), fixed = Block.postProcessState(s, world, p);
                if (fixed != s) world.setBlockState(p, fixed, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
            for (BlockPos p : placed) world.updateNeighbors(p, world.getBlockState(p).getBlock());
            placed.clear();
        }
    }

    private static BlockState stairs(Block block, Direction facing, boolean top) {
        return block.getDefaultState().with(StairsBlock.FACING, facing).with(StairsBlock.HALF, top ? BlockHalf.TOP : BlockHalf.BOTTOM);
    }
    private static BlockState logY(Block block) { return block.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y); }

    void build(Plot plot, Graft.Stage stage) {
        switch (stage) {
            case FENCE -> fence(plot);
            case COTTAGE -> cottage(plot);
            case BANYA -> banya(plot);
            case FOUNTAIN -> fountain(plot);
            case TOILET -> toilet(plot, false);
            case NONE -> { }
        }
        plot.settle();
    }

    /** The plot levelled, a spruce fence with lanterns on the corners, a gate, a path, and the sign. */
    private void fence(Plot p) {
        for (int x = -1; x <= WIDTH; x++) for (int z = -2; z <= DEPTH; z++) {
            for (int y = 0; y <= 12; y++) if (!p.get(x, y, z).isAir()) p.set(x, y, z, Blocks.AIR.getDefaultState());
            p.set(x, -1, z, Blocks.GRASS_BLOCK.getDefaultState());
            for (int y = -2; y >= -5 && (p.get(x, y, z).isAir() || !p.world.getFluidState(p.at(x, y, z)).isEmpty() || p.get(x, y, z).isReplaceable()); y--) p.set(x, y, z, Blocks.DIRT.getDefaultState());
        }
        BlockState fence = Blocks.SPRUCE_FENCE.getDefaultState();
        for (int x = 0; x < WIDTH; x++) { if (x != 6) p.set(x, 0, 0, fence); p.set(x, 0, DEPTH - 1, fence); }
        for (int z = 0; z < DEPTH; z++) { p.set(0, 0, z, fence); p.set(WIDTH - 1, 0, z, fence); }
        p.set(6, 0, 0, Blocks.SPRUCE_FENCE_GATE.getDefaultState().with(FenceGateBlock.FACING, Direction.NORTH));
        for (int[] c : new int[][] {{0, 0}, {WIDTH - 1, 0}, {0, DEPTH - 1}, {WIDTH - 1, DEPTH - 1}}) p.set(c[0], 1, c[1], Blocks.LANTERN.getDefaultState());
        for (int z = 1; z <= 5; z++) p.set(6, -1, z, Blocks.DIRT_PATH.getDefaultState());
        p.set(6, -1, -1, Blocks.DIRT_PATH.getDefaultState());
        p.set(4, 0, -1, Blocks.SPRUCE_SIGN.getDefaultState().with(SignBlock.ROTATION, 8));
        if (p.world.getBlockEntity(p.at(4, 0, -1)) instanceof SignBlockEntity sign) {
            sign.changeText(t -> t.withMessage(0, Text.literal("Частная")).withMessage(1, Text.literal("собственность")).withMessage(2, Text.literal("Посторонним"))
                    .withMessage(3, Text.literal("вход воспрещён")), true);
            sign.markDirty();
        }
    }

    /** A brick cottage with dark oak corners and a gable roof, windows, a door, a lantern inside, the safe, flowers in front. */
    private void cottage(Plot p) {
        int x0 = 3, x1 = 9, z0 = 6, z1 = 11;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
            p.set(x, -1, z, edge ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState());
            p.set(x, 3, z, Blocks.SPRUCE_PLANKS.getDefaultState());
            for (int y = 0; y <= 2; y++) {
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                p.set(x, y, z, corner ? logY(Blocks.DARK_OAK_LOG) : edge ? Blocks.BRICKS.getDefaultState() : Blocks.AIR.getDefaultState());
            }
        }
        for (int[] w : new int[][] {{4, z0}, {8, z0}, {x0, 8}, {x0, 9}, {x1, 8}, {x1, 9}, {5, z1}, {7, z1}}) p.set(w[0], 1, w[1], Blocks.GLASS.getDefaultState());
        p.set(6, 0, z0, Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        p.set(6, 1, z0, Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // The gable roof along x, overhanging a block all round; the gables in brick, the ridge in planks.
        for (int k = 0; k <= 3; k++) for (int z = z0 - 1; z <= z1 + 1; z++) {
            p.set(x0 - 1 + k, 3 + k, z, stairs(Blocks.DARK_OAK_STAIRS, Direction.EAST, false));
            p.set(x1 + 1 - k, 3 + k, z, stairs(Blocks.DARK_OAK_STAIRS, Direction.WEST, false));
        }
        for (int z = z0 - 1; z <= z1 + 1; z++) { p.set(6, 6, z, Blocks.DARK_OAK_PLANKS.getDefaultState()); p.set(6, 7, z, Blocks.DARK_OAK_SLAB.getDefaultState().with(Properties.SLAB_TYPE, SlabType.BOTTOM)); }
        for (int z : new int[] {z0, z1}) { for (int x = 4; x <= 8; x++) p.set(x, 4, z, Blocks.BRICKS.getDefaultState()); for (int x = 5; x <= 7; x++) p.set(x, 5, z, Blocks.BRICKS.getDefaultState()); }
        p.set(6, 2, 8, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        p.set(8, 0, 10, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST));
        p.set(4, 0, 10, Blocks.SPRUCE_FENCE.getDefaultState()); p.set(4, 1, 10, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.getDefaultState()); // a table
        p.set(4, 0, 7, Blocks.FLOWER_POT.getDefaultState());
        for (int x : new int[] {5, 7}) { p.set(x, 0, 4, Blocks.SPRUCE_FENCE.getDefaultState()); p.set(x, 1, 4, Blocks.LANTERN.getDefaultState()); }
        BlockState[] flowers = {Blocks.RED_TULIP.getDefaultState(), Blocks.ALLIUM.getDefaultState(), Blocks.OXEYE_DAISY.getDefaultState(), Blocks.POPPY.getDefaultState()};
        int i = 0;
        for (int x : new int[] {3, 4, 8, 9}) p.set(x, 0, 5, flowers[i++ % flowers.length]);
    }

    /** The banya in the back right corner: spruce logs, a slab roof, a chimney with a campfire smoking. */
    private void banya(Plot p) {
        for (int x = 9; x <= 11; x++) for (int z = 12; z <= 14; z++) {
            boolean edge = x == 9 || x == 11 || z == 12 || z == 14;
            p.set(x, -1, z, Blocks.SPRUCE_PLANKS.getDefaultState());
            for (int y = 0; y <= 1; y++) p.set(x, y, z, edge ? logY(Blocks.SPRUCE_LOG) : Blocks.AIR.getDefaultState());
            p.set(x, 2, z, Blocks.SPRUCE_PLANKS.getDefaultState());
            p.set(x, 3, z, Blocks.SPRUCE_SLAB.getDefaultState());
        }
        p.set(10, 0, 12, Blocks.SPRUCE_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        p.set(10, 1, 12, Blocks.SPRUCE_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH).with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        p.set(10, 0, 13, Blocks.SPRUCE_SLAB.getDefaultState());
        p.set(11, 3, 14, Blocks.COBBLESTONE.getDefaultState());
        p.set(11, 4, 14, Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, true).with(CampfireBlock.SIGNAL_FIRE, false));
        p.set(8, 0, 13, Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X));
        p.set(8, 0, 14, Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X));
        p.set(8, 1, 14, Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X));
    }

    /** The fountain in the front left: a quartz basin lit from below, a pillar with a gold top. */
    private void fountain(Plot p) {
        for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
            boolean rim = x == 1 || x == 5 || z == 1 || z == 5;
            p.set(x, -1, z, rim ? Blocks.SMOOTH_QUARTZ.getDefaultState() : Blocks.SEA_LANTERN.getDefaultState());
            p.set(x, 0, z, rim ? Blocks.QUARTZ_BLOCK.getDefaultState() : x == 3 && z == 3 ? logY(Blocks.QUARTZ_PILLAR) : Blocks.WATER.getDefaultState());
        }
        p.set(3, 1, 3, logY(Blocks.QUARTZ_PILLAR));
        p.set(3, 2, 3, Blocks.GOLD_BLOCK.getDefaultState());
    }

    /** The golden toilet under glass in the front right — or, after heaven's word, a dung one. */
    private void toilet(Plot p, boolean dung) {
        for (int x = 9; x <= 11; x++) for (int z = 2; z <= 4; z++) {
            boolean inner = x == 10 && z == 3;
            p.set(x, -1, z, inner ? (dung ? Blocks.COARSE_DIRT : Blocks.GOLD_BLOCK).getDefaultState() : Blocks.SMOOTH_QUARTZ.getDefaultState());
            if (!inner) for (int y = 0; y <= 1; y++) p.set(x, y, z, Blocks.GLASS.getDefaultState());
            p.set(x, 2, z, Blocks.GLASS.getDefaultState());
        }
        p.set(10, 0, 3, (dung ? Blocks.MUD : Blocks.GOLD_BLOCK).getDefaultState());
        p.set(10, 1, 3, (dung ? Blocks.BROWN_CARPET : Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE).getDefaultState());
        p.set(10, 0, 4, (dung ? Blocks.PACKED_MUD : Blocks.GOLD_BLOCK).getDefaultState());
        p.set(10, 1, 4, (dung ? Blocks.MUD_BRICKS : Blocks.GOLD_BLOCK).getDefaultState());
    }

    /** The black ledger goes into the cottage's safe once the head has taken enough envelopes. */
    private void ledger(ServerWorld world, VillagerEntity head, Graft.Estate estate) {
        if (!bridge.config.officialRevizor || estate.ledger() || estate.built() < Graft.Stage.COTTAGE.ordinal()) return;
        int taken = bridge.memory.officialTaken(head.getUuid());
        if (taken < Graft.LEDGER_ENVELOPES) return;
        Plot plot = new Plot(world, estate);
        if (!plot.loaded() || !(world.getBlockEntity(plot.at(8, 0, 10)) instanceof ChestBlockEntity chest)) return;
        chest.setStack(13, OfficialContent.ledger(head.getUuid(), bridge.officials.name(head), estate.village(), taken));
        chest.markDirty();
        bridge.memory.estateUpdate(head.getUuid(), e -> e.ledger(true));
    }

    // ---- Envelopes, heaven ----

    /** A head took an envelope: the dacha's points; the giver may be «noticed from above». */
    void took(ServerWorld world, ServerPlayerEntity player, VillagerEntity official, Officialdom.Rank rank) {
        if (rank == Officialdom.Rank.HEAD) bridge.memory.estateUpdate(official.getUuid(), Graft::envelope);
        if (bridge.config.officialSmite && random.nextDouble() < Graft.GIVER_MARK) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.WHITE_CONCRETE_POWDER.getDefaultState()),
                    player.getX(), player.getY() + 2.4, player.getZ(), 6, 0.15, 0.1, 0.15, 0);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PARROT_AMBIENT, SoundCategory.NEUTRAL, 1f, 0.6f);
            player.sendMessage(Text.translatable("mobtalk.graft.pigeon").formatted(Formatting.GRAY, Formatting.ITALIC), false);
        }
    }

    void smite(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity head, Graft.Estate estate, long day) {
        int taken = bridge.memory.officialTaken(head.getUuid());
        Graft.Smite smite = Graft.smite(estate, taken, day, bridge.config.smitePercent, random.nextDouble(), random.nextDouble());
        if (smite == Graft.Smite.NONE) return;
        strike(world, zone, head, estate, smite, day);
    }
    /** Heaven's word on the head (also from the self-test). */
    void strike(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity head, Graft.Estate estate, Graft.Smite smite, long day) {
        String name = bridge.officials.name(head);
        Plot plot = estate.placed() ? new Plot(world, estate) : null;
        switch (smite) {
            case LIGHTNING -> {
                if (plot == null || !plot.loaded()) { strike(world, zone, head, estate, Graft.Smite.TOADS, day); return; }
                LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
                if (bolt == null) return;
                bolt.refreshPositionAfterTeleport(Vec3d.ofBottomCenter(plot.at(6, 7, 8)));
                bolt.setCosmetic(true);
                world.spawnEntity(bolt);
            }
            case TOADS -> {
                BlockPos desk = head.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(head.getBlockPos());
                for (int i = 0; i < 7; i++) {
                    FrogEntity frog = EntityType.FROG.create(world);
                    if (frog == null) continue;
                    frog.refreshPositionAndAngles(desk.getX() + 0.5 + random.nextGaussian() * 1.5, desk.getY() + 1, desk.getZ() + 0.5 + random.nextGaussian() * 1.5, random.nextFloat() * 360, 0);
                    frog.initialize(world, world.getLocalDifficulty(desk), SpawnReason.MOB_SUMMONED, null);
                    frog.addCommandTag(TOAD);
                    world.spawnEntity(frog);
                    toads.put(frog.getUuid(), world.getTime() + TOAD_TICKS);
                }
                world.playSound(null, desk, SoundEvents.ENTITY_FROG_AMBIENT, SoundCategory.NEUTRAL, 1.5f, 0.8f);
            }
            case DUNG -> {
                if (plot == null || !plot.loaded()) return;
                toilet(plot, true);
                plot.settle();
                world.playSound(null, plot.at(10, 0, 3), SoundEvents.BLOCK_MUD_PLACE, SoundCategory.BLOCKS, 1.5f, 0.7f);
                bridge.memory.estateUpdate(head.getUuid(), e -> e.dung(true));
            }
            case NONE -> { return; }
        }
        bridge.memory.estateUpdate(head.getUuid(), e -> e.smitten(day));
        String key = "mobtalk.graft.smite." + smite.name().toLowerCase(Locale.ROOT);
        announce(world, zone, Text.translatable(key, name).formatted(Formatting.GOLD));
        rumor(world, zone, Text.translatable(key + ".rumor", name).getString(), 5);
        bridge.memory.recordEvent(head.getUuid(), MobGameBridge.type(head), head.getName().getString(), Text.translatable(key + ".memory").getString(), MobClock.now());
    }

    private void toads(ServerWorld world, long now) {
        if (toads.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Long>> it = toads.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (now < e.getValue()) continue;
            if (world.getEntity(e.getKey()) instanceof FrogEntity frog) {
                world.spawnParticles(ParticleTypes.POOF, frog.getX(), frog.getY() + 0.3, frog.getZ(), 6, 0.2, 0.2, 0.2, 0.02);
                frog.discard();
                it.remove();
            } else if (world == world.getServer().getOverworld()) it.remove();
        }
    }

    /** Sparkles round the golden toilet, flies round the dung one, spray at the fountain — only with a player near. */
    private void effects(ServerWorld world) {
        if (!bridge.config.officialDacha) return;
        for (Graft.Estate e : bridge.memory.estates()) {
            if (!e.placed() || e.built() < Graft.Stage.FOUNTAIN.ordinal()) continue;
            Plot plot = new Plot(world, e);
            BlockPos center = plot.at(6, 0, 7);
            if (!world.isChunkLoaded(center) || world.getClosestPlayer(center.getX(), center.getY(), center.getZ(), 48, false) == null) continue;
            Vec3d spout = Vec3d.ofCenter(plot.at(3, 2, 3)).add(0, 0.6, 0);
            world.spawnParticles(ParticleTypes.SPLASH, spout.x, spout.y, spout.z, 8, 0.3, 0.1, 0.3, 0.05);
            if (e.built() < Graft.Stage.TOILET.ordinal()) continue;
            Vec3d toilet = Vec3d.ofCenter(plot.at(10, 1, 3));
            if (e.dung()) world.spawnParticles(new DustParticleEffect(new Vector3f(0.36f, 0.24f, 0.12f), 1.2f), toilet.x, toilet.y, toilet.z, 6, 0.3, 0.3, 0.3, 0);
            else { world.spawnParticles(ParticleTypes.WAX_ON, toilet.x, toilet.y, toilet.z, 4, 0.4, 0.4, 0.4, 0.4); world.spawnParticles(ParticleTypes.END_ROD, toilet.x, toilet.y + 0.6, toilet.z, 1, 0.3, 0.2, 0.3, 0.01); }
        }
    }

    // ---- «Охрана!» ----

    /** An official was hit by a player: «Охрана!», and an inspector writes an order for the assault on an official. */
    void assaulted(VillagerEntity official, ServerPlayerEntity player) {
        if (!enabled() || !(official.getWorld() instanceof ServerWorld world)) return;
        Officialdom.Rank rank = OfficialContent.rank(official);
        if (rank == null) return;
        long now = world.getTime();
        Long last = shouted.get(official.getUuid());
        if (last != null && now - last < SHOUT_TICKS) return;
        shouted.put(official.getUuid(), now);
        Text line = Text.empty().append(bridge.officials.titled(official, rank).copy().formatted(Formatting.WHITE)).append(": ")
                .append(Text.translatable("mobtalk.graft.guard").formatted(Formatting.RED, Formatting.BOLD));
        for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(official) <= 32 * 32) p.sendMessage(line, false);
        world.playSound(null, official.getBlockPos(), SoundEvents.ENTITY_VILLAGER_HURT, SoundCategory.NEUTRAL, 1.5f, 0.7f);
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, official.getBlockPos());
        if (zone == null) return;
        long day = day(world);
        VillagerEntity inspector = bridge.officials.officials(world, zone).getOrDefault(Officialdom.Rank.INSPECTOR, List.of()).stream().findFirst().orElse(null);
        if (inspector == null || !bridge.memory.officialMayFine(zone.key(), player.getUuid(), day)) return;
        Officialdom.Fine fine = bridge.memory.officialIssue(zone.key(), player.getUuid(), MobGameBridge.playerName(player), inspector.getUuid(), bridge.officials.name(inspector),
                "нападение на должностное лицо при исполнении", bridge.config.inspectorFine * 2, day, bridge.config.inspectorDays);
        player.sendMessage(Text.translatable("mobtalk.official.order.unnamed", fine.number(), fine.reason(), fine.amount(), fine.dueDay()).formatted(Formatting.GOLD), false);
    }

    // ---- The black ledger and the revizor ----

    /** From «Подарить»: the black ledger handed to someone. Null when it is no ledger (the ordinary gift goes on). */
    String ledgerGift(ServerWorld world, ServerPlayerEntity player, VillagerEntity villager, ItemStack stack) {
        if (!enabled() || OfficialContent.LEDGER == null || !stack.isOf(OfficialContent.LEDGER)) return null;
        OfficialContent.LedgerData data = OfficialContent.ledgerData(stack);
        if (data == null) return null;
        Officialdom.Rank rank = OfficialContent.rank(villager);
        boolean honest = rank != null && Officialdom.honest(villager.getUuid(), bridge.config.officialHonestPercent);
        boolean isHead = villager.getUuid().equals(data.head());
        long day = day(world);
        boolean coming = bridge.memory.revizorDay(data.village()) >= 0 || inspections.containsKey(data.village());
        Graft.Ledger what = Graft.ledger(rank != null, honest, isHead, coming);
        if (what == Graft.Ledger.REVIZOR && !bridge.config.officialRevizor) what = Graft.Ledger.RUMOR;
        String name = bridge.officials.name(villager), playerName = MobGameBridge.playerName(player);
        long now = MobClock.now();
        switch (what) {
            case ALREADY -> { return Text.translatable("mobtalk.graft.ledger.already", name).getString(); }
            case REVIZOR -> {
                stack.decrement(1);
                bridge.memory.revizorSchedule(data.village(), day + 1);
                bridge.memory.recordEvent(villager.getUuid(), MobGameBridge.type(villager), villager.getName().getString(),
                        "Игрок " + playerName + " принёс тебе чёрную бухгалтерию главы администрации " + data.headName() + ". Ты отправил её в столицу: завтра приедет ревизор.", now);
                bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), name, Initiative.Trigger.GIFT,
                        "игрок принёс тебе чёрную бухгалтерию главы администрации " + data.headName() + " (" + data.count() + " конвертов). Ты честный: благодаришь казённо и обещаешь «передать куда следует» — завтра приедет ревизор из столицы.", List.of(), now));
                return Text.translatable("mobtalk.graft.ledger.revizor", name).getString();
            }
            case BLACKMAIL -> {
                stack.decrement(1);
                int pay = Graft.hushMoney(bridge.memory.officialTaken(villager.getUuid()));
                player.getInventory().offerOrDrop(new ItemStack(net.minecraft.item.Items.EMERALD, pay));
                bridge.memory.recordBribe(villager.getUuid(), MobGameBridge.type(villager), villager.getName().getString(), player.getUuid(), playerName,
                        -30, "шантажировал тебя твоей же чёрной бухгалтерией; ты выкупил её за " + pay + " изумр. и ненавидишь его", now);
                bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), name, Initiative.Trigger.GIFT,
                        "игрок положил перед тобой твою же чёрную бухгалтерию. Ты бледнеешь, выкупаешь её за " + pay + " изумрудов и сквозь зубы просишь «забыть этот разговор».", List.of(), now));
                return Text.translatable("mobtalk.graft.ledger.blackmail", name, pay).getString();
            }
            case RUMOR -> {
                stack.decrement(1);
                VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, villager.getBlockPos());
                if (zone != null) rumor(world, zone, "у главы администрации " + data.headName() + " нашли чёрную бухгалтерию: " + data.count() + " конвертов", 6);
                bridge.memory.recordWitnessed(villager.getUuid(), MobGameBridge.type(villager), villager.getName().getString(), player.getUuid(), playerName,
                        "показал тебе чёрную бухгалтерию главы администрации " + data.headName(), 3, MobState.Event.HELP, now);
                return Text.translatable("mobtalk.graft.ledger.rumor", name).getString();
            }
        }
        return null;
    }

    /** The revizor comes on his day to a village a player is near; the inspection; the verdict. */
    private void revizors(ServerWorld world, List<VillageDefenseController.Zone> zones) {
        long day = day(world), now = world.getTime();
        for (Map.Entry<String, Long> due : bridge.memory.revizors().entrySet()) {
            if (day < due.getValue() || inspections.containsKey(due.getKey())) continue;
            VillageDefenseController.Zone zone = zones.stream().filter(z -> z.key().equals(due.getKey())).findFirst().orElse(null);
            if (zone == null || world.getClosestPlayer(zone.center().getX(), zone.center().getY(), zone.center().getZ(), 96, false) == null) continue;
            arrive(world, zone, now);
        }
        for (Iterator<Map.Entry<String, Inspection>> it = inspections.entrySet().iterator(); it.hasNext(); ) {
            Inspection inspection = it.next().getValue();
            VillageDefenseController.Zone zone = zones.stream().filter(z -> z.key().equals(inspection.zone)).findFirst().orElse(null);
            VillagerEntity revizor = world.getEntity(inspection.revizor) instanceof VillagerEntity v ? v : null;
            if (zone == null) continue;
            if (!inspection.judged) {
                panic(world, zone, revizor);
                if (now >= inspection.until) { judge(world, zone, revizor); inspection.judged = true; inspection.leave = now + 20 * 15; }
            } else if (now >= inspection.leave) {
                if (revizor != null) { world.spawnParticles(ParticleTypes.CLOUD, revizor.getX(), revizor.getY() + 1, revizor.getZ(), 12, 0.3, 0.5, 0.3, 0.02); revizor.discard(); }
                it.remove();
            }
        }
    }
    void arrive(ServerWorld world, VillageDefenseController.Zone zone, long now) {
        BlockPos at = zone.bell() != null ? zone.bell().add(3, 0, 3) : zone.center();
        at = new BlockPos(at.getX(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
        VillagerEntity revizor = EntityType.VILLAGER.create(world);
        if (revizor == null) return;
        revizor.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        revizor.initialize(world, world.getLocalDifficulty(at), SpawnReason.MOB_SUMMONED, null);
        revizor.setVillagerData(new VillagerData(VillagerType.forBiome(world.getBiome(at)), OfficialContent.INSPECTOR, 5));
        revizor.addCommandTag("mobtalk_road"); revizor.addCommandTag(REVIZOR);
        revizor.setCustomName(Text.translatable("mobtalk.graft.revizor.name"));
        revizor.setCustomNameVisible(true);
        revizor.setPersistent();
        world.spawnEntity(revizor);
        world.spawnParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 20, 0.5, 0.5, 0.5, 0.02);
        inspections.put(zone.key(), new Inspection(zone.key(), revizor.getUuid(), now + bridge.config.revizorSeconds * 20L));
        bridge.memory.revizorSchedule(zone.key(), -1);
        announce(world, zone, Text.translatable("mobtalk.graft.revizor.arrived", zone.title()).formatted(Formatting.RED));
        for (ServerPlayerEntity p : world.getPlayers()) if (zone.holds(p.getBlockPos())) p.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1f, 0.8f);
    }
    /** Everyone runs about and «paints the grass»; the head hides; the revizor walks to the head's desk. */
    private void panic(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity revizor) {
        VillagerEntity head = bridge.officials.officials(world, zone).getOrDefault(Officialdom.Rank.HEAD, List.of()).stream().findFirst().orElse(null);
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.getCommandTags().contains(REVIZOR) && !v.isAiDisabled())) {
            if (v == head) continue;
            if (random.nextInt(3) == 0) {
                BlockPos to = v.getBlockPos().add(random.nextInt(13) - 6, 0, random.nextInt(13) - 6);
                v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(to, 0.75f, 1));
            }
            world.spawnParticles(new DustParticleEffect(new Vector3f(0.2f, 0.8f, 0.25f), 1.4f), v.getX(), v.getY() + 0.1, v.getZ(), 3, 0.4, 0.05, 0.4, 0);
        }
        if (head != null && !head.isAiDisabled()) {
            Graft.Estate estate = bridge.memory.estate(head.getUuid(), null, null);
            if (estate.placed()) head.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(new Plot(world, estate).at(6, 0, 3), 0.7f, 1));
        }
        if (revizor != null && head != null && !revizor.isAiDisabled()) revizor.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(head, 0.55f, 2));
    }
    /** The verdict: the head of a corrupt village is removed (the dacha confiscated), an honest one is thanked. */
    void judge(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity revizor) {
        VillagerEntity head = bridge.officials.officials(world, zone).getOrDefault(Officialdom.Rank.HEAD, List.of()).stream().findFirst().orElse(null);
        long now = MobClock.now();
        if (head == null) { announce(world, zone, Text.translatable("mobtalk.graft.revizor.nobody")); return; }
        String name = bridge.officials.name(head);
        if (Officialdom.honest(head.getUuid(), bridge.config.officialHonestPercent)) {
            announce(world, zone, Text.translatable("mobtalk.graft.revizor.clean", name).formatted(Formatting.GREEN));
            return;
        }
        head.setVillagerData(head.getVillagerData().withProfession(VillagerProfession.NITWIT));
        head.reinitializeBrain(world);
        Graft.Estate estate = bridge.memory.estateUpdate(head.getUuid(), e -> e.confiscated(true));
        if (estate.placed()) {
            Plot plot = new Plot(world, estate);
            if (plot.loaded()) {
                plot.set(8, 0, -1, Blocks.OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, 8));
                if (world.getBlockEntity(plot.at(8, 0, -1)) instanceof SignBlockEntity sign) {
                    sign.changeText(t -> t.withMessage(0, Text.literal("КОНФИСКОВАНО")).withMessage(1, Text.literal("в пользу")).withMessage(2, Text.literal("государства")), true);
                    sign.markDirty();
                }
                plot.settle();
            }
        }
        bridge.memory.recordEvent(head.getUuid(), MobGameBridge.type(head), head.getName().getString(), "Ревизор из столицы снял тебя с должности главы администрации и конфисковал дачу. Ты теперь никто и очень обижен.", now);
        announce(world, zone, Text.translatable("mobtalk.graft.revizor.removed", name).formatted(Formatting.GOLD));
        rumor(world, zone, "ревизор из столицы снял главу администрации " + name + ", дачу конфисковали", 7);
        if (revizor != null) world.playSound(null, revizor.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 0.7f);
    }
    /** Whether an inspection runs in this village (self-tests). */
    boolean inspecting(String zone) { return inspections.containsKey(zone); }

    // ---- Helpers ----

    private void rumor(ServerWorld world, VillageDefenseController.Zone zone, String text, int importance) {
        if (zone.villageId().isBlank() || text.isBlank()) return;
        VillagerEntity teller = world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby() && OfficialContent.rank(v) == null).stream().findFirst().orElse(null);
        String source = teller == null ? "" : bridge.officials.name(teller);
        long now = MobClock.now();
        bridge.memory.addRumors(zone.villageId(), List.of(new VillageRumors.View("", text, source, importance, -1, now, 1, "")), now);
    }
    private void announce(ServerWorld world, VillageDefenseController.Zone zone, Text text) {
        double range = Math.max(96, bridge.config.alarmNoticeRange);
        Text line = Text.empty().append(Text.literal("[").append(zone.title()).append("] ").formatted(Formatting.GRAY))
                .append(text.getStyle().getColor() == null ? text.copy().formatted(Formatting.GRAY) : text);
        for (ServerPlayerEntity p : world.getPlayers()) if (p.getBlockPos().getSquaredDistance(zone.center()) <= range * range) p.sendMessage(line, false);
    }

    void clearRuntime() { toads.clear(); shouted.clear(); inspections.clear(); }
}
