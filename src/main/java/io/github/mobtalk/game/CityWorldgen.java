package io.github.mobtalk.game;

import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.game.mixin.StructureSetAccessor;
import io.github.mobtalk.server.ServerConfig;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
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

    /** Whether a city stands over this spot (its chunk lies in a city's bounds): the city keeps its own ground, so no wild trees
     * grow there between the blocks of flats; the yards plant their own. */
    public static boolean inCity(StructureWorldAccess world, BlockPos pos) {
        Map<Structure, ?> refs = world.getChunk(pos).getStructureReferences();
        if (refs.isEmpty()) return false;
        Registry<Structure> structures = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        for (Structure structure : refs.keySet()) {
            Identifier id = structures.getId(structure);
            if (id != null && id.getNamespace().equals("mobtalk") && id.getPath().startsWith("city_")) return true;
        }
        return false;
    }

    public static void register() {
        // The cities must be in the set before the overworld is built: it notes then which structures each set may place, and /locate
        // and the structure checks go by that note. The weights are counted again once the world loads, after the village mods.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> apply(server.getRegistryManager(), ServerConfig.get().cityPerVillages));
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() == World.OVERWORLD) apply(server.getRegistryManager(), ServerConfig.get().cityPerVillages);
        });
    }

    /** The entries this class put into the set: cities, and other structures' entries with their weight scaled (to the original weight). */
    private static final Map<StructureSet.WeightedEntry, Integer> OURS = new IdentityHashMap<>();

    /** Puts the cities into the villages' set with the weight for one city per {@code perVillages} villages; 0 takes them out. */
    static synchronized void apply(DynamicRegistryManager registries, int perVillages) {
        Registry<StructureSet> sets = registries.get(RegistryKeys.STRUCTURE_SET);
        Registry<Structure> structures = registries.get(RegistryKeys.STRUCTURE);
        Registry<Biome> biomes = registries.get(RegistryKeys.BIOME);
        StructureSet villages = sets.get(VILLAGES);
        if (villages == null) return;
        List<RegistryEntry<Structure>> cities = new ArrayList<>();
        for (String kind : KINDS) structures.getEntry(RegistryKey.of(RegistryKeys.STRUCTURE, Identifier.of("mobtalk", "city_" + kind))).ifPresent(cities::add);
        // The set as the village mods left it: our cities out, our scaled copies back to their weight, anything new as it is.
        List<StructureSet.WeightedEntry> others = new ArrayList<>();
        for (StructureSet.WeightedEntry entry : villages.structures()) {
            Integer original = OURS.get(entry);
            if (original == null) others.add(entry);
            else if (original > 0) others.add(new StructureSet.WeightedEntry(entry.structure(), original));
        }
        // The villages' weight in an average city biome: what a city competes with where it may stand.
        double sum = 0;
        int counted = 0;
        for (RegistryEntry<Biome> biome : biomes.iterateEntries(CITY_BIOMES)) {
            int weight = 0;
            for (StructureSet.WeightedEntry entry : others) if (entry.structure().value().getValidBiomes().contains(biome)) weight += entry.weight();
            if (weight > 0) { sum += weight; counted++; }
        }
        List<StructureSet.WeightedEntry> result = new ArrayList<>();
        OURS.clear();
        for (StructureSet.WeightedEntry entry : others) {
            StructureSet.WeightedEntry scaled = new StructureSet.WeightedEntry(entry.structure(), entry.weight() * SCALE);
            OURS.put(scaled, entry.weight());
            result.add(scaled);
        }
        int each = 0;
        if (perVillages > 0 && counted > 0 && !cities.isEmpty()) {
            each = Math.max(1, (int) Math.round(sum / counted * SCALE / perVillages / cities.size()));
            for (RegistryEntry<Structure> city : cities) {
                StructureSet.WeightedEntry entry = new StructureSet.WeightedEntry(city, each);
                OURS.put(entry, 0);
                result.add(entry);
            }
        }
        ((StructureSetAccessor) (Object) villages).mobtalk$setStructures(List.copyOf(result));
        MobTalkMod.LOGGER.info("Mob Talk: города — {} видов, вес каждого {} против деревень {} (в среднем по биомам города, ×{}); один город на {} деревни",
                cities.size(), each, Math.round(sum / Math.max(1, counted) * SCALE), SCALE, perVillages);
    }
}
