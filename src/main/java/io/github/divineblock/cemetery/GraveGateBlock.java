package io.github.divineblock.cemetery;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WoodType;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvents;

/** The gate of a grave plot fence: a pair of painted iron leaves that squeak like an iron door. */
public class GraveGateBlock extends FenceGateBlock {
    static final WoodType IRON = new WoodType("divineblock_iron", BlockSetType.IRON, BlockSoundGroup.METAL, BlockSoundGroup.METAL,
            SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundEvents.BLOCK_IRON_DOOR_OPEN);
    public static final MapCodec<GraveGateBlock> CODEC = createCodec(GraveGateBlock::new);

    public GraveGateBlock(Settings settings) { super(IRON, settings); }

    @SuppressWarnings("unchecked") @Override public MapCodec<FenceGateBlock> getCodec() { return (MapCodec<FenceGateBlock>) (MapCodec<?>) CODEC; }
}
