package io.github.mobtalk.game;

import io.github.mobtalk.core.RoadPlan;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.biome.Biome;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** The odds and ends beside a road (Dmitriy 2026-10-25: «камушки и прочее, чтобы дорога была чуть интереснее, возможно в зависимости от
 * биома»): on the strip beyond the curb, about one piece every {@link #EVERY} blocks on one side or the other, chosen for the
 * biome of the spot — pebbles (grey, mossy or sandy), a cairn in the mountains and the snow, a fallen log of the forest's wood, a
 * dead bush in the dry lands — and a verst post every {@link #VERST} blocks on the right. Each piece lies on the bank's turf at the
 * road's level; the paved way, the curb and the lamps are never touched. */
final class RoadDecor {
    private RoadDecor() { }

    /** A piece about every this many points; a verst post every this many points, counted from the road's start. */
    static final int EVERY = 7, VERST = 100;
    /** How far in from each end the pieces begin (a junction's square and its lamps stand there). */
    static final int END_MARGIN = 10;

    /** Lays the pieces of this chunk. The road's columns, {@code taken} by other roads' paving, and the lamps' columns are kept clear. */
    static int lay(ServerWorld world, ChunkPos chunk, Map<Long, RoadBuilder.Column> columns, List<RoadPlan.Point> points, int from, int to,
                   Map<Long, Integer> taken) {
        int placed = 0;
        long base = points.hashCode() * 1000003L;
        for (int i = Math.max(from + END_MARGIN, 0); i <= Math.min(to - END_MARGIN, points.size() - 1); i++) {
            RoadPlan.Point p = points.get(i);
            if (p.kind() != RoadPlan.Kind.GROUND) continue;
            Random random = new Random(base + i * 7919L);
            boolean verst = (i - from) % VERST == VERST / 2;
            if (!verst && random.nextInt(EVERY) != 0) continue;
            int side = verst ? 1 : random.nextBoolean() ? 1 : -1;
            double out = RoadBuilder.CURB + (verst ? 1.3 : 0.9 + random.nextDouble() * 1.6);
            double nx = -p.dz(), nz = p.dx();
            int x = MathHelper.floor(p.x() + nx * side * out), z = MathHelper.floor(p.z() + nz * side * out);
            if ((x >> 4) != chunk.x || (z >> 4) != chunk.z) continue;
            if (!clearOfRoad(columns, taken, x, z)) continue;
            int feet = (p.h2() + 1) >> 1;
            int y = ground(world, x, z, feet + 4, feet - 4);
            if (y == Integer.MIN_VALUE) continue;
            BlockPos pos = new BlockPos(x, y, z);
            placed += verst ? post(world, pos) : piece(world, pos, p, columns, taken, random);
        }
        return placed;
    }

    /** The column is beyond the curb (or beyond the road's reach altogether) and holds no other road's paving. */
    private static boolean clearOfRoad(Map<Long, RoadBuilder.Column> columns, Map<Long, Integer> taken, int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        if (taken.containsKey(key)) return false;
        RoadBuilder.Column column = columns.get(key);
        return column == null || Math.abs(column.across()) >= RoadBuilder.CURB + 0.75;
    }

    /** The height a piece stands at on this column: one over the turf, looking down from {@code high} to {@code low}, with air or a
     * plant there to make room; MIN_VALUE when the ground is not turf (rock face, water, a tree, something built) or out of range. */
    private static int ground(ServerWorld world, int x, int z, int high, int low) {
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int y = high; y >= low; y--) {
            BlockState state = world.getBlockState(at.set(x, y, z));
            if (state.isAir() || state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.FLOWERS) || state.isOf(Blocks.SNOW)) continue;
            if (!turf(state) || !state.isSideSolidFullSquare(world, at, Direction.UP)) return Integer.MIN_VALUE;
            if (y + 1 > high) return Integer.MIN_VALUE;
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean turf(BlockState state) {
        return state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.TERRACOTTA)
                || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SANDSTONE) || state.isOf(Blocks.RED_SANDSTONE)
                || state.isOf(Blocks.MOSSY_COBBLESTONE) || state.isOf(Blocks.CALCITE) || state.isOf(Blocks.PACKED_ICE);
    }

    private static int post(ServerWorld world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        if (!open(above) || !roomy(world, pos)) return 0;
        if (!above.isAir()) world.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        world.setBlockState(pos, RoadContent.VERST_POST.getDefaultState(), Block.NOTIFY_LISTENERS);
        return 1;
    }

    /** The manner of a biome: which pieces lie beside a road there. */
    enum Manner { SANDY, SAVANNA, COLD, TAIGA, JUNGLE, WET, FOREST, PLAIN }

    static Manner manner(RegistryEntry<Biome> biome) {
        String key = biome.getKey().map(k -> k.getValue().getPath()).orElse("");
        if (biome.isIn(BiomeTags.IS_BADLANDS) || biome.isIn(BiomeTags.IS_BEACH) || key.contains("desert")) return Manner.SANDY;
        if (biome.isIn(BiomeTags.IS_SAVANNA)) return Manner.SAVANNA;
        if (key.contains("cherry")) return Manner.FOREST;
        if (key.contains("snowy") || key.contains("frozen") || key.contains("ice") || biome.isIn(BiomeTags.IS_MOUNTAIN) || key.contains("peaks")
                || key.contains("slopes") || key.contains("windswept") || key.contains("stony") || key.contains("grove")) return Manner.COLD;
        if (biome.isIn(BiomeTags.IS_TAIGA)) return Manner.TAIGA;
        if (biome.isIn(BiomeTags.IS_JUNGLE)) return Manner.JUNGLE;
        if (key.contains("swamp") || key.contains("mangrove")) return Manner.WET;
        if (biome.isIn(BiomeTags.IS_FOREST)) return Manner.FOREST;
        return Manner.PLAIN;
    }

    /** How many pieces in a hundred are fallen logs in a biome of this manner (the self-test expects logs where the woods are long). */
    static int logShare(Manner manner) {
        return switch (manner) {
            case SANDY, COLD -> 0;
            case SAVANNA -> 30;
            case WET -> 35;
            case TAIGA, JUNGLE, FOREST -> 40;
            case PLAIN -> 15;
        };
    }

    private static int piece(ServerWorld world, BlockPos pos, RoadPlan.Point p, Map<Long, RoadBuilder.Column> columns, Map<Long, Integer> taken, Random random) {
        RegistryEntry<Biome> biome = world.getBiome(pos);
        String key = biome.getKey().map(k -> k.getValue().getPath()).orElse("");
        Manner manner = manner(biome);
        int roll = random.nextInt(100);
        boolean fallen = roll < logShare(manner);
        Direction facing = Direction.fromHorizontal(random.nextInt(4));
        return switch (manner) {
            case SANDY -> roll < 70 ? lie(world, pos, RoadContent.SAND_PEBBLES, facing) : bush(world, pos, RoadContent.SAND_PEBBLES, facing);
            case SAVANNA -> fallen ? log(world, pos, p, RoadContent.Wood.ACACIA, columns, taken, random)
                    : roll < 70 ? lie(world, pos, RoadContent.SAND_PEBBLES, facing) : bush(world, pos, RoadContent.SAND_PEBBLES, facing);
            case COLD -> roll < 55 ? lie(world, pos, RoadContent.CAIRN, facing) : lie(world, pos, RoadContent.PEBBLES, facing);
            case TAIGA -> fallen ? log(world, pos, p, RoadContent.Wood.SPRUCE, columns, taken, random)
                    : roll < 70 ? lie(world, pos, RoadContent.MOSSY_PEBBLES, facing) : lie(world, pos, RoadContent.PEBBLES, facing);
            case JUNGLE -> fallen ? log(world, pos, p, RoadContent.Wood.JUNGLE, columns, taken, random) : lie(world, pos, RoadContent.MOSSY_PEBBLES, facing);
            case WET -> fallen ? log(world, pos, p, key.contains("mangrove") ? RoadContent.Wood.MANGROVE : RoadContent.Wood.OAK, columns, taken, random)
                    : lie(world, pos, RoadContent.MOSSY_PEBBLES, facing);
            case FOREST -> {
                RoadContent.Wood wood = key.contains("birch") ? RoadContent.Wood.BIRCH : key.contains("dark_forest") ? RoadContent.Wood.DARK_OAK
                        : key.contains("cherry") ? RoadContent.Wood.CHERRY : RoadContent.Wood.OAK;
                yield fallen ? log(world, pos, p, wood, columns, taken, random)
                        : roll < 65 ? lie(world, pos, RoadContent.MOSSY_PEBBLES, facing) : lie(world, pos, RoadContent.PEBBLES, facing);
            }
            case PLAIN -> fallen ? log(world, pos, p, RoadContent.Wood.OAK, columns, taken, random) : lie(world, pos, RoadContent.PEBBLES, facing);
        };
    }

    private static int lie(ServerWorld world, BlockPos pos, Block block, Direction facing) {
        if (block == RoadContent.CAIRN && !roomy(world, pos)) block = RoadContent.PEBBLES;
        world.setBlockState(pos, block.getDefaultState().with(RoadContent.GroundPieceBlock.FACING, facing), Block.NOTIFY_LISTENERS);
        return 1;
    }

    /** Nothing solid stands next to the spot: a piece that rises above the ground is not wedged into the bank's steps (a log
     * half-buried in a terrace looked like a stray block). */
    private static boolean roomy(ServerWorld world, BlockPos pos) {
        for (Direction side : Direction.Type.HORIZONTAL) if (!open(world.getBlockState(pos.offset(side)))) return false;
        return true;
    }

    /** A dead bush where one can grow (sand, dirt, terracotta), pebbles otherwise. */
    private static int bush(ServerWorld world, BlockPos pos, Block otherwise, Direction facing) {
        BlockState bush = Blocks.DEAD_BUSH.getDefaultState();
        if (!bush.canPlaceAt(world, pos)) return lie(world, pos, otherwise, facing);
        world.setBlockState(pos, bush, Block.NOTIFY_LISTENERS);
        return 1;
    }

    /** A fallen log two or three blocks long, lying along the road on level ground with room round it; where there is no room
     * for one, mossy pebbles lie instead. */
    private static int log(ServerWorld world, BlockPos pos, RoadPlan.Point p, RoadContent.Wood wood, Map<Long, RoadBuilder.Column> columns, Map<Long, Integer> taken, Random random) {
        Direction.Axis axis = Math.abs(p.dx()) >= Math.abs(p.dz()) ? Direction.Axis.X : Direction.Axis.Z;
        Direction along = Direction.from(axis, random.nextBoolean() ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        BlockState state = RoadContent.FALLEN_LOG.getDefaultState().with(RoadContent.FallenLogBlock.AXIS, axis).with(RoadContent.FallenLogBlock.WOOD, wood);
        int length = 2 + random.nextInt(2);
        int fit = fit(world, pos, along, length, columns, taken);
        if (fit < 2) { along = along.getOpposite(); fit = fit(world, pos, along, length, columns, taken); }
        if (fit < 2) return lie(world, pos, RoadContent.MOSSY_PEBBLES, Direction.fromHorizontal(random.nextInt(4)));
        for (int k = 0; k < fit; k++) world.setBlockState(pos.offset(along, k), state, Block.NOTIFY_LISTENERS);
        return fit;
    }

    /** How many segments of a log of this length can lie from {@code pos} along {@code along}: each on turf at the same height, free,
     * with both ends of the log in the open and at most one of its long sides against a step (a log at the foot of a bank is
     * fine; one wedged into a notch is not). */
    private static int fit(ServerWorld world, BlockPos pos, Direction along, int length, Map<Long, RoadBuilder.Column> columns, Map<Long, Integer> taken) {
        int fit = 0;
        for (int k = 0; k < length; k++) {
            BlockPos at = pos.offset(along, k);
            // Free at the piece's own height, turf right under it (the first segment stands where lay() put it; the others must match).
            if (!clearOfRoad(columns, taken, at.getX(), at.getZ()) || ground(world, at.getX(), at.getZ(), at.getY(), at.getY() - 1) != at.getY()) break;
            int walls = 0;
            boolean room = true;
            for (Direction side : Direction.Type.HORIZONTAL) {
                if (side == along.getOpposite() && k > 0) continue;
                if (side == along && k < length - 1) continue;
                if (open(world.getBlockState(at.offset(side)))) continue;
                if (side.getAxis() == along.getAxis() || ++walls > 1) { room = false; break; }
            }
            if (!room) break;
            fit++;
        }
        return fit;
    }

    private static boolean open(BlockState state) {
        return state.isAir() || state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.FLOWERS) || state.isOf(Blocks.SNOW);
    }

    /** Whether the block is one of the road's pieces (for the self-test's count). */
    static boolean piece(BlockState state) {
        return state.isOf(RoadContent.PEBBLES) || state.isOf(RoadContent.MOSSY_PEBBLES) || state.isOf(RoadContent.SAND_PEBBLES) || state.isOf(RoadContent.CAIRN)
                || state.isOf(RoadContent.VERST_POST) || state.isOf(RoadContent.FALLEN_LOG) || state.isOf(Blocks.DEAD_BUSH);
    }
}
