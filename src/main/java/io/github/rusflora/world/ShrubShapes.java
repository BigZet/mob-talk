package io.github.rusflora.world;

import io.github.rusflora.RusFlora;
import io.github.rusflora.block.ShrubKind;
import io.github.rusflora.block.ShrubLeavesBlock;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldAccess;

/** Builds one shrub of a kind on a patch of ground, for world generation and for a grown sapling. The shapes are rough solids
 * (vase, mound, ellipsoid crown, cone) with ragged edges so no two bushes match. */
public final class ShrubShapes {
    private ShrubShapes() { }

    /** Grows a shrub standing on ground (the soil block); false when the soil or the room above is wrong. */
    public static boolean grow(WorldAccess world, BlockPos ground, ShrubKind kind, Random random, boolean worldgen) {
        if (!soil(world.getBlockState(ground), kind) || !free(world.getBlockState(ground.up()))) return false;
        Map<BlockPos, Boolean> parts = new LinkedHashMap<>(); // true = stem, false = leaves
        switch (kind.shape) {
            case VASE -> vase(parts, random);
            case MOUND -> ellipsoid(parts, random, 1.6f + random.nextFloat() * 0.7f, 1.6f + random.nextFloat() * 0.6f, 0.4f, 0);
            case CROWN -> crown(parts, random);
            case ROUND -> {
                int h = 3 + random.nextInt(2);
                stems(parts, 1, 1, random);
                ellipsoid(parts, random, 1.5f + random.nextFloat() * 0.6f, (h - 1) / 2f + 0.4f, 1 + (h - 1) / 2f, 1);
            }
            case CONE -> cone(parts, random);
            case DOME -> ellipsoid(parts, random, 2.2f + random.nextFloat() * 0.8f, 3f + random.nextFloat(), 0.6f, 0);
            case BROAD -> {
                int h = 3 + random.nextInt(2);
                stems(parts, 1, 2, random);
                ellipsoid(parts, random, 2f + random.nextFloat() * 0.7f, h / 2f, 1 + h / 2f, 1);
            }
        }
        BlockPos base = ground.up();
        // Stems stand on soil or on a lower stem; drop any that would float over a slope.
        parts.entrySet().removeIf(e -> e.getValue() && e.getKey().getY() == 0 && !soil(world.getBlockState(base.add(e.getKey()).down()), kind)
                && !(e.getKey().getX() == 0 && e.getKey().getZ() == 0));
        int stage = worldgen ? startStage(kind, random) : 0;
        int placed = 0;
        for (Map.Entry<BlockPos, Boolean> part : parts.entrySet()) {
            BlockPos pos = base.add(part.getKey());
            if (!free(world.getBlockState(pos))) continue;
            BlockState state;
            if (part.getValue()) state = RusFlora.stem(kind).getDefaultState();
            else {
                int s = random.nextInt(5) == 0 ? startStage(kind, random) : stage;
                state = RusFlora.leaves(kind).getDefaultState().with(ShrubLeavesBlock.STAGE, s);
            }
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
            placed++;
        }
        return placed > 0;
    }

    /** A whole bush blooms or fruits together: half are plain, the rest in flower or with fruit. */
    private static int startStage(ShrubKind kind, Random random) {
        int roll = random.nextInt(4);
        if (roll < 2) return 0;
        if (roll == 2) return kind.flowers ? 1 : kind.fruits() ? 2 : 0;
        return kind.fruits() ? 2 : kind.flowers ? 1 : 0;
    }

    private static boolean soil(BlockState state, ShrubKind kind) {
        return state.isIn(BlockTags.DIRT) || (kind == ShrubKind.WILLOW || kind == ShrubKind.BIRD_CHERRY) && state.isIn(BlockTags.SAND);
    }

    static boolean free(BlockState state) { return state.isAir() || state.isIn(BlockTags.REPLACEABLE_BY_TREES); }

    /** Bare stems from y=0 up to top, the centre one and up to extra more around it. */
    private static void stems(Map<BlockPos, Boolean> parts, int top, int extra, Random random) {
        for (int y = 0; y <= top; y++) parts.put(new BlockPos(0, y, 0), true);
        for (int i = 0; i < extra; i++) {
            int dx = random.nextInt(3) - 1, dz = random.nextInt(3) - 1;
            if (dx == 0 && dz == 0) continue;
            int t = random.nextInt(top + 1);
            for (int y = 0; y <= t; y++) parts.put(new BlockPos(dx, y, dz), true);
        }
    }

    private static boolean ragged(float value, Random random) { return value <= 1 + (random.nextFloat() - 0.6f) * 0.45f; }

    private static void leaf(Map<BlockPos, Boolean> parts, int x, int y, int z) { parts.putIfAbsent(new BlockPos(x, y, z), false); }

    /** An ellipsoid of leaves, horizontal radius r and vertical radius v, centred at height cy, above a lowest row. */
    private static void ellipsoid(Map<BlockPos, Boolean> parts, Random random, float r, float v, float cy, int lowest) {
        int R = (int) Math.ceil(r), top = (int) Math.ceil(cy + v);
        for (int y = lowest; y <= top; y++)
            for (int x = -R; x <= R; x++)
                for (int z = -R; z <= R; z++) {
                    float d = (x * x + z * z) / (r * r) + (y - cy) * (y - cy) / (v * v);
                    if (ragged(d, random)) leaf(parts, x, y, z);
                }
    }

    /** Hazel: many stems spreading upwards, so the bush is wider at the top than at the foot. */
    private static void vase(Map<BlockPos, Boolean> parts, Random random) {
        int h = 4 + random.nextInt(2);
        stems(parts, 1, 3, random);
        for (int y = 1; y <= h; y++) {
            float t = (y - 1) / (float) (h - 1);
            float r = y == h ? 1.7f : 1.1f + t * 1.6f;
            int R = (int) Math.ceil(r);
            for (int x = -R; x <= R; x++)
                for (int z = -R; z <= R; z++)
                    if (ragged((x * x + z * z) / (r * r), random) && (y > 1 || x * x + z * z <= 1)) leaf(parts, x, y, z);
        }
    }

    /** Bird cherry: two-three bare stems under a big rounded crown. */
    private static void crown(Map<BlockPos, Boolean> parts, Random random) {
        int h = 5 + random.nextInt(3);
        stems(parts, 2, 2, random);
        float v = (h - 2) / 2f + 0.3f;
        ellipsoid(parts, random, 2.3f + random.nextFloat() * 0.7f, v, 2 + (h - 2) / 2f, 2);
    }

    /** Juniper: mostly a narrow dark cone in pine woods, sometimes a low spreading mat on dry hills. */
    private static void cone(Map<BlockPos, Boolean> parts, Random random) {
        if (random.nextInt(10) < 3) { ellipsoid(parts, random, 2f + random.nextFloat() * 0.6f, 1.3f, 0, 0); return; }
        int h = 3 + random.nextInt(3);
        for (int y = 0; y < h; y++) {
            float r = 1.4f - 1.1f * y / (h - 1);
            int R = (int) Math.ceil(r);
            for (int x = -R; x <= R; x++)
                for (int z = -R; z <= R; z++)
                    if (x == 0 && z == 0 || ragged((x * x + z * z) / (r * r), random)) leaf(parts, x, y, z);
        }
    }
}
