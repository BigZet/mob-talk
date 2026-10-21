package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.boss.BossEffects;
import io.github.divineblock.boss.BossKind;
import io.github.divineblock.boss.BossSealBlockEntity;
import io.github.divineblock.boss.DivineBosses;
import io.github.divineblock.boss.SacredBoss;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.game.Scheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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

/** The unattended boss test, on with -Ddivineblock.bosstest=true (only some bosses: -Ddivineblock.bosstest.only=id,id): a fresh
 * world with an arena (torches, glass, a pool), then every boss in turn — spawn, an ordinary attack, the ultimate from its
 * warning to its strike, its affliction on the player, a later phase, death and its relic — with screenshots of each stage.
 * Then the vanilla stand-in for a switched-off boss, a seal that wakes when the player comes near, the command and the
 * settings screen. Report: %LOCALAPPDATA%\DivineBlock\bosstest\report.txt; screenshots in the game's screenshots folder. */
public final class BossSelfTest {
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures;
    private static volatile String screenshot;
    private static volatile boolean finished, settingsShot;
    private static volatile Entity watched;
    private static BlockPos base;

    private BossSelfTest() { }

    public static void register() {
        if (!Boolean.getBoolean("divineblock.bosstest")) return;
        log("тест боссов включён");
        ClientTickEvents.END_CLIENT_TICK.register(BossSelfTest::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        if (screenshot != null) {
            String name = "boss-" + screenshot + ".png";
            screenshot = null;
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), text -> log("  скриншот " + name));
        }
        // The camera looks at the boss under test.
        Entity look = watched;
        if (look != null && client.player != null && client.currentScreen == null) {
            Vec3d to = look.getPos().add(0, look.getHeight() * 0.55, 0).subtract(client.player.getEyePos());
            client.player.setYaw((float) (MathHelper.atan2(to.z, to.x) * 57.2957763671875) - 90f);
            client.player.setPitch((float) -(MathHelper.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * 57.2957763671875));
        }
        switch (phase) {
            case 0 -> {
                // A fresh game directory first shows the accessibility onboarding: skip it.
                if (!(client.currentScreen instanceof TitleScreen)) {
                    if (ticks > 100 && client.world == null && client.getOverlay() == null) { client.options.onboardAccessibility = false; client.setScreen(new TitleScreen()); }
                    return;
                }
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                String name = "divineblock-bosstest";
                LevelInfo info = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.NORMAL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + name);
                phase = 1; ticks = 0;
                client.options.hudHidden = false;
                client.options.tutorialStep = net.minecraft.client.tutorial.TutorialStep.NONE;
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(20261021L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
            }
            case 1 -> {
                if (client.player != null && client.getServer() != null && client.player.age > 60) {
                    MinecraftServer server = client.getServer();
                    var uuid = client.player.getUuid();
                    client.options.setPerspective(Perspective.FIRST_PERSON);
                    server.execute(() -> begin(server, server.getPlayerManager().getPlayer(uuid)));
                    phase = 2;
                } else if (ticks > 20 * 240) { fail("мир не открылся"); finish(client); }
            }
            case 2 -> {
                if (finished) { phase = 4; ticks = 0; }
                else if (ticks > 20 * 1500) { fail("тест боссов не уложился в 25 минут"); finish(client); }
            }
            case 4 -> {
                if (ticks == 1) { watched = null; client.setScreen(new DivineSettingsScreen(null)); }
                if (ticks == 20 && client.currentScreen instanceof DivineSettingsScreen parent)
                    client.setScreen(new BossSettingsScreen(parent, DivineBlockMod.config()));
                if (ticks == 50) { check(client.currentScreen instanceof BossSettingsScreen, "экран «Святые стражи» открылся из F9"); screenshot = "settings"; }
                if (ticks == 70) { client.setScreen(null); log("ошибок всего: " + failures); finish(client); }
            }
            default -> { }
        }
    }

    // ---- On the server thread. ----

    private record Step(int delay, Runnable action) { }

    private static void begin(MinecraftServer server, ServerPlayerEntity player) {
        if (player == null) { fail("нет игрока на сервере"); finished = true; return; }
        ServerWorld world = player.getServerWorld();
        world.setWeather(100_000, 0, false, false);
        arena(world, player);
        DivineConfig config = DivineBlockMod.config();
        log("босс-тест: сила " + config.bossDifficulty + "%, ломать блоки " + config.bossBlockDamage);
        String only = System.getProperty("divineblock.bosstest.only", "");
        List<BossKind> kinds = Arrays.stream(BossKind.values()).filter(k -> only.isBlank() || Arrays.asList(only.split(",")).contains(k.id)).toList();
        List<Step> steps = new ArrayList<>();
        if (Boolean.getBoolean("divineblock.bosstest.god")) {
            godScene(steps, server, player);
            run(steps, 0);
            return;
        }
        if (Boolean.getBoolean("divineblock.bosstest.showcase")) {
            for (BossKind kind : kinds) showcase(steps, player, kind);
            steps.add(new Step(20, () -> { write(); finished = true; }));
            run(steps, 0);
            return;
        }
        for (BossKind kind : kinds) fight(steps, server, player, kind);
        steps.add(new Step(20, () -> { home(player); fallback(player); }));
        steps.add(new Step(60, () -> { screenshot = "fallback"; clear(player.getServerWorld()); seal(player); }));
        steps.add(new Step(80, () -> {
            var near = DivineBosses.near(player.getServerWorld(), base, 30);
            check(!near.isEmpty(), "печать стража разбудила босса, когда игрок подошёл: " + near.stream().map(b -> b.kind().id).toList());
            if (!near.isEmpty()) watched = near.get(0);
            screenshot = "seal";
        }));
        steps.add(new Step(40, () -> {
            clear(player.getServerWorld());
            watched = null;
            int done;
            try { done = server.getCommandManager().getDispatcher().execute("divineblock boss bell_ringer 2", player.getCommandSource().withLevel(4)); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException wrong) { fail("команда не разобрана: " + wrong.getMessage()); return; }
            check(done == 1 && !DivineBosses.near(player.getServerWorld(), base, 30).isEmpty(), "команда /divineblock boss bell_ringer 2 вызвала босса");
            clear(player.getServerWorld());
        }));
        steps.add(new Step(20, () -> { log("ошибок после боссов: " + failures); write(); finished = true; }));
        run(steps, 0);
    }

    /** A 41×41 stage: stone floor, open sky, torches and lanterns (for the Schema's darkness), glass panes (for the Tocsin),
     * a pool (for the Leviathan) and a doorway with a red banner above (for the Destroying Angel's mark). */
    private static void arena(ServerWorld world, ServerPlayerEntity player) {
        base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, player.getBlockPos()).up(1);
        for (BlockPos pos : BlockPos.iterate(base.add(-20, -1, -24), base.add(20, 24, 20)))
            world.setBlockState(pos, pos.getY() == base.getY() - 1 ? Blocks.POLISHED_DEEPSLATE.getDefaultState() : Blocks.AIR.getDefaultState(), 2);
        for (BlockPos pos : BlockPos.iterate(base.add(-20, -6, -24), base.add(20, -2, 20)))
            if (world.getBlockState(pos).isAir() || !world.getFluidState(pos).isEmpty()) world.setBlockState(pos, Blocks.STONE.getDefaultState(), 2);
        for (int i = -16; i <= 16; i += 8) {
            world.setBlockState(base.add(i, 0, -18), Blocks.TORCH.getDefaultState());
            world.setBlockState(base.add(-16, 0, i), Blocks.LANTERN.getDefaultState());
            world.setBlockState(base.add(16, 0, i), Blocks.GLASS.getDefaultState());
            world.setBlockState(base.add(16, 1, i), Blocks.GLASS_PANE.getDefaultState());
        }
        // The pool: 9×9, 4 deep, north of the stage.
        for (BlockPos pos : BlockPos.iterate(base.add(-4, -5, -22), base.add(4, -1, -14)))
            world.setBlockState(pos, Blocks.WATER.getDefaultState(), 2);
        // The doorway: an oak door on the west with a red banner over its lintel.
        BlockPos door = base.add(-10, 0, 6);
        world.setBlockState(door.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(door, Blocks.OAK_DOOR.getDefaultState());
        world.setBlockState(door.up(), Blocks.OAK_DOOR.getDefaultState().with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER));
        world.setBlockState(door.up(2), Blocks.RED_WOOL.getDefaultState());
        world.setBlockState(door.up(2).south(), Blocks.RED_WALL_BANNER.getDefaultState());
        home(player);
        log("арена в " + base.toShortString());
    }

    private static void fight(List<Step> steps, MinecraftServer server, ServerPlayerEntity player, BossKind kind) {
        SacredBoss[] boss = new SacredBoss[1];
        boolean water = kind == BossKind.LEVIATHAN, night = kind == BossKind.DESTROYING_ANGEL || kind == BossKind.WAILER;
        steps.add(new Step(30, () -> {
            log("--- " + kind.title + " (" + kind.id + ") ---");
            ServerWorld world = player.getServerWorld();
            clear(world);
            world.setTimeOfDay(night ? 18000 : 6000);
            home(player);
            BlockPos at = water ? base.add(0, -3, -18) : base.add(0, 0, -11);
            LivingEntity spawned = DivineBosses.spawn(kind, world, at, 3, null);
            check(spawned instanceof SacredBoss, "появился " + kind.title + (spawned == null ? ": нет места" : ": " + spawned.getType().getUntranslatedName()));
            if (spawned instanceof SacredBoss sacred) { boss[0] = sacred; sacred.setTarget(player); watched = sacred; }
        }));
        steps.add(new Step(50, () -> { if (boss[0] != null) { check(boss[0].isAlive(), kind.id + " жив и держит цель"); screenshot = kind.id + "-1-idle"; } }));
        steps.add(new Step(5, () -> { if (boss[0] != null) { refresh(player); boss[0].forceAttack(); } }));
        steps.add(new Step(12, () -> {
            if (boss[0] == null) return;
            if (boss[0].isCasting() || boss[0].hazardCount() > 0) { check(true, kind.id + ": обычная атака с предупреждением"); screenshot = kind.id + "-2-attack"; return; }
            boss[0].forceAttack();
            Scheduler.later(25, () -> { check(boss[0].isCasting() || boss[0].hazardCount() > 0, kind.id + ": обычная атака с предупреждением (со второй попытки)"); screenshot = kind.id + "-2-attack"; });
        }));
        steps.add(new Step(50, () -> {
            if (boss[0] == null) return;
            refresh(player);
            home(player);
            if (water) player.teleport(player.getServerWorld(), base.getX() + 0.5, base.getY(), base.getZ() - 12.5, 180f, 10f);
            boss[0].forceUltimate();
        }));
        steps.add(new Step(30, () -> { if (boss[0] != null) { check(boss[0].isCasting(), kind.id + ": ульта «" + kind.ultimate + "» предупреждает заранее"); screenshot = kind.id + "-3-ult-warning"; } }));
        steps.add(new Step(45, () -> { if (boss[0] != null) screenshot = kind.id + "-4-ult"; }));
        steps.add(new Step(40, () -> { if (boss[0] != null) screenshot = kind.id + "-5-ult-late"; }));
        steps.add(new Step(60, () -> {
            if (boss[0] == null) return;
            RegistryEntry<StatusEffect> affliction = affliction(kind);
            boolean afflicted = affliction == null || player.hasStatusEffect(affliction) || afflictedEver(player, affliction);
            check(afflicted, kind.id + ": эффект «" + kind.affliction + "» на игроке");
            check(player.isAlive(), kind.id + ": игрок пережил ульту (урон ограничен), здоровье " + Math.round(player.getHealth()));
            check(boss[0].isAlive(), kind.id + ": босс жив после ульты");
            boss[0].setHealth(boss[0].getMaxHealth() * 0.3f);
        }));
        steps.add(new Step(40, () -> {
            if (boss[0] == null) return;
            check(boss[0].phase() >= 2, kind.id + ": новая фаза при 30% здоровья: " + boss[0].phase());
            screenshot = kind.id + "-6-phase";
            refresh(player);
        }));
        steps.add(new Step(60, () -> {
            if (boss[0] == null) return;
            boss[0].damage(player.getServerWorld().getDamageSources().playerAttack(player), Float.MAX_VALUE / 4);
            if (boss[0].isAlive()) boss[0].kill();
        }));
        steps.add(new Step(40, () -> {
            if (boss[0] == null) return;
            var relic = DivineBosses.RELICS.get(kind);
            boolean dropped = !player.getServerWorld().getEntitiesByClass(ItemEntity.class, new Box(boss[0].getBlockPos()).expand(12), item -> item.getStack().isOf(relic)).isEmpty();
            check(dropped, kind.id + ": выпала реликвия " + relic.getName().getString());
            screenshot = kind.id + "-7-defeated";
            for (RegistryEntry<StatusEffect> effect : List.of(BossEffects.VOW_OF_SILENCE, BossEffects.RINGING, BossEffects.SCORCH, BossEffects.GAZE,
                    BossEffects.DEPTHS, BossEffects.DUST, BossEffects.STUN, BossEffects.FAMINE, BossEffects.FIRSTBORN_MARK, BossEffects.EXILE,
                    BossEffects.MARK_OF_BEAST, BossEffects.CONFUSION)) player.removeStatusEffect(effect);
            BossEffects.lift(player);
        }));
    }

    /** «Бог с булавой» (-Ddivineblock.bosstest.god=true): an armour stand where the sinner stands, the player watching as a
     * spectator from behind and below; the god comes out of the clouds to the north, strikes the stand with each of his
     * three blows, is sent back early and the sky clears. Then the storm without the figure (F9 off) and the deed by command. */
    private static void godScene(List<Step> steps, MinecraftServer server, ServerPlayerEntity player) {
        io.github.divineblock.boss.GodWrath[] wrath = new io.github.divineblock.boss.GodWrath[1];
        net.minecraft.entity.decoration.ArmorStandEntity[] stand = new net.minecraft.entity.decoration.ArmorStandEntity[1];
        steps.add(new Step(20, () -> {
            log("--- Бог с булавой ---");
            ServerWorld world = player.getServerWorld();
            clear(world);
            world.setTimeOfDay(6000);
            BlockPos spot = base.add(0, 0, -10);
            var made = net.minecraft.entity.EntityType.ARMOR_STAND.create(world);
            if (made != null) {
                made.refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 180f, 0f);
                made.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_HELMET));
                made.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_CHESTPLATE));
                made.equipStack(net.minecraft.entity.EquipmentSlot.LEGS, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_LEGGINGS));
                made.equipStack(net.minecraft.entity.EquipmentSlot.FEET, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_BOOTS));
                world.spawnEntity(made);
                stand[0] = made;
            }
            player.teleport(world, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 180f, 0f);
            io.github.divineblock.boss.GodWrath.placeNextAt(-Math.PI / 2);
            wrath[0] = DivineBosses.spawnGodAvatar(world, player, 4, 20 * 60);
            check(wrath[0] != null && wrath[0].hasFigure(), "Бог с булавой явился с фигурой");
            if (wrath[0] == null) return;
            if (stand[0] != null) wrath[0].aimAt(stand[0]);
            var avatar = wrath[0].avatar();
            check(avatar != null && !avatar.shouldSave() && !avatar.canHit() && !avatar.damage(world.getDamageSources().generic(), 100f),
                    "фигура неуязвима, не сохраняется, в неё не попасть");
            player.changeGameMode(GameMode.SPECTATOR);
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().options.hudHidden = true;
                MinecraftClient.getInstance().options.getFov().setValue(90);
            });
            watched = null;
            player.teleport(world, spot.getX() + 0.5, spot.getY() + 7, spot.getZ() + 28.5, 180f, -20f);
        }));
        steps.add(new Step(45, () -> screenshot = "god-1-gathering"));
        steps.add(new Step(40, () -> {
            screenshot = "god-2-emerged";
            check(io.github.divineblock.client.SkyTint.godAmount() > 0.5f, "небо потемнело: " + io.github.divineblock.client.SkyTint.godAmount());
        }));
        // Portraits at his height, from the front and from the side, then back behind the stand.
        steps.add(new Step(5, () -> {
            if (wrath[0] == null) return;
            Vec3d at = wrath[0].station();
            player.teleport(player.getServerWorld(), at.x, at.y + 14, at.z + 52, 180f, 2f);
        }));
        steps.add(new Step(25, () -> screenshot = "god-portrait-front"));
        steps.add(new Step(5, () -> {
            if (wrath[0] == null) return;
            Vec3d at = wrath[0].station();
            player.teleport(player.getServerWorld(), at.x + 52, at.y + 14, at.z, 90f, 2f);
        }));
        steps.add(new Step(25, () -> screenshot = "god-portrait-side"));
        steps.add(new Step(5, () -> {
            BlockPos spot = base.add(0, 0, -10);
            player.teleport(player.getServerWorld(), spot.getX() + 0.5, spot.getY() + 7, spot.getZ() + 28.5, 180f, -20f);
        }));
        steps.add(new Step(10, () -> { if (wrath[0] != null) wrath[0].strikeNow(io.github.divineblock.boss.GodAvatarEntity.OVERHEAD); }));
        steps.add(new Step(22, () -> {
            screenshot = "god-3-raised";
            if (wrath[0] != null) check("WARN".equals(wrath[0].stageName()), "булава занесена, круг на земле: " + wrath[0].stageName());
        }));
        steps.add(new Step(16, () -> screenshot = "god-4-strike"));
        steps.add(new Step(20, () -> {
            if (wrath[0] == null) return;
            check(wrath[0].strikes() >= 1, "удар булавы упал: " + wrath[0].strikes());
            check(stand[0] == null || stand[0].isAlive(), "стойка цела (её не бьют)");
        }));
        steps.add(new Step(30, () -> { if (wrath[0] != null) wrath[0].strikeNow(io.github.divineblock.boss.GodAvatarEntity.SWEEP); }));
        steps.add(new Step(24, () -> screenshot = "god-5-sweep-raised"));
        steps.add(new Step(16, () -> screenshot = "god-6-sweep"));
        steps.add(new Step(40, () -> { if (wrath[0] != null) wrath[0].strikeNow(io.github.divineblock.boss.GodAvatarEntity.LIGHTNING); }));
        steps.add(new Step(24, () -> screenshot = "god-7-lightning-raised"));
        steps.add(new Step(14, () -> screenshot = "god-8-lightning"));
        steps.add(new Step(40, () -> {
            if (wrath[0] == null) return;
            check(wrath[0].strikes() >= 3, "три вида ударов: " + wrath[0].strikes());
            check(DivineBosses.dismissGodAvatar(player), "покаяние: Бог уходит раньше срока");
        }));
        steps.add(new Step(40, () -> screenshot = "god-9-leaving"));
        steps.add(new Step(70, () -> {
            if (wrath[0] == null) return;
            check(wrath[0].finished(), "Бог ушёл в облака: " + wrath[0].stageName());
            check(player.getServerWorld().getEntitiesByClass(io.github.divineblock.boss.GodAvatarEntity.class, new Box(base).expand(128), e -> true).isEmpty(), "фигуры больше нет в мире");
        }));
        steps.add(new Step(60, () -> {
            check(io.github.divineblock.client.SkyTint.godAmount() < 0.2f, "небо прояснилось: " + io.github.divineblock.client.SkyTint.godAmount());
            screenshot = "god-10-clear";
            // F9 off: the storm without the figure.
            DivineConfig config = DivineBlockMod.config();
            config.disabledBosses.add(DivineBosses.GOD_AVATAR_ID);
            try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException failure) { fail("настройки не сохранены: " + failure); }
            wrath[0] = DivineBosses.spawnGodAvatar(player.getServerWorld(), player, 3, 20 * 10);
            check(wrath[0] != null && !wrath[0].hasFigure(), "выключенный Бог с булавой приходит грозой без фигуры");
            if (wrath[0] != null && stand[0] != null) { wrath[0].aimAt(stand[0]); wrath[0].strikeNow(io.github.divineblock.boss.GodAvatarEntity.LIGHTNING); }
        }));
        steps.add(new Step(200, () -> {
            if (wrath[0] != null) check(wrath[0].strikes() >= 1, "гроза ударила без фигуры: " + wrath[0].strikes());
            screenshot = "god-11-storm";
            DivineConfig config = DivineBlockMod.config();
            config.disabledBosses.remove(DivineBosses.GOD_AVATAR_ID);
            try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException ignored) { }
            DivineBosses.dismissGodAvatar(player);
        }));
        steps.add(new Step(100, () -> {
            int done;
            try { done = server.getCommandManager().getDispatcher().execute("divineblock act god_mace 4", player.getCommandSource().withLevel(4)); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException wrong) { fail("команда не разобрана: " + wrong.getMessage()); return; }
            check(done == 1 && DivineBosses.dismissGodAvatar(player), "деяние /divineblock act god_mace 4 вызвало Бога с булавой");
        }));
        steps.add(new Step(100, () -> { log("ошибок после Бога с булавой: " + failures); write(); finished = true; }));
    }

    /** A posed portrait for scale: the boss stands still (no AI) beside an armoured stand of a player's size, the player
     * watches as a spectator with the HUD hidden, once from the front and once from the side. */
    private static void showcase(List<Step> steps, ServerPlayerEntity player, BossKind kind) {
        SacredBoss[] boss = new SacredBoss[1];
        steps.add(new Step(20, () -> {
            ServerWorld world = player.getServerWorld();
            clear(world);
            for (var stand : world.getEntitiesByClass(net.minecraft.entity.decoration.ArmorStandEntity.class, new Box(base).expand(64), s -> true)) stand.discard();
            world.setTimeOfDay(kind == BossKind.DESTROYING_ANGEL ? 14000 : 6000);
            BlockPos at = kind == BossKind.LEVIATHAN ? base.add(0, -3, -18) : base.add(0, 0, -8);
            LivingEntity spawned = DivineBosses.spawn(kind, world, at, 3, null);
            if (!(spawned instanceof SacredBoss sacred)) { fail("нет босса для портрета: " + kind.id); return; }
            boss[0] = sacred;
            sacred.setAiDisabled(true);
            sacred.refreshPositionAndAngles(sacred.getX(), sacred.getY(), sacred.getZ(), 0f, 0f);
            sacred.setHeadYaw(0f); sacred.setBodyYaw(0f);
            var stand = net.minecraft.entity.EntityType.ARMOR_STAND.create(world);
            if (stand != null) {
                stand.refreshPositionAndAngles(sacred.getX() + sacred.getWidth() / 2 + 2, base.getY(), sacred.getZ() + 1, 0f, 0f);
                stand.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_HELMET));
                stand.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_CHESTPLATE));
                stand.equipStack(net.minecraft.entity.EquipmentSlot.LEGS, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_LEGGINGS));
                stand.equipStack(net.minecraft.entity.EquipmentSlot.FEET, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_BOOTS));
                world.spawnEntity(stand);
            }
            player.changeGameMode(GameMode.SPECTATOR);
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().options.hudHidden = true);
            watched = null;
            double distance = sacred.getHeight() * 0.8 + 5;
            player.teleport(world, sacred.getX() + 1, sacred.getY() + sacred.getHeight() * 0.4, sacred.getZ() + distance, 180f, -4f);
        }));
        steps.add(new Step(40, () -> { if (boss[0] != null) screenshot = "showcase-" + kind.id + "-front"; }));
        steps.add(new Step(10, () -> {
            if (boss[0] == null) return;
            double distance = boss[0].getHeight() * 0.8 + 5;
            player.teleport(player.getServerWorld(), boss[0].getX() + distance, boss[0].getY() + boss[0].getHeight() * 0.4, boss[0].getZ() + 1, 90f, -4f);
        }));
        steps.add(new Step(30, () -> { if (boss[0] != null) screenshot = "showcase-" + kind.id + "-side"; }));
        steps.add(new Step(10, () -> log("портрет: " + kind.id)));
    }

    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();
    /** Afflictions may be short: remember any seen on the player while a fight runs. */
    private static boolean afflictedEver(ServerPlayerEntity player, RegistryEntry<StatusEffect> effect) {
        return SEEN.contains(effect.getIdAsString());
    }

    private static RegistryEntry<StatusEffect> affliction(BossKind kind) {
        return switch (kind) {
            case BLACK_SCHEMNIK -> BossEffects.VOW_OF_SILENCE; case BELL_RINGER -> BossEffects.RINGING; case SERAPH, ARCHANGEL -> BossEffects.SCORCH;
            case FACELESS_ICON -> BossEffects.GAZE; case WAILER -> BossEffects.PANIKHIDA; case LEVIATHAN -> BossEffects.DEPTHS; case BEHEMOTH -> BossEffects.DUST;
            case GOLIATH -> BossEffects.STUN; case HORSEMEN -> null; case DESTROYING_ANGEL -> BossEffects.FIRSTBORN_MARK; case CHERUB -> BossEffects.EXILE;
            case BEAST -> BossEffects.MARK_OF_BEAST; case NIMROD -> BossEffects.CONFUSION;
        };
    }

    /** A switched-off boss comes as a named, buffed vanilla mob. */
    private static void fallback(ServerPlayerEntity player) {
        DivineConfig config = DivineBlockMod.config();
        config.disabledBosses.add(BossKind.BLACK_SCHEMNIK.id);
        try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException failure) { fail("настройки не сохранены: " + failure); return; }
        LivingEntity standIn = DivineBosses.spawn(BossKind.BLACK_SCHEMNIK, player.getServerWorld(), base.add(0, 0, -9), 3, player);
        check(standIn instanceof MobEntity && !(standIn instanceof SacredBoss) && standIn.hasCustomName() && standIn.getMaxHealth() > 40,
                "выключенный босс пришёл заменой: " + (standIn == null ? "ничего" : standIn.getType().getUntranslatedName() + ", здоровье " + standIn.getMaxHealth()));
        watched = standIn;
        config.disabledBosses.remove(BossKind.BLACK_SCHEMNIK.id);
        try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException ignored) { }
    }

    /** A seal of the cemetery at night, eight blocks away: the player is already near. */
    private static void seal(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.setTimeOfDay(18000);
        BlockPos at = base.add(0, -1, 1);
        world.setBlockState(at, DivineBosses.SEAL.getDefaultState());
        check(BossSealBlockEntity.configure(world, at, "cemetery", 2, true, 1), "печать стража настроена на погост ночью");
    }

    private static void refresh(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 3, false, false));
        for (StatusEffectInstance effect : player.getStatusEffects()) SEEN.add(effect.getEffectType().getIdAsString());
    }

    private static void home(ServerPlayerEntity player) {
        if (base == null) return;
        player.teleport(player.getServerWorld(), base.getX() + 0.5, base.getY(), base.getZ() + 6.5, 180f, 0f);
        player.setVelocity(0, 0, 0); player.fallDistance = 0; player.setFireTicks(0);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 3, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20 * 60, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 20 * 60, 0, false, false));
    }

    private static void clear(ServerWorld world) {
        if (base == null) return;
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, new Box(base).expand(64), m -> true)) mob.discard();
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(base).expand(48), i -> true)) item.discard();
    }

    private static void run(List<Step> steps, int index) {
        if (index >= steps.size()) return;
        Step step = steps.get(index);
        Scheduler.later(Math.max(1, step.delay()), () -> {
            // Afflictions are noted every step, so a short one is not missed.
            MinecraftServer server = MinecraftClient.getInstance().getServer();
            if (server != null) for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
                for (StatusEffectInstance effect : p.getStatusEffects()) SEEN.add(effect.getEffectType().getIdAsString());
            safely(step.action());
            run(steps, index + 1);
        });
    }

    private static void safely(Runnable action) {
        try { action.run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("тест боссов", failure); }
    }
    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[тест боссов] {}", line);
    }
    private static synchronized void write() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "bosstest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("тест боссов: отчёт не записан", failure); }
    }
    private static void finish(MinecraftClient client) {
        phase = 3;
        write();
        client.execute(client::scheduleStop);
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
