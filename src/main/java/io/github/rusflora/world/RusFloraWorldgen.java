package io.github.rusflora.world;

import io.github.rusflora.RusFlora;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.placementmodifier.PlacementModifierType;

/** Registers the shrub feature and puts each placed feature (data/rusflora/worldgen/placed_feature) only into the biomes where
 * the shrub really grows in the Russian middle belt: no hazel in deserts, no willows in jungles. */
public final class RusFloraWorldgen {
    public static final ShrubFeature SHRUB = new ShrubFeature();
    public static final PlacementModifierType<NearWaterPlacement> NEAR_WATER = () -> NearWaterPlacement.CODEC;
    public static final PlacementModifierType<NearVillagePlacement> NEAR_VILLAGE = () -> NearVillagePlacement.CODEC;

    private static final Predicate<BiomeSelectionContext> BROADLEAF = BiomeSelectors.includeByKey(BiomeKeys.FOREST, BiomeKeys.FLOWER_FOREST,
            BiomeKeys.BIRCH_FOREST, BiomeKeys.OLD_GROWTH_BIRCH_FOREST, BiomeKeys.DARK_FOREST);
    private static final Predicate<BiomeSelectionContext> OPEN = BiomeSelectors.includeByKey(BiomeKeys.PLAINS, BiomeKeys.SUNFLOWER_PLAINS, BiomeKeys.MEADOW);
    private static final Predicate<BiomeSelectionContext> TAIGA = BiomeSelectors.includeByKey(BiomeKeys.TAIGA, BiomeKeys.OLD_GROWTH_PINE_TAIGA,
            BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA, BiomeKeys.SNOWY_TAIGA);
    private static final Predicate<BiomeSelectionContext> HILLS = BiomeSelectors.includeByKey(BiomeKeys.WINDSWEPT_HILLS, BiomeKeys.WINDSWEPT_FOREST,
            BiomeKeys.WINDSWEPT_GRAVELLY_HILLS, BiomeKeys.GROVE, BiomeKeys.MEADOW);
    private static final Predicate<BiomeSelectionContext> WET = BiomeSelectors.includeByKey(BiomeKeys.RIVER, BiomeKeys.SWAMP);
    private static final Predicate<BiomeSelectionContext> VILLAGES = BiomeSelectors.includeByKey(BiomeKeys.PLAINS, BiomeKeys.SUNFLOWER_PLAINS,
            BiomeKeys.MEADOW, BiomeKeys.TAIGA, BiomeKeys.SNOWY_PLAINS, BiomeKeys.FOREST);

    private RusFloraWorldgen() { }

    public static void register() {
        Registry.register(Registries.FEATURE, RusFlora.id("shrub"), SHRUB);
        Registry.register(Registries.PLACEMENT_MODIFIER_TYPE, RusFlora.id("near_water"), NEAR_WATER);
        Registry.register(Registries.PLACEMENT_MODIFIER_TYPE, RusFlora.id("near_village"), NEAR_VILLAGE);
        add("hazel_forest", BROADLEAF);
        add("hazel_open", OPEN);
        add("dog_rose_open", OPEN);
        add("dog_rose_forest", BROADLEAF);
        add("bird_cherry_water", BROADLEAF.or(OPEN).or(TAIGA).or(WET));
        add("red_elder", TAIGA.or(BROADLEAF));
        add("juniper_taiga", TAIGA);
        add("juniper_hills", HILLS);
        add("willow_water", BROADLEAF.or(OPEN).or(TAIGA).or(WET));
        add("lilac_village", VILLAGES);
        add("white_lilac_village", VILLAGES);
        add("lilac_wild", BiomeSelectors.includeByKey(BiomeKeys.PLAINS, BiomeKeys.FLOWER_FOREST));
    }

    private static void add(String placed, Predicate<BiomeSelectionContext> biomes) {
        RegistryKey<PlacedFeature> key = RegistryKey.of(RegistryKeys.PLACED_FEATURE, RusFlora.id(placed));
        BiomeModifications.addFeature(biomes, GenerationStep.Feature.VEGETAL_DECORATION, key);
    }

}
