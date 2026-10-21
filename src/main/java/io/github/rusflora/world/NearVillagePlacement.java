package io.github.rusflora.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.feature.FeaturePlacementContext;
import net.minecraft.world.gen.placementmodifier.AbstractConditionalPlacementModifier;
import net.minecraft.world.gen.placementmodifier.PlacementModifierType;

/** Keeps a spot only inside a chunk that a village reaches: lilac grows by village houses. */
public final class NearVillagePlacement extends AbstractConditionalPlacementModifier {
    public static final NearVillagePlacement INSTANCE = new NearVillagePlacement();
    public static final MapCodec<NearVillagePlacement> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override protected boolean shouldPlace(FeaturePlacementContext context, Random random, BlockPos pos) {
        var world = context.getWorld();
        var structures = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        var refs = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.STRUCTURE_REFERENCES).getStructureReferences();
        for (var entry : refs.entrySet())
            if (!entry.getValue().isEmpty() && structures.getEntry(entry.getKey()).isIn(StructureTags.VILLAGE)) return true;
        return false;
    }

    @Override public PlacementModifierType<?> getType() { return RusFloraWorldgen.NEAR_VILLAGE; }
}
