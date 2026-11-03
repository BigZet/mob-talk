package io.github.mobtalk.game.city;

import io.github.mobtalk.core.MobTalkConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

/** Where the cities stand (spec §6.2): the overworld is cut into squares of {@code citySpacing}; a square holds a city with
 * {@code cityChance} percent, its centre shifted by the seed so far in that the city's biome stays inside the square. A centre over
 * an ocean, a river or the mountains (the vanilla biomes there) has no city. Everything follows from the world seed and the settings
 * the world was loaded with, so every chunk, on any thread and after a restart, sees the same cities. */
public final class CityMap {
    private CityMap() { }
    public static final RegistryKey<Biome> CITY = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("mobtalk", "city"));

    /** A city: its centre, seed, built-up radius (the streets' square), biome radius, and the ground level it is levelled to. */
    public static final class City {
        public final int x, z, radius, biomeRadius;
        public final long seed;
        private volatile int groundY = Integer.MIN_VALUE;
        private volatile CityPlan plan;
        City(int x, int z, long seed, int radius, int biomeRadius) {
            this.x = x; this.z = z; this.seed = seed; this.radius = radius; this.biomeRadius = biomeRadius;
        }
        /** The rounded "radius" of a point from the centre: (dx⁴+dz⁴)^¼, a square with rounded corners, so the square city keeps
         * a margin at its corners as wide as the round biome around it would give. */
        public double reach(int wx, int wz) { return reach4(wx - x, wz - z); }
        /** Up to here the ground is level: the streets' square and its corners with a little room. */
        public double flatReach() { return Math.min((radius + 8) * 1.19, biomeRadius - 60); }
        public CityPlan plan() {
            CityPlan p = plan;
            if (p == null) synchronized (this) { if ((p = plan) == null) plan = p = new CityPlan(this); }
            return p;
        }
        public int groundY() { return groundY; }
        @Override public String toString() { return "City[" + x + ", " + z + "]"; }
    }

    static double reach4(double dx, double dz) {
        double a = dx * dx, b = dz * dz;
        return Math.sqrt(Math.sqrt(a * a + b * b));
    }

    private static final City NONE = new City(0, 0, 0, 0, 0);
    private static final ConcurrentHashMap<Long, City> CELLS = new ConcurrentHashMap<>();
    /** The vanilla biome is wanted while a centre is checked: the injection steps aside on this thread. */
    private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial(() -> false);
    private static volatile BiomeSource overworld;
    private static volatile RegistryEntry<Biome> biome;
    private static volatile boolean enabled;
    private static volatile long worldSeed;
    private static volatile int spacing, chance, radius, biomeRadius;

    /** Set by {@link CityGen} when the server starts: the biome entry from the server's registries. */
    public static void setBiome(RegistryEntry<Biome> entry) { biome = entry; }
    public static RegistryEntry<Biome> biome() { return biome; }

    /** Set when the overworld loads: its biome source and seed, and the settings the cities follow until the world closes. */
    public static void load(BiomeSource source, long seed, MobTalkConfig config) {
        CELLS.clear();
        worldSeed = seed;
        radius = MathHelper.clamp(config.cityRadius, 200, 500);
        biomeRadius = MathHelper.clamp(config.cityBiomeRadius, radius + 100, 1000);
        spacing = Math.max(config.citySpacing, 2 * biomeRadius + 256);
        chance = MathHelper.clamp(config.cityChance, 0, 100);
        enabled = config.cities && chance > 0 && biome != null;
        overworld = source;
    }

    public static void unload() {
        overworld = null;
        enabled = false;
        CELLS.clear();
    }

    public static boolean enabled() { return enabled; }

    /** The city biome for a point of the overworld's biome source within a city's biome, otherwise null (the vanilla biome). */
    public static RegistryEntry<Biome> biomeAt(BiomeSource source, int wx, int wz, MultiNoiseUtil.MultiNoiseSampler noise) {
        if (!enabled || source != overworld || CHECKING.get()) return null;
        City city = cityIn(wx, wz, source, noise);
        return city != null && city.reach(wx, wz) <= city.biomeRadius ? biome : null;
    }

    /** The city whose square holds this point, if the square has one (checked against the vanilla biomes on first use). */
    public static City cityIn(int wx, int wz, BiomeSource source, MultiNoiseUtil.MultiNoiseSampler noise) {
        if (!enabled) return null;
        int cx = Math.floorDiv(wx, spacing), cz = Math.floorDiv(wz, spacing);
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        City city = CELLS.get(key);
        if (city == null) {
            city = check(cx, cz, source, noise);
            CELLS.putIfAbsent(key, city);
        }
        return city == NONE ? null : city;
    }

    /** A city near a point for code that has a world at hand (no biome source of its own). */
    public static City cityIn(ServerWorld world, int wx, int wz) {
        if (!enabled) return null;
        return cityIn(wx, wz, world.getChunkManager().getChunkGenerator().getBiomeSource(),
                world.getChunkManager().getNoiseConfig().getMultiNoiseSampler());
    }

    /** The cities in the squares around a point, nearest first. */
    public static List<City> near(ServerWorld world, int wx, int wz, int squares) {
        List<City> found = new ArrayList<>();
        if (!enabled) return found;
        int cx = Math.floorDiv(wx, spacing), cz = Math.floorDiv(wz, spacing);
        for (int i = -squares; i <= squares; i++)
            for (int j = -squares; j <= squares; j++) {
                City c = cityIn(world, (cx + i) * spacing + spacing / 2, (cz + j) * spacing + spacing / 2);
                if (c != null) found.add(c);
            }
        found.sort((a, b) -> Double.compare(Math.hypot(a.x - wx, a.z - wz), Math.hypot(b.x - wx, b.z - wz)));
        return found;
    }

    private static long mix(long h) {
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return h;
    }

    private static City check(int cx, int cz, BiomeSource source, MultiNoiseUtil.MultiNoiseSampler noise) {
        long h = mix(worldSeed ^ mix(cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + 0x6d6f6274616c6bL));
        if (Math.floorMod(h, 100) >= chance) return NONE;
        int margin = biomeRadius + 32, room = spacing - 2 * margin;
        int x = cx * spacing + margin + (int) Math.floorMod(mix(h + 1), room + 1);
        int z = cz * spacing + margin + (int) Math.floorMod(mix(h + 2), room + 1);
        CHECKING.set(true);
        try {
            if (bad(source.getBiome(x >> 2, 16, z >> 2, noise), true)) return NONE;
            int poor = 0;
            for (int ring : new int[] { radius / 2, radius - 40 })
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI / 4 * k + (ring == radius / 2 ? 0 : Math.PI / 8);
                    int px = x + (int) Math.round(Math.cos(a) * ring), pz = z + (int) Math.round(Math.sin(a) * ring);
                    if (bad(source.getBiome(px >> 2, 16, pz >> 2, noise), false)) poor++;
                }
            if (poor >= 5) return NONE;
        } finally {
            CHECKING.set(false);
        }
        return new City(x, z, mix(h + 3), radius, biomeRadius);
    }

    private static boolean bad(RegistryEntry<Biome> b, boolean centre) {
        if (b.isIn(BiomeTags.IS_OCEAN) || b.isIn(BiomeTags.IS_DEEP_OCEAN) || b.isIn(BiomeTags.IS_MOUNTAIN)) return true;
        return centre && b.isIn(BiomeTags.IS_RIVER);
    }

    /** The level the city is cut and filled to: the median of the natural ground at nine points of its centre, held between the
     * sea and 40 blocks above it. Counted once, by whichever chunk asks first. */
    public static int groundY(City city, ChunkGenerator generator, NoiseConfig noise, net.minecraft.world.HeightLimitView view, int seaLevel) {
        int g = city.groundY;
        if (g != Integer.MIN_VALUE) return g;
        synchronized (city) {
            if (city.groundY != Integer.MIN_VALUE) return city.groundY;
            int[] hs = new int[9];
            int n = 0;
            for (int i = -1; i <= 1; i++)
                for (int j = -1; j <= 1; j++)
                    hs[n++] = generator.getHeight(city.x + i * 120, city.z + j * 120, Heightmap.Type.OCEAN_FLOOR_WG, view, noise);
            Arrays.sort(hs);
            city.groundY = MathHelper.clamp(hs[4] - 1, seaLevel + 1, seaLevel + 40);
            return city.groundY;
        }
    }
}
