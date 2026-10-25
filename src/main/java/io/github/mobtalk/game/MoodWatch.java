package io.github.mobtalk.game;

import io.github.mobtalk.core.MoodFactor;
import io.github.mobtalk.core.MoodSample;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The world side of the moods: every ten seconds each known villager near a player is looked at — the sky over it, the night,
 * monsters and a golem around, the village alarm or a raid, its wounds, its bed and work, its sleep and its larder, who stands
 * next to it — and the store turns that into lasting factors and long moods ({@link io.github.mobtalk.core.MobState}). Only
 * villagers someone already knows: a stranger has no mood worth keeping. Server thread only. */
final class MoodWatch {
    private static final long PERIOD = 200, PHASE = 13;
    /** How far from a player villagers are looked at, how close a monster frightens, a golem reassures, a friend is "near". */
    private static final double RANGE = 48, MONSTERS = 12, GOLEM = 16, NEAR = 8;
    /** Villagers share food (a farmer throws bread to a neighbour), so a larder counts as full when one neighbour this close has some. */
    private static final double LARDER = 24;
    private final MobGameBridge bridge;
    MoodWatch(MobGameBridge bridge) { this.bridge = bridge; }

    void tick(ServerWorld world) {
        if (!bridge.config.enabled || world.getTime() % PERIOD != PHASE || world.getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        long time = world.getServer().getOverworld().getTimeOfDay();
        long night = MoodSample.nightOf(time);
        // By the clock, not the light: a thunderstorm darkens the sky at noon, and that is not a night out.
        long hour = Math.floorMod(time, 24_000L);
        boolean dark = hour >= 13_000L && hour < 23_000L;
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(RANGE), VillagerEntity::isAlive)) {
                if (!seen.add(villager.getUuid()) || !bridge.memory.known(villager.getUuid())) continue;
                MoodSample sample = sample(world, villager, night, dark);
                bridge.memory.surroundings(villager.getUuid(), player.getUuid(), sample, now);
            }
        }
    }

    private MoodSample sample(ServerWorld world, VillagerEntity villager, long night, boolean dark) {
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
            if (!MobActionController.registered(villager, MemoryModuleType.HOME) || villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME).isEmpty())
                factors.add(MoodFactor.HOMELESS);
            if (villager.getVillagerData().getProfession() == VillagerProfession.NONE) factors.add(MoodFactor.JOBLESS);
        }
        Set<UUID> near = new HashSet<>();
        for (var other : world.getOtherEntities(villager, villager.getBoundingBox().expand(NEAR), e -> e.isAlive())) near.add(other.getUuid());
        boolean fed = hasFood(villager.getInventory());
        if (!fed) for (VillagerEntity other : world.getEntitiesByClass(VillagerEntity.class, villager.getBoundingBox().expand(LARDER), v -> v != villager && v.isAlive()))
            if (hasFood(other.getInventory())) { fed = true; break; }
        return new MoodSample(factors, near, true, sleeping, fed, night, dark);
    }

    private static boolean hasFood(SimpleInventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && MoodSample.food(Registries.ITEM.getId(stack.getItem()).toString())) return true;
        }
        return false;
    }
}
