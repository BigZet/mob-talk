package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.world.ChurchBuilder;
import io.github.divineblock.world.ChurchSites;
import io.github.divineblock.world.ChurchStructure;
import io.github.divineblock.world.ChurchWorldgen;
import java.nio.file.Files;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.structure.Structure;

/** A new world starts at KAIZEN87's Slavic Church (the owner's wish, {@code spawnAtChurch}): while a fresh world is created every
 * church generated is the Slavic one, then the nearest church to the vanilla spawn is found and the world spawn moves to the path
 * before its porch, with no random spread around it. A world that already existed keeps its spawn. */
public final class ChurchSpawn {
    private ChurchSpawn() { }
    /** How far from the vanilla spawn to look: for a random spread the search counts grid cells (churchSpacing chunks each). */
    private static final int SEARCH_CHUNKS = 32;
    private static volatile boolean fresh;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // Before the overworld is created: no chunk saved yet (level.dat is already written by now) means this start makes a new world.
            fresh = DivineBlockMod.config().spawnAtChurch && DivineBlockMod.config().churches
                    && noChunks(server.getSavePath(WorldSavePath.ROOT).resolve("region"));
            if (fresh) { ChurchStructure.forceVariant = ChurchBuilder.Variant.SLAVIC; DivineBlockMod.LOG.info("Божественный блок: новый мир — церкви при создании будут Славянскими, спавн у ближайшей"); }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!fresh) return;
            try { place(server); } finally { fresh = false; ChurchStructure.forceVariant = null; }
        });
    }

    private static boolean noChunks(java.nio.file.Path region) {
        if (!Files.isDirectory(region)) return true;
        try (var files = Files.list(region)) { return files.noneMatch(file -> file.getFileName().toString().endsWith(".mca")); }
        catch (java.io.IOException unreadable) { return false; }
    }

    private static void place(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        Structure church = world.getRegistryManager().get(RegistryKeys.STRUCTURE).get(ChurchWorldgen.STRUCTURE);
        if (church == null || !ChurchWorldgen.enabled()) return;
        BlockPos origin = world.getSpawnPos();
        var found = world.getChunkManager().getChunkGenerator().locateStructure(world,
                RegistryEntryList.of(world.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntry(church)), origin, SEARCH_CHUNKS, false);
        if (found == null) { DivineBlockMod.LOG.warn("Божественный блок: церкви в {} чанках от спавна нет — спавн мира не перенесён", SEARCH_CHUNKS); return; }
        BlockPos center = found.getFirst();
        // The start chunk keeps the structure start; the church's own chunks are generated in full when the spawn loads them.
        var start = world.getChunk(center.getX() >> 4, center.getZ() >> 4).getStructureStart(church);
        var site = ChurchSites.of(start);
        if (site.isEmpty()) { DivineBlockMod.LOG.warn("Божественный блок: церковь у {} не найдена после генерации — спавн мира не перенесён", center); return; }
        BlockPos spot = site.get().outdoorSpot();
        world.getChunk(spot);
        BlockPos feet = new BlockPos(spot.getX(), Math.max(spot.getY(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ())), spot.getZ());
        float yaw = site.get().rotation().rotate(net.minecraft.util.math.Direction.EAST).asRotation(); // Facing the porch.
        world.setSpawnPos(feet, yaw);
        world.getGameRules().get(GameRules.SPAWN_RADIUS).set(0, server);
        DivineBlockMod.LOG.info("Божественный блок: спавн нового мира у церкви ({}) {} — {} блоков от прежнего", site.get().variant(), feet,
                Math.round(Math.sqrt(feet.getSquaredDistance(origin))));
    }
}
