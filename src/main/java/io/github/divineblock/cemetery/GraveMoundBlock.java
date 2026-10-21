package io.github.divineblock.cemetery;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A low earth mound over a grave, one block of a two blocks long grave. Bone meal lets grass grow over it; a shovel or a hoe
 * tidies it again. */
public class GraveMoundBlock extends GraveBlock {
    public static final BooleanProperty OVERGROWN = BooleanProperty.of("overgrown");
    private final double[][] boxes;

    public GraveMoundBlock(Settings settings, double[]... boxes) {
        super(settings, true, boxes);
        this.boxes = boxes;
        setDefaultState(getDefaultState().with(OVERGROWN, false));
    }

    @Override protected MapCodec<? extends Block> getCodec() { return createCodec(settings -> new GraveMoundBlock(settings, boxes)); }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { super.appendProperties(builder); builder.add(OVERGROWN); }

    @Override protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
            BlockHitResult hit) {
        boolean grow = stack.isOf(Items.BONE_MEAL) && !state.get(OVERGROWN);
        boolean tidy = (stack.isIn(ItemTags.SHOVELS) || stack.isIn(ItemTags.HOES)) && state.get(OVERGROWN);
        if (!grow && !tidy) return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!world.isClient) {
            world.setBlockState(pos, state.with(OVERGROWN, grow), Block.NOTIFY_ALL);
            if (grow) {
                stack.decrementUnlessCreative(1, player);
                ((ServerWorld) world).spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 8, 0.3, 0.1, 0.3, 0);
                world.playSound(null, pos, SoundEvents.ITEM_BONE_MEAL_USE, SoundCategory.BLOCKS, 1f, 1f);
            } else {
                stack.damage(1, player, LivingEntity.getSlotForHand(hand));
                world.playSound(null, pos, SoundEvents.ITEM_SHOVEL_FLATTEN, SoundCategory.BLOCKS, 1f, 1f);
            }
        }
        return ItemActionResult.success(world.isClient);
    }
}
