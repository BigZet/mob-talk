package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import java.util.*;

/** The physical side of the villagers' social layer on the integrated server: a gift or ring leaves the player's hand when the reply
 * accepts it, a slap stings, and a gift thrown at an acquainted villager's feet is weighed, picked up (or left lying) and answered in
 * words through the initiative queue. The relationship, the state and the memory change in {@link SocialLedger}. Server thread only. */
final class SocialController {
    /** A thrown gift counts when it lands this close to an acquainted villager, while the item is this young (ticks). */
    private static final double GIFT_RANGE = 2.5;
    private static final int GIFT_AGE = 200;
    private final MobGameBridge bridge;
    /** Dropped stacks already judged, so one throw is one gift. */
    private final Set<UUID> judged = new LinkedHashSet<>();
    SocialController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer; }

    /** Inside the delivery, under the memory lock: the gesture's effect on the world; "" or a line for the chat. A gift that is no
     * longer in hand cancels the reply (it would thank for nothing). */
    String apply(MobEntity mob, ServerPlayerEntity player, GestureIntent gesture) {
        if (gesture == null || !gesture.present() || !enabled()) return "";
        if (gesture.takesItem()) {
            ItemStack hand = player.getMainHandStack();
            if (hand.isEmpty() || !Registries.ITEM.getId(hand.getItem()).toString().equals(gesture.item()))
                throw new IllegalStateException("Подарка в руке уже нет: ответ отменён");
            hand.decrement(1);
            if (mob.getWorld() instanceof ServerWorld world) world.playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
            return "";
        }
        if (gesture.outcome() == Gesture.Outcome.SLAP && mob.getWorld() instanceof ServerWorld world) {
            player.damage(world.getDamageSources().mobAttack(mob), 1.0f);
            return "Пощёчина!";
        }
        return "";
    }

    void tick(ServerWorld world) {
        if (!enabled() || world.getTime() % 10 != 3) return;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            for (ItemEntity drop : world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(12),
                    item -> item.isAlive() && item.getItemAge() < GIFT_AGE && !judged.contains(item.getUuid()) && item.getOwner() == player)) {
                VillagerEntity villager = nearestAcquainted(world, drop);
                if (villager == null) continue;
                judged.add(drop.getUuid());
                while (judged.size() > 256) judged.remove(judged.iterator().next());
                gift(world, player, villager, drop, now);
            }
        }
    }
    private VillagerEntity nearestAcquainted(ServerWorld world, ItemEntity drop) {
        VillagerEntity best = null; double nearest = GIFT_RANGE * GIFT_RANGE;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, drop.getBoundingBox().expand(GIFT_RANGE),
                v -> v.isAlive() && !v.isRemoved() && bridge.memory.acquainted(v.getUuid()))) {
            double distance = villager.squaredDistanceTo(drop);
            if (distance <= nearest) { nearest = distance; best = villager; }
        }
        return best;
    }
    private void gift(ServerWorld world, ServerPlayerEntity player, VillagerEntity villager, ItemEntity drop, long now) {
        String item = Registries.ITEM.getId(drop.getStack().getItem()).toString();
        String profession = Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).toString();
        GestureIntent result = bridge.memory.social().giftThrown(villager.getUuid(), MobGameBridge.type(villager), villager.getName().getString(),
                player.getUuid(), player.getName().getString(), item, profession, villager.isBaby(), now);
        if (result == null) return;
        villager.getLookControl().lookAt(player, 30f, 30f);
        if (result.takesItem()) {
            drop.getStack().decrement(1);
            if (drop.getStack().isEmpty()) drop.discard();
            world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
        } else {
            villager.setHeadRollingTimeLeft(40);
            world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 1f);
        }
        String name = bridge.memory.displayName(villager.getUuid());
        bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), name.isBlank() ? villager.getName().getString() : name, Initiative.Trigger.GIFT,
                SocialLedger.giftCue(result), List.of(), now));
    }

    /** One item from the player's main hand goes to the creature (the gift key): weighed by the ledger, taken or declined with a sound,
     * then the creature says a word of it. Returns the line for the chat. */
    String giftFromHand(ServerWorld world, ServerPlayerEntity player, MobEntity mob) {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) return "В руке ничего нет";
        if (!mob.isAlive() || mob.squaredDistanceTo(player) > bridge.config.maxDistance * bridge.config.maxDistance) return "Подойдите к мобу ближе";
        String item = Registries.ITEM.getId(stack.getItem()).toString();
        String profession = mob instanceof VillagerEntity villager ? Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).toString() : "";
        boolean favouriteFood = mob instanceof net.minecraft.entity.passive.AnimalEntity animal && animal.isBreedingItem(stack);
        long now = System.currentTimeMillis();
        GestureIntent result = bridge.memory.social().giftFromHand(mob.getUuid(), MobGameBridge.type(mob), mob.getName().getString(),
                player.getUuid(), player.getName().getString(), item, profession, mob.isBaby(), favouriteFood, now);
        if (result == null) return "Этот моб подарков не принимает";
        String name = bridge.memory.displayName(mob.getUuid());
        if (name.isBlank()) name = mob.getName().getString();
        mob.getLookControl().lookAt(player, 30f, 30f);
        String shown = stack.getName().getString();
        if (result.takesItem()) {
            stack.decrement(1);
            world.playSound(null, mob.getBlockPos(), mob instanceof VillagerEntity ? SoundEvents.ENTITY_VILLAGER_YES : SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 1f, 1f);
        } else {
            if (mob instanceof VillagerEntity villager) villager.setHeadRollingTimeLeft(40);
            world.playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 0.6f, mob instanceof VillagerEntity ? 1f : 1.4f);
        }
        bridge.initiative.raise(new Initiative.Cue(mob.getUuid(), name, Initiative.Trigger.GIFT, SocialLedger.handGiftCue(result), List.of(), now));
        return result.takesItem() ? name + " принимает " + shown + (result.hearts() > 0 ? " (+" + result.hearts() + ")" : "") : name + " не берёт " + shown;
    }

    void clearRuntime() { judged.clear(); }
}
