package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.MobGameBridge;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BucketItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Water or lava poured from a bucket counts as a placed block: that is how funnels and lava traps are made. */
@Mixin(BucketItem.class)
abstract class FluidPlacedMixin {
    @Inject(method = "placeFluid(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/hit/BlockHitResult;)Z", at = @At("RETURN"))
    private void mobtalk$poured(PlayerEntity player, World world, BlockPos pos, BlockHitResult hit, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && world instanceof ServerWorld serverWorld && player instanceof ServerPlayerEntity serverPlayer)
            MobGameBridge.blockPlaced(serverWorld, pos, serverPlayer);
    }
}
