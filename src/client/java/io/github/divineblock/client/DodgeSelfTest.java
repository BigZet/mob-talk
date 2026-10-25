package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.boss.BossKind;
import io.github.divineblock.boss.DivineBosses;
import io.github.divineblock.boss.SeraphEntity;
import io.github.divineblock.game.Scheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/** The seraph's learned dodge in the real game, on with -Ddivineblock.dodgetest=true. The client player really draws a bow
 * and shoots at a Seraph the way the trainer's archer does (aims with lead and the arrow's drop, holds the string 22–30
 * ticks, sometimes longer as a bait), first with the dodge off, then on. Arrows that strike are counted and do no harm,
 * so the seraph lives through the round. Report: %LOCALAPPDATA%\DivineBlock\dodgetest\report.txt; screenshots
 * dodge-off-NNN.png / dodge-on-NNN.png (a short burst every 2 ticks) in the game's screenshots folder. */
public final class DodgeSelfTest {
    private static final int SHOTS = Integer.getInteger("divineblock.dodgetest.shots", 40);
    private static final List<String> report = new ArrayList<>();
    private static final Random random = new Random(20261024L);
    private static int phase, ticks, round = -1;
    private static volatile SeraphEntity seraph;
    private static volatile boolean roundLive, finished;
    private static int shots, hold, rest, frames, frameNo, eventFrames, eventNo, eventCooldown;
    private static String eventName;
    private static final int[] fired = new int[2], hits = new int[2], back = new int[2], burned = new int[2];
    private static Vec3d lastPos;
    private static BlockPos base;

    private DodgeSelfTest() { }

    public static void register() {
        if (!Boolean.getBoolean("divineblock.dodgetest")) return;
        log("тест уклонения Серафима включён");
        ClientTickEvents.END_CLIENT_TICK.register(DodgeSelfTest::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity && roundLive && round >= 0) {
                if (source.getSource() instanceof PersistentProjectileEntity) back[round]++;       // своя стрела, отбитая взмахом
                else if (source.getAttacker() instanceof SeraphEntity) burned[round]++;            // перья, огонь
                return true;
            }
            if (!(entity instanceof SeraphEntity) || !(source.getSource() instanceof PersistentProjectileEntity)) return true;
            if (roundLive && round >= 0) hits[round]++;
            return false;   // counted, harmless: the round goes on
        });
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        switch (phase) {
            case 0 -> {
                if (!(client.currentScreen instanceof TitleScreen)) {
                    if (ticks > 100 && client.world == null && client.getOverlay() == null) { client.options.onboardAccessibility = false; client.setScreen(new TitleScreen()); }
                    return;
                }
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                String name = "divineblock-dodgetest";
                LevelInfo info = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.NORMAL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + name);
                phase = 1; ticks = 0;
                client.options.hudHidden = false;
                client.options.tutorialStep = net.minecraft.client.tutorial.TutorialStep.NONE;
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(20261024L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (client.player != null && client.getServer() != null && client.player.age > 60) {
                    MinecraftServer server = client.getServer();
                    var uuid = client.player.getUuid();
                    client.options.setPerspective(Perspective.FIRST_PERSON);
                    server.execute(() -> prepare(server.getPlayerManager().getPlayer(uuid)));
                    phase = 2; ticks = 0;
                } else if (ticks > 20 * 240) { log("FAIL мир не открылся"); finish(client); }
            }
            case 2 -> shoot(client);
            default -> { }
        }
    }

    // ---- The archer, on the client. ----

    private static void shoot(MinecraftClient client) {
        if (finished) { finish(client); phase = 3; return; }
        if (ticks > 20 * 600) { log("FAIL тест не уложился в 10 минут"); finish(client); phase = 3; return; }
        var player = client.player;
        SeraphEntity server = seraph;
        if (player == null || client.world == null || server == null || !roundLive) return;
        Entity target = client.world.getEntityById(server.getId());
        if (target == null) return;
        Vec3d pos = target.getPos();
        Vec3d vel = lastPos == null ? Vec3d.ZERO : pos.subtract(lastPos);
        lastPos = pos;
        // Aim like the trainer's archer: the centre, led by the flight time, raised for the arrow's drop.
        // the pitch is chosen for the arrow as it will leave at release (a full draw), so the bow is raised with distance
        double speed = 3.0 * net.minecraft.item.BowItem.getPullProgress(Math.max(hold, 20));
        Vec3d eye = player.getEyePos();
        Vec3d aim = pos.add(0, target.getHeight() / 2, 0);
        for (int i = 0; i < 2; i++) aim = pos.add(0, target.getHeight() / 2, 0).add(vel.multiply(aim.distanceTo(eye) / speed));
        Vec3d to = aim.subtract(eye);
        double flat = Math.hypot(to.x, to.z);
        player.setYaw((float) (MathHelper.atan2(to.z, to.x) * MathHelper.DEGREES_PER_RADIAN) - 90f);
        player.setPitch((float) -(ballistic(flat, to.y, speed) * MathHelper.DEGREES_PER_RADIAN));
        if (frames > 0) {
            frames--;
            String name = "dodge-" + (round == 0 ? "off" : "on") + "-" + String.format("%03d", frameNo++) + ".png";
            if (frames % 2 == 0) ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), text -> { });
        }
        // a few frames of each dash, sweep and feather cast (dodge-on-dash-NNN.png …), at most one burst in 3 s
        if (eventCooldown > 0) eventCooldown--;
        if (eventFrames == 0 && eventCooldown == 0 && round == 1) {
            String what = server.mind().dashing() ? "dash" : server.mind().closingWings() || server.mind().deflecting() ? "sweep"
                    : server.isCasting() ? "feathers" : null;
            if (what != null) { eventName = what; eventFrames = 8; eventCooldown = 60; }
        }
        if (eventFrames > 0) {
            eventFrames--;
            if (eventFrames % 2 == 0) ScreenshotRecorder.saveScreenshot(client.runDirectory,
                    "dodge-on-" + eventName + "-" + String.format("%03d", eventNo++) + ".png", client.getFramebuffer(), text -> { });
        }
        if (ticks % 200 == 0) log("состояние: раунд " + round + ", выстрелов " + shots + ", тянет " + player.isUsingItem() + " ("
                + player.getItemUseTime() + "), стрел у клиента " + player.getInventory().count(Items.ARROW) + ", лук в руке "
                + player.getMainHandStack().isOf(Items.BOW) + ", до Серафима " + String.format("%.1f", pos.distanceTo(player.getPos())));
        if (rest > 0) { rest--; return; }
        if (!player.isUsingItem()) {
            if (shots >= SHOTS) { roundLive = false; client.getServer().execute(DodgeSelfTest::nextRound); return; }
            // Like a player: a full draw (20 ticks) and a little more, sometimes holding the shot.
            hold = 22 + random.nextInt(8) + (random.nextDouble() < 0.3 ? 10 + random.nextInt(40) : 0);
            player.getInventory().selectedSlot = 0;
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            return;
        }
        if (player.getItemUseTime() >= hold) {
            client.interactionManager.stopUsingItem(player);
            shots++;
            fired[round]++;
            rest = 2 + random.nextInt(8);
        }
    }

    /** The upward angle that carries an arrow (drag 0.99, gravity 0.05) through a point {@code flat} away and {@code dy} up. */
    private static double ballistic(double flat, double dy, double speed) {
        double lo = Math.atan2(dy, flat), hi = Math.toRadians(80);
        for (int k = 0; k < 18; k++) {
            double mid = (lo + hi) / 2, vx = Math.cos(mid) * speed, vy = Math.sin(mid) * speed, x = 0, y = 0;
            for (int t = 0; t < 120 && x < flat; t++) { x += vx; y += vy; vx *= 0.99; vy = vy * 0.99 - 0.05; }
            if (x < flat || y < dy) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    // ---- The rounds, on the server thread. ----

    private static void prepare(ServerPlayerEntity player) {
        if (player == null) { log("FAIL нет игрока"); finished = true; return; }
        DivineBlockMod.config().voice = false;   // no text to speech in the test (not saved)
        ServerWorld world = player.getServerWorld();
        world.setWeather(100_000, 0, false, false);
        world.setTimeOfDay(6000);
        base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, player.getBlockPos()).up(1);
        for (BlockPos pos : BlockPos.iterate(base.add(-32, -1, -32), base.add(32, 24, 32)))
            world.setBlockState(pos, pos.getY() == base.getY() - 1 ? Blocks.POLISHED_DEEPSLATE.getDefaultState() : Blocks.AIR.getDefaultState(), 2);
        player.getInventory().clear();
        player.getInventory().setStack(0, new ItemStack(Items.BOW));
        player.getInventory().setStack(9, new ItemStack(Items.ARROW, 64));
        player.getInventory().setStack(10, new ItemStack(Items.ARROW, 64));
        player.getInventory().selectedSlot = 0;
        player.playerScreenHandler.syncState();   // otherwise the client never sees the arrows and the bow will not draw
        log("арена в " + base.toShortString() + ", выстрелов в раунде " + SHOTS);
        nextRound();
        // the player in the middle; the seraph circles it
        player.teleport(world, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180f, 0f);
    }

    private static void nextRound() {
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
        ServerWorld world = player.getServerWorld();
        if (round >= 0) log("раунд " + (round == 0 ? "без сети" : "с сетью") + ": выстрелов " + fired[round] + ", попаданий " + hits[round]
                + String.format(" (%.0f%%)", 100.0 * hits[round] / Math.max(1, fired[round])) + ", отбито в стрелка " + back[round]
                + ", ударов Серафима по игроку " + burned[round]);
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, new Box(base).expand(64), m -> true)) mob.discard();
        for (PersistentProjectileEntity arrow : world.getEntitiesByClass(PersistentProjectileEntity.class, new Box(base).expand(64), a -> true)) arrow.discard();
        round++;
        if (round >= 2) { write(); finished = true; return; }
        DivineBlockMod.config().seraphDodge = round == 1;
        player.setHealth(player.getMaxHealth());
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 600, 4, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20 * 600, 0, false, false));
        LivingEntity spawned = DivineBosses.spawn(BossKind.SERAPH, world, base.add(0, 0, -12), 3, null);
        if (!(spawned instanceof SeraphEntity s)) { log("FAIL Серафим не появился"); write(); finished = true; return; }
        s.setTarget(player);
        seraph = s;
        shots = 0; rest = 60; lastPos = null;
        log("раунд " + (round == 0 ? "без сети (как раньше)" : "с сетью") + ": начинаю");
        Scheduler.later(20 * 8, () -> { frames = 160; frameNo = 0; });   // 8 s of frames every 2 ticks, a little into the round
        roundLive = true;
    }

    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[тест уклонения] {}", line);
    }

    private static synchronized void write() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "dodgetest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("тест уклонения: отчёт не записан", failure); }
    }

    private static void finish(MinecraftClient client) {
        write();
        client.execute(client::scheduleStop);
    }
}
