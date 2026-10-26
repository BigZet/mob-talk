package io.github.mobtalk.game;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import net.minecraft.world.poi.PointOfInterestType;

/** МФЦ «Госуслуги» (track «чиновники», the city's one-window office, docs/officials.md): the hall's furniture, the ticket and the
 * staff's professions. Models, textures and the staff's clothes are made by tools/mfc/mfc.py and tools/mfc/outfits.py, to the
 * cities' scale (1 block = 1 m). The operator works at a reception window, the guard at the guard post, the administrator at the
 * «бланки» stand; the hall's logic (the ticket queue, the passport moved here from the clerk's desk) comes with the new cities. */
public final class MfcContent {
    private MfcContent() { }

    public static BarFurnitureBlock TERMINAL, BOARD, CHAIRS, FORM_DESK, INFO, COOLER, PLANT, STANCHION, PHOTO, DETECTOR, GUARD_DESK, SIGN;
    public static Window WINDOW;
    public static Item TICKET;
    public static VillagerProfession OPERATOR, GUARD, ADMIN;
    public static final Identifier WINDOW_POI_ID = Identifier.of("mobtalk", "mfc_window"), GUARD_POI_ID = Identifier.of("mobtalk", "mfc_guard_desk"),
            INFO_POI_ID = Identifier.of("mobtalk", "mfc_info");
    static final RegistryKey<PointOfInterestType> WINDOW_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, WINDOW_POI_ID),
            GUARD_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, GUARD_POI_ID), INFO_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, INFO_POI_ID);
    private static boolean registered;

    /** A reception window: its number plate (1–9) is part of the state; Shift+right-click turns the number on. */
    public static final class Window extends BarFurnitureBlock {
        public static final IntProperty NUMBER = IntProperty.of("number", 1, 9);
        Window(Settings settings, double[]... boxes) { super(settings, 0, true, boxes); setDefaultState(getDefaultState().with(NUMBER, 1)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { super.appendProperties(builder); builder.add(NUMBER); }
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (!player.isSneaking()) return ActionResult.PASS;
            if (!world.isClient) world.setBlockState(pos, state.cycle(NUMBER), Block.NOTIFY_ALL);
            return ActionResult.success(world.isClient);
        }
    }

    public static void register() {
        if (registered) return;
        registered = true;
        AbstractBlock.Settings metal = AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(2f).sounds(BlockSoundGroup.METAL).nonOpaque();
        AbstractBlock.Settings lit = AbstractBlock.Settings.create().mapColor(MapColor.BLUE).strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque().luminance(s -> 7);
        AbstractBlock.Settings wood = AbstractBlock.Settings.create().mapColor(MapColor.OAK_TAN).strength(2f).sounds(BlockSoundGroup.WOOD).nonOpaque();
        AbstractBlock.Settings thin = AbstractBlock.Settings.create().mapColor(MapColor.BLUE).strength(1f).sounds(BlockSoundGroup.METAL).nonOpaque().noCollision().luminance(s -> 5);
        AbstractBlock.Settings plant = AbstractBlock.Settings.create().mapColor(MapColor.DARK_GREEN).strength(0.6f).sounds(BlockSoundGroup.AZALEA).nonOpaque();
        List<Item> items = new ArrayList<>();
        TERMINAL = block("mfc_terminal", new BarFurnitureBlock(lit, 0, true, new double[] { 3, 0, 4, 13, 24, 12 }), items);
        BOARD = block("mfc_board", new BarFurnitureBlock(thin, 0, false, new double[] { 0, 3, 14, 16, 13, 16 }), items);
        WINDOW = block("mfc_window", new Window(wood, new double[] { 0, 0, 0, 16, 16, 16 }, new double[] { 0, 16, 3.75, 16, 28, 4.5 }), items);
        CHAIRS = block("mfc_chairs", new BarFurnitureBlock(metal, 8 / 16.0, true, new double[] { 0, 0, 3, 16, 8, 12.25 }, new double[] { 0.75, 8, 11.5, 15.25, 15.5, 12.25 }), items);
        FORM_DESK = block("mfc_form_desk", new BarFurnitureBlock(wood, 0, true, new double[] { 7, 0, 7, 9, 17, 9 }, new double[] { 0.5, 17, 0.5, 15.5, 18, 15.5 }), items);
        INFO = block("mfc_info", new BarFurnitureBlock(metal, 0, true, new double[] { 0.5, 0, 7.25, 15.5, 24, 8.75 }), items);
        COOLER = block("mfc_cooler", new BarFurnitureBlock(metal, 0, true, new double[] { 4, 0, 4, 12, 24, 12 }), items);
        PLANT = block("mfc_plant", new BarFurnitureBlock(plant, 0, true, new double[] { 4, 0, 4, 12, 16, 12 }), items);
        STANCHION = block("mfc_stanchion", new BarFurnitureBlock(metal, 0, true, new double[] { 0, 0, 7, 16, 24, 9 }), items);
        PHOTO = block("mfc_photo", new BarFurnitureBlock(metal, 0, true, new double[] { 0, 0, 1, 1, 24, 16 }, new double[] { 15, 0, 1, 16, 24, 16 }, new double[] { 0, 0, 15, 16, 24, 16 }), items);
        DETECTOR = block("mfc_detector", new BarFurnitureBlock(metal.luminance(s -> 4), 0, true, new double[] { 0, 0, 5, 2.5, 24, 11 }, new double[] { 13.5, 0, 5, 16, 24, 11 }), items);
        GUARD_DESK = block("mfc_guard_desk", new BarFurnitureBlock(wood, 0, true, new double[] { 0, 0, 1.5, 16, 13, 16 }), items);
        SIGN = block("mfc_sign", new BarFurnitureBlock(thin, 0, false, new double[] { 0, 2, 15, 16, 14, 16 }), items);
        TICKET = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "mfc_ticket"), new Item(new Item.Settings().maxCount(16)));
        items.add(TICKET);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
        PointOfInterestHelper.register(WINDOW_POI_ID, 1, 1, WINDOW);
        PointOfInterestHelper.register(GUARD_POI_ID, 1, 1, GUARD_DESK);
        PointOfInterestHelper.register(INFO_POI_ID, 1, 1, INFO);
        OPERATOR = profession("mfc_operator", WINDOW_POI);
        GUARD = profession("mfc_guard", GUARD_POI);
        ADMIN = profession("mfc_admin", INFO_POI);
    }

    private static <B extends Block> B block(String name, B block, List<Item> items) {
        Identifier id = Identifier.of("mobtalk", name);
        Registry.register(Registries.BLOCK, id, block);
        items.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        return block;
    }
    private static VillagerProfession profession(String path, RegistryKey<PointOfInterestType> poi) {
        return Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", path),
                new VillagerProfession(path, entry -> entry.matchesKey(poi), entry -> entry.matchesKey(poi), ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_LIBRARIAN));
    }
}
