package io.github.mobtalk.game;

import com.google.common.collect.ImmutableSet;
import io.github.mobtalk.game.CasinoBlocks.Use;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The village casino's content (docs/casino.md): its furniture (tables for 21, roulette and cards, slot machines, the bar, chairs, chips,
 * the sign, the chandelier, the velvet rope), the bouncer (a villager profession whose job site is the velvet rope post) and two points
 * of interest: the rope, and the game tables visitors go to. Registered before any world loads, on both sides. */
public final class CasinoContent {
    private CasinoContent() { }
    public static final Identifier ROPE_ID = Identifier.of("mobtalk", "casino_rope");
    public static final RegistryKey<PointOfInterestType> ROPE_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, ROPE_ID);
    public static final Identifier GAME_ID = Identifier.of("mobtalk", "casino_game");
    public static final RegistryKey<PointOfInterestType> GAME_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, GAME_ID);
    public static Block PART, BLACKJACK, POKER, ROULETTE, SLOT, BAR_COUNTER, BAR_SHELF, BAR_STOOL, CHAIR, CHIPS, SIGN, CHANDELIER, ROPE;
    public static VillagerProfession BOUNCER;
    /** Every casino block with an item, in the creative tab's order. */
    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    private static boolean registered;

    private static final int[][] TABLE_CELLS = { {-1, 0}, {1, 0}, {-1, -1}, {0, -1}, {1, -1} };
    private static final int[][] ROW_CELLS = { {-1, 0}, {1, 0} };
    private static final int[][] NONE = {};

    public static void register() {
        if (registered) return;
        registered = true;
        PART = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", "casino_part"), new CasinoBlocks.Part(wood().dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK).nonOpaque()));
        Map<Direction, VoxelShape> table = CasinoBlocks.shapes(new double[]{0, 0, 0, 16, 15, 16});
        BLACKJACK = decor("casino_blackjack_table", wood().pistonBehavior(PistonBehavior.BLOCK), table, table, TABLE_CELLS, Use.BLACKJACK, false);
        POKER = decor("casino_poker_table", wood().pistonBehavior(PistonBehavior.BLOCK), table, table, TABLE_CELLS, Use.POKER, false);
        ROULETTE = decor("casino_roulette_table", wood().pistonBehavior(PistonBehavior.BLOCK), table, table, ROW_CELLS, Use.ROULETTE, false);
        SLOT = add("casino_slot_machine", new CasinoBlocks.SlotMachine(AbstractBlock.Settings.create().mapColor(MapColor.RED).strength(2.5f)
                .sounds(BlockSoundGroup.METAL).nonOpaque().luminance(state -> 6).pistonBehavior(PistonBehavior.BLOCK)));
        Map<Direction, VoxelShape> full = CasinoBlocks.shapes(new double[]{0, 0, 0, 16, 16, 16});
        BAR_COUNTER = decor("casino_bar_counter", wood(), full, full, NONE, Use.NONE, false);
        Map<Direction, VoxelShape> shelf = CasinoBlocks.shapes(new double[]{0, 0, 7, 16, 16, 16});
        BAR_SHELF = decor("casino_bar_shelf", wood().sounds(BlockSoundGroup.GLASS), shelf, shelf, NONE, Use.NONE, false);
        Map<Direction, VoxelShape> stool = CasinoBlocks.shapes(new double[]{4, 0, 4, 12, 12, 12});
        BAR_STOOL = decor("casino_bar_stool", metal(), stool, stool, NONE, Use.SEAT, false);
        Map<Direction, VoxelShape> seat = CasinoBlocks.shapes(new double[]{2.5, 0, 2.5, 13.5, 9.5, 13.5});
        CHAIR = decor("casino_chair", wood().sounds(BlockSoundGroup.WOOL),
                CasinoBlocks.shapes(new double[]{2.5, 0, 2.5, 13.5, 9.5, 13.5}, new double[]{3, 9.5, 12, 13, 20, 15}), seat, NONE, Use.SEAT, false);
        CHIPS = add("casino_chips", new CasinoBlocks.Chips(AbstractBlock.Settings.create().mapColor(MapColor.RED).strength(0.3f)
                .sounds(BlockSoundGroup.STONE).nonOpaque().noCollision().pistonBehavior(PistonBehavior.DESTROY)));
        Map<Direction, VoxelShape> empty = CasinoBlocks.shapes();
        SIGN = decor("casino_sign", wood().noCollision().luminance(state -> 12), CasinoBlocks.shapes(new double[]{0, 2, 14, 16, 14, 16}), empty,
                NONE, Use.NONE, true);
        CHANDELIER = decor("casino_chandelier", metal().noCollision().luminance(state -> 15), CasinoBlocks.shapes(new double[]{2, 2, 2, 14, 16, 14}),
                empty, NONE, Use.NONE, false);
        Map<Direction, VoxelShape> rope = CasinoBlocks.shapes(new double[]{5, 0, 5, 11, 14.2, 11}, new double[]{0, 8, 7.3, 16, 11.1, 8.7});
        ROPE = decor("casino_rope", metal(), rope, CasinoBlocks.shapes(new double[]{5, 0, 5, 11, 16, 11}, new double[]{0, 0, 7.3, 16, 16, 8.7}),
                NONE, Use.NONE, false);

        PointOfInterestHelper.register(ROPE_ID, 1, 1, ROPE);
        List<BlockState> games = new ArrayList<>();
        for (Block b : List.of(BLACKJACK, POKER, ROULETTE)) games.addAll(b.getStateManager().getStates());
        for (BlockState s : SLOT.getStateManager().getStates()) if (s.get(CasinoBlocks.SlotMachine.HALF) == DoubleBlockHalf.LOWER) games.add(s);
        PointOfInterestHelper.register(GAME_ID, 0, 1, games);
        BOUNCER = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "casino_bouncer"),
                new VillagerProfession("casino_bouncer", entry -> entry.matchesKey(ROPE_POI), entry -> entry.matchesKey(ROPE_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_ARMORER));

        Registry.register(Registries.ITEM_GROUP, Identifier.of("mobtalk", "casino"), FabricItemGroup.builder()
                .icon(() -> new ItemStack(SLOT))
                .displayName(Text.translatable("itemGroup.mobtalk.casino"))
                .entries((context, entries) -> { for (Block b : BLOCKS.values()) entries.add(b); })
                .build());
    }

    public static boolean bouncer(VillagerEntity villager) { return BOUNCER != null && villager.getVillagerData().getProfession() == BOUNCER; }

    private static AbstractBlock.Settings wood() {
        return AbstractBlock.Settings.create().mapColor(MapColor.DARK_RED).strength(2.0f).sounds(BlockSoundGroup.WOOD).nonOpaque();
    }
    private static AbstractBlock.Settings metal() {
        return AbstractBlock.Settings.create().mapColor(MapColor.GOLD).strength(1.5f).sounds(BlockSoundGroup.METAL).nonOpaque();
    }

    private static Block decor(String id, AbstractBlock.Settings settings, Map<Direction, VoxelShape> outline, Map<Direction, VoxelShape> collision,
                               int[][] cells, Use use, boolean onWall) {
        return add(id, new CasinoBlocks.Decor(settings, outline, collision, cells, use, onWall));
    }

    private static Block add(String id, Block block) {
        Identifier key = Identifier.of("mobtalk", id);
        Registry.register(Registries.BLOCK, key, block);
        Registry.register(Registries.ITEM, key, new BlockItem(block, new Item.Settings()));
        BLOCKS.put(id, block);
        return block;
    }
}
