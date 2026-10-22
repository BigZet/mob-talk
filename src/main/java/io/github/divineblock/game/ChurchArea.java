package io.github.divineblock.game;

import io.github.divineblock.world.ChurchPiece;
import io.github.divineblock.world.ChurchSites;
import io.github.divineblock.world.ChurchWorldgen;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.Structure;

/** Where the churches are, for the punishments: the church box (inviolable), the protected zone around it (church, yard and
 * cemetery: no landscape punishment), whether a mortal is inside, and a standing spot in the churchyard.
 *
 * <p>Our own fallback until the church thread's helpers (the enlarged church with its walled churchyard) arrive: it reads the
 * {@code divineblock:orthodox_church} structure through {@link ChurchSites#at} and remembers every church it has met (on
 * chunk load and on lookups), so the hot checks ({@link #isChurchBlock}, {@link #inZone}) never touch the structure accessor.
 * Swap the bodies of {@link #near}, {@link #yardSpot} and {@link #inside} for the church thread's helpers when they land. */
public final class ChurchArea {
    /** The protected zone: this many blocks around the church box (the church, the yard and the cemetery). */
    public static final int ZONE_RADIUS = 40;
    private static final Map<World, List<ChurchSites.Site>> KNOWN = new WeakHashMap<>();

    private ChurchArea() { }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            Structure structure = world.getRegistryManager().get(RegistryKeys.STRUCTURE).get(ChurchWorldgen.STRUCTURE);
            if (structure == null) return;
            StructureStart start = chunk.getStructureStart(structure);
            if (start == null || !start.hasChildren()) return;
            for (StructurePiece piece : start.getChildren()) if (piece instanceof ChurchPiece church) remember(world, church.site());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { synchronized (KNOWN) { KNOWN.clear(); } });
    }

    public static void remember(ServerWorld world, ChurchSites.Site site) {
        List<ChurchSites.Site> sites;
        synchronized (KNOWN) { sites = KNOWN.computeIfAbsent(world, w -> new CopyOnWriteArrayList<>()); }
        for (ChurchSites.Site known : sites) if (known.origin().equals(site.origin())) return;
        sites.add(site);
    }

    static List<ChurchSites.Site> known(World world) {
        synchronized (KNOWN) { List<ChurchSites.Site> sites = KNOWN.get(world); return sites == null ? List.of() : sites; }
    }

    /** The church around pos or within the protected zone of it; looks the structure up at pos if none is known yet. */
    public static Optional<ChurchSites.Site> near(ServerWorld world, BlockPos pos) {
        ChurchSites.Site best = null; double bestDistance = Double.MAX_VALUE;
        for (ChurchSites.Site site : known(world)) {
            double distance = distance(site.church(), pos);
            if (distance <= ZONE_RADIUS && distance < bestDistance) { best = site; bestDistance = distance; }
        }
        if (best != null) return Optional.of(best);
        Optional<ChurchSites.Site> found = ChurchSites.at(world, pos);
        found.ifPresent(site -> remember(world, site));
        return found.filter(site -> distance(site.church(), pos) <= ZONE_RADIUS);
    }

    /** A block of the church itself: nothing but the player may change it. Fast: known churches only. */
    public static boolean isChurchBlock(World world, BlockPos pos) {
        for (ChurchSites.Site site : known(world)) if (site.church().contains(pos)) return true;
        return false;
    }

    /** Inside the protected zone of any known church (horizontally within {@link #ZONE_RADIUS} of the church box). */
    public static boolean inZone(World world, BlockPos pos) {
        for (ChurchSites.Site site : known(world)) if (distance(site.church(), pos) <= ZONE_RADIUS) return true;
        return false;
    }

    /** Within {@code margin} blocks of a known church box (bosses must not rise inside or against the walls). */
    public static boolean nearChurchBox(World world, BlockPos pos, int margin) {
        for (ChurchSites.Site site : known(world)) if (distance(site.church(), pos) <= margin && pos.getY() >= site.church().getMinY() - 4
                && pos.getY() <= site.church().getMaxY()) return true;
        return false;
    }

    /** Horizontal distance from pos to the box (0 inside). */
    public static double distance(BlockBox box, BlockPos pos) {
        int dx = Math.max(0, Math.max(box.getMinX() - pos.getX(), pos.getX() - box.getMaxX()));
        int dz = Math.max(0, Math.max(box.getMinZ() - pos.getZ(), pos.getZ() - box.getMaxZ()));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The mortal stands in the church (in its box, under the roof or within a step of the walls). */
    public static boolean inside(ChurchSites.Site site, BlockPos pos) {
        BlockBox box = site.church();
        return pos.getX() >= box.getMinX() - 1 && pos.getX() <= box.getMaxX() + 1 && pos.getZ() >= box.getMinZ() - 1 && pos.getZ() <= box.getMaxZ() + 1
                && pos.getY() >= box.getMinY() - 2 && pos.getY() <= box.getMaxY();
    }

    public static Vec3d center(ChurchSites.Site site) { return Vec3d.ofCenter(site.naveCenter()); }

    /** Where the fight takes place: open ground in the churchyard, a dozen blocks from the church, near the floor's level, dry,
     * with room for a big boss. Candidates west of the porch, then north, south-west and east; the porch as the last resort. */
    public static Vec3d yardSpot(ServerWorld world, ChurchSites.Site site) {
        ChurchPiece.Frame frame = new ChurchPiece.Frame(site.origin(), site.rotation());
        int[][] candidates = { { -18, 0 }, { -16, -14 }, { -16, 14 }, { 18, -24 }, { 50, 0 }, { -26, 0 }, { 0, -26 }, { -30, 12 }, { -30, -12 }, { 36, -22 } };
        int floor = site.origin().getY();
        List<Vec3d> good = new ArrayList<>();
        // First open sky (no tree crown over the spot or around it), then any dry ground; the cemetery's alley is cleared of trees.
        for (int pass = 0; pass < 2 && good.isEmpty(); pass++) {
            List<BlockPos> columns = new ArrayList<>();
            for (int[] c : candidates) columns.add(frame.world(c[0], 0, c[1]));
            if (site.bossPoints().size() > 3) columns.add(3, site.bossPoints().get(3));
            for (BlockPos column : columns) {
                world.getChunk(column);
                BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
                if (Math.abs(top.getY() - floor) > 7 || site.church().contains(top)) continue;
                BlockState ground = world.getBlockState(top.down());
                if (!ground.getFluidState().isEmpty() || !world.getBlockState(top).getCollisionShape(world, top).isEmpty()
                        || !world.getBlockState(top.up()).getCollisionShape(world, top.up()).isEmpty()) continue;
                if (pass == 0 && !openSky(world, top)) continue;
                good.add(Vec3d.ofBottomCenter(top));
                break;
            }
        }
        if (!good.isEmpty()) return good.get(0);
        BlockPos porch = site.bossPoints().size() > 2 ? site.bossPoints().get(2) : site.origin().west(6);
        return Vec3d.ofBottomCenter(world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, porch));
    }

    /** No leaves over the spot and the eight around it, two blocks out. */
    private static boolean openSky(ServerWorld world, BlockPos top) {
        for (int dx = -2; dx <= 2; dx += 2) for (int dz = -2; dz <= 2; dz += 2) {
            BlockPos column = top.add(dx, 0, dz);
            if (world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, column).getY() > world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column).getY() + 0)
                return false;
        }
        return true;
    }
}
