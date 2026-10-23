package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodLedger;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.Verdict;
import io.github.divineblock.game.Banishment;
import io.github.divineblock.game.Curses;
import io.github.divineblock.game.DivineCommands;
import io.github.divineblock.game.DivineState;
import io.github.divineblock.game.Scheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/** The unattended test of the cruel punishments, on with -Ddivineblock.selftest=cruel: a fresh world, each punishment of
 * groups К1–К5 performed through {@code /divineblock act} on its own stage (a house, a tower, a farm, a pond, cattle and a
 * villager), photographed, then the eternal ban in this test world — the sentence, the refusal at the gate — and every way
 * back: the unban file, the command, the launch flag and the switch. The real config is restored at the end.
 * Report: %LOCALAPPDATA%\DivineBlock\selftest\cruel-report.txt; screenshots divineblock-cruel-*.png. */
public final class CruelTest {
    static final String WORLD = "divineblock-cruel";
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures;
    private static volatile String screenshot;
    private static volatile boolean actsDone;
    private static String savedConfig;
    private static Path configFile;

    /** id, power, severity, and how to film it: "near" stays by the stage, "high" from above, "far" a step back. */
    private record Act(String id, int power, int severity, String camera, int[] shots) { }
    private static final List<Act> ACTS = List.of(
            act("lightning", 3, 1, "near", 30, 400), act("curse_minor", 2, 1, "near", 30), act("swarm", 3, 1, "near", 60, 380),
            act("curse_tool", 2, 1, "near", 20), act("acid_rain", 3, 1, "far", 60, 500), act("fire_ring", 3, 1, "near", 40, 140),
            act("anvil_rain", 3, 1, "far", 50), act("meteor", 4, 3, "high", 140, 600), act("earthquake", 4, 1, "far", 120, 700),
            act("sinkhole", 4, 1, "high", 60, 400), act("tnt_rain", 4, 1, "high", 120, 320), act("tornado", 4, 2, "far", 200, 600),
            act("blight", 4, 1, "high", 100, 600), act("undead_horde", 4, 1, "far", 60), act("raid", 4, 1, "far", 60),
            act("skeleton_riders", 4, 3, "far", 80), act("blaze_horde", 4, 1, "far", 60), act("warden", 4, 1, "far", 80),
            act("cain", 4, 1, "near", 80), act("sacred_boss", 4, 2, "far", 80), act("wither", 4, 1, "far", 80),
            act("dragon", 4, 1, "high", 120), act("great_crater", 4, 1, "high", 300, 900), act("rifts", 4, 2, "high", 200, 1200),
            act("chunk_void", 4, 1, "high", 200, 900), act("meteor_shower", 4, 2, "high", 200, 600), act("plagues", 4, 3, "far", 60, 400, 900, 1400, 1900),
            act("sodom", 4, 2, "high", 200, 700), act("flood", 4, 1, "high", 300, 900), act("babel", 4, 1, "far", 40, 300),
            act("judgement", 4, 1, "near", 200, 330), act("memory_wipe", 4, 1, "near", 60), act("hell_exile", 4, 1, "near", 60),
            act("end_exile", 4, 1, "near", 60));

    private static Act act(String id, int power, int severity, String camera, int... shots) { return new Act(id, power, severity, camera, shots); }

    private CruelTest() { }

    public static void register() {
        if (!"cruel".equals(System.getProperty("divineblock.selftest"))) return;
        log("самотест жестоких кар включён");
        DivineCommands.acted = line -> log("  act " + line);
        ClientTickEvents.END_CLIENT_TICK.register(CruelTest::tick);
    }

    // ---- The client: the world, the screenshots, the ban's screens. ----

    private static void tick(MinecraftClient client) {
        ticks++;
        if (screenshot != null) {
            String name = "divineblock-cruel-" + screenshot + ".png";
            screenshot = null;
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), text -> log("  скриншот " + name));
        }
        switch (phase) {
            case 0 -> {
                // A fresh game directory opens on the accessibility onboarding first: step past it to the title.
                if (client.world == null && client.currentScreen != null && !(client.currentScreen instanceof TitleScreen) && ticks > 100
                        && client.getOverlay() == null) { client.options.onboardAccessibility = false; client.setScreen(new TitleScreen()); }
                if (!(client.currentScreen instanceof TitleScreen)) return;
                prepareConfig();
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                rules.get(GameRules.DO_IMMEDIATE_RESPAWN).set(true, null);
                LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.NORMAL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + WORLD);
                phase = 1; ticks = 0;
                client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(20261021L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (client.player != null && client.getServer() != null && client.player.age > 80) {
                    client.options.hudHidden = true;
                    MinecraftServer server = client.getServer();
                    var uuid = client.player.getUuid();
                    server.execute(() -> acts(server, server.getPlayerManager().getPlayer(uuid)));
                    phase = 2; ticks = 0;
                } else if (ticks > 20 * 300) { fail("мир не открылся"); finish(client); }
            }
            case 2 -> {
                if (actsDone) { phase = Boolean.getBoolean("divineblock.cruel.skipban") ? 40 : 10; ticks = 0; }
                else if (ticks > 20 * 60 * 45) { fail("кары не уложились в 45 минут"); finish(client); }
            }
            // The settings screen of the punishments.
            case 10 -> {
                if (ticks == 2) { client.options.hudHidden = false; client.setScreen(new WrathSettingsScreen(null, DivineBlockMod.config())); }
                if (ticks == 30) screenshot = "settings-wrath";
                if (ticks == 40) { client.setScreen(null); phase = 20; ticks = 0; }
            }
            // The ban: the god speaks the sentence, the world is copied, the mortal is thrown out.
            case 20 -> {
                if (ticks == 1) { MinecraftServer server = client.getServer(); var uuid = client.player.getUuid(); server.execute(() -> ban(server, server.getPlayerManager().getPlayer(uuid))); }
                if (client.currentScreen instanceof DisconnectedScreen) { phase = 21; ticks = 0; }
                else if (ticks > 20 * 120) { fail("изгнание не выкинуло из мира"); phase = 30; ticks = 0; }
            }
            case 21 -> {
                if (ticks == 40) {
                    String shown = screenText(client);
                    check(shown.contains("ПРИГОВОР"), "экран приговора: " + shown.replace('\n', ' '));
                    screenshot = "ban-sentence";
                }
                if (ticks == 60) check(backups().size() == 1, "копия мира перед изгнанием: " + backups());
                if (ticks == 100) { log("снова вхожу в мир"); reopen(client); phase = 22; ticks = 0; }
            }
            case 22 -> refusedThen(client, "ban-refused", 23);
            // Way back 1: the unban file.
            case 23 -> {
                if (ticks == 1) {
                    write(DivineBlockMod.configDirectory().resolve("divineblock-unban.txt"), "снять изгнание");
                    log("файл divineblock-unban.txt положен, вхожу снова");
                    reopen(client); phase = 24; ticks = 0;
                }
            }
            case 24 -> admittedThen(client, "unban-file", () -> {
                check(!Files.exists(DivineBlockMod.configDirectory().resolve("divineblock-unban.txt")), "файл снятия использован и удалён");
                MinecraftServer server = client.getServer();
                server.execute(() -> {
                    check(DivineState.of(server).banned.isEmpty(), "изгнание снято файлом");
                    // Way back 2: the command (an operator, or a copy of the world).
                    fakeBan(server, client.player.getUuid());
                    server.getCommandManager().executeWithPrefix(server.getCommandSource(), "divineblock unban");
                    check(DivineState.of(server).banned.isEmpty(), "изгнание снято командой /divineblock unban");
                    // Way back 3 is tested next: a ban stays and the launch flag lifts it.
                    fakeBan(server, client.player.getUuid());
                });
            }, 25);
            case 25 -> {
                if (ticks == 1) { System.setProperty("divineblock.unban", "1"); quit(client); }
                if (ticks == 60) { log("вхожу с -Ddivineblock.unban=1"); reopen(client); phase = 26; ticks = 0; }
            }
            case 26 -> admittedThen(client, "unban-flag", () -> {
                System.clearProperty("divineblock.unban");
                MinecraftServer server = client.getServer();
                server.execute(() -> {
                    check(DivineState.of(server).banned.isEmpty(), "изгнание снято ключом запуска");
                    // Way back 4: the switch. A ban stays in the world, but with the ban switched off the gate is open.
                    fakeBan(server, client.player.getUuid());
                });
            }, 27);
            case 27 -> {
                if (ticks == 1) { setBan(false); quit(client); }
                if (ticks == 60) { log("вхожу с выключенным изгнанием"); reopen(client); phase = 28; ticks = 0; }
            }
            case 28 -> admittedThen(client, "unban-switch", () -> log("ok с выключенным изгнанием мир впускает"), 30);
            case 30 -> { if (ticks == 20) { log("ошибок всего: " + failures); finish(client); } }
            // -Ddivineblock.cruel.skipban=true: after all the acts the player just leaves; the world must close and save.
            case 40 -> {
                if (ticks == 1) { log("выхожу из мира после всех кар"); long t = System.currentTimeMillis(); quit(client); check(true, "мир закрылся и сохранился за " + (System.currentTimeMillis() - t) / 1000 + " с"); }
                if (ticks == 40) { phase = 30; ticks = 0; }
            }
            default -> { }
        }
    }

    /** Waits for the world to refuse the banished one with the curse on the screen. */
    private static void refusedThen(MinecraftClient client, String shot, int next) {
        if (client.currentScreen instanceof DisconnectedScreen && ticks > 20) {
            if (ticks % 1000 == 0) return;
            String shown = screenText(client);
            check(shown.contains("Врата закрыты"), "мир не впустил изгнанного: " + shown.replace('\n', ' '));
            screenshot = shot;
            phase = 100 + next; ticks = 0; // A short pause for the screenshot, then the next phase.
        } else if (client.player != null && ticks > 20 * 30) { fail("мир впустил изгнанного"); phase = 30; ticks = 0; }
        else if (ticks > 20 * 120) { fail("мир не открылся и не отказал"); phase = 30; ticks = 0; }
    }

    /** Waits for the world to let the player in, photographs him there, runs {@code then}, and goes on. */
    private static void admittedThen(MinecraftClient client, String shot, Runnable then, int next) {
        if (client.currentScreen instanceof DisconnectedScreen) { fail(shot + ": мир не впустил: " + screenText(client).replace('\n', ' ')); phase = 30; ticks = 0; return; }
        if (client.player != null && client.player.age == 60) { check(true, shot + ": мир впустил"); client.options.hudHidden = false; screenshot = shot; }
        if (client.player != null && client.player.age == 80) { then.run(); phase = next; ticks = 0; }
        if (ticks > 20 * 180) { fail(shot + ": мир не открылся"); phase = 30; ticks = 0; }
    }

    static {
        // Phases 1xx: a pause of two seconds after a refusal, then phase xx.
        ClientTickEvents.END_CLIENT_TICK.register(client -> { if (phase >= 100 && ticks >= 40) { phase -= 100; ticks = 0; } });
    }

    private static void reopen(MinecraftClient client) {
        client.setScreen(new TitleScreen());
        client.createIntegratedServerLoader().start(WORLD, () -> { fail("вход в мир отменён"); });
    }

    private static void quit(MinecraftClient client) {
        if (client.world != null) client.world.disconnect();
        client.disconnect();
        client.setScreen(new TitleScreen());
    }

    private static String screenText(MinecraftClient client) {
        if (client.currentScreen == null) return "";
        StringBuilder out = new StringBuilder(client.currentScreen.getTitle().getString()).append(' ');
        if (client.currentScreen instanceof DisconnectedScreen) out.append(client.currentScreen.getNarratedTitle().getString());
        try {
            for (var field : DisconnectedScreen.class.getDeclaredFields())
                if (net.minecraft.text.Text.class.isAssignableFrom(field.getType())) { field.setAccessible(true); out.append(' ').append(((net.minecraft.text.Text) field.get(client.currentScreen)).getString()); }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return out.toString();
    }

    // ---- The server: the stages and the acts. ----

    private static void acts(MinecraftServer server, ServerPlayerEntity player) {
        if (player == null) { fail("нет игрока"); actsDone = true; return; }
        // -Ddivineblock.cruel.skipacts=true: straight to the ban (to check it, and the shutdown after it, in a clean world).
        if (Boolean.getBoolean("divineblock.cruel.skipacts")) { log("--- кары пропущены ---"); actsDone = true; return; }
        ServerWorld world = server.getOverworld();
        world.setTimeOfDay(6000);
        world.setWeather(100_000, 0, false, false);
        // -Ddivineblock.cruel.only=meteor,flood,...: just these acts.
        String only = System.getProperty("divineblock.cruel.only", "");
        List<Act> acts = only.isBlank() ? ACTS : ACTS.stream().filter(a -> java.util.Arrays.asList(only.split(",")).contains(a.id())).toList();
        List<Runnable> steps = new ArrayList<>();
        int[] delays = new int[acts.size()];
        for (int i = 0; i < acts.size(); i++) {
            Act act = acts.get(i);
            BlockPos origin = new BlockPos(400 * (i + 1), 0, 300 * (i % 3));
            steps.add(() -> stage(server, player, origin, act));
            delays[i] = 140 + act.shots()[act.shots().length - 1] + 60;
        }
        chain(steps, delays, 0, () -> { log("--- все кары показаны ---"); actsDone = true; });
    }

    private static void chain(List<Runnable> steps, int[] delays, int index, Runnable done) {
        if (index >= steps.size()) { done.run(); return; }
        try { steps.get(index).run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("самотест кар", failure); }
        Scheduler.later(delays[index], () -> chain(steps, delays, index + 1, done));
    }

    /** A fresh stage in the overworld, then (after the chunks load) the act and its photographs. */
    private static void stage(MinecraftServer server, ServerPlayerEntity player, BlockPos origin, Act act) {
        log("--- " + act.id() + " ---");
        ServerWorld world = server.getOverworld();
        world.getChunk(origin.getX() >> 4, origin.getZ() >> 4);
        BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin);
        player.teleport(world, ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5, 0, 10);
        player.changeGameMode(GameMode.CREATIVE);
        player.getAbilities().flying = false; player.sendAbilitiesUpdate();
        player.clearStatusEffects();
        player.setHealth(player.getMaxHealth());
        world.setTimeOfDay(6000);
        world.setWeather(100_000, 0, false, false);
        Scheduler.later(100, () -> {
            BlockPos base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin);
            build(world, base);
            for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, new Box(base).expand(64), m -> true)) mob.discard();
            for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(base).expand(64), i -> true)) item.discard();
            populate(world, base);
            player.teleport(world, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 15);
            player.setVelocity(0, 0, 0); player.fallDistance = 0;
            stock(player);
            Scheduler.later(30, () -> perform(server, world, player, base, act));
        });
    }

    private static void perform(MinecraftServer server, ServerWorld world, ServerPlayerEntity player, BlockPos base, Act act) {
        if (act.id().equals("memory_wipe")) check(player.getInventory().main.stream().anyMatch(s -> !s.isEmpty()), "перед стиранием памяти в сумке есть вещи");
        int before = Curses.of(server, player.getUuid()).size();
        server.getCommandManager().executeWithPrefix(player.getCommandSource().withLevel(4).withSilent(),
                "divineblock act " + act.id() + " " + act.power() + " " + act.severity());
        // Film it.
        camera(world, player, base, act.camera());
        for (int i = 0; i < act.shots().length; i++) {
            int shot = i;
            Scheduler.later(act.shots()[i], () -> screenshot = act.id() + "-" + (char) ('a' + shot));
        }
        int last = act.shots()[act.shots().length - 1];
        Scheduler.later(Math.min(last, 40) + 5, () -> verify(server, world, player, base, act, before));
        // Exiles and wipes take him away: bring him back afterwards, and lift every curse so the next stage starts clean.
        Scheduler.later(last + 30, () -> {
            Curses.lift(server, player.getUuid());
            GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());
            soul.forgiven();
            if (player.getServerWorld() != world) player.teleport(world, base.getX() + 0.5, base.getY() + 2, base.getZ() + 0.5, 0, 10);
            player.clearStatusEffects();
        });
    }

    /** What must be true just after the act, beyond its own words. */
    private static void verify(MinecraftServer server, ServerWorld world, ServerPlayerEntity player, BlockPos base, Act act, int cursesBefore) {
        switch (act.id()) {
            case "blight", "cain" -> check(Curses.of(server, player.getUuid()).size() > cursesBefore, act.id() + ": проклятие легло");
            case "plagues" -> Scheduler.later(1400, () -> check(Curses.has(server, player.getUuid(), Curses.Type.DARKNESS) || Curses.has(server, player.getUuid(), Curses.Type.BLOOD),
                    "казни: кровь и тьма легли"));
            case "flood" -> check(Curses.has(server, player.getUuid(), Curses.Type.FLOOD), "потоп: проклятие потопа легло");
            case "memory_wipe" -> {
                check(player.getInventory().main.stream().allMatch(ItemStack::isEmpty), "стирание памяти: сумка пуста");
                check(player.getPos().distanceTo(base.toCenterPos()) > 3000, "стирание памяти: заброшен далеко, " + Math.round(player.getPos().distanceTo(base.toCenterPos())));
                boolean chest = false;
                for (BlockPos pos : BlockPos.iterate(base.north(3).add(-3, -6, -3), base.north(3).add(3, 2, 3))) if (world.getBlockState(pos).isOf(Blocks.CHEST)) chest = true;
                check(chest, "стирание памяти: запечатанный сундук у алтаря");
            }
            case "hell_exile" -> check(player.getServerWorld().getRegistryKey() == World.NETHER, "изгнан в Ад");
            case "end_exile" -> check(player.getServerWorld().getRegistryKey() == World.END, "изгнан в Край");
            case "sacred_boss" -> check(!world.getEntitiesByClass(MobEntity.class, new Box(base).expand(24), m -> m.hasCustomName() || m.getType() != EntityType.VILLAGER).isEmpty(),
                    "священный босс (или схимник) пришёл");
            default -> { }
        }
    }

    /** A little homestead: a plank house with glass, a stone tower, a wheat field, a pond, an altar. */
    private static void build(ServerWorld world, BlockPos base) {
        // Flatten 18 blocks around and keep the sky clear above the stage.
        for (BlockPos pos : BlockPos.iterate(base.add(-18, 0, -18), base.add(18, 24, 18))) if (!world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.AIR.getDefaultState());
        for (BlockPos pos : BlockPos.iterate(base.add(-18, -1, -18), base.add(18, -1, 18))) world.setBlockState(pos, Blocks.GRASS_BLOCK.getDefaultState());
        for (BlockPos pos : BlockPos.iterate(base.add(-18, -4, -18), base.add(18, -2, 18))) if (world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.DIRT.getDefaultState());
        // The house, east.
        BlockPos house = base.add(7, 0, -3);
        for (BlockPos pos : BlockPos.iterate(house, house.add(6, 4, 6))) {
            int x = pos.getX() - house.getX(), y = pos.getY() - house.getY(), z = pos.getZ() - house.getZ();
            boolean wall = x == 0 || x == 6 || z == 0 || z == 6;
            if (y == 4) world.setBlockState(pos, Blocks.SPRUCE_PLANKS.getDefaultState());
            else if (wall) world.setBlockState(pos, y == 2 && (x == 3 || z == 3) ? Blocks.GLASS.getDefaultState() : (x == 0 || x == 6) && (z == 0 || z == 6) ? Blocks.OAK_LOG.getDefaultState() : Blocks.OAK_PLANKS.getDefaultState());
        }
        world.setBlockState(house.add(3, 0, 0), Blocks.AIR.getDefaultState()); world.setBlockState(house.add(3, 1, 0), Blocks.AIR.getDefaultState());
        world.setBlockState(house.add(2, 1, 2), Blocks.LANTERN.getDefaultState());
        // The tower, west.
        BlockPos tower = base.add(-10, 0, -4);
        for (BlockPos pos : BlockPos.iterate(tower, tower.add(3, 14, 3)))
            if (pos.getX() == tower.getX() || pos.getX() == tower.getX() + 3 || pos.getZ() == tower.getZ() || pos.getZ() == tower.getZ() + 3)
                world.setBlockState(pos, (pos.getY() - tower.getY()) % 5 == 4 ? Blocks.CHISELED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState());
        // The field, south-east.
        for (int dx = 4; dx <= 10; dx++) for (int dz = 6; dz <= 10; dz++) {
            BlockPos soil = base.add(dx, -1, dz);
            if (dx == 7) { world.setBlockState(soil, Blocks.WATER.getDefaultState()); continue; }
            world.setBlockState(soil, Blocks.FARMLAND.getDefaultState());
            world.setBlockState(soil.up(), ((CropBlock) Blocks.WHEAT).withAge(5));
        }
        // The pond, south-west.
        for (int dx = -10; dx <= -4; dx++) for (int dz = 5; dz <= 10; dz++) {
            world.setBlockState(base.add(dx, -1, dz), Blocks.WATER.getDefaultState());
            world.setBlockState(base.add(dx, -2, dz), Blocks.WATER.getDefaultState());
        }
        // Flowers and tall grass in the open.
        for (int n = 0; n < 30; n++) {
            BlockPos at = base.add(world.random.nextInt(31) - 15, 0, world.random.nextInt(31) - 15);
            if (world.getBlockState(at).isAir() && world.getBlockState(at.down()).isOf(Blocks.GRASS_BLOCK))
                world.setBlockState(at, (n % 3 == 0 ? Blocks.POPPY : n % 3 == 1 ? Blocks.DANDELION : Blocks.SHORT_GRASS).getDefaultState());
        }
        world.setBlockState(base.north(3), DivineBlockMod.ALTAR.getDefaultState());
        // A bed inside, for the acts that look for the home (the stage does not set it as the spawn point).
        BlockPos bed = house.add(4, 0, 4);
        world.setBlockState(bed, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT));
        world.setBlockState(bed.north(), Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD));
    }

    /** Cattle, sheep and a villager on the stage. */
    private static void populate(ServerWorld world, BlockPos base) {
        EntityType<?>[] beasts = { EntityType.COW, EntityType.COW, EntityType.SHEEP, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN, EntityType.VILLAGER, EntityType.WOLF };
        for (int i = 0; i < beasts.length; i++) {
            if (!(beasts[i].create(world) instanceof MobEntity mob)) continue;
            double angle = Math.PI * 2 * i / beasts.length;
            mob.refreshPositionAndAngles(base.getX() + 0.5 + Math.cos(angle) * 6, base.getY(), base.getZ() + 0.5 + Math.sin(angle) * 6, 0, 0);
            mob.setPersistent();
            world.spawnEntity(mob);
        }
    }

    /** Things in his hands and bags, for the curses on tools and the wiping of memory. */
    private static void stock(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.getInventory().insertStack(new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().insertStack(new ItemStack(Items.IRON_PICKAXE));
        player.getInventory().insertStack(new ItemStack(Items.COOKED_BEEF, 16));
        player.getInventory().insertStack(new ItemStack(Items.BREAD, 8));
        player.getInventory().insertStack(new ItemStack(Items.FILLED_MAP));
        player.getInventory().insertStack(new ItemStack(Items.DIAMOND, 12));
        player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.currentScreenHandler.sendContentUpdates();
    }

    /** Where to stand for the photographs: by the stage, a step back, or high above it. */
    private static void camera(ServerWorld world, ServerPlayerEntity player, BlockPos base, String how) {
        switch (how) {
            case "far" -> Scheduler.later(4, () -> { if (player.getServerWorld() == world) { fly(player); player.teleport(world, base.getX() + 0.5, base.getY() + 7, base.getZ() + 22.5, 180, 20); } });
            case "high" -> Scheduler.later(4, () -> { if (player.getServerWorld() == world) { fly(player); player.teleport(world, base.getX() - 30.5, base.getY() + 38, base.getZ() + 30.5, -135, 38); } });
            default -> { }
        }
    }
    private static void fly(ServerPlayerEntity player) {
        player.getAbilities().flying = true; player.sendAbilitiesUpdate();
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 120, 4, false, false));
    }

    // ---- The ban. ----

    private static void ban(MinecraftServer server, ServerPlayerEntity player) {
        ServerWorld world = server.getOverworld();
        BlockPos origin = new BlockPos(-600, 0, -600);
        world.getChunk(origin.getX() >> 4, origin.getZ() >> 4);
        BlockPos base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin);
        build(world, base);
        player.teleport(world, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180, 0);
        player.changeGameMode(GameMode.SURVIVAL);
        player.getAbilities().flying = false; player.sendAbilitiesUpdate();
        // The god's memory of this mortal: three blasphemies before, and the words of his last verdict.
        GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());
        Verdict verdict = new Verdict();
        verdict.inclination = Miracle.Kind.WRATH; verdict.fury = true; verdict.blasphemiesBefore = 3; verdict.miracle = Miracle.ETERNAL_BAN.id();
        verdict.reasons.add(new Verdict.Reason("брань и хула на бога — бог в бешенстве", -10, 3));
        verdict.speech = "Четырежды ты плевал в моё небо, смертный. Мене, мене, текел, упарсин: исчислен, взвешен и разделён. Ступай вон из мира моего и не возвращайся.";
        soul.remember(verdict);
        DivineState.of(server).markDirty();
        log("изгнание: приговор в тестовом мире");
        server.getCommandManager().executeWithPrefix(player.getCommandSource().withLevel(4).withSilent(), "divineblock act eternal_ban 4 5");
        // The mortal hanging in judgement, the title over him, before he is cast out (real time: the save may stall ticks).
        new Thread(() -> { try { Thread.sleep(3000); } catch (InterruptedException ignored) { } screenshot = "ban-judgement"; }, "cruel-shot").start();
    }

    private static void fakeBan(MinecraftServer server, java.util.UUID player) {
        Banishment.Ban ban = new Banishment.Ban();
        ban.name = "Player"; ban.reason = "хула на бога (проверка)";
        DivineState.of(server).banned.put(player.toString(), ban);
        DivineState.of(server).markDirty();
    }

    private static List<String> backups() {
        List<String> out = new ArrayList<>();
        try (var list = Files.list(MinecraftClient.getInstance().runDirectory.toPath().resolve("saves"))) {
            list.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(WORLD + "-before-ban-")).forEach(out::add);
        } catch (IOException failure) { log("  saves не читаются: " + failure); }
        return out;
    }

    // ---- Config: the ban is switched on only for this test, and the player's settings come back at the end. ----

    private static void prepareConfig() {
        configFile = DivineBlockMod.configFile();
        try { savedConfig = Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : null; } catch (IOException failure) { savedConfig = null; }
        DivineConfig config = DivineBlockMod.config();
        config.allowWither = true; config.allowDragon = true; config.maxTier = 4; config.protectBlocks = false;
        config.allowEternalBan = true; config.banBackup = true; config.lingeringCurses = true; config.curseMinutes = 20;
        save(config);
    }
    private static void setBan(boolean on) { DivineConfig config = DivineBlockMod.config(); config.allowEternalBan = on; save(config); }
    private static void save(DivineConfig config) {
        try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException failure) { fail("настройки не сохранены: " + failure); }
    }
    private static void restoreConfig() {
        try {
            if (savedConfig != null) Files.writeString(configFile, savedConfig, StandardCharsets.UTF_8);
            else Files.deleteIfExists(configFile);
            DivineBlockMod.reloadConfig();
            log("настройки игрока восстановлены");
        } catch (IOException failure) { fail("настройки не восстановлены: " + failure); }
    }

    // ---- Report. ----

    private static void write(Path file, String text) {
        try { Files.writeString(file, text, StandardCharsets.UTF_8); } catch (IOException failure) { fail("файл не записан: " + file); }
    }
    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[самотест кар] {}", line);
        writeReport();
    }
    private static synchronized void writeReport() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "selftest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("cruel-report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("самотест кар: отчёт не записан", failure); }
    }
    private static void finish(MinecraftClient client) {
        phase = 99;
        restoreConfig();
        System.clearProperty("divineblock.unban");
        writeReport();
        client.execute(client::scheduleStop);
    }
}
