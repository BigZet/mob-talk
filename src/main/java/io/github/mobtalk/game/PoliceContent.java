package io.github.mobtalk.game;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The city police's content (tools/police/police.py draws it, tools/police/siren.py makes the sounds): the police post (a block and
 * its point of interest) and the policeman, a villager profession whose job site it is; the rubber baton and the handcuffs it carries;
 * the holding cell's wall, which no tool breaks; three police cars whose light bar flashes while {@link #LIT} is on; and the siren
 * of the Russian police (the «кряк», the wail and the yelp of an SGU), which a city's alarm sounds instead of the bell. Registered
 * before any world loads, on both sides. */
public final class PoliceContent {
    private PoliceContent() { }
    public static final Identifier POLICE_POST_ID = Identifier.of("mobtalk", "police_post");
    public static final RegistryKey<PointOfInterestType> POLICE_POST_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, POLICE_POST_ID);
    public static final BooleanProperty LIT = Properties.LIT;
    public static final List<String> CARS = List.of("car_police", "car_police_uaz", "car_militsiya");
    public static Block POLICE_POST, JAIL_WALL;
    public static final Map<String, Block> CAR_BLOCKS = new LinkedHashMap<>();
    public static Item BATON, HANDCUFFS;
    public static VillagerProfession POLICE;
    public static SoundEvent SIREN, QUACK;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        List<Item> items = new ArrayList<>();
        POLICE_POST = Registry.register(Registries.BLOCK, POLICE_POST_ID, new Block(AbstractBlock.Settings.create().mapColor(MapColor.BLUE)
                .strength(2.5f).sounds(BlockSoundGroup.METAL).nonOpaque()));
        items.add(Registry.register(Registries.ITEM, POLICE_POST_ID, new BlockItem(POLICE_POST, new Item.Settings())));
        PointOfInterestHelper.register(POLICE_POST_ID, 1, 1, POLICE_POST);
        POLICE = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "police"),
                new VillagerProfession("police", entry -> entry.matchesKey(POLICE_POST_POI), entry -> entry.matchesKey(POLICE_POST_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_ARMORER));
        Identifier wall = Identifier.of("mobtalk", "jail_wall");
        JAIL_WALL = Registry.register(Registries.BLOCK, wall, new Block(AbstractBlock.Settings.create().mapColor(MapColor.BLUE)
                .strength(-1f, 3600000f).dropsNothing().sounds(BlockSoundGroup.STONE)));
        items.add(Registry.register(Registries.ITEM, wall, new BlockItem(JAIL_WALL, new Item.Settings())));
        for (String car : CARS) {
            CityContent.Spec spec = new CityContent.Spec(car, CityContent.Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0,
                    car.equals("car_militsiya") ? MapColor.YELLOW : MapColor.WHITE, BlockSoundGroup.METAL, true);
            Block block = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", car), new PoliceCarBlock(AbstractBlock.Settings.create()
                    .mapColor(spec.color()).strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque().luminance(state -> state.get(LIT) ? 9 : 0), spec));
            CAR_BLOCKS.put(car, block);
            items.add(Registry.register(Registries.ITEM, Identifier.of("mobtalk", car), new BlockItem(block, new Item.Settings())));
        }
        BATON = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "police_baton"), new Item(new Item.Settings().maxCount(1)));
        HANDCUFFS = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "handcuffs"), new Item(new Item.Settings().maxCount(1)));
        items.add(BATON); items.add(HANDCUFFS);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
        SIREN = sound("police_siren");
        QUACK = sound("police_quack");
        TradeOfferHelper.registerVillagerOffers(POLICE, 1, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 3), new ItemStack(BATON), 4, 2, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.PAPER, 24), new ItemStack(Items.EMERALD), 16, 2, 0.05f));
        });
        TradeOfferHelper.registerVillagerOffers(POLICE, 2, factories ->
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 6), new ItemStack(HANDCUFFS), 3, 5, 0.05f)));
    }

    private static SoundEvent sound(String name) {
        Identifier id = Identifier.of("mobtalk", name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static boolean police(VillagerEntity villager) { return POLICE != null && villager.getVillagerData().getProfession() == POLICE; }
    public static boolean car(BlockState state) { return state.getBlock() instanceof PoliceCarBlock; }

    /** A police car: a city car three cells long whose light bar flashes when lit (in an alarm the police switch it on). */
    public static class PoliceCarBlock extends CityContent.CarBlock {
        PoliceCarBlock(AbstractBlock.Settings settings, CityContent.Spec spec) {
            super(settings, spec);
            setDefaultState(getDefaultState().with(LIT, false));
        }
        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(s -> new PoliceCarBlock(s, spec)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { super.appendProperties(builder); builder.add(LIT); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState state = super.getPlacementState(ctx);
            return state == null ? null : state.with(LIT, false);
        }
    }
}
