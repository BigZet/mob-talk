package io.github.rusflora.hogweed;

import java.util.EnumSet;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** Cows love hogweed: every few seconds a cow looks for a plant within ten blocks, walks to it and eats it to the ground.
 * The search first asks the chunk sections around the cow whether they hold any hogweed at all, so a cow far from any
 * thicket costs nothing beyond that palette check. */
public final class CowEatHogweedGoal extends Goal {
    private static final int RADIUS = 10;
    private final CowEntity cow;
    private BlockPos target;
    private int cooldown, ticks;

    public CowEatHogweedGoal(CowEntity cow) {
        this.cow = cow;
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        cooldown = cow.getRandom().nextInt(100);
    }

    @Override public boolean canStart() {
        if (!HogweedConfig.get().cowsEat || --cooldown > 0) return false;
        cooldown = 60 + cow.getRandom().nextInt(60);
        target = find();
        return target != null;
    }

    @Override public boolean shouldContinue() {
        return target != null && ticks < 400 && isRoot(cow.getWorld().getBlockState(target));
    }

    @Override public void start() {
        ticks = 0;
        cow.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1);
    }

    @Override public void stop() {
        target = null;
        cow.getNavigation().stop();
    }

    @Override public boolean shouldRunEveryTick() { return true; }

    @Override public void tick() {
        if (target == null) return;
        ticks++;
        cow.getLookControl().lookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (cow.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) < 3.2) {
            eat();
            target = null;
        } else if (ticks % 40 == 0 && cow.getNavigation().isIdle()) {
            cow.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1);
        }
    }

    private void eat() {
        if (!(cow.getWorld() instanceof ServerWorld world)) return;
        BlockState state = world.getBlockState(target);
        if (!isRoot(state)) return;
        Hogweed.BLOCK.removePlant(world, target);
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), target.getX() + 0.5, target.getY() + 0.4, target.getZ() + 0.5,
                12, 0.3, 0.3, 0.3, 0.05);
        world.playSound(null, target, SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 1f, 0.8f + cow.getRandom().nextFloat() * 0.3f);
        cow.onEatingGrass();
    }

    private static boolean isRoot(BlockState state) { return state.isOf(Hogweed.BLOCK) && state.get(HogweedBlock.PART) == 0; }

    /** The nearest hogweed root within RADIUS, or null. */
    private BlockPos find() {
        BlockPos home = cow.getBlockPos();
        if (!anyNearby(home)) return null;
        for (BlockPos at : BlockPos.iterateOutwards(home, RADIUS, 3, RADIUS))
            if (isRoot(cow.getWorld().getBlockState(at))) return at.toImmutable();
        return null;
    }

    private boolean anyNearby(BlockPos home) {
        var world = cow.getWorld();
        for (int cx = (home.getX() - RADIUS) >> 4; cx <= (home.getX() + RADIUS) >> 4; cx++)
            for (int cz = (home.getZ() - RADIUS) >> 4; cz <= (home.getZ() + RADIUS) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                WorldChunk chunk = world.getChunk(cx, cz);
                for (int sy = world.getSectionIndex(home.getY() - 3); sy <= world.getSectionIndex(home.getY() + 3); sy++) {
                    if (sy < 0 || sy >= chunk.getSectionArray().length) continue;
                    ChunkSection section = chunk.getSectionArray()[sy];
                    if (!section.isEmpty() && section.getBlockStateContainer().hasAny(s -> s.isOf(Hogweed.BLOCK))) return true;
                }
            }
        return false;
    }
}
