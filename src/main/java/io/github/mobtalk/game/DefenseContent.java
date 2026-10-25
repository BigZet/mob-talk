package io.github.mobtalk.game;

import com.google.common.collect.ImmutableSet;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestType;
import java.util.List;

/** Village defence's own content: the archer's post (a block and its point of interest) and the archer, a villager profession whose
 * job site is the post on top of a watchtower (design §4); the guard stand and the guard, whose job site it is (design §2). Registered before any world loads, on both sides. The archer trades a
 * little (arrows, a bow); it fights from the post through {@link VillageTowers}. */
public final class DefenseContent {
    private DefenseContent() { }
    public static final Identifier ARCHER_POST_ID = Identifier.of("mobtalk", "archer_post");
    public static final RegistryKey<PointOfInterestType> ARCHER_POST_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, ARCHER_POST_ID);
    public static final Identifier GUARD_POST_ID = Identifier.of("mobtalk", "guard_post");
    public static final RegistryKey<PointOfInterestType> GUARD_POST_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, GUARD_POST_ID);
    public static final Identifier CROSSBOW_POST_ID = Identifier.of("mobtalk", "crossbow_post");
    public static final RegistryKey<PointOfInterestType> CROSSBOW_POST_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, CROSSBOW_POST_ID);
    public static Block ARCHER_POST, GUARD_POST, CROSSBOW_POST;
    /** The heavy warrior (diamond, an axe) and the horseman (leather, a sword, a horse) hold a guard stand too; only the village gives
     * those ranks (a free villager who takes a stand becomes a plain guard). The crossbowman holds a crossbow post on a large tower. */
    public static VillagerProfession ARCHER, GUARD, HEAVY, CAVALRY, CROSSBOWMAN;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        ARCHER_POST = Registry.register(Registries.BLOCK, ARCHER_POST_ID,
                new Block(AbstractBlock.Settings.create().mapColor(MapColor.OAK_TAN).strength(2.5f).sounds(BlockSoundGroup.WOOD)));
        Item item = Registry.register(Registries.ITEM, ARCHER_POST_ID, new BlockItem(ARCHER_POST, new Item.Settings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(item));
        PointOfInterestHelper.register(ARCHER_POST_ID, 1, 1, ARCHER_POST);
        ARCHER = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "archer"),
                new VillagerProfession("archer", entry -> entry.matchesKey(ARCHER_POST_POI), entry -> entry.matchesKey(ARCHER_POST_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_FLETCHER));
        GUARD_POST = Registry.register(Registries.BLOCK, GUARD_POST_ID,
                new Block(AbstractBlock.Settings.create().mapColor(MapColor.SPRUCE_BROWN).strength(2.5f).sounds(BlockSoundGroup.WOOD)));
        Item guardItem = Registry.register(Registries.ITEM, GUARD_POST_ID, new BlockItem(GUARD_POST, new Item.Settings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(guardItem));
        PointOfInterestHelper.register(GUARD_POST_ID, 1, 1, GUARD_POST);
        GUARD = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "guard"),
                new VillagerProfession("guard", entry -> entry.matchesKey(GUARD_POST_POI), entry -> entry.matchesKey(GUARD_POST_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_WEAPONSMITH));
        HEAVY = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "heavy"),
                new VillagerProfession("heavy", entry -> entry.matchesKey(GUARD_POST_POI), entry -> false,
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_WEAPONSMITH));
        CAVALRY = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "cavalry"),
                new VillagerProfession("cavalry", entry -> entry.matchesKey(GUARD_POST_POI), entry -> false,
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_LEATHERWORKER));
        CROSSBOW_POST = Registry.register(Registries.BLOCK, CROSSBOW_POST_ID,
                new Block(AbstractBlock.Settings.create().mapColor(MapColor.BROWN).strength(2.5f).sounds(BlockSoundGroup.WOOD)));
        Item crossbowItem = Registry.register(Registries.ITEM, CROSSBOW_POST_ID, new BlockItem(CROSSBOW_POST, new Item.Settings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(crossbowItem));
        PointOfInterestHelper.register(CROSSBOW_POST_ID, 1, 1, CROSSBOW_POST);
        CROSSBOWMAN = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "crossbowman"),
                new VillagerProfession("crossbowman", entry -> entry.matchesKey(CROSSBOW_POST_POI), entry -> entry.matchesKey(CROSSBOW_POST_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_FLETCHER));
        for (VillagerProfession soldier : List.of(HEAVY, CAVALRY))
            TradeOfferHelper.registerVillagerOffers(soldier, 1, factories -> {
                factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.IRON_INGOT, 4), new ItemStack(Items.EMERALD), 12, 2, 0.05f));
                factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.LEATHER, 8), new ItemStack(Items.EMERALD), 12, 2, 0.05f));
            });
        TradeOfferHelper.registerVillagerOffers(CROSSBOWMAN, 1, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.STRING, 14), new ItemStack(Items.EMERALD), 16, 2, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 1), new ItemStack(Items.ARROW, 16), 12, 2, 0.05f));
        });
        TradeOfferHelper.registerVillagerOffers(CROSSBOWMAN, 2, factories ->
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 4), new ItemStack(Items.CROSSBOW), 6, 5, 0.05f)));
        TradeOfferHelper.registerVillagerOffers(GUARD, 1, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.IRON_INGOT, 4), new ItemStack(Items.EMERALD), 12, 2, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 2), new ItemStack(Items.SHIELD), 6, 2, 0.05f));
        });
        TradeOfferHelper.registerVillagerOffers(GUARD, 2, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.ROTTEN_FLESH, 24), new ItemStack(Items.EMERALD), 16, 10, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 4), new ItemStack(Items.IRON_SWORD), 6, 5, 0.05f));
        });
        TradeOfferHelper.registerVillagerOffers(ARCHER, 1, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 1), new ItemStack(Items.ARROW, 16), 12, 2, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.STICK, 32), new ItemStack(Items.EMERALD), 16, 2, 0.05f));
        });
        TradeOfferHelper.registerVillagerOffers(ARCHER, 2, factories -> {
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.EMERALD, 3), new ItemStack(Items.BOW), 6, 5, 0.05f));
            factories.add((entity, random) -> new TradeOffer(new TradedItem(Items.FLINT, 24), new ItemStack(Items.EMERALD), 16, 10, 0.05f));
        });
    }

    public static boolean guard(VillagerEntity villager) { return GUARD != null && villager.getVillagerData().getProfession() == GUARD; }
    public static boolean archer(VillagerEntity villager) { return ARCHER != null && villager.getVillagerData().getProfession() == ARCHER; }
    public static boolean heavy(VillagerEntity villager) { return HEAVY != null && villager.getVillagerData().getProfession() == HEAVY; }
    public static boolean cavalry(VillagerEntity villager) { return CAVALRY != null && villager.getVillagerData().getProfession() == CAVALRY; }
    public static boolean crossbowman(VillagerEntity villager) { return CROSSBOWMAN != null && villager.getVillagerData().getProfession() == CROSSBOWMAN; }
    /** A guard of any rank: one who fights on foot or on horseback from a guard stand. */
    public static boolean soldier(VillagerEntity villager) { return guard(villager) || heavy(villager) || cavalry(villager); }
    /** One who shoots from a tower post: an archer or a crossbowman. */
    public static boolean shooter(VillagerEntity villager) { return archer(villager) || crossbowman(villager); }
    /** Any of the village's fighters: they do not hide at the bell, do not turn into zombies, are no builders. */
    public static boolean defender(VillagerEntity villager) { return soldier(villager) || shooter(villager); }
    /** A traveller of the roads (carts, wagons, riders; the roads track tags them): not of any village, so the defence leaves it be. */
    public static boolean traveller(net.minecraft.entity.Entity entity) { return entity.getCommandTags().contains("mobtalk_road"); }
}
