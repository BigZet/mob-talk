package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.MobGameBridge;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.entity.effect.InstantHealthOrDamageStatusEffect")
abstract class InstantHealingMixin {
    @Unique private float mobtalk$health;
    @Inject(method = "applyInstantEffect", at = @At("HEAD"))
    private void mobtalk$before(Entity source, Entity attacker, LivingEntity target, int amplifier, double proximity, CallbackInfo ci) {
        mobtalk$health = target.getHealth();
    }
    @Inject(method = "applyInstantEffect", at = @At("RETURN"))
    private void mobtalk$after(Entity source, Entity attacker, LivingEntity target, int amplifier, double proximity, CallbackInfo ci) {
        if (target instanceof MobEntity mob && attacker instanceof ServerPlayerEntity player && target.getHealth() > mobtalk$health)
            MobGameBridge.helped(mob, player, "The player's potion healed the mob");
    }
}
