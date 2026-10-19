package io.github.mobtalk.client.game;

import com.google.gson.Gson;
import io.github.mobtalk.core.place.BlockFamily;
import io.github.mobtalk.core.place.Material;
import io.github.mobtalk.core.place.Trees;
import io.github.mobtalk.core.place.VoxelGrid;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.level.LevelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds a labelled dataset of real Minecraft places for the place classifier, for the developer only:
 * {@code -Dmobtalk.harvest=<output folder>;seeds=<N>[;minutes=<per world>][;seed=<first seed>]} creates N fresh creative worlds from
 * the title screen one after another and, in each, locates the vanilla structures from the registry, cuts 16³ cubes around
 * places a creature could stand inside every piece (labelled structure / piece / template), grows trees of every kind with their
 * configured features on natural ground and cuts cubes next to them, and cuts background cubes (surface, water, caves, the
 * Nether) far from any structure, labelled by biome. Each world is deleted after its run; the game quits at the end.
 *
 * <p>Output per world: {@code <n>-<seed>.mtvx} (version 2: header, then one 16³ material cube per sample),
 * {@code .mtvb} (the same cells as indices into each sample's block palette, big-endian uint16) and {@code .jsonl} (one line of
 * labels and the palette per sample). The first world also writes {@code blocks.tsv}: every block's material and family, so
 * tools that read saved regions classify blocks exactly as the game does. Never active in normal play. */
public final class DatasetHarvester {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-harvest");
    private static final Gson GSON = new Gson();
    /** Server-thread time per tick spent on harvesting. */
    private static final long BUDGET_NANOS = 150_000_000L;
    private static final int PIECES_PER_START = 24, SAMPLES_PER_PIECE = 2, TREES_PER_FEATURE = 5, SAMPLES_PER_TREE = 3,
            SURFACE_SAMPLES = 160, CAVE_SAMPLES = 80, NETHER_SAMPLES = 30, SPREAD = 4000, LOCATE_RADIUS = 64;
    private static final List<String> NETHER = List.of("fortress", "bastion_remnant", "nether_fossil", "ruined_portal_nether");
    private static final List<String> SKIP = List.of("buried_treasure", "end_city");
    private static final List<String> TREE_FEATURES = List.of("oak", "fancy_oak", "birch", "super_birch_bees_0002", "spruce", "pine", "mega_spruce",
            "mega_pine", "jungle_tree", "mega_jungle_tree", "jungle_bush", "acacia", "dark_oak", "mangrove", "tall_mangrove", "cherry", "azalea_tree",
            "swamp_oak", "crimson_fungus_planted", "warped_fungus_planted", "huge_red_mushroom", "huge_brown_mushroom");

    private final MinecraftClient client;
    private final Path out;
    private final int worlds;
    private final long minutes;
    private final Random random;
    private int index = -1, phase, ticks, phaseStart;
    private long seed;
    private String worldName;
    private MinecraftServer server;
    // Server-thread state for the current world.
    private final Deque<Runnable> work = new ArrayDeque<>();
    private volatile boolean done;
    private long deadline;
    private Sink sink;
    private final Set<Long> taken = new HashSet<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private String lastJob = "";

    private DatasetHarvester(MinecraftClient client, Path out, int worlds, long minutes, long firstSeed) {
        this.client = client; this.out = out; this.worlds = worlds; this.minutes = minutes;
        this.random = new Random(firstSeed == 0 ? System.nanoTime() : firstSeed);
    }

    /** Arms the harvester when the system property is set; one line in the client's start-up. */
    public static void install(MinecraftClient client) {
        String spec = System.getProperty("mobtalk.harvest");
        if (spec == null || spec.isBlank()) return;
        String[] parts = spec.split("[;|]"); // Prism's JVM argument field may eat semicolons: '|' works as well
        Path out;
        try { out = Path.of(parts[0].trim()); }
        catch (RuntimeException bad) { LOGGER.error("[harvest] bad output folder '{}' (use forward slashes)", parts[0]); return; }
        int worlds = 1; long minutes = 8, seed = 0;
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0].trim()) {
                case "seeds" -> worlds = Integer.parseInt(kv[1].trim());
                case "minutes" -> minutes = Long.parseLong(kv[1].trim());
                case "seed" -> seed = Long.parseLong(kv[1].trim());
                default -> { }
            }
        }
        DatasetHarvester harvester = new DatasetHarvester(client, out, worlds, minutes, seed);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> harvester.clientTick());
        ServerTickEvents.END_SERVER_TICK.register(harvester::serverTick);
        LOGGER.info("[harvest] armed: {} worlds, {} minutes each, output {}", worlds, minutes, out);
    }

    private static void log(String text) { LOGGER.info("[harvest] {}", text); }

    // ---- Client side: one world after another.
    private void clientTick() {
        ticks++;
        switch (phase) {
            case 0 -> { // the title screen: create the next world
                if (!(client.currentScreen instanceof TitleScreen) || ticks < 40) return;
                index++;
                if (index >= worlds) { log("all worlds done"); phase = 9; client.scheduleStop(); return; }
                seed = random.nextLong();
                worldName = "mobtalk-harvest-" + index;
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                rules.get(GameRules.RANDOM_TICK_SPEED).set(0, null);
                LevelInfo info = new LevelInfo(worldName, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, rules, DataConfiguration.SAFE_MODE);
                log("world " + (index + 1) + "/" + worlds + " seed " + seed);
                done = false; phase = 1; phaseStart = ticks;
                client.createIntegratedServerLoader().createAndStart(worldName, info, new GeneratorOptions(seed, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> { // wait for the player to be in the world, then hand the work to the server thread
                if (client.player != null && client.world != null && client.getServer() != null && client.player.age > 40) {
                    server = client.getServer();
                    server.execute(this::begin);
                    phase = 2;
                } else if (ticks - phaseStart > 20 * 180) { log("world did not start"); phase = 9; client.scheduleStop(); }
            }
            case 2 -> { // harvesting on the server thread
                if (!done) return;
                log("world " + (index + 1) + " done: " + counts);
                client.world.disconnect();
                client.disconnect(new MessageScreen(Text.literal("Mob Talk harvest")));
                client.setScreen(new TitleScreen());
                phase = 3; phaseStart = ticks;
            }
            case 3 -> { // wait for the server to stop, then delete the world
                if (server != null && server.getThread().isAlive() && ticks - phaseStart < 20 * 120) return;
                deleteWorld();
                server = null; phase = 0; ticks = 0;
            }
            default -> { }
        }
    }

    private void deleteWorld() {
        Path dir = client.getLevelStorage().getSavesDirectory().resolve(worldName);
        if (!dir.getFileName().toString().startsWith("mobtalk-harvest-") || !Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            log("deleted " + dir.getFileName());
        } catch (IOException e) { log("could not delete " + dir + ": " + e); }
    }

    // ---- Server side.
    private void begin() {
        ServerWorld overworld = server.getOverworld();
        try {
            Files.createDirectories(out);
            if (index == 0) dumpBlocks(overworld);
            sink = new Sink(out.resolve(index + "-" + Long.toUnsignedString(seed)));
        } catch (IOException e) { log("cannot write: " + e); done = true; return; }
        taken.clear(); counts.clear(); work.clear();
        deadline = System.nanoTime() + minutes * 60_000_000_000L;
        List<Runnable> plan = new ArrayList<>();
        Registry<Structure> structures = server.getRegistryManager().get(RegistryKeys.STRUCTURE);
        for (RegistryKey<Structure> key : structures.getKeys()) {
            String path = key.getValue().getPath();
            if (!key.getValue().getNamespace().equals("minecraft") || SKIP.contains(path)) continue;
            int instances = path.startsWith("village_") ? 3 : path.equals("stronghold") || path.equals("mansion") || path.equals("ancient_city") ? 1 : 2;
            ServerWorld world = server.getWorld(NETHER.contains(path) ? World.NETHER : World.OVERWORLD);
            for (int i = 0; i < instances; i++) { BlockPos center = i == 0 ? BlockPos.ORIGIN : randomColumn(); plan.add(() -> locate(world, key, center)); }
        }
        for (String feature : TREE_FEATURES) for (int i = 0; i < TREES_PER_FEATURE; i++) plan.add(() -> tree(overworld, feature));
        for (int i = 0; i < SURFACE_SAMPLES; i++) plan.add(() -> background(overworld, false));
        for (int i = 0; i < CAVE_SAMPLES; i++) plan.add(() -> background(overworld, true));
        ServerWorld nether = server.getWorld(World.NETHER);
        for (int i = 0; i < NETHER_SAMPLES; i++) plan.add(() -> background(nether, true));
        Collections.shuffle(plan, random);
        work.addAll(plan);
        log("planned " + plan.size() + " jobs");
    }

    private void serverTick(MinecraftServer ticking) {
        if (ticking != server || done || sink == null) return;
        long until = System.nanoTime() + BUDGET_NANOS;
        while (System.nanoTime() < until) {
            Runnable job = work.pollFirst();
            if (job == null || System.nanoTime() > deadline) { finishWorld(job == null ? "all jobs" : "time is up, " + work.size() + " jobs left"); return; }
            long jobStart = System.nanoTime();
            try { job.run(); } catch (RuntimeException e) { LOGGER.warn("[harvest] job failed", e); }
            long ms = (System.nanoTime() - jobStart) / 1_000_000;
            if (ms > 5000) log("slow job: " + ms + " ms (" + lastJob + ")");
        }
    }

    private void finishWorld(String why) {
        try { sink.close(); } catch (IOException e) { log("close failed: " + e); }
        log("world " + (index + 1) + " finished (" + why + "): " + sink.count + " samples");
        sink = null; work.clear(); done = true;
    }

    private BlockPos randomColumn() { return new BlockPos(random.nextInt(2 * SPREAD) - SPREAD, 0, random.nextInt(2 * SPREAD) - SPREAD); }

    /** Loads (generating if needed) every chunk a 25×17×25 sample around {@code pos} reads. */
    private static void ensure(ServerWorld world, BlockPos pos, int radius) {
        for (int cx = (pos.getX() - radius) >> 4; cx <= (pos.getX() + radius) >> 4; cx++)
            for (int cz = (pos.getZ() - radius) >> 4; cz <= (pos.getZ() + radius) >> 4; cz++) world.getChunk(cx, cz);
    }

    private static String biome(ServerWorld world, BlockPos pos) {
        return world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("unknown");
    }

    private static boolean passable(ServerWorld world, BlockPos pos) { return PlaceCapture.classify(world.getBlockState(pos), world, pos).passable; }
    /** A cell a creature could stand in: room for its feet and head, something under it (or water to swim in). */
    private static boolean standable(ServerWorld world, BlockPos pos) {
        if (!passable(world, pos) || !passable(world, pos.up())) return false;
        Material below = PlaceCapture.classify(world.getBlockState(pos.down()), world, pos.down());
        return !below.passable || world.getBlockState(pos).getFluidState().isStill() && !world.getBlockState(pos).getFluidState().isEmpty();
    }

    // ---- Structures.
    private void locate(ServerWorld world, RegistryKey<Structure> key, BlockPos center) {
        lastJob = "locate " + key.getValue().getPath();
        Registry<Structure> registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        RegistryEntry<Structure> entry = registry.getEntry(key).orElse(null);
        if (entry == null) return;
        var found = world.getChunkManager().getChunkGenerator().locateStructure(world, RegistryEntryList.of(entry), center, LOCATE_RADIUS, false);
        if (found == null) { count("locate-miss:" + key.getValue().getPath()); return; }
        ChunkPos at = new ChunkPos(found.getFirst());
        Chunk chunk = world.getChunk(at.x, at.z, ChunkStatus.STRUCTURE_STARTS);
        StructureStart start = chunk.getStructureStart(entry.value());
        if (start == null || !start.hasChildren()) { count("no-start:" + key.getValue().getPath()); return; }
        long id = at.toLong() ^ ((long) key.getValue().hashCode() << 32);
        if (!taken.add(id)) return; // the same instance from another centre
        List<StructurePiece> pieces = new ArrayList<>(start.getChildren());
        Collections.shuffle(pieces, random);
        // Buildings first; streets and their end caps are many and alike, so only a few of them.
        Map<StructurePiece, String> templates = new java.util.IdentityHashMap<>();
        for (StructurePiece piece : pieces) templates.put(piece, String.valueOf(StructureCapture.template(world, piece)));
        pieces.sort(Comparator.comparingInt(piece -> {
            String template = templates.get(piece);
            return template.contains("/houses") || template.contains("town_centers") ? 0 : template.contains("streets") || template.contains("terminators") ? 2 : 1;
        }));
        int streets = 0;
        for (var it = pieces.iterator(); it.hasNext(); ) {
            String template = templates.get(it.next());
            if ((template.contains("streets") || template.contains("terminators")) && ++streets > 4) it.remove();
        }
        String structure = key.getValue().toString();
        String startBiome = biome(world, start.getBoundingBox().getCenter());
        for (StructurePiece piece : pieces.subList(0, Math.min(PIECES_PER_START, pieces.size())))
            work.addFirst(() -> piece(world, structure, startBiome, piece));
    }

    private void piece(ServerWorld world, String structure, String startBiome, StructurePiece piece) {
        lastJob = "piece of " + structure;
        String template = StructureCapture.template(world, piece);
        if (template == null) return;
        BlockBox box = piece.getBoundingBox();
        BlockPos center = box.getCenter();
        // Big pieces (a whole monument, a bastion block) are searched near their middle only.
        int rx = Math.min(16, box.getBlockCountX() / 2), rz = Math.min(16, box.getBlockCountZ() / 2);
        // Flat pieces (streets, fields) have their top at the ground: a creature stands just above the box.
        int minY = Math.max(box.getMinY(), world.getBottomY() + 1), maxY = Math.min(box.getMaxY() + 2, world.getTopY() - 2);
        ensure(world, center, Math.max(rx, rz) + 13);
        List<BlockPos> spots = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = center.getX() - rx; x <= center.getX() + rx; x++) for (int z = center.getZ() - rz; z <= center.getZ() + rz; z++)
            for (int y = minY; y <= maxY; y++) {
                pos.set(x, y, z);
                if (standable(world, pos)) spots.add(pos.toImmutable());
            }
        if (spots.isEmpty()) { count("no-spot"); return; }
        Collections.shuffle(spots, random);
        int made = 0;
        for (BlockPos spot : spots) {
            if (made >= SAMPLES_PER_PIECE) break;
            if (!taken.add(spot.asLong())) continue;
            Map<String, Object> meta = meta("structure", world, spot);
            meta.put("structure", structure); meta.put("piece_type", StructureCapture.pieceType(piece)); meta.put("template", template);
            meta.put("start_biome", startBiome);
            if (write(world, spot, meta)) { made++; count(structure.substring(10)); }
        }
    }

    // ---- Trees grown by their own configured features on natural ground.
    private void tree(ServerWorld world, String feature) {
        lastJob = "tree " + feature;
        Registry<ConfiguredFeature<?, ?>> registry = world.getRegistryManager().get(RegistryKeys.CONFIGURED_FEATURE);
        var entry = registry.getEntry(Identifier.of("minecraft", feature)).orElse(null);
        if (entry == null) { count("no-feature:" + feature); return; }
        for (int attempt = 0; attempt < 6; attempt++) {
            BlockPos column = randomColumn();
            ensure(world, column, 14);
            if (world.getChunk(column).hasStructureReferences()) continue;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
            BlockPos root = new BlockPos(column.getX(), y, column.getZ());
            BlockState ground = world.getBlockState(root.down());
            if (!ground.isIn(BlockTags.DIRT) || !world.getBlockState(root).isReplaceable()) continue;
            if (feature.startsWith("crimson")) world.setBlockState(root.down(), Blocks.CRIMSON_NYLIUM.getDefaultState());
            if (feature.startsWith("warped")) world.setBlockState(root.down(), Blocks.WARPED_NYLIUM.getDefaultState());
            if (!entry.value().generate(world, world.getChunkManager().getChunkGenerator(), world.getRandom(), root)) continue;
            int made = 0;
            for (int tries = 0; tries < 30 && made < SAMPLES_PER_TREE; tries++) {
                int reach = made == 0 ? 1 + random.nextInt(2) : 2 + random.nextInt(5);
                double angle = random.nextDouble() * Math.PI * 2;
                int x = root.getX() + (int) Math.round(Math.cos(angle) * reach), z = root.getZ() + (int) Math.round(Math.sin(angle) * reach);
                int fy = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos spot = new BlockPos(x, fy, z);
                if (!standable(world, spot) || !taken.add(spot.asLong())) continue;
                Map<String, Object> meta = meta("tree", world, spot);
                meta.put("feature", "minecraft:" + feature);
                meta.put("trunk", List.of(root.getX() - x, root.getY() - fy, root.getZ() - z));
                if (write(world, spot, meta)) { made++; count("tree:" + feature); }
            }
            return;
        }
        count("tree-miss:" + feature);
    }

    // ---- Background: no structure within the sample, labelled by biome and relief.
    private void background(ServerWorld world, boolean underground) {
        lastJob = "background " + (underground ? "underground" : "surface");
        for (int attempt = 0; attempt < 4; attempt++) {
            BlockPos column = randomColumn();
            ensure(world, column, 13);
            boolean structures = false;
            for (int dx = -1; dx <= 1 && !structures; dx++) for (int dz = -1; dz <= 1; dz++)
                if (world.getChunk((column.getX() >> 4) + dx, (column.getZ() >> 4) + dz).hasStructureReferences()) { structures = true; break; }
            if (structures) continue;
            BlockPos spot = null; String relief;
            if (!underground) {
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
                spot = new BlockPos(column.getX(), y, column.getZ());
                boolean water = !world.getBlockState(spot.down()).getFluidState().isEmpty();
                if (water) { while (!world.getBlockState(spot.down()).getFluidState().isEmpty() && spot.getY() > world.getBottomY() + 1) spot = spot.down(); }
                relief = water ? "water" : "surface";
                if (water && spot.getY() < y - 1) spot = spot.up(random.nextInt(Math.max(1, y - spot.getY())));
            } else {
                int top = world.getRegistryKey() == World.NETHER ? 120 : world.getTopY(Heightmap.Type.WORLD_SURFACE, column.getX(), column.getZ()) - 12;
                int bottom = world.getBottomY() + 6;
                List<BlockPos> floors = new ArrayList<>();
                for (int y = bottom; y < top; y++) {
                    BlockPos p = new BlockPos(column.getX(), y, column.getZ());
                    if (standable(world, p) && world.getBlockState(p).getFluidState().isEmpty()) floors.add(p);
                }
                if (floors.isEmpty()) continue;
                spot = floors.get(random.nextInt(floors.size()));
                relief = world.getRegistryKey() == World.NETHER ? "nether" : "cave";
            }
            if (!taken.add(spot.asLong())) continue;
            Map<String, Object> meta = meta("background", world, spot);
            meta.put("relief", relief);
            if (write(world, spot, meta)) count("bg:" + relief);
            return;
        }
    }

    private Map<String, Object> meta(String kind, ServerWorld world, BlockPos spot) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("src", "harvest"); meta.put("seed", Long.toUnsignedString(seed)); meta.put("kind", kind);
        meta.put("dim", world.getRegistryKey().getValue().toString()); meta.put("biome", biome(world, spot));
        meta.put("pos", List.of(spot.getX(), spot.getY(), spot.getZ()));
        return meta;
    }

    private boolean write(ServerWorld world, BlockPos spot, Map<String, Object> meta) {
        ensure(world, spot, PlaceCapture.RADIUS_XZ);
        VoxelGrid grid = PlaceCapture.sample(world, spot);
        if (grid == null) return false;
        List<String> palette = new ArrayList<>();
        short[] blocks = grid.blockCube(palette);
        List<String> trees = new ArrayList<>();
        for (Trees.Tree tree : Trees.find(grid)) if (tree.distance() <= 8) trees.add(tree.species().id());
        meta.put("trees_rule", trees);
        meta.put("palette", palette);
        try { sink.write(grid.cube(), blocks, meta); return true; }
        catch (IOException e) { log("write failed: " + e); return false; }
    }

    private void count(String what) { counts.merge(what, 1, Integer::sum); }

    /** Every block's default material, the material of its open/lit variants, and its family. */
    private void dumpBlocks(ServerWorld world) throws IOException {
        BlockPos at = new BlockPos(0, world.getBottomY() + 1, 0);
        try (Writer w = Files.newBufferedWriter(out.resolve("blocks.tsv"), StandardCharsets.UTF_8)) {
            w.write("block\tmaterial\topen\tclosed\tlit\tunlit\tfamily\n");
            for (Block block : Registries.BLOCK) {
                BlockState state = block.getDefaultState();
                String id = Registries.BLOCK.getId(block).toString();
                String open = "-", closed = "-", lit = "-", unlit = "-";
                if (state.contains(Properties.OPEN)) {
                    open = PlaceCapture.classify(state.with(Properties.OPEN, true), world, at).name();
                    closed = PlaceCapture.classify(state.with(Properties.OPEN, false), world, at).name();
                }
                if (state.contains(Properties.LIT)) {
                    lit = PlaceCapture.classify(state.with(Properties.LIT, true), world, at).name();
                    unlit = PlaceCapture.classify(state.with(Properties.LIT, false), world, at).name();
                }
                w.write(id + "\t" + PlaceCapture.classify(state, world, at).name() + "\t" + open + "\t" + closed + "\t" + lit + "\t" + unlit + "\t" + BlockFamily.of(id).name() + "\n");
            }
        }
        log("wrote blocks.tsv");
    }

    /** The three files of one world's samples. */
    private static final class Sink {
        final Path base;
        final DataOutputStream cubes, blocks;
        final Writer meta;
        int count;
        Sink(Path base) throws IOException {
            this.base = base;
            cubes = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(base + ".mtvx")), 1 << 16));
            blocks = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(base + ".mtvb")), 1 << 16));
            meta = new BufferedWriter(Files.newBufferedWriter(Path.of(base + ".jsonl"), StandardCharsets.UTF_8));
            cubes.writeBytes("MTVX");
            cubes.writeInt(2); cubes.writeInt(0); cubes.writeInt(VoxelGrid.CUBE); cubes.writeInt(Material.COUNT); cubes.writeInt(0);
        }
        void write(byte[] cube, short[] cells, Map<String, Object> labels) throws IOException {
            cubes.write(cube);
            for (short s : cells) blocks.writeShort(s);
            meta.write(GSON.toJson(labels)); meta.write('\n');
            count++;
        }
        void close() throws IOException {
            cubes.close(); blocks.close(); meta.close();
            try (RandomAccessFile file = new RandomAccessFile(base + ".mtvx", "rw")) { file.seek(8); file.writeInt(count); }
        }
    }
}
