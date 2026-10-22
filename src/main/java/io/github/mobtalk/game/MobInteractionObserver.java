package io.github.mobtalk.game;

import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import java.util.UUID;

/** An accepted click alone is not help: verify consumption or a real health/age/love/owner change. */
public final class MobInteractionObserver {
    private MobInteractionObserver() { }
    public static Before before(MobEntity mob, PlayerEntity player, Hand hand) {
        if (!(player instanceof ServerPlayerEntity) || MobGameBridge.forEntity(mob) == null) return null;
        var stack = player.getStackInHand(hand);
        return new Before(mob.getHealth(), stack.getCount(), mob instanceof AnimalEntity animal && animal.isBreedingItem(stack),
                mob instanceof AnimalEntity animal ? animal.getLoveTicks() : 0,
                mob instanceof PassiveEntity passive ? passive.getBreedingAge() : 0,
                mob instanceof TameableEntity tameable ? tameable.getOwnerUuid() : null);
    }
    public static void after(MobEntity mob, PlayerEntity player, Hand hand, ActionResult result, Before before) {
        if (before == null || !(player instanceof ServerPlayerEntity serverPlayer) || !result.isAccepted()) return;
        if (mob instanceof TameableEntity tameable && tameable.isTamed()
                && !java.util.Objects.equals(before.owner, tameable.getOwnerUuid())
                && serverPlayer.getUuid().equals(tameable.getOwnerUuid())) {
            MobGameBridge.helped(mob, serverPlayer, "The player successfully tamed the mob"); return;
        }
        if (mob.getHealth() > before.health) { MobGameBridge.helped(mob, serverPlayer, "The player healed the mob"); return; }
        boolean changed = mob instanceof AnimalEntity animal && animal.getLoveTicks() > before.love
                || mob instanceof PassiveEntity passive && passive.getBreedingAge() > before.age && before.age < 0;
        if (changed || before.food && player.getStackInHand(hand).getCount() < before.count)
            MobGameBridge.helped(mob, serverPlayer, "The player fed the mob");
    }
    public record Before(float health, int count, boolean food, int love, int age, UUID owner) { }
}
