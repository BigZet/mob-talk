package io.github.divineblock.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/** Levels the cemetery's site to its planned {@link CemeteryGround.Field} before the plots are laid: earth and rock above
 * the target height go (a boulder, a hump, a stump), up to {@link #CLEAR} blocks above it; hollows and ponds are filled with
 * earth; the top is grass. Around the site the cut and fill fade into the natural ground over {@link CemeteryGround#MARGIN}
 * blocks, so no wall or ditch marks the edge. Columns inside the protected boxes (the church) are never touched. */
public final class LevelingPiece extends StructurePiece {
    private static final int CLEAR = 12, REACH = 16;
    private final CemeteryGround.Field field;
    private final List<BlockBox> protect;

    /** The box starts at the field's lowest planned ground, not below it: the structure's terrain adaptation (beard) takes every
     * piece's floor for ground and hollows the terrain out above it, so a box reaching under the ground left caves under the
     * churchyard. The cut and fill still reach under it: the piece writes its columns outside its box's height. */
    public LevelingPiece(CemeteryGround.Field field, List<BlockBox> protect, int maxY) {
        super(ChurchWorldgen.LEVELING_PIECE, 0, field.box(lowest(field), maxY).expand(CemeteryGround.MARGIN, 0, CemeteryGround.MARGIN));
        this.field = field;
        this.protect = List.copyOf(protect);
        CemeteryGround.register(field);
    }

    public LevelingPiece(StructureContext context, NbtCompound nbt) {
        super(ChurchWorldgen.LEVELING_PIECE, nbt);
        field = new CemeteryGround.Field(nbt.getInt("FX"), nbt.getInt("FZ"), nbt.getInt("SX"), nbt.getInt("SZ"), nbt.getIntArray("H"));
        List<BlockBox> boxes = new ArrayList<>();
        for (NbtElement e : nbt.getList("Protect", NbtElement.INT_ARRAY_TYPE)) {
            int[] b = ((NbtIntArray) e).getIntArray();
            boxes.add(new BlockBox(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        protect = boxes;
        CemeteryGround.register(field);
    }

    @Override protected void writeNbt(StructureContext context, NbtCompound nbt) {
        nbt.putInt("FX", field.minX()); nbt.putInt("FZ", field.minZ()); nbt.putInt("SX", field.sizeX()); nbt.putInt("SZ", field.sizeZ());
        nbt.putIntArray("H", CemeteryGround.copy(field.heights()));
        NbtList list = new NbtList();
        for (BlockBox b : protect) list.add(new NbtIntArray(new int[] { b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ() }));
        nbt.put("Protect", list);
    }

    private static int lowest(CemeteryGround.Field field) {
        int min = Integer.MAX_VALUE;
        for (int h : field.heights()) min = Math.min(min, h);
        return min;
    }

    private boolean protectedColumn(int x, int z) {
        for (BlockBox b : protect) if (x >= b.getMinX() && x <= b.getMaxX() && z >= b.getMinZ() && z <= b.getMaxZ()) return true;
        return false;
    }

    @Override public void generate(StructureWorldAccess world, StructureAccessor structures, ChunkGenerator generator, Random random,
                                   BlockBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int x = Math.max(boundingBox.getMinX(), chunkBox.getMinX()); x <= Math.min(boundingBox.getMaxX(), chunkBox.getMaxX()); x++)
            for (int z = Math.max(boundingBox.getMinZ(), chunkBox.getMinZ()); z <= Math.min(boundingBox.getMaxZ(), chunkBox.getMaxZ()); z++) {
                if (protectedColumn(x, z)) continue;
                boolean inside = field.contains(x, z);
                int planned = field.nearest(x, z);
                int g = groundAt(world, at, x, z, planned);
                int t;
                if (inside) t = planned;
                else {
                    int dx = Math.max(field.minX() - x, x - (field.minX() + field.sizeX() - 1)), dz = Math.max(field.minZ() - z, z - (field.minZ() + field.sizeZ() - 1));
                    double w = Math.max(Math.max(dx, dz), 0) / (double) (CemeteryGround.MARGIN + 1);
                    t = (int) Math.round(planned + (g - planned) * w);
                    if (t == g) continue;
                }
                level(world, at, x, z, g, t, inside);
            }
    }

    /** The top ground block of the column near the planned height (the lowest searched when there is a hole). */
    private static int groundAt(StructureWorldAccess world, BlockPos.Mutable at, int x, int z, int planned) {
        for (int y = planned + REACH; y >= planned - REACH; y--) {
            at.set(x, y, z);
            if (CemeteryGround.ground(world, at, world.getBlockState(at))) return y;
        }
        return planned - REACH;
    }

    private static void level(StructureWorldAccess world, BlockPos.Mutable at, int x, int z, int g, int t, boolean inside) {
        // Above the target: rock, earth, boulders and whatever stands on them go; inside the site everything up to CLEAR.
        int top = inside ? t + CLEAR : Math.max(g, t) + 2;
        for (int y = t + 1; y <= top; y++) {
            at.set(x, y, z);
            BlockState state = world.getBlockState(at);
            if (state.isAir()) continue;
            if (!inside && y > g && !state.isReplaceable() && !state.isOf(Blocks.SNOW)) continue; // keep what stands beside, only lose what floats
            world.setBlockState(at, Blocks.AIR.getDefaultState(), 2);
        }
        // Below it: earth up from the ground found, through water and hollows.
        for (int y = Math.min(g, t) + 1; y <= t; y++) {
            at.set(x, y, z);
            BlockState state = world.getBlockState(at);
            if (CemeteryGround.ground(world, at, state) && y < t) continue;
            world.setBlockState(at, Blocks.DIRT.getDefaultState(), 2);
        }
        if (g != t || inside) {
            at.set(x, t, z);
            BlockState surface = world.getBlockState(at);
            boolean natural = surface.isOf(Blocks.GRASS_BLOCK) || surface.isOf(Blocks.PODZOL) || surface.isOf(Blocks.COARSE_DIRT) || surface.isOf(Blocks.MYCELIUM);
            if (!natural) world.setBlockState(at, Blocks.GRASS_BLOCK.getDefaultState(), 2);
            for (int y = t - 1; y >= t - 2; y--) {  // a cut through rock shows earth under the turf
                at.set(x, y, z);
                BlockState below = world.getBlockState(at);
                if (below.isOf(Blocks.STONE) || below.isOf(Blocks.GRANITE) || below.isOf(Blocks.ANDESITE) || below.isOf(Blocks.DIORITE)
                        || below.isOf(Blocks.MOSSY_COBBLESTONE) || below.isOf(Blocks.COBBLESTONE) || below.isOf(Blocks.GRAVEL) || below.isAir()
                        || !below.getFluidState().isEmpty())
                    world.setBlockState(at, Blocks.DIRT.getDefaultState(), 2);
            }
        }
    }
}
