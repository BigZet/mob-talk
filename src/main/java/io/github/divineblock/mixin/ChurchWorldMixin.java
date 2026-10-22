package io.github.divineblock.mixin;

import io.github.divineblock.game.ChurchGuard;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The church is inviolable: see {@link ChurchGuard}. */
@Mixin(World.class)
abstract class ChurchWorldMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void divineblock$church(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> info) {
        if (ChurchGuard.refuses((World) (Object) this, pos, state)) info.setReturnValue(false);
    }

    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;ZLnet/minecraft/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void divineblock$churchBreak(BlockPos pos, boolean drop, Entity breaker, int maxUpdateDepth, CallbackInfoReturnable<Boolean> info) {
        if (ChurchGuard.refusesBreak((World) (Object) this, pos)) info.setReturnValue(false);
    }
}
