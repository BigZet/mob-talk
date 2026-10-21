package io.github.divineblock.cemetery;

import static io.github.divineblock.cemetery.GraveShapes.box;

import io.github.divineblock.DivineBlockMod;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** The blocks of a Russian Orthodox village cemetery: crosses, monuments, mounds, plot fences, a bench and a table, wreaths,
 * flowers and a lampada, all in the mod's own creative tab beside the altar. The cemetery structure is built from them. */
public final class CemeteryBlocks {
    private static final List<Item> TAB = new ArrayList<>();

    private static AbstractBlock.Settings wood() {
        return AbstractBlock.Settings.create().mapColor(MapColor.SPRUCE_BROWN).strength(1.5f).sounds(BlockSoundGroup.WOOD).nonOpaque()
                .instrument(NoteBlockInstrument.BASS).burnable().pistonBehavior(PistonBehavior.DESTROY);
    }
    private static AbstractBlock.Settings iron(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(2.5f, 6f).sounds(BlockSoundGroup.METAL).nonOpaque()
                .pistonBehavior(PistonBehavior.DESTROY);
    }
    private static AbstractBlock.Settings stone(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(2f, 6f).sounds(BlockSoundGroup.POLISHED_DEEPSLATE).nonOpaque()
                .instrument(NoteBlockInstrument.BASEDRUM).pistonBehavior(PistonBehavior.DESTROY);
    }
    private static AbstractBlock.Settings soft(MapColor color, BlockSoundGroup sound) {
        return AbstractBlock.Settings.create().mapColor(color).noCollision().breakInstantly().sounds(sound).nonOpaque()
                .pistonBehavior(PistonBehavior.DESTROY);
    }

    // Eight-pointed crosses: the post, the crossbar and the title board above, the slanted footrest below.
    private static final double[][] CROSS_LOW = { box(7, 0, 7, 9, 16, 9), box(3, 8, 7, 13, 11, 9) };
    private static final double[][] CROSS_HIGH = { box(7, 0, 7, 9, 14, 9), box(1, 4.5, 7, 15, 7, 9), box(5, 9.5, 7, 11, 11.5, 9) };
    private static final double[][] ROOFED_HIGH = { box(7, 0, 7, 9, 14, 9), box(1, 4.5, 7, 15, 7, 9), box(3, 10, 3.5, 13, 16, 12.5) };
    // The old cross leans back on its sunken foot, its footrest slipped level and its title board gone.
    private static final double[][] OLD_LOW = { box(7, 0, 1.5, 9, 8, 6), box(7, 8, 4, 9, 16, 10), box(4.5, 7.5, 5.5, 11.5, 9.5, 7.5) };
    private static final double[][] OLD_HIGH = { box(7, 0, 9, 9, 11.5, 15), box(2.5, 1.5, 9.5, 13, 4.5, 12.5) };

    public static final Block WOODEN_CROSS = block("wooden_cross", new TallGraveBlock(wood(), CROSS_LOW, CROSS_HIGH));
    public static final Block ROOFED_CROSS = block("roofed_cross", new TallGraveBlock(wood(), CROSS_LOW, ROOFED_HIGH));
    public static final Block OLD_CROSS = block("old_cross", new TallGraveBlock(wood().mapColor(MapColor.GRAY), OLD_LOW, OLD_HIGH));
    public static final Block IRON_CROSS = block("iron_cross", new TallGraveBlock(iron(MapColor.BLACK),
            new double[][] { box(5, 0, 5, 11, 2, 11), box(7, 0, 7, 9, 16, 9), box(3.5, 8, 7, 12.5, 11, 9) }, CROSS_HIGH));
    public static final Block STAR_OBELISK = block("star_obelisk", new TallGraveBlock(iron(MapColor.LIGHT_GRAY),
            new double[][] { box(3, 0, 3, 13, 2, 13), box(4, 2, 4, 12, 12, 12), box(5, 12, 5, 11, 16, 11) },
            new double[][] { box(6, 0, 6, 10, 9.5, 10), box(5, 9.5, 7.5, 11, 16, 8.5) }));
    public static final Block GRANITE_STELE = block("granite_stele", new GraveBlock(stone(MapColor.BLACK), true,
            box(2, 0, 5, 14, 2, 12), box(3.5, 2, 7, 12.5, 16, 9)));
    public static final Block MARBLE_STONE = block("marble_stone", new GraveBlock(stone(MapColor.WHITE).sounds(BlockSoundGroup.CALCITE), true,
            box(4, 0, 4, 12, 1.5, 12), box(5, 1.5, 6, 11, 9.5, 10)));
    public static final Block GRAVE_MOUND = block("grave_mound", new GraveMoundBlock(AbstractBlock.Settings.create().mapColor(MapColor.DIRT_BROWN)
            .strength(0.6f).sounds(BlockSoundGroup.ROOTED_DIRT).nonOpaque(),
            box(1, 0, 0, 15, 3, 16), box(2.5, 3, 0, 13.5, 5, 16), box(4, 5, 0, 12, 6, 16)));
    public static final Block BLACK_GRAVE_FENCE = block("black_grave_fence", new GraveFenceBlock(iron(MapColor.BLACK)));
    public static final Block BLACK_GRAVE_GATE = block("black_grave_gate", new GraveGateBlock(iron(MapColor.BLACK)));
    public static final Block BLUE_GRAVE_FENCE = block("blue_grave_fence", new GraveFenceBlock(iron(MapColor.LIGHT_BLUE)));
    public static final Block BLUE_GRAVE_GATE = block("blue_grave_gate", new GraveGateBlock(iron(MapColor.LIGHT_BLUE)));
    public static final Block GRAVE_BENCH = block("grave_bench", new GraveBlock(wood(), true,
            box(1, 7, 5, 15, 8.5, 11), box(2, 0, 5.5, 3, 7, 10.5), box(13, 0, 5.5, 14, 7, 10.5)));
    public static final Block GRAVE_TABLE = block("grave_table", new GraveBlock(wood(), true,
            box(3, 11, 3, 13, 12.5, 13), box(7, 0, 7, 9, 11, 9), box(5, 0, 5, 11, 1, 11)));
    public static final Block FUNERAL_WREATH = block("funeral_wreath", new GraveBlock(soft(MapColor.DARK_GREEN, BlockSoundGroup.AZALEA_LEAVES), false,
            box(1, 0, 5, 15, 15, 11)));
    public static final Block ARTIFICIAL_FLOWERS = block("artificial_flowers", new GraveBlock(soft(MapColor.RED, BlockSoundGroup.AZALEA), false,
            box(4, 0, 4, 12, 11, 12)));
    public static final Block GRAVE_LAMPADA = block("grave_lampada", new LampadaBlock(AbstractBlock.Settings.create().mapColor(MapColor.RED)
            .strength(0.5f).sounds(BlockSoundGroup.GLASS).nonOpaque().luminance(state -> 7).pistonBehavior(PistonBehavior.DESTROY),
            box(6, 0, 6, 10, 8, 10)));

    private CemeteryBlocks() { }

    private static Block block(String name, Block block) {
        Identifier id = Identifier.of(DivineBlockMod.ID, name);
        Registry.register(Registries.BLOCK, id, block);
        TAB.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        return block;
    }

    /** Called from the mod initializer: the static fields above register the blocks, this adds the creative tab. */
    public static void register() {
        ItemGroup tab = FabricItemGroup.builder().icon(() -> new ItemStack(WOODEN_CROSS)).displayName(Text.translatable("itemGroup.divineblock"))
                .entries((context, entries) -> {
                    entries.add(DivineBlockMod.ALTAR_ITEM);
                    TAB.forEach(entries::add);
                }).build();
        Registry.register(Registries.ITEM_GROUP, Identifier.of(DivineBlockMod.ID, "main"), tab);
    }

    /** Every cemetery block, for the client's render layers and the self-test showcase. */
    public static List<Block> all() { return TAB.stream().map(item -> ((BlockItem) item).getBlock()).toList(); }
}
