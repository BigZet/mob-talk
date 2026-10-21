package io.github.rusflora;

import io.github.rusflora.block.ShrubKind;
import io.github.rusflora.block.ShrubLeavesBlock;
import io.github.rusflora.block.ShrubSaplingBlock;
import io.github.rusflora.block.ShrubStemBlock;
import io.github.rusflora.hogweed.Hogweed;
import io.github.rusflora.world.RusFloraWorldgen;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.registry.CompostingChanceRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the shrubs of the Russian middle belt: for each kind its leaves, bare stems and sapling, the fruit, and their
 * place in world generation. Everything lives on the common side, so the mod works on a server as well. */
public final class RusFlora implements ModInitializer {
    public static final String ID = "rusflora";
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    private static final Map<ShrubKind, ShrubLeavesBlock> LEAVES = new EnumMap<>(ShrubKind.class);
    private static final Map<ShrubKind, ShrubStemBlock> STEMS = new EnumMap<>(ShrubKind.class);
    private static final Map<ShrubKind, ShrubSaplingBlock> SAPLINGS = new EnumMap<>(ShrubKind.class);
    private static final Map<ShrubKind, Item> FRUITS = new EnumMap<>(ShrubKind.class);

    public static Identifier id(String path) { return Identifier.of(ID, path); }
    public static ShrubLeavesBlock leaves(ShrubKind kind) { return LEAVES.get(kind); }
    public static ShrubStemBlock stem(ShrubKind kind) { return STEMS.get(kind); }
    public static ShrubSaplingBlock sapling(ShrubKind kind) { return SAPLINGS.get(kind); }
    public static Item fruit(ShrubKind kind) { return FRUITS.get(kind); }

    @Override public void onInitialize() {
        for (ShrubKind kind : ShrubKind.values()) {
            MapColor color = kind == ShrubKind.WILLOW ? MapColor.LICHEN_GREEN : MapColor.DARK_GREEN;
            ShrubLeavesBlock leaves = block(kind.id + "_leaves", new ShrubLeavesBlock(kind, AbstractBlock.Settings.create().mapColor(color)
                    .strength(0.2f).sounds(BlockSoundGroup.AZALEA_LEAVES).noCollision().nonOpaque().ticksRandomly().burnable()
                    .allowsSpawning((s, w, p, t) -> false).suffocates((s, w, p) -> false).blockVision((s, w, p) -> false)
                    .solidBlock((s, w, p) -> false).pistonBehavior(PistonBehavior.DESTROY)));
            ShrubStemBlock stem = block(kind.id + "_stems", new ShrubStemBlock(kind, AbstractBlock.Settings.create().mapColor(MapColor.BROWN)
                    .strength(0.6f).sounds(BlockSoundGroup.WOOD).noCollision().nonOpaque().burnable().pistonBehavior(PistonBehavior.DESTROY)));
            ShrubSaplingBlock sapling = block(kind.id + "_sapling", new ShrubSaplingBlock(kind, AbstractBlock.Settings.create().mapColor(MapColor.DARK_GREEN)
                    .noCollision().ticksRandomly().breakInstantly().sounds(BlockSoundGroup.GRASS).pistonBehavior(PistonBehavior.DESTROY)));
            LEAVES.put(kind, leaves); STEMS.put(kind, stem); SAPLINGS.put(kind, sapling);
            FlammableBlockRegistry.getDefaultInstance().add(leaves, 30, 60);
            FlammableBlockRegistry.getDefaultInstance().add(stem, 5, 20);
            CompostingChanceRegistry.INSTANCE.add(leaves, 0.3f);
            CompostingChanceRegistry.INSTANCE.add(sapling, 0.3f);
            if (kind.fruits()) {
                Item fruit = Registry.register(Registries.ITEM, id(kind.fruit), new Item(new Item.Settings().food(food(kind))));
                FRUITS.put(kind, fruit);
                CompostingChanceRegistry.INSTANCE.add(fruit, 0.3f);
            }
        }
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> {
            for (ShrubKind kind : ShrubKind.values()) {
                entries.add(SAPLINGS.get(kind)); entries.add(LEAVES.get(kind)); entries.add(STEMS.get(kind));
            }
        });
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FOOD_AND_DRINK).register(entries -> FRUITS.values().forEach(entries::add));
        RusFloraWorldgen.register();
        Hogweed.register();
        LOG.info("Русская флора загружена: {} видов кустов", ShrubKind.values().length);
    }

    private static <B extends Block> B block(String name, B block) {
        Registry.register(Registries.BLOCK, id(name), block);
        Registry.register(Registries.ITEM, id(name), new BlockItem(block, new Item.Settings()));
        return block;
    }

    /** Nuts are the most filling; raw red elderberries are mildly poisonous, as in life. */
    private static FoodComponent food(ShrubKind kind) {
        return switch (kind) {
            case HAZEL -> new FoodComponent.Builder().nutrition(3).saturationModifier(0.5f).build();
            case DOG_ROSE -> new FoodComponent.Builder().nutrition(2).saturationModifier(0.3f).snack().build();
            case BIRD_CHERRY -> new FoodComponent.Builder().nutrition(2).saturationModifier(0.2f).snack().build();
            case RED_ELDER -> new FoodComponent.Builder().nutrition(1).saturationModifier(0.1f).snack()
                    .statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 200), 1f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.POISON, 60), 0.4f).build();
            default -> new FoodComponent.Builder().nutrition(1).saturationModifier(0.1f).snack().build();
        };
    }
}
