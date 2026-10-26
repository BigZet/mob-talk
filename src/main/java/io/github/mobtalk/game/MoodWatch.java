package io.github.mobtalk.game;

import io.github.mobtalk.core.Hunger;
import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.MoodFactor;
import io.github.mobtalk.core.MoodSample;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Items;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import java.util.*;

/** The world side of the moods: every ten seconds each known villager near a player is looked at — the sky over it, the night,
 * monsters and a golem around, the village alarm or a raid, its wounds, its bed and work, its sleep, who stands next to it — and
 * the store turns that into lasting factors and long moods ({@link io.github.mobtalk.core.MobState}). Hunger is kept here too: a
 * peckish villager eats from its own pockets (bakes bread from three wheat first), a neighbour shares, or it takes a meal from a chest
 * or barrel near it ({@link Hunger}), and a village whose residents carry less than
 * a meal each has empty larders. A villager in apathy lies in its own bed day and night ({@link #afterBrain}). Only villagers someone
 * already knows: a stranger has no mood worth keeping. Server thread only. */
final class MoodWatch {
    private static final long PERIOD = 200, PHASE = 13;
    /** How far from a player villagers are looked at, how close a monster frightens, a golem reassures, a friend is "near". */
    private static final double RANGE = 48, MONSTERS = 12, GOLEM = 16, NEAR = 8;
    /** How far a hungry villager goes to ask a neighbour for food. */
    private static final double SHARE = 16;
    /** How far a hungry villager reaches for food in a chest or barrel; three wheat bake one bread. */
    private static final int PANTRY = 10, WHEAT_PER_BREAD = 3;
    private final MobGameBridge bridge;
    /** Villagers in apathy, kept in bed; refreshed every watch. */
    private final Set<UUID> bedridden = new HashSet<>();
    MoodWatch(MobGameBridge bridge) { this.bridge = bridge; }

    void tick(ServerWorld world) {
        if (!bridge.config.enabled || world.getTime() % PERIOD != PHASE || world.getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        long time = world.getServer().getOverworld().getTimeOfDay();
        long night = MoodSample.nightOf(time);
        // By the clock, not the light: a thunderstorm darkens the sky at noon, and that is not a night out.
        long hour = Math.floorMod(time, 24_000L);
        boolean dark = hour >= 13_000L && hour < 23_000L;
        Map<UUID, VillagerEntity> seen = new LinkedHashMap<>();
        for (ServerPlayerEntity player : world.getPlayers())
            for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(RANGE), VillagerEntity::isAlive))
                if (!seen.containsKey(villager.getUuid()) && bridge.memory.known(villager.getUuid())) seen.put(villager.getUuid(), villager);
        Map<UUID, UUID> watcher = new HashMap<>();
        for (ServerPlayerEntity player : world.getPlayers())
            for (UUID id : seen.keySet()) if (!watcher.containsKey(id) && seen.get(id).squaredDistanceTo(player) <= RANGE * RANGE) watcher.put(id, player.getUuid());
        boolean hunger = bridge.config.moodHungerMinutes > 0;
        List<Pantry> pantries = hunger ? pantries(world, seen.values()) : List.of();
        if (hunger) for (VillagerEntity villager : seen.values()) eat(world, villager, pantries, now);
        Map<String, int[]> larders = larders(seen.values(), pantries, now);
        bedridden.clear();
        for (VillagerEntity villager : seen.values()) {
            String village = bridge.memory.villageOf(villager.getUuid());
            int[] larder = larders.get(village);
            boolean stocked = !hunger || larder == null || larder[0] >= larder[1];
            bridge.memory.surroundings(villager.getUuid(), watcher.get(villager.getUuid()), sample(world, villager, night, dark, stocked), now);
            MobState.View view = bridge.memory.stateView(villager.getUuid(), null);
            if (bridge.config.moodApathyBed > 0 && view != null && view.spell() != null && view.spell().bedridden() && !villager.isBaby()) bedridden.add(villager.getUuid());
        }
    }

    /** A peckish villager eats: the best food in its own pockets, else a neighbour's (one in apathy does not go asking). */
    private void eat(ServerWorld world, VillagerEntity villager, List<Pantry> pantries, long now) {
        if (bridge.memory.hunger(villager.getUuid(), now) == Hunger.Level.FED) return;
        bake(villager.getInventory());
        Hunger.Meal own = take(villager.getInventory(), false);
        if (own != null) { take(villager.getInventory(), true); bridge.memory.meal(villager.getUuid(), own, "", null, "", now); return; }
        MobState.View view = bridge.memory.stateView(villager.getUuid(), null);
        if (view != null && view.spell() != null && view.spell().bedridden()) return;
        for (VillagerEntity other : world.getEntitiesByClass(VillagerEntity.class, villager.getBoundingBox().expand(SHARE), v -> v != villager && v.isAlive())) {
            bake(other.getInventory());
            Hunger.Meal shared = take(other.getInventory(), false);
            if (shared == null) continue;
            take(other.getInventory(), true);
            String name = bridge.memory.displayName(other.getUuid());
            bridge.memory.meal(villager.getUuid(), shared, "поделился " + (name.isBlank() ? "сосед" : name), null, "", now);
            return;
        }
        for (Pantry pantry : pantries) {
            if (!pantry.pos().isWithinDistance(villager.getBlockPos(), PANTRY)) continue;
            Hunger.Meal stored = take(pantry.inventory(), false);
            if (stored == null) continue;
            take(pantry.inventory(), true);
            bridge.memory.meal(villager.getUuid(), stored, "", null, "", now);
            return;
        }
    }

    /** A chest or barrel in a loaded chunk around the villagers; a hungry one takes a meal from it. */
    private record Pantry(BlockPos pos, Inventory inventory) { }

    /** Chests and barrels in the loaded chunks around these villagers (block entities only: no block scan). */
    private static List<Pantry> pantries(ServerWorld world, Collection<VillagerEntity> villagers) {
        Set<Long> chunks = new HashSet<>();
        for (VillagerEntity villager : villagers) {
            ChunkPos at = villager.getChunkPos();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) chunks.add(ChunkPos.toLong(at.x + dx, at.z + dz));
        }
        List<Pantry> out = new ArrayList<>();
        for (long key : chunks) {
            int x = ChunkPos.getPackedX(key), z = ChunkPos.getPackedZ(key);
            if (!world.isChunkLoaded(x, z)) continue;
            WorldChunk chunk = world.getChunk(x, z);
            for (BlockEntity entity : chunk.getBlockEntities().values())
                if (entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity) out.add(new Pantry(entity.getPos(), (Inventory) entity));
        }
        return out;
    }

    /** No ready food but wheat in the pockets: three wheat become one bread (what a farmer does with the harvest). */
    private static void bake(Inventory inventory) {
        if (take(inventory, false) != null) return;
        int wheat = 0;
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(Items.WHEAT)) wheat += inventory.getStack(i).getCount();
        if (wheat < WHEAT_PER_BREAD) return;
        int slot = -1;
        for (int i = 0; i < inventory.size() && slot < 0; i++) if (inventory.getStack(i).isEmpty()) slot = i;
        int left = WHEAT_PER_BREAD;
        for (int i = 0; i < inventory.size() && left > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isOf(Items.WHEAT)) continue;
            int used = Math.min(left, stack.getCount());
            stack.decrement(used); left -= used;
            if (slot < 0 && stack.isEmpty()) slot = i;
        }
        if (slot < 0) return; // the pockets are full: the wheat is spent but no bread fits (never happens with 3 wheat in one stack)
        inventory.setStack(slot, new ItemStack(Items.BREAD));
        inventory.markDirty();
    }

    /** The best meal in these pockets (a treat, then a hearty one, then a meagre one); {@code eat} takes one of it. */
    private static Hunger.Meal take(Inventory inventory, boolean eat) {
        int best = -1; Hunger.Meal meal = null;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            Hunger.Meal m = stack.isEmpty() ? null : Hunger.meal(Registries.ITEM.getId(stack.getItem()).toString());
            if (m != null && (meal == null || m.ordinal() < meal.ordinal())) { meal = m; best = i; }
        }
        if (eat && best >= 0) { inventory.getStack(best).decrement(1); inventory.markDirty(); }
        return meal;
    }

    /** Meals in the residents' pockets and the residents seen, by village; told to the store for the admin. */
    private Map<String, int[]> larders(Collection<VillagerEntity> villagers, List<Pantry> pantries, long now) {
        Map<String, int[]> out = new HashMap<>();
        for (VillagerEntity villager : villagers) {
            String village = bridge.memory.villageOf(villager.getUuid());
            if (village.isBlank()) continue;
            int[] larder = out.computeIfAbsent(village, k -> new int[3]);
            SimpleInventory inventory = villager.getInventory();
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (!stack.isEmpty() && Hunger.meal(Registries.ITEM.getId(stack.getItem()).toString()) != null) larder[0] += stack.getCount();
                else if (stack.isOf(Items.WHEAT)) larder[0] += stack.getCount() / WHEAT_PER_BREAD;
            }
            for (Pantry pantry : pantries)
                if (pantry.pos().isWithinDistance(villager.getBlockPos(), PANTRY))
                    for (int i = 0; i < pantry.inventory().size(); i++) {
                        ItemStack stack = pantry.inventory().getStack(i);
                        if (!stack.isEmpty() && Hunger.meal(Registries.ITEM.getId(stack.getItem()).toString()) != null) larder[0] += stack.getCount();
                    }
            larder[1]++;
            if (bridge.memory.hunger(villager.getUuid(), now).atLeast(Hunger.Level.HUNGRY)) larder[2]++;
        }
        out.forEach((village, larder) -> bridge.memory.larder(village, larder[0], larder[1], larder[2], now));
        return out;
    }

    private MoodSample sample(ServerWorld world, VillagerEntity villager, long night, boolean dark, boolean larder) {
        BlockPos pos = villager.getBlockPos();
        EnumSet<MoodFactor> factors = EnumSet.noneOf(MoodFactor.class);
        boolean sky = world.isSkyVisible(pos.up());
        boolean sleeping = villager.isSleeping();
        if (world.isThundering()) factors.add(MoodFactor.STORM);
        else if (world.isRaining() && world.hasRain(pos.up())) factors.add(MoodFactor.RAIN);
        else if (!dark && !world.isRaining() && sky) factors.add(MoodFactor.SUNNY);
        if (dark && sky && !sleeping) factors.add(MoodFactor.NIGHT_OUT);
        if (!world.getEntitiesByClass(HostileEntity.class, villager.getBoundingBox().expand(MONSTERS), HostileEntity::isAlive).isEmpty()) factors.add(MoodFactor.MONSTERS);
        if (!world.getEntitiesByClass(IronGolemEntity.class, villager.getBoundingBox().expand(GOLEM), IronGolemEntity::isAlive).isEmpty()) factors.add(MoodFactor.GUARDED);
        if (world.hasRaidAt(pos)) factors.add(MoodFactor.RAID);
        else if (bridge.defense.alarmAt(world, pos) != null) factors.add(MoodFactor.ALARM);
        if (villager.getMaxHealth() > 0 && villager.getHealth() < villager.getMaxHealth() * 0.5f) factors.add(MoodFactor.WOUNDED);
        // A traveller from the roads has no bed or work here, and a child needs neither.
        if (!villager.isBaby() && !villager.getCommandTags().contains("mobtalk_road")) {
            if (home(villager) == null) factors.add(MoodFactor.HOMELESS);
            if (villager.getVillagerData().getProfession() == VillagerProfession.NONE) factors.add(MoodFactor.JOBLESS);
        }
        Set<UUID> near = new HashSet<>();
        for (var other : world.getOtherEntities(villager, villager.getBoundingBox().expand(NEAR), e -> e.isAlive())) near.add(other.getUuid());
        return new MoodSample(factors, near, true, sleeping, larder, night, dark);
    }

    /** Its own bed in this world, or null. */
    private static BlockPos home(VillagerEntity villager) {
        if (!MobActionController.registered(villager, MemoryModuleType.HOME)) return null;
        GlobalPos home = villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME).orElse(null);
        return home == null || !home.dimension().equals(villager.getWorld().getRegistryKey()) ? null : home.pos();
    }

    /** Whether this villager stays in bed now: in apathy, and no alarm or raid around (then it gets up like everyone). */
    boolean keepsInBed(VillagerEntity villager) {
        if (!bedridden.contains(villager.getUuid()) || !(villager.getWorld() instanceof ServerWorld world)) return false;
        return !world.hasRaidAt(villager.getBlockPos()) && bridge.defense.alarmAt(world, villager.getBlockPos()) == null;
    }

    /** After the brain: a villager in apathy walks slowly to its bed and lies down, and lies there, day or night. */
    boolean afterBrain(MobEntity mob) {
        if (!(mob instanceof VillagerEntity villager) || !keepsInBed(villager)) return false;
        if (villager.isSleeping()) { still(villager); return true; }
        BlockPos bed = home(villager);
        if (bed == null) return false; // No bed of its own: it drifts about as usual; its words still show the apathy.
        BlockState state = villager.getWorld().getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)) return false;
        if (villager.squaredDistanceTo(Vec3d.ofBottomCenter(bed)) > 4.0) {
            if (MobActionController.registered(villager, MemoryModuleType.WALK_TARGET))
                villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(bed, 0.45F, 1), 40);
            return true;
        }
        if (state.get(BedBlock.OCCUPIED)) return false;
        villager.getNavigation().stop();
        villager.sleep(bed);
        return true;
    }

    private static void still(VillagerEntity villager) {
        if (MobActionController.registered(villager, MemoryModuleType.WALK_TARGET)) villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (MobActionController.registered(villager, MemoryModuleType.PATH)) villager.getBrain().forget(MemoryModuleType.PATH);
        villager.getNavigation().stop();
    }

    void unload(MobEntity mob) { bedridden.remove(mob.getUuid()); }
}
