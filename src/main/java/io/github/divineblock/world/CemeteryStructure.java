package io.github.divineblock.world;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureLiquidSettings;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolBasedGenerator;
import net.minecraft.structure.pool.alias.StructurePoolAliasLookup;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.structure.DimensionPadding;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

/** The cemetery on its own (/divineblock cemetery, /place structure divineblock:cemetery; no structure set places it by
 * itself): the same pool as the church's churchyard, moved to the most level spot within {@link #SEARCH} blocks of where it
 * was asked for, levelled ({@link LevelingPiece}) and cleared of trees ({@link ClearingPiece}) first. */
public final class CemeteryStructure extends Structure {
    public static final MapCodec<CemeteryStructure> CODEC = createCodec(CemeteryStructure::new);
    public static final StructureType<CemeteryStructure> TYPE = () -> CODEC;
    private static final int SEARCH = 40, STEP = 8, GRID = 4;

    public CemeteryStructure(Config config) { super(config); }

    @Override protected Optional<StructurePosition> getStructurePosition(Context context) {
        ChunkPos chunk = context.chunkPos();
        int ex = chunk.getStartX() + 8, ez = chunk.getStartZ() + 8;
        int ground = height(context, ex, ez);
        Optional<RegistryEntry.Reference<StructurePool>> pool = context.dynamicRegistryManager().get(RegistryKeys.TEMPLATE_POOL).getEntry(ChurchStructure.CEMETERY_POOL);
        if (pool.isEmpty()) return Optional.empty();
        Optional<StructurePosition> yard = StructurePoolBasedGenerator.generate(context, pool.get(), Optional.of(ChurchStructure.CEMETERY_ENTRANCE), 7,
                new BlockPos(ex, ground, ez), false, Optional.empty(), 116, StructurePoolAliasLookup.EMPTY, DimensionPadding.NONE,
                StructureLiquidSettings.APPLY_WATERLOGGING);
        if (yard.isEmpty()) return Optional.empty();
        List<StructurePiece> pieces = new ArrayList<>(yard.get().generate().toList().pieces());
        if (pieces.isEmpty()) return Optional.empty();
        BlockBox box = StructurePiece.boundingBox(pieces.stream());

        // The most level spot nearby: the least spread of the ground over the site, on a coarse grid.
        int bestDx = 0, bestDz = 0, bestScore = Integer.MAX_VALUE;
        for (int dx = -SEARCH; dx <= SEARCH; dx += STEP)
            for (int dz = -SEARCH; dz <= SEARCH; dz += STEP) {
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int x = box.getMinX(); x <= box.getMaxX() + GRID - 1; x += GRID)
                    for (int z = box.getMinZ(); z <= box.getMaxZ() + GRID - 1; z += GRID) {
                        int h = height(context, Math.min(x, box.getMaxX()) + dx, Math.min(z, box.getMaxZ()) + dz);
                        lo = Math.min(lo, h); hi = Math.max(hi, h);
                    }
                int score = (hi - lo) * 4 + (Math.abs(dx) + Math.abs(dz)) / STEP; // nearer wins a tie
                if (score < bestScore) { bestScore = score; bestDx = dx; bestDz = dz; }
            }
        int moveX = bestDx, moveZ = bestDz;
        int newGround = height(context, ex + moveX, ez + moveZ);
        pieces.forEach(p -> p.translate(moveX, newGround - ground, moveZ));
        BlockBox site = StructurePiece.boundingBox(pieces.stream());
        // Centred on the middle of the ground (the median of a few samples), so cut and fill even out.
        List<Integer> samples = new ArrayList<>();
        for (int x = site.getMinX(); x <= site.getMaxX(); x += GRID)
            for (int z = site.getMinZ(); z <= site.getMaxZ(); z += GRID) samples.add(height(context, x, z));
        samples.sort(null);
        int base = samples.get(samples.size() / 2);
        CemeteryGround.Plan plan = CemeteryGround.plan(context.chunkGenerator(), context.world(), context.noiseConfig(), site, base, null);
        LevelingPiece leveling = new LevelingPiece(plan.field(), List.of(), site.getMaxY() + 12);
        ClearingPiece clearing = new ClearingPiece(new BlockBox(site.getMinX() - 3, base, site.getMinZ() - 3, site.getMaxX() + 3,
                site.getMaxY() + 30, site.getMaxZ() + 3), List.of());
        BlockPos center = new BlockPos(site.getCenter().getX(), base, site.getCenter().getZ());
        return Optional.of(new StructurePosition(center, collector -> {
            collector.addPiece(clearing);
            collector.addPiece(leveling);
            pieces.forEach(collector::addPiece);
        }));
    }

    private static int height(Context context, int x, int z) {
        return context.chunkGenerator().getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, context.world(), context.noiseConfig()) - 1;
    }

    @Override public StructureType<?> getType() { return TYPE; }
}
