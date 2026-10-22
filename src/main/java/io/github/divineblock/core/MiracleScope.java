package io.github.divineblock.core;

import java.util.Collection;
import java.util.List;

/** Who an act falls on when several mortals share the world. A personal act (mercy, effects, gifts, the trip home, a curse on
 * the mortal, the court, the ban) touches the one who prayed and nobody else; a world act (weather, time, the land, fire from
 * the sky, the great beasts) falls on the place, and everyone standing there suffers with him. Plain Java, for the tests. */
public enum MiracleScope {
    PERSONAL("личное"), WORLD("мировое");

    public final String label;
    MiracleScope(String label) { this.label = label; }

    public static MiracleScope of(Miracle miracle) {
        return switch (miracle) {
            // Weather, time and poison from the sky: the whole sky changes, not one mortal's.
            case WEATHER_CLEAR, TIME_RUSH, ACID_RAIN,
                 // Fire and stone from the sky and the land torn up: whoever stands there is hit.
                 FIRE_RING, METEOR, ANVIL_RAIN, EARTHQUAKE, SINKHOLE, TNT_RAIN, METEOR_SHOWER, CHUNK_VOID, TORNADO, RIFTS, GREAT_CRATER,
                 // Beasts that spare nobody, and the god himself with the mace.
                 WITHER, DRAGON, GOD_MACE,
                 // The great biblical punishments lie on the land for days.
                 BLIGHT, PLAGUES, SODOM, FLOOD, BABEL -> WORLD;
            default -> PERSONAL;
        };
    }

    /** Who suffers the act: the one who prayed alone, or everyone online. */
    public static <P> List<P> targets(Miracle miracle, P asker, Collection<P> online) {
        return of(miracle) == PERSONAL ? List.of(asker) : List.copyOf(online);
    }

    /** Where «домой» leads: the asker's own respawn point when his world still exists, else the world spawn. */
    public static <W, B> Home<W, B> home(B spawnPoint, W spawnWorld, W defaultWorld, B worldSpawn) {
        return spawnPoint != null && spawnWorld != null ? new Home<>(spawnWorld, spawnPoint, true) : new Home<>(defaultWorld, worldSpawn, false);
    }
    public record Home<W, B>(W world, B pos, boolean own) { }
}
