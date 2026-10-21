package io.github.divineblock.game;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/** The altar: right-click opens the prayer screen; gifts are thrown on top of it before praying. */
public final class AltarBlock extends Block {
    public static final MapCodec<AltarBlock> CODEC = createCodec(AltarBlock::new);
    /** Set by the client entrypoint: opens the prayer screen for this altar. A plain hook keeps client classes off the server. */
    public static volatile java.util.function.Consumer<BlockPos> openPrayer = pos -> { };
    /** Set by the client entrypoint: an altar was seen nearby, for the motes of light that circle it. */
    public static volatile java.util.function.BiConsumer<World, BlockPos> ambience = (world, pos) -> { };

    public AltarBlock(Settings settings) { super(settings); }
    @Override protected MapCodec<? extends Block> getCodec() { return CODEC; }

    @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) openPrayer.accept(pos.toImmutable());
        return ActionResult.success(world.isClient);
    }

    @Override public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        ambience.accept(world, pos);
        for (int i = 0; i < 2; i++)
            world.addParticle(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 2.0, pos.getZ() + 0.5,
                    random.nextFloat() - 0.5, random.nextFloat() - 1.2, random.nextFloat() - 0.5);
        if (random.nextInt(6) == 0)
            world.addParticle(ParticleTypes.END_ROD, pos.getX() + random.nextDouble(), pos.getY() + 1.05, pos.getZ() + random.nextDouble(), 0, 0.02, 0);
    }
}
