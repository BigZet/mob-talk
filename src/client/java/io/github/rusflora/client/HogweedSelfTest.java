package io.github.rusflora.client;

import com.mojang.datafixers.util.Pair;
import io.github.rusflora.RusFlora;
import io.github.rusflora.hogweed.Hogweed;
import io.github.rusflora.hogweed.HogweedBlock;
import io.github.rusflora.hogweed.HogweedConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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

/** The unattended hogweed self-test, on with -Drusflora.hogweedtest=true (./gradlew runHogweedtest): makes a fresh world,
 * counts the patches world generation put into meadows, forests and river banks (and checks deserts and jungles have none),
 * shows every stage, lets one plant loose on a plot of grass, flowers, saplings and wheat, checks the switches and the limit
 * of the config, cuts and digs plants, and walks a player through a thicket at noon, at night, in water and in armour.
 * Report: run-hogweedtest/hogweed-selftest.txt; screenshots hogweed-*.png in its screenshots folder. */
public final class HogweedSelfTest {
    private static final List<String> report = new ArrayList<>();
    private static final Deque<Step> steps = new ArrayDeque<>();
    private static int phase, ticks, failures, wait, waited;
    private static volatile String screenshot;
    private static volatile boolean finished, hud;
    private static BlockPos wild, row, plot, lab;

    private record Step(int delay, Runnable action) { }

    private HogweedSelfTest() { }

    static void register() {
        if (!Boolean.getBoolean("rusflora.hogweedtest")) return;
        log("самотест борщевика включён");
        ClientTickEvents.END_CLIENT_TICK.register(HogweedSelfTest::tick);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (steps.isEmpty() || --wait > 0) return;
            Step step = steps.poll();
            try { step.action().run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); RusFlora.LOG.warn("самотест", failure); }
            wait = steps.isEmpty() ? 0 : Math.max(1, steps.peek().delay());
        });
    }

    private static void tick(MinecraftClient mc) {
        ticks++;
        mc.options.hudHidden = phase == 2 && !hud;
        if (screenshot != null && !mc.worldRenderer.isTerrainRenderComplete() && waited++ < 200) return;
        if (screenshot != null) {
            waited = 0;
            String name = "hogweed-" + screenshot + ".png";
            screenshot = null;
            ScreenshotRecorder.saveScreenshot(mc.runDirectory, name, mc.getFramebuffer(), text -> log("  скриншот " + name));
        }
        switch (phase) {
            case 0 -> {
                if (mc.currentScreen instanceof AccessibilityOnboardingScreen) { // first start in a fresh run folder
                    mc.options.onboardAccessibility = false;
                    mc.options.write();
                    mc.setScreen(new TitleScreen());
                }
                if (!(mc.currentScreen instanceof TitleScreen)) return;
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                rules.get(GameRules.RANDOM_TICK_SPEED).set(0, null); // the test ticks its plants itself, in a known order
                String name = "rusflora-hogweedtest";
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.EASY, true, rules, DataConfiguration.SAFE_MODE);
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
        HogweedConfig config = HogweedConfig.get();
        log("настройки: разлёт " + config.spread + ", радиус " + config.spreadRadius + ", предел " + config.maxPlantsNearby + " на 7x7");

        if (Files.exists(Path.of("hogweed-models-only"))) { models(world, player); wait = 1; return; }
        worldgen(world, player);
        if (wild != null) {
            steps.add(new Step(10, () -> look(player, Vec3d.ofCenter(world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, wild.add(8, 0, 8))).add(0, 4, 0), Vec3d.ofCenter(wild).add(0, 1.5, 0))));
            steps.add(new Step(120, () -> screenshot = "wild"));
        }
        steps.add(new Step(20, () -> stages(world, player)));
        steps.add(new Step(120, () -> screenshot = "stages"));
        steps.add(new Step(10, () -> look(player, Vec3d.ofCenter(row).add(10, 4.5, 3), Vec3d.ofCenter(row).add(10, 2.5, 0))));
        steps.add(new Step(60, () -> screenshot = "umbels"));
        steps.add(new Step(10, () -> plotSetup(world, player)));
        steps.add(new Step(120, () -> screenshot = "invasion-before"));
        steps.add(new Step(10, () -> invade(world)));
        steps.add(new Step(120, () -> screenshot = "invasion-after"));
        steps.add(new Step(10, () -> look(player, Vec3d.ofCenter(plot).add(0, 26, 1), Vec3d.ofCenter(plot))));
        steps.add(new Step(80, () -> screenshot = "invasion-above"));
        steps.add(new Step(10, () -> spreadOff(world)));
        steps.add(new Step(10, () -> removal(world, player)));
        burns(world, player);
        steps.add(new Step(10, () -> {
            check(FlammableBlockRegistry.getDefaultInstance().get(Hogweed.BLOCK).getBurnChance() > 0, "борщевик горит");
            log("ошибок всего: " + failures); finished = true;
        }));
        wait = 1;
    }

    private static void worldgen(ServerWorld world, ServerPlayerEntity player) {
        record Group(String name, List<RegistryKey<Biome>> biomes, boolean russian) { }
        List<Group> groups = List.of(
                new Group("равнина и луг", List.of(BiomeKeys.PLAINS, BiomeKeys.MEADOW, BiomeKeys.SUNFLOWER_PLAINS), true),
                new Group("лес", List.of(BiomeKeys.FOREST, BiomeKeys.BIRCH_FOREST, BiomeKeys.FLOWER_FOREST), true),
                new Group("тайга", List.of(BiomeKeys.TAIGA), true),
                new Group("река", List.of(BiomeKeys.RIVER), true),
                new Group("пустыня", List.of(BiomeKeys.DESERT), false),
                new Group("джунгли", List.of(BiomeKeys.JUNGLE), false),
                new Group("саванна", List.of(BiomeKeys.SAVANNA), false));
        int russian = 0;
        for (Group group : groups) {
            Pair<BlockPos, RegistryEntry<Biome>> hit = world.locateBiome(e -> group.biomes().stream().anyMatch(e::matchesKey), player.getBlockPos(), 6400, 32, 64);
            if (hit == null) { log("  " + group.name() + ": биом не найден рядом"); continue; }
            int count = count(world, hit.getFirst(), 5, group.biomes());
            log("  " + group.name() + " у " + hit.getFirst().toShortString() + ": растений борщевика " + count);
            if (group.russian()) russian += count;
            else check(count == 0, group.name() + ": борщевика нет");
        }
        check(russian > 0, "борщевик есть в средней полосе (" + russian + ")");
        log("  дикий очаг: " + (wild == null ? "не найден" : wild.toShortString()));
    }

    /** Generates chunks within radius chunks around at and counts hogweed roots; remembers a flowering one. */
    /** Hogweed plants standing in the group's own biomes around at (not in neighbouring biomes sharing these chunks). */
    private static int count(ServerWorld world, BlockPos at, int radius, List<RegistryKey<Biome>> biomes) {
        int count = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int cx = (at.getX() >> 4) - radius; cx <= (at.getX() >> 4) + radius; cx++)
            for (int cz = (at.getZ() >> 4) - radius; cz <= (at.getZ() >> 4) + radius; cz++) {
                var chunk = world.getChunk(cx, cz);
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++) {
                        int top = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
                        for (int y = top; y > top - 6; y--) {
                            BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (!state.isOf(Hogweed.BLOCK) || state.get(HogweedBlock.PART) != 0) continue;
                            var biome = world.getBiome(new BlockPos((cx << 4) + x, y, (cz << 4) + z));
                            if (biomes.stream().noneMatch(biome::matchesKey)) continue;
                            count++;
                            if (wild == null && state.get(HogweedBlock.STAGE) == HogweedBlock.FLOWERING) wild = new BlockPos((cx << 4) + x, y, (cz << 4) + z);
                        }
                    }
            }
        return count;
    }

    /** A grass platform of size dx by dz high in the open sky above from, so terrain never shades or hides it; returns its
     * corner one block above the grass. */
    private static BlockPos field(ServerWorld world, BlockPos from, int dx, int dz) {
        BlockPos base = new BlockPos(from.getX(), 200, from.getZ());
        for (BlockPos pos : BlockPos.iterate(base.add(-4, -3, -4), base.add(dx + 4, 16, dz + 4)))
            world.setBlockState(pos, pos.getY() < base.getY() ? (pos.getY() == base.getY() - 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).getDefaultState()
                    : Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        return base;
    }

    private static void stages(ServerWorld world, ServerPlayerEntity player) {
        row = field(world, player.getBlockPos().add(300, 0, 300), 24, 8);
        for (int stage = 0; stage <= HogweedBlock.DRY; stage++) Hogweed.BLOCK.setStage(world, row.add(stage * 4, 0, 0), stage);
        for (int stage = 0; stage <= HogweedBlock.DRY; stage++) {
            BlockState root = world.getBlockState(row.add(stage * 4, 0, 0));
            check(root.isOf(Hogweed.BLOCK) && root.get(HogweedBlock.STAGE) == stage, "стадия " + stage + " стоит");
        }
        world.setBlockState(row.add(24, 0, 0), Hogweed.DRY_UMBEL.getDefaultState(), Block.NOTIFY_ALL);
        look(player, Vec3d.ofCenter(row).add(11, 3, 15), Vec3d.ofCenter(row).add(11, 1.5, 0));
    }

    /** Quick look at the models only (run folder has a file hogweed-models-only): every stage from several sides, at a
     * standing player's eye height and from above, plus close-ups of the umbels. */
    private static void models(ServerWorld world, ServerPlayerEntity player) {
        steps.add(new Step(5, () -> stages(world, player)));
        steps.add(new Step(140, () -> screenshot = "m-row-eye"));
        steps.add(new Step(5, () -> look(player, Vec3d.ofCenter(row).add(11, 12, 12), Vec3d.ofCenter(row).add(11, 0, 0))));
        steps.add(new Step(40, () -> screenshot = "m-row-above"));
        for (int stage = 0; stage <= HogweedBlock.DRY; stage++) {
            int s = stage;
            double h = HogweedBlock.height(stage);
            steps.add(new Step(5, () -> look(player, at(s).add(2.2, 1.62, 3.2), at(s).add(0, h * 0.5, 0))));
            steps.add(new Step(30, () -> screenshot = "m-stage" + s + "-eye"));
            steps.add(new Step(5, () -> look(player, at(s).add(-3.5, h + 1.5, -2), at(s).add(0, h * 0.6, 0))));
            steps.add(new Step(30, () -> screenshot = "m-stage" + s + "-high"));
        }
        steps.add(new Step(5, () -> look(player, at(3).add(0.6, 0.4, 1.4), at(3).add(0, 2.2, 0))));
        steps.add(new Step(30, () -> screenshot = "m-umbel-below"));
        steps.add(new Step(5, () -> look(player, at(6).add(1.2, 0.8, 1.6), at(6))));
        steps.add(new Step(30, () -> screenshot = "m-dry-umbel"));
        steps.add(new Step(10, () -> { log("модели сняты"); finished = true; }));
    }

    private static Vec3d at(int slot) { return Vec3d.ofCenter(row.add(slot * 4, 0, 0)); }

    private static void plotSetup(ServerWorld world, ServerPlayerEntity player) {
        plot = field(world, player.getBlockPos().add(100, 0, 0), 20, 20).add(10, 0, 10);
        var random = world.getRandom();
        for (BlockPos pos : BlockPos.iterate(plot.add(-10, 0, -10), plot.add(10, 0, 10))) {
            int roll = random.nextInt(10);
            world.setBlockState(pos, (roll < 6 ? Blocks.SHORT_GRASS : roll < 8 ? Blocks.DANDELION : roll < 9 ? Blocks.CORNFLOWER : Blocks.AIR).getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        for (int i = -2; i <= 2; i++) world.setBlockState(plot.add(i * 3, 0, -6), Blocks.OAK_SAPLING.getDefaultState(), Block.NOTIFY_LISTENERS);
        // A strip of wheat along a water channel.
        for (int x = -6; x <= 6; x++) {
            world.setBlockState(plot.add(x, -1, 5), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
            for (int z = 3; z <= 4; z++) {
                world.setBlockState(plot.add(x, -1, z), Blocks.FARMLAND.getDefaultState().with(net.minecraft.block.FarmlandBlock.MOISTURE, 7), Block.NOTIFY_LISTENERS);
                world.setBlockState(plot.add(x, 0, z), ((CropBlock) Blocks.WHEAT).withAge(5), Block.NOTIFY_LISTENERS);
            }
            world.setBlockState(plot.add(x, 0, 5), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        for (int i = 0; i < 3; i++) world.setBlockState(plot.add(i - 1, 0, 0), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        Hogweed.BLOCK.setStage(world, plot, HogweedBlock.SEEDING);
        log("  участок до: " + census(world) + ", под пшеницей " + world.getBlockState(plot.add(0, -1, 3)).getBlock() + ", на ней " + world.getBlockState(plot.add(0, 0, 3)));
        look(player, Vec3d.ofCenter(plot).add(0, 9, 19), Vec3d.ofCenter(plot));
    }

    private static Map<String, Integer> census(ServerWorld world) {
        Map<String, Integer> counts = new TreeMap<>();
        int crowd = 0;
        for (BlockPos pos : BlockPos.iterate(plot.add(-16, -1, -16), plot.add(16, 3, 16))) {
            BlockState state = world.getBlockState(pos);
            String key = null;
            if (state.isOf(Hogweed.BLOCK) && state.get(HogweedBlock.PART) == 0) {
                key = "борщевик";
                crowd = Math.max(crowd, Hogweed.BLOCK.crowd(world, pos));
            } else if (state.isOf(Blocks.SHORT_GRASS)) key = "трава";
            else if (state.isIn(BlockTags.SMALL_FLOWERS)) key = "цветы";
            else if (state.isOf(Blocks.OAK_SAPLING)) key = "саженцы";
            else if (state.isOf(Blocks.DEAD_BUSH)) key = "сухие кусты";
            else if (state.isOf(Blocks.WHEAT)) key = "пшеница";
            else if (state.isOf(Blocks.GRASS_BLOCK)) key = "дёрн";
            else if (state.isOf(Blocks.DIRT) && world.getBlockState(pos.up()).isAir()) key = "голая земля";
            if (key != null) counts.merge(key, 1, Integer::sum);
        }
        counts.put("теснота", crowd);
        return counts;
    }

    /** Every hogweed root on the plot gets a random tick per round, as if many game days passed. */
    private static void rounds(ServerWorld world, BlockPos centre, int radius, int rounds) {
        var random = world.getRandom();
        for (int round = 0; round < rounds; round++) {
            List<BlockPos> roots = new ArrayList<>();
            for (BlockPos pos : BlockPos.iterate(centre.add(-radius, -2, -radius), centre.add(radius, 3, radius))) {
                BlockState state = world.getBlockState(pos);
                if (state.isOf(Hogweed.BLOCK) && state.get(HogweedBlock.PART) == 0) roots.add(pos.toImmutable());
            }
            for (BlockPos pos : roots) {
                BlockState state = world.getBlockState(pos);
                if (state.isOf(Hogweed.BLOCK)) state.randomTick(world, pos, random);
            }
        }
    }

    private static void invade(ServerWorld world) {
        Map<String, Integer> before = census(world);
        rounds(world, plot, 16, 300);
        Map<String, Integer> after = census(world);
        log("  участок после 300 кругов: " + after);
        check(after.getOrDefault("борщевик", 0) >= 10, "борщевик захватил участок (" + after.getOrDefault("борщевик", 0) + " растений)");
        int greenBefore = before.getOrDefault("трава", 0) + before.getOrDefault("цветы", 0);
        int greenAfter = after.getOrDefault("трава", 0) + after.getOrDefault("цветы", 0);
        check(greenAfter < greenBefore / 2, "трава и цветы задавлены (" + greenBefore + " → " + greenAfter + ")");
        check(after.getOrDefault("сухие кусты", 0) > 0, "саженцы засохли");
        check(after.getOrDefault("пшеница", 0) < before.getOrDefault("пшеница", 0), "пшеница вянет");
    }

    private static void spreadOff(ServerWorld world) {
        HogweedConfig config = HogweedConfig.get();
        BlockPos spot = field(world, plot.add(60, 0, 0), 12, 12).add(6, 0, 6);
        Hogweed.BLOCK.setStage(world, spot, HogweedBlock.SEEDING);
        config.spread = false;
        rounds(world, spot, 10, 100);
        config.spread = true;
        int roots = 0;
        for (BlockPos pos : BlockPos.iterate(spot.add(-10, -2, -10), spot.add(10, 3, 10))) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Hogweed.BLOCK) && state.get(HogweedBlock.PART) == 0) roots++;
        }
        check(roots <= 1, "с выключенным разлётом новых растений нет (" + roots + ")");
        // With a limit of one plant per 7x7 square and seeds flying one block, the parent itself fills every landing square.
        Hogweed.BLOCK.setStage(world, spot.add(0, 0, 0), HogweedBlock.SEEDING);
        config.maxPlantsNearby = 1; config.spreadRadius = 1;
        for (int i = 0; i < 200; i++) Hogweed.BLOCK.scatterSeeds(world, spot, world.getRandom(), 2);
        config.maxPlantsNearby = 16; config.spreadRadius = 6;
        roots = 0;
        for (BlockPos pos : BlockPos.iterate(spot.add(-10, -2, -10), spot.add(10, 3, 10))) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Hogweed.BLOCK) && state.get(HogweedBlock.PART) == 0) roots++;
        }
        check(roots == 1, "предел тесноты не пускает новые всходы (" + roots + ")");
    }

    private static void removal(ServerWorld world, ServerPlayerEntity player) {
        lab = field(world, plot.add(0, 0, 60), 16, 8);
        player.changeGameMode(GameMode.SURVIVAL);
        BlockPos a = lab, b = lab.add(4, 0, 0), c = lab.add(8, 0, 0), d = lab.add(12, 0, 0);
        Hogweed.BLOCK.setStage(world, a, HogweedBlock.FLOWERING);
        Hogweed.BLOCK.setStage(world, b, HogweedBlock.ROSETTE);
        Hogweed.BLOCK.setStage(world, c, HogweedBlock.ROSETTE);
        Hogweed.BLOCK.setStage(world, d, HogweedBlock.SEEDLING);
        player.teleport(world, a.getX() + 0.5, a.getY(), a.getZ() + 2.5, 180, 30);

        player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.SHEARS));
        player.interactionManager.tryBreakBlock(a.up());
        BlockState root = world.getBlockState(a);
        check(root.isOf(Hogweed.BLOCK) && root.get(HogweedBlock.STAGE) == HogweedBlock.ROSETTE && world.getBlockState(a.up(2)).isAir(),
                "срезанный стебель оставил розетку");
        check(!world.getEntitiesByClass(ItemEntity.class, new Box(a).expand(3), e -> e.getStack().isOf(Hogweed.HOLLOW_STALK)).isEmpty(), "выпал полый стебель");
        check(!player.hasStatusEffect(Hogweed.SAP), "ножницы не пачкают соком");

        player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        player.interactionManager.tryBreakBlock(b);
        root = world.getBlockState(b);
        check(root.isOf(Hogweed.BLOCK) && root.get(HogweedBlock.STAGE) == HogweedBlock.SEEDLING, "сломанная рукой розетка отрастает от корня");
        check(player.hasStatusEffect(Hogweed.SAP), "рука в соке после поломки");
        player.removeStatusEffect(Hogweed.SAP);

        player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
        player.interactionManager.tryBreakBlock(c);
        check(world.getBlockState(c).isAir(), "выкопанная лопатой розетка не отрастает");

        int grown = 0;
        for (int i = 0; i < 8 && world.getBlockState(d).get(HogweedBlock.STAGE) < HogweedBlock.FLOWERING; i++, grown++)
            Hogweed.BLOCK.grow(world, world.getRandom(), d, world.getBlockState(d));
        check(world.getBlockState(d).get(HogweedBlock.STAGE) == HogweedBlock.FLOWERING && world.getBlockState(d.up(2)).isOf(Hogweed.BLOCK),
                "костная мука доращивает всход до цветения (" + grown + " раз)");
        player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        player.removeStatusEffect(Hogweed.SAP);
    }

    private static void burns(ServerWorld world, ServerPlayerEntity player) {
        HogweedConfig config = HogweedConfig.get();
        BlockPos[] thicket = new BlockPos[1];
        float[] health = new float[1];
        steps.add(new Step(60, () -> { // after the two-second guard against repeated splashes from the removal test
            config.burnDelaySeconds = 3;
            thicket[0] = field(world, lab.add(0, 0, 40), 10, 10).add(5, 0, 5);
            world.setTimeOfDay(6000);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) Hogweed.BLOCK.setStage(world, thicket[0].add(x * 2, 0, z * 2), HogweedBlock.BIG);
            for (BlockPos pos : BlockPos.iterate(thicket[0].add(6, -1, -1), thicket[0].add(8, -1, 1))) world.setBlockState(pos, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
            player.clearStatusEffects();
            player.setHealth(20);
            // Hungry, so natural healing does not hide the burn damage.
            player.getHungerManager().setFoodLevel(10);
            player.getHungerManager().setSaturationLevel(0);
            walkInto(player, thicket[0]);
        }));
        steps.add(new Step(20, () -> {
            check(player.hasStatusEffect(Hogweed.SAP) && !player.hasStatusEffect(Hogweed.BURN), "в зарослях сок попал на кожу, ожога ещё нет");
            player.teleport(world, thicket[0].getX() + 0.5, thicket[0].getY(), thicket[0].getZ() - 4.5, 0, 10);
            health[0] = player.getHealth();
        }));
        steps.add(new Step(110, () -> {
            check(player.hasStatusEffect(Hogweed.BURN), "через 3 с на солнце появился ожог");
            check(player.getHealth() < health[0], "ожог ранит (" + health[0] + " → " + player.getHealth() + ")");
            hud = true;
            look(player, Vec3d.ofCenter(thicket[0]).add(0, 1.6, -4), Vec3d.ofCenter(thicket[0]).add(0, 1, 0));
        }));
        steps.add(new Step(30, () -> screenshot = "burn"));
        steps.add(new Step(30, () -> {
            hud = false;
            player.clearStatusEffects();
            player.setHealth(20);
            world.setTimeOfDay(18000);
            walkInto(player, thicket[0]);
        }));
        steps.add(new Step(20, () -> {
            check(player.hasStatusEffect(Hogweed.SAP), "ночью сок тоже пачкает");
            player.teleport(world, thicket[0].getX() + 0.5, thicket[0].getY(), thicket[0].getZ() - 4.5, 0, 10);
        }));
        steps.add(new Step(110, () -> {
            check(!player.hasStatusEffect(Hogweed.BURN), "ночью ожога нет");
            player.clearStatusEffects();
            world.setTimeOfDay(6000);
            walkInto(player, thicket[0]);
        }));
        steps.add(new Step(50, () -> {
            check(player.hasStatusEffect(Hogweed.SAP), "сок перед мытьём");
            player.teleport(world, thicket[0].getX() + 7.5, thicket[0].getY() - 1, thicket[0].getZ() + 0.5, 0, 10);
        }));
        steps.add(new Step(50, () -> {
            check(!player.hasStatusEffect(Hogweed.SAP), "вода смыла сок");
            player.clearStatusEffects();
            player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            walkInto(player, thicket[0]);
        }));
        steps.add(new Step(50, () -> {
            check(!player.hasStatusEffect(Hogweed.SAP), "в нагруднике и поножах сок не достаёт");
            player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
            player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
            player.changeGameMode(GameMode.SPECTATOR);
            config.burnDelaySeconds = 30;
            log("  предметы: " + Registries.ITEM.getId(Hogweed.SEEDS) + ", " + Registries.ITEM.getId(Hogweed.HOLLOW_STALK));
        }));
    }

    private static void walkInto(ServerPlayerEntity player, BlockPos plant) {
        player.teleport(player.getServerWorld(), plant.getX() + 0.5, plant.getY(), plant.getZ() + 0.5, 0, 0);
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
        RusFlora.LOG.info("[борщевик] {}", line);
    }
    private static void finish(MinecraftClient mc) {
        phase = 3;
        try { Files.write(Path.of(mc.runDirectory.getPath(), "hogweed-selftest.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { RusFlora.LOG.warn("отчёт не записан", failure); }
        mc.execute(mc::scheduleStop);
    }
}
