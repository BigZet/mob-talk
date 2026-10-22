package io.github.rusflora.hogweed;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/** An invasion focus as world generation leaves it: 10 to 25 plants of mixed ages within six blocks, rosettes
 * around a core of flowering plants and dry stalks from the year before. From here it spreads on its own. */
public final class HogweedPatchFeature extends Feature<DefaultFeatureConfig> {
    public HogweedPatchFeature() { super(DefaultFeatureConfig.CODEC); }

    @Override public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        StructureWorldAccess world = context.getWorld();
        Random random = context.getRandom();
        BlockPos origin = context.getOrigin();
        int plants = 10 + random.nextInt(16), placed = 0;
        for (int i = 0; i < plants * 3 && placed < plants; i++) {
            int spread = i < 3 ? 1 : i < 12 ? 4 : 6;
            int x = origin.getX() + random.nextBetween(-spread, spread), z = origin.getZ() + random.nextBetween(-spread, spread);
            BlockPos root = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            // Not under a tree crown: there it would never grow.
            if (world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) != root.getY()) continue;
            BlockState ground = world.getBlockState(root.down());
            if (!HogweedBlock.isSoil(ground)) continue;
            int stage = stage(random, i < 3);
            boolean room = true;
            for (int part = 0; part < HogweedBlock.height(stage) && room; part++) room = HogweedBlock.roomFor(world, root.up(part));
            if (!room) continue;
            Hogweed.BLOCK.setStage(world, root, stage);
            placed++;
        }
        return placed > 0;
    }

    /** The first few plants near the centre are the old ones; the rest are mostly rosettes. */
    private static int stage(Random random, boolean core) {
        int roll = random.nextInt(100);
        if (core) return roll < 50 ? HogweedBlock.FLOWERING : roll < 75 ? HogweedBlock.DRY : HogweedBlock.SEEDING;
        return roll < 15 ? HogweedBlock.SEEDLING : roll < 55 ? HogweedBlock.ROSETTE : roll < 80 ? HogweedBlock.BIG : HogweedBlock.FLOWERING;
    }
}
