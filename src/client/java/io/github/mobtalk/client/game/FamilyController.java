package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
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
        if (!enabled() || world.getTime() % MINUTE != PHASE) return;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            if (random.nextInt(100) < bridge.config.weddingChance) wedding(world, player);
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
        if (!enabled() || !(mob instanceof VillagerEntity) || !(mob.getWorld() instanceof ServerWorld world)) return;
        ServerPlayerEntity killer = source.getAttacker() instanceof ServerPlayerEntity player ? player : null;
        long now = System.currentTimeMillis();
        String deadName = name(mob);
        List<SocialLedger.Mourner> mourners = bridge.memory.social().died(mob.getUuid(), deadName, MobGameBridge.type(mob),
                killer == null ? null : killer.getUuid(), killer == null ? "" : killer.getName().getString(), now);
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
