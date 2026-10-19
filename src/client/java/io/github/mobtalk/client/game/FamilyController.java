package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import java.util.*;

/** Families of villagers on the integrated server (docs/mca-social-spec.md §5): once a minute a village around the player may hold a
 * wedding; a death in a family reaches every relative with a profile, seen or not, and a mourner near the player says a word of it.
 * The tree itself lives in the world memory ({@link SocialLedger}, {@link FamilyTree}). Server thread only. */
final class FamilyController {
    /** Villagers this close to a player count as one village for weddings. */
    static final double VILLAGE_RANGE = 48;
    private static final long MINUTE = 1200, PHASE = 611;
    private final MobGameBridge bridge;
    private final Random random = new Random();
    FamilyController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer && bridge.config.familyLife; }

    void tick(ServerWorld world) {
        if (!enabled()) return;
        long time = world.getTime();
        if (time % 200 == 47) expecting(world);
        if (time % 600 == 131) growth(world);
        if (time % MINUTE != PHASE) return;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            if (random.nextInt(100) < bridge.config.weddingChance) wedding(world, player);
            births(world, player);
        }
    }

    /** The player's child: the day is set a game day after the agreement; when it comes and the spouse is loaded, the baby appears. */
    private void expecting(ServerWorld world) {
        for (SocialLedger.Expecting expecting : bridge.memory.social().expecting()) {
            if (expecting.dueTick() == 0) { bridge.memory.social().due(expecting.villager(), world.getTime() + ChildGrowth.BIRTH_DELAY_TICKS); continue; }
            if (world.getTime() < expecting.dueTick() || !(world.getEntity(expecting.villager()) instanceof VillagerEntity parent) || !parent.isAlive()) continue;
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(expecting.partner());
            String playerName = player == null ? "" : player.getName().getString();
            int count = random.nextDouble() < ChildGrowth.TWINS ? 2 : 1;
            for (int i = 0; i < count; i++) {
                VillagerEntity baby = spawnBaby(world, parent);
                if (baby != null) bridge.memory.social().born(baby.getUuid(), parent.getUuid(), expecting.partner(), playerName, world.getTime(), System.currentTimeMillis());
            }
            if (player != null) player.sendMessage(Text.translatable("mobtalk.family.birth", name(parent), playerName), false);
        }
    }
    /** Married couples of the village around the player have children now and then; nobody else does. */
    private void births(ServerWorld world, ServerPlayerEntity player) {
        for (VillagerEntity mother : village(world, player)) {
            if (mother.isBaby()) continue;
            SocialLedger.Household household = bridge.memory.social().household(mother.getUuid());
            if (household.spouse() == null || household.gender() != Gender.FEMALE || !household.fertile()) continue;
            if (!(world.getEntity(household.spouse()) instanceof VillagerEntity father) || !father.isAlive() || father.squaredDistanceTo(mother) > 32 * 32) continue;
            if (random.nextInt(100) >= bridge.config.birthChance || random.nextDouble() >= ChildGrowth.birthChance(household.children())) continue;
            VillagerEntity baby = spawnBaby(world, mother);
            if (baby == null) continue;
            bridge.memory.social().born(baby.getUuid(), mother.getUuid(), father.getUuid(), "", world.getTime(), System.currentTimeMillis());
            player.sendMessage(Text.translatable("mobtalk.family.birth", name(mother), name(father)), false);
            return; // One birth per village and minute is plenty.
        }
    }
    private VillagerEntity spawnBaby(ServerWorld world, VillagerEntity parent) {
        VillagerEntity baby = EntityType.VILLAGER.create(world);
        if (baby == null) return null;
        baby.refreshPositionAndAngles(parent.getX(), parent.getY(), parent.getZ(), parent.getYaw(), 0);
        baby.setVillagerData(baby.getVillagerData().withType(parent.getVillagerData().getType()));
        baby.setBreedingAge(-24_000);
        world.spawnEntityAndPassengers(baby);
        world.spawnParticles(ParticleTypes.HEART, parent.getX(), parent.getY() + 1.8, parent.getZ(), 8, 0.5, 0.3, 0.5, 0.02);
        return baby;
    }
    /** Children of couples stay small until their own age says they are grown; then vanilla lets them grow at once. */
    private void growth(ServerWorld world) {
        Map<UUID, VillagerEntity> loaded = new HashMap<>();
        for (ServerPlayerEntity player : world.getPlayers())
            for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(128), v -> v.isAlive() && !v.isRemoved()))
                loaded.put(villager.getUuid(), villager);
        if (loaded.isEmpty()) return;
        for (Map.Entry<UUID, ChildGrowth.Stage> entry : bridge.memory.social().grow(loaded.keySet(), world.getTime()).entrySet()) {
            VillagerEntity child = loaded.get(entry.getKey());
            if (entry.getValue() != ChildGrowth.Stage.ADULT) {
                if (!child.isBaby()) child.setBaby(true);
                else if (child.getBreedingAge() > -1_200) child.setBreedingAge(-12_000);
            } else if (child.isBaby()) {
                child.setBreedingAge(0);
                for (ServerPlayerEntity player : world.getPlayers())
                    if (player.squaredDistanceTo(child) <= 64 * 64) player.sendMessage(Text.translatable("mobtalk.family.grown", name(child)), false);
            }
        }
    }

    private List<VillagerEntity> village(ServerWorld world, ServerPlayerEntity player) {
        return world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(VILLAGE_RANGE), v -> v.isAlive() && !v.isRemoved() && !v.isAiDisabled());
    }
    private String name(MobEntity mob) { String given = bridge.memory.displayName(mob.getUuid()); return given.isBlank() ? mob.getName().getString() : given; }

    /** Marries the pair the rules pick among the villagers around the player, with hearts over both and a line in the chat. */
    private void wedding(ServerWorld world, ServerPlayerEntity player) {
        List<VillagerEntity> villagers = village(world, player);
        if (villagers.size() < 2) return;
        List<SocialLedger.Resident> residents = villagers.stream().map(v -> new SocialLedger.Resident(v.getUuid(), name(v), !v.isBaby())).toList();
        UUID[] pair = bridge.memory.social().villageWedding(residents, player.getUuid(), System.currentTimeMillis());
        if (pair == null) return;
        List<String> names = new ArrayList<>();
        for (UUID id : pair) {
            if (!(world.getEntity(id) instanceof VillagerEntity villager)) continue;
            names.add(name(villager));
            world.spawnParticles(ParticleTypes.HEART, villager.getX(), villager.getY() + 1.8, villager.getZ(), 6, 0.4, 0.3, 0.4, 0.02);
        }
        if (names.size() == 2) player.sendMessage(Text.translatable("mobtalk.family.wedding", names.get(0), names.get(1)), false);
    }

    /** A villager died: its relatives mourn and turn on a player killer; the nearest mourner by the player says a word of it. */
    void died(MobEntity mob, DamageSource source) {
        if (!bridge.config.enabled || !(mob instanceof VillagerEntity) || !(mob.getWorld() instanceof ServerWorld world)) return;
        ServerPlayerEntity killer = source.getAttacker() instanceof ServerPlayerEntity player ? player : null;
        long now = System.currentTimeMillis();
        String deadName = name(mob);
        List<SocialLedger.Mourner> mourners = bridge.memory.social().died(mob.getUuid(), deadName, MobGameBridge.type(mob),
                killer == null ? null : killer.getUuid(), killer == null ? "" : killer.getName().getString(), now);
        if (!enabled()) return;
        for (ServerPlayerEntity player : world.getPlayers()) {
            for (SocialLedger.Mourner mourner : mourners) {
                if (!(world.getEntity(mourner.id()) instanceof VillagerEntity relative) || relative.squaredDistanceTo(player) > 16 * 16) continue;
                bridge.initiative.raise(new Initiative.Cue(relative.getUuid(), name(relative), Initiative.Trigger.GIFT,
                        "только что погиб " + mourner.word() + " " + deadName + (killer == player ? ", и убил его этот игрок" : "")
                                + ". Горе настоящее: скажи, что чувствуешь, по характеру", List.of(), now));
                return;
            }
        }
    }
}
