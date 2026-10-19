package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.MischiefWatch;
import io.github.mobtalk.client.game.MobGameBridge;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block a player places on the integrated server goes into the change log (Fabric has a break event but no place event)
 * and is marked as the player's own, so breaking or opening it later is no misdeed. */
@Mixin(BlockItem.class)
abstract class BlockPlacedMixin {
    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("RETURN"))
    private void mobtalk$placed(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (cir.getReturnValue() == null || !cir.getReturnValue().isAccepted()) return;
        MischiefWatch.placed(context);
        if (context.getWorld() instanceof ServerWorld world && context.getPlayer() instanceof ServerPlayerEntity player)
            MobGameBridge.blockPlaced(world, context.getBlockPos(), player);
    }
}
