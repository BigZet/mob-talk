package io.github.rusflora.block;

import com.mojang.serialization.MapCodec;
import io.github.rusflora.RusFlora;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

/** The leafy body of a shrub. One pushes through it slowly (no collision, like a real thicket); the dog rose pricks. Stage 0 is
 * leaves only, 1 flowers or catkins, 2 ripe fruit; random ticks move it along and picking the fruit starts it over. */
public class ShrubLeavesBlock extends Block {
    public static final IntProperty STAGE = IntProperty.of("stage", 0, 2);
    public final ShrubKind kind;

    public ShrubLeavesBlock(ShrubKind kind, Settings settings) {
        super(settings);
        this.kind = kind;
        setDefaultState(getStateManager().getDefaultState().with(STAGE, 0));
    }

    @Override protected MapCodec<? extends Block> getCodec() { return createCodec(settings -> new ShrubLeavesBlock(kind, settings)); }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(STAGE); }

    @Override protected boolean hasRandomTicks(BlockState state) {
        int stage = state.get(STAGE);
        return stage == 0 ? kind.flowers || kind.fruits() : stage == 1;
    }

    @Override protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (random.nextInt(8) != 0) return;
        int stage = state.get(STAGE), next;
        if (stage == 0) next = kind.flowers ? 1 : 2;
        else next = kind.fruits() ? 2 : 0; // a shrub without fruit drops its flowers and grows them again later
        world.setBlockState(pos, state.with(STAGE, next), Block.NOTIFY_LISTENERS);
    }

    @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!kind.fruits() || state.get(STAGE) != 2) return ActionResult.PASS;
        if (!world.isClient) {
            int count = 1 + world.random.nextInt(3);
            dropStack(world, pos, new ItemStack(RusFlora.fruit(kind), count));
            world.playSound(null, pos, SoundEvents.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, SoundCategory.BLOCKS, 1f, 0.8f + world.random.nextFloat() * 0.4f);
            world.setBlockState(pos, state.with(STAGE, 0), Block.NOTIFY_LISTENERS);
            world.emitGameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Emitter.of(player, state));
        }
        return ActionResult.success(world.isClient);
    }

    @Override protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity)) return;
        entity.slowMovement(state, new Vec3d(0.8, 0.85, 0.8));
        if (!kind.thorny || world.isClient) return;
        double dx = Math.abs(entity.getX() - entity.lastRenderX), dz = Math.abs(entity.getZ() - entity.lastRenderZ);
        if (dx >= 0.003 || dz >= 0.003) entity.damage(world.getDamageSources().sweetBerryBush(), 0.5f);
    }
}
