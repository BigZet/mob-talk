package io.github.alicespeaker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

public final class AliceSpeakerMod implements ModInitializer {
    public static final String ID = "alicespeaker";
    public static final Block SPEAKER = new SpeakerBlock(AbstractBlock.Settings.create()
            .strength(0.8f).sounds(BlockSoundGroup.WOOL).nonOpaque().pistonBehavior(PistonBehavior.DESTROY));

    @Override public void onInitialize() {
        Identifier id = Identifier.of(ID, "speaker");
        Registry.register(Registries.BLOCK, id, SPEAKER);
        Item item = Registry.register(Registries.ITEM, id, new BlockItem(SPEAKER, new Item.Settings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(item));
    }

    /** A small cylinder-ish speaker; all behaviour lives on the client. */
    static final class SpeakerBlock extends Block {
        private static final VoxelShape SHAPE = Block.createCuboidShape(4, 0, 4, 12, 10, 12);
        SpeakerBlock(Settings settings) { super(settings); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
    }
}
