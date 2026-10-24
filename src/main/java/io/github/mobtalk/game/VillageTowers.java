package io.github.mobtalk.game;

import io.github.mobtalk.core.TowerPlan;
import io.github.mobtalk.core.VillageAlarm;
import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EntityType;
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
import net.minecraft.village.VillagerType;
import net.minecraft.world.Heightmap;
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

    private final MobGameBridge bridge;
    private final VillageDefenseController defense;
    private final Map<UUID, Long> lastShot = new HashMap<>();
    /** When each empty post was first seen empty, by position. */
    private final Map<BlockPos, Long> emptySince = new HashMap<>();

    VillageTowers(MobGameBridge bridge, VillageDefenseController defense) { this.bridge = bridge; this.defense = defense; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.villageDefense; }

    // ---- Saved builds ----

    /** One tower going up: where, which way the door faces, the style, and the next block of the plan. */
    static final class Build {
        String zone; BlockPos origin; TowerPlan.Facing door; TowerPlan.Style style; int next; long nextTick; boolean foundationDone;
        NbtCompound write() {
            NbtCompound tag = new NbtCompound();
            tag.putString("zone", zone); tag.putLong("origin", origin.asLong()); tag.putString("door", door.name());
            tag.putString("style", style.name()); tag.putInt("next", next); tag.putBoolean("foundation", foundationDone);
            return tag;
        }
        static Build read(NbtCompound tag) {
            Build build = new Build();
            build.zone = tag.getString("zone"); build.origin = BlockPos.fromLong(tag.getLong("origin"));
            build.door = TowerPlan.Facing.valueOf(tag.getString("door")); build.style = TowerPlan.Style.valueOf(tag.getString("style"));
            build.next = tag.getInt("next"); build.foundationDone = tag.getBoolean("foundation");
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
            if (now < build.nextTick || !world.isChunkLoaded(build.origin)) continue;
            if (step(world, build, now)) { it.remove(); finished(world, build, zones); }
            saved.markDirty();
        }
        if (now % PLAN_PERIOD == PLAN_PHASE) {
            for (VillageDefenseController.Zone zone : zones) { plan(world, zone, saved); posts(world, zone, now); }
        }
        if (now % POST_PERIOD == 0) for (VillageDefenseController.Zone zone : zones) archers(world, zone, now);
    }

    /** Towers of the zone: the archer posts inside it. */
    private List<BlockPos> towers(ServerWorld world, VillageDefenseController.Zone zone) {
        int radius = (int) Math.ceil(Math.max(zone.box().getLengthX(), zone.box().getLengthZ()) / 2) + 1;
        List<BlockPos> out = new ArrayList<>();
        world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(DefenseContent.ARCHER_POST_POI), zone.center(), radius,
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
        BlockPos site = site(world, zone, existing);
        if (site == null) return;
        Build build = new Build();
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
    private BlockPos site(ServerWorld world, VillageDefenseController.Zone zone, List<BlockPos> existing) {
        List<BlockPos> candidates = new ArrayList<>();
        int minX = zone.minX() - EDGE_OUT, maxX = zone.maxX() + EDGE_OUT, minZ = zone.minZ() - EDGE_OUT, maxZ = zone.maxZ() + EDGE_OUT;
        for (int x = minX; x <= maxX; x += EDGE_STEP) { candidates.add(new BlockPos(x, 0, minZ)); candidates.add(new BlockPos(x, 0, maxZ)); }
        for (int z = minZ + EDGE_STEP; z < maxZ; z += EDGE_STEP) { candidates.add(new BlockPos(minX, 0, z)); candidates.add(new BlockPos(maxX, 0, z)); }
        BlockPos best = null; double bestScore = -1;
        for (BlockPos spot : candidates) {
            if (!world.isChunkLoaded(spot) || !zone.holds(spot.withY(zone.center().getY()))) continue;
            BlockPos floor = ground(world, spot);
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
    private static BlockPos ground(ServerWorld world, BlockPos spot) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        int reach = TowerPlan.RADIUS + 1;
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
        for (int dx = -TowerPlan.RADIUS; dx <= TowerPlan.RADIUS; dx++)
            for (int dz = -TowerPlan.RADIUS; dz <= TowerPlan.RADIUS; dz++)
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

    /** The next block when its time has come; true when the tower is done. */
    private boolean step(ServerWorld world, Build build, long now) {
        if (now < build.nextTick) return false;
        List<TowerPlan.Block> plan = TowerPlan.rotated(build.door);
        if (!build.foundationDone) { foundation(world, build); build.foundationDone = true; }
        long solid = plan.stream().filter(b -> b.kind() != TowerPlan.Kind.AIR).count();
        long interval = Math.max(1, bridge.config.towerBuildMinutes * 1200L / Math.max(1, solid));
        while (build.next < plan.size()) {
            TowerPlan.Block block = plan.get(build.next++);
            BlockPos pos = build.origin.add(block.x(), block.y(), block.z());
            if (place(world, build.style, block, pos)) {
                build.nextTick = now + interval;
                worker(world, build, pos);
                return false;
            }
        }
        return true;
    }

    /** Fills the ground under the footprint down to solid earth, so a tower on a slope stands on stone, not on air. */
    private void foundation(ServerWorld world, Build build) {
        BlockState stone = palette(build.style).foundation;
        for (int dx = -TowerPlan.RADIUS; dx <= TowerPlan.RADIUS; dx++)
            for (int dz = -TowerPlan.RADIUS - 1; dz <= TowerPlan.RADIUS; dz++) {
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

    /** A villager of the village stands by and works while the tower goes up (the look only: it does not carry blocks). */
    private void worker(ServerWorld world, Build build, BlockPos placed) {
        VillageDefenseController.Zone zone = defense.zone(world, build.zone);
        if (zone == null) return;
        VillagerEntity worker = null; double nearest = 48 * 48;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby() && !v.isSleeping()
                && !DefenseContent.archer(v) && !bridge.actions.busy(v))) {
            double distance = villager.squaredDistanceTo(Vec3d.ofCenter(build.origin));
            if (distance < nearest) { nearest = distance; worker = villager; }
        }
        if (worker == null) return;
        BlockPos stand = rotate(build, 0, 1, -TowerPlan.RADIUS - 2);
        if (MobActionController.registered(worker, MemoryModuleType.WALK_TARGET)) worker.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, 0.5F, 2), 200);
        if (MobActionController.registered(worker, MemoryModuleType.LOOK_TARGET)) worker.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(placed), 60);
        if (worker.squaredDistanceTo(Vec3d.ofCenter(stand)) <= 4 * 4) worker.swingHand(Hand.MAIN_HAND);
    }

    /** The tower is up: an archer comes to its post. */
    private void finished(ServerWorld world, Build build, List<VillageDefenseController.Zone> zones) {
        BlockPos post = build.origin.up(TowerPlan.PLATFORM + 1);
        VillageDefenseController.Zone zone = null;
        for (VillageDefenseController.Zone candidate : zones) if (candidate.key().equals(build.zone)) zone = candidate;
        VillagerEntity archer = newcomer(world, post);
        if (zone != null) announce(world, zone, Text.translatable("mobtalk.defense.tower.done", zone.title(), archer == null ? "?" : name(archer)));
    }

    // ---- Archers ----

    /** Posts left empty long enough get a newcomer archer. */
    private void posts(ServerWorld world, VillageDefenseController.Zone zone, long now) {
        for (BlockPos post : towers(world, zone)) {
            boolean empty = world.getPointOfInterestStorage().getType(post).isPresent()
                    && world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(DefenseContent.ARCHER_POST_POI), post, 0,
                            PointOfInterestStorage.OccupationStatus.HAS_SPACE).findAny().isPresent();
            if (!empty) { emptySince.remove(post); continue; }
            Long since = emptySince.putIfAbsent(post, now);
            if (since == null || now - since < bridge.config.archerReplaceMinutes * 1200L) continue;
            emptySince.remove(post);
            VillagerEntity archer = newcomer(world, post);
            if (archer != null) announce(world, zone, Text.translatable("mobtalk.defense.archer.arrived", zone.title(), name(archer)));
        }
    }

    /** A grown villager of the local kind with the archer's profession, standing by the post, the post its job site. */
    private VillagerEntity newcomer(ServerWorld world, BlockPos post) {
        if (DefenseContent.ARCHER_POST == null || !world.getBlockState(post).isOf(DefenseContent.ARCHER_POST)) return null;
        if (world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(DefenseContent.ARCHER_POST_POI), post, 0,
                PointOfInterestStorage.OccupationStatus.HAS_SPACE).findAny().isEmpty()) return null; // someone already holds the post
        BlockPos stand = standBy(world, post);
        if (stand == null) return null;
        VillagerEntity archer = EntityType.VILLAGER.create(world);
        if (archer == null) return null;
        archer.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, world.random.nextFloat() * 360f, 0f);
        archer.initialize(world, world.getLocalDifficulty(stand), SpawnReason.MOB_SUMMONED, null);
        archer.setVillagerData(new VillagerData(VillagerType.forBiome(world.getBiome(post)), DefenseContent.ARCHER, 1));
        archer.setBreedingAge(0);
        // The brain was built for a jobless villager: its tasks would forget the post and take the profession away (vanilla
        // rebuilds the brain whenever a villager takes a job).
        archer.reinitializeBrain(world);
        world.spawnEntityAndPassengers(archer);
        world.getPointOfInterestStorage().getPosition(entry -> entry.matchesKey(DefenseContent.ARCHER_POST_POI), (entry, pos) -> pos.equals(post), post, 1);
        archer.getBrain().remember(MemoryModuleType.JOB_SITE, GlobalPos.create(world.getRegistryKey(), post));
        return archer;
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
        for (VillagerEntity archer : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isRemoved() && !v.isBaby() && DefenseContent.archer(v))) {
            BlockPos post = post(world, archer);
            boolean onShift = !night || ((archer.getUuid().getLeastSignificantBits() & 1) == (nightIndex & 1));
            boolean onDuty = post != null && (alarm != null || onShift);
            if (onDuty && archer.isSleeping()) archer.wakeUp();
            if (onDuty && !archer.isSleeping() && archer.squaredDistanceTo(Vec3d.ofCenter(post)) > 2.5 * 2.5 && !bridge.actions.busy(archer)
                    && MobActionController.registered(archer, MemoryModuleType.WALK_TARGET))
                archer.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(post, alarm != null ? 0.8F : 0.55F, 1), 40);
            if (alarm != null && !archer.isSleeping()) shoot(world, zone, alarm, archer, now);
        }
    }

    private static BlockPos post(ServerWorld world, VillagerEntity archer) {
        if (!MobActionController.registered(archer, MemoryModuleType.JOB_SITE)) return null;
        Optional<GlobalPos> site = archer.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE);
        return site.isPresent() && site.get().dimension() == world.getRegistryKey() ? site.get().pos() : null;
    }

    /** One arrow at the nearest monster or alarm player in range and sight, when the bow is ready and no neighbour stands in the way.
     * The arrow cannot be picked up: an archer's arrows never run out. */
    private void shoot(ServerWorld world, VillageDefenseController.Zone zone, VillageAlarm.Alarm alarm, VillagerEntity archer, long now) {
        Long last = lastShot.get(archer.getUuid());
        long cooldown = Math.max(5, bridge.config.archerShotTenths * 2L);
        if (last != null && now - last < cooldown) return;
        double range = bridge.config.archerRange;
        LivingEntity target = null; double nearest = range * range;
        for (MobEntity monster : world.getEntitiesByClass(MobEntity.class, archer.getBoundingBox().expand(range), defense::threat)) {
            double distance = archer.squaredDistanceTo(monster);
            if (distance < nearest && archer.canSee(monster)) { nearest = distance; target = monster; }
        }
        if (bridge.config.alarmAgainstPlayers)
            for (UUID id : alarm.targets.keySet()) {
                ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
                if (player == null || player.getWorld() != world || !player.isAlive() || player.isCreative() || player.isSpectator()) continue;
                double distance = archer.squaredDistanceTo(player);
                if (distance < nearest && archer.canSee(player)) { nearest = distance; target = player; }
            }
        if (target == null) return;
        archer.getLookControl().lookAt(target, 30f, 30f);
        if (MobActionController.registered(archer, MemoryModuleType.LOOK_TARGET)) archer.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(target, true), 40);
        Vec3d from = archer.getEyePos(), to = target.getPos().add(0, target.getHeight() * 0.6, 0);
        if (blocked(world, archer, target, from, to)) return;
        lastShot.put(archer.getUuid(), now);
        ArrowEntity arrow = new ArrowEntity(world, archer, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        arrow.setDamage(2.5);
        double dx = to.x - archer.getX(), dz = to.z - archer.getZ();
        double dy = to.y - arrow.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.setVelocity(dx, dy + flat * 0.12, dz, 1.9f, 6f);
        world.spawnEntity(arrow);
        archer.swingHand(Hand.MAIN_HAND);
        world.playSound(null, archer.getX(), archer.getY(), archer.getZ(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.NEUTRAL, 1f,
                1f / (world.random.nextFloat() * 0.4f + 0.8f));
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
        for (VillagerEntity archer : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby() && DefenseContent.archer(v))) {
            double distance = archer.getBlockPos().getSquaredDistance(at);
            if (distance < nearest) { nearest = distance; horn = archer; }
        }
        if (horn == null) return;
        world.playSound(null, horn.getX(), horn.getY(), horn.getZ(), SoundEvents.GOAT_HORN_SOUNDS.get(0).value(), SoundCategory.NEUTRAL, 6f, 1f);
    }

    /** An archer sees farther than other villagers: the alarm uses its range for it. */
    double sight(LivingEntity watcher, double usual) {
        return watcher instanceof VillagerEntity villager && DefenseContent.archer(villager) ? Math.max(usual, bridge.config.archerRange) : usual;
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

    void clearRuntime() { lastShot.clear(); emptySince.clear(); }
}
