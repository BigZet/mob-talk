package io.github.mobtalk.game;

import io.github.mobtalk.core.RoadPlan;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

/** The stop where a road meets its village (docs/roads.md; Dmitriy 2026-10-25: the road ran into a house and stopped there, «when the
 * road ends at a village there is a station»). A paved square where the road comes in, with a roofed bench on one side and a hitching
 * rail with lanterns, a hay bale and a water trough on the other; from it a path of trodden earth leads into the village's own streets,
 * round the houses. Built once, by the chunk that holds the road's end, with the eight chunks round it loaded. Like the road it keeps
 * off the village's pieces and off anything not natural. */
final class RoadStation {
    private RoadStation() { }

    /** How far the square reaches along the road and across it, how far back from the road's end its middle is, and how far the path
     * into the village may go looking for a street. */
    static final int ALONG = 4, ACROSS = 6, BACK = 4, HEAD = 4, PATH_REACH = 28;
    private static final int FLAGS = Block.NOTIFY_LISTENERS, FLAGS_SHAPED = Block.NOTIFY_ALL;

    /** Builds the station at point {@code end} of the road, the village lying beyond it ({@code atStart}: before the first point).
     * Returns the number of blocks placed. */
    static int build(ServerWorld world, RoadBuilder.Palette palette, List<RoadPlan.Point> points, int end, boolean atStart, List<BlockBox> pieces) {
        RoadPlan.Point e = points.get(end);
        double dx = atStart ? -e.dx() : e.dx(), dz = atStart ? -e.dz() : e.dz(); // toward the village
        double nx = -dz, nz = dx; // across the road
        int feet = (e.h2() + 1) >> 1;
        double cx = e.x() - dx * BACK, cz = e.z() - dz * BACK;
        Builder b = new Builder(world, palette, feet, pieces);
        // The square.
        for (double along = -ALONG; along <= ALONG; along += 0.5)
            for (double across = -ACROSS; across <= ACROSS; across += 0.5)
                b.floor(MathHelper.floor(cx + dx * along + nx * across), MathHelper.floor(cz + dz * along + nz * across));
        // The shelter on one side: a bench under a slab roof on four posts, a lantern under the roof.
        Direction back = Direction.getFacing(-nx, 0, -nz);
        for (int along = -2; along <= 2; along++) b.put(at(cx, cz, dx, dz, nx, nz, along, -6), 0, palette.stairs().getDefaultState().with(StairsBlock.FACING, back));
        for (int along = -3; along <= 3; along += 6)
            for (int across = -6; across <= -4; across += 2)
                for (int k = 0; k <= 2; k++) b.put(at(cx, cz, dx, dz, nx, nz, along, across), k, palette.post().getDefaultState());
        for (int along = -3; along <= 3; along++)
            for (int across = -6; across <= -4; across++)
                b.put(at(cx, cz, dx, dz, nx, nz, along, across), 3, palette.deckSlab().getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        b.put(at(cx, cz, dx, dz, nx, nz, 0, -5), 2, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        // The hitching rail on the other side, lanterns on its ends, the hay and the trough beside it.
        for (int along = -2; along <= 2; along++) b.put(at(cx, cz, dx, dz, nx, nz, along, 5), 0, palette.post().getDefaultState());
        b.put(at(cx, cz, dx, dz, nx, nz, -2, 5), 1, Blocks.LANTERN.getDefaultState());
        b.put(at(cx, cz, dx, dz, nx, nz, 2, 5), 1, Blocks.LANTERN.getDefaultState());
        b.put(at(cx, cz, dx, dz, nx, nz, 3, 5), 0, Blocks.HAY_BLOCK.getDefaultState());
        b.put(at(cx, cz, dx, dz, nx, nz, -3, 5), 0, Blocks.WATER_CAULDRON.getDefaultState().with(LeveledCauldronBlock.LEVEL, 3));
        b.path(MathHelper.floor(e.x() + dx), MathHelper.floor(e.z() + dz));
        return b.placed;
    }

    private static long at(double cx, double cz, double dx, double dz, double nx, double nz, int along, int across) {
        return BlockPos.asLong(MathHelper.floor(cx + dx * along + nx * across), 0, MathHelper.floor(cz + dz * along + nz * across));
    }

    private static final class Builder {
        final ServerWorld world; final RoadBuilder.Palette palette; final int feet; final List<BlockBox> pieces;
        /** The square's columns that were paved: the furniture stands only on those. */
        final Set<Long> paved = new HashSet<>();
        final BlockPos.Mutable at = new BlockPos.Mutable();
        int placed;
        Builder(ServerWorld world, RoadBuilder.Palette palette, int feet, List<BlockBox> pieces) { this.world = world; this.palette = palette; this.feet = feet; this.pieces = pieces; }

        private boolean loaded(int x, int z) { return world.getChunkManager().getWorldChunk(x >> 4, z >> 4) != null; }
        private BlockState get(int x, int y, int z) { return world.getBlockState(at.set(x, y, z)); }
        private void set(int x, int y, int z, BlockState state, int flags) {
            if (get(x, y, z) != state) { world.setBlockState(at.set(x, y, z), state, flags); placed++; }
        }
        private boolean ours(BlockState state) { return RoadBuilder.natural(state) || palette.owns(state.getBlock()); }

        /** One column of the square: paving at the road's level, fill under it, head room over it. A column in the village, with
         * anything built in it, or in a slope higher than the head room is left as it is. */
        void floor(int x, int z) {
            long key = BlockPos.asLong(x, 0, z);
            if (paved.contains(key) || !loaded(x, z) || RoadBuilder.inPiece(pieces, x, z, feet)) return;
            int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (top > feet + HEAD) return;
            for (int y = feet - 1; y <= feet + HEAD; y++) if (!ours(get(x, y, z))) return;
            Block[] paving = palette.paving();
            set(x, feet - 1, z, paving[Math.floorMod(x * 31 + z * 17 + (x ^ z) * 7, paving.length)].getDefaultState(), FLAGS);
            for (int y = feet - 2; y > feet - 8; y--) {
                BlockState state = get(x, y, z);
                if (!(state.isAir() || state.isIn(BlockTags.REPLACEABLE) || state.getBlock() instanceof FluidBlock || state.isIn(BlockTags.FLOWERS))) break;
                set(x, y, z, palette.fill().getDefaultState(), FLAGS);
            }
            for (int y = feet; y <= feet + HEAD; y++) if (!palette.owns(get(x, y, z).getBlock())) set(x, y, z, Blocks.AIR.getDefaultState(), FLAGS);
            paved.add(key);
        }

        /** A piece of the station {@code up} blocks over the square, on a paved column. */
        void put(long column, int up, BlockState state) {
            if (!paved.contains(column)) return;
            int x = BlockPos.unpackLongX(column), z = BlockPos.unpackLongZ(column);
            if (!ours(get(x, feet + up, z))) return;
            set(x, feet + up, z, state, FLAGS_SHAPED);
        }

        /** The path into the village: from the road's end over the open ground (a step of one block at most) to the nearest street of
         * the village, trodden to a path three blocks wide. Nothing is done when no street is in reach. */
        void path(int sx, int sz) {
            if (!loaded(sx, sz)) return;
            Map<Long, Long> came = new HashMap<>();
            ArrayDeque<long[]> queue = new ArrayDeque<>();
            long start = BlockPos.asLong(sx, 0, sz);
            came.put(start, start);
            queue.add(new long[] { start, surface(sx, sz) });
            long goal = Long.MIN_VALUE;
            while (!queue.isEmpty() && goal == Long.MIN_VALUE) {
                long[] node = queue.poll();
                int x = BlockPos.unpackLongX(node[0]), z = BlockPos.unpackLongZ(node[0]);
                for (Direction direction : Direction.Type.HORIZONTAL) {
                    int nx = x + direction.getOffsetX(), nz = z + direction.getOffsetZ();
                    long key = BlockPos.asLong(nx, 0, nz);
                    if (came.containsKey(key) || Math.abs(nx - sx) > PATH_REACH || Math.abs(nz - sz) > PATH_REACH || !loaded(nx, nz)) continue;
                    int y = surface(nx, nz);
                    if (Math.abs(y - node[1]) > 1) continue;
                    BlockState ground = get(nx, y - 1, nz);
                    boolean street = ground.isOf(Blocks.DIRT_PATH) && !paved.contains(key);
                    if (!street && !walkable(ground)) continue;
                    came.put(key, node[0]);
                    if (street && Math.abs(nx - sx) + Math.abs(nz - sz) > 2) { goal = key; break; }
                    queue.add(new long[] { key, y });
                }
            }
            if (goal == Long.MIN_VALUE) return;
            for (long node = came.get(goal); node != start; node = came.get(node)) {
                int x = BlockPos.unpackLongX(node), z = BlockPos.unpackLongZ(node);
                tread(x, z);
                for (Direction direction : Direction.Type.HORIZONTAL) tread(x + direction.getOffsetX(), z + direction.getOffsetZ());
            }
        }

        /** The height a walker stands at on this column. */
        private int surface(int x, int z) { return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z); }

        private static boolean walkable(BlockState ground) {
            return ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.COARSE_DIRT) || ground.isOf(Blocks.PODZOL)
                    || ground.isIn(BlockTags.SAND) || ground.isOf(Blocks.GRAVEL) || ground.isOf(Blocks.SNOW_BLOCK) || ground.isOf(Blocks.DIRT_PATH);
        }

        /** Treads one column of grass or earth into a path (the road's paving and the village's own blocks are left alone). */
        private void tread(int x, int z) {
            if (!loaded(x, z) || paved.contains(BlockPos.asLong(x, 0, z))) return;
            int y = surface(x, z) - 1;
            BlockState ground = get(x, y, z);
            if (!(ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.COARSE_DIRT) || ground.isOf(Blocks.PODZOL))) return;
            BlockState over = get(x, y + 1, z);
            if (!over.isAir()) {
                if (!(over.isIn(BlockTags.REPLACEABLE) || over.isIn(BlockTags.FLOWERS)) || over.getBlock() instanceof FluidBlock) return;
                set(x, y + 1, z, Blocks.AIR.getDefaultState(), FLAGS);
                if (get(x, y + 2, z).getBlock() instanceof TallPlantBlock) set(x, y + 2, z, Blocks.AIR.getDefaultState(), FLAGS);
            }
            set(x, y, z, Blocks.DIRT_PATH.getDefaultState(), FLAGS);
        }
    }
}
