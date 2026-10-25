package io.github.mobtalk.game;

import io.github.mobtalk.core.VillageAlarm;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerType;
import net.minecraft.world.Heightmap;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import java.util.*;

/** The village guard (design §2): a villager with the guard's profession, a sword, a shield and iron, whose job site is a guard stand.
 * Beside the plain guard the village keeps two ranks on the same stands (Dmitriy, 2026-10-25): the heavy warrior in diamond with an
 * axe (slower, tougher, a heavier blow) and the horseman in leather with a sword on a village horse, which it rides wherever the guard
 * walks; new stands get the rank the village has fewest of. What follows holds for all of them
 * at the bell or at the village's edge. The village puts up stands until it has {@code guardsFor(residents)} of them and a newcomer
 * takes each new or long-empty one. By day a guard holds its stand and now and then walks to the next; at night half of them walk
 * the round of the stands and the bell (the shifts swap every night), the rest sleep; in an alarm all of them go for the monsters and
 * for the players the alarm is against, at once and without a warning. A badly hurt guard falls back to its stand and heals; its
 * shield turns half the arrows. The fight is this class's own (a blow at arm's length once a second): villagers have no attack of
 * their own. Server thread only. */
final class VillageGuards {
    static final long PLAN_PERIOD = 600, PLAN_PHASE = 337, DUTY_PERIOD = 20;
    static final Identifier GUARD_HEALTH = Identifier.of("mobtalk", "guard_health");
    /** How close a guard must be to strike, how long between blows, how far it chases a foe beyond the village. */
    static final double REACH = 2.4, CHASE_BEYOND = 16;
    static final long BLOW_TICKS = 20, HEAVY_BLOW_TICKS = 30;
    static final Identifier HEAVY_SLOW = Identifier.of("mobtalk", "heavy_slow"), HEAVY_STEADY = Identifier.of("mobtalk", "heavy_steady");
    /** A horseman's horses carry this tag; one without a horse gets a new one at most this often. */
    static final String STEED_TAG = "mobtalk_steed";
    static final long STEED_TICKS = 1200;
    /** Below this share of health a guard falls back; above this it goes back to the fight. */
    static final float RETREAT = 0.3f, RETURN = 0.7f;
    /** How long a guard holds its stand before walking the round by day, and how long it stays at the next stand. */
    static final long DAY_ROUND_TICKS = 2400, ROUND_STOP_TICKS = 300;

    private final MobGameBridge bridge;
    private final VillageDefenseController defense;
    private final Map<UUID, Long> lastBlow = new HashMap<>();
    private final Map<UUID, Boolean> fallingBack = new HashMap<>();
    /** The stand each guard walks to on its round, and when it set out. */
    private final Map<UUID, BlockPos> roundTarget = new HashMap<>();
    private final Map<UUID, Long> roundSince = new HashMap<>();
    private final Map<BlockPos, Long> emptySince = new HashMap<>();
    private final Map<UUID, Long> lastSteed = new HashMap<>();
    private final Random random = new Random();

    VillageGuards(MobGameBridge bridge, VillageDefenseController defense) { this.bridge = bridge; this.defense = defense; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.villageDefense && bridge.config.villageGuards && DefenseContent.GUARD != null; }

    /** How many guards a village of this many grown residents keeps. */
    static int guardsFor(int residents, int perGuard, int min, int max) {
        if (residents <= 0 || max <= 0) return 0;
        return Math.max(Math.min(min, max), Math.min(max, residents / Math.max(1, perGuard)));
    }

    void tick(ServerWorld world, List<VillageDefenseController.Zone> zones) {
        if (!enabled()) return;
        long now = world.getTime();
        if (now % PLAN_PERIOD == PLAN_PHASE) for (VillageDefenseController.Zone zone : zones) { stands(world, zone); posts(world, zone, now); }
        if (now % DUTY_PERIOD == 5) for (VillageDefenseController.Zone zone : zones) duty(world, zone, now);
    }

    // ---- Stands ----

    private List<BlockPos> stands(ServerWorld world, VillageDefenseController.Zone zone, PointOfInterestStorage.OccupationStatus status) {
        int radius = (int) Math.ceil(Math.max(zone.box().getLengthX(), zone.box().getLengthZ()) / 2) + 1;
        List<BlockPos> out = new ArrayList<>();
        world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(DefenseContent.GUARD_POST_POI), zone.center(), radius, status)
                .map(PointOfInterest::getPos).filter(zone::holds).forEach(out::add);
        return out;
    }

    /** A village short of stands puts one up: first by its bell, then at the middle of each side of its edge. */
    private void stands(ServerWorld world, VillageDefenseController.Zone zone) {
        int wanted = guardsFor(zone.residents(), bridge.config.watchResidents, bridge.config.watchMin, bridge.config.watchMax);
        List<BlockPos> existing = stands(world, zone, PointOfInterestStorage.OccupationStatus.ANY);
        if (existing.size() >= wanted) return;
        VillageAlarm.Alarm alarm = defense.alarm(zone);
        if (alarm != null && alarm.attack()) return;
        List<BlockPos> spots = new ArrayList<>();
        BlockPos center = zone.bell() != null ? zone.bell() : zone.center();
        for (int r = 3; r <= 6; r++) for (Direction side : Direction.Type.HORIZONTAL) spots.add(center.offset(side, r));
        int midX = (zone.minX() + zone.maxX()) / 2, midZ = (zone.minZ() + zone.maxZ()) / 2;
        spots.add(new BlockPos(midX, 0, zone.minZ() + 2)); spots.add(new BlockPos(midX, 0, zone.maxZ() - 2));
        spots.add(new BlockPos(zone.minX() + 2, 0, midZ)); spots.add(new BlockPos(zone.maxX() - 2, 0, midZ));
        for (BlockPos spot : spots) {
            if (!world.isChunkLoaded(spot)) continue;
            BlockPos at = new BlockPos(spot.getX(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ()), spot.getZ());
            boolean taken = false;
            for (BlockPos stand : existing) if (stand.getSquaredDistance(at) < 8 * 8) { taken = true; break; }
            if (taken || !free(world, at)) continue;
            world.setBlockState(at, DefenseContent.GUARD_POST.getDefaultState(), Block.NOTIFY_ALL);
            world.playSound(null, at, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1f, 1f);
            VillagerEntity guard = newcomer(world, zone, at);
            arrived(world, zone, guard);
            return; // one stand per round: the village grows its guard step by step
        }
    }

    /** A stand may go here: dry natural or village ground under it (not a road or a field), room for it and for a guard beside it. */
    private static boolean free(ServerWorld world, BlockPos at) {
        BlockState ground = world.getBlockState(at.down()), here = world.getBlockState(at);
        if (!world.getFluidState(at).isEmpty() || !world.getFluidState(at.down()).isEmpty()) return false;
        if (ground.isOf(Blocks.DIRT_PATH) || ground.isOf(Blocks.FARMLAND) || !ground.isSolidBlock(world, at.down())) return false;
        if (!here.isAir() && !here.isReplaceable()) return false;
        if (!world.getBlockState(at.up()).isAir() && !world.getBlockState(at.up()).isReplaceable()) return false;
        if (here.isIn(BlockTags.FLOWERS) || ground.isIn(BlockTags.LEAVES)) return false;
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos feet = at.offset(side);
            if (world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) return true;
        }
        return false;
    }

    /** Stands left empty long enough (the guard died or left) get a newcomer. */
    private void posts(ServerWorld world, VillageDefenseController.Zone zone, long now) {
        Set<BlockPos> empty = new HashSet<>(stands(world, zone, PointOfInterestStorage.OccupationStatus.HAS_SPACE));
        for (BlockPos stand : stands(world, zone, PointOfInterestStorage.OccupationStatus.ANY)) {
            if (!empty.contains(stand)) { emptySince.remove(stand); continue; }
            Long since = emptySince.putIfAbsent(stand, now);
            if (since == null || now - since < bridge.config.watchReplaceMinutes * 1200L) continue;
            emptySince.remove(stand);
            VillagerEntity guard = newcomer(world, zone, stand);
            if (guard != null) arrived(world, zone, guard);
        }
    }

    private void arrived(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity guard) {
        if (guard == null) return;
        announce(world, zone, Text.translatable("mobtalk.defense.soldier.arrived", zone.title(), Text.translatable("mobtalk.defense.rank." + rankName(guard)), name(guard)));
    }
    private static String rankName(VillagerEntity v) { return DefenseContent.heavy(v) ? "heavy" : DefenseContent.cavalry(v) ? "cavalry" : "guard"; }

    /** The rank a new stand gets: the one the village has fewest of, the plain guard first, then the heavy warrior, then the horseman
     * (each while its switch is on). Two stands make a guard and a heavy warrior, three add a horseman. */
    private net.minecraft.village.VillagerProfession rank(ServerWorld world, VillageDefenseController.Zone zone) {
        List<net.minecraft.village.VillagerProfession> order = new ArrayList<>(List.of(DefenseContent.GUARD));
        if (bridge.config.villageHeavy && DefenseContent.HEAVY != null) order.add(DefenseContent.HEAVY);
        if (bridge.config.villageCavalry && DefenseContent.CAVALRY != null) order.add(DefenseContent.CAVALRY);
        Map<net.minecraft.village.VillagerProfession, Integer> count = new HashMap<>();
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && DefenseContent.soldier(v)))
            count.merge(v.getVillagerData().getProfession(), 1, Integer::sum);
        net.minecraft.village.VillagerProfession best = order.getFirst();
        for (net.minecraft.village.VillagerProfession rank : order) if (count.getOrDefault(rank, 0) < count.getOrDefault(best, 0)) best = rank;
        return best;
    }

    /** A grown villager of the local kind with the rank's profession and gear (a horseman on its horse), standing by the stand, the
     * stand its job site. */
    private VillagerEntity newcomer(ServerWorld world, VillageDefenseController.Zone zone, BlockPos stand) {
        if (!world.getBlockState(stand).isOf(DefenseContent.GUARD_POST)) return null;
        if (world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(DefenseContent.GUARD_POST_POI), stand, 0,
                PointOfInterestStorage.OccupationStatus.HAS_SPACE).findAny().isEmpty()) return null;
        BlockPos feet = null;
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos p = stand.offset(side);
            if (world.getBlockState(p).getCollisionShape(world, p).isEmpty() && world.getBlockState(p.up()).getCollisionShape(world, p.up()).isEmpty()) { feet = p; break; }
        }
        if (feet == null) return null;
        VillagerEntity guard = EntityType.VILLAGER.create(world);
        if (guard == null) return null;
        guard.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, world.random.nextFloat() * 360f, 0f);
        guard.initialize(world, world.getLocalDifficulty(feet), SpawnReason.MOB_SUMMONED, null);
        guard.setVillagerData(new VillagerData(VillagerType.forBiome(world.getBiome(stand)), rank(world, zone), 1));
        guard.setBreedingAge(0);
        world.spawnEntityAndPassengers(guard);
        world.getPointOfInterestStorage().getPosition(entry -> entry.matchesKey(DefenseContent.GUARD_POST_POI), (entry, pos) -> pos.equals(stand), stand, 1);
        guard.reinitializeBrain(world); // a profession set by hand needs the brain vanilla builds for it, or the villager drops it
        guard.getBrain().remember(MemoryModuleType.JOB_SITE, GlobalPos.create(world.getRegistryKey(), stand));
        gear(guard);
        if (DefenseContent.cavalry(guard)) { net.minecraft.entity.passive.HorseEntity horse = steed(world, guard); if (horse != null) guard.startRiding(horse, true); }
        return guard;
    }

    /** The rank's arms and armour (never dropped: the village's, not loot) and health. The guard: sword, shield, iron helmet and
     * chestplate. The heavy warrior: a diamond axe and all diamond, slower and hard to push. The horseman: a sword and all leather. */
    void gear(VillagerEntity guard) {
        boolean heavy = DefenseContent.heavy(guard), cavalry = DefenseContent.cavalry(guard);
        equip(guard, EquipmentSlot.MAINHAND, heavy ? Items.DIAMOND_AXE : Items.IRON_SWORD);
        if (!heavy && !cavalry) equip(guard, EquipmentSlot.OFFHAND, Items.SHIELD);
        equip(guard, EquipmentSlot.HEAD, heavy ? Items.DIAMOND_HELMET : cavalry ? Items.LEATHER_HELMET : Items.IRON_HELMET);
        equip(guard, EquipmentSlot.CHEST, heavy ? Items.DIAMOND_CHESTPLATE : cavalry ? Items.LEATHER_CHESTPLATE : Items.IRON_CHESTPLATE);
        if (heavy || cavalry) {
            equip(guard, EquipmentSlot.LEGS, heavy ? Items.DIAMOND_LEGGINGS : Items.LEATHER_LEGGINGS);
            equip(guard, EquipmentSlot.FEET, heavy ? Items.DIAMOND_BOOTS : Items.LEATHER_BOOTS);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) guard.setEquipmentDropChance(slot, 0f);
        if (heavy) {
            modifier(guard, EntityAttributes.GENERIC_MOVEMENT_SPEED, HEAVY_SLOW, -0.2, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            modifier(guard, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, HEAVY_STEADY, 0.6, EntityAttributeModifier.Operation.ADD_VALUE);
        }
        EntityAttributeInstance health = guard.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health == null) return;
        double extra = Math.max(0, (heavy ? bridge.config.heavyHealth : bridge.config.watchHealth) - health.getBaseValue());
        EntityAttributeModifier existing = health.getModifier(GUARD_HEALTH);
        if (existing != null && existing.value() == extra) return;
        boolean fresh = existing == null;
        health.removeModifier(GUARD_HEALTH);
        if (extra > 0) health.addPersistentModifier(new EntityAttributeModifier(GUARD_HEALTH, extra, EntityAttributeModifier.Operation.ADD_VALUE));
        if (fresh) guard.setHealth(guard.getMaxHealth());
        else if (guard.getHealth() > guard.getMaxHealth()) guard.setHealth(guard.getMaxHealth());
    }

    private static void equip(VillagerEntity v, EquipmentSlot slot, net.minecraft.item.Item item) {
        if (v.getEquippedStack(slot).isEmpty()) v.equipStack(slot, new ItemStack(item));
    }
    private static void modifier(VillagerEntity v, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
                                 Identifier id, double value, EntityAttributeModifier.Operation operation) {
        EntityAttributeInstance instance = v.getAttributeInstance(attribute);
        if (instance != null && instance.getModifier(id) == null) instance.addPersistentModifier(new EntityAttributeModifier(id, value, operation));
    }

    // ---- The horseman's horse ----

    /** A village horse: tame, saddled, in leather barding, kept (it never despawns), tagged as the guard's. */
    private static net.minecraft.entity.passive.HorseEntity steed(ServerWorld world, VillagerEntity rider) {
        net.minecraft.entity.passive.HorseEntity horse = EntityType.HORSE.create(world);
        if (horse == null) return null;
        horse.refreshPositionAndAngles(rider.getX(), rider.getY(), rider.getZ(), rider.getYaw(), 0f);
        horse.initialize(world, world.getLocalDifficulty(rider.getBlockPos()), SpawnReason.MOB_SUMMONED, null);
        horse.setTame(true);
        horse.saddle(new ItemStack(Items.SADDLE), null);
        horse.equipBodyArmor(new ItemStack(Items.LEATHER_HORSE_ARMOR));
        horse.setPersistent();
        horse.addCommandTag(STEED_TAG);
        world.spawnEntity(horse);
        return horse;
    }

    /** A horseman out of the saddle: back on a free village horse near it, or a new horse now and then. */
    private void mount(ServerWorld world, VillagerEntity rider, long now) {
        if (rider.getVehicle() != null) return;
        net.minecraft.entity.passive.HorseEntity free = null; double nearest = 24 * 24;
        for (net.minecraft.entity.passive.HorseEntity horse : world.getEntitiesByClass(net.minecraft.entity.passive.HorseEntity.class,
                rider.getBoundingBox().expand(24), h -> h.isAlive() && !h.hasPassengers() && h.getCommandTags().contains(STEED_TAG))) {
            double distance = rider.squaredDistanceTo(horse);
            if (distance < nearest) { nearest = distance; free = horse; }
        }
        if (free == null) {
            Long last = lastSteed.get(rider.getUuid());
            if (last != null && now - last < STEED_TICKS) return;
            lastSteed.put(rider.getUuid(), now);
            free = steed(world, rider);
            if (free == null) return;
        }
        if (rider.squaredDistanceTo(free) <= 3 * 3) rider.startRiding(free, true);
        else walk(rider, free.getBlockPos(), 0.6f);
    }

    // ---- Duty and fight ----

    private void duty(ServerWorld world, VillageDefenseController.Zone zone, long now) {
        VillageAlarm.Alarm alarm = defense.alarm(zone);
        boolean night = !world.isDay();
        long nightIndex = world.getTimeOfDay() / 24000L;
        List<BlockPos> stands = null;
        for (VillagerEntity guard : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isRemoved() && !v.isBaby() && DefenseContent.soldier(v))) {
            gear(guard);
            BlockPos stand = jobSite(world, guard);
            if (bridge.actions.busy(guard)) continue;
            boolean onShift = !night || ((guard.getUuid().getMostSignificantBits() & 1) == (nightIndex & 1));
            if (alarm != null || onShift) { if (guard.isSleeping()) guard.wakeUp(); VillageDefenseController.stayOut(world, guard); }
            else { roundTarget.remove(guard.getUuid()); continue; } // off shift at night: vanilla puts it to bed
            float share = guard.getHealth() / guard.getMaxHealth();
            boolean back = fallingBack.getOrDefault(guard.getUuid(), false);
            if (!back && share < RETREAT) back = true; else if (back && share >= RETURN) back = false;
            fallingBack.put(guard.getUuid(), back);
            if (back) {
                if (now % 100 == 5 && guard.getHealth() < guard.getMaxHealth()) guard.heal(1);
                walk(guard, stand != null ? stand : zone.center(), 0.75f);
                continue;
            }
            if (DefenseContent.cavalry(guard)) mount(world, guard, now);
            if (fight(world, zone, alarm, guard, now)) continue; // monsters in the village need no alarm
            if (guard.getHealth() < guard.getMaxHealth() && now % 100 == 5) guard.heal(1);
            if (stands == null) stands = stands(world, zone, PointOfInterestStorage.OccupationStatus.ANY);
            round(world, zone, guard, stand, stands, night, now);
        }
    }

    /** The nearest monster or alarm player in the village (anywhere among its houses, or within sight of the guard a little beyond
     * them): walk up to it, strike at arm's length. */
    private boolean fight(ServerWorld world, VillageDefenseController.Zone zone, VillageAlarm.Alarm alarm, VillagerEntity guard, long now) {
        double alert = bridge.config.watchSight;
        LivingEntity foe = null; double nearest = Double.MAX_VALUE;
        var reach = zone.box().expand(CHASE_BEYOND);
        for (MobEntity monster : world.getEntitiesByClass(MobEntity.class, reach, defense::threat)) {
            if (!zone.box().contains(monster.getPos()) && guard.squaredDistanceTo(monster) > alert * alert) continue;
            double distance = guard.squaredDistanceTo(monster);
            if (distance < nearest) { nearest = distance; foe = monster; }
        }
        if (alarm != null && bridge.config.alarmAgainstPlayers)
            for (UUID id : alarm.targets.keySet()) {
                ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(id);
                if (player == null || player.getWorld() != world || !player.isAlive() || player.isCreative() || player.isSpectator()) continue;
                if (!reach.contains(player.getPos())) continue;
                double distance = guard.squaredDistanceTo(player);
                if (distance < nearest) { nearest = distance; foe = player; }
            }
        if (foe == null) return false;
        if (MobActionController.registered(guard, MemoryModuleType.LOOK_TARGET)) guard.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(foe, true), 40);
        guard.getLookControl().lookAt(foe, 30f, 30f);
        boolean heavy = DefenseContent.heavy(guard);
        double reachOf = guard.getVehicle() != null ? REACH + 1 : REACH; // a sword from the saddle reaches farther
        if (nearest > reachOf * reachOf) {
            if (guard.getVehicle() instanceof MobEntity horse) horse.getNavigation().startMovingTo(foe, 1.6);
            else if (MobActionController.registered(guard, MemoryModuleType.WALK_TARGET))
                guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityLookTarget(foe, false), 0.9F, 1), 40);
            return true;
        }
        Long last = lastBlow.get(guard.getUuid());
        if (last != null && now - last < (heavy ? HEAVY_BLOW_TICKS : BLOW_TICKS)) return true;
        lastBlow.put(guard.getUuid(), now);
        guard.swingHand(Hand.MAIN_HAND);
        DamageSource source = world.getDamageSources().mobAttack(guard);
        if (foe.damage(source, (float) (heavy ? bridge.config.heavyDamage : bridge.config.watchDamage))) {
            double dx = foe.getX() - guard.getX(), dz = foe.getZ() - guard.getZ();
            foe.takeKnockback(heavy ? 0.8 : guard.getVehicle() != null ? 0.6 : 0.4, -dx, -dz);
            world.playSound(null, foe.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.NEUTRAL, 0.8f, 1f);
        }
        return true;
    }

    /** By day: hold the stand, and after a while walk to another stand (or the bell) and back; on a night shift: walk the round of
     * the stands and the bell, a short stop at each. */
    private void round(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity guard, BlockPos stand, List<BlockPos> stands, boolean night, long now) {
        UUID id = guard.getUuid();
        BlockPos home = stand != null ? stand : zone.bell() != null ? zone.bell() : zone.center();
        List<BlockPos> round = new ArrayList<>(stands);
        if (zone.bell() != null) round.add(zone.bell());
        round.remove(home);
        BlockPos target = roundTarget.get(id);
        Long since = roundSince.get(id);
        long hold = night ? ROUND_STOP_TICKS : target == null || target.equals(home) ? DAY_ROUND_TICKS : ROUND_STOP_TICKS;
        if (target == null || since == null || now - since >= hold) {
            if (round.isEmpty()) target = home;
            else if (night) { round.add(home); target = next(round, target, zone.center(), guard); } // its own stand is on the round too
            else target = target == null || !target.equals(home) ? home : round.get(random.nextInt(round.size()));
            roundTarget.put(id, target); roundSince.put(id, now);
        }
        if (guard.squaredDistanceTo(Vec3d.ofCenter(target)) > 2.5 * 2.5) walk(guard, target, night ? 0.5f : 0.45f);
    }

    /** The night round goes round the village: the point after {@code from} in order of their bearing from the centre, passing over
     * one the guard already stands at (with two stands by the bell a random pick often meant standing still). */
    private static BlockPos next(List<BlockPos> round, BlockPos from, BlockPos centre, VillagerEntity guard) {
        List<BlockPos> ring = new ArrayList<>(round);
        ring.sort(Comparator.comparingDouble(p -> Math.atan2(p.getZ() - centre.getZ(), p.getX() - centre.getX())));
        int at = from == null ? -1 : ring.indexOf(from);
        for (int i = 1; i <= ring.size(); i++) {
            BlockPos candidate = ring.get(Math.floorMod(at + i, ring.size()));
            if (guard.squaredDistanceTo(Vec3d.ofCenter(candidate)) > 4 * 4) return candidate;
        }
        return ring.get(Math.floorMod(at + 1, ring.size()));
    }

    /** Walks there; a horseman in the saddle rides there (the horse goes where it is led). */
    private static void walk(VillagerEntity guard, BlockPos to, float speed) {
        if (to == null) return;
        if (guard.getVehicle() instanceof MobEntity horse) { horse.getNavigation().startMovingTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed * 2.4); return; }
        if (!MobActionController.registered(guard, MemoryModuleType.WALK_TARGET)) return;
        guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(to, speed, 1), 40);
    }

    private static BlockPos jobSite(ServerWorld world, VillagerEntity guard) {
        if (!MobActionController.registered(guard, MemoryModuleType.JOB_SITE)) return null;
        Optional<GlobalPos> site = guard.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE);
        return site.isPresent() && site.get().dimension() == world.getRegistryKey() ? site.get().pos() : null;
    }

    /** A guard does not panic at monsters or blows, as other villagers do (vanilla's flight would undo its walk to the fight
     * every tick); a badly hurt one falling back may. */
    boolean standsGround(VillagerEntity villager) { return enabled() && DefenseContent.soldier(villager) && !fallingBack.getOrDefault(villager.getUuid(), false); }

    /** From the damage event: a guard's shield turns half of the arrows and other projectiles that come at it. */
    boolean shieldBlocks(LivingEntity victim, DamageSource source) {
        if (!enabled() || !(victim instanceof VillagerEntity villager) || !DefenseContent.guard(villager)) return false;
        if (!source.isIn(DamageTypeTags.IS_PROJECTILE) || villager.getOffHandStack().isEmpty() || random.nextFloat() >= 0.5f) return false;
        villager.getWorld().playSound(null, villager.getBlockPos(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.NEUTRAL, 1f, 1f);
        return true;
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

    void clearRuntime() { lastBlow.clear(); fallingBack.clear(); roundTarget.clear(); roundSince.clear(); emptySince.clear(); lastSteed.clear(); }
}
