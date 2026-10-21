package io.github.divineblock.world;

import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructureLiquidSettings;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolBasedGenerator;
import net.minecraft.structure.pool.alias.StructurePoolAliasLookup;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.gen.structure.DimensionPadding;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

/** The Orthodox church with its churchyard: stands only on fairly level dry ground, never when switched off in the settings.
 * The churchyard is the cemetery thread's jigsaw pool {@code divineblock:cemetery/start}, started at its entrance; the church
 * turns to face it (so its altar points east only when the churchyard lies south). Without that pool a stand-in churchyard
 * ({@link CemeteryPiece}) is drawn. Northern biomes (taiga, snow) favour the log church under a tent. */
public final class ChurchStructure extends Structure {
    public static final MapCodec<ChurchStructure> CODEC = createCodec(ChurchStructure::new);
    public static final RegistryKey<StructurePool> CEMETERY_POOL = RegistryKey.of(RegistryKeys.TEMPLATE_POOL, Identifier.of("divineblock", "cemetery/start"));
    public static final Identifier CEMETERY_ENTRANCE = Identifier.of("divineblock", "cemetery_entrance");
    /** Jigsaw depth: the churchyard is the start, its birches one level below; room to spare. */
    private static final int CEMETERY_DEPTH = 4;

    public ChurchStructure(Config config) { super(config); }

    /** Points sampled for the height in church coordinates: the church's corners and middle, then the churchyard's. */
    private static final int[][] CHURCH_SAMPLES = { { -3, -7 }, { 35, -7 }, { -3, 7 }, { 35, 7 }, { 22, 0 }, { 3, 0 } },
            CEMETERY_SAMPLES = { { 8, 10 }, { 28, 10 }, { 8, 36 }, { 28, 36 }, { 18, 23 } };

    @Override protected Optional<StructurePosition> getStructurePosition(Context context) {
        if (!ChurchWorldgen.enabled()) return Optional.empty();
        ChunkPos chunk = context.chunkPos();
        int ex = chunk.getStartX() + 8, ez = chunk.getStartZ() + 8;
        int groundAtEntrance = context.chunkGenerator().getHeight(ex, ez, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig()) - 1;
        BlockPos entrance = new BlockPos(ex, groundAtEntrance, ez);

        // The churchyard first: its random turn decides the church's.
        List<StructurePiece> cemetery = List.of();
        BlockRotation rotation;
        Optional<RegistryEntry.Reference<StructurePool>> pool = context.dynamicRegistryManager().get(RegistryKeys.TEMPLATE_POOL).getEntry(CEMETERY_POOL);
        Optional<StructurePosition> yard = pool.flatMap(p -> StructurePoolBasedGenerator.generate(context, p, Optional.of(CEMETERY_ENTRANCE), CEMETERY_DEPTH,
                entrance, false, Optional.empty(), 80, StructurePoolAliasLookup.EMPTY, DimensionPadding.NONE, StructureLiquidSettings.APPLY_WATERLOGGING));
        if (yard.isPresent()) {
            cemetery = yard.get().generate().toList().pieces();
            rotation = cemetery.isEmpty() || !(cemetery.getFirst() instanceof PoolStructurePiece start) ? BlockRotation.NONE : start.getRotation();
        } else rotation = BlockRotation.random(context.random());

        ChurchPiece.Frame probe = new ChurchPiece.Frame(BlockPos.ORIGIN, rotation);
        BlockPos fromEntrance = probe.world(ChurchBuilder.CEMETERY_ENTRANCE_X, 0, ChurchBuilder.CEMETERY_MIN_Z);
        int ox = ex - fromEntrance.getX(), oz = ez - fromEntrance.getZ();
        int[] church = heights(context, ox, oz, rotation, CHURCH_SAMPLES), yardHeights = heights(context, ox, oz, rotation, CEMETERY_SAMPLES);
        if (church == null || yardHeights == null) return Optional.empty();
        int floor = Math.round((float) mean(church));
        if (max(church) - min(church) > 9 || max(yardHeights) - min(yardHeights) > 12 || Math.abs(mean(yardHeights) - floor) > 6) return Optional.empty();
        if (floor - 1 <= context.chunkGenerator().getSeaLevel()) return Optional.empty();

        ChurchPiece.Frame frame = new ChurchPiece.Frame(new BlockPos(ox, floor, oz), rotation);
        BlockPos center = frame.world(ChurchBuilder.NAVE_X, 0, ChurchBuilder.NAVE_Z);
        RegistryEntry<Biome> biome = context.biomeSource().getBiome(BiomeCoords.fromBlock(center.getX()), BiomeCoords.fromBlock(center.getY()),
                BiomeCoords.fromBlock(center.getZ()), context.noiseConfig().getMultiNoiseSampler());
        boolean north = biome.isIn(BiomeTags.IS_TAIGA) || biome.getKey().map(key -> key.getValue().getPath().matches(".*(snowy|grove).*")).orElse(false);
        ChurchBuilder.Variant variant = context.random().nextFloat() < (north ? 0.7f : 0.25f) ? ChurchBuilder.Variant.WOOD : ChurchBuilder.Variant.STONE;
        long seed = context.random().nextLong();
        List<StructurePiece> yardPieces = cemetery;
        return Optional.of(new StructurePosition(center, collector -> {
            collector.addPiece(new ChurchPiece(frame, variant, seed));
            if (yardPieces.isEmpty()) collector.addPiece(new CemeteryPiece(frame, seed ^ 0x5DEECE66DL));
            else yardPieces.forEach(collector::addPiece);
        }));
    }

    /** Surface heights at the samples, or null where water stands (a river or a lake in the terrain). */
    private static int[] heights(Context context, int ox, int oz, BlockRotation rotation, int[][] samples) {
        ChurchPiece.Frame frame = new ChurchPiece.Frame(new BlockPos(ox, 0, oz), rotation);
        int[] heights = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            BlockPos at = frame.world(samples[i][0], 0, samples[i][1]);
            int surface = context.chunkGenerator().getHeight(at.getX(), at.getZ(), Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig());
            int ground = context.chunkGenerator().getHeight(at.getX(), at.getZ(), Heightmap.Type.OCEAN_FLOOR_WG, context.world(), context.noiseConfig());
            if (ground < surface) return null;
            heights[i] = surface;
        }
        return heights;
    }
    private static double mean(int[] values) { double sum = 0; for (int v : values) sum += v; return sum / values.length; }
    private static int max(int[] values) { int m = Integer.MIN_VALUE; for (int v : values) m = Math.max(m, v); return m; }
    private static int min(int[] values) { int m = Integer.MAX_VALUE; for (int v : values) m = Math.min(m, v); return m; }

    @Override public StructureType<?> getType() { return ChurchWorldgen.CHURCH_TYPE; }
}
