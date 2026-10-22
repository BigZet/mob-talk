package io.github.divineblock.world;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

/** The ground the cemetery stands on: a nearly level height for every column of its site, worked out once from the terrain
 * when the structure is planned (gentle bumps of a block or two are kept, so it does not look poured flat).
 * {@link LevelingPiece} cuts and fills the real ground to it before the plots are laid, and {@link CemeteryGroundProcessor}
 * stands every plot block and every tree on it. Fields are found by position: the processor gets no other link to its
 * structure, so each field is registered here when its piece is made or read back from the save. */
public final class CemeteryGround {
    /** How far the cut and fill are blended into the natural ground around the site. */
    public static final int MARGIN = 4;
    private static final int GRID = 3;
    private static final Map<Long, List<Field>> BY_CHUNK = new ConcurrentHashMap<>();

    private CemeteryGround() { }

    /** Target ground heights (the y of the top ground block) over the rectangle minX..minX+sizeX-1, minZ..minZ+sizeZ-1. */
    public record Field(int minX, int minZ, int sizeX, int sizeZ, int[] heights) {
        public boolean contains(int x, int z) { return x >= minX && z >= minZ && x < minX + sizeX && z < minZ + sizeZ; }
        public int at(int x, int z) { return heights[(x - minX) * sizeZ + (z - minZ)]; }
        /** The height at the nearest column of the field. */
        public int nearest(int x, int z) {
            return at(Math.clamp(x, minX, minX + sizeX - 1), Math.clamp(z, minZ, minZ + sizeZ - 1));
        }
        public BlockBox box(int y0, int y1) { return new BlockBox(minX, y0, minZ, minX + sizeX - 1, y1, minZ + sizeZ - 1); }
        String key() { return minX + "," + minZ + "," + sizeX + "," + sizeZ; }
    }

    /** What the planner found: the field and how rough the natural ground was under it. */
    public record Plan(Field field, int maxCut, double meanCut, int spread, double water) {
        /** Fairly level dry ground: worth a cemetery without looking dug out of a hill. */
        public boolean level() { return maxCut <= 4 && meanCut <= 1.6 && spread <= 8 && water < 0.04; }
    }

    static void register(Field field) {
        for (int cx = field.minX >> 4; cx <= (field.minX + field.sizeX - 1) >> 4; cx++)
            for (int cz = field.minZ >> 4; cz <= (field.minZ + field.sizeZ - 1) >> 4; cz++) {
                List<Field> list = BY_CHUNK.computeIfAbsent(ChunkPos.toLong(cx, cz), k -> new CopyOnWriteArrayList<>());
                list.removeIf(f -> f.key().equals(field.key()));
                list.add(field);
            }
    }

    /** The target ground at the column, or {@link Integer#MIN_VALUE} where no cemetery is planned. */
    public static int target(int x, int z) {
        List<Field> list = BY_CHUNK.get(ChunkPos.toLong(x >> 4, z >> 4));
        if (list != null) for (Field field : list) if (field.contains(x, z)) return field.at(x, z);
        return Integer.MIN_VALUE;
    }

    /** Ground for leveling: solid earth or rock, a boulder too, but no log, leaf, plant or block of the mod. */
    static boolean ground(BlockView world, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) return false;
        if (Registries.BLOCK.getId(state.getBlock()).getNamespace().equals("divineblock")) return false;
        return state.isSolidBlock(world, pos);
    }

    /**
     * Plans the ground for a site. yard: the cemetery's pieces (x and z used); base: the ground level the site is
     * centred on (one under the church floor, or the median); entrance: the column where the site meets the church, held
     * at base for a few blocks so the gate meets it; null for none.
     */
    public static Plan plan(ChunkGenerator generator, HeightLimitView world, NoiseConfig noise, BlockBox yard, int base, BlockPos entrance) {
        int minX = yard.getMinX(), minZ = yard.getMinZ(), sx = yard.getBlockCountX(), sz = yard.getBlockCountZ();
        // The natural ground on a coarse grid, interpolated between.
        int gx = sx / GRID + 2, gz = sz / GRID + 2;
        double[][] grid = new double[gx][gz];
        int waterCells = 0;
        for (int i = 0; i < gx; i++)
            for (int j = 0; j < gz; j++) {
                int x = minX + Math.min(i * GRID, sx - 1), z = minZ + Math.min(j * GRID, sz - 1);
                int surface = generator.getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, world, noise) - 1;
                int floor = generator.getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, world, noise) - 1;
                if (floor < surface) waterCells++;
                grid[i][j] = floor;
            }
        double[][] h = new double[sx][sz];
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++) {
                double fx = Math.min(x, sx - 1) / (double) GRID, fz = Math.min(z, sz - 1) / (double) GRID;
                int i = Math.min((int) fx, gx - 2), j = Math.min((int) fz, gz - 2);
                double u = Math.min(1, fx - i), v = Math.min(1, fz - j);
                h[x][z] = (grid[i][j] * (1 - u) + grid[i + 1][j] * u) * (1 - v) + (grid[i][j + 1] * (1 - u) + grid[i + 1][j + 1] * u) * v;
            }
        // A broad swell of the land, half as steep as it really is and at most two blocks off the base, plus a third of the
        // smaller bumps, all smoothed so the steps stay a block high.
        double[][] broad = blur(h, 8);
        double[][] t = new double[sx][sz];
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++) {
                double swell = Math.clamp(0.5 * (broad[x][z] - base), -2, 2);
                double bump = Math.clamp(0.35 * (h[x][z] - broad[x][z]), -1, 1);
                t[x][z] = base + swell + bump;
            }
        t = blur(t, 2);
        int[] heights = new int[sx * sz];
        int maxCut = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        double sum = 0;
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++) {
                double target = t[x][z];
                if (entrance != null) {
                    double d = Math.max(Math.abs(minX + x - entrance.getX()), Math.abs(minZ + z - entrance.getZ()));
                    double w = Math.clamp((d - 2) / 6.0, 0, 1);
                    target = base + (target - base) * w;
                }
                int y = (int) Math.round(target);
                heights[x * sz + z] = y;
                int natural = (int) Math.round(h[x][z]);
                maxCut = Math.max(maxCut, Math.abs(natural - y));
                sum += Math.abs(natural - y);
                lo = Math.min(lo, natural); hi = Math.max(hi, natural);
            }
        Field field = new Field(minX, minZ, sx, sz, heights);
        return new Plan(field, maxCut, sum / heights.length, hi - lo, waterCells / (double) (gx * gz));
    }

    private static double[][] blur(double[][] a, int r) {
        int sx = a.length, sz = a[0].length;
        double[][] rows = new double[sx][sz], out = new double[sx][sz];
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++) {
                double s = 0; int n = 0;
                for (int d = -r; d <= r; d++) { int xx = Math.clamp(x + d, 0, sx - 1); s += a[xx][z]; n++; }
                rows[x][z] = s / n;
            }
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++) {
                double s = 0; int n = 0;
                for (int d = -r; d <= r; d++) { int zz = Math.clamp(z + d, 0, sz - 1); s += rows[x][zz]; n++; }
                out[x][z] = s / n;
            }
        return out;
    }

    static int[] copy(int[] a) { return Arrays.copyOf(a, a.length); }
}
