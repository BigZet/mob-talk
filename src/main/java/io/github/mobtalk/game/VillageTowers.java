package io.github.mobtalk.game;

import io.github.mobtalk.core.TowerPlan;
import io.github.mobtalk.core.VillageAlarm;
import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.PersistentState;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import java.util.*;

/** Watchtowers and their archers (design §4, Dmitriy 2026-10-24: archers shoot the alarm's player without warning, endless
 * arrows, night shifts). A village with enough residents and fewer towers than the settings allow picks a spot at its edge and
 * builds a {@link TowerPlan} there block by block over {@code towerBuildMinutes}, the door to the village; the build survives a
 * restart (world saved data). When it is done an archer comes to the post, and a post empty for {@code archerReplaceMinutes} gets
 * a newcomer. Archers stay on the post by day, half of them at night (the shifts swap every night) and all of them in an alarm;
 * from the post they shoot the monsters about and the players the alarm is against. Server thread only. */
final class VillageTowers {
    static final long PLAN_PERIOD = 600, PLAN_PHASE = 211, POST_PERIOD = 20;
    /** How far apart two towers of a village stand at least, and how far from any bell, bed or job site a tower keeps. */
    static final int TOWER_SPACING = 20, POI_CLEARANCE = 5;
    /** How far outside the village's bounds the tower's centre may go, and the step between the spots tried along the edge. */
    static final int EDGE_OUT = 5, EDGE_STEP = 4;
    /** A tower whose ground differs more than this across its footprint is not built there. */
    static final int MAX_SLOPE = 2;
    /** The builder lays blocks only this close to the tower's centre; one on its way longer than this is set down at the site. */
    static final double AT_SITE = 7;
    static final long BUILDER_WAY_TICKS = 600;
    /** A shooter that cannot reach its post this long (at its tower, or on the way from afar) is put there. */
    static final long SHOOTER_STUCK_TICKS = 400, SHOOTER_WAY_TICKS = 1200;

    private final MobGameBridge bridge;
    private final VillageDefenseController defense;
    private final Map<UUID, Long> lastShot = new HashMap<>();
    /** Since when each shooter on duty has been away from its post. */
    private final Map<UUID, Long> shooterAway = new HashMap<>();
    /** When each empty post was first seen empty, by position. */
    private final Map<BlockPos, Long> emptySince = new HashMap<>();
    /** Since when the builder of each tower (by its origin) has been on its way to the site without getting there. */
    private final Map<BlockPos, Long> builderAway = new HashMap<>();

    VillageTowers(MobGameBridge bridge, VillageDefenseController defense) { this.bridge = bridge; this.defense = defense; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.villageDefense; }

    // ---- Saved builds ----

    /** One tower going up: where, which way the door faces, the style, the next block of the plan and the villager building it. */
    static final class Build {
        String zone; BlockPos origin; TowerPlan.Facing door; TowerPlan.Style style; int next; long nextTick; boolean foundationDone; UUID builder;
        /** A large tower (7×7, two archers and two crossbowmen). */
        boolean large;
        int radius() { return TowerPlan.radius(large); }
        List<TowerPlan.Block> plan() { return TowerPlan.rotated(door, large); }
        NbtCompound write() {
            NbtCompound tag = new NbtCompound();
            tag.putString("zone", zone); tag.putLong("origin", origin.asLong()); tag.putString("door", door.name());
            tag.putString("style", style.name()); tag.putInt("next", next); tag.putBoolean("foundation", foundationDone);
            if (builder != null) tag.putUuid("builder", builder);
            tag.putBoolean("large", large);
            return tag;
        }
        static Build read(NbtCompound tag) {
            Build build = new Build();
            build.zone = tag.getString("zone"); build.origin = BlockPos.fromLong(tag.getLong("origin"));
            build.door = TowerPlan.Facing.valueOf(tag.getString("door")); build.style = TowerPlan.Style.valueOf(tag.getString("style"));
            build.next = tag.getInt("next"); build.foundationDone = tag.getBoolean("foundation");
            if (tag.containsUuid("builder")) build.builder = tag.getUuid("builder");
            build.large = tag.getBoolean("large");
            return build;
        }
    }

    /** The builds of one world, kept in its saved data so a half-built tower is finished after a restart. */
    static final class Saved extends PersistentState {
        final List<Build> builds = new ArrayList<>();
        static final Type<Saved> TYPE = new Type<>(Saved::new, Saved::read, null);
        static Saved read(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            Saved saved = new Saved();
            NbtList list = tag.getList("builds", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                try { saved.builds.add(Build.read(list.getCompound(i))); } catch (IllegalArgumentException broken) { /* an unknown style or side: drop it */ }
            }
            return saved;
        }
        @Override public NbtCompound writeNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            NbtList list = new NbtList();
            for (Build build : builds) list.add(build.write());
            tag.put("builds", list);
            return tag;
        }
    }
    private static Saved saved(ServerWorld world) { return world.getPersistentStateManager().getOrCreate(Saved.TYPE, "mobtalk_towers"); }

    // ---- The tick ----

    void tick(ServerWorld world, List<VillageDefenseController.Zone> zones) {
        if (!enabled() || DefenseContent.ARCHER_POST == null) return;
        long now = world.getTime();
        Saved saved = saved(world);
        for (Iterator<Build> it = saved.builds.iterator(); it.hasNext(); ) {
            Build build = it.next();
            if (!world.isChunkLoaded(build.origin)) continue;
            if (now % POST_PERIOD == 7) attend(world, build, now);
            if (now < build.nextTick) continue;
            if (step(world, build, now)) { it.remove(); finished(world, build, zones); }
            saved.markDirty();
        }
        if (now % PLAN_PERIOD == PLAN_PHASE) {
            for (VillageDefenseController.Zone zone : zones) { plan(world, zone, saved); posts(world, zone, now); }
        }
        if (now % POST_PERIOD == 0) for (VillageDefenseController.Zone zone : zones) archers(world, zone, now);
    }

    /** Towers of the zone: one archer post of each (a large tower has two). */
    private List<BlockPos> towers(ServerWorld world, VillageDefenseController.Zone zone) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos post : posts(world, zone, DefenseContent.ARCHER_POST_POI))
            if (out.stream().noneMatch(other -> other.getSquaredDistance(post) <= 5 * 5)) out.add(post);
        return out;
    }

    /** The posts of one kind inside the zone. */
    private static List<BlockPos> posts(ServerWorld world, VillageDefenseController.Zone zone, net.minecraft.registry.RegistryKey<net.minecraft.world.poi.PointOfInterestType> kind) {
        int radius = (int) Math.ceil(Math.max(zone.box().getLengthX(), zone.box().getLengthZ()) / 2) + 1;
        List<BlockPos> out = new ArrayList<>();
        world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(kind), zone.center(), radius,
                PointOfInterestStorage.OccupationStatus.ANY).map(PointOfInterest::getPos).filter(zone::holds).forEach(out::add);
        return out;
    }

    /** A village short of towers starts one at a good spot of its edge. */
    private void plan(ServerWorld world, VillageDefenseController.Zone zone, Saved saved) {
        if (!bridge.config.villageTowers || bridge.config.towerMax <= 0) return;
        for (Build build : saved.builds) if (build.zone.equals(zone.key())) return;
        int wanted = Math.min(bridge.config.towerMax, zone.residents() / Math.max(1, bridge.config.towerResidents));
        List<BlockPos> existing = towers(world, zone);
        if (existing.size() >= wanted) return;
        VillageAlarm.Alarm alarm = defense.alarm(zone);
        if (alarm != null && alarm.attack()) return; // nobody builds under attack
        boolean large = bridge.config.towerLargeResidents > 0 && zone.residents() >= bridge.config.towerLargeResidents && DefenseContent.CROSSBOW_POST != null;
        BlockPos site = site(world, zone, existing, TowerPlan.radius(large));
        if (site == null) return;
        Build build = new Build();
        build.large = large;
        build.zone = zone.key(); build.origin = site; build.door = facing(site, zone.center());
        build.style = TowerPlan.Style.forBiome(world.getBiome(site).getKey().map(key -> key.getValue().toString()).orElse(""));
        build.nextTick = world.getTime();
        saved.builds.add(build); saved.markDirty();
        announce(world, zone, Text.translatable("mobtalk.defense.tower.started", zone.title(), site.getX() + " " + site.getY() + " " + site.getZ()));
    }

    /** The door faces the village: the side towards its centre. */
    static TowerPlan.Facing facing(BlockPos site, BlockPos center) {
        int dx = center.getX() - site.getX(), dz = center.getZ() - site.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? TowerPlan.Facing.EAST : TowerPlan.Facing.WEST;
        return dz > 0 ? TowerPlan.Facing.SOUTH : TowerPlan.Facing.NORTH;
    }

    /** A spot just outside the village's buildings, flat enough, on natural ground, clear above, away from its bells, beds and job
     * sites and from its other towers; the one farthest from the other towers wins. Null when there is none. */
    private BlockPos site(ServerWorld world, VillageDefenseController.Zone zone, List<BlockPos> existing, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        int minX = zone.minX() - EDGE_OUT, maxX = zone.maxX() + EDGE_OUT, minZ = zone.minZ() - EDGE_OUT, maxZ = zone.maxZ() + EDGE_OUT;
        for (int x = minX; x <= maxX; x += EDGE_STEP) { candidates.add(new BlockPos(x, 0, minZ)); candidates.add(new BlockPos(x, 0, maxZ)); }
        for (int z = minZ + EDGE_STEP; z < maxZ; z += EDGE_STEP) { candidates.add(new BlockPos(minX, 0, z)); candidates.add(new BlockPos(maxX, 0, z)); }
        BlockPos best = null; double bestScore = -1;
        for (BlockPos spot : candidates) {
            if (!world.isChunkLoaded(spot) || !zone.holds(spot.withY(zone.center().getY()))) continue;
            BlockPos floor = ground(world, spot, radius);
            if (floor == null) continue;
            double nearest = Double.MAX_VALUE;
            for (BlockPos tower : existing) nearest = Math.min(nearest, Math.sqrt(tower.getSquaredDistance(floor)));
            if (nearest < TOWER_SPACING) continue;
            if (world.getPointOfInterestStorage().getInSquare(entry -> true, floor, POI_CLEARANCE, PointOfInterestStorage.OccupationStatus.ANY).findAny().isPresent()) continue;
            double score = existing.isEmpty() ? world.random.nextDouble() : nearest;
            if (score > bestScore) { bestScore = score; best = floor; }
        }
        return best;
    }

    /** The floor block of a tower centred here: the highest ground of the footprint (and the step before the door), when the ground
     * is natural, dry and level within {@link #MAX_SLOPE} and the tower's space above is free. */
    private static BlockPos ground(ServerWorld world, BlockPos spot, int radius) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        int reach = radius + 1;
        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++) {
                int x = spot.getX() + dx, z = spot.getZ() + dz;
                BlockPos top = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                BlockState state = world.getBlockState(top);
                if (!natural(state) || !world.getFluidState(top).isEmpty() || !world.getFluidState(top.up()).isEmpty()) return null;
                low = Math.min(low, top.getY()); high = Math.max(high, top.getY());
            }
        if (high - low > MAX_SLOPE) return null;
        BlockPos floor = new BlockPos(spot.getX(), high, spot.getZ());
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dy = 1; dy <= TowerPlan.HEIGHT; dy++) {
                    BlockState state = world.getBlockState(floor.add(dx, dy, dz));
                    if (!state.isAir() && !state.isReplaceable()) return null;
                }
        return floor;
    }

    /** Ground a village may build on: earth, sand, stone, gravel, snow — not a path, a floor or anything the player placed. */
    private static boolean natural(BlockState state) {
        if (state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.FARMLAND)) return false;
        return state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SANDSTONE) || state.isOf(Blocks.TERRACOTTA)
                || state.isIn(BlockTags.TERRACOTTA);
    }

    // ---- Building ----

    /** The next block when its time has come and the builder is at the site; true when the tower is done. */
    private boolean step(ServerWorld world, Build build, long now) {
        if (now < build.nextTick) return false;
        VillagerEntity builder = builder(world, build);
        if (builder == null || builder.squaredDistanceTo(Vec3d.ofCenter(build.origin)) > AT_SITE * AT_SITE) { build.nextTick = now + POST_PERIOD; return false; }
        List<TowerPlan.Block> plan = build.plan();
        if (!build.foundationDone) { foundation(world, build); build.foundationDone = true; }
        long solid = plan.stream().filter(b -> b.kind() != TowerPlan.Kind.AIR).count();
        long interval = Math.max(1, bridge.config.towerBuildMinutes * 1200L / Math.max(1, solid));
        while (build.next < plan.size()) {
            TowerPlan.Block block = plan.get(build.next++);
            BlockPos pos = build.origin.add(block.x(), block.y(), block.z());
            if (place(world, build.style, block, pos)) {
                build.nextTick = now + interval;
                work(world, builder, pos);
                return false;
            }
        }
        return true;
    }

    /** Fills the ground under the footprint down to solid earth, so a tower on a slope stands on stone, not on air. */
    private void foundation(ServerWorld world, Build build) {
        BlockState stone = palette(build.style).foundation;
        int r = build.radius();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r - 1; dz <= r; dz++) {
                for (int dy = -1; dy >= -8; dy--) {
                    BlockPos pos = rotate(build, dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (!state.isAir() && !state.isReplaceable() && world.getFluidState(pos).isEmpty()) break;
                    world.setBlockState(pos, stone, Block.NOTIFY_ALL);
                }
            }
    }
    private static BlockPos rotate(Build build, int x, int y, int z) {
        int nx = x, nz = z;
        for (int i = 0; i < build.door.ordinal(); i++) { int t = nx; nx = -nz; nz = t; }
        return build.origin.add(nx, y, nz);
    }

    /** One block of the plan; false when there was nothing to do (air already there, or something the village must not touch). */
    private boolean place(ServerWorld world, TowerPlan.Style style, TowerPlan.Block block, BlockPos pos) {
        BlockState current = world.getBlockState(pos);
        if (block.kind() == TowerPlan.Kind.AIR) {
            if (current.isAir()) return false;
            if (!current.isReplaceable() && !natural(current) && !current.isIn(BlockTags.LEAVES) && !current.isIn(BlockTags.FLOWERS)) return false;
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            return false; // clearing is quick: it does not take a builder's turn
        }
        if (block.kind() == TowerPlan.Kind.DOOR_UPPER) return false; // placed with the lower half
        if (!current.isAir() && !current.isReplaceable() && !natural(current)) return false;
        Palette palette = palette(style);
        Direction facing = block.facing() == null ? Direction.NORTH : direction(block.facing());
        int flags = Block.NOTIFY_ALL;
        BlockState state = switch (block.kind()) {
            case FLOOR -> palette.floor;
            case POST -> palette.post;
            case WALL -> palette.wall;
            case STAIR -> palette.stair.with(StairsBlock.FACING, facing).with(StairsBlock.HALF, BlockHalf.BOTTOM);
            case WINDOW -> Blocks.GLASS_PANE.getDefaultState();
            case RAIL -> palette.rail;
            case ROOF -> palette.roof;
            case LANTERN -> Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true);
            case POST_BLOCK -> DefenseContent.ARCHER_POST.getDefaultState();
            case CROSSBOW_POST -> DefenseContent.CROSSBOW_POST.getDefaultState();
            case DOOR_LOWER -> palette.door.with(DoorBlock.FACING, facing).with(DoorBlock.HINGE, DoorHinge.LEFT).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            default -> null;
        };
        if (state == null) return false;
        if (block.kind() == TowerPlan.Kind.DOOR_LOWER) {
            flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
            world.setBlockState(pos, state, flags);
            world.setBlockState(pos.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), flags);
        } else world.setBlockState(pos, state, flags);
        world.playSound(null, pos, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.7f, 1f);
        return true;
    }

    private static Direction direction(TowerPlan.Facing facing) {
        return switch (facing) { case NORTH -> Direction.NORTH; case EAST -> Direction.EAST; case SOUTH -> Direction.SOUTH; case WEST -> Direction.WEST; };
    }

    /** The villager building the tower: the one already on it while it is about and awake, else a mason of the village, else its
     * grown villager nearest the site (never a guard or an archer, nobody in a conversation). Null when nobody can come now (the
     * village sleeps): the tower waits for its builder. A newly picked builder is named in the chat. */
    private VillagerEntity builder(ServerWorld world, Build build) {
        VillageDefenseController.Zone zone = defense.zone(world, build.zone);
        if (build.builder != null && world.getEntity(build.builder) instanceof VillagerEntity current && fit(current)
                && (zone == null || zone.box().expand(16).contains(current.getPos()))) return current;
        if (zone == null) return null;
        VillagerEntity best = null; double bestScore = Double.MAX_VALUE;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, zone.box(), this::fit)) {
            double score = villager.squaredDistanceTo(Vec3d.ofCenter(build.origin)) - (villager.getVillagerData().getProfession() == VillagerProfession.MASON ? 1e6 : 0);
            if (score < bestScore) { bestScore = score; best = villager; }
        }
        if (best == null) return null;
        if (build.builder != null && world.getEntity(build.builder) instanceof VillagerEntity old) emptyHands(old);
        build.builder = best.getUuid();
        builderAway.remove(build.origin);
        announce(world, zone, Text.translatable("mobtalk.defense.tower.builder", zone.title(), name(best)));
        return best;
    }

    private boolean fit(VillagerEntity villager) {
        return villager.isAlive() && !villager.isRemoved() && !villager.isBaby() && !villager.isSleeping()
                && !DefenseContent.defender(villager) && !bridge.actions.busy(villager);
    }

    /** Once a second: the builder keeps to the site with a block in hand, walking round to the side that is going up; one that has
     * not got there in {@link #BUILDER_WAY_TICKS} (stuck behind a fence or a river) is set down at the site. */
    private void attend(ServerWorld world, Build build, long now) {
        VillagerEntity builder = builder(world, build);
        if (builder == null) return;
        VillageDefenseController.stayOut(world, builder);
        List<TowerPlan.Block> plan = build.plan();
        TowerPlan.Block next = build.next < plan.size() ? plan.get(build.next) : null;
        BlockPos target = next == null ? build.origin : build.origin.add(next.x(), next.y(), next.z());
        BlockPos stand = stand(world, build, target);
        if (builder.getMainHandStack().isEmpty()) builder.equipStack(EquipmentSlot.MAINHAND, new ItemStack(palette(build.style).wall.getBlock().asItem()));
        builder.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        if (MobActionController.registered(builder, MemoryModuleType.LOOK_TARGET)) builder.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(target), 40);
        if (builder.squaredDistanceTo(Vec3d.ofCenter(stand)) <= 2.5 * 2.5) { builderAway.remove(build.origin); return; }
        if (MobActionController.registered(builder, MemoryModuleType.WALK_TARGET)) builder.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, 0.55F, 1), 40);
        Long away = builderAway.putIfAbsent(build.origin, now);
        if (away != null && now - away >= BUILDER_WAY_TICKS) {
            builder.requestTeleport(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            builderAway.remove(build.origin);
        }
    }

    /** Where the builder stands: on the ground just outside the footprint, on the side of the block going up. */
    private static BlockPos stand(ServerWorld world, Build build, BlockPos target) {
        int dx = target.getX() - build.origin.getX(), dz = target.getZ() - build.origin.getZ(), out = build.radius() + 2;
        int x = build.origin.getX(), z = build.origin.getZ();
        if (dx == 0 && dz == 0) { BlockPos front = rotate(build, 0, 0, -out); x = front.getX(); z = front.getZ(); }
        else if (Math.abs(dx) >= Math.abs(dz)) x += Integer.signum(dx) * out;
        else z += Integer.signum(dz) * out;
        return new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** One block laid: the builder swings at it, and dust of the block flies where it went. */
    private static void work(ServerWorld world, VillagerEntity builder, BlockPos placed) {
        builder.swingHand(Hand.MAIN_HAND);
        builder.getLookControl().lookAt(Vec3d.ofCenter(placed));
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, world.getBlockState(placed)),
                placed.getX() + 0.5, placed.getY() + 0.5, placed.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.05);
    }

    /** The builder puts away the block it carried for the work. */
    private static void emptyHands(VillagerEntity builder) {
        if (builder.getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem) builder.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    /** The tower is up: the builder goes back to its life, a shooter comes to each post (an archer, or a crossbowman on a large tower's
     * sides). */
    private void finished(ServerWorld world, Build build, List<VillageDefenseController.Zone> zones) {
        if (build.builder != null && world.getEntity(build.builder) instanceof VillagerEntity builder) emptyHands(builder);
        builderAway.remove(build.origin);
        VillageDefenseController.Zone zone = null;
        for (VillageDefenseController.Zone candidate : zones) if (candidate.key().equals(build.zone)) zone = candidate;
        List<String> came = new ArrayList<>();
        for (TowerPlan.Block block : build.plan())
            if (block.kind() == TowerPlan.Kind.POST_BLOCK || block.kind() == TowerPlan.Kind.CROSSBOW_POST) {
                VillagerEntity shooter = newcomer(world, build.origin.add(block.x(), block.y(), block.z()));
                if (shooter != null) came.add(name(shooter));
            }
        if (zone != null) announce(world, zone, Text.translatable("mobtalk.defense.tower.done", zone.title(), came.isEmpty() ? "?" : String.join(", ", came)));
    }

    // ---- Archers ----

    /** Posts left empty long enough get a newcomer: an archer, a crossbowman. */
    private void posts(ServerWorld world, VillageDefenseController.Zone zone, long now) {
        List<BlockPos> all = new ArrayList<>(posts(world, zone, DefenseContent.ARCHER_POST_POI));
        if (DefenseContent.CROSSBOW_POST != null) all.addAll(posts(world, zone, DefenseContent.CROSSBOW_POST_POI));
        for (BlockPos post : all) {
            var kind = poi(world, post);
            boolean empty = kind != null && world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(kind), post, 0,
                            PointOfInterestStorage.OccupationStatus.HAS_SPACE).findAny().isPresent();
            if (!empty) { emptySince.remove(post); continue; }
            Long since = emptySince.putIfAbsent(post, now);
            if (since == null || now - since < bridge.config.archerReplaceMinutes * 1200L) continue;
            emptySince.remove(post);
            VillagerEntity archer = newcomer(world, post);
            if (archer != null) announce(world, zone, Text.translatable("mobtalk.defense.archer.arrived", zone.title(), name(archer)));
        }
    }

    /** The kind of post a block is, or null. */
    private static net.minecraft.registry.RegistryKey<net.minecraft.world.poi.PointOfInterestType> poi(ServerWorld world, BlockPos post) {
        BlockState state = world.getBlockState(post);
        if (DefenseContent.ARCHER_POST != null && state.isOf(DefenseContent.ARCHER_POST)) return DefenseContent.ARCHER_POST_POI;
        if (DefenseContent.CROSSBOW_POST != null && state.isOf(DefenseContent.CROSSBOW_POST)) return DefenseContent.CROSSBOW_POST_POI;
        return null;
    }

    /** A grown villager of the local kind with the post's profession (archer or crossbowman) and its bow, standing by the post, the
     * post its job site. */
    private VillagerEntity newcomer(ServerWorld world, BlockPos post) {
        var kind = poi(world, post);
        if (kind == null) return null;
        boolean crossbow = kind == DefenseContent.CROSSBOW_POST_POI;
        if (world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(kind), post, 0,
                PointOfInterestStorage.OccupationStatus.HAS_SPACE).findAny().isEmpty()) return null; // someone already holds the post
        BlockPos stand = standBy(world, post);
        if (stand == null) return null;
        VillagerEntity archer = EntityType.VILLAGER.create(world);
        if (archer == null) return null;
        archer.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, world.random.nextFloat() * 360f, 0f);
        archer.initialize(world, world.getLocalDifficulty(stand), SpawnReason.MOB_SUMMONED, null);
        archer.setVillagerData(new VillagerData(VillagerType.forBiome(world.getBiome(post)), crossbow ? DefenseContent.CROSSBOWMAN : DefenseContent.ARCHER, 1));
        archer.setBreedingAge(0);
        world.spawnEntityAndPassengers(archer);
        world.getPointOfInterestStorage().getPosition(entry -> entry.matchesKey(kind), (entry, pos) -> pos.equals(post), post, 1);
        archer.reinitializeBrain(world); // a profession set by hand needs the brain vanilla builds for it, or the villager drops it
        archer.getBrain().remember(MemoryModuleType.JOB_SITE, GlobalPos.create(world.getRegistryKey(), post));
        arm(archer);
        return archer;
    }

    /** The shooter's bow in hand, never dropped: a bow for an archer, a crossbow for a crossbowman. */
    private static void arm(VillagerEntity shooter) {
        if (!shooter.getMainHandStack().isEmpty()) return;
        shooter.equipStack(EquipmentSlot.MAINHAND, new ItemStack(DefenseContent.crossbowman(shooter) ? Items.CROSSBOW : Items.BOW));
        shooter.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
    }

    /** Where one can stand beside the post: solid under the feet, air at the feet and the head (the stairwell is no place). */
    private static BlockPos standBy(ServerWorld world, BlockPos post) {
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos feet = post.offset(side);
            if (world.getBlockState(feet.down()).isSolidBlock(world, feet.down()) && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                    && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) return feet;
        }
        return null;
    }

    /** The archers of a zone keep their post and shoot from it. */
    private void archers(ServerWorld world, VillageDefenseController.Zone zone, long now) {
        VillageAlarm.Alarm alarm = defense.alarm(zone);
        boolean night = !world.isDay();
        long nightIndex = world.getTimeOfDay() / 24000L;
        for (VillagerEntity archer : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isRemoved() && !v.isBaby() && DefenseContent.shooter(v))) {
            BlockPos post = post(world, archer);
            boolean onShift = !night || ((archer.getUuid().getLeastSignificantBits() & 1) == (nightIndex & 1));
            boolean onDuty = post != null && (alarm != null || onShift);
            if (onDuty && archer.isSleeping()) archer.wakeUp();
            if (onDuty) VillageDefenseController.stayOut(world, archer);
            arm(archer);
            if (alarm != null && !archer.isSleeping()) shoot(world, zone, alarm, archer, now);
            boolean away = onDuty && !archer.isSleeping() && archer.squaredDistanceTo(Vec3d.ofCenter(post)) > 2.5 * 2.5 && !bridge.actions.busy(archer);
            if (away && MobActionController.registered(archer, MemoryModuleType.WALK_TARGET))
                archer.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(post, alarm != null ? 0.8F : 0.55F, 1), 40);
            if (!away) { shooterAway.remove(archer.getUuid()); continue; }
            // Stuck on the way (a turn of the stair, a crowd at the door): at its tower after 20 s, from afar after a minute, it is put at the post.
            Long since = shooterAway.putIfAbsent(archer.getUuid(), now);
            boolean atTower = Math.hypot(archer.getX() - post.getX() - 0.5, archer.getZ() - post.getZ() - 0.5) <= 6;
            if (since != null && now - since >= (atTower ? SHOOTER_STUCK_TICKS : SHOOTER_WAY_TICKS)) {
                BlockPos stand = standBy(world, post);
                if (stand != null) { archer.getNavigation().stop(); archer.requestTeleport(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5); }
                shooterAway.remove(archer.getUuid());
            }
        }
    }

    private static BlockPos post(ServerWorld world, VillagerEntity archer) {
        if (!MobActionController.registered(archer, MemoryModuleType.JOB_SITE)) return null;
        Optional<GlobalPos> site = archer.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE);
        return site.isPresent() && site.get().dimension() == world.getRegistryKey() ? site.get().pos() : null;
    }

    /** One arrow at the nearest monster or alarm player in range and sight, when the bow is ready and no neighbour stands in the way.
     * The arrow cannot be picked up: an archer's arrows never run out. A crossbowman shoots less often, farther, faster and harder. */
    private void shoot(ServerWorld world, VillageDefenseController.Zone zone, VillageAlarm.Alarm alarm, VillagerEntity archer, long now) {
        Long last = lastShot.get(archer.getUuid());
        boolean crossbow = DefenseContent.crossbowman(archer);
        long cooldown = Math.max(5, (crossbow ? bridge.config.crossbowShotTenths : bridge.config.archerShotTenths) * 2L);
        if (last != null && now - last < cooldown) return;
        double range = crossbow ? bridge.config.crossbowRange : bridge.config.archerRange;
        BlockPos post = post(world, archer);
        LivingEntity target = null; double nearest = range * range;
        for (MobEntity monster : world.getEntitiesByClass(MobEntity.class, archer.getBoundingBox().expand(range), defense::threat)) {
            double distance = archer.squaredDistanceTo(monster);
            if (distance < nearest && sees(world, archer, post, monster)) { nearest = distance; target = monster; }
        }
        if (bridge.config.alarmAgainstPlayers)
            for (UUID id : alarm.targets.keySet()) {
                ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
                if (player == null || player.getWorld() != world || !player.isAlive() || player.isCreative() || player.isSpectator()) continue;
                double distance = archer.squaredDistanceTo(player);
                if (distance < nearest && sees(world, archer, post, player)) { nearest = distance; target = player; }
            }
        if (target == null) return;
        archer.getLookControl().lookAt(target, 30f, 30f);
        if (MobActionController.registered(archer, MemoryModuleType.LOOK_TARGET)) archer.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(target, true), 40);
        Vec3d from = release(world, archer, post, target), to = target.getPos().add(0, target.getHeight() * 0.6, 0);
        if (blocked(world, archer, target, from, to)) return;
        lastShot.put(archer.getUuid(), now);
        ArrowEntity arrow = new ArrowEntity(world, archer, new ItemStack(Items.ARROW), new ItemStack(crossbow ? Items.CROSSBOW : Items.BOW));
        arrow.setPosition(from);
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        arrow.setDamage(crossbow ? 3.5 : 2.5);
        double dx = to.x - from.x, dz = to.z - from.z;
        double dy = to.y - arrow.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float speed = crossbow ? 3.15f : 1.9f;
        arrow.setVelocity(dx, dy + flat * (crossbow ? 0.05 : 0.12), dz, speed, crossbow ? 3f : 6f);
        world.spawnEntity(arrow);
        archer.swingHand(Hand.MAIN_HAND);
        world.playSound(null, archer.getX(), archer.getY(), archer.getZ(), crossbow ? SoundEvents.ITEM_CROSSBOW_SHOOT : SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.NEUTRAL, 1f,
                1f / (world.random.nextFloat() * 0.4f + 0.8f));
    }

    /** Where the arrow leaves: at a post the archer leans over the railing on the target's side (from beside the post the railing
     * hides everything close below the tower, the village included); elsewhere from the eyes. */
    private static Vec3d release(ServerWorld world, VillagerEntity archer, BlockPos post, LivingEntity target) {
        Vec3d eye = archer.getEyePos();
        if (post == null || archer.squaredDistanceTo(Vec3d.ofCenter(post)) > 2.5 * 2.5) return eye;
        // The tower's middle: the post itself on a small tower, two blocks off on a large one; the lantern hangs over it.
        BlockPos middle = post;
        boolean large = false;
        if (!world.getBlockState(post.up(2)).isOf(Blocks.LANTERN))
            for (Direction side : Direction.Type.HORIZONTAL)
                if (world.getBlockState(post.offset(side, 2).up(2)).isOf(Blocks.LANTERN)) { middle = post.offset(side, 2); large = true; break; }
        double edge = TowerPlan.radius(large) + 0.75; // just past the railing of the square platform
        double cx = middle.getX() + 0.5, cz = middle.getZ() + 0.5;
        double dx = target.getX() - eye.x, dz = target.getZ() - eye.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-3) return eye;
        dx /= length; dz /= length;
        // From the archer towards the target, out to where the line leaves the platform's square.
        double t = Double.MAX_VALUE;
        if (Math.abs(dx) > 1e-6) t = Math.min(t, ((dx > 0 ? edge : -edge) - (eye.x - cx)) / dx);
        if (Math.abs(dz) > 1e-6) t = Math.min(t, ((dz > 0 ? edge : -edge) - (eye.z - cz)) / dz);
        if (t <= 0 || t == Double.MAX_VALUE) return eye;
        return new Vec3d(eye.x + dx * t, post.getY() + 1.7, eye.z + dz * t);
    }

    /** The archer sees the target: from its eyes, or leaning over the railing at its post. */
    private static boolean sees(ServerWorld world, VillagerEntity archer, BlockPos post, LivingEntity target) {
        if (archer.canSee(target)) return true;
        Vec3d from = release(world, archer, post, target);
        if (from.equals(archer.getEyePos())) return false;
        return world.raycast(new RaycastContext(from, target.getEyePos(), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, archer)).getType() == HitResult.Type.MISS;
    }

    /** A villager or golem of the village in the line of fire. */
    private static boolean blocked(ServerWorld world, VillagerEntity archer, LivingEntity target, Vec3d from, Vec3d to) {
        Box path = new Box(from, to).expand(1);
        for (LivingEntity friend : world.getEntitiesByClass(LivingEntity.class, path, e -> e != archer && e != target && e.isAlive()
                && (e instanceof VillagerEntity || e instanceof IronGolemEntity)))
            if (friend.getBoundingBox().expand(0.3).raycast(from, to).isPresent()) return true;
        return false;
    }

    /** The horn from the archer nearest the danger when the alarm is first raised or grows. */
    void horn(ServerWorld world, VillageDefenseController.Zone zone, BlockPos at) {
        VillagerEntity horn = null; double nearest = Double.MAX_VALUE;
        for (VillagerEntity archer : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby() && DefenseContent.shooter(v))) {
            double distance = archer.getBlockPos().getSquaredDistance(at);
            if (distance < nearest) { nearest = distance; horn = archer; }
        }
        if (horn == null) return;
        world.playSound(null, horn.getX(), horn.getY(), horn.getZ(), SoundEvents.GOAT_HORN_SOUNDS.get(0).value(), SoundCategory.NEUTRAL, 6f, 1f);
    }

    /** An archer sees farther than other villagers: the alarm uses its range for it. */
    double sight(LivingEntity watcher, double usual) {
        return watcher instanceof VillagerEntity villager && DefenseContent.shooter(villager) ? Math.max(usual, DefenseContent.crossbowman(villager) ? bridge.config.crossbowRange : bridge.config.archerRange) : usual;
    }

    private void announce(ServerWorld world, VillageDefenseController.Zone zone, Text text) {
        if (!bridge.config.alarmChat) return;
        double range = bridge.config.alarmNoticeRange;
        for (ServerPlayerEntity player : world.getPlayers())
            if (player.getBlockPos().getSquaredDistance(zone.center()) <= range * range) player.sendMessage(text.copy().formatted(Formatting.GRAY), false);
    }

    private String name(VillagerEntity villager) {
        String name = bridge.memory.displayName(villager.getUuid());
        return name == null || name.isBlank() ? villager.getName().getString() : name;
    }

    private record Palette(BlockState foundation, BlockState floor, BlockState post, BlockState wall, BlockState stair, BlockState door, BlockState rail, BlockState roof) { }

    private static Palette palette(TowerPlan.Style style) {
        return switch (style) {
            case SPRUCE -> new Palette(Blocks.COBBLESTONE.getDefaultState(), Blocks.COBBLESTONE.getDefaultState(), Blocks.SPRUCE_LOG.getDefaultState(),
                    Blocks.SPRUCE_PLANKS.getDefaultState(), Blocks.SPRUCE_STAIRS.getDefaultState(), Blocks.SPRUCE_DOOR.getDefaultState(),
                    Blocks.SPRUCE_FENCE.getDefaultState(), Blocks.SPRUCE_SLAB.getDefaultState());
            case ACACIA -> new Palette(Blocks.COBBLESTONE.getDefaultState(), Blocks.COBBLESTONE.getDefaultState(), Blocks.ACACIA_LOG.getDefaultState(),
                    Blocks.ACACIA_PLANKS.getDefaultState(), Blocks.ACACIA_STAIRS.getDefaultState(), Blocks.ACACIA_DOOR.getDefaultState(),
                    Blocks.ACACIA_FENCE.getDefaultState(), Blocks.ACACIA_SLAB.getDefaultState());
            case JUNGLE -> new Palette(Blocks.MOSSY_COBBLESTONE.getDefaultState(), Blocks.MOSSY_COBBLESTONE.getDefaultState(), Blocks.JUNGLE_LOG.getDefaultState(),
                    Blocks.JUNGLE_PLANKS.getDefaultState(), Blocks.JUNGLE_STAIRS.getDefaultState(), Blocks.JUNGLE_DOOR.getDefaultState(),
                    Blocks.JUNGLE_FENCE.getDefaultState(), Blocks.JUNGLE_SLAB.getDefaultState());
            case SANDSTONE -> new Palette(Blocks.SANDSTONE.getDefaultState(), Blocks.SMOOTH_SANDSTONE.getDefaultState(), Blocks.CUT_SANDSTONE.getDefaultState(),
                    Blocks.SMOOTH_SANDSTONE.getDefaultState(), Blocks.SANDSTONE_STAIRS.getDefaultState(), Blocks.JUNGLE_DOOR.getDefaultState(),
                    Blocks.SANDSTONE_WALL.getDefaultState(), Blocks.SANDSTONE_SLAB.getDefaultState());
            case OAK -> new Palette(Blocks.COBBLESTONE.getDefaultState(), Blocks.COBBLESTONE.getDefaultState(), Blocks.OAK_LOG.getDefaultState(),
                    Blocks.OAK_PLANKS.getDefaultState(), Blocks.OAK_STAIRS.getDefaultState(), Blocks.OAK_DOOR.getDefaultState(),
                    Blocks.OAK_FENCE.getDefaultState(), Blocks.OAK_SLAB.getDefaultState());
        };
    }

    void clearRuntime() { lastShot.clear(); emptySince.clear(); builderAway.clear(); shooterAway.clear(); }
}
