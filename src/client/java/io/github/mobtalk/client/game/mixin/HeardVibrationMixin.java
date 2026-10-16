package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.MobGameBridge;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.event.GameEvent;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records only vibrations accepted by vanilla (including sneaking/wool/range filtering). */
@Mixin(targets = "net.minecraft.entity.mob.WardenEntity$VibrationCallback")
abstract class HeardVibrationMixin {
    @Shadow @Final private WardenEntity field_44600;
    @Inject(method = "accept", at = @At("HEAD"))
    private void mobtalk$heard(ServerWorld world, BlockPos position, RegistryEntry<GameEvent> event,
            Entity source, Entity owner, float distance, CallbackInfo ci) {
        MobGameBridge.heard(field_44600, Vec3d.ofCenter(position), source,
                event.getKey().map(key -> key.getValue().toString()).orElse("vibration"));
    }
}
