package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.city.CityMap;
import java.util.stream.Stream;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cities 2.0 (spec §6.2): the overworld's biome inside a city's reach is the city's own biome. The biome source also lists it
 * among its biomes, so the chunk generator runs the city biome's features (its ores under the city). */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class CityBiomeMixin {
    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void mobtalk$city(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise, CallbackInfoReturnable<RegistryEntry<Biome>> cir) {
        RegistryEntry<Biome> city = CityMap.biomeAt((MultiNoiseBiomeSource) (Object) this, x << 2, z << 2, noise);
        if (city != null) cir.setReturnValue(city);
    }

    @Inject(method = "biomeStream", at = @At("RETURN"), cancellable = true)
    private void mobtalk$listCity(CallbackInfoReturnable<Stream<RegistryEntry<Biome>>> cir) {
        RegistryEntry<Biome> city = CityMap.biome();
        if (city != null) cir.setReturnValue(Stream.concat(cir.getReturnValue(), Stream.of(city)));
    }
}
