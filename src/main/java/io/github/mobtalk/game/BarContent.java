package io.github.mobtalk.game;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.world.poi.PointOfInterestType;
import java.util.ArrayList;
import java.util.List;

/** The village bar's own content: its furniture (models from tools/bar/bar.py), the seat a sitting villager rides, and two points of
 * interest by which {@link BarEvenings} finds a bar — its seats (stools and chairs) and its counter. Registered before any world
 * loads, on both sides. */
public final class BarContent {
    private BarContent() { }
    public static final Identifier SEAT_POI_ID = Identifier.of("mobtalk", "bar_seat"), COUNTER_POI_ID = Identifier.of("mobtalk", "bar_counter");
    public static final RegistryKey<PointOfInterestType> SEAT_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, SEAT_POI_ID);
    public static final RegistryKey<PointOfInterestType> COUNTER_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, COUNTER_POI_ID);
    public static BarFurnitureBlock COUNTER, STOOL, TABLE, CHAIR, KEG, SHELF, BOTTLES, MUGS;
    public static EntityType<BarSeatEntity> SEAT;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        AbstractBlock.Settings wood = AbstractBlock.Settings.create().mapColor(MapColor.BROWN).strength(1.5f).sounds(BlockSoundGroup.WOOD).nonOpaque();
        AbstractBlock.Settings glass = AbstractBlock.Settings.create().mapColor(MapColor.CLEAR).strength(0.3f).sounds(BlockSoundGroup.GLASS).nonOpaque()
                .noCollision().pistonBehavior(PistonBehavior.DESTROY);
        List<Item> items = new ArrayList<>();
        COUNTER = block("bar_counter", new BarFurnitureBlock(wood, 0, true, new double[] { 0, 0, 0, 16, 16, 16 }), items);
        STOOL = block("bar_stool", new BarFurnitureBlock(wood, 13.5 / 16, true, new double[] { 4, 0, 4, 12, 13.5, 12 }), items);
        TABLE = block("bar_table", new BarFurnitureBlock(wood, 0, true, new double[] { 1, 0, 1, 15, 15, 15 }), items);
        CHAIR = block("bar_chair", new BarFurnitureBlock(wood, 9.5 / 16, true, new double[] { 2, 0, 2, 14, 9.5, 14 }, new double[] { 2, 9.5, 12.5, 14, 21, 14 }), items);
        KEG = block("bar_keg", new BarFurnitureBlock(wood, 0, true, new double[] { 2, 0, 1, 14, 14, 15 }), items);
        SHELF = block("bar_shelf", new BarFurnitureBlock(wood, 0, true, new double[] { 0, 0, 10, 16, 16, 16 }), items);
        BOTTLES = block("bar_bottles", new BarFurnitureBlock(glass, 0, false, new double[] { 2, 0, 4, 15, 10, 12 }), items);
        MUGS = block("bar_mugs", new BarFurnitureBlock(glass, 0, false, new double[] { 2, 0, 3, 15, 6, 13 }), items);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
        PointOfInterestHelper.register(SEAT_POI_ID, 1, 1, STOOL, CHAIR);
        PointOfInterestHelper.register(COUNTER_POI_ID, 1, 1, COUNTER);
        SEAT = Registry.register(Registries.ENTITY_TYPE, Identifier.of("mobtalk", "bar_seat"),
                EntityType.Builder.<BarSeatEntity>create(BarSeatEntity::new, SpawnGroup.MISC).dimensions(0.25f, 0.25f).maxTrackingRange(10).disableSummon().build("mobtalk:bar_seat"));
    }

    private static BarFurnitureBlock block(String name, BarFurnitureBlock block, List<Item> items) {
        Identifier id = Identifier.of("mobtalk", name);
        Registry.register(Registries.BLOCK, id, block);
        items.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        return block;
    }
}
