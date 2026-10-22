package io.github.rusflora.hogweed;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** How big a thicket may get: no more than maxPlantsPerChunk roots in one chunk and maxPlantsPerRegion in the 7x7 chunks
 * around a landing seed. Counts are cached per chunk and recounted at most once a minute; a chunk without any hogweed is
 * answered from the section palettes without reading a single block, so the check costs almost nothing in the common case. */
public final class ThicketLimits {
    private static final int STALE_TICKS = 1200, REGION = 3;
    private static final Map<ServerWorld, Long2ObjectOpenHashMap<long[]>> COUNTS = new WeakHashMap<>();

    private ThicketLimits() { }

    /** True if one more plant may take root at pos. */
    public static boolean allows(ServerWorld world, BlockPos pos) {
        HogweedConfig config = HogweedConfig.get();
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (count(world, cx, cz) >= config.maxPlantsPerChunk) return false;
        int total = 0;
        for (int x = cx - REGION; x <= cx + REGION; x++)
            for (int z = cz - REGION; z <= cz + REGION; z++)
                if ((total += count(world, x, z)) >= config.maxPlantsPerRegion) return false;
        return true;
    }

    /** A new plant took root at pos: keeps the cached count honest until the next recount. */
    public static void added(ServerWorld world, BlockPos pos) {
        long[] entry = counts(world).get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (entry != null) entry[0]++;
    }

    /** A plant went away (eaten, mowed, rotted); plants broken by hand or burnt are caught by the next recount. */
    public static void removed(ServerWorld world, BlockPos pos) {
        long[] entry = counts(world).get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (entry != null && entry[0] > 0) entry[0]--;
    }

    /** Drops all cached counts of a world (for tests that change the limits). */
    public static void forget(ServerWorld world) { counts(world).clear(); }

    public static int count(ServerWorld world, int cx, int cz) {
        Long2ObjectOpenHashMap<long[]> counts = counts(world);
        long key = ChunkPos.toLong(cx, cz), now = world.getTime();
        long[] entry = counts.get(key);
        if (entry != null && now - entry[1] < STALE_TICKS) return (int) entry[0];
        if (!world.getChunkManager().isChunkLoaded(cx, cz)) return entry == null ? 0 : (int) entry[0];
        int counted = recount(world.getChunk(cx, cz));
        counts.put(key, new long[] {counted, now});
        return counted;
    }

    private static int recount(WorldChunk chunk) {
        boolean any = false;
        for (ChunkSection section : chunk.getSectionArray())
            if (!section.isEmpty() && section.getBlockStateContainer().hasAny(state -> state.isOf(Hogweed.BLOCK))) { any = true; break; }
        if (!any) return 0;
        int count = 0;
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int top = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
                for (int y = top + 1; y > top - 4; y--) {
                    BlockState state = chunk.getBlockState(at.set(x, y, z));
                    if (state.isOf(Hogweed.BLOCK) && state.get(HogweedBlock.PART) == 0) { count++; break; }
                }
            }
        return count;
    }

    private static synchronized Long2ObjectOpenHashMap<long[]> counts(ServerWorld world) {
        return COUNTS.computeIfAbsent(world, w -> new Long2ObjectOpenHashMap<>());
    }
}
