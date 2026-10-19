package io.github.mobtalk.client.game;

import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.place.Material;
import io.github.mobtalk.core.place.Place;
import io.github.mobtalk.core.place.PlaceClassifier;
import io.github.mobtalk.core.place.PlaceObjects;
import io.github.mobtalk.core.place.VoxelGrid;
import net.minecraft.block.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Samples the blocks around a creature into a {@link VoxelGrid} on the server thread and runs the pure analysis on it:
 * the flood ({@link Place}), the grouped objects ({@link PlaceObjects}) and, off the server thread, the classifier.
 * Nothing here is clever about Minecraft beyond naming what a block is for a body. */
final class PlaceCapture {
    /** The box sampled around the creature: 25×17×25 cells, enough for the flood's radius and the classifier's cube. */
    static final int RADIUS_XZ = Place.RADIUS, RADIUS_Y = 8;

    record Result(VoxelGrid grid, Place.Report report, List<PlaceObjects.Item> objects, int originY) { }

    private PlaceCapture() { }

    /** Everything about the place, in one server-thread pass of about a millisecond. Null when the area is not loaded. */
    static Result capture(ServerWorld world, MobEntity mob, ServerPlayerEntity player, BlockChangeLog changes) {
        BlockPos origin = mob.getBlockPos();
        VoxelGrid grid = sample(world, origin);
        if (grid == null) return null;
        int minutes = changes == null ? 0 : changes.mark(grid, world, origin);
        int headroom = Math.max(1, Math.min(3, (int) Math.ceil(mob.getHeight() - 0.05)));
        boolean opensDoors = mob.getNavigation() != null && mob.getNavigation().getNodeMaker() != null && mob.getNavigation().getNodeMaker().canOpenDoors();
        Place.Options options = Place.Options.DEFAULT.headroom(headroom).doors(opensDoors).changes(minutes);
        if (player != null && player.getWorld() == world) {
            BlockPos p = player.getBlockPos().subtract(origin);
            if (grid.inside(p.getX(), p.getY(), p.getZ())) options = options.player(p.getX(), p.getY(), p.getZ());
        }
        Place.Report report = Place.analyse(grid, options);
        List<PlaceObjects.Item> objects = PlaceObjects.find(grid, report.originY);
        return new Result(grid, report, objects, report.originY);
    }

    /** The grid, or null when a chunk of the box is not loaded (never load chunks for a conversation). */
    static VoxelGrid sample(ServerWorld world, BlockPos origin) {
        for (int cx = (origin.getX() - RADIUS_XZ) >> 4; cx <= (origin.getX() + RADIUS_XZ) >> 4; cx++)
            for (int cz = (origin.getZ() - RADIUS_XZ) >> 4; cz <= (origin.getZ() + RADIUS_XZ) >> 4; cz++)
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) return null;
        VoxelGrid grid = new VoxelGrid(RADIUS_XZ, RADIUS_Y);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int bottom = world.getBottomY(), top = world.getTopY();
        for (int x = -RADIUS_XZ; x <= RADIUS_XZ; x++) for (int z = -RADIUS_XZ; z <= RADIUS_XZ; z++) {
            int wx = origin.getX() + x, wz = origin.getZ() + z;
            grid.openAbove(x, z, world.getTopY(Heightmap.Type.MOTION_BLOCKING, wx, wz) <= origin.getY() + RADIUS_Y + 1);
            for (int y = -RADIUS_Y; y <= RADIUS_Y; y++) {
                int wy = origin.getY() + y;
                if (wy < bottom) { grid.set(x, y, z, Material.UNBREAKABLE, "minecraft:bedrock"); continue; }
                if (wy >= top) continue;
                pos.set(wx, wy, wz);
                BlockState state = world.getBlockState(pos);
                if (state.isAir()) continue;
                Material kind = classify(state, world, pos);
                if (kind == Material.AIR) continue;
                grid.set(x, y, z, kind, Registries.BLOCK.getId(state.getBlock()).toString());
                if (kind == Material.WATER || kind == Material.LAVA) {
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty() && !fluid.isStill()) {
                        Vec3d v = fluid.getVelocity(world, pos);
                        grid.flow(x, y, z, (float) v.x, (float) v.y, (float) v.z);
                    }
                }
            }
        }
        return grid;
    }

    /** What a block is for a body. The order matters: fluids and doors before the full-cube shortcut. */
    static Material classify(BlockState state, ServerWorld world, BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof FluidBlock || block instanceof BubbleColumnBlock) return state.getFluidState().isIn(FluidTags.LAVA) ? Material.LAVA : Material.WATER;
        if (block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock)
            return state.contains(Properties.OPEN) && state.get(Properties.OPEN) ? Material.DOOR_OPEN : Material.DOOR_CLOSED;
        if (block instanceof FenceBlock || block instanceof WallBlock) return Material.FENCE;
        if (block instanceof PaneBlock || state.isIn(BlockTags.IMPERMEABLE) || block == Blocks.TINTED_GLASS) return Material.GLASS;
        if (state.isIn(BlockTags.CLIMBABLE)) return Material.CLIMBABLE;
        if (state.isIn(BlockTags.FIRE) || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.POINTED_DRIPSTONE || block == Blocks.WITHER_ROSE || block == Blocks.LAVA_CAULDRON
                || block instanceof CampfireBlock && state.contains(Properties.LIT) && state.get(Properties.LIT)) return Material.HAZARD;
        if (block instanceof PistonBlock || block instanceof PistonHeadBlock || block instanceof PistonExtensionBlock || block instanceof DispenserBlock
                || block instanceof ObserverBlock || block instanceof TntBlock || block instanceof HopperBlock) return Material.MACHINE;
        if (state.isIn(BlockTags.LEAVES)) return Material.LEAVES;
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOODEN_STAIRS) || state.isIn(BlockTags.WOODEN_SLABS)) return Material.WOOD;
        if (block == Blocks.FARMLAND) return Material.FARMLAND;
        float hardness = state.getHardness(world, pos);
        if (hardness < 0 || hardness >= 50) return Material.UNBREAKABLE;
        if (state.isFullCube(world, pos)) return Material.SOLID;
        if (state.getCollisionShape(world, pos).isEmpty())
            return block instanceof PlantBlock || state.isIn(BlockTags.CROPS) || state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.SAPLINGS) ? Material.PLANT : Material.AIR;
        return Material.SOLID;
    }

    /** What goes into the snapshot under environmentDetails.place: the facts the prompt block reads, nothing the JSON needs twice. */
    static Map<String, Object> details(Result result) {
        Map<String, Object> place = new LinkedHashMap<>();
        Place.Report r = result.report();
        place.put("kind", r.kind.name());
        place.put("trapped", r.trapped);
        place.put("enclosed", r.enclosed);
        place.put("exits", new ArrayList<>(r.exits));
        place.put("summary", r.summary());
        place.put("escape", r.escape());
        List<String> objects = new ArrayList<>();
        for (PlaceObjects.Item item : result.objects()) objects.add(item.text());
        place.put("objects", objects);
        return place;
    }

    private static final Map<UUID, byte[]> CUBES = new ConcurrentHashMap<>();
    /** Keeps the classifier's input aside for {@link #classify(MobSnapshot)}: the snapshot itself carries only JSON values. */
    static void stash(UUID mob, VoxelGrid grid) {
        if (CUBES.size() > 64) CUBES.clear(); // A capture that never reached the classifier must not pile up.
        CUBES.put(mob, grid.cube());
    }

    /** Runs the classifier off the server thread and writes its guess into the place details; the snapshot comes back as is
     * when there is nothing to classify or no weights are bundled. */
    static MobSnapshot classify(MobSnapshot snapshot) {
        byte[] cube = CUBES.remove(snapshot.id());
        PlaceClassifier classifier = PlaceClassifier.bundled();
        if (cube == null || classifier == null || !(snapshot.environmentDetails().get("place") instanceof Map<?, ?> existing)) return snapshot;
        PlaceClassifier.Guess guess = classifier.guess(cube);
        Map<String, Object> place = new LinkedHashMap<>();
        existing.forEach((key, value) -> place.put(String.valueOf(key), value));
        place.put("looksLike", guess.label());
        place.put("looksLikeConfidence", Math.round(guess.confidence() * 100) / 100.0);
        Map<String, Object> environment = new LinkedHashMap<>(snapshot.environmentDetails());
        environment.put("place", place);
        return new MobSnapshot(snapshot.id(), snapshot.type(), snapshot.name(), snapshot.health(), snapshot.maxHealth(), snapshot.dimension(), snapshot.biome(),
                snapshot.timeOfDay(), snapshot.weather(), snapshot.distance(), snapshot.heldItem(), snapshot.nearby(), snapshot.recentEvents(), snapshot.playerId(),
                snapshot.playerDetails(), snapshot.mobDetails(), environment, snapshot.observedEntities(), snapshot.availableActions(), snapshot.interactionRevision());
    }
}
