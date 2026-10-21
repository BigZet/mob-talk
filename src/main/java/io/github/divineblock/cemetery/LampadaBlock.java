package io.github.divineblock.cemetery;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/** A red glass grave lamp with a candle inside: a little light and now and then a wisp of smoke from the cap. */
public class LampadaBlock extends GraveBlock {
    private final double[][] boxes;

    public LampadaBlock(Settings settings, double[]... boxes) { super(settings, true, boxes); this.boxes = boxes; }

    @Override protected MapCodec<? extends Block> getCodec() { return createCodec(settings -> new LampadaBlock(settings, boxes)); }

    @Override public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (state.get(WATERLOGGED) || random.nextInt(4) != 0) return;
        world.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.45, pos.getZ() + 0.5, 0, 0.01, 0);
    }
}
