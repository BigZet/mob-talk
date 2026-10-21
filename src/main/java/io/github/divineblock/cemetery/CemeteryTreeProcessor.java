package io.github.divineblock.cemetery;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.divineblock.DivineBlockMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.processor.StructureProcessor;
import net.minecraft.structure.processor.StructureProcessorType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import net.minecraft.world.biome.Biome;

/** Lets a cemetery tree template stand only where it grows: conifers (spruce, pine) in taiga and the cold biomes, oaks and
 * birches elsewhere. The biome is read at the tree's own origin, so a tree on a biome border stays whole or goes whole.
 * Trees are templates rather than features: a structure piece is laid chunk by chunk after the plots, so a crown never
 * reaches into a neighbouring chunk before that chunk's plots are down and the plots never settle on leaves. */
public class CemeteryTreeProcessor extends StructureProcessor {
    public static final MapCodec<CemeteryTreeProcessor> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(com.mojang.serialization.Codec.BOOL.fieldOf("cold").forGetter(p -> p.cold)).apply(instance, CemeteryTreeProcessor::new));
    public static final StructureProcessorType<CemeteryTreeProcessor> TYPE = () -> CODEC;
    private final boolean cold;

    public CemeteryTreeProcessor(boolean cold) { this.cold = cold; }

    static void register() { Registry.register(Registries.STRUCTURE_PROCESSOR, Identifier.of(DivineBlockMod.ID, "cemetery_tree"), TYPE); }

    static boolean cold(RegistryEntry<Biome> biome) { return biome.isIn(BiomeTags.IS_TAIGA) || biome.value().getTemperature() < 0.3f; }

    @Override public StructureTemplate.StructureBlockInfo process(WorldView world, BlockPos pos, BlockPos pivot, StructureTemplate.StructureBlockInfo original,
            StructureTemplate.StructureBlockInfo current, StructurePlacementData data) {
        return cold(world.getBiome(pos)) == cold ? current : null;
    }

    @Override protected StructureProcessorType<?> getType() { return TYPE; }
}
