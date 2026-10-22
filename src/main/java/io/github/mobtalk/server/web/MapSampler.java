package io.github.mobtalk.server.web;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The game side of the admin map (wave 1): on the server thread, a loaded chunk's top blocks become 256 colours the way a vanilla
 * map shades them (brighter up a slope, darker down it, deeper water darker), at most {@link #PER_TICK} chunks a tick, so the map
 * costs microseconds of a tick. Painting and PNG files are {@link MapTiles}' background thread. Chunks enter the queue when they load
 * and, every 30 s, around each player (what they built or dug). */
public final class MapSampler {
    public static final int PER_TICK = 2, AROUND = 4;
    private static final int RESCAN_TICKS = 600;

    private final MapTiles tiles;
    private final Map<ServerWorld, Set<Long>> queue = new ConcurrentHashMap<>();
    private int ticks;

    private MapSampler(MapTiles tiles) { this.tiles = tiles; }

    /** Hooks the sampler into chunk loads and server ticks; call once from the mod's initializer. */
    public static MapSampler register(MapTiles tiles) {
        MapSampler sampler = new MapSampler(tiles);
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> sampler.enqueue(world, chunk.getPos()));
        ServerTickEvents.END_SERVER_TICK.register(sampler::tick);
        return sampler;
    }

    void enqueue(ServerWorld world, ChunkPos pos) {
        Set<Long> chunks = queue.computeIfAbsent(world, w -> new LinkedHashSet<>());
        synchronized (chunks) { if (chunks.size() < 20_000) chunks.add(pos.toLong()); }
    }

    private void tick(MinecraftServer server) {
        if (++ticks % RESCAN_TICKS == 0)
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ChunkPos at = player.getChunkPos();
                for (int dz = -AROUND; dz <= AROUND; dz++) for (int dx = -AROUND; dx <= AROUND; dx++)
                    enqueue(player.getServerWorld(), new ChunkPos(at.x + dx, at.z + dz));
            }
        int budget = PER_TICK;
        for (Map.Entry<ServerWorld, Set<Long>> entry : queue.entrySet()) {
            Set<Long> chunks = entry.getValue();
            while (budget > 0) {
                long next;
                synchronized (chunks) {
                    var it = chunks.iterator();
                    if (!it.hasNext()) break;
                    next = it.next();
                    it.remove();
                }
                ServerWorld world = entry.getKey();
                WorldChunk chunk = world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(next), ChunkPos.getPackedZ(next), false);
                if (chunk == null) continue;
                tiles.update(world.getRegistryKey().getValue().toString(), chunk.getPos().x, chunk.getPos().z, colors(world, chunk));
                budget--;
            }
        }
    }

    /** 256 ARGB colours of a chunk seen from above, row by row (z, then x). */
    static int[] colors(ServerWorld world, WorldChunk chunk) {
        int[] colors = new int[256];
        int[] heights = new int[256];
        boolean ceiling = world.getDimension().hasCeiling();
        int bottom = world.getBottomY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int y = ceiling ? Math.min(chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z), 100)
                    : chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z);
            if (ceiling) { // Under the nether roof: first air going down, then the first visible block.
                while (y > bottom && !chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)).isAir()) y--;
            }
            MapColor color = MapColor.CLEAR;
            BlockState state = null;
            while (y > bottom) {
                state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                color = state.getMapColor(world, pos);
                if (color != MapColor.CLEAR) break;
                y--;
            }
            heights[z * 16 + x] = y;
            if (color == MapColor.CLEAR || state == null) continue;
            int depth = 0;
            FluidState fluid = state.getFluidState();
            if (fluid.isIn(FluidTags.WATER)) {
                int below = y - 1;
                while (below > bottom && depth < 16 && chunk.getBlockState(pos.set(baseX + x, below, baseZ + z)).getFluidState().isIn(FluidTags.WATER)) { depth++; below--; }
            }
            colors[z * 16 + x] = shade(color.color, depth > 0 ? (depth < 3 ? 255 : depth < 7 ? 220 : 180)
                    : slope(y, z > 0 ? heights[(z - 1) * 16 + x] : y));
        }
        return colors;
    }

    /** Vanilla map brightness: higher than the block to the north — brighter, lower — darker. */
    static int slope(int y, int north) { return y > north ? 255 : y < north ? 180 : 220; }

    static int shade(int rgb, int brightness) {
        int r = ((rgb >> 16) & 0xFF) * brightness / 255, g = ((rgb >> 8) & 0xFF) * brightness / 255, b = (rgb & 0xFF) * brightness / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
