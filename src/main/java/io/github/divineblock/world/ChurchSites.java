package io.github.divineblock.world;

import java.util.ArrayList;
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
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.structure.Structure;

/** Where a generated church keeps its places, for other parts of the mod (the bosses thread, the cemetery thread, the
 * punishments): {@link #at} finds the church around a position at runtime, {@link #yard} gives its walled churchyard,
 * {@link #isChurchBlock} tells the punishments which blocks to leave alone; {@link #DECORATORS} run while a church is
 * generated. See docs/church-cemetery-interface.md. */
public final class ChurchSites {
    private ChurchSites() { }

    /** A church in world coordinates (turned by rotation from the plan in {@link ChurchBuilder}). Boss points stand on the
     * floor, free of blocks: the middle of the nave, the refectory, before the west porch, the middle of the churchyard north
     * of the church, and the middle of the cemetery on its central alley. yard is the open ground inside the wall (its minY is
     * the ground, y = -1 in church coordinates); outdoorSpot is a safe place to stand outdoors on the path before the porch. */
    public record Site(ChurchBuilder.Variant variant, BlockPos origin, BlockRotation rotation, BlockPos altar, BlockPos naveCenter, BlockPos chest,
                       List<BlockPos> bossPoints, BlockBox church, BlockBox cemetery, BlockBox yard, BlockPos outdoorSpot) {
        static Site of(ChurchPiece.Frame frame, ChurchBuilder.Variant variant) {
            int stand = variant.stand();
            BlockPos nave = frame.world(ChurchBuilder.NAVE_X, stand, ChurchBuilder.NAVE_Z);
            int middle = (ChurchBuilder.CEMETERY_MIN_Z + ChurchBuilder.CEMETERY_MAX_Z) / 2;
            BlockPos outdoor = frame.world(ChurchBuilder.OUTDOOR_X, 0, ChurchBuilder.OUTDOOR_Z);
            BlockPos altar = ChurchPiece.altar(variant), chest = ChurchPiece.chest(variant), north = ChurchPiece.yardNorth(variant);
            return new Site(variant, frame.origin(), frame.rotation(), frame.world(altar.getX(), altar.getY(), altar.getZ()), nave,
                    frame.world(chest.getX(), chest.getY(), chest.getZ()),
                    List.of(nave, frame.world(21, stand, 0), outdoor, frame.world(north.getX(), 0, north.getZ()), frame.world(ChurchBuilder.CEMETERY_ENTRANCE_X, 0, middle)),
                    ChurchPiece.box(frame, variant), CemeteryPiece.box(frame), ChurchYard.open(frame), outdoor);
        }
        /** The analoy in the nave before the iconostasis (church x 40, z 0 on the floor, facing the door): there the rector hears
         * confessions. */
        public BlockPos analoy() { return new ChurchPiece.Frame(origin, rotation).world(40, variant.stand(), 0); }
        /** Where the rector stands when no one confesses: beside the analoy, on the carpet's edge. */
        public BlockPos rectorSpot() { return new ChurchPiece.Frame(origin, rotation).world(39, variant.stand(), -1); }
    }

    /** Runs at the end of generating a church, once per chunk it touches: write blocks only inside chunkBox, and spawn
     * entities only at points inside it (or the boss appears several times). Runs on a world generation thread. */
    public interface Decorator { void decorate(StructureWorldAccess world, BlockBox chunkBox, Site site, Random random); }
    public static final List<Decorator> DECORATORS = new CopyOnWriteArrayList<>();

    static void decorate(StructureWorldAccess world, BlockBox chunkBox, Site site, Random random) {
        for (Decorator decorator : DECORATORS) decorator.decorate(world, chunkBox, site, random);
    }

    private static Structure structure(ServerWorld world) {
        return world.getRegistryManager().get(RegistryKeys.STRUCTURE).get(ChurchWorldgen.STRUCTURE);
    }

    /** The church whose pieces contain pos, on the server thread. */
    public static Optional<Site> at(ServerWorld world, BlockPos pos) {
        Structure structure = structure(world);
        if (structure == null) return Optional.empty();
        StructureStart start = world.getStructureAccessor().getStructureContaining(pos, structure);
        if (start == null || !start.hasChildren()) return Optional.empty();
        return site(start);
    }

    /** The church whose walled yard (or church, or cemetery) holds pos. */
    public static Optional<Site> near(ServerWorld world, BlockPos pos) {
        for (StructureStart start : starts(world, pos)) {
            Optional<Site> site = site(start);
            if (site.isPresent() && (site.get().yard().expand(ChurchYard.REACH).contains(pos) || site.get().church().contains(pos)
                    || site.get().cemetery().contains(pos))) return site;
        }
        return at(world, pos);
    }

    /** The open yard inside the wall of the church around pos (pos anywhere in its yard, church or cemetery). */
    public static Optional<BlockBox> yard(ServerWorld world, BlockPos pos) { return near(world, pos).map(Site::yard); }

    /** A safe outdoor standing spot on the path before the church's porch (feet position; the ground is one below). */
    public static Optional<BlockPos> outdoorSpot(ServerWorld world, BlockPos pos) { return near(world, pos).map(Site::outdoorSpot); }

    /** Whether pos is part of a church structure that punishments must not break: the church itself with its foundation (down
     * to 14 under the floor), and the yard's wall, gates and corner towers. The open grass of the yard and the cemetery are
     * not included. Server thread; cheap enough per block, but for big areas take {@link #protectedBoxes} once. */
    public static boolean isChurchBlock(ServerWorld world, BlockPos pos) {
        for (BlockBox box : protectedBoxes(world, pos)) if (box.contains(pos)) return true;
        return false;
    }

    /** The boxes {@link #isChurchBlock} tests for the churches referenced by pos's chunk: the church (with its foundation)
     * and the four strips of the yard's wall with the towers and gates. */
    public static List<BlockBox> protectedBoxes(ServerWorld world, BlockPos pos) {
        List<BlockBox> boxes = new ArrayList<>();
        for (StructureStart start : starts(world, pos))
            for (StructurePiece piece : start.getChildren()) {
                if (piece instanceof ChurchPiece church) {
                    BlockBox b = church.getBoundingBox();
                    boxes.add(new BlockBox(b.getMinX(), b.getMinY() + ChurchBuilder.MIN_Y, b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()));
                } else if (piece instanceof ChurchYard yard) boxes.addAll(walls(yard.frame));
            }
        return boxes;
    }

    /** The same boxes for one known church: the church with its foundation and the yard's wall strips. */
    public static List<BlockBox> protectedBoxes(Site site) {
        ChurchPiece.Frame frame = new ChurchPiece.Frame(site.origin(), site.rotation());
        BlockBox b = site.church();
        List<BlockBox> boxes = new ArrayList<>(walls(frame));
        boxes.add(new BlockBox(b.getMinX(), b.getMinY() + ChurchBuilder.MIN_Y, b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        return boxes;
    }

    /** The yard's wall strips, each 7 blocks thick across the wall line (the towers and the gates stand in them). */
    static List<BlockBox> walls(ChurchPiece.Frame frame) {
        int x0 = ChurchBuilder.YARD_MIN_X - 1, x1 = ChurchBuilder.YARD_MAX_X + 1, z0 = ChurchBuilder.YARD_MIN_Z - 1, z1 = ChurchBuilder.YARD_MAX_Z + 1;
        int r = ChurchYard.REACH, lo = -2, hi = ChurchYard.TOP;
        return List.of(frame.box(x0 - r, lo, z0 - r, x1 + r, hi, z0 + r), frame.box(x0 - r, lo, z1 - r, x1 + r, hi, z1 + r),
                frame.box(x0 - r, lo, z0 - r, x0 + r, hi, z1 + r), frame.box(x1 - r, lo, z0 - r, x1 + r, hi, z1 + r));
    }

    private static List<StructureStart> starts(ServerWorld world, BlockPos pos) {
        Structure structure = structure(world);
        if (structure == null) return List.of();
        return world.getStructureAccessor().getStructureStarts(ChunkSectionPos.from(pos), structure).stream().filter(StructureStart::hasChildren).toList();
    }

    /** The church of a structure start (its start chunk keeps it), or empty. */
    public static Optional<Site> of(StructureStart start) { return start == null || !start.hasChildren() ? Optional.empty() : site(start); }

    private static Optional<Site> site(StructureStart start) {
        for (StructurePiece piece : start.getChildren()) if (piece instanceof ChurchPiece church) return Optional.of(church.site());
        return Optional.empty();
    }
}
