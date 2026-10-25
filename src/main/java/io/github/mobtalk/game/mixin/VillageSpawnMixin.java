package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Village defence: natural spawning puts no monster inside a village that has a golem (VillageDefenseController). Raids,
 * spawners and monsters walking in from outside are untouched: only the natural spawn's last check says no. */
@Mixin(SpawnHelper.class)
abstract class VillageSpawnMixin {
    @Inject(method = "isValidSpawn", at = @At("HEAD"), cancellable = true)
    private static void mobtalk$noMonstersInVillage(ServerWorld world, MobEntity entity, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        if (MobGameBridge.blocksMonsterSpawn(world, entity)) cir.setReturnValue(false);
    }
}
