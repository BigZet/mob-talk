package io.github.rusflora.client;

import com.mojang.datafixers.util.Pair;
import io.github.rusflora.RusFlora;
import io.github.rusflora.block.ShrubKind;
import io.github.rusflora.block.ShrubLeavesBlock;
import io.github.rusflora.world.ShrubShapes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/** The unattended self-test, on with -Drusflora.selftest=true: makes a fresh world, finds each biome group, counts the shrubs
 * world generation put there (and checks deserts and jungles have none), photographs a wild shrub of every kind, builds a
 * showcase row of all kinds in all stages, picks fruit and grows a sapling. Report: run/rusflora-selftest.txt; screenshots
 * rusflora-*.png in the run's screenshots folder. */
public final class ShrubSelfTest {
    private static final List<String> report = new ArrayList<>();
    private static final Deque<Step> steps = new ArrayDeque<>();
    private static int phase, ticks, failures, wait;
    private static volatile String screenshot;
    private static volatile boolean finished;
    private static int waited;
    private static MinecraftClient client;

    private record Step(int delay, Runnable action) { }

    private ShrubSelfTest() { }

    public static void register() {
        if (!Boolean.getBoolean("rusflora.selftest")) return;
        log("самотест кустов включён");
        ClientTickEvents.END_CLIENT_TICK.register(ShrubSelfTest::tick);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (steps.isEmpty() || --wait > 0) return;
            Step step = steps.poll();
            try { step.action().run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); RusFlora.LOG.warn("самотест", failure); }
            wait = steps.isEmpty() ? 0 : Math.max(1, steps.peek().delay());
        });
    }

    private static void tick(MinecraftClient mc) {
        client = mc;
        ticks++;
        mc.options.hudHidden = phase == 2;
        if (screenshot != null && !mc.worldRenderer.isTerrainRenderComplete() && waited++ < 200) return;
        if (screenshot != null) {
            waited = 0;
            String name = "rusflora-" + screenshot + ".png";
            screenshot = null;
            ScreenshotRecorder.saveScreenshot(mc.runDirectory, name, mc.getFramebuffer(), text -> log("  скриншот " + name));
        }
        switch (phase) {
            case 0 -> {
                if (!(mc.currentScreen instanceof TitleScreen)) return;
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                String name = "rusflora-selftest";
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + name);
                phase = 1; ticks = 0;
                mc.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(20261021L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (mc.player != null && mc.getServer() != null && mc.player.age > 60) {
                    MinecraftServer server = mc.getServer();
                    var uuid = mc.player.getUuid();
                    server.execute(() -> begin(server.getPlayerManager().getPlayer(uuid)));
                    phase = 2;
                } else if (ticks > 20 * 240) { fail("мир не открылся"); finish(mc); }
            }
            case 2 -> {
                if (finished) finish(mc);
                else if (ticks > 20 * 900) { fail("самотест не уложился в 15 минут"); finish(mc); }
            }
            default -> { }
        }
    }

    // ---- On the server thread. ----

    private static void begin(ServerPlayerEntity player) {
        if (player == null) { fail("нет игрока"); finished = true; return; }
        ServerWorld world = player.getServerWorld();
        world.setWeather(100_000, 0, false, false);
        world.setTimeOfDay(6000);
        player.changeGameMode(GameMode.SPECTATOR);

        Map<ShrubKind, BlockPos> wild = new EnumMap<>(ShrubKind.class);
        record Group(String name, List<RegistryKey<Biome>> biomes, boolean expectShrubs) { }
        List<Group> groups = List.of(
                new Group("лиственный лес", List.of(BiomeKeys.FOREST, BiomeKeys.BIRCH_FOREST, BiomeKeys.FLOWER_FOREST), true),
                new Group("равнина", List.of(BiomeKeys.PLAINS, BiomeKeys.MEADOW), true),
                new Group("тайга", List.of(BiomeKeys.TAIGA, BiomeKeys.OLD_GROWTH_PINE_TAIGA, BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA), true),
                new Group("река", List.of(BiomeKeys.RIVER), true),
                new Group("болото", List.of(BiomeKeys.SWAMP), true),
                new Group("пустыня", List.of(BiomeKeys.DESERT), false),
                new Group("джунгли", List.of(BiomeKeys.JUNGLE), false));
        for (Group group : groups) {
            BlockPos at = locate(world, player.getBlockPos(), group.biomes());
            if (at == null) { log("  " + group.name() + ": биом не найден рядом"); continue; }
            Map<ShrubKind, Integer> counts = count(world, at, 4, wild);
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            log("  " + group.name() + " у " + at.toShortString() + " (" + world.getBiome(at).getKey().map(k -> k.getValue().getPath()).orElse("?")
                    + "): блоков кустов " + total + " " + counts);
            if (group.expectShrubs()) check(total > 0, group.name() + ": кусты есть");
            else check(total == 0, group.name() + ": кустов нет");
        }
        for (ShrubKind kind : ShrubKind.values()) log("  дикий " + kind.id + ": " + (wild.containsKey(kind) ? wild.get(kind).toShortString() : "не найден"));

        // A wild shrub of each kind found.
        for (ShrubKind kind : ShrubKind.values()) {
            BlockPos pos = wild.get(kind);
            if (pos == null) continue;
            steps.add(new Step(10, () -> look(player, Vec3d.ofCenter(pos).add(7, 3, 7), Vec3d.ofCenter(pos))));
            steps.add(new Step(100, () -> screenshot = "wild-" + kind.id));
        }
        steps.add(new Step(40, () -> showcase(world, player)));
        steps.add(new Step(120, () -> screenshot = "showcase-front"));
        steps.add(new Step(10, () -> look(player, showcaseEye(world, -1), showcaseCentre(world))));
        steps.add(new Step(80, () -> screenshot = "showcase-side"));
        steps.add(new Step(10, () -> harvest(world, player)));
        steps.add(new Step(10, () -> sapling(world)));
        steps.add(new Step(10, () -> { log("ошибок всего: " + failures); finished = true; }));
        wait = 1;
    }

    private static BlockPos locate(ServerWorld world, BlockPos from, List<RegistryKey<Biome>> biomes) {
        Pair<BlockPos, RegistryEntry<Biome>> hit = world.locateBiome(entry -> biomes.stream().anyMatch(entry::matchesKey), from, 6400, 32, 64);
        return hit == null ? null : hit.getFirst();
    }

    /** Generates the chunks within radius around at and counts shrub blocks by kind; remembers one leaves block per kind. */
    private static Map<ShrubKind, Integer> count(ServerWorld world, BlockPos at, int radius, Map<ShrubKind, BlockPos> wild) {
        Map<ShrubKind, Integer> counts = new EnumMap<>(ShrubKind.class);
        int cx0 = at.getX() >> 4, cz0 = at.getZ() >> 4;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int cx = cx0 - radius; cx <= cx0 + radius; cx++)
            for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
                var chunk = world.getChunk(cx, cz);
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++) {
                        int top = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z) + 1;
                        for (int y = top; y > top - 10; y--) {
                            BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (!(state.getBlock() instanceof ShrubLeavesBlock leaves)) continue;
                            counts.merge(leaves.kind, 1, Integer::sum);
                            wild.putIfAbsent(leaves.kind, new BlockPos((cx << 4) + x, y, (cz << 4) + z));
                        }
                    }
            }
        return counts;
    }

    /** All kinds in a row on a cleared grass strip: a grown shrub per kind, then a column of leaves in each stage. */
    private static BlockPos showcaseBase;
    private static void showcase(ServerWorld world, ServerPlayerEntity player) {
        BlockPos origin = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, player.getBlockPos().add(200, 0, 200));
        int y = origin.getY() + 2;
        showcaseBase = new BlockPos(origin.getX(), y, origin.getZ());
        ShrubKind[] kinds = ShrubKind.values();
        for (BlockPos pos : BlockPos.iterate(showcaseBase.add(-6, -3, -6), showcaseBase.add(kinds.length * 8, 12, 14)))
            world.setBlockState(pos, pos.getY() < y ? (pos.getY() == y - 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        var random = world.getRandom();
        for (int i = 0; i < kinds.length; i++) {
            BlockPos ground = showcaseBase.add(i * 8, -1, 0);
            check(ShrubShapes.grow(world, ground, kinds[i], random, true), "вырос куст " + kinds[i].id);
            for (int s = 0; s < 3; s++) world.setBlockState(showcaseBase.add(i * 8 - 1 + s, 0, 9),
                    RusFlora.leaves(kinds[i]).getDefaultState().with(ShrubLeavesBlock.STAGE, s), Block.NOTIFY_LISTENERS);
            world.setBlockState(showcaseBase.add(i * 8 + 3, 0, 9), RusFlora.sapling(kinds[i]).getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        look(player, showcaseEye(world, 1), showcaseCentre(world));
    }
    private static Vec3d showcaseCentre(ServerWorld world) { return Vec3d.ofCenter(showcaseBase).add(ShrubKind.values().length * 4 - 4, 2, 4); }
    private static Vec3d showcaseEye(ServerWorld world, int side) {
        return side > 0 ? showcaseCentre(world).add(0, 12, 34) : showcaseCentre(world).add(-40, 6, 10);
    }

    /** Ripe fruit is picked with an empty hand: fruit drops and the leaves go back to stage 0. */
    private static void harvest(ServerWorld world, ServerPlayerEntity player) {
        player.changeGameMode(GameMode.SURVIVAL);
        for (ShrubKind kind : ShrubKind.values()) {
            if (!kind.fruits()) continue;
            BlockPos pos = showcaseBase.add(kind.ordinal() * 8 + 1, 0, 9);
            BlockState state = world.getBlockState(pos);
            var result = state.onUse(world, player, new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
            long fruit = world.getEntitiesByClass(ItemEntity.class, new Box(pos).expand(2), e -> e.getStack().isOf(RusFlora.fruit(kind))).size();
            check(result.isAccepted() && fruit > 0 && world.getBlockState(pos).get(ShrubLeavesBlock.STAGE) == 0,
                    kind.id + ": сорваны плоды " + Registries.ITEM.getId(RusFlora.fruit(kind)) + ", куст снова зелёный");
        }
        player.changeGameMode(GameMode.SPECTATOR);
    }

    /** A sapling grown with bone meal turns into the full shrub. */
    private static void sapling(ServerWorld world) {
        for (ShrubKind kind : ShrubKind.values()) {
            BlockPos pos = showcaseBase.add(kind.ordinal() * 8 + 3, 0, 9);
            var block = RusFlora.sapling(kind);
            for (int i = 0; i < 20 && world.getBlockState(pos).isOf(block); i++) block.grow(world, world.getRandom(), pos, world.getBlockState(pos));
            BlockState now = world.getBlockState(pos);
            check(now.isOf(RusFlora.stem(kind)) || now.isOf(RusFlora.leaves(kind)), kind.id + ": росток вырос в куст (" + now.getBlock() + ")");
        }
    }

    private static void look(ServerPlayerEntity player, Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleport(player.getServerWorld(), eye.x, eye.y - player.getStandingEyeHeight(), eye.z, yaw, pitch);
    }

    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        RusFlora.LOG.info("[самотест] {}", line);
    }
    private static void finish(MinecraftClient mc) {
        phase = 3;
        try { Files.write(Path.of(mc.runDirectory.getPath(), "rusflora-selftest.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { RusFlora.LOG.warn("отчёт не записан", failure); }
        mc.execute(mc::scheduleStop);
    }
}
