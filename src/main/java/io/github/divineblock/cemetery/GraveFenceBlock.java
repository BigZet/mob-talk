package io.github.divineblock.cemetery;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.FenceBlock;

/** A low painted iron fence around a grave plot ("оградка"). Joins other fences, gates and solid blocks like a vanilla fence. */
public class GraveFenceBlock extends FenceBlock {
    public static final MapCodec<GraveFenceBlock> CODEC = createCodec(GraveFenceBlock::new);

    public GraveFenceBlock(Settings settings) { super(settings); }

    @SuppressWarnings("unchecked") @Override public MapCodec<FenceBlock> getCodec() { return (MapCodec<FenceBlock>) (MapCodec<?>) CODEC; }
}
