package io.github.divineblock.boss;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/** «Печать стража»: a black slab with a gold cross that structures place where a boss sleeps (the church's crypt, a grave on
 * the cemetery). When a player comes near, the boss rises from it; see {@link BossSealBlockEntity} for the rules. */
public final class BossSealBlock extends BlockWithEntity {
    public static final MapCodec<BossSealBlock> CODEC = createCodec(BossSealBlock::new);

    public BossSealBlock(Settings settings) { super(settings); }
    @Override protected MapCodec<? extends BlockWithEntity> getCodec() { return CODEC; }
    @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }

    @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new BossSealBlockEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : validateTicker(type, DivineBosses.SEAL_ENTITY, BossSealBlockEntity::tick);
    }

    @Override public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (random.nextInt(3) == 0)
            world.addParticle(ParticleTypes.SOUL, pos.getX() + random.nextDouble(), pos.getY() + 1.05, pos.getZ() + random.nextDouble(), 0, 0.02, 0);
    }
}
