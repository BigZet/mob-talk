package io.github.divineblock.mixin;

import io.github.divineblock.game.ChurchGuard;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explosions (TNT from the sky, withers, creepers) leave the church's blocks standing. */
@Mixin(Explosion.class)
abstract class ChurchExplosionMixin {
    @Shadow @Final private World world;

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void divineblock$spareChurch(boolean particles, CallbackInfo info) {
        ((Explosion) (Object) this).getAffectedBlocks().removeIf(pos -> ChurchGuard.spares(world, pos));
    }
}
