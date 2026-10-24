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
 * repentance under the roulette, a mini spin of a tail, a spin that spares by chance — a real punishment falling exactly
 * when its spin ends, and the
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
    private static volatile float chantBefore = -1, chantDuring = -1;
    private static volatile boolean answered;
    private static int waited;
    /** The steps that check a scene: they run once its machine is gone. */
    private static final java.util.Set<Integer> AFTER_SPIN = java.util.Set.of(1, 3, 5, 7, 9, 10, 14, 16, 18, 19, 20);
    private static volatile boolean spared;
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
                case "stop2" -> {
                    shotLater(scene + "-stop2", 4); shotLater(scene + "-anticipation", 16);
                    if (scene.equals("k1")) chantDuring = ChantPlayer.altarLevel();
                }
                case "777", "gates" -> shotLater(scene + "-" + name + "-flash", 2);
                case "final" -> shotLater(scene + "-final", 12);
                case "pardon" -> shotLater(scene + "-pardon", 14);
                case "mercy" -> { shotLater(scene + "-dove", 3); shotLater(scene + "-spared", 16); }
                default -> { }
            }
        };
        RouletteService.said = line -> log("  чат: " + line);
        DivineCommands.acted = line -> { log("  " + line); if (line.contains("пощажён случаем")) spared = true; };
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
                // Big screenshots: a 1600x900 window, GUI scale 3.
                client.getWindow().setWindowedSize(1600, 900);
                client.options.getGuiScale().setValue(3);
                client.onResolutionChanged();
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
        if (RouletteHud.showing() && AFTER_SPIN.contains(step)) return; // these steps wait until the machine has left
        switch (step++) {
            case 0 -> {
                chantBefore = ChantPlayer.altarLevel();
                log("  песнопение у алтаря: " + ChantPlayer.altarPiece() + ", громкость " + chantBefore);
                scene = "k1"; recording = "k1"; frame = 0; command("divineblock roulette show lightning normal"); wait = 10;
            }
            case 1 -> {
                checkScene("k1", true);
                log("  песнопение во время рулетки: громкость " + chantDuring);
                check(chantBefore <= 0 || chantDuring >= 0 && chantDuring < chantBefore * 0.4f, "песнопение у алтаря затихает под рулеткой: " + chantBefore + " → " + chantDuring);
                wait = 60;
            }
            case 2 -> {
                float after = ChantPlayer.altarLevel();
                check(chantBefore <= 0 || after > chantDuring * 2, "и возвращается после неё: " + after);
                scene = "k3tease"; command("divineblock roulette show earthquake tease"); shotAt("k3tease-crawl", Roulette.Timing.of(Roulette.Mode.NORMAL, 3).stops()[2] - 14); wait = 10;
            }
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
                answered = false;
                Prayers.performed = act -> answered = true;
                server(() -> Prayers.handle(player, altar, "Господи, благослови мой путь"));
                wait = 20 * 3;
            }
            // The god answers the first prayer when he answers (his words may take a while): the next waits for his act.
            case 11 -> { if (!answered && waited++ < 20 * 40) { step--; return; } log("  первая молитва отвечена: " + answered); wait = 20; }
            case 12 -> { command("divineblock roulette undead_horde normal"); wait = 40; }
            case 13 -> { recording = "pardon"; frame = 0; server(() -> Prayers.handle(player, altar, "Господи, прости меня грешного, каюсь")); wait = 10; }
            case 14 -> {
                check(saw("pardon:pardon"), "покаяние под рулеткой: барабаны заклинило, голуби");
                check(!saw("pardon:final"), "помилованная кара не дошла до финала");
                wait = 40;
            }
            // A real punishment: it falls exactly when its spin ends, counted in server ticks.
            case 15 -> {
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
            case 16 -> {
                check(actAt == actPlanned, "кара упала ровно через длительность рулетки: " + actAt + " = " + actPlanned);
                wait = 10;
            }
            // A tail's mini spin before the second wave.
            case 17 -> { scene = "mini"; command("divineblock roulette show raid mini"); shotAt("mini-spinning", 12); wait = 10; }
            case 18 -> { check(saw("mini:stop1") && saw("mini:final"), "мини-рулетка продолжения: один барабан"); wait = 10; }
            // Another sinner's spin as the others see it.
            // Spared by chance: the third reel slips past the match onto a dove, the punishment does not fall.
            case 19 -> { scene = "mercy"; recording = "mercy"; frame = 0; spared = false; command("divineblock roulette lightning mercy"); wait = 10; }
            case 20 -> {
                check(saw("mercy:stop3") && saw("mercy:mercy"), "случайное помилование: третий барабан встал на голубя");
                check(spared, "пощажён случаем: кара не упала");
                wait = 10;
            }
            case 21 -> { client.setScreen(new WrathSettingsScreen(null, DivineBlockMod.config())); wait = 30; }
            case 22 -> { shotLater("f9-settings", 1); wait = 10; }
            case 23 -> { client.setScreen(null); log("ошибок всего: " + failures); finish(client); }
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
        config.rouletteMercyChance = 0; // only the mercy scene spares, the others must fall
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
