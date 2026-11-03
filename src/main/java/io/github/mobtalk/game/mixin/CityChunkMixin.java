package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.city.CityGen;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cities 2.0: a chunk in a city's biome is levelled and built after its vanilla features ({@link CityGen#generate}). */
@Mixin(ChunkGenerator.class)
public abstract class CityChunkMixin {
    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void mobtalk$city(StructureWorldAccess world, Chunk chunk, StructureAccessor structures, CallbackInfo ci) {
        CityGen.generate(world, chunk);
    }
}
