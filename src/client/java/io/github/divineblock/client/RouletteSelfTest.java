package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.Roulette;
import io.github.divineblock.game.DivineCommands;
import io.github.divineblock.game.Prayers;
import io.github.divineblock.game.RouletteService;
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
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/** The punishment roulette's unattended test, on with -Ddivineblock.selftest=roulette (no paid speech, see GodVoicePlayer): a
 * fresh world with an altar, then spins of each kind with a screenshot at every reel's stop and at the final — silver К1, a
 * gold К3 with the tease, the 777 of the end of the world, the black gates of the ban, a pardon by a real prayer of
 * repentance under the roulette, a mini spin of a tail — a real punishment falling exactly when its spin ends, and the
 * roulette's group in F9. Report: report.txt in -Ddivineblock.selftest.report; screenshots divineblock-roulette-*.png. */
final class RouletteSelfTest {
    private static final String WORLD = "divineblock-roulettetest";
    private static final List<String> report = new ArrayList<>();
    private static final List<String> moments = new ArrayList<>();
    private static int phase, ticks, failures, step, wait;
    private static String scene = "";
    /** Screenshots waiting for their tick: name and the tick to take it at. */
    private static final List<Object[]> SHOTS = new ArrayList<>();
    /** While set, a frame every other tick for a GIF of this scene. */
    private static String recording;
    private static int frame;
    private static BlockPos altar, base;
    private static MinecraftServer server;
    private static ServerPlayerEntity player;
    private static volatile long actAt = -1, actPlanned = -2;
    private static Path configFile;
    private static String savedConfig;

    private RouletteSelfTest() { }

    static void register() {
        if (!"roulette".equals(System.getProperty("divineblock.selftest"))) return;
        log("самотест рулетки кары включён");
        RouletteHud.moment = name -> {
            synchronized (moments) { moments.add(scene + ":" + name); }
            // A screenshot a little after each moment: the reel has bounced and settled, the ribbon has rolled out.
            switch (name) {
                case "stop1", "stop3" -> shotLater(scene + "-" + name, 4);
                case "stop2" -> { shotLater(scene + "-stop2", 4); shotLater(scene + "-anticipation", 16); }
                case "777", "gates" -> shotLater(scene + "-" + name + "-flash", 2);
                case "final" -> shotLater(scene + "-final", 12);
                case "pardon" -> shotLater(scene + "-pardon", 14);
                default -> { }
            }
        };
        RouletteService.said = line -> log("  чат: " + line);
        DivineCommands.acted = line -> log("  " + line);
        ClientTickEvents.END_CLIENT_TICK.register(RouletteSelfTest::tick);
    }

    private static void shotLater(String name, int delay) { synchronized (SHOTS) { SHOTS.add(new Object[] { name, ticks + delay }); } }

    private static void tick(MinecraftClient client) {
        ticks++;
        synchronized (SHOTS) {
            for (var it = SHOTS.iterator(); it.hasNext(); ) {
                Object[] shot = it.next();
                if ((int) shot[1] > ticks) continue;
                it.remove();
                String name = "divineblock-roulette-" + shot[0] + ".png";
                ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), text -> { });
                log("  скриншот " + name);
                break; // one a tick
            }
        }
        if (recording != null && ticks % 2 == 0) {
            if (RouletteHud.showing() || frame < 4) ScreenshotRecorder.saveScreenshot(client.runDirectory, String.format("divineblock-roulette-gif-%s-%03d.png", recording, frame++), client.getFramebuffer(), text -> { });
            else { log("  кадров для GIF " + recording + ": " + frame); recording = null; }
        }
        switch (phase) {
            case 0 -> {
                if (client.world == null && client.currentScreen != null && !(client.currentScreen instanceof TitleScreen) && ticks > 100
                        && client.getOverlay() == null) { client.options.onboardAccessibility = false; client.setScreen(new TitleScreen()); }
                if (!(client.currentScreen instanceof TitleScreen)) return;
                prepareConfig();
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + WORLD);
                phase = 1; ticks = 0;
                client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(20261024L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (client.player != null && client.getServer() != null && client.player.age > 80) {
                    client.options.hudHidden = false;
                    server = client.getServer();
                    var uuid = client.player.getUuid();
                    server.execute(() -> stage(server.getPlayerManager().getPlayer(uuid)));
                    phase = 2; ticks = 0;
                } else if (ticks > 20 * 300) { fail("мир не открылся"); finish(client); }
            }
            case 2 -> { if (altar != null && ticks > 60) { phase = 3; ticks = 0; } }
            case 3 -> script(client);
            default -> { }
        }
    }

    /** On the server: a flat stage by day, the altar a few blocks north. */
    private static void stage(ServerPlayerEntity who) {
        player = who;
        ServerWorld world = who.getServerWorld();
        world.setTimeOfDay(6000);
        world.setWeather(100_000, 0, false, false);
        base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, who.getBlockPos()).up(2);
        for (BlockPos pos : BlockPos.iterate(base.add(-20, -1, -24), base.add(20, 24, 12)))
            world.setBlockState(pos, pos.getY() == base.getY() - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
        altar = base.north(4);
        world.setBlockState(altar, DivineBlockMod.ALTAR.getDefaultState());
        player.teleport(world, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180f, 10f);
        log("алтарь в " + altar.toShortString());
    }

    private static void server(Runnable action) {
        server.execute(() -> { try { action.run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("самотест рулетки", failure); } });
    }

    private static void command(String line) {
        log("> /" + line);
        server(() -> server.getCommandManager().executeWithPrefix(player.getCommandSource().withLevel(4), line));
    }

    private static boolean saw(String what) { synchronized (moments) { return moments.contains(what); } }

    /** The scenes one after another: each starts, then waits until its spin is gone from the screen. */
    private static void script(MinecraftClient client) {
        if (wait > 0) { wait--; return; }
        if (RouletteHud.showing() && step % 2 == 1) return; // a scene runs until its machine leaves
        switch (step++) {
            case 0 -> { scene = "k1"; recording = "k1"; frame = 0; command("divineblock roulette show lightning normal"); wait = 10; }
            case 1 -> { checkScene("k1", true); wait = 10; }
            case 2 -> { scene = "k3tease"; command("divineblock roulette show earthquake tease"); shotAt("k3tease-crawl", Roulette.Timing.of(Roulette.Mode.NORMAL, 3).stops()[2] - 14); wait = 10; }
            case 3 -> { checkScene("k3tease", true); wait = 10; }
            case 4 -> { scene = "k5"; recording = "k5"; frame = 0; command("divineblock roulette show god_mace normal"); wait = 10; }
            case 5 -> { checkScene("k5", true); check(saw("k5:777"), "777 на конце света"); wait = 10; }
            case 6 -> { scene = "k6"; command("divineblock roulette show eternal_ban normal"); wait = 10; }
            case 7 -> { checkScene("k6", true); check(saw("k6:gates"), "запертые врата на изгнании"); wait = 10; }
            case 8 -> { scene = "ks"; command("divineblock roulette show boss_goliath dramatic"); shotAt("ks-spinning", 30); wait = 10; }
            case 9 -> { checkScene("ks", true); wait = 10; }
            // A pardon by a real prayer: a polite prayer first (the altar cools for 20 s), then the spin, then repentance at once.
            case 10 -> {
                scene = "pardon";
                server(() -> Prayers.handle(player, altar, "Господи, благослови мой путь"));
                wait = 20 * 8;
            }
            case 11 -> { command("divineblock roulette undead_horde normal"); wait = 40; }
            case 12 -> { recording = "pardon"; frame = 0; server(() -> Prayers.handle(player, altar, "Господи, прости меня грешного, каюсь")); wait = 10; }
            case 13 -> {
                check(saw("pardon:pardon"), "покаяние под рулеткой: барабаны заклинило, голуби");
                check(!saw("pardon:final"), "помилованная кара не дошла до финала");
                wait = 40;
            }
            // A real punishment: it falls exactly when its spin ends, counted in server ticks.
            case 14 -> {
                scene = "act";
                server(() -> {
                    var spin = RouletteService.spin(player, Miracle.LIGHTNING, null, "самотест", Roulette.Mode.SHORT, false, new RouletteService.Steps() {
                        @Override public void act() { actAt = server.getOverworld().getTime(); }
                    });
                    actPlanned = spin.actTick();
                    log("  молния запланирована на тик " + actPlanned + " (старт " + spin.start + ", длительность " + spin.timing.act() + ")");
                });
                wait = 10;
            }
            case 15 -> {
                check(actAt == actPlanned, "кара упала ровно через длительность рулетки: " + actAt + " = " + actPlanned);
                wait = 10;
            }
            // A tail's mini spin before the second wave.
            case 16 -> { scene = "mini"; command("divineblock roulette show raid mini"); shotAt("mini-spinning", 12); wait = 10; }
            case 17 -> { check(saw("mini:stop1") && saw("mini:final"), "мини-рулетка продолжения: один барабан"); wait = 10; }
            // Another sinner's spin as the others see it.
            case 18 -> { client.setScreen(new WrathSettingsScreen(null, DivineBlockMod.config())); wait = 30; }
            case 19 -> { shotLater("f9-settings", 1); wait = 10; }
            case 20 -> { client.setScreen(null); log("ошибок всего: " + failures); finish(client); }
            default -> { }
        }
    }

    /** A screenshot {@code at} ticks into the scene's spin (by the client's tick count since the command). */
    private static void shotAt(String name, int at) {
        int start = ticks;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ticks - start == at + 2) shotLater(name, 1);
        });
    }

    private static void checkScene(String name, boolean full) {
        check(saw(name + ":start"), name + ": рулетка появилась");
        if (full) check(saw(name + ":stop1") && saw(name + ":stop2") && saw(name + ":stop3"), name + ": три барабана встали по очереди");
        check(saw(name + ":final"), name + ": финал с лентой");
        check(saw(name + ":gone"), name + ": автомат ушёл");
    }

    private static void prepareConfig() {
        configFile = DivineBlockMod.configFile();
        try { savedConfig = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : null; } catch (IOException failure) { savedConfig = null; }
        DivineConfig config = DivineBlockMod.config();
        config.useJev = false; config.rouletteEnabled = true; config.rouletteMode = Roulette.Mode.NORMAL; config.rouletteShow = true;
        config.rouletteVolume = 100; config.rouletteJackpotFx = true; config.cooldownSeconds = 20; config.protectBlocks = true; config.speechTimeoutSeconds = 3;
        try { config.save(configFile); DivineBlockMod.reloadConfig(); } catch (IOException failure) { fail("настройки не сохранены: " + failure); }
    }

    private static void restoreConfig() {
        try {
            if (savedConfig != null) Files.writeString(configFile, savedConfig, StandardCharsets.UTF_8);
            else Files.deleteIfExists(configFile);
            DivineBlockMod.reloadConfig();
        } catch (IOException failure) { log("настройки не восстановлены: " + failure); }
    }

    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[самотест рулетки] {}", line);
    }
    private static void finish(MinecraftClient client) {
        phase = 4;
        synchronized (moments) { log("моменты: " + String.join(" ", moments)); }
        restoreConfig();
        String local = System.getenv("LOCALAPPDATA"), own = System.getProperty("divineblock.selftest.report");
        Path dir = own != null && !own.isBlank() ? Path.of(own) : Path.of(local == null ? "." : local, "DivineBlock", "selftest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("самотест рулетки: отчёт не записан", failure); }
        client.execute(client::scheduleStop);
    }
}
