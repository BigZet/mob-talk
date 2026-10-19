package io.github.mobtalk.client.game;

import io.github.mobtalk.core.place.Structures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.SimpleStructurePiece;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.structure.Structure;

/** Reads the vanilla structures around a position from the world's own records: every loaded chunk nearby lists the structure
 * starts that reach into it, and each start lists its pieces with their boxes and templates. Nothing is loaded or generated: a
 * start whose chunk is not loaded is skipped. Runs on the server thread. */
public final class StructureCapture {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private StructureCapture() { }

    /** One piece as the world records it, with the identifiers the dataset and the prompt both use. */
    public record Found(StructureStart start, StructurePiece piece, String structure, String pieceType, String template) { }

    /** The pieces whose boxes come within {@code radius} of {@code origin}, plus whether origin is inside each one. */
    public static List<Structures.Piece> pieces(ServerWorld world, BlockPos origin, int radius) {
        List<Structures.Piece> out = new ArrayList<>();
        for (Found found : find(world, origin, radius)) {
            BlockBox box = found.piece().getBoundingBox(), whole = found.start().getBoundingBox();
            int dx = gap(origin.getX(), box.getMinX(), box.getMaxX()), dy = gap(origin.getY(), box.getMinY(), box.getMaxY()),
                    dz = gap(origin.getZ(), box.getMinZ(), box.getMaxZ());
            boolean inside = dx == 0 && dz == 0 && (dy == 0 || box.contains(origin.down()));
            boolean inStart = whole.contains(origin) || whole.contains(origin.down());
            long volume = (long) box.getBlockCountX() * box.getBlockCountY() * box.getBlockCountZ();
            out.add(new Structures.Piece(found.structure(), found.pieceType(), found.template(), dx, dy, dz, inside, inStart, (int) Math.min(Integer.MAX_VALUE, volume)));
        }
        return out;
    }

    private static int gap(int at, int min, int max) { return at < min ? min - at : at > max ? max - at : 0; }

    /** The registry id of a piece's type ("minecraft:jigsaw", "minecraft:mscorridor"). */
    public static String pieceType(StructurePiece piece) {
        Identifier type = Registries.STRUCTURE_PIECE.getId(piece.getType());
        return type == null ? "" : type.toString();
    }

    /** The template a piece was built from ("minecraft:village/plains/houses/plains_small_house_1"), "" for hand-coded pieces, or
     * null for pieces that are no place at all (empty pool elements, decoration features such as hay piles). */
    public static String template(ServerWorld world, StructurePiece piece) {
        if (piece instanceof PoolStructurePiece pool) {
            String element = String.valueOf(pool.getPoolElement());
            if (element.startsWith("Empty") || element.startsWith("Feature")) return null;
            Matcher m = ID.matcher(element);
            String template = m.find() ? m.group() : "";
            // Mob and golem markers (villagers, animals, piglins) carry no blocks: no place to name.
            return template.contains("/villagers") || template.contains("/animals") || template.contains("/mobs") || template.contains("village/common/") ? null : template;
        }
        if (piece instanceof SimpleStructurePiece) return piece.toNbt(StructureContext.from(world)).getString("Template");
        return "";
    }

    /** Structure pieces within {@code radius} of origin; empty pool elements and decoration features are left out. */
    public static List<Found> find(ServerWorld world, BlockPos origin, int radius) {
        List<Found> out = new ArrayList<>();
        Set<StructureStart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        BlockBox area = new BlockBox(origin.getX() - radius, origin.getY() - radius, origin.getZ() - radius, origin.getX() + radius, origin.getY() + radius, origin.getZ() + radius);
        for (int cx = (origin.getX() - radius) >> 4; cx <= (origin.getX() + radius) >> 4; cx++)
            for (int cz = (origin.getZ() - radius) >> 4; cz <= (origin.getZ() + radius) >> 4; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;
                Chunk chunk = world.getChunk(cx, cz);
                for (Map.Entry<Structure, LongSet> entry : chunk.getStructureReferences().entrySet()) {
                    for (long reference : entry.getValue()) {
                        ChunkPos at = new ChunkPos(reference);
                        if (!world.getChunkManager().isChunkLoaded(at.x, at.z)) continue;
                        StructureStart start = world.getChunk(at.x, at.z).getStructureStart(entry.getKey());
                        if (start == null || !start.hasChildren() || !seen.add(start) || !start.getBoundingBox().intersects(area)) continue;
                        Identifier id = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getId(entry.getKey());
                        String structure = id == null ? "unknown" : id.toString();
                        for (StructurePiece piece : start.getChildren()) {
                            if (!piece.getBoundingBox().intersects(area)) continue;
                            String template = template(world, piece);
                            if (template != null) out.add(new Found(start, piece, structure, pieceType(piece), template));
                        }
                    }
                }
            }
        return out;
    }

    /** For the self-test: a spot a creature could stand on inside a house of the village nearest to {@code near} (located through the
     * registry, generating chunks when needed), or null when no village is within {@code radiusChunks}. */
    public static BlockPos villageHouseSpot(ServerWorld world, BlockPos near, int radiusChunks) {
        var registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        List<net.minecraft.registry.entry.RegistryEntry<Structure>> villages = new ArrayList<>();
        for (var key : registry.getKeys())
            if (key.getValue().getPath().startsWith("village_")) registry.getEntry(key).ifPresent(villages::add);
        var found = world.getChunkManager().getChunkGenerator().locateStructure(world, net.minecraft.registry.entry.RegistryEntryList.of(villages), near, radiusChunks, false);
        if (found == null) return null;
        ChunkPos at = new ChunkPos(found.getFirst());
        StructureStart start = world.getChunk(at.x, at.z, net.minecraft.world.chunk.ChunkStatus.STRUCTURE_STARTS).getStructureStart(found.getSecond().value());
        if (start == null || !start.hasChildren()) return null;
        for (StructurePiece piece : start.getChildren()) {
            String template = template(world, piece);
            if (template == null || !template.contains("/houses/")) continue;
            BlockBox box = piece.getBoundingBox();
            for (int cx = box.getMinX() - 16 >> 4; cx <= box.getMaxX() + 16 >> 4; cx++)
                for (int cz = box.getMinZ() - 16 >> 4; cz <= box.getMaxZ() + 16 >> 4; cz++) world.getChunk(cx, cz);
            BlockPos center = box.getCenter(), best = null;
            for (BlockPos pos : BlockPos.iterate(box.getMinX() + 1, box.getMinY(), box.getMinZ() + 1, box.getMaxX() - 1, box.getMaxY(), box.getMaxZ() - 1)) {
                if (!PlaceCapture.classify(world.getBlockState(pos), world, pos).passable || !PlaceCapture.classify(world.getBlockState(pos.up()), world, pos.up()).passable) continue;
                if (PlaceCapture.classify(world.getBlockState(pos.down()), world, pos.down()).passable) continue;
                if (best == null || pos.getSquaredDistance(center) < best.getSquaredDistance(center)) best = pos.toImmutable();
            }
            if (best != null) return best;
        }
        return null;
    }
}
