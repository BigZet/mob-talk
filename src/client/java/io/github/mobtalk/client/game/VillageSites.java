package io.github.mobtalk.client.game;

import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.place.Structures;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.gen.structure.Structure;

/** Which village structure a spot belongs to, read from the world's structure records: a vanilla village or one of a village mod
 * (CTOV and the like, any structure in {@code #minecraft:village} or named "village_…"). The world memory keys villages by it,
 * so two neighbouring villages stay apart and a large one stays whole. Only loaded chunks are read; runs on the server thread. */
public final class VillageSites {
    private VillageSites() { }

    /** The site of the village this villager (or golem) lives in: around its bell, else its bed, else its feet; null when none. */
    public static ConversationStore.Site of(ServerWorld world, MobEntity mob) {
        for (MemoryModuleType<GlobalPos> memory : java.util.List.of(MemoryModuleType.MEETING_POINT, MemoryModuleType.HOME)) {
            if (!MobActionController.registered(mob, memory)) continue;
            Optional<GlobalPos> place = mob.getBrain().getOptionalRegisteredMemory(memory);
            if (place.isEmpty() || place.get().dimension() != world.getRegistryKey()) continue;
            ConversationStore.Site site = at(world, place.get().pos());
            if (site != null) return site;
        }
        return at(world, mob.getBlockPos());
    }

    /** The village structure whose bounds hold {@code pos}; a piece holding it wins over the whole start's box, and the nearest
     * centre breaks a tie between overlapping villages. Null outside any village or in an unloaded chunk. */
    public static ConversationStore.Site at(ServerWorld world, BlockPos pos) {
        if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        var registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        StructureStart best = null; Identifier bestId = null; boolean bestInPiece = false; long bestDistance = Long.MAX_VALUE;
        for (Map.Entry<Structure, LongSet> entry : world.getChunk(pos.getX() >> 4, pos.getZ() >> 4).getStructureReferences().entrySet()) {
            Identifier id = registry.getId(entry.getKey());
            boolean village = id != null && (Structures.village(id.toString()) || registry.getEntry(entry.getKey()).isIn(StructureTags.VILLAGE));
            if (!village) continue;
            for (long reference : entry.getValue()) {
                ChunkPos at = new ChunkPos(reference);
                if (!world.getChunkManager().isChunkLoaded(at.x, at.z)) continue;
                StructureStart start = world.getChunk(at.x, at.z).getStructureStart(entry.getKey());
                if (start == null || !start.hasChildren() || !flat(start.getBoundingBox(), pos)) continue;
                boolean inPiece = false;
                for (StructurePiece piece : start.getChildren()) if (piece.getBoundingBox().contains(pos)) { inPiece = true; break; }
                BlockPos center = start.getBoundingBox().getCenter();
                long dx = center.getX() - pos.getX(), dz = center.getZ() - pos.getZ(), distance = dx * dx + dz * dz;
                if (best == null || inPiece && !bestInPiece || inPiece == bestInPiece && distance < bestDistance) {
                    best = start; bestId = id; bestInPiece = inPiece; bestDistance = distance;
                }
            }
        }
        if (best == null) return null;
        BlockBox box = best.getBoundingBox();
        BlockPos center = box.getCenter();
        return new ConversationStore.Site(bestId + "@" + best.getPos().x + "," + best.getPos().z, center.getX(), center.getZ(),
                box.getMinX(), box.getMinZ(), box.getMaxX(), box.getMaxZ());
    }

    /** The site as snapshot details ({@code mobDetails.villageSite}), which the world memory reads back. */
    public static Map<String, Object> details(ConversationStore.Site site) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", site.key()); out.put("x", site.x()); out.put("z", site.z());
        out.put("minX", site.minX()); out.put("minZ", site.minZ()); out.put("maxX", site.maxX()); out.put("maxZ", site.maxZ());
        return out;
    }

    private static boolean flat(BlockBox box, BlockPos pos) {
        return pos.getX() >= box.getMinX() && pos.getX() <= box.getMaxX() && pos.getZ() >= box.getMinZ() && pos.getZ() <= box.getMaxZ();
    }
}
