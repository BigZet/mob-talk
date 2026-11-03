package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.Optional;

@Mixin(Brain.class)
abstract class BrainMovementMixin {
    @Unique private MobEntity mobtalk$owner;
    @Inject(method = "tick", at = @At("HEAD"))
    private void mobtalk$owner(ServerWorld world, LivingEntity entity, CallbackInfo ci) {
        mobtalk$owner = entity instanceof MobEntity mob ? mob : null;
        MobGameBridge.thinking(mobtalk$owner);
    }
    @Inject(method = "tick", at = @At("RETURN"))
    private void mobtalk$movement(ServerWorld world, LivingEntity entity, CallbackInfo ci) {
        MobGameBridge.thinking(null);
        MobGameBridge.afterBrain(world, entity);
    }
    @Inject(method = {"getOptionalMemory", "getOptionalRegisteredMemory"}, at = @At("HEAD"), cancellable = true)
    private void mobtalk$temporaryPeace(MemoryModuleType<?> type, CallbackInfoReturnable<Optional<?>> ci) {
        if (type == MemoryModuleType.ATTACK_TARGET && mobtalk$owner != null && MobGameBridge.suppressesAttack(mobtalk$owner))
            ci.setReturnValue(Optional.empty());
    }
}
