package io.github.divineblock.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.SpreadType;
import net.minecraft.world.gen.chunk.placement.StructurePlacementType;

/** A random spread whose spacing comes from the mod's settings (how rare churches are), not from the data pack, so the player
 * can change it in F9. One church per grid cell of spacing x spacing chunks, at least spacing - separation chunks apart. A
 * new spacing applies to chunks generated afterwards; churches already standing stay. /locate reads the same numbers. */
public final class ChurchPlacement extends RandomSpreadStructurePlacement {
    public static final MapCodec<ChurchPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> buildCodec(instance).apply(instance, ChurchPlacement::new));

    public ChurchPlacement(Vec3i locateOffset, FrequencyReductionMethod method, float frequency, int salt, Optional<ExclusionZone> exclusion) {
        super(locateOffset, method, frequency, salt, exclusion, ChurchWorldgen.DEFAULT_SPACING, ChurchWorldgen.separation(ChurchWorldgen.DEFAULT_SPACING), SpreadType.LINEAR);
    }

    @Override public int getSpacing() { return ChurchWorldgen.spacing(); }
    @Override public int getSeparation() { return ChurchWorldgen.separation(ChurchWorldgen.spacing()); }

    @Override public ChunkPos getStartChunk(long seed, int chunkX, int chunkZ) {
        int spacing = getSpacing(), spread = spacing - ChurchWorldgen.separation(spacing);
        int cellX = Math.floorDiv(chunkX, spacing), cellZ = Math.floorDiv(chunkZ, spacing);
        ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
        random.setRegionSeed(seed, cellX, cellZ, getSalt());
        return new ChunkPos(cellX * spacing + random.nextInt(spread), cellZ * spacing + random.nextInt(spread));
    }

    @Override public StructurePlacementType<?> getType() { return ChurchWorldgen.PLACEMENT_TYPE; }
}
