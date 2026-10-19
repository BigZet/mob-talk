package io.github.mobtalk.core.place;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A box of block classes around one creature, with its feet at (0,0,0): the only thing the place analysis, the object grouping
 * and the classifier ever read, so all three run on generated scenes exactly as on a sampled world. Coordinates are relative;
 * x grows east, y up, z south, as in Minecraft. Cells outside the box read as air of an unknown kind. */
public final class VoxelGrid {
    public final int radiusXZ, radiusY, sizeXZ, sizeY;
    private final byte[] material;
    private final short[] block;
    private final List<String> names = new ArrayList<>();
    private final Map<String, Short> nameIds = new HashMap<>();
    private final Map<Integer, float[]> flows = new HashMap<>();
    /** Which cells the player changed lately: 0 untouched, {@link #PLACED} or {@link #BROKEN}. */
    private final byte[] changed;
    /** Per column, whether nothing but sky lies above the box: a surface column, not a cave roof. */
    private final boolean[] openAbove;
    public static final byte PLACED = 1, BROKEN = 2;

    public VoxelGrid(int radiusXZ, int radiusY) {
        this.radiusXZ = radiusXZ; this.radiusY = radiusY;
        sizeXZ = 2 * radiusXZ + 1; sizeY = 2 * radiusY + 1;
        int cells = sizeXZ * sizeY * sizeXZ;
        material = new byte[cells]; block = new short[cells]; changed = new byte[cells];
        openAbove = new boolean[sizeXZ * sizeXZ];
        Arrays.fill(openAbove, true);
        nameId("minecraft:air");
    }

    public boolean inside(int x, int y, int z) {
        return x >= -radiusXZ && x <= radiusXZ && z >= -radiusXZ && z <= radiusXZ && y >= -radiusY && y <= radiusY;
    }
    /** Cell index, or -1 outside the box. */
    public int index(int x, int y, int z) {
        return inside(x, y, z) ? ((x + radiusXZ) * sizeY + (y + radiusY)) * sizeXZ + (z + radiusXZ) : -1;
    }
    public int column(int x, int z) { return (x + radiusXZ) * sizeXZ + (z + radiusXZ); }

    public Material material(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? Material.AIR : Material.of(material[i]);
    }
    public byte materialId(int index) { return material[index]; }
    public boolean passable(int x, int y, int z) { return material(x, y, z).passable; }
    /** The block id ("minecraft:bedrock") of a cell, "minecraft:air" outside. */
    public String block(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? "minecraft:air" : names.get(block[i]);
    }
    public short blockId(int index) { return block[index]; }
    public String blockName(short id) { return names.get(id); }

    public void set(int x, int y, int z, Material kind, String blockName) {
        int i = index(x, y, z);
        if (i < 0) return;
        material[i] = (byte) kind.ordinal();
        block[i] = nameId(blockName);
    }
    public void set(int x, int y, int z, Material kind) { set(x, y, z, kind, defaultName(kind)); }
    /** Fills a box inclusive of both corners. */
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material kind, String blockName) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, kind, blockName);
    }
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material kind) { fill(x1, y1, z1, x2, y2, z2, kind, defaultName(kind)); }

    public void flow(int x, int y, int z, float fx, float fy, float fz) {
        int i = index(x, y, z);
        if (i >= 0 && (fx != 0 || fy != 0 || fz != 0)) flows.put(i, new float[]{fx, fy, fz});
    }
    /** The fluid's current in a cell, or null when still or not a fluid. */
    public float[] flow(int x, int y, int z) { return flows.get(index(x, y, z)); }

    public void changed(int x, int y, int z, byte how) { int i = index(x, y, z); if (i >= 0) changed[i] = how; }
    public byte changed(int x, int y, int z) { int i = index(x, y, z); return i < 0 ? 0 : changed[i]; }

    public void openAbove(int x, int z, boolean open) { if (inside(x, 0, z)) openAbove[column(x, z)] = open; }
    public boolean openAbove(int x, int z) { return inside(x, 0, z) && openAbove[column(x, z)]; }

    /** The 16×16×16 cube the classifier reads: x and z from -8 to 7, y from -7 to 8, so the creature's feet sit at (8,7,8). */
    public static final int CUBE = 16, CUBE_X0 = -8, CUBE_Y0 = -7, CUBE_Z0 = -8;
    public byte[] cube() {
        byte[] out = new byte[CUBE * CUBE * CUBE];
        for (int x = 0; x < CUBE; x++) for (int y = 0; y < CUBE; y++) for (int z = 0; z < CUBE; z++)
            out[(x * CUBE + y) * CUBE + z] = (byte) material(CUBE_X0 + x, CUBE_Y0 + y, CUBE_Z0 + z).ordinal();
        return out;
    }

    /** The same cube as {@link #cube()}, with each cell's {@link BlockFamily} instead of its material. */
    public byte[] familyCube() {
        byte[] families = new byte[names.size()];
        for (int i = 0; i < families.length; i++) families[i] = (byte) BlockFamily.of(names.get(i)).ordinal();
        byte[] out = new byte[CUBE * CUBE * CUBE];
        for (int x = 0; x < CUBE; x++) for (int y = 0; y < CUBE; y++) for (int z = 0; z < CUBE; z++) {
            int i = index(CUBE_X0 + x, CUBE_Y0 + y, CUBE_Z0 + z);
            out[(x * CUBE + y) * CUBE + z] = i < 0 ? 0 : families[block[i]];
        }
        return out;
    }

    /** The block ids of the classifier's cube as indices into {@code palette} (filled by this call), laid out like {@link #cube()}. */
    public short[] blockCube(List<String> palette) {
        Map<Short, Short> local = new HashMap<>();
        short[] out = new short[CUBE * CUBE * CUBE];
        for (int x = 0; x < CUBE; x++) for (int y = 0; y < CUBE; y++) for (int z = 0; z < CUBE; z++) {
            int i = index(CUBE_X0 + x, CUBE_Y0 + y, CUBE_Z0 + z);
            short id = i < 0 ? 0 : block[i];
            Short mapped = local.get(id);
            if (mapped == null) { mapped = (short) palette.size(); palette.add(names.get(id)); local.put(id, mapped); }
            out[(x * CUBE + y) * CUBE + z] = mapped;
        }
        return out;
    }

    private short nameId(String name) {
        Short id = nameIds.get(name);
        if (id != null) return id;
        short next = (short) names.size();
        names.add(name); nameIds.put(name, next);
        return next;
    }
    static String defaultName(Material kind) {
        return switch (kind) {
            case AIR -> "minecraft:air"; case SOLID -> "minecraft:stone"; case UNBREAKABLE -> "minecraft:bedrock"; case GLASS -> "minecraft:glass";
            case WATER -> "minecraft:water"; case LAVA -> "minecraft:lava"; case DOOR_OPEN, DOOR_CLOSED -> "minecraft:oak_door";
            case FENCE -> "minecraft:oak_fence"; case CLIMBABLE -> "minecraft:ladder"; case HAZARD -> "minecraft:fire";
            case WOOD -> "minecraft:oak_planks"; case LEAVES -> "minecraft:oak_leaves"; case PLANT -> "minecraft:short_grass";
            case FARMLAND -> "minecraft:farmland"; case MACHINE -> "minecraft:piston";
        };
    }
}
