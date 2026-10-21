package io.github.divineblock.world;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.structure.Structure;

/** Where a generated church keeps its places, for other parts of the mod (the bosses thread, the cemetery thread):
 * {@link #at} finds the church around a position at runtime; {@link #DECORATORS} run while a church is generated.
 * See docs/church-cemetery-interface.md. */
public final class ChurchSites {
    private ChurchSites() { }

    /** A church in world coordinates (turned by rotation from the plan in {@link ChurchBuilder}). Boss points stand on the
     * floor, free of blocks: the middle of the nave, the refectory, before the west door, and the middle of the churchyard
     * on its central alley. */
    public record Site(ChurchBuilder.Variant variant, BlockPos origin, BlockRotation rotation, BlockPos altar, BlockPos naveCenter, BlockPos chest,
                       List<BlockPos> bossPoints, BlockBox church, BlockBox cemetery) {
        static Site of(ChurchPiece.Frame frame, ChurchBuilder.Variant variant) {
            BlockPos nave = frame.world(ChurchBuilder.NAVE_X, 1, ChurchBuilder.NAVE_Z);
            int middle = (ChurchBuilder.CEMETERY_MIN_Z + ChurchBuilder.CEMETERY_MAX_Z) / 2;
            return new Site(variant, frame.origin(), frame.rotation(), frame.world(ChurchBuilder.ALTAR_X, 1, ChurchBuilder.ALTAR_Z), nave,
                    frame.world(ChurchBuilder.CHEST_X, 1, ChurchBuilder.CHEST_Z),
                    List.of(nave, frame.world(10, 1, 1), frame.world(-6, 0, 0), frame.world(ChurchBuilder.CEMETERY_ENTRANCE_X, 0, middle)),
                    ChurchPiece.box(frame), CemeteryPiece.box(frame));
        }
    }

    /** Runs at the end of generating a church, once per chunk it touches: write blocks only inside chunkBox, and spawn
     * entities only at points inside it (or the boss appears several times). Runs on a world generation thread. */
    public interface Decorator { void decorate(StructureWorldAccess world, BlockBox chunkBox, Site site, Random random); }
    public static final List<Decorator> DECORATORS = new CopyOnWriteArrayList<>();

    static void decorate(StructureWorldAccess world, BlockBox chunkBox, Site site, Random random) {
        for (Decorator decorator : DECORATORS) decorator.decorate(world, chunkBox, site, random);
    }

    /** The church whose pieces contain pos, on the server thread. */
    public static Optional<Site> at(ServerWorld world, BlockPos pos) {
        Structure structure = world.getRegistryManager().get(RegistryKeys.STRUCTURE).get(ChurchWorldgen.STRUCTURE);
        if (structure == null) return Optional.empty();
        StructureStart start = world.getStructureAccessor().getStructureContaining(pos, structure);
        if (start == null || !start.hasChildren()) return Optional.empty();
        for (StructurePiece piece : start.getChildren()) if (piece instanceof ChurchPiece church) return Optional.of(church.site());
        return Optional.empty();
    }
}
