package io.github.mobtalk.client.game;

import io.github.mobtalk.core.place.Place;
import io.github.mobtalk.core.place.VoxelGrid;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** What the player built or dug lately, kept in memory for a few minutes so a creature can tell "I fell into a pit" from
 * "he walled me in": every block placed or broken on the integrated server, and a short check, a moment after the player
 * stops, of whether a known creature nearby has just been shut in. Server thread only. */
final class BlockChangeLog {
    /** How long a change stays remembered. */
    static final long WINDOW_MILLIS = 5 * 60_000L;
    static final int MAX_CHANGES = 4000;
    /** How far from a change a creature is checked, and how long after the last change. */
    static final double CHECK_RANGE = 6;
    static final long SETTLE_MILLIS = 2500;

    private record Change(RegistryKey<World> world, long pos, byte how, long millis) { }

    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    /** Creatures near recent changes, with the moment their check is due. */
    private final Map<UUID, Long> pending = new HashMap<>();
    private final Map<UUID, Boolean> enclosed = new HashMap<>();

    void placed(ServerWorld world, BlockPos pos, ServerPlayerEntity player) { record(world, pos, VoxelGrid.PLACED); }
    void broken(ServerWorld world, BlockPos pos, ServerPlayerEntity player) { record(world, pos, VoxelGrid.BROKEN); }

    private void record(ServerWorld world, BlockPos pos, byte how) {
        long now = System.currentTimeMillis();
        prune(now);
        changes.addLast(new Change(world.getRegistryKey(), pos.asLong(), how, now));
        while (changes.size() > MAX_CHANGES) changes.pollFirst();
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        if (bridge == null) return;
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, new Box(pos).expand(CHECK_RANGE),
                other -> other.isAlive() && !other.isRemoved() && bridge.memory.known(other.getUuid())))
            pending.put(mob.getUuid(), now + SETTLE_MILLIS);
    }

    private void prune(long now) {
        while (!changes.isEmpty() && changes.peekFirst().millis() < now - WINDOW_MILLIS) changes.pollFirst();
    }

    /** Flags the grid's cells the player changed and returns the age in minutes of the oldest such change (0 when none). */
    synchronized int mark(VoxelGrid grid, ServerWorld world, BlockPos origin) {
        long now = System.currentTimeMillis();
        prune(now);
        long oldest = now;
        boolean any = false;
        for (Change change : changes) {
            if (change.world() != world.getRegistryKey()) continue;
            BlockPos pos = BlockPos.fromLong(change.pos());
            int x = pos.getX() - origin.getX(), y = pos.getY() - origin.getY(), z = pos.getZ() - origin.getZ();
            if (!grid.inside(x, y, z)) continue;
            grid.changed(x, y, z, change.how());
            oldest = Math.min(oldest, change.millis()); any = true;
        }
        return any ? (int) Math.max(1, Math.round((now - oldest) / 60_000.0)) : 0;
    }

    /** Once a tick: runs the due checks, at most one per tick, and tells a creature that was just shut in who did it. */
    void tick(MobGameBridge bridge, ServerWorld world) {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue() > now) continue;
            it.remove();
            if (!(world.getEntity(entry.getKey()) instanceof MobEntity mob) || !mob.isAlive()) { enclosed.remove(entry.getKey()); continue; }
            ServerPlayerEntity player = world.getClosestPlayer(mob, 32) instanceof ServerPlayerEntity near ? near : null;
            PlaceCapture.Result result = PlaceCapture.capture(world, mob, player, this);
            if (result == null) return;
            Place.Report report = result.report();
            boolean was = enclosed.getOrDefault(mob.getUuid(), false);
            enclosed.put(mob.getUuid(), report.trapped);
            if (report.trapped && !was) {
                String text = report.placedByPlayer >= 2 && report.placedByPlayer >= report.brokenByPlayer
                        ? "Игрок замуровал тебя: поставил вокруг " + report.placedByPlayer + " " + Place.plural(report.placedByPlayer, "блок", "блока", "блоков") + " (" + report.escape() + ")"
                        : report.brokenByPlayer >= 2 ? "Игрок окопал тебя: выкопал " + report.brokenByPlayer + " " + Place.plural(report.brokenByPlayer, "блок", "блока", "блоков") + " под тобой и вокруг (" + report.escape() + ")"
                        : null;
                if (text != null) bridge.event(mob, text);
            }
            return;
        }
    }

    void forget(UUID mob) { pending.remove(mob); enclosed.remove(mob); }
}
