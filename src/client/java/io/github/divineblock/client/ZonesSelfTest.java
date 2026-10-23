package io.github.divineblock.client;

import com.mojang.datafixers.util.Pair;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.boss.GodAvatarEntity;
import io.github.divineblock.boss.SacredBoss;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.MiracleGroup;
import io.github.divineblock.game.ChurchArea;
import io.github.divineblock.game.ChurchGuard;
import io.github.divineblock.game.Protection;
import io.github.divineblock.game.Scheduler;
import io.github.divineblock.game.SkyCourt;
import io.github.divineblock.game.Zones;
import io.github.divineblock.world.ChurchSites;
import io.github.divineblock.world.ChurchWorldgen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.level.LevelInfo;

/** The test of where punishments fall, on with -Ddivineblock.selftest=zones: no voice and no LLM, the acts are called
 * directly. A fresh world with structures; a church is found; the player stands inside and: a small curse falls in the church,
 * a boss casts him out into the yard, the Archangel and the god with the mace take him to the Sky Court and back, a great
 * crater takes him home (a hut with a bed far away), the blight shows the way home, a sinkhole without a home takes him into
 * open land; an explosion in the nave and a direct change leave the church whole; the F9 switch is shown. Every church block
 * is compared before and after. Report: zones-report.txt; screenshots divineblock-zones-*.png. */
public final class ZonesSelfTest {
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures, waited;
    private static volatile String screenshot;
    private static volatile boolean finished, hud = true;
    private static volatile Runnable clientAction;
    private record Later(int at, Runnable action) { }
    private static final List<Later> clientQueue = new ArrayList<>();
    private static void clientLater(int delay, Runnable action) { clientQueue.add(new Later(ticks + delay, action)); }
    private static DivineConfig saved;

    private ZonesSelfTest() { }

    public static void register() {
        if (!"zones".equals(System.getProperty("divineblock.selftest"))) return;
        log("самотест мест кар включён");
        Zones.decided = line -> log("  место: " + line);
        ClientTickEvents.END_CLIENT_TICK.register(ZonesSelfTest::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        client.options.hudHidden = !hud;
        Runnable action = clientAction;
        if (action != null) { clientAction = null; action.run(); }
        for (Later later : new ArrayList<>(clientQueue)) if (later.at() <= ticks) { clientQueue.remove(later); later.action().run(); }
        if (screenshot != null && client.world != null && !client.worldRenderer.isTerrainRenderComplete() && waited++ < 200) return;
        if (screenshot != null) {
            waited = 0;
            String name = "divineblock-zones-" + screenshot + ".png";
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
                String name = "divineblock-zonestest";
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.NORMAL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + name);
                phase = 1; ticks = 0;
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(20261021L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (client.player != null && client.getServer() != null && client.player.age > 60) {
                    MinecraftServer server = client.getServer();
                    var uuid = client.player.getUuid();
                    server.execute(() -> begin(server, server.getPlayerManager().getPlayer(uuid)));
                    phase = 2;
                } else if (ticks > 20 * 240) { fail("мир не открылся"); finish(client); }
            }
            case 2 -> {
                if (finished) { phase = 3; client.execute(() -> { write(); client.scheduleStop(); }); }
                else if (ticks > 20 * 1200) { fail("самотест не уложился в 20 минут"); phase = 3; write(); client.execute(client::scheduleStop); }
            }
            default -> { }
        }
    }

    // ---- On the server thread. ----

    private record Step(int delay, Runnable action) { }

    private static ChurchSites.Site site;
    private static ServerWorld world;
    private static ServerPlayerEntity player;
    private static Block[] before;
    private static BlockPos bed;

    private static void begin(MinecraftServer server, ServerPlayerEntity who) {
        if (who == null) { fail("нет игрока"); finished = true; return; }
        player = who; world = who.getServerWorld();
        world.setWeather(100_000, 0, false, false);
        world.setTimeOfDay(5000);
        DivineConfig config = DivineBlockMod.config();
        saved = config.copy();
        config.protectBlocks = false; config.landscapeWrath = true; config.bossBlockDamage = true; config.protectRadius = 16;
        site = locate(world, player.getBlockPos());
        if (site == null) site = locate(world, player.getBlockPos().add(4000, 0, 0));
        if (site == null) { fail("церковь не найдена"); finished = true; return; }
        ChurchArea.remember(world, site);
        before = snapshot();
        log("церковь " + site.variant() + " у " + site.origin().toShortString() + ", бокс " + site.church() + ", блоков в боксе " + before.length);

        List<Step> steps = new ArrayList<>();
        // 1. A small curse inside.
        steps.add(new Step(10, ZonesSelfTest::enter));
        steps.add(new Step(60, () -> screenshot = "1-inside-before"));
        steps.add(new Step(20, () -> {
            String done = Zones.perform(Miracle.CURSE_MINOR, 2, player, site.altar(), "", DivineBlockMod.config(), 1);
            log("малое проклятие: " + done);
            check(done != null && done.contains("свечи"), "в храме малое проклятие гасит свечи и слепит: " + done);
        }));
        steps.add(new Step(10, () -> {
            check(ChurchArea.inside(site, player.getBlockPos()), "после малого проклятия грешник остаётся в храме");
            check(player.hasStatusEffect(StatusEffects.BLINDNESS) && player.hasStatusEffect(StatusEffects.WEAKNESS), "слепота и слабость на грешнике");
            screenshot = "1-inside-curse";
        }));
        steps.add(new Step(40, () -> {
            player.clearStatusEffects();
            String done = Zones.perform(Miracle.LIGHTNING, 2, player, site.altar(), "", DivineBlockMod.config(), 1);
            check(done != null && done.contains("порог"), "молния в храме бьёт в порог: " + done);
        }));
        steps.add(new Step(8, () -> { player.clearStatusEffects(); screenshot = "1-inside-lightning"; }));
        // 2. A boss: cast out into the yard.
        steps.add(new Step(40, () -> {
            player.clearStatusEffects();
            String done = Zones.perform(Miracle.BOSS_GOLIATH, 3, player, site.altar(), "", DivineBlockMod.config(), 2);
            check(done != null && done.contains("изгнан"), "босс: «Изыди из храма»: " + done);
        }));
        steps.add(new Step(30, () -> screenshot = "2-cast-out-title"));
        steps.add(new Step(20 * 6, () -> {
            check(!ChurchArea.inside(site, player.getBlockPos()), "грешник вышвырнут из храма: " + player.getBlockPos().toShortString()
                    + ", до церкви " + Math.round(ChurchArea.distance(site.church(), player.getBlockPos())) + " бл.");
            List<Entity> bosses = world.getEntitiesByClass(Entity.class, player.getBoundingBox().expand(40), e -> e instanceof SacredBoss && e.isAlive());
            check(!bosses.isEmpty(), "босс встал во дворе: " + bosses.stream().map(e -> e.getBlockPos().toShortString()).toList());
            for (Entity boss : bosses) check(!site.church().contains(boss.getBlockPos()), "босс не в стенах храма");
            if (!bosses.isEmpty()) lookAt(bosses.get(0).getPos().add(0, 2.5, 0));
        }));
        steps.add(new Step(30, () -> screenshot = "2-yard-boss"));
        steps.add(new Step(10, () -> {
            Vec3d at = player.getPos();
            player.changeGameMode(GameMode.SPECTATOR); hud = false;
            look(at.add(-14, 9, -14), at.add(0, 1, 0));
        }));
        steps.add(new Step(60, () -> screenshot = "2-yard-wide"));
        steps.add(new Step(10, () -> { clearFoes(); hud = true; player.changeGameMode(GameMode.CREATIVE); }));
        // 3. The Sky Court: the Archangel, defeated, then back to the church.
        steps.add(new Step(20, ZonesSelfTest::enter));
        steps.add(new Step(20, () -> {
            String done = Zones.perform(Miracle.BOSS_ARCHANGEL, 4, player, site.altar(), "", DivineBlockMod.config(), 3);
            check(done != null && done.contains("небесный суд"), "Архангел — на небесный суд: " + done);
        }));
        steps.add(new Step(20, () -> screenshot = "3-sky-summon-title"));
        steps.add(new Step(150, () -> {
            BlockPos center = SkyCourt.center(world.getServer());
            check(SkyCourt.inCourt(player) && Math.abs(player.getY() - center.getY() - 1) < 3, "грешник на небесном суде: " + player.getBlockPos().toShortString() + ", центр " + center.toShortString());
            List<Entity> foes = SkyCourt.foes(world, center);
            check(!foes.isEmpty(), "противник на арене: " + foes.stream().map(e -> e.getType().getUntranslatedName()).toList());
            check(world.getBlockState(center.down()).isOf(Blocks.BARRIER) && world.getBlockState(center).isOf(Blocks.GOLD_BLOCK), "под полом арены барьер, в сердце золото");
            if (!foes.isEmpty()) lookAt(foes.get(0).getPos().add(0, 2, 0));
        }));
        steps.add(new Step(30, () -> screenshot = "3-sky-archangel"));
        steps.add(new Step(10, () -> { for (Entity foe : SkyCourt.foes(world, SkyCourt.center(world.getServer()))) foe.kill(); log("  противник повержен (kill)"); }));
        steps.add(new Step(40, () -> screenshot = "3-sky-victory-title"));
        steps.add(new Step(160, () -> {
            check(!SkyCourt.inCourt(player) && ChurchArea.inside(site, player.getBlockPos()), "после победы грешник возвращён в храм: " + player.getBlockPos().toShortString());
            BlockPos center = SkyCourt.center(world.getServer());
            player.changeGameMode(GameMode.SPECTATOR); hud = false;
            look(Vec3d.ofCenter(center).add(52, 30, 52), Vec3d.ofCenter(center));
        }));
        steps.add(new Step(100, () -> screenshot = "3-sky-wide"));
        steps.add(new Step(10, () -> { BlockPos c = SkyCourt.center(world.getServer()); look(Vec3d.ofCenter(c).add(70, -4, 8), Vec3d.ofCenter(c).add(0, -2, 0)); }));
        steps.add(new Step(60, () -> screenshot = "3-sky-side"));
        steps.add(new Step(10, () -> { BlockPos c = SkyCourt.center(world.getServer()); look(Vec3d.ofCenter(c).add(4, 3.2, 18), Vec3d.ofCenter(c).add(0, 1, -10)); }));
        steps.add(new Step(60, () -> screenshot = "3-sky-floor"));
        steps.add(new Step(10, () -> { BlockPos c = SkyCourt.center(world.getServer()); look(Vec3d.ofCenter(c).add(0, 70, 1), Vec3d.ofCenter(c)); }));
        steps.add(new Step(60, () -> screenshot = "3-sky-top"));
        steps.add(new Step(10, () -> { world.setTimeOfDay(18000); BlockPos c = SkyCourt.center(world.getServer()); look(Vec3d.ofCenter(c).add(30, 12, 30), Vec3d.ofCenter(c)); }));
        steps.add(new Step(60, () -> screenshot = "3-sky-night"));
        steps.add(new Step(10, () -> { world.setTimeOfDay(5000); hud = true; player.changeGameMode(GameMode.CREATIVE); }));
        // 3b. The god with the mace at the court; he leaves by himself and the mortal comes back.
        steps.add(new Step(10, ZonesSelfTest::enter));
        steps.add(new Step(10, () -> check(Zones.perform(Miracle.GOD_MACE, 4, player, site.altar(), "", DivineBlockMod.config(), 1) != null, "Бог с булавой — на небесный суд")));
        steps.add(new Step(220, () -> {
            check(SkyCourt.inCourt(player), "с Богом с булавой грешник на небесном суде");
            List<Entity> god = world.getEntitiesByClass(Entity.class, player.getBoundingBox().expand(90), e -> e instanceof GodAvatarEntity);
            if (!god.isEmpty()) lookAt(god.get(0).getPos().add(0, 22, 0)); else log("  фигуры бога нет (выключена?)");
        }));
        steps.add(new Step(30, () -> screenshot = "3-sky-godmace"));
        steps.add(new Step(20 * 32, () -> check(!SkyCourt.inCourt(player) && ChurchArea.inside(site, player.getBlockPos()),
                "Бог ушёл — грешник снова в храме: " + player.getBlockPos().toShortString())));
        // 4. A great crater: home.
        steps.add(new Step(10, () -> { bed = hut(); player.setSpawnPoint(World.OVERWORLD, bed, 0f, true, false); log("дом с кроватью у " + bed.toShortString()); enter(); }));
        steps.add(new Step(10, () -> {
            String done = Zones.perform(Miracle.GREAT_CRATER, 4, player, site.altar(), "", DivineBlockMod.config(), 1);
            check(done != null && done.contains("дома"), "великая воронка — у дома: " + done);
        }));
        steps.add(new Step(20, () -> screenshot = "4-home-go-title"));
        steps.add(new Step(90, () -> check(player.getBlockPos().isWithinDistance(bed, 6), "грешник перенесён домой: " + player.getBlockPos().toShortString())));
        steps.add(new Step(20, () -> {
            // Towards the deepest ground around the home: the crater.
            BlockPos deepest = bed.add(12, 0, 0); int low = Integer.MAX_VALUE;
            for (int dx = -80; dx <= 80; dx += 4) for (int dz = -80; dz <= 80; dz += 4) {
                BlockPos column = bed.add(dx, 0, dz);
                if (!world.isChunkLoaded(column.getX() >> 4, column.getZ() >> 4)) continue;
                int y = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column).getY();
                if (y < low) { low = y; deepest = column.withY(y); }
            }
            lookAt(Vec3d.ofCenter(deepest).add(0, (player.getY() - deepest.getY()) * 0.6, 0));
            screenshot = "4-home-disaster-title";
        }));
        steps.add(new Step(20 * 8, () -> {
            int air = 0;
            for (int dx = -80; dx <= 80; dx += 6) for (int dz = -80; dz <= 80; dz += 6) {
                BlockPos column = bed.add(dx, 0, dz);
                if (world.isChunkLoaded(column.getX() >> 4, column.getZ() >> 4) && world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column).getY() < bed.getY() - 8) air++;
            }
            check(air > 0, "рядом с домом воронка (колонн ниже земли на 8+: " + air + ")");
            check(world.getBlockState(bed).getBlock() instanceof BedBlock, "сам дом с кроватью цел (защита дома)");
            player.changeGameMode(GameMode.SPECTATOR); hud = false;
            look(Vec3d.ofCenter(bed).add(-40, 35, -40), Vec3d.ofCenter(bed));
        }));
        steps.add(new Step(80, () -> screenshot = "4-home-wide"));
        steps.add(new Step(10, () -> { hud = true; player.changeGameMode(GameMode.CREATIVE); }));
        // 5. The blight lingers around the home: the mortal is shown the way.
        steps.add(new Step(10, ZonesSelfTest::enter));
        steps.add(new Step(10, () -> {
            String done = Zones.perform(Miracle.BLIGHT, 3, player, site.altar(), "", DivineBlockMod.config(), 1);
            check(done != null && done.contains("путь"), "порча — у дома, путь указан: " + done);
        }));
        steps.add(new Step(30, () -> screenshot = "5-blight-title"));
        steps.add(new Step(90, () -> {
            check(ChurchArea.inside(site, player.getBlockPos()), "при порче грешник остаётся в храме");
            String hint = Zones.hint(player.getX(), player.getZ(), player.getYaw(), bed, "порча земли");
            log("  подсказка: " + hint);
            screenshot = "5-direction-hint";
        }));
        steps.add(new Step(40, () -> { player.setYaw(player.getYaw() + 120); player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.getYaw() + 120, 0); }));
        steps.add(new Step(30, () -> screenshot = "5-direction-hint-turned"));
        // 6. No home: open land 60–100 blocks out.
        steps.add(new Step(20, () -> { player.setSpawnPoint(World.OVERWORLD, null, 0f, false, false); enter(); }));
        steps.add(new Step(10, () -> {
            String done = Zones.perform(Miracle.SINKHOLE, 3, player, site.altar(), "", DivineBlockMod.config(), 2);
            check(done != null && done.contains("поля"), "провал без дома — в чистом поле: " + done);
        }));
        steps.add(new Step(25, () -> screenshot = "6-openland-title"));
        steps.add(new Step(110, () -> {
            double far = player.getPos().distanceTo(Vec3d.ofCenter(site.naveCenter()));
            check(far >= 50 && far <= 115 && !ChurchArea.inZone(world, player.getBlockPos()), "грешник в чистом поле в " + Math.round(far) + " бл. от церкви");
            screenshot = "6-openland-disaster";
        }));
        // 7. The church holds: an explosion in the nave, a direct change, the zone's protection.
        steps.add(new Step(20 * 8, () -> {
            enter();
            int refused = ChurchGuard.refused;
            Vec3d nave = Vec3d.ofCenter(site.naveCenter());
            world.createExplosion(null, nave.x, nave.y + 1, nave.z, 6f, World.ExplosionSourceType.TNT);
            BlockPos wall = firstSolid();
            boolean changed = wall != null && world.setBlockState(wall, Blocks.AIR.getDefaultState());
            check(wall != null && !changed, "прямая замена блока церкви отвергнута: " + (wall == null ? "нет стены" : wall.toShortString()));
            check(ChurchGuard.refused > refused, "страж церкви отверг изменений: " + (ChurchGuard.refused - refused));
            Vec3d yard = ChurchArea.yardSpot(world, site);
            check(Protection.spares(world, player, null, BlockPos.ofFloored(yard), DivineBlockMod.config()), "двор под защитой от кар, меняющих ландшафт");
            BlockPos far = BlockPos.ofFloored(nave).add(ChurchArea.ZONE_RADIUS + 60, 0, 0);
            check(!Protection.spares(world, player, null, far, DivineBlockMod.config()), "далеко от церкви защиты нет");
        }));
        steps.add(new Step(40, () -> {
            int changed = 0; List<String> first = new ArrayList<>();
            Block[] after = snapshot();
            BlockBox box = site.church();
            int i = 0;
            for (BlockPos pos : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
                if (before[i] != after[i]) { changed++; if (first.size() < 10) first.add(pos.toShortString() + " " + before[i] + "→" + after[i]); }
                i++;
            }
            check(changed == 0, "блоки церкви после всех кар не изменились: изменено " + changed + " " + first);
            screenshot = "7-church-after";
        }));
        // 8. The F9 switch, then the replacement it makes. A screen pauses the integrated server: the client drives this part.
        steps.add(new Step(20, () -> clientAction = () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.setScreen(new WrathSettingsScreen(null, DivineBlockMod.config().copy()));
            clientLater(40, () -> screenshot = "8-f9-landscape-yes");
            clientLater(70, () -> { DivineConfig off = DivineBlockMod.config().copy(); off.landscapeWrath = false; client.setScreen(new WrathSettingsScreen(null, off)); });
            clientLater(110, () -> screenshot = "8-f9-landscape-no");
            clientLater(140, () -> client.setScreen(null));
        }));
        steps.add(new Step(10, () -> {
            DivineConfig off = DivineBlockMod.config().copy(); off.landscapeWrath = false;
            List<String> k5 = MiracleGroup.K5.members.stream().filter(off::allows).map(Miracle::id).toList();
            check(k5.stream().noneMatch(id -> Miracle.byId(id).landscape()) && !k5.isEmpty(), "без ландшафта в К5 остаются: " + k5);
        }));
        steps.add(new Step(20, () -> {
            DivineConfig restore = DivineBlockMod.config();
            restore.protectBlocks = saved.protectBlocks; restore.landscapeWrath = saved.landscapeWrath; restore.bossBlockDamage = saved.bossBlockDamage;
            restore.protectRadius = saved.protectRadius;
            log("ошибок всего: " + failures); finished = true;
        }));
        run(steps, 0);
    }

    private static void enter() {
        player.changeGameMode(GameMode.CREATIVE);
        player.clearStatusEffects();
        Vec3d at = Vec3d.ofBottomCenter(site.naveCenter());
        Vec3d altar = Vec3d.ofCenter(site.altar());
        double dx = altar.x - at.x, dz = altar.z - at.z;
        player.teleport(world, at.x, at.y, at.z, (float) Math.toDegrees(Math.atan2(-dx, dz)), 5f);
    }

    private static void lookAt(Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleport(world, player.getX(), player.getY(), player.getZ(), yaw, pitch);
    }
    private static void look(Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleport(world, eye.x, eye.y - player.getStandingEyeHeight(), eye.z, yaw, pitch);
    }

    private static void clearFoes() {
        for (Entity e : world.getEntitiesByClass(Entity.class, player.getBoundingBox().expand(80), e -> e instanceof SacredBoss || e instanceof net.minecraft.entity.mob.HostileEntity)) e.discard();
    }

    /** A little log hut with a bed 170 blocks east of the church (out of the zone), on the ground. */
    private static BlockPos hut() {
        BlockPos column = site.naveCenter().add(170, 0, 30);
        world.getChunk(column);
        BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            for (int y = 0; y <= 5; y++) world.setBlockState(ground.add(dx, y, dz), Blocks.AIR.getDefaultState());
            world.setBlockState(ground.add(dx, -1, dz), Blocks.OAK_PLANKS.getDefaultState());
            world.setBlockState(ground.add(dx, 4, dz), Blocks.SPRUCE_PLANKS.getDefaultState());
            boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
            if (wall) for (int y = 0; y <= 3; y++) world.setBlockState(ground.add(dx, y, dz),
                    (y == 1 && (dx == 0 || dz == 0)) ? Blocks.GLASS_PANE.getDefaultState() : Math.abs(dx) == 3 && Math.abs(dz) == 3 ? Blocks.OAK_LOG.getDefaultState() : Blocks.OAK_PLANKS.getDefaultState());
        }
        world.setBlockState(ground.add(0, 0, -3), Blocks.AIR.getDefaultState());
        world.setBlockState(ground.add(0, 1, -3), Blocks.AIR.getDefaultState());
        BlockPos foot = ground.add(-1, 0, 1), head = foot.south();
        world.setBlockState(foot, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.SOUTH).with(BedBlock.PART, BedPart.FOOT));
        world.setBlockState(head, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.SOUTH).with(BedBlock.PART, BedPart.HEAD));
        world.setBlockState(ground.add(2, 0, 2), Blocks.CRAFTING_TABLE.getDefaultState());
        world.setBlockState(ground.add(2, 0, 1), Blocks.FURNACE.getDefaultState());
        world.setBlockState(ground.add(-2, 2, -2), Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        return head;
    }

    private static BlockPos firstSolid() {
        BlockBox box = site.church();
        for (BlockPos pos : BlockPos.iterate(box.getMinX(), box.getMinY() + 2, box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()))
            if (!world.getBlockState(pos).isAir() && world.getBlockState(pos).getHardness(world, pos) >= 0) return pos.toImmutable();
        return null;
    }

    private static Block[] snapshot() {
        BlockBox box = site.church();
        List<Block> out = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
            BlockState state = world.getBlockState(pos);
            out.add(state.getBlock());
        }
        return out.toArray(new Block[0]);
    }

    private static ChurchSites.Site locate(ServerWorld world, BlockPos start) {
        RegistryEntry<Structure> entry = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntry(ChurchWorldgen.STRUCTURE).orElse(null);
        if (entry == null) { fail("структура не зарегистрирована"); return null; }
        Pair<BlockPos, RegistryEntry<Structure>> hit = world.getChunkManager().getChunkGenerator().locateStructure(world, RegistryEntryList.of(entry), start, 100, false);
        if (hit == null) return null;
        BlockPos at = hit.getFirst();
        for (int cx = (at.getX() >> 4) - 4; cx <= (at.getX() >> 4) + 5; cx++)
            for (int cz = (at.getZ() >> 4) - 4; cz <= (at.getZ() >> 4) + 5; cz++) world.getChunk(cx, cz);
        ChurchSites.Site found = null;
        for (int y = world.getBottomY(); y < world.getTopY() && found == null; y += 4) found = ChurchSites.at(world, at.withY(y)).orElse(null);
        if (found == null) fail("у " + at.toShortString() + " нет частей церкви");
        return found;
    }

    private static void run(List<Step> steps, int index) {
        if (index >= steps.size()) return;
        Step step = steps.get(index);
        Scheduler.later(Math.max(1, step.delay()), () -> {
            try { step.action().run(); } catch (RuntimeException failure) { fail("шаг " + index + " упал: " + failure); DivineBlockMod.LOG.warn("самотест мест кар", failure); }
            run(steps, index + 1);
        });
    }

    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[самотест мест кар] {}", line);
    }
    private static synchronized void write() {
        String local = System.getenv("LOCALAPPDATA"), own = System.getProperty("divineblock.selftest.report");
        Path dir = own != null && !own.isBlank() ? Path.of(own) : Path.of(local == null ? "." : local, "DivineBlock", "selftest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("zones-report.txt"), report, StandardCharsets.UTF_8); Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("самотест мест кар: отчёт не записан", failure); }
        // -Ddivineblock.zones.shots=<folder>: the screenshots and the report also go there (the game sees the real %LOCALAPPDATA%).
        String shots = System.getProperty("divineblock.zones.shots");
        if (shots == null || shots.isBlank()) return;
        try {
            Path to = Path.of(shots);
            Files.createDirectories(to);
            Path from = MinecraftClient.getInstance().runDirectory.toPath().resolve("screenshots");
            try (var files = Files.list(from)) {
                for (Path file : files.filter(f -> f.getFileName().toString().startsWith("divineblock-zones-")).toList())
                    Files.copy(file, to.resolve(file.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(to.resolve("zones-report.txt"), report, StandardCharsets.UTF_8);
        } catch (IOException failure) { DivineBlockMod.LOG.warn("самотест мест кар: снимки не скопированы", failure); }
    }
    private static void finish(MinecraftClient client) { phase = 3; write(); client.execute(client::scheduleStop); }
}
