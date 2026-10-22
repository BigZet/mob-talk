package io.github.rusflora.hogweed;

import io.github.rusflora.RusFlora;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.registry.CompostingChanceRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.registry.LandPathNodeTypesRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.CowEntity;
import io.github.rusflora.mixin.MobEntityAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AliasedBlockItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.PlacedFeature;

/** Sosnowsky's hogweed: the plant, its seeds, hollow stalk and dry umbel, the sap and the sunburn it causes, and the patches
 * world generation starts on plains and in oak and birch forests; cows eat it, villagers mow it. All on the common side, so it runs on a server. */
public final class Hogweed {
    public static final HogweedBlock BLOCK = new HogweedBlock(AbstractBlock.Settings.create().mapColor(MapColor.DARK_GREEN)
            .strength(0.3f).sounds(BlockSoundGroup.GRASS).noCollision().nonOpaque().ticksRandomly().burnable().offset(AbstractBlock.OffsetType.XZ)
           .allowsSpawning((s, w, p, t) -> false).pistonBehavior(PistonBehavior.DESTROY));
    public static final DryUmbelBlock DRY_UMBEL = new DryUmbelBlock(AbstractBlock.Settings.create().mapColor(MapColor.BROWN)
            .breakInstantly().sounds(BlockSoundGroup.GRASS).noCollision().nonOpaque().burnable().pistonBehavior(PistonBehavior.DESTROY));
    public static final Item SEEDS = new AliasedBlockItem(BLOCK, new Item.Settings());
    public static final Item HOLLOW_STALK = new Item(new Item.Settings());
    public static final RegistryKey<DamageType> BURN_DAMAGE = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, RusFlora.id("hogweed_burn"));
    public static final Feature<DefaultFeatureConfig> PATCH = new HogweedPatchFeature();
    public static RegistryEntry<StatusEffect> SAP, BURN;
    private static final Map<LivingEntity, Long> LAST_SPLASH = new WeakHashMap<>();

    private Hogweed() { }

    public static final net.minecraft.world.gen.placementmodifier.PlacementModifierType<NearWaterPlacement> NEAR_WATER = () -> NearWaterPlacement.CODEC;

    public static void register() {
        HogweedConfig.load();
        Registry.register(Registries.BLOCK, RusFlora.id("hogweed"), BLOCK);
        Registry.register(Registries.ITEM, RusFlora.id("hogweed_seeds"), SEEDS);
        Registry.register(Registries.ITEM, RusFlora.id("hogweed_stalk"), HOLLOW_STALK);
        Registry.register(Registries.BLOCK, RusFlora.id("dry_hogweed_umbel"), DRY_UMBEL);
        Registry.register(Registries.ITEM, RusFlora.id("dry_hogweed_umbel"), new BlockItem(DRY_UMBEL, new Item.Settings()));
        SAP = Registry.registerReference(Registries.STATUS_EFFECT, RusFlora.id("hogweed_sap"), new SapEffect());
        BURN = Registry.registerReference(Registries.STATUS_EFFECT, RusFlora.id("hogweed_burn"), new BurnEffect());

        // Burns like dry grass and passes the fire on fast: the highest catch and burn chances vanilla uses.
        FlammableBlockRegistry.getDefaultInstance().add(BLOCK, 100, 100);
        FlammableBlockRegistry.getDefaultInstance().add(DRY_UMBEL, 100, 100);
        FuelRegistry.INSTANCE.add(HOLLOW_STALK, 100);
        FuelRegistry.INSTANCE.add(DRY_UMBEL, 100);
        CompostingChanceRegistry.INSTANCE.add(SEEDS, 0.3f);
        // Mobs and villagers walk around a thicket, as they do around a berry bush.
        LandPathNodeTypesRegistry.register(BLOCK, PathNodeType.DAMAGE_OTHER, PathNodeType.DANGER_OTHER);

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof CowEntity cow)) return;
            var goals = ((MobEntityAccessor) cow).rusflora$goalSelector();
            if (goals.getGoals().stream().noneMatch(g -> g.getGoal() instanceof CowEatHogweedGoal)) goals.add(4, new CowEatHogweedGoal(cow));
        });

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> {
            entries.add(SEEDS); entries.add(HOLLOW_STALK); entries.add(DRY_UMBEL);
        });

        Registry.register(Registries.FEATURE, RusFlora.id("hogweed_patch"), PATCH);
        Registry.register(Registries.PLACEMENT_MODIFIER_TYPE, RusFlora.id("near_water"), NEAR_WATER);
        // Only plains and the classic oak and birch forests; on plains four times rarer than in the forests.
        add("hogweed_plains", BiomeSelectors.includeByKey(BiomeKeys.PLAINS, BiomeKeys.SUNFLOWER_PLAINS));
        add("hogweed_forest", BiomeSelectors.includeByKey(BiomeKeys.FOREST, BiomeKeys.BIRCH_FOREST));
    }

    private static void add(String placed, Predicate<BiomeSelectionContext> biomes) {
        BiomeModifications.addFeature(biomes, GenerationStep.Feature.VEGETAL_DECORATION, RegistryKey.of(RegistryKeys.PLACED_FEATURE, RusFlora.id(placed)));
    }

    /** Sap gets on whoever brushes against or breaks a living plant, unless legs and body are covered, they are undead or an
     * animal. Its effect shows the countdown to the burn. */
    public static void splashSap(LivingEntity entity) {
        if (entity == null || !HogweedConfig.get().burns || entity.getWorld().isClient) return;
        if (entity instanceof PlayerEntity player && (player.isCreative() || player.isSpectator())) return;
        if (entity.getType().isIn(EntityTypeTags.UNDEAD) || entity instanceof AnimalEntity) return; // cows graze it unharmed
        if (!entity.getEquippedStack(EquipmentSlot.CHEST).isEmpty() && !entity.getEquippedStack(EquipmentSlot.LEGS).isEmpty()) return;
        // Fresh contact keeps the clock where it was, so walking through a thicket does not postpone the burn forever; it only
        // makes the dose (amplifier) stronger, at most once every two seconds.
        long now = entity.getWorld().getTime();
        Long last = LAST_SPLASH.get(entity);
        if (last != null && now - last < 40 && now >= last) return;
        LAST_SPLASH.put(entity, now);
        StatusEffectInstance had = entity.getStatusEffect(SAP);
        int amplifier = had == null ? 0 : Math.min(2, had.getAmplifier() + 1);
        int duration = had == null ? SapEffect.duration() : had.getDuration();
        entity.removeStatusEffect(SAP);
        entity.addStatusEffect(new StatusEffectInstance(SAP, duration, amplifier, false, false, true));
    }
}
