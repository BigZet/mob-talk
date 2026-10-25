package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.CityWorldgen;
import net.minecraft.world.gen.feature.TreeFeature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No wild trees inside a city: they grew through roofs and streets and left leaves hanging where the houses cut them. */
@Mixin(TreeFeature.class)
public abstract class CityTreesMixin {
    @Inject(method = "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z", at = @At("HEAD"), cancellable = true)
    private void mobtalk$noTreesInCities(FeatureContext<TreeFeatureConfig> context, CallbackInfoReturnable<Boolean> cir) {
        if (CityWorldgen.inCity(context.getWorld(), context.getOrigin())) cir.setReturnValue(false);
    }
}
