package io.github.divineblock.client.mixin;

import io.github.divineblock.client.BloodWater;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Water the colour of blood while the first plague lasts. */
@Mixin(BiomeColors.class)
public abstract class BloodWaterMixin {
    @Inject(method = "getWaterColor", at = @At("RETURN"), cancellable = true)
    private static void divineblock$blood(BlockRenderView world, BlockPos pos, CallbackInfoReturnable<Integer> info) {
        if (BloodWater.active()) info.setReturnValue(BloodWater.tint(info.getReturnValue()));
    }
}
