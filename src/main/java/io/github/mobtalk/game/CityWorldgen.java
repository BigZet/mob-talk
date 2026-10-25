package io.github.mobtalk.game;

import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.game.mixin.StructureSetAccessor;
import io.github.mobtalk.server.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

/** Cities with Soviet panel blocks (tools/city): they take the place of a village, one city for every {@code cityPerVillages}
 * villages. The cities join the vanilla villages' structure set, so they share its spacing and never overlap a village. The set
 * picks among the structures that fit the biome by weight; a city's weight is the villages' weight there divided by the ratio,
 * counted after the village mods (CTOV adds its villages to the same set on server start), so the ratio holds with or without them. */
public final class CityWorldgen {
    private CityWorldgen() { }
    public static final List<String> KINDS = List.of("small", "mix", "brick", "ninth");
    private static final Identifier VILLAGES = Identifier.ofVanilla("villages");
    private static final TagKey<Biome> CITY_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of("mobtalk", "has_structure/city"));
    /** Every weight already in the set is multiplied by this, so the cities' share needs no rounding to whole villages. */
    private static final int SCALE = 60;
    private static StructureSet patchedSet;
    private static List<StructureSet.WeightedEntry> original = List.of();

    public static void register() {
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() == World.OVERWORLD) apply(server.getRegistryManager(), ServerConfig.get().cityPerVillages);
        });
    }

    /** Puts the cities into the villages' set with the weight for one city per {@code perVillages} villages; 0 takes them out. */
    static synchronized void apply(DynamicRegistryManager registries, int perVillages) {
        Registry<StructureSet> sets = registries.get(RegistryKeys.STRUCTURE_SET);
        Registry<Structure> structures = registries.get(RegistryKeys.STRUCTURE);
        Registry<Biome> biomes = registries.get(RegistryKeys.BIOME);
        StructureSet villages = sets.get(VILLAGES);
        if (villages == null) return;
        List<RegistryEntry<Structure>> cities = new ArrayList<>();
        for (String kind : KINDS) structures.getEntry(RegistryKey.of(RegistryKeys.STRUCTURE, Identifier.of("mobtalk", "city_" + kind))).ifPresent(cities::add);
        // The set as the village mods left it, remembered on the first load of this server's registries (later loads find our entries).
        if (villages != patchedSet) { patchedSet = villages; original = List.copyOf(villages.structures()); }
        List<StructureSet.WeightedEntry> others = original;
        // The villages' weight in an average city biome: what a city competes with where it may stand.
        double sum = 0;
        int counted = 0;
        for (RegistryEntry<Biome> biome : biomes.iterateEntries(CITY_BIOMES)) {
            int weight = 0;
            for (StructureSet.WeightedEntry entry : others) if (entry.structure().value().getValidBiomes().contains(biome)) weight += entry.weight();
            if (weight > 0) { sum += weight; counted++; }
        }
        List<StructureSet.WeightedEntry> result = new ArrayList<>();
        for (StructureSet.WeightedEntry entry : others) result.add(new StructureSet.WeightedEntry(entry.structure(), entry.weight() * SCALE));
        int each = 0;
        if (perVillages > 0 && counted > 0 && !cities.isEmpty()) {
            each = Math.max(1, (int) Math.round(sum / counted * SCALE / perVillages / cities.size()));
            for (RegistryEntry<Structure> city : cities) result.add(new StructureSet.WeightedEntry(city, each));
        }
        ((StructureSetAccessor) (Object) villages).mobtalk$setStructures(List.copyOf(result));
        MobTalkMod.LOGGER.info("Mob Talk: города — {} видов, вес каждого {} против деревень {} (в среднем по биомам города, ×{}); один город на {} деревни",
                cities.size(), each, Math.round(sum / Math.max(1, counted) * SCALE), SCALE, perVillages);
    }
}
