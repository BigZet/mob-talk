package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import java.util.*;

/** The village's stance towards the player in the world ({@link VillageStanding}): an exile is refused trade, the golem watches a
 * suspect and walks up to an exile, and now and then a villager warns the suspect or tells the exile to leave — in its own words,
 * through the initiative queue. Attacks stay with the existing hatred rules. Server thread only. */
final class VillageController {
    private static final long PERIOD = 40, PHASE = 29;
    /** One spoken warning per player per this many real millis. */
    private static final long WARNING_MILLIS = 10 * 60_000L;
    private static final double GOLEM_RANGE = 24, SPEAKER_RANGE = 12;
    private final MobGameBridge bridge;
    private final Map<UUID, Long> warned = new HashMap<>();
    VillageController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer; }

    /** The standing of this player in the village the villager belongs to (or stands in). */
    VillageStanding.View standing(VillagerEntity villager, ServerPlayerEntity player) {
        long now = MobClock.now();
        String village = bridge.memory.villageOf(villager.getUuid());
        if (!village.isBlank()) return bridge.memory.social().standing(village, player.getUuid(), now);
        ConversationStore.Village here = villager.getWorld() instanceof ServerWorld world ? bridge.villageAt(world, villager.getBlockPos()) : null;
        return here == null ? VillageStanding.View.NONE : bridge.memory.social().standing(here.id(), player.getUuid(), now);
    }

    /** From the interaction mixin: an exile's right click on a villager gets a shake of the head instead of the trade window, and so
     * does anyone's while the village is under attack or on alarm against them (village defence). */
    boolean refuseTrade(VillagerEntity villager, ServerPlayerEntity player) {
        if (villager.isBaby()) return false;
        boolean alarm = bridge.defense.refusesTrade(villager, player);
        if (!alarm && (!enabled() || !standing(villager, player).refusesTrade())) return false;
        villager.setHeadRollingTimeLeft(40);
        villager.getWorld().playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 1f);
        player.sendMessage(Text.translatable(alarm ? "mobtalk.defense.noTrade" : "mobtalk.village.noTrade"), true);
        return true;
    }

    void tick(ServerWorld world) {
        if (!enabled() || world.getTime() % PERIOD != PHASE) return;
        long now = MobClock.now();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            ConversationStore.Village here = bridge.villageAt(world, player.getBlockPos());
            VillageStanding.View view = here == null ? VillageStanding.View.NONE : bridge.memory.social().standing(here.id(), player.getUuid(), now);
            if (view.banished()) bridge.defense.exile(world, player);
            if (!view.golemAlert()) continue;
            for (IronGolemEntity golem : world.getEntitiesByClass(IronGolemEntity.class, player.getBoundingBox().expand(GOLEM_RANGE),
                    g -> g.isAlive() && !g.isPlayerCreated() && g.getTarget() == null)) {
                golem.getLookControl().lookAt(player, 30f, 30f);
                if (view.banished() && golem.squaredDistanceTo(player) > 6 * 6) golem.getNavigation().startMovingTo(player, 0.6);
            }
            Long last = warned.get(player.getUuid());
            if (!view.warns() || last != null && now - last < WARNING_MILLIS) continue;
            VillagerEntity speaker = null; double nearest = SPEAKER_RANGE * SPEAKER_RANGE;
            for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(SPEAKER_RANGE), v -> v.isAlive() && !v.isBaby())) {
                double distance = villager.squaredDistanceTo(player);
                if (distance < nearest && villager.canSee(player)) { nearest = distance; speaker = villager; }
            }
            if (speaker == null) continue;
            warned.put(player.getUuid(), now);
            String name = bridge.memory.displayName(speaker.getUuid());
            bridge.initiative.raise(new Initiative.Cue(speaker.getUuid(), name.isBlank() ? speaker.getName().getString() : name, Initiative.Trigger.GIFT,
                    view.banished() ? "в деревню явился изгнанник — этот игрок; деревня с ним не торгует. Скажи ему убираться, по характеру, пока голем не взялся"
                            : "этот игрок на подозрении у деревни; предупреди его, что за ним следят и терпение кончается", List.of(), now));
        }
    }

    void clearRuntime() { warned.clear(); }
}
