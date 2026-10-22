package io.github.divineblock.world;

import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructureLiquidSettings;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.pool.FeaturePoolElement;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolBasedGenerator;
import net.minecraft.structure.pool.alias.StructurePoolAliasLookup;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
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
    /** Jigsaw depth and reach: the churchyard grows in a chain of sections along its path away from the church, birches one
     * level below each; room to spare for a large one. */
    private static final int CEMETERY_DEPTH = 7, CEMETERY_REACH = 116;

    public ChurchStructure(Config config) { super(config); }

    /** The church self-test's switch: churches placed while it is set are all of this kind. Never set in play. */
    public static volatile ChurchBuilder.Variant forceVariant;

    /** Points sampled for the height in church coordinates: the church's corners and middle, then a 40 x 60 churchyard ground
     * beyond it (dry, not too steep), room for a large cemetery growing away from the church. */
    private static final int[][] CHURCH_SAMPLES = { { -6, -12 }, { 62, -12 }, { -6, 12 }, { 62, 12 }, { 42, 0 }, { 6, 0 }, { 21, 0 } },
            YARD_SAMPLES = { { ChurchBuilder.YARD_MIN_X, ChurchBuilder.YARD_MIN_Z }, { ChurchBuilder.YARD_MAX_X, ChurchBuilder.YARD_MIN_Z },
                    { ChurchBuilder.YARD_MIN_X, ChurchBuilder.YARD_MAX_Z }, { ChurchBuilder.YARD_MAX_X, ChurchBuilder.YARD_MAX_Z },
                    { 28, ChurchBuilder.YARD_MIN_Z }, { 28, ChurchBuilder.YARD_MAX_Z }, { ChurchBuilder.YARD_MIN_X, 0 }, { ChurchBuilder.YARD_MAX_X, 0 } },
            CEMETERY_SAMPLES = { { 8, 41 }, { 48, 41 }, { 8, 71 }, { 48, 71 }, { 8, 101 }, { 48, 101 }, { 28, 56 }, { 28, 86 } };

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
                entrance, false, Optional.empty(), CEMETERY_REACH, StructurePoolAliasLookup.EMPTY, DimensionPadding.NONE, StructureLiquidSettings.APPLY_WATERLOGGING));
        if (yard.isPresent()) {
            cemetery = yard.get().generate().toList().pieces();
            rotation = cemetery.isEmpty() || !(cemetery.getFirst() instanceof PoolStructurePiece start) ? BlockRotation.NONE : start.getRotation();
        } else rotation = BlockRotation.random(context.random());

        ChurchPiece.Frame probe = new ChurchPiece.Frame(BlockPos.ORIGIN, rotation);
        BlockPos fromEntrance = probe.world(ChurchBuilder.CEMETERY_ENTRANCE_X, 0, ChurchBuilder.CEMETERY_MIN_Z);
        int ox = ex - fromEntrance.getX(), oz = ez - fromEntrance.getZ();
        int[] church = heights(context, ox, oz, rotation, CHURCH_SAMPLES), yardHeights = heights(context, ox, oz, rotation, CEMETERY_SAMPLES),
                court = heights(context, ox, oz, rotation, YARD_SAMPLES);
        if (church == null || yardHeights == null || court == null) return Optional.empty();
        int floor = Math.round((float) mean(church));
        if (max(church) - min(church) > 10 || max(yardHeights) - floor > 8 || floor - min(yardHeights) > 8) return Optional.empty();
        // The walled churchyard is levelled by the terrain adaptation and cut by its own piece: a fair slope is fine.
        if (max(court) - floor > 14 || floor - min(court) > 12) return Optional.empty();
        if (floor - 1 <= context.chunkGenerator().getSeaLevel()) return Optional.empty();

        ChurchPiece.Frame frame = new ChurchPiece.Frame(new BlockPos(ox, floor, oz), rotation);
        BlockPos center = frame.world(ChurchBuilder.NAVE_X, 0, ChurchBuilder.NAVE_Z);
        // The whole site in the biomes of the tag, the churchyard's far end too: no beach, river or sea at its edge.
        for (int[] point : BIOME_SAMPLES)
            if (!biomeAt(context, frame.world(point[0], 0, point[1])).isIn(BIOMES)) return Optional.empty();
        RegistryEntry<Biome> biome = biomeAt(context, center);
        boolean north = biome.isIn(BiomeTags.IS_TAIGA) || biome.getKey().map(key -> key.getValue().getPath().matches(".*snowy.*")).orElse(false);
        ChurchBuilder.Variant chance = context.random().nextFloat() < (north ? 0.7f : 0.25f) ? ChurchBuilder.Variant.WOOD : ChurchBuilder.Variant.STONE;
        ChurchBuilder.Variant variant = forceVariant != null ? forceVariant : chance;
        long seed = context.random().nextLong();
        // The churchyard was started at the ground by its entrance; its gate belongs one under the church floor.
        int lift = floor - 1 - groundAtEntrance;
        cemetery.forEach(piece -> piece.translate(0, lift, 0));
        List<StructurePiece> yardPieces = cemetery;
        BlockBox yardBox = yardPieces.isEmpty() ? null : StructurePiece.boundingBox(yardPieces.stream()).expand(3); // ponds and trees right at the fence too
        ClearingPiece clearing = yardBox == null ? null
                : new ClearingPiece(new BlockBox(yardBox.getMinX(), yardBox.getMinY() - 2, yardBox.getMinZ(), yardBox.getMaxX(), floor + 34, yardBox.getMaxZ()),
                        yardPieces.stream().filter(p -> p instanceof PoolStructurePiece element && element.getPoolElement() instanceof FeaturePoolElement)
                                .map(p -> ((PoolStructurePiece) p).getPos()).toList());
        return Optional.of(new StructurePosition(center, collector -> {
            collector.addPiece(new ChurchYard(frame, variant)); // first: the church draws over the levelled yard
            collector.addPiece(new ChurchPiece(frame, variant, seed));
            if (yardPieces.isEmpty()) collector.addPiece(new CemeteryPiece(frame, seed ^ 0x5DEECE66DL));
            else { collector.addPiece(clearing); yardPieces.forEach(collector::addPiece); }
        }));
    }

    /** Where the biome must be one of the tag's: the nave, the walled yard's corners, the cemetery's corners, middle and far
     * end (21 x 58 from its entrance at z = 41). */
    private static final int[][] BIOME_SAMPLES = { { ChurchBuilder.NAVE_X, 0 }, { ChurchBuilder.YARD_MIN_X, ChurchBuilder.YARD_MIN_Z },
            { ChurchBuilder.YARD_MAX_X, ChurchBuilder.YARD_MIN_Z }, { ChurchBuilder.YARD_MIN_X, ChurchBuilder.YARD_MAX_Z }, { ChurchBuilder.YARD_MAX_X, ChurchBuilder.YARD_MAX_Z },
            { 18, 41 }, { 38, 41 }, { 28, 70 }, { 18, 99 }, { 38, 99 }, { 28, 99 } };
    private static final TagKey<Biome> BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of("divineblock", "has_structure/orthodox_church"));

    private static RegistryEntry<Biome> biomeAt(Context context, BlockPos at) {
        return context.biomeSource().getBiome(BiomeCoords.fromBlock(at.getX()), BiomeCoords.fromBlock(at.getY()), BiomeCoords.fromBlock(at.getZ()),
                context.noiseConfig().getMultiNoiseSampler());
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
