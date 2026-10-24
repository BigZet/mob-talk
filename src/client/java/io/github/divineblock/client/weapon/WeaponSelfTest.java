package io.github.divineblock.client.weapon;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.boss.BossKind;
import io.github.divineblock.boss.DivineBosses;
import io.github.divineblock.boss.SacredBoss;
import io.github.divineblock.client.DivineSettingsScreen;
import io.github.divineblock.client.WeaponSettingsScreen;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.game.Scheduler;
import io.github.divineblock.weapon.CherubSwordItem;
import io.github.divineblock.weapon.DivineWeapons;
import io.github.divineblock.weapon.GideonJarItem;
import io.github.divineblock.weapon.JerichoHornItem;
import io.github.divineblock.weapon.SlingStoneEntity;
import io.github.divineblock.weapon.ThrownSpearEntity;
import io.github.divineblock.weapon.WeaponKind;
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
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
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

/** The unattended weapons test, on with -Ddivineblock.weapontest=true: a fresh world and a flat stage, then every weapon in
 * the hand (first and third person) and on an armour stand from three sides, with screenshots; then each weapon's rule:
 * David's stone in Goliath's forehead opens his weak point, the cherub's turning sword burns a zombie, the archistrategos'
 * blows on the unclean, the spear's threefold strike on Leviathan and its return, Nimrod's arrow in a cow, the jawbone in
 * a crowd, Gideon's rout, the war sword's unrest among villagers, the trumpets bringing a cobblestone wall down (and not
 * an obsidian one), the stake on a zombie, and the «Оружие» page of F9. Report:
 * %LOCALAPPDATA%\DivineBlock\weapontest\report.txt; screenshots weapon-*.png in the game's screenshots folder. */
public final class WeaponSelfTest {
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures;
    private static volatile String screenshot;
    private static volatile boolean finished;
    private static BlockPos base;

    private WeaponSelfTest() { }

    public static void register() {
        if (!Boolean.getBoolean("divineblock.weapontest")) return;
        log("тест оружия включён");
        ClientTickEvents.END_CLIENT_TICK.register(WeaponSelfTest::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        if (screenshot != null) {
            String name = "weapon-" + screenshot + ".png";
            screenshot = null;
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), text -> log("  скриншот " + name));
        }
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
                String name = "divineblock-weapontest";
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.NORMAL, true, rules, DataConfiguration.SAFE_MODE);
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
                    server.execute(() -> begin(server, server.getPlayerManager().getPlayer(uuid)));
                    phase = 2;
                } else if (ticks > 20 * 240) { fail("мир не открылся"); finish(client); }
            }
            case 2 -> {
                if (finished) { phase = 4; ticks = 0; }
                else if (ticks > 20 * 900) { fail("тест оружия не уложился в 15 минут"); finish(client); }
            }
            case 4 -> {
                if (ticks == 1) client.setScreen(new DivineSettingsScreen(null));
                if (ticks == 20 && client.currentScreen instanceof DivineSettingsScreen parent) client.setScreen(new WeaponSettingsScreen(parent, DivineBlockMod.config()));
                if (ticks == 50) { check(client.currentScreen instanceof WeaponSettingsScreen, "страница «Оружие» открылась из F9"); screenshot = "settings"; }
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
        world.setTimeOfDay(6000);
        arena(world, player);
        // Every weapon on: the war sword is off by default.
        DivineConfig config = DivineBlockMod.config();
        config.disabledWeapons.clear();
        config.jerichoBreaksBlocks = true; config.protectBlocks = false;
        try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException failure) { fail("настройки не сохранены: " + failure); }
        List<Step> steps = new ArrayList<>();
        for (WeaponKind kind : WeaponKind.values()) showcase(steps, player, kind);
        rules(steps, server, player);
        steps.add(new Step(20, () -> { log("ошибок: " + failures); finished = true; }));
        run(steps, 0);
    }

    private static void arena(ServerWorld world, ServerPlayerEntity player) {
        base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, player.getBlockPos()).up(1);
        for (BlockPos pos : BlockPos.iterate(base.add(-20, -1, -20), base.add(20, 16, 20)))
            world.setBlockState(pos, pos.getY() == base.getY() - 1 ? Blocks.POLISHED_DEEPSLATE.getDefaultState() : Blocks.AIR.getDefaultState(), 2);
        for (BlockPos pos : BlockPos.iterate(base.add(-20, -6, -20), base.add(20, -2, 20)))
            if (world.getBlockState(pos).isAir() || !world.getFluidState(pos).isEmpty()) world.setBlockState(pos, Blocks.STONE.getDefaultState(), 2);
        home(player);
        log("арена в " + base.toShortString());
    }

    // ---- The showcase: each weapon in the hand and on a stand. ----

    private static void showcase(List<Step> steps, ServerPlayerEntity player, WeaponKind kind) {
        ArmorStandEntity[] stand = new ArmorStandEntity[1];
        steps.add(new Step(20, () -> {
            ServerWorld world = player.getServerWorld();
            clear(world);
            player.changeGameMode(GameMode.CREATIVE);
            home(player);
            player.getInventory().clear();
            player.getInventory().setStack(0, new ItemStack(DivineWeapons.item(kind)));
            player.getInventory().setStack(1, new ItemStack(DivineWeapons.SLING_STONE, 16));
            player.getInventory().selectedSlot = 0;
            player.setPitch(10f);
            MinecraftClient.getInstance().execute(() -> { MinecraftClient.getInstance().options.setPerspective(Perspective.FIRST_PERSON); MinecraftClient.getInstance().options.hudHidden = false; });
        }));
        steps.add(new Step(30, () -> screenshot = "showcase-" + kind.id + "-1-hand"));
        steps.add(new Step(5, () -> MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().options.setPerspective(Perspective.THIRD_PERSON_FRONT))));
        steps.add(new Step(25, () -> screenshot = "showcase-" + kind.id + "-2-third"));
        steps.add(new Step(5, () -> {
            ServerWorld world = player.getServerWorld();
            ArmorStandEntity s = EntityType.ARMOR_STAND.create(world);
            if (s == null) { fail("нет стойки для " + kind.id); return; }
            s.refreshPositionAndAngles(base.getX() + 0.5, base.getY(), base.getZ() - 6.5, 0f, 0f);
            NbtCompound nbt = new NbtCompound();
            s.writeNbt(nbt);
            nbt.putBoolean("ShowArms", true);
            s.readNbt(nbt);
            s.equipStack(EquipmentSlot.MAINHAND, new ItemStack(DivineWeapons.item(kind)));
            world.spawnEntity(s);
            stand[0] = s;
            player.changeGameMode(GameMode.SPECTATOR);
            MinecraftClient.getInstance().execute(() -> { MinecraftClient.getInstance().options.setPerspective(Perspective.FIRST_PERSON); MinecraftClient.getInstance().options.hudHidden = true; });
        }));
        // The stand faces +Z (yaw 0): its right hand with the weapon is toward -X.
        String[][] views = { { "front", "0", "4.0", "1.0" }, { "side", "90", "3.5", "1.0" }, { "hand", "40", "2.2", "0.9" } };
        for (String[] view : views) {
            steps.add(new Step(12, () -> { if (stand[0] != null) aim(player, stand[0], view); }));
            steps.add(new Step(18, () -> { if (stand[0] != null) screenshot = "showcase-" + kind.id + "-3-" + view[0]; }));
        }
        steps.add(new Step(5, () -> { if (stand[0] != null) stand[0].discard(); MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().options.hudHidden = false); log("витрина: " + kind.id); }));
    }

    /** The spectator camera about the stand: {angle} degrees from its front, {distance} blocks away, at {height} blocks up,
     * looking at the hand. */
    private static void aim(ServerPlayerEntity player, ArmorStandEntity stand, String[] view) {
        double angle = Math.toRadians(Double.parseDouble(view[1])), distance = Double.parseDouble(view[2]), height = Double.parseDouble(view[3]);
        double x = stand.getX() - 0.4 + Math.sin(angle) * distance, z = stand.getZ() + Math.cos(angle) * distance, y = stand.getY() + height;
        Vec3d eye = new Vec3d(x, y, z), target = new Vec3d(stand.getX() - 0.4, stand.getY() + 1.1, stand.getZ());
        Vec3d to = target.subtract(eye);
        float yaw = (float) (MathHelper.atan2(to.z, to.x) * 57.2957763671875) - 90f;
        float pitch = (float) -(MathHelper.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * 57.2957763671875);
        player.teleport(player.getServerWorld(), x, y - 1.62, z, yaw, pitch);
    }

    // ---- The rules. ----

    private static void rules(List<Step> steps, MinecraftServer server, ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        LivingEntity[] foe = new LivingEntity[1];
        float[] before = new float[1];
        // 1. David's sling: a stone in Goliath's forehead.
        steps.add(new Step(20, () -> {
            log("--- праща Давида ---");
            clear(world); home(player); player.changeGameMode(GameMode.SURVIVAL);
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().options.setPerspective(Perspective.FIRST_PERSON));
            foe[0] = DivineBosses.spawn(BossKind.GOLIATH, world, base.add(0, 0, -8), 3, null);
            if (foe[0] instanceof MobEntity mob) mob.setAiDisabled(true);
            check(foe[0] instanceof SacredBoss, "Голиаф встал");
        }));
        steps.add(new Step(30, () -> {
            if (!(foe[0] instanceof SacredBoss goliath)) return;
            SlingStoneEntity stone = new SlingStoneEntity(world, player, new ItemStack(DivineWeapons.SLING_STONE), 1f, true);
            Vec3d from = player.getEyePos(), to = goliath.getPos().add(0, goliath.getHeight() * 0.93, 0);
            stone.setPosition(from);
            stone.setVelocity(to.subtract(from).normalize().multiply(2.5));
            world.spawnEntity(stone);
            screenshot = "rule-sling";
        }));
        steps.add(new Step(25, () -> { if (foe[0] instanceof SacredBoss goliath) check(goliath.exposed(), "камень в лоб: Голиаф на коленях (слабое место открыто)"); }));
        // 2. The cherub's turning sword.
        steps.add(new Step(10, () -> {
            log("--- пламенный меч Херувима ---");
            clear(world); home(player);
            foe[0] = zombie(world, base.add(0, 0, 4));
            before[0] = foe[0].getHealth();
            CherubSwordItem.turn(world, player);
        }));
        steps.add(new Step(30, () -> screenshot = "rule-cherub"));
        steps.add(new Step(30, () -> check(foe[0].isOnFire() || foe[0].getHealth() < before[0], "обращающийся меч жжёт зомби: " + before[0] + " → " + foe[0].getHealth() + (foe[0].isOnFire() ? ", горит" : ""))));
        // 3. The archistrategos on the unclean.
        steps.add(new Step(100, () -> {
            log("--- меч архистратига ---");
            clear(world); home(player);
            foe[0] = zombie(world, base.add(0, 0, 4));
            before[0] = foe[0].getHealth();
            ItemStack sword = new ItemStack(DivineWeapons.ARCHISTRATEGOS_SWORD);
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, sword);
            for (int i = 0; i < 5; i++) { foe[0].timeUntilRegen = 0; sword.getItem().postHit(sword, foe[0], player); }
            screenshot = "rule-archistrategos";
        }));
        steps.add(new Step(10, () -> check(foe[0].getHealth() <= before[0] - 15 || !foe[0].isAlive(), "пять ударов по нечисти: " + before[0] + " → " + foe[0].getHealth())));
        // 4. The spear in Leviathan, and back.
        steps.add(new Step(10, () -> {
            log("--- копьё Георгия ---");
            clear(world); home(player);
            player.getInventory().clear();
            foe[0] = DivineBosses.spawn(BossKind.LEVIATHAN, world, base.add(0, 0, -9), 3, null);
            if (foe[0] instanceof MobEntity mob) mob.setAiDisabled(true);
            if (foe[0] != null) {
                before[0] = foe[0].getHealth();
                ThrownSpearEntity spear = new ThrownSpearEntity(world, player, new ItemStack(DivineWeapons.GEORGE_SPEAR));
                Vec3d from = player.getEyePos(), to = foe[0].getPos().add(0, foe[0].getHeight() * 0.5, 0);
                spear.setPosition(from);
                spear.setVelocity(to.subtract(from).normalize().multiply(2.6));
                world.spawnEntity(spear);
            }
            check(foe[0] != null, "Левиафан встал");
        }));
        steps.add(new Step(12, () -> screenshot = "rule-spear"));
        steps.add(new Step(60, () -> {
            if (foe[0] != null) check(foe[0].getHealth() < before[0], "копьё ранило змия сквозь броню: " + before[0] + " → " + foe[0].getHealth());
            check(player.getInventory().containsAny(s -> s.isOf(DivineWeapons.GEORGE_SPEAR)) || !world.getEntitiesByClass(ThrownSpearEntity.class, new Box(base).expand(32), e -> true).isEmpty(),
                    "копьё вернулось или ещё летит назад");
        }));
        // 5. Nimrod's arrow in a cow.
        steps.add(new Step(10, () -> {
            log("--- лук Нимрода ---");
            clear(world); home(player);
            CowEntity cow = EntityType.COW.create(world);
            if (cow == null) { fail("нет коровы"); return; }
            cow.refreshPositionAndAngles(base.getX() + 0.5, base.getY(), base.getZ() - 3.5, 0, 0);
            cow.setAiDisabled(true);
            world.spawnEntity(cow);
            foe[0] = cow; before[0] = cow.getHealth();
            ArrowEntity arrow = new ArrowEntity(world, player, new ItemStack(Items.ARROW), new ItemStack(DivineWeapons.NIMROD_BOW));
            Vec3d from = player.getEyePos(), to = cow.getPos().add(0, 0.7, 0);
            arrow.setPosition(from);
            arrow.setVelocity(to.subtract(from).normalize().multiply(2.5));
            world.spawnEntity(arrow);
        }));
        steps.add(new Step(25, () -> { screenshot = "rule-nimrod"; check(foe[0].getHealth() <= before[0] - 3 || !foe[0].isAlive(), "стрела Нимрода бьёт зверя вдвое: " + before[0] + " → " + foe[0].getHealth()); }));
        // 6. The jawbone in a crowd.
        steps.add(new Step(10, () -> {
            log("--- челюсть Самсона ---");
            clear(world); home(player);
            for (int i = 0; i < 5; i++) zombie(world, base.add(-2 + i, 0, 3));
            foe[0] = zombie(world, base.add(0, 0, 4));
            before[0] = foe[0].getHealth();
            ItemStack jaw = new ItemStack(DivineWeapons.SAMSON_JAWBONE);
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, jaw);
            jaw.getItem().postHit(jaw, foe[0], player);
            screenshot = "rule-jawbone";
        }));
        steps.add(new Step(10, () -> check(foe[0].getHealth() <= before[0] - 5, "челюсть в толпе из шести: " + before[0] + " → " + foe[0].getHealth())));
        // 7. Gideon's rout.
        steps.add(new Step(10, () -> {
            log("--- кувшин Гедеона ---");
            clear(world); home(player);
            foe[0] = zombie(world, base.add(-2, 0, 4));
            zombie(world, base.add(2, 0, 4));
            int routed = GideonJarItem.rout(world, player);
            check(routed == 2, "смятение на двоих: " + routed);
            screenshot = "rule-gideon";
        }));
        steps.add(new Step(30, () -> check(foe[0] instanceof MobEntity mob && mob.getTarget() instanceof ZombieEntity, "зомби в смятении идёт на зомби")));
        // 8. The war sword among villagers.
        steps.add(new Step(10, () -> {
            log("--- большой меч Войны ---");
            clear(world); home(player);
            VillagerEntity villager = EntityType.VILLAGER.create(world);
            if (villager == null) { fail("нет жителя"); return; }
            villager.refreshPositionAndAngles(base.getX() + 0.5, base.getY(), base.getZ() + 1.5, 0, 0);
            world.spawnEntity(villager);
            foe[0] = villager;
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new ItemStack(DivineWeapons.WAR_SWORD));
        }));
        steps.add(new Step(45, () -> {
            screenshot = "rule-war";
            check(foe[0] instanceof VillagerEntity v && v.getBrain().hasMemoryModule(MemoryModuleType.NEAREST_HOSTILE), "житель бежит от меча Войны");
        }));
        // 9. The trumpets and a wall.
        int[] wall = new int[2];
        steps.add(new Step(10, () -> {
            log("--- трубы Иерихона ---");
            clear(world); home(player);
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new ItemStack(DivineWeapons.JERICHO_HORN));
            // The player at home faces -Z (yaw 180): the wall three blocks north, cobblestone with an obsidian pillar.
            BlockPos feet = player.getBlockPos();
            for (int x = -3; x <= 3; x++) for (int y = 0; y < 5; y++) for (int z = 2; z <= 3; z++)
                world.setBlockState(feet.add(x, y, -z), x == 3 ? Blocks.OBSIDIAN.getDefaultState() : Blocks.COBBLESTONE.getDefaultState());
            wall[0] = count(world, feet, Blocks.COBBLESTONE); wall[1] = count(world, feet, Blocks.OBSIDIAN);
            int fell = JerichoHornItem.blast(world, player);
            check(fell > 20, "стена пала: " + fell + " блоков");
            screenshot = "rule-jericho";
        }));
        steps.add(new Step(40, () -> {
            BlockPos feet = player.getBlockPos();
            check(count(world, feet, Blocks.COBBLESTONE) < wall[0], "булыжника стало меньше: " + wall[0] + " → " + count(world, feet, Blocks.COBBLESTONE));
            check(count(world, feet, Blocks.OBSIDIAN) == wall[1], "обсидиан устоял: " + wall[1]);
        }));
        // 10. The stake on a zombie.
        steps.add(new Step(10, () -> {
            log("--- осиновый кол ---");
            clear(world); home(player);
            foe[0] = zombie(world, base.add(0, 0, 4));
            before[0] = foe[0].getHealth();
            ItemStack stake = new ItemStack(DivineWeapons.ASPEN_STAKE);
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, stake);
            stake.getItem().postHit(stake, foe[0], player);
            screenshot = "rule-stake";
        }));
        steps.add(new Step(10, () -> check(foe[0].getHealth() <= before[0] - 6 && foe[0].isOnFire(), "кол в нечисть: " + before[0] + " → " + foe[0].getHealth() + ", горит")));
        // 11. A switched-off weapon says so.
        steps.add(new Step(10, () -> {
            DivineConfig config = DivineBlockMod.config();
            config.disabledWeapons.add(WeaponKind.ASPEN_STAKE.id);
            try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException ignored) { }
            check(!DivineWeapons.enabled(WeaponKind.ASPEN_STAKE), "выключенный кол выключен");
            config.disabledWeapons.remove(WeaponKind.ASPEN_STAKE.id);
            try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); } catch (IOException ignored) { }
            clear(world);
        }));
    }

    private static int count(ServerWorld world, BlockPos feet, net.minecraft.block.Block block) {
        int n = 0;
        for (BlockPos pos : BlockPos.iterate(feet.add(-4, -1, -6), feet.add(4, 6, 0))) if (world.getBlockState(pos).isOf(block)) n++;
        return n;
    }

    private static ZombieEntity zombie(ServerWorld world, BlockPos at) {
        ZombieEntity zombie = EntityType.ZOMBIE.create(world);
        if (zombie == null) throw new IllegalStateException("нет зомби");
        zombie.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        zombie.setAiDisabled(true);
        zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // no burning in the sun
        world.spawnEntity(zombie);
        return zombie;
    }

    private static void home(ServerPlayerEntity player) {
        if (base == null) return;
        player.teleport(player.getServerWorld(), base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180f, 0f);
        player.setVelocity(0, 0, 0); player.fallDistance = 0; player.setFireTicks(0);
        player.setHealth(player.getMaxHealth());
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 3, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20 * 60, 0, false, false));
    }

    private static void clear(ServerWorld world) {
        if (base == null) return;
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, new Box(base).expand(64), m -> true)) mob.discard();
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(base).expand(48), i -> true)) item.discard();
        for (ArmorStandEntity stand : world.getEntitiesByClass(ArmorStandEntity.class, new Box(base).expand(64), s -> true)) stand.discard();
    }

    private static void run(List<Step> steps, int index) {
        if (index >= steps.size()) return;
        Step step = steps.get(index);
        Scheduler.later(Math.max(1, step.delay()), () -> { safely(step.action()); run(steps, index + 1); });
    }

    private static void safely(Runnable action) {
        try { action.run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("тест оружия", failure); }
    }
    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[тест оружия] {}", line);
    }
    private static synchronized void write() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "weapontest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("тест оружия: отчёт не записан", failure); }
    }
    private static void finish(MinecraftClient client) {
        phase = 3;
        write();
        client.execute(client::scheduleStop);
    }
}
