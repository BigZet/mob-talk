package io.github.rusflora.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** The bare lower stems of a tall shrub: a few thin trunks one walks between, under the leaves. */
public class ShrubStemBlock extends Block {
    private static final VoxelShape OUTLINE = Block.createCuboidShape(3, 0, 3, 13, 16, 13);
    public final ShrubKind kind;

    public ShrubStemBlock(ShrubKind kind, Settings settings) { super(settings); this.kind = kind; }

    @Override protected MapCodec<? extends Block> getCodec() { return createCodec(settings -> new ShrubStemBlock(kind, settings)); }

    @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return OUTLINE; }

    @Override protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (entity instanceof LivingEntity) entity.slowMovement(state, new Vec3d(0.9, 1, 0.9));
    }
}
