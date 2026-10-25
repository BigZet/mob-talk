package io.github.mobtalk.game;

import io.github.mobtalk.core.RoadPlan;
import io.github.mobtalk.core.place.Structures;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.PersistentState;
import net.minecraft.world.StructurePresence;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Roads between villages (docs/roads.md). The server looks for villages around its players the way the world generator places them
 * (a village structure set's grid, its spread and the biome and ground check at the spot), so a village is known before anyone has
 * been there. Neighbours — two villages with no third nearer to both — get a {@link RoadPlan} worked out on a background thread from
 * the generator's own ground heights. The road is laid chunk by chunk by {@link RoadBuilder} as the chunks load, one per tick, and
 * the world's saved data keeps the villages, the roads and the chunks already laid, so nothing is planned or laid twice. */
final class RoadNetwork {
    static final long SCAN_PERIOD = 400, SCAN_PHASE = 37, SWEEP_PERIOD = 200, SWEEP_PHASE = 91;
    /** Grid cells checked per tick (each may read a chunk's saved data from disk) and road chunks laid per tick. */
    static final int CELLS_PER_TICK = 2, CHUNKS_PER_TICK = 1, FORCED_AT_ONCE = 9;
    /** A branch («ответвление») leads off a road to a village this near it that the road does not end at (Dmitriy 2026-10-25: «add
     * small branches to leave the highway for a village nearby»): no nearer than this (the road all but passes it) and no farther. */
    static final int SPUR_MIN = 32, SPUR_REACH = 160;
    /** The key a branch's start goes by: the road it leaves, by id. */
    static final String SPUR = "spur#";
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-roads");
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-roads"); thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });

    private final MobGameBridge bridge;
    /** Per world: the grid cells waiting for a check, the road chunks waiting to be laid, the pairs being planned, the chunk index. */
    private final Map<String, World> worlds = new HashMap<>();

    RoadNetwork(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.villageRoads; }
    private static String dimension(ServerWorld world) { return world.getRegistryKey().getValue().toString(); }

    private final class World {
        final Deque<Cell> cells = new ArrayDeque<>();
        final Set<String> queuedCells = new HashSet<>();
        final LinkedHashSet<Job> jobs = new LinkedHashSet<>();
        final Set<String> planning = new HashSet<>();
        Map<Long, List<Road>> index;
        Saved saved;
    }
    private record Cell(RegistryEntry<StructureSet> set, String setId, RandomSpreadStructurePlacement placement, List<RegistryEntry<Structure>> villages, int i, int j) {
        String key() { return setId + "|" + i + "|" + j; }
    }
    private record Job(int road, long chunk) { }

    // ---- Saved data ----

    static final class Village {
        String key; int x, z;
        NbtCompound write() { NbtCompound tag = new NbtCompound(); tag.putString("key", key); tag.putInt("x", x); tag.putInt("z", z); return tag; }
        static Village read(NbtCompound tag) { Village v = new Village(); v.key = tag.getString("key"); v.x = tag.getInt("x"); v.z = tag.getInt("z"); return v; }
    }

    static final class Road {
        int id; String a, b; List<RoadPlan.Point> points; int bridge, tunnel;
        /** A branch off another road to a village near it: its start is on that road, not at a village. */
        boolean spur() { return a.startsWith(SPUR); }
        /** RoadBuilder's palette (-1 not chosen yet), and the first and last point to lay, where the road meets each village
         * ({@link Integer#MIN_VALUE} until a chunk the village reaches has told). */
        int palette = -1, from = Integer.MIN_VALUE, to = Integer.MIN_VALUE;
        final LongSet done = new LongOpenHashSet();
        LongSet chunks;
        NbtCompound write() {
            NbtCompound tag = new NbtCompound();
            tag.putInt("id", id); tag.putString("a", a); tag.putString("b", b); tag.putInt("bridge", bridge); tag.putInt("tunnel", tunnel);
            int n = points.size();
            int[] xs = new int[n], zs = new int[n], hs = new int[n]; byte[] kinds = new byte[n];
            for (int i = 0; i < n; i++) {
                RoadPlan.Point p = points.get(i);
                xs[i] = (int) Math.round(p.x() * 16); zs[i] = (int) Math.round(p.z() * 16); hs[i] = p.h2(); kinds[i] = (byte) p.kind().ordinal();
            }
            tag.putIntArray("xs", xs); tag.putIntArray("zs", zs); tag.putIntArray("hs", hs); tag.putByteArray("kinds", kinds);
            tag.putLongArray("done", done.toLongArray());
            tag.putInt("palette", palette);
            if (from != Integer.MIN_VALUE) tag.putInt("from", from);
            if (to != Integer.MIN_VALUE) tag.putInt("to", to);
            return tag;
        }
        static Road read(NbtCompound tag) {
            Road road = new Road();
            road.id = tag.getInt("id"); road.a = tag.getString("a"); road.b = tag.getString("b");
            road.bridge = tag.getInt("bridge"); road.tunnel = tag.getInt("tunnel");
            int[] xs = tag.getIntArray("xs"), zs = tag.getIntArray("zs"), hs = tag.getIntArray("hs"); byte[] kinds = tag.getByteArray("kinds");
            int n = Math.min(Math.min(xs.length, zs.length), Math.min(hs.length, kinds.length));
            double[][] at = new double[n][];
            for (int i = 0; i < n; i++) at[i] = new double[] { xs[i] / 16.0, zs[i] / 16.0 };
            List<RoadPlan.Point> points = new ArrayList<>(n);
            RoadPlan.Kind[] values = RoadPlan.Kind.values();
            for (int i = 0; i < n; i++) {
                double[] ahead = at[Math.min(n - 1, i + 1)], behind = at[Math.max(0, i - 1)];
                double dx = ahead[0] - behind[0], dz = ahead[1] - behind[1], d = Math.hypot(dx, dz);
                if (d == 0) { dx = 1; dz = 0; d = 1; }
                points.add(new RoadPlan.Point(at[i][0], at[i][1], dx / d, dz / d, hs[i], values[Math.floorMod(kinds[i], values.length)]));
            }
            road.points = List.copyOf(points);
            for (long chunk : tag.getLongArray("done")) road.done.add(chunk);
            road.palette = tag.contains("palette") ? tag.getInt("palette") : -1;
            road.from = tag.contains("from") ? tag.getInt("from") : Integer.MIN_VALUE;
            road.to = tag.contains("to") ? tag.getInt("to") : Integer.MIN_VALUE;
            return road;
        }
        /** The chunks the road and its earthworks reach. */
        LongSet chunks() {
            if (chunks != null) return chunks;
            LongSet out = new LongOpenHashSet();
            int reach = (int) Math.ceil(RoadBuilder.REACH) + 1;
            for (RoadPlan.Point p : points)
                for (int dx = -reach; dx <= reach; dx += reach) for (int dz = -reach; dz <= reach; dz += reach)
                    out.add(ChunkPos.toLong(MathHelper.floor(p.x() + dx) >> 4, MathHelper.floor(p.z() + dz) >> 4));
            return chunks = out;
        }
        String title(Saved saved) { return name(saved, a) + " — " + name(saved, b); }
        private static String name(Saved saved, String key) {
            Village v = saved.villages.get(key);
            return v == null ? key : v.x + " " + v.z;
        }
    }

    static final class Saved extends PersistentState {
        final Map<String, Village> villages = new LinkedHashMap<>();
        final Set<String> cells = new HashSet<>();
        /** Pairs of villages already planned, with a road or refused ("a|b" → road id, or -1 with the reason in refusals). */
        final Map<String, Integer> pairs = new HashMap<>();
        final Map<String, String> refusals = new HashMap<>();
        final List<Road> roads = new ArrayList<>();
        /** Chunks held loaded by {@code /roads load} until their part of the road is laid (kept over a restart: the world keeps them
         * held too), and those still to hold, a few at a time so the world generator is not flooded. */
        final LongSet forced = new LongOpenHashSet();
        final ArrayDeque<Long> toForce = new ArrayDeque<>();
        int nextId = 1;
        static final Type<Saved> TYPE = new Type<>(Saved::new, Saved::read, null);
        static Saved read(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            Saved saved = new Saved();
            NbtList villages = tag.getList("villages", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < villages.size(); i++) { Village v = Village.read(villages.getCompound(i)); saved.villages.put(v.key, v); }
            NbtList cells = tag.getList("cells", NbtElement.STRING_TYPE);
            for (int i = 0; i < cells.size(); i++) saved.cells.add(cells.getString(i));
            NbtCompound pairs = tag.getCompound("pairs");
            for (String key : pairs.getKeys()) saved.pairs.put(key, pairs.getInt(key));
            NbtCompound refusals = tag.getCompound("refusals");
            for (String key : refusals.getKeys()) saved.refusals.put(key, refusals.getString(key));
            NbtList roads = tag.getList("roads", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < roads.size(); i++) saved.roads.add(Road.read(roads.getCompound(i)));
            saved.nextId = Math.max(1, tag.getInt("nextId"));
            for (long chunk : tag.getLongArray("forced")) saved.forced.add(chunk);
            for (long chunk : tag.getLongArray("toForce")) saved.toForce.add(chunk);
            return saved;
        }
        @Override public NbtCompound writeNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            NbtList villages = new NbtList();
            for (Village v : this.villages.values()) villages.add(v.write());
            tag.put("villages", villages);
            NbtList cells = new NbtList();
            for (String cell : this.cells) cells.add(net.minecraft.nbt.NbtString.of(cell));
            tag.put("cells", cells);
            NbtCompound pairs = new NbtCompound();
            this.pairs.forEach(pairs::putInt);
            tag.put("pairs", pairs);
            NbtCompound refusals = new NbtCompound();
            this.refusals.forEach(refusals::putString);
            tag.put("refusals", refusals);
            NbtList roads = new NbtList();
            for (Road road : this.roads) roads.add(road.write());
            tag.put("roads", roads);
            tag.putInt("nextId", nextId);
            tag.putLongArray("forced", forced.toLongArray());
            tag.putLongArray("toForce", toForce.stream().mapToLong(Long::longValue).toArray());
            return tag;
        }
        Road road(int id) { for (Road road : roads) if (road.id == id) return road; return null; }
    }

    private World world(ServerWorld world) {
        World state = worlds.computeIfAbsent(dimension(world), k -> new World());
        if (state.saved == null) state.saved = world.getPersistentStateManager().getOrCreate(Saved.TYPE, "mobtalk_roads");
        if (state.index == null) {
            state.index = new HashMap<>();
            for (Road road : state.saved.roads) {
                index(state, road);
                for (long chunk : road.chunks()) // chunks that came in before the mod was listening (the spawn area at start-up)
                    if (!road.done.contains(chunk) && ready(world, ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk)))
                        state.jobs.add(new Job(road.id, chunk));
            }
        }
        return state;
    }

    private static void index(World state, Road road) {
        for (long chunk : road.chunks()) state.index.computeIfAbsent(chunk, k -> new ArrayList<>()).add(road);
    }

    void clearRuntime() { worlds.clear(); }

    /** The roads planned in this world (for the traffic). */
    List<Road> roads(ServerWorld world) { return world(world).saved.roads; }
    Road road(ServerWorld world, int id) { return world(world).saved.road(id); }

    // ---- The tick ----

    void tick(ServerWorld world) {
        if (!enabled()) return;
        World state = world(world);
        long now = world.getTime();
        if (now % SCAN_PERIOD == SCAN_PHASE) queueCells(world, state);
        if (now % SWEEP_PERIOD == SWEEP_PHASE) sweep(world, state);
        for (int k = 0; k < CELLS_PER_TICK && !state.cells.isEmpty(); k++) {
            Cell cell = state.cells.poll();
            state.queuedCells.remove(cell.key());
            check(world, state, cell);
        }
        while (state.saved.forced.size() < FORCED_AT_ONCE && !state.saved.toForce.isEmpty()) {
            long chunk = state.saved.toForce.poll();
            if (laid(state, chunk)) { state.saved.markDirty(); continue; } // laid while it waited in the queue
            if (state.saved.forced.add(chunk)) world.setChunkForced(ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk), true);
            state.saved.markDirty();
        }
        for (int k = 0; k < CHUNKS_PER_TICK && !state.jobs.isEmpty(); k++) {
            Iterator<Job> it = state.jobs.iterator();
            Job job = it.next(); it.remove();
            lay(world, state, job);
        }
    }

    /** A chunk came in: it and its neighbours, where a road crosses them and has not been laid yet, get queued (a chunk is laid only
     * once its eight neighbours are loaded too, so that nothing the road changes reaches into an unloaded chunk). */
    void loaded(ServerWorld world, ChunkPos chunk) {
        if (!enabled()) return;
        World state = world(world);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                long at = ChunkPos.toLong(chunk.x + dx, chunk.z + dz);
                List<Road> roads = state.index.get(at);
                if (roads != null) for (Road road : roads) if (!road.done.contains(at)) state.jobs.add(new Job(road.id, at));
            }
    }

    /** Queues every road chunk that is ready and not laid: a chunk that was loaded before its neighbours, or before it was held,
     * hears no load event of its own again. */
    private void sweep(ServerWorld world, World state) {
        for (long chunk : state.saved.forced.toLongArray()) release(world, state, chunk);
        for (Road road : state.saved.roads)
            for (long chunk : road.chunks())
                if (!road.done.contains(chunk) && ready(world, ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk))) state.jobs.add(new Job(road.id, chunk));
    }

    /** The chunk and its eight neighbours are all loaded. */
    private static boolean ready(ServerWorld world, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) if (world.getChunkManager().getWorldChunk(cx + dx, cz + dz) == null) return false;
        return true;
    }

    private void lay(ServerWorld world, World state, Job job) {
        Road road = state.saved.road(job.road());
        if (road == null || road.done.contains(job.chunk())) return;
        int cx = ChunkPos.getPackedX(job.chunk()), cz = ChunkPos.getPackedZ(job.chunk());
        if (!ready(world, cx, cz)) return; // queued again when it or a neighbour comes in
        try {
            ChunkPos pos = new ChunkPos(cx, cz);
            if (road.palette < 0) road.palette = RoadBuilder.palette(world, road.points);
            if (road.from == Integer.MIN_VALUE || road.to == Integer.MIN_VALUE) {
                int[] ends = RoadBuilder.ends(world, pos, road.points);
                if (road.from == Integer.MIN_VALUE && ends[0] != Integer.MIN_VALUE) road.from = ends[0];
                if (road.to == Integer.MIN_VALUE && ends[1] != Integer.MIN_VALUE) road.to = ends[1];
            }
            int from = road.from == Integer.MIN_VALUE ? 0 : road.from, to = road.to == Integer.MIN_VALUE ? road.points.size() - 1 : road.to;
            RoadBuilder.Report report = RoadBuilder.lay(world, road.points, pos, RoadBuilder.palette(road.palette), from, to,
                    bridge.config.roadLamps, bridge.config.roadLampSpacing, road.from != Integer.MIN_VALUE, road.to != Integer.MIN_VALUE);
            road.done.add(job.chunk());
            state.saved.markDirty();
            release(world, state, job.chunk());
            if (road.done.size() == 1 || report.station > 0 || report.blocker != null)
                LOGGER.info("дорога {} ({}): уложен чанк {} {}: {}", road.id, road.title(state.saved), cx, cz, report);
        } catch (RuntimeException failure) {
            road.done.add(job.chunk()); // never retried in a loop
            state.saved.markDirty();
            release(world, state, job.chunk());
            LOGGER.warn("дорога {}: чанк {} {} не уложен", road.id, cx, cz, failure);
        }
    }

    // ---- Finding the villages ----

    /** Queues the grid cells of every village structure set within {@code roadScanRadius} of each player of this world. */
    private void queueCells(ServerWorld world, World state) {
        List<int[]> around = new ArrayList<>();
        for (ServerPlayerEntity player : world.getPlayers()) around.add(new int[] { player.getBlockX(), player.getBlockZ() });
        queueCells(world, state, around);
    }

    private void queueCells(ServerWorld world, World state, List<int[]> around) {
        if (around.isEmpty()) return;
        StructurePlacementCalculator calculator = world.getChunkManager().getStructurePlacementCalculator();
        var registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);
        int radius = Math.max(16, bridge.config.roadScanRadius) >> 4;
        for (RegistryEntry<StructureSet> set : calculator.getStructureSets()) {
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement placement)) continue;
            List<RegistryEntry<Structure>> villages = new ArrayList<>();
            for (StructureSet.WeightedEntry entry : set.value().structures()) if (village(world, entry.structure())) villages.add(entry.structure());
            if (villages.isEmpty()) continue;
            String setId = set.getKey().map(key -> key.getValue().toString()).orElseGet(() -> String.valueOf(registry.getId(set.value())));
            int spacing = placement.getSpacing();
            for (int[] spot : around) {
                int pcx = spot[0] >> 4, pcz = spot[1] >> 4;
                for (int i = Math.floorDiv(pcx - radius, spacing); i <= Math.floorDiv(pcx + radius, spacing); i++)
                    for (int j = Math.floorDiv(pcz - radius, spacing); j <= Math.floorDiv(pcz + radius, spacing); j++) {
                        Cell cell = new Cell(set, setId, placement, villages, i, j);
                        if (state.saved.cells.contains(cell.key()) || !state.queuedCells.add(cell.key())) continue;
                        state.cells.add(cell);
                    }
            }
        }
    }

    static boolean village(ServerWorld world, RegistryEntry<Structure> structure) {
        if (structure.isIn(StructureTags.VILLAGE)) return true;
        return structure.getKey().map(key -> Structures.village(key.getValue().toString())).orElse(false);
    }

    /** One grid cell: does a village start in it? Asked of the world's own structure locator (the check /locate makes). */
    private void check(ServerWorld world, World state, Cell cell) {
        StructurePlacementCalculator calculator = world.getChunkManager().getStructurePlacementCalculator();
        int spacing = cell.placement().getSpacing();
        ChunkPos start = cell.placement().getStartChunk(calculator.getStructureSeed(), cell.i() * spacing, cell.j() * spacing);
        String found = null;
        if (cell.placement().shouldGenerate(calculator, start.x, start.z)) {
            for (RegistryEntry<Structure> structure : cell.villages()) {
                StructurePresence presence = world.getStructureAccessor().getStructurePresence(start, structure.value(), cell.placement(), false);
                // START_PRESENT: the chunk is generated and holds the village; CHUNK_LOAD_NEEDED: not generated yet, but the biome and
                // the ground there let the village stand (what /locate goes by before it loads the chunk).
                if (presence == StructurePresence.START_PRESENT || presence == StructurePresence.CHUNK_LOAD_NEEDED) {
                    found = structure.getKey().map(k -> k.getValue().toString()).orElse("village"); break;
                }
            }
        }
        state.saved.cells.add(cell.key());
        state.saved.markDirty();
        if (found == null) return;
        Village village = new Village();
        village.key = found + "@" + start.x + "," + start.z; village.x = start.getCenterX(); village.z = start.getCenterZ();
        if (state.saved.villages.putIfAbsent(village.key, village) == null) {
            LOGGER.info("деревня {} в {} {}", found, village.x, village.z);
            connect(world, state);
        }
    }

    // ---- Planning ----

    /** Plans a road for each pair of neighbouring villages that has none planned yet. */
    private void connect(ServerWorld world, World state) {
        List<Village> villages = new ArrayList<>(state.saved.villages.values());
        int[] xs = new int[villages.size()], zs = new int[villages.size()];
        for (int i = 0; i < villages.size(); i++) { xs[i] = villages.get(i).x; zs[i] = villages.get(i).z; }
        for (int[] edge : RoadPlan.network(xs, zs, bridge.config.roadMaxLength)) {
            Village a = villages.get(edge[0]), b = villages.get(edge[1]);
            String pair = pair(a.key, b.key);
            if (state.saved.pairs.containsKey(pair) || !state.planning.add(pair)) continue;
            plan(world, state, a.key, a.x, a.z, b.key, b.x, b.z, null);
        }
        spurs(world, state);
    }

    /** A branch from each road to every village near it that it does not join already, directly or through one of its two villages. */
    private void spurs(ServerWorld world, World state) {
        for (Village village : state.saved.villages.values())
            for (Road road : List.copyOf(state.saved.roads)) {
                if (road.spur() || road.a.equals(village.key) || road.b.equals(village.key)) continue;
                String pair = pair(SPUR + road.id, village.key);
                if (state.saved.pairs.containsKey(pair) || state.planning.contains(pair) || joined(state, village.key, road.a) || joined(state, village.key, road.b)) continue;
                int best = -1; double distance = Double.MAX_VALUE;
                for (int i = 0; i < road.points.size(); i++) {
                    RoadPlan.Point p = road.points.get(i);
                    double d = Math.hypot(p.x() - village.x, p.z() - village.z);
                    if (d < distance) { distance = d; best = i; }
                }
                if (best < 0 || distance < SPUR_MIN || distance > SPUR_REACH) continue;
                RoadPlan.Point from = road.points.get(best);
                plan(world, state, SPUR + road.id, from.blockX(), from.blockZ(), village.key, village.x, village.z, from.h2() >> 1, null);
            }
    }

    /** A road of its own joins the two villages. */
    private static boolean joined(World state, String a, String b) {
        Integer id = state.saved.pairs.get(pair(a, b));
        return id != null && id >= 0;
    }

    static String pair(String a, String b) { return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a; }

    /** Plans one road off the game thread and takes it in on the game thread; {@code done} hears the outcome (for the command). */
    private void plan(ServerWorld world, World state, String a, int ax, int az, String b, int bx, int bz, java.util.function.Consumer<String> done) {
        plan(world, state, a, ax, az, b, bx, bz, Integer.MIN_VALUE, done);
    }

    /** {@code startFloor}: the height the road starts at (a branch starts on its road's surface), or MIN_VALUE to take the ground's. */
    private void plan(ServerWorld world, World state, String a, int ax, int az, String b, int bx, int bz, int startFloor, java.util.function.Consumer<String> done) {
        String pair = pair(a, b);
        state.planning.add(pair);
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        NoiseConfig noise = world.getChunkManager().getNoiseConfig();
        int top = world.getTopY() - 1, bottom = world.getBottomY();
        RoadPlan.Terrain terrain = new RoadPlan.Terrain() {
            @Override public int floor(int x, int z) { return both(x, z)[0]; }
            @Override public int surface(int x, int z) { return both(x, z)[1]; }
            /** One worldgen column for both heights: the first block from the top that is not air is the surface (water counts),
             * the first that blocks movement is the floor. */
            @Override public int[] both(int x, int z) {
                if (startFloor != Integer.MIN_VALUE && Math.abs(x - ax) <= 3 && Math.abs(z - az) <= 3) return new int[] { startFloor, startFloor };
                var column = generator.getColumnSample(x, z, world, noise);
                int surface = Integer.MIN_VALUE;
                for (int y = top; y >= bottom; y--) {
                    var state = column.getState(y);
                    if (state.isAir()) continue;
                    if (surface == Integer.MIN_VALUE) surface = y + 1;
                    if (state.blocksMovement()) return new int[] { y + 1, surface };
                }
                return new int[] { bottom, Math.max(bottom, surface) };
            }
        };
        RoadPlan.Rules defaults = RoadPlan.Rules.defaults();
        RoadPlan.Rules rules = new RoadPlan.Rules(defaults.grade(), defaults.tunnelCut(), defaults.bridgeFill(), defaults.maxBridge(), defaults.maxTunnel(),
                bridge.config.roadMaxLength, defaults.variants());
        long seed = world.getSeed() ^ pair.hashCode() * 0x9E3779B97F4A7C15L;
        PLANNER.execute(() -> {
            RoadPlan.Result result;
            try { result = RoadPlan.plan(seed, ax, az, bx, bz, terrain, rules); }
            catch (RuntimeException failure) { LOGGER.warn("дорога {} не спланирована", pair, failure); result = new RoadPlan.Result(null, "ошибка: " + failure); }
            RoadPlan.Result planned = result;
            world.getServer().execute(() -> accept(world, a, b, planned, done));
        });
    }

    private void accept(ServerWorld world, String a, String b, RoadPlan.Result result, java.util.function.Consumer<String> done) {
        World state = world(world);
        String pair = pair(a, b);
        state.planning.remove(pair);
        if (!result.ok()) {
            state.saved.pairs.put(pair, -1); state.saved.refusals.put(pair, result.refusal()); state.saved.markDirty();
            LOGGER.info("дороги {} не будет: {}", pair, result.refusal());
            if (done != null) done.accept("дороги не будет: " + result.refusal());
            return;
        }
        Road road = new Road();
        road.id = state.saved.nextId++; road.a = a; road.b = b; road.points = result.road().points();
        road.bridge = result.road().bridge(); road.tunnel = result.road().tunnel();
        state.saved.roads.add(road); state.saved.pairs.put(pair, road.id); state.saved.markDirty();
        index(state, road);
        if (!road.spur()) spurs(world, state);
        int queued = 0;
        for (long chunk : road.chunks())
            if (world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk)) != null) { state.jobs.add(new Job(road.id, chunk)); queued++; }
        String line = "дорога " + road.id + ": " + road.points.size() + " блоков, мостов " + road.bridge + ", тоннелей " + road.tunnel
                + ", " + road.chunks().size() + " чанков (" + queued + " загружено)";
        LOGGER.info("{} ({})", line, pair);
        if (done != null) done.accept(line);
    }

    // ---- For the command ----

    String status(ServerWorld world) {
        World state = world(world);
        long laid = state.saved.roads.stream().mapToLong(r -> r.done.size()).sum(), all = state.saved.roads.stream().mapToLong(r -> r.chunks().size()).sum();
        return (enabled() ? "" : "выключено в настройках. ") + "Деревень известно: " + state.saved.villages.size() + ", дорог: " + state.saved.roads.size()
                + ", отказов: " + state.saved.refusals.size() + ", планируется: " + state.planning.size() + ", ждут проверки клеток: " + state.cells.size()
                + ", уложено чанков: " + laid + " из " + all + ", в очереди: " + state.jobs.size();
    }

    List<String> list(ServerWorld world) {
        World state = world(world);
        List<String> out = new ArrayList<>();
        for (Road road : state.saved.roads)
            out.add("#" + road.id + " " + road.title(state.saved) + ": " + road.points.size() + " блоков, мост " + road.bridge + ", тоннель " + road.tunnel
                    + ", уложено " + road.done.size() + "/" + road.chunks().size());
        state.saved.refusals.forEach((pair, why) -> out.add("нет дороги " + pair + ": " + why));
        return out;
    }

    /** The road point nearest to (x, z): the road, the index of the point, and its distance; null when there is no road. */
    record Near(Road road, int index, double distance) { }
    Near near(ServerWorld world, double x, double z) {
        Near best = null;
        for (Road road : world(world).saved.roads)
            for (int i = 0; i < road.points.size(); i++) {
                RoadPlan.Point p = road.points.get(i);
                double d = Math.hypot(p.x() - x, p.z() - z);
                if (best == null || d < best.distance()) best = new Near(road, i, d);
            }
        return best;
    }

    /** The first point of each kind along the road (for the command and the self-test). */
    static Map<RoadPlan.Kind, RoadPlan.Point> firsts(Road road) {
        Map<RoadPlan.Kind, RoadPlan.Point> out = new EnumMap<>(RoadPlan.Kind.class);
        for (RoadPlan.Point p : road.points) out.putIfAbsent(p.kind(), p);
        return out;
    }

    /** A test road between two spots, planned now and laid where loaded. */
    void build(ServerWorld world, int ax, int az, int bx, int bz, java.util.function.Consumer<String> done) {
        World state = world(world);
        plan(world, state, "spot@" + ax + "," + az, ax, az, "spot@" + bx + "," + bz, bx, bz, done);
    }

    /** Lays again every loaded chunk of the roads (a changed setting, a self-test). */
    int relay(ServerWorld world) {
        World state = world(world);
        int queued = 0;
        for (Road road : state.saved.roads)
            for (long chunk : road.chunks())
                if (world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk)) != null) {
                    road.done.remove(chunk); state.jobs.add(new Job(road.id, chunk)); queued++;
                }
        state.saved.markDirty();
        return queued;
    }

    /** Lets a chunk go once no road waits for it any more. */
    private void release(ServerWorld world, World state, long chunk) {
        if (!state.saved.forced.contains(chunk) || !laid(state, chunk)) return;
        state.saved.forced.remove(chunk);
        state.saved.markDirty();
        world.setChunkForced(ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk), false);
    }

    /** Holds the chunks of a road loaded until it is laid there: the road is built now, without anyone walking it. */
    int load(ServerWorld world, Road road) {
        World state = world(world);
        int held = 0;
        for (long chunk : road.chunks()) {
            if (road.done.contains(chunk) || state.saved.forced.contains(chunk) || state.saved.toForce.contains(chunk)) continue;
            if (world.getForcedChunks().contains(chunk)) { state.jobs.add(new Job(road.id, chunk)); continue; } // held already by someone else
            state.saved.toForce.add(chunk);
            held++;
        }
        return held;
    }

    /** Every road through the chunk is laid there. */
    private static boolean laid(World state, long chunk) {
        for (Road road : state.index.getOrDefault(chunk, List.of())) if (!road.done.contains(chunk)) return false;
        return true;
    }

    /** What the held chunks wait for (the command's {@code debug}). */
    List<String> debug(ServerWorld world) {
        World state = world(world);
        List<String> out = new ArrayList<>();
        out.add("держу " + state.saved.forced.size() + ", ждут " + state.saved.toForce.size() + ", очередь " + state.jobs.size());
        for (long chunk : state.saved.forced) {
            int x = ChunkPos.getPackedX(chunk), z = ChunkPos.getPackedZ(chunk);
            StringBuilder line = new StringBuilder(x + " " + z + (ready(world, x, z) ? " готов" : " не готов") + ":");
            for (Road road : state.index.getOrDefault(chunk, List.of())) line.append(" #").append(road.id).append(road.done.contains(chunk) ? "+" : "-");
            out.add(line.toString());
        }
        return out;
    }

    /** Looks for villages now instead of on the next round. */
    void scanNow(ServerWorld world) { queueCells(world, world(world)); }
    /** Looks for villages around a spot (a server console has no player to look around). */
    void scanAt(ServerWorld world, int x, int z) { queueCells(world, world(world), List.<int[]>of(new int[] { x, z })); }

    int pending(ServerWorld world) { World state = world(world); return state.jobs.size() + state.planning.size(); }
}
