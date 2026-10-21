package io.github.divineblock.cemetery;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.TreeConfiguredFeatures;
import net.minecraft.world.gen.feature.util.FeatureContext;

/** A tree that grows on a Russian cemetery: oak or birch, mostly tall ones; spruce, pine or birch in taiga and the cold
 * biomes. Never anything exotic. */
public class CemeteryTreeFeature extends Feature<DefaultFeatureConfig> {
    public CemeteryTreeFeature() { super(DefaultFeatureConfig.CODEC); }

    @Override public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        StructureWorldAccess world = context.getWorld();
        BlockPos pos = context.getOrigin();
        RegistryKey<ConfiguredFeature<?, ?>> tree = pick(world.getBiome(pos), context.getRandom());
        return world.getRegistryManager().get(RegistryKeys.CONFIGURED_FEATURE).getEntry(tree)
                .map(entry -> entry.value().generate(world, context.getGenerator(), context.getRandom(), pos)).orElse(false);
    }

    static RegistryKey<ConfiguredFeature<?, ?>> pick(RegistryEntry<Biome> biome, Random random) {
        float roll = random.nextFloat();
        // Old cemetery trees stand tall over the fences: tall birches and pines more often than young ones.
        if (biome.isIn(BiomeTags.IS_TAIGA) || biome.value().getTemperature() < 0.3f)
            return roll < 0.3f ? TreeConfiguredFeatures.SPRUCE : roll < 0.55f ? TreeConfiguredFeatures.PINE
                    : roll < 0.85f ? TreeConfiguredFeatures.SUPER_BIRCH_BEES_0002 : TreeConfiguredFeatures.BIRCH;
        return roll < 0.25f ? TreeConfiguredFeatures.FANCY_OAK : roll < 0.45f ? TreeConfiguredFeatures.OAK
                : roll < 0.85f ? TreeConfiguredFeatures.SUPER_BIRCH_BEES_0002 : TreeConfiguredFeatures.BIRCH;
    }
}
