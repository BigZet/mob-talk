package io.github.rusflora.hogweed;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.feature.FeaturePlacementContext;
import net.minecraft.world.gen.placementmodifier.AbstractConditionalPlacementModifier;
import net.minecraft.world.gen.placementmodifier.PlacementModifierType;

/** Keeps a spot only if open water lies within radius blocks around it at ground level: hogweed thickets along river banks,
 * lakes and swamp edges. */
public final class NearWaterPlacement extends AbstractConditionalPlacementModifier {
    public static final MapCodec<NearWaterPlacement> CODEC = Codec.intRange(1, 12).fieldOf("radius").xmap(NearWaterPlacement::new, p -> p.radius);
    private final int radius;

    public NearWaterPlacement(int radius) { this.radius = radius; }

    @Override protected boolean shouldPlace(FeaturePlacementContext context, Random random, BlockPos pos) {
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx += 2)
            for (int dz = -radius; dz <= radius; dz += 2)
                for (int dy = -2; dy <= 0; dy++)
                    if (context.getWorld().getFluidState(at.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz)).isIn(FluidTags.WATER)) return true;
        return false;
    }

    @Override public PlacementModifierType<?> getType() { return Hogweed.NEAR_WATER; }
}
