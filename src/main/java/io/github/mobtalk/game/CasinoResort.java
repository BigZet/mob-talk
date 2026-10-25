package io.github.mobtalk.game;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.mobtalk.server.ServerConfig;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolBasedGenerator;
import net.minecraft.structure.pool.alias.StructurePoolAliasLookup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.structure.JigsawStructure;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;
import java.util.Arrays;
import java.util.Optional;

/** The casino resort «Фортуна» (docs/casino.md): one big template (data/mobtalk/structure/casino/resort.nbt, made by
 * tools/casino/resort.py) placed as a rare standalone structure (worldgen/structure/casino_resort.json). It only stands on ground
 * that is fairly level and dry over its whole footprint, and not at all when {@code casinoResort} is off. */
public final class CasinoResort extends Structure {
    public static final MapCodec<CasinoResort> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(configCodecBuilder(instance),
            StructurePool.REGISTRY_CODEC.fieldOf("start_pool").forGetter(s -> s.pool)).apply(instance, CasinoResort::new));
    public static StructureType<CasinoResort> TYPE;
    /** How far the template reaches from its corner (it is 80 blocks across), and the most the ground may rise and fall under it. */
    private static final int REACH = 80, ROUGHNESS = 14;
    private final RegistryEntry<StructurePool> pool;

    private CasinoResort(Config config, RegistryEntry<StructurePool> pool) {
        super(config);
        this.pool = pool;
    }

    public static void register() {
        TYPE = Registry.register(Registries.STRUCTURE_TYPE, Identifier.of("mobtalk", "resort"), () -> CODEC);
    }

    @Override protected Optional<StructurePosition> getStructurePosition(Context context) {
        if (!ServerConfig.get().casinoResort) return Optional.empty();
        ChunkPos chunk = context.chunkPos();
        int x0 = chunk.getStartX(), z0 = chunk.getStartZ();
        // the template turns about its corner, so it may lie in any quarter round it: sample the ground of all four
        int[] heights = new int[25];
        int n = 0;
        for (int i = -2; i <= 2; i++)
            for (int j = -2; j <= 2; j++) {
                int x = x0 + i * REACH / 2, z = z0 + j * REACH / 2;
                int surface = context.chunkGenerator().getHeightOnGround(x, z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig());
                int floor = context.chunkGenerator().getHeightOnGround(x, z, Heightmap.Type.OCEAN_FLOOR_WG, context.world(), context.noiseConfig());
                if (surface != floor) return Optional.empty();  // water there
                heights[n++] = surface;
            }
        Arrays.sort(heights);
        if (heights[heights.length - 3] - heights[2] > ROUGHNESS) return Optional.empty();
        BlockPos start = new BlockPos(x0, heights[heights.length / 2] - 1, z0);
        return StructurePoolBasedGenerator.generate(context, pool, Optional.empty(), 1, start, false, Optional.empty(), 128,
                StructurePoolAliasLookup.EMPTY, JigsawStructure.DEFAULT_DIMENSION_PADDING, JigsawStructure.DEFAULT_LIQUID_SETTINGS);
    }

    @Override public StructureType<?> getType() { return TYPE; }
}
