package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.cemetery.CemeteryBlocks;
import io.github.divineblock.cemetery.GraveBlock;
import io.github.divineblock.cemetery.TallGraveBlock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/** The unattended cemetery test, on with -Ddivineblock.cemeterytest=true: makes a fresh world, lines up every cemetery block on
 * a flattened lawn and photographs it from the front, at an angle and from behind, lays a raw cemetery template on the lawn,
 * generates the cemetery structure on natural ground, checks the recipes, the templates and that a tall cross breaks whole,
 * then quits. Report: %LOCALAPPDATA%\DivineBlock\cemeterytest\report.txt; screenshots in the game's screenshots folder. */
public final class CemeteryTest {
    private record Step(int delay, Consumer<MinecraftClient> action) { }

    private static final List<String> report = new ArrayList<>();
    private static final Deque<Step> steps = new ArrayDeque<>();
    private static int phase, ticks, wait, failures;
    private static volatile String screenshot;
    private static volatile BlockPos base, field, wild;
    private static volatile int[] wildBounds;

    private CemeteryTest() { }

    public static void register() {
        if (!Boolean.getBoolean("divineblock.cemeterytest")) return;
        log("тест кладбища включён");
        ClientTickEvents.END_CLIENT_TICK.register(CemeteryTest::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        if (screenshot != null) {
            String name = "cemetery-" + screenshot + ".png";
            screenshot = null;
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), text -> log("  скриншот " + name));
        }
        switch (phase) {
            case 0 -> {
                if (!(client.currentScreen instanceof TitleScreen)) return;
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                String name = "divineblock-cemeterytest";
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + name);
                phase = 1; ticks = 0;
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(20261021L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (client.player != null && client.getServer() != null && client.player.age > 60) { plan(); phase = 2; wait = 0; }
                else if (ticks > 20 * 240) { fail("мир не открылся"); finish(client); }
            }
            case 2 -> {
                if (wait > 0) { wait--; return; }
                Step step = steps.poll();
                if (step == null) { log("ошибок всего: " + failures); finish(client); return; }
                try { step.action.accept(client); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("тест кладбища", failure); }
                wait = step.delay;
            }
            default -> { }
        }
    }

    private static void plan() {
        List<Block> blocks = CemeteryBlocks.all();
        on(20, client -> serverDo(client, server -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
            ServerWorld world = server.getOverworld();
            BlockPos spawn = player.getBlockPos();
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
            base = new BlockPos(spawn.getX(), y, spawn.getZ());
            field = base.add(48, 0, 0);
            run(server, "gamemode spectator @a");
            run(server, "time set noon");
            run(server, "weather clear");
            lawn(server, base.add(-4, 0, -8), base.add(38, 0, 10));
            lawn(server, field.add(-4, 0, -6), field.add(26, 0, 32));
            for (int i = 0; i < blocks.size(); i++) place(world, base.add(2 * i, 1, 0), blocks.get(i), Direction.SOUTH);
            // A small plot by hand: a fence ring with a gate, a cross on a two block mound.
            BlockPos plot = base.add(0, 1, 5);
            Block fence = CemeteryBlocks.BLUE_GRAVE_FENCE;
            for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
                if (x != 0 && x != 4 && z != 0 && z != 4) continue;
                world.setBlockState(plot.add(x, 0, z), (x == 2 && z == 4 ? CemeteryBlocks.BLUE_GRAVE_GATE : fence).getDefaultState()
                        .withIfExists(net.minecraft.block.FenceGateBlock.FACING, Direction.SOUTH));
            }
            for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
                BlockPos at = plot.add(x, 0, z);
                world.setBlockState(at, Block.postProcessState(world.getBlockState(at), world, at));
            }
            place(world, plot.add(2, 0, 1), CemeteryBlocks.ROOFED_CROSS, Direction.SOUTH);
            place(world, plot.add(2, 0, 2), CemeteryBlocks.GRAVE_MOUND, Direction.SOUTH);
            place(world, plot.add(2, 0, 3), CemeteryBlocks.GRAVE_MOUND, Direction.SOUTH);
            place(world, plot.add(1, 0, 1), CemeteryBlocks.FUNERAL_WREATH, Direction.SOUTH);
            place(world, plot.add(3, 0, 1), CemeteryBlocks.GRAVE_LAMPADA, Direction.SOUTH);
            place(world, plot.add(3, 0, 3), CemeteryBlocks.GRAVE_BENCH, Direction.WEST);
            place(world, plot.add(1, 0, 3), CemeteryBlocks.ARTIFICIAL_FLOWERS, Direction.SOUTH);
            world.setBlockState(plot.add(1, 0, 2), CemeteryBlocks.GRAVE_MOUND.getDefaultState().with(GraveBlock.FACING, Direction.SOUTH)
                    .with(io.github.divineblock.cemetery.GraveMoundBlock.OVERGROWN, true));
            run(server, String.format("place template divineblock:cemetery/cemetery_1 %d %d %d", field.getX(), field.getY(), field.getZ()));
            checks(server);
        }));
        on(10, client -> client.options.hudHidden = true);
        int groups = (blocks.size() + 4) / 5;
        for (int g = 0; g < groups; g++) {
            int group = g;
            double cx = 2 * (5 * g + 2);
            camera(cx, 2.2, 5.2, 180, 14, "front-" + (group + 1));
            camera(cx - 4.2, 2.6, 4.2, -135, 18, "angle-" + (group + 1));
            camera(cx + 0.5, 2.4, -5.2, 0, 14, "back-" + (group + 1));
        }
        for (int page = 0; page < 2; page++) {
            int first = page * 9;
            String name = "items-" + (page + 1);
            on(5, client -> serverDo(client, server -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
                player.changeGameMode(GameMode.CREATIVE);
                for (int slot = 0; slot < 9; slot++) {
                    int i = first + slot;
                    player.getInventory().setStack(slot, i < blocks.size() ? new net.minecraft.item.ItemStack(blocks.get(i)) : net.minecraft.item.ItemStack.EMPTY);
                }
            }));
            on(5, client -> tp(client, base.getX() + 2.5, base.getY() + 3, base.getZ() + 9, 180, -10));
            on(1, client -> client.options.hudHidden = false);
            on(60, client -> screenshot = name);
        }
        on(5, client -> { client.options.hudHidden = true; serverDo(client, server -> run(server, "gamemode spectator @a")); });
        camera(2.5, 5.5, 13.5, 180, 38, "plot");
        camera(-3, 3.5, 11.5, -135, 25, "plot-angle");
        camera(10.5, 40, 40, 180, 60, "template-top", true);
        camera(-4, 6, 30, -140, 18, "template-ground", true);
        camera(13, 4, -4, 25, 15, "template-entrance", true);
        on(10, client -> serverDo(client, server -> {
            BlockPos at = base.add(260, 0, 40);
            run(server, String.format("tp @a %d %d %d 180 30", at.getX(), base.getY() + 30, at.getZ()));
        }));
        on(160, client -> serverDo(client, server -> {
            ServerWorld world = server.getOverworld();
            BlockPos at = base.add(260, 0, 40);
            int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, at.getX(), at.getZ());
            wild = new BlockPos(at.getX(), y, at.getZ());
            run(server, String.format("place structure divineblock:cemetery %d %d %d", at.getX(), y, at.getZ()));
            wildBounds = bounds(world, wild, 48);
            if (check(wildBounds != null, "структура кладбища сгенерирована на природном рельефе"))
                log(String.format("  границы: x %d..%d, y %d..%d, z %d..%d", wildBounds[0], wildBounds[3], wildBounds[1], wildBounds[4], wildBounds[2], wildBounds[5]));
        }));
        on(20, client -> {
            if (wildBounds == null) return;
            int[] b = wildBounds;
            double cx = (b[0] + b[3]) / 2.0, cz = (b[2] + b[5]) / 2.0;
            tp(client, cx, b[4] + 30, b[5] + 22, 180, 50);
        });
        on(140, client -> screenshot = "wild-overview");
        on(5, client -> { if (wildBounds != null) tp(client, wildBounds[3] + 8, wildBounds[4] + 6, wildBounds[5] + 8, 135, 22); });
        on(100, client -> screenshot = "wild-corner");
        on(5, client -> { if (wildBounds != null) tp(client, (wildBounds[0] + wildBounds[3]) / 2.0 + 0.5, wildBounds[1] + 3, (wildBounds[2] + wildBounds[5]) / 2.0, 30, 12); });
        on(100, client -> screenshot = "wild-inside");
        on(40, client -> { });
    }

    private static void checks(MinecraftServer server) {
        for (Block block : CemeteryBlocks.all()) {
            Identifier id = Registries.BLOCK.getId(block);
            check(server.getRecipeManager().get(id).isPresent(), "рецепт " + id);
        }
        for (int i = 1; i <= 3; i++) {
            Identifier id = Identifier.of(DivineBlockMod.ID, "cemetery/cemetery_" + i);
            check(server.getStructureTemplateManager().getTemplate(id).isPresent(), "шаблон " + id);
        }
        check(server.getRegistryManager().get(net.minecraft.registry.RegistryKeys.TEMPLATE_POOL)
                .containsId(Identifier.of(DivineBlockMod.ID, "cemetery/start")), "пул divineblock:cemetery/start");
        // A tall cross breaks whole from its top and drops one item.
        ServerWorld world = server.getOverworld();
        BlockPos at = base.add(0, 1, -6);
        place(world, at, CemeteryBlocks.WOODEN_CROSS, Direction.SOUTH);
        boolean both = world.getBlockState(at.up()).isOf(CemeteryBlocks.WOODEN_CROSS);
        world.breakBlock(at.up(), true);
        int drops = world.getEntitiesByClass(ItemEntity.class, new Box(at).expand(2), e -> e.getStack().isOf(CemeteryBlocks.WOODEN_CROSS.asItem()))
                .stream().mapToInt(e -> e.getStack().getCount()).sum();
        check(both && world.getBlockState(at).isAir() && drops == 1, "крест ломается целиком сверху и даёт один предмет (" + drops + ")");
        world.getEntitiesByClass(ItemEntity.class, new Box(at).expand(3), e -> true).forEach(ItemEntity::discard);
    }

    private static void place(ServerWorld world, BlockPos pos, Block block, Direction facing) {
        BlockState state = block.getDefaultState().withIfExists(GraveBlock.FACING, facing);
        if (block instanceof TallGraveBlock) {
            world.setBlockState(pos, state.with(TallGraveBlock.HALF, DoubleBlockHalf.LOWER), Block.NOTIFY_LISTENERS);
            world.setBlockState(pos.up(), state.with(TallGraveBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_LISTENERS);
        } else {
            world.setBlockState(pos, Block.postProcessState(state, world, pos), Block.NOTIFY_ALL);
        }
    }

    private static void lawn(MinecraftServer server, BlockPos from, BlockPos to) {
        run(server, String.format("fill %d %d %d %d %d %d minecraft:dirt", from.getX(), from.getY() - 3, from.getZ(), to.getX(), to.getY() - 1, to.getZ()));
        run(server, String.format("fill %d %d %d %d %d %d minecraft:grass_block", from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ()));
        run(server, String.format("fill %d %d %d %d %d %d minecraft:air", from.getX(), from.getY() + 1, from.getZ(), to.getX(), to.getY() + 14, to.getZ()));
    }

    /** The box around the cemetery blocks found near the point, or null when there are none. */
    private static int[] bounds(ServerWorld world, BlockPos center, int radius) {
        int[] b = null;
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) for (int y = -20; y <= 20; y++) {
            at.set(center.getX() + x, center.getY() + y, center.getZ() + z);
            if (!Registries.BLOCK.getId(world.getBlockState(at).getBlock()).getNamespace().equals(DivineBlockMod.ID)) continue;
            if (b == null) b = new int[] { at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ() };
            b[0] = Math.min(b[0], at.getX()); b[1] = Math.min(b[1], at.getY()); b[2] = Math.min(b[2], at.getZ());
            b[3] = Math.max(b[3], at.getX()); b[4] = Math.max(b[4], at.getY()); b[5] = Math.max(b[5], at.getZ());
        }
        return b;
    }

    private static void camera(double dx, double dy, double dz, float yaw, float pitch, String name) { camera(dx, dy, dz, yaw, pitch, name, false); }

    private static void camera(double dx, double dy, double dz, float yaw, float pitch, String name, boolean onField) {
        on(5, client -> {
            BlockPos o = onField ? field : base;
            tp(client, o.getX() + dx, o.getY() + dy, o.getZ() + dz, yaw, pitch);
        });
        on(70, client -> screenshot = name);
    }

    private static void tp(MinecraftClient client, double x, double y, double z, float yaw, float pitch) {
        serverDo(client, server -> run(server, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch)));
    }

    private static void on(int delay, Consumer<MinecraftClient> action) { steps.add(new Step(delay, action)); }

    private static void serverDo(MinecraftClient client, Consumer<MinecraftServer> action) {
        MinecraftServer server = client.getServer();
        server.execute(() -> {
            try { action.accept(server); } catch (RuntimeException failure) { fail("шаг сервера упал: " + failure); DivineBlockMod.LOG.warn("тест кладбища", failure); }
        });
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommandManager().executeWithPrefix(server.getCommandSource().withLevel(4).withSilent(), command);
    }

    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[тест кладбища] {}", line);
    }
    private static void finish(MinecraftClient client) {
        phase = 3;
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "cemeterytest");
        try { Files.createDirectories(dir); synchronized (CemeteryTest.class) { Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); } }
        catch (IOException failure) { DivineBlockMod.LOG.warn("тест кладбища: отчёт не записан", failure); }
        client.execute(client::scheduleStop);
    }
}
