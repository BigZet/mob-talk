package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.client.fx.FxEngine;
import io.github.divineblock.client.fx.FxQuality;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.game.Fx;
import io.github.divineblock.game.Miracles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/** The unattended effects test, on with -Ddivineblock.fxtest=true: a fresh world, an altar on a flat stage, then each new
 * effect in turn (the altar's motes at night, the rays in gold and in red with ash from a red sky, feathers, confetti, a
 * bolt, a meteor and its crater) with a screenshot of each and the number of live sprites. Report:
 * %LOCALAPPDATA%\DivineBlock\selftest\fx-report.txt; screenshots divineblock-fx-*.png in the game's screenshots. */
final class FxTest {
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures;
    private static BlockPos altar, base;
    private static MinecraftServer server;
    private static ServerPlayerEntity player;

    private FxTest() { }

    static void register() {
        if (!Boolean.getBoolean("divineblock.fxtest")) return;
        log("тест эффектов включён");
        ClientTickEvents.END_CLIENT_TICK.register(FxTest::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        switch (phase) {
            case 0 -> {
                if (!(client.currentScreen instanceof TitleScreen)) return;
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                String name = "divineblock-fxtest";
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + name);
                phase = 1; ticks = 0;
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(20261021L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (client.player != null && client.getServer() != null && client.player.age > 80) {
                    server = client.getServer();
                    var uuid = client.player.getUuid();
                    server.execute(() -> stage(server.getPlayerManager().getPlayer(uuid)));
                    phase = 2; ticks = 0;
                } else if (ticks > 20 * 240) { fail("мир не открылся"); finish(client); }
            }
            case 2 -> script(client);
            default -> { }
        }
    }

    /** On the server: a flat stage, the altar six blocks north, the player facing it, night. */
    private static void stage(ServerPlayerEntity who) {
        player = who;
        ServerWorld world = who.getServerWorld();
        world.setTimeOfDay(18000);
        world.setWeather(100_000, 0, false, false);
        base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, who.getBlockPos()).up(2);
        for (BlockPos pos : BlockPos.iterate(base.add(-24, -1, -40), base.add(24, 30, 12)))
            world.setBlockState(pos, pos.getY() == base.getY() - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
        for (BlockPos pos : BlockPos.iterate(base.add(-24, -6, -40), base.add(24, -2, 12)))
            if (world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.STONE.getDefaultState());
        altar = base.north(6);
        world.setBlockState(altar, DivineBlockMod.ALTAR.getDefaultState());
        home();
        log("алтарь в " + altar.toShortString() + ", качество эффектов " + FxQuality.level());
    }

    private static void home() {
        player.teleport(player.getServerWorld(), base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180f, -8f);
        player.setVelocity(0, 0, 0);
    }

    private static void server(Runnable action) {
        server.execute(() -> { try { action.run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("тест эффектов", failure); } });
    }

    private static void command(String line) {
        server(() -> server.getCommandManager().executeWithPrefix(player.getCommandSource().withLevel(4), line));
    }

    private static void shot(MinecraftClient client, String name) {
        int alive = FxEngine.alive();
        String file = "divineblock-fx-" + name + ".png";
        ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), text -> { });
        log("скриншот " + file + ", живых частиц и эффектов: " + alive);
    }

    private static void script(MinecraftClient client) {
        if (altar == null) return;
        switch (ticks) {
            // Night: the altar's motes alone.
            case 160 -> { check(FxEngine.alive() > 0, "у алтаря кружат пылинки света"); shot(client, "1-altar-motes"); }
            // The rays of a blessing, then of wrath with the red sky and ash.
            case 180 -> AltarBeams.start(client, altar, 20 * 60, 0xFFFFD24A);
            case 230 -> { check(FxEngine.alive() > 0, "лучи благословения горят"); shot(client, "2-rays-gold"); }
            case 250 -> AltarBeams.start(client, altar, 0, 0xFFFFD24A);
            case 280 -> server(() -> player.getServerWorld().setTimeOfDay(6000));
            case 300 -> { AltarBeams.start(client, altar, 20 * 60, 0xFFFF2A1A); SkyTint.verdict(client, 4); }
            case 400 -> { check(SkyTint.amount() > 0.9f, "небо покраснело"); shot(client, "3-rays-wrath-ash"); }
            case 420 -> { AltarBeams.start(client, altar, 0, 0xFFFF2A1A); SkyTint.verdict(client, 0); }
            // A blessing falls: the pillar, the ring and the feathers.
            case 520 -> server(() -> { player.teleport(player.getServerWorld(), base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0f, 20f);
                Miracles.flourish(player, altar, Miracle.Kind.HELP, 3); });
            case 560 -> server(() -> player.teleport(player.getServerWorld(), base.getX() + 0.5, base.getY(), base.getZ() + 7.5, 180f, -5f));
            case 600 -> shot(client, "4-blessing-feathers");
            case 680 -> shot(client, "5-feathers-ground");
            // A jest: confetti.
            case 720 -> server(() -> Miracles.flourish(player, altar, Miracle.Kind.JEST, 2));
            case 740 -> shot(client, "6-confetti");
            case 820 -> shot(client, "7-confetti-ground");
            // A bolt on the stage, with a vanilla lightning entity under it that must not show.
            case 860 -> server(() -> {
                ServerWorld world = player.getServerWorld();
                BlockPos at = altar.east(4);
                Fx.bolt(world, Vec3d.ofBottomCenter(at), 0xFFB8D4FF);
                LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
                bolt.refreshPositionAfterTeleport(Vec3d.ofBottomCenter(at));
                bolt.setCosmetic(true);
                world.spawnEntity(bolt);
            });
            case 863 -> shot(client, "8-bolt");
            // A meteor in front of the eyes, the blast and the embers in the crater.
            case 900 -> server(FxTest::home);
            case 920 -> command("divineblock act meteor 2");
            case 942 -> shot(client, "9-meteor");
            case 961 -> shot(client, "10-impact");
            case 1040 -> shot(client, "11-crater-embers");
            case 1060 -> client.setScreen(new DivineSettingsScreen(null));
            case 1080 -> shot(client, "12-settings");
            case 1090 -> { client.setScreen(null); log("ошибок всего: " + failures); finish(client); }
            default -> { }
        }
    }

    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[тест эффектов] {}", line);
    }
    private static void finish(MinecraftClient client) {
        phase = 3;
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "selftest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("fx-report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("тест эффектов: отчёт не записан", failure); }
        client.execute(client::scheduleStop);
    }
}
