package io.github.mobtalk.game.city;

import io.github.mobtalk.game.CityWorldgen;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.WorldView;

/** The one answer to "is this the city?" for every track (police, bandits, city women, officials; spec §6.2 item 5): the city
 * biome, or, for worlds from before Cities 2.0, an old city's structure. */
public final class CityZone {
    private CityZone() { }

    /** In a city's biome (its streets, its levelled margin and the land round it up to the biome's edge) or an old city. */
    public static boolean at(WorldView world, BlockPos pos) {
        if (world.getBiome(pos).matchesKey(CityMap.CITY)) return true;
        return world instanceof StructureWorldAccess access && CityWorldgen.inCity(access, pos);
    }

    /** Within a city's streets (the built-up square, ring road included). */
    public static boolean builtUp(ServerWorld world, BlockPos pos) {
        CityMap.City city = CityMap.cityIn(world, pos.getX(), pos.getZ());
        return city != null && Math.max(Math.abs(pos.getX() - city.x), Math.abs(pos.getZ() - city.z)) <= city.radius;
    }

    /** The city whose biome holds this point, or null. */
    public static CityMap.City city(ServerWorld world, BlockPos pos) {
        CityMap.City city = CityMap.cityIn(world, pos.getX(), pos.getZ());
        return city != null && city.reach(pos.getX(), pos.getZ()) <= city.biomeRadius ? city : null;
    }
}
