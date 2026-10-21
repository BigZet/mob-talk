package io.github.divineblock.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/** The church itself, drawn by {@link ChurchBuilder} from its origin (the floor under the tower's west door is x = 0) and
 * turned as its churchyard is turned. Its box starts at the floor: the terrain adaptation fills the ground up to just under it
 * (its level is the box's lowest y). */
public final class ChurchPiece extends StructurePiece {
    final Frame frame;
    final ChurchBuilder.Variant variant;
    final long seed;

    /** The church's own coordinates turned and moved into the world: local +x (the altar's east) becomes rotation(+x). */
    public record Frame(BlockPos origin, BlockRotation rotation) {
        public BlockPos world(int x, int y, int z) { return origin.add(new BlockPos(x, y, z).rotate(rotation)); }
        public BlockBox box(int x0, int y0, int z0, int x1, int y1, int z1) { return BlockBox.create(world(x0, y0, z0), world(x1, y1, z1)); }
        void write(NbtCompound nbt) {
            nbt.putInt("OX", origin.getX()); nbt.putInt("OY", origin.getY()); nbt.putInt("OZ", origin.getZ());
            nbt.putString("Rot", rotation.name());
        }
        static Frame read(NbtCompound nbt) {
            BlockRotation rotation = BlockRotation.NONE;
            for (BlockRotation r : BlockRotation.values()) if (r.name().equals(nbt.getString("Rot"))) rotation = r;
            return new Frame(new BlockPos(nbt.getInt("OX"), nbt.getInt("OY"), nbt.getInt("OZ")), rotation);
        }
    }

    public ChurchPiece(Frame frame, ChurchBuilder.Variant variant, long seed) {
        super(ChurchWorldgen.CHURCH_PIECE, 0, box(frame));
        this.frame = frame; this.variant = variant; this.seed = seed;
    }

    public ChurchPiece(StructureContext context, NbtCompound nbt) {
        super(ChurchWorldgen.CHURCH_PIECE, nbt);
        frame = Frame.read(nbt);
        variant = nbt.getString("Variant").equals("WOOD") ? ChurchBuilder.Variant.WOOD : ChurchBuilder.Variant.STONE;
        seed = nbt.getLong("Seed");
    }

    static BlockBox box(Frame frame) {
        return frame.box(ChurchBuilder.MIN_X, 0, ChurchBuilder.MIN_Z, ChurchBuilder.MAX_X, ChurchBuilder.MAX_Y, ChurchBuilder.MAX_Z);
    }

    @Override protected void writeNbt(StructureContext context, NbtCompound nbt) {
        frame.write(nbt);
        nbt.putString("Variant", variant.name());
        nbt.putLong("Seed", seed);
    }

    public ChurchSites.Site site() { return ChurchSites.Site.of(frame, variant); }

    @Override public void generate(StructureWorldAccess world, StructureAccessor structures, ChunkGenerator generator, Random random,
                                   BlockBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        new ChurchBuilder(canvas(world, chunkBox, frame, random), seed, variant).build();
        bossSeal(world, chunkBox);
        ChurchSites.decorate(world, chunkBox, site(), random);
    }

    /** The bosses thread's seal (divineblock:boss_seal) under the sanctuary floor: it wakes the church's guardian. Looked up by
     * id only, so the church builds without that code; absent, nothing is placed. */
    private void bossSeal(StructureWorldAccess world, BlockBox chunkBox) {
        BlockPos at = frame.world(ChurchBuilder.SEAL_X, 0, ChurchBuilder.SEAL_Z);
        if (!chunkBox.contains(at)) return;
        var block = Registries.BLOCK.getOrEmpty(BOSS_SEAL);
        if (block.isEmpty()) return;
        world.setBlockState(at, block.get().getDefaultState(), 2);
        BlockEntity seal = world.getBlockEntity(at);
        if (seal == null) return;
        NbtCompound nbt = new NbtCompound();
        nbt.putString("boss", "church");
        nbt.putInt("power", variant == ChurchBuilder.Variant.STONE ? 3 : 2);
        nbt.putInt("radius", 20);
        nbt.putBoolean("night", false);
        nbt.putInt("cooldownDays", 1);
        seal.read(nbt, world.getRegistryManager());
        seal.markDirty();
    }
    static final Identifier BOSS_SEAL = Identifier.of("divineblock", "boss_seal");

    /** Writes into the chunk being generated only, turning blocks with the church; the foundation goes down through air,
     * water and leaves to the ground. */
    static ChurchBuilder.Canvas canvas(StructureWorldAccess world, BlockBox chunkBox, Frame frame, Random random) {
        return new ChurchBuilder.Canvas() {
            @Override public void set(int x, int y, int z, BlockState state) {
                BlockPos at = frame.world(x, y, z);
                if (chunkBox.contains(at)) world.setBlockState(at, state.rotate(frame.rotation()), 2);
            }
            @Override public void foundation(int x, int y, int z, BlockState state) {
                BlockPos top = frame.world(x, y, z);
                for (int depth = 0; depth < 18; depth++) {
                    BlockPos at = top.down(depth);
                    if (!chunkBox.contains(at) || at.getY() <= world.getBottomY()) return;
                    if (depth > 0 && world.getBlockState(at).isSolidBlock(world, at)) return;
                    world.setBlockState(at, state, 2);
                }
            }
            @Override public void chest(int x, int y, int z, BlockState state) {
                BlockPos at = frame.world(x, y, z);
                if (!chunkBox.contains(at)) return;
                world.setBlockState(at, state.rotate(frame.rotation()), 2);
                LootableInventory.setLootTable(world, random, at, ChurchWorldgen.LOOT);
            }
        };
    }
}
