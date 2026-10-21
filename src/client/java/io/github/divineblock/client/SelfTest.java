package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodLedger;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.Righteousness;
import io.github.divineblock.core.Verdict;
import io.github.divineblock.game.DivineState;
import io.github.divineblock.game.Miracles;
import io.github.divineblock.game.PrayPayload;
import io.github.divineblock.game.Prayers;
import io.github.divineblock.game.Scheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/** The unattended in-game self-test, on with -Ddivineblock.selftest=true: makes a fresh world, places an altar, prays through
 * the real packet, kills a villager to see the deed counted, angers the god and repents, performs every act of the catalogue,
 * takes screenshots and quits. Report: %LOCALAPPDATA%\DivineBlock\selftest\report.txt; screenshots in the game's screenshots. */
public final class SelfTest {
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures;
    private static volatile String screenshot, clientPrayer, heardText, unheardText;
    private static volatile int voiced;
    private static volatile long spokenAt, performedAt;
    private static volatile float underVoice;
    private static volatile boolean finished;
    private static BlockPos altar, base;

    private SelfTest() { }

    public static void register() {
        if (!Boolean.getBoolean("divineblock.selftest")) return;
        log("самотест включён");
        Prayers.spoken = line -> log("  бог: " + line);
        GodVoicePlayer.played = seconds -> { voiced++; log(String.format(java.util.Locale.ROOT, "  голос бога звучит: %.1f с", seconds)); };
        VoicePrayer.heard = text -> { heardText = text; log("  Whisper распознал молитву: " + text); };
        ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        if (screenshot != null) {
            String name = "divineblock-" + screenshot + ".png";
            screenshot = null;
            ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), text -> log("  скриншот " + name));
        }
        if (clientPrayer != null && client.player != null) {
            String text = clientPrayer; clientPrayer = null;
            ClientPlayNetworking.send(new PrayPayload(altar, text));
            log("молитва через пакет: " + text);
        }
        switch (phase) {
            case 0 -> {
                if (!(client.currentScreen instanceof TitleScreen)) return;
                GameRules rules = new GameRules();
                rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                String name = "divineblock-selftest";
                LevelInfo info = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.NORMAL, true, rules, DataConfiguration.SAFE_MODE);
                log("создаю мир " + name);
                phase = 1; ticks = 0;
                client.createIntegratedServerLoader().createAndStart(name, info, new GeneratorOptions(20261020L, true, false), WorldPresets::createDemoOptions, new TitleScreen());
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
                if (finished) { phase = 4; ticks = 0; }
                else if (ticks > 20 * 600) { fail("самотест не уложился в 10 минут"); finish(client); }
            }
            case 4 -> screens(client);
            default -> { }
        }
    }

    /** Mob Talk's F7 menu and this mod's F9 menu open side by side without breaking each other. */
    private static void screens(MinecraftClient client) {
        switch (ticks) {
            case 1 -> {
                for (String mod : new String[] { "mobtalk", "viewerparrots", "divineblock" })
                    check(net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(mod).isPresent(), "мод загружен: " + mod
                            + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(mod).map(c -> " " + c.getMetadata().getVersion().getFriendlyString()).orElse(""));
                press(client, "key.mobtalk.settings");
            }
            case 30 -> {
                String open = client.currentScreen == null ? "нет" : client.currentScreen.getClass().getName();
                check(open.contains("mobtalk"), "меню Mob Talk (F7) открылось: " + open);
                screenshot = "mobtalk-f7";
            }
            case 40 -> { client.setScreen(null); press(client, "key.divineblock.settings"); }
            case 70 -> {
                check(client.currentScreen instanceof DivineSettingsScreen, "меню Божественного блока (F9) открылось");
                screenshot = "settings-f9";
            }
            case 80 -> {
                // The reverb and echo menu: opens from F9 and plays the preview line through the effects.
                DivineSettingsScreen settings = (DivineSettingsScreen) client.currentScreen;
                client.setScreen(new VoiceSettingsScreen(settings, DivineBlockMod.config()));
                GodVoicePlayer.played = seconds -> log(String.format(java.util.Locale.ROOT, "  пробная фраза с эффектом звучит: %.1f с", seconds));
                GodVoicePlayer.preview(client, DivineBlockMod.config(), line -> { if (!line.isBlank()) log("  прослушивание: " + line); });
            }
            case 200 -> {
                check(client.currentScreen instanceof VoiceSettingsScreen, "меню «Эхо и реверб» открылось");
                screenshot = "voice-settings";
            }
            case 220 -> client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
            case 250 -> {
                boolean button = client.currentScreen != null && net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(client.currentScreen).stream()
                        .anyMatch(b -> b.getMessage().getString().equals("Божественный блок"));
                check(button, "кнопка «Божественный блок» есть в меню Esc");
                screenshot = "pause-menu";
            }
            case 270 -> { client.setScreen(null); log("ошибок всего: " + failures); finish(client); }
            default -> { }
        }
    }
    private static void press(MinecraftClient client, String translationKey) {
        for (net.minecraft.client.option.KeyBinding key : client.options.allKeys)
            if (key.getTranslationKey().equals(translationKey)) {
                net.minecraft.client.option.KeyBinding.onKeyPressed(net.minecraft.client.util.InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey()));
                log("нажата клавиша " + key.getBoundKeyLocalizedText().getString() + " (" + translationKey + ")");
                return;
            }
        fail("нет клавиши " + translationKey);
    }

    // ---- On the server thread. ----

    private static void begin(MinecraftServer server, ServerPlayerEntity player) {
        if (player == null) { fail("нет игрока на сервере"); finished = true; return; }
        ServerWorld world = player.getServerWorld();
        world.setTimeOfDay(6000);
        world.setWeather(100_000, 0, false, false);
        // A flat stage in the open: a 15x15 floor with clear sky above, so the screenshots show the altar and the acts.
        base = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, player.getBlockPos()).up(2);
        for (BlockPos pos : BlockPos.iterate(base.add(-7, -1, -7), base.add(7, 12, 7)))
            world.setBlockState(pos, pos.getY() == base.getY() - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
        for (BlockPos pos : BlockPos.iterate(base.add(-7, -4, -7), base.add(7, -2, 7)))
            if (world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.STONE.getDefaultState());
        altar = base.north(3);
        world.setBlockState(altar, DivineBlockMod.ALTAR.getDefaultState());
        home(player);
        log("алтарь в " + altar.toShortString() + ", игрок в " + base.toShortString());
        screenshot = "altar";
        GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());

        List<Step> steps = new ArrayList<>();
        steps.add(new Step(60, () -> {
            // The altar sings quietly to whoever stands by it; every chant is a real sound of the mod.
            MinecraftClient client = MinecraftClient.getInstance();
            List<String> missing = ChantPlayer.allPieces().stream().filter(piece -> client.getSoundManager()
                    .get(net.minecraft.util.Identifier.of("divineblock", "chant." + piece)) == null).toList();
            check(missing.isEmpty(), "песнопения есть в звуках мода: " + ChantPlayer.allPieces().size() + (missing.isEmpty() ? "" : ", нет " + missing));
            check(ChantPlayer.altarPiece() != null, "у алтаря звучит песнопение: " + ChantPlayer.altarPiece());
        }));
        steps.add(new Step(40, () -> {
            VillagerEntity villager = EntityType.VILLAGER.create(world);
            villager.refreshPositionAndAngles(base.getX() + 4, base.getY(), base.getZ(), 0, 0);
            world.spawnEntity(villager);
            double before = soul.righteousness.score;
            villager.damage(world.getDamageSources().playerAttack(player), 1000f);
            double after = soul.righteousness.score;
            check(after < before && soul.righteousness.memories.stream().anyMatch(m -> m.deed == Righteousness.Deed.VILLAGER_KILLED),
                    "убийство жителя замечено: праведность " + before + " → " + after);
        }));
        steps.add(new Step(20, () -> {
            soul.righteousness.score = 70; soul.mood = 40; soul.lastPrayerTick = Long.MIN_VALUE / 2; soul.prayerTicks.clear();
            soul.righteousness.memories.clear();
            for (int i = 0; i < 3; i++) world.spawnEntity(new ItemEntity(world, altar.getX() + 0.5, altar.getY() + 1.2, altar.getZ() + 0.5, new ItemStack(Items.DIAMOND)));
            player.setHealth(6f);
            clientPrayer = "Пожалуйста, великий, исцели меня";
        }));
        steps.add(new Step(-1, () -> verdictArrived(soul, 1, "милость святому")));
        steps.add(new Step(40, () -> { screenshot = "help"; soul.lastPrayerTick = Long.MIN_VALUE / 2; Prayers.handle(player, altar, "Почему?"); }));
        steps.add(new Step(100, () -> {
            // A spoken prayer: the god's own voice says it, Whisper hears it, and it goes to the altar like a real one.
            soul.lastPrayerTick = Long.MIN_VALUE / 2; soul.prayerTicks.clear();
            MinecraftClient client = MinecraftClient.getInstance();
            java.util.concurrent.CompletableFuture<byte[]> wav = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return io.github.divineblock.core.PcmWav.encode(io.github.divineblock.core.PcmWav.from24k(
                        new io.github.divineblock.core.GodVoice(DivineBlockMod.config(), DivineBlockMod.configDirectory()).synthesize("Господи, молю тебя, исцели меня."))); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            BlockPos at = altar;
            client.execute(() -> VoicePrayer.process(client, wav, at, client.world));
        }));
        steps.add(new Step(-1, () -> {
            check(heardText != null && heardText.toLowerCase().contains("исцели"), "молитва голосом распознана: " + heardText);
            verdictArrived(soul, 2, "молитва голосом");
        }));
        steps.add(new Step(100, () -> {
            // An ordinary mortal swears at the god: real wrath, at full strength, and only after the god has spoken.
            soul.righteousness.score = 0; soul.mood = 0; soul.lastPrayerTick = Long.MIN_VALUE / 2; soul.prayerTicks.clear();
            Prayers.spoken = line -> {
                spokenAt = System.nanoTime(); log("  бог: " + line); Scheduler.later(20, () -> screenshot = "beams");
                Scheduler.later(40, () -> {
                    underVoice = ChantPlayer.musicLevel();
                    check(String.valueOf(ChantPlayer.musicPiece()).startsWith("punishment") && GodVoicePlayer.speaking(),
                            "под голос бога звучит музыка кары: " + ChantPlayer.musicPiece() + ", громкость " + underVoice);
                    check(ChantPlayer.altarPiece() == null, "песнопение алтаря уступило музыке кары");
                });
            };
            Prayers.performed = act -> {
                performedAt = System.nanoTime(); log("  свершилось: " + act);
                Scheduler.later(80, () -> check(ChantPlayer.musicLevel() > underVoice * 2,
                        "после слов бога музыка кары стала громче: " + underVoice + " → " + ChantPlayer.musicLevel()));
                Scheduler.later(140, () -> check(ChantPlayer.musicEnding(), "через 5 с после слов бога музыка кары стихает"));
            };
            spokenAt = performedAt = 0;
            Prayers.handle(player, altar, "бля, ты бесполезный бог, дай алмазы");
        }));
        steps.add(new Step(-1, () -> {
            Verdict wrath = verdictArrived(soul, 3, "гнев грешнику");
            if (wrath != null) {
                check(wrath.chosen() == Miracle.IGNORE || wrath.chosen().kind == Miracle.Kind.WRATH && wrath.power >= 3,
                        "за брань — настоящая кара: " + wrath.miracle + " ×" + wrath.power);
                check(wrath.reasons.stream().anyMatch(r -> r.text().contains("брань")), "брань названа причиной");
            }
        }));
        steps.add(new Step(800, () -> { // The god may speak for up to twenty seconds before the act falls.
            screenshot = "wrath";
            check(voiced >= 1, "голос бога прозвучал до конца: " + voiced + " раз");
            check(spokenAt > 0 && performedAt > spokenAt, "кара пала после слов бога: слова → дело через "
                    + (performedAt > 0 && spokenAt > 0 ? (performedAt - spokenAt) / 1_000_000 + " мс" : "—"));
        }));
        steps.add(new Step(80, () -> {
            // Idle talk at the altar is not a prayer: the god does not hear it, nothing is weighed or done.
            soul.lastPrayerTick = Long.MIN_VALUE / 2; soul.prayerTicks.clear();
            Prayers.unheard = text -> { unheardText = text; log("  не молитва, бог не услышал: " + text); };
            Prayers.handle(player, altar, "так, сейчас накопаю угля и поставлю факелы у входа в шахту");
        }));
        steps.add(new Step(80, () -> {
            check(unheardText != null, "болтовню у алтаря бог не счёл молитвой");
            check(soul.chronicle.size() == 3, "после болтовни нового вердикта нет: " + soul.chronicle.size());
        }));
        steps.add(new Step(120, () -> log("--- все деяния каталога ---")));
        DivineConfig config = DivineBlockMod.config();
        config.allowWither = true; config.maxTier = 4;
        for (Miracle miracle : Miracle.values()) steps.add(new Step(miracle == Miracle.METEOR || miracle == Miracle.CHICKEN_RAIN ? 90 : 30, () -> perform(player, miracle, config)));
        steps.add(new Step(40, () -> { clean(world); home(player); screenshot = "end"; }));
        steps.add(new Step(30, () -> { log("ошибок после деяний: " + failures); write(); finished = true; }));
        run(steps, 0);
    }

    private record Step(int delay, Runnable action) { }

    /** Runs the steps in order; a step with delay -1 waits (up to 20 s) for a verdict to arrive before its action. */
    private static void run(List<Step> steps, int index) {
        if (index >= steps.size()) return;
        Step step = steps.get(index);
        if (step.delay() >= 0) Scheduler.later(Math.max(1, step.delay()), () -> { safely(step.action()); run(steps, index + 1); });
        else waitVerdict(steps, index, 0);
    }
    private static int verdicts;
    private static void waitVerdict(List<Step> steps, int index, int waited) {
        Scheduler.later(10, () -> {
            MinecraftServer server = MinecraftClient.getInstance().getServer();
            int size = server == null ? 0 : DivineState.of(server).ledger.souls.values().stream().mapToInt(s -> s.chronicle.size()).sum();
            if (size > verdicts || waited > 400) { verdicts = size; safely(steps.get(index).action()); run(steps, index + 1); }
            else waitVerdict(steps, index, waited + 10);
        });
    }

    private static Verdict verdictArrived(GodLedger.Soul soul, int expected, String what) {
        Verdict last = soul.lastVerdict();
        if (!check(soul.chronicle.size() >= expected && last != null, what + ": вердикт пришёл")) return null;
        log("  " + last.breakdown().replace("\n", "\n  "));
        log("  решил: " + last.decidedBy + ", деяние " + last.miracle + " ×" + last.power + (last.warned ? ", со знамением" : ""));
        if (what.startsWith("милость")) check(last.inclination == Miracle.Kind.HELP, what + ": склонность " + last.inclination);
        if (what.startsWith("гнев")) check(last.inclination == Miracle.Kind.WRATH, what + ": склонность " + last.inclination);
        return last;
    }

    private static void perform(ServerPlayerEntity player, Miracle miracle, DivineConfig config) {
        home(player);
        ServerWorld world = player.getServerWorld();
        clean(world);
        player.clearStatusEffects();
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 4, false, false));
        player.setHealth(player.getMaxHealth() * 0.5f);
        switch (miracle) {
            case ENCHANT, CURSE_TOOL -> player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            case REPAIR -> { ItemStack sword = new ItemStack(Items.IRON_SWORD); sword.setDamage(100); player.setStackInHand(Hand.MAIN_HAND, sword); }
            case TRANSMUTE -> player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.DIAMOND, 5));
            case GROW -> {
                for (int dx = 1; dx <= 3; dx++) {
                    BlockPos soil = base.east(dx).down();
                    world.setBlockState(soil, Blocks.FARMLAND.getDefaultState());
                    world.setBlockState(soil.up(), Blocks.WHEAT.getDefaultState());
                }
            }
            case SMITE_ENEMIES -> { for (int i = 0; i < 2; i++) { var zombie = EntityType.ZOMBIE.create(world); zombie.refreshPositionAndAngles(base.getX() + 6, base.getY(), base.getZ() + i * 2, 0, 0); world.spawnEntity(zombie); } }
            default -> { }
        }
        int power = Math.max(miracle.minTier, Math.min(miracle.maxTier, 2));
        String result;
        try { result = Miracles.perform(miracle, power, player, altar, "дай алмазный меч", config); }
        catch (RuntimeException failure) { fail(miracle.id() + " ×" + power + ": исключение " + failure); return; }
        boolean optional = miracle == Miracle.SAFE_HOME || miracle == Miracle.RANDOM_TP; // These move the player; null only in odd terrain.
        if (result == null && !optional) fail(miracle.id() + " ×" + power + ": не сработало");
        else log("ok " + miracle.id() + " ×" + power + ": " + result);
        if (miracle == Miracle.WITHER) Scheduler.later(10, () -> { var withers = world.getEntitiesByType(EntityType.WITHER, w -> true); check(!withers.isEmpty(), "визер появился"); withers.forEach(w -> w.discard()); });
        if (miracle == Miracle.OMEN_LIGHT || miracle == Miracle.CHICKEN_RAIN || miracle == Miracle.FIRE_RING || miracle == Miracle.SMITE_ENEMIES || miracle == Miracle.GIFT_ITEM)
            Scheduler.later(miracle == Miracle.CHICKEN_RAIN ? 40 : 8, () -> screenshot = miracle.id());
        if (miracle == Miracle.METEOR) Scheduler.later(64, () -> screenshot = "meteor");
        if (miracle == Miracle.TORNADO) Scheduler.later(140, () -> {
            // Turn to face the funnel from a safe distance, then photograph it.
            var at = io.github.divineblock.game.Tornadoes.newest();
            if (at == null) { fail("торнадо не появилось"); return; }
            double dx = at.x - player.getX(), dz = at.z - player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 4, false, false));
            player.teleport(player.getServerWorld(), player.getX(), player.getY(), player.getZ(), yaw, -12f);
            check(true, "торнадо стоит в " + Math.round(Math.hypot(dx, dz)) + " блоках");
            Scheduler.later(5, () -> screenshot = "tornado");
        });
    }

    private static void home(ServerPlayerEntity player) {
        if (base == null) return;
        // Back to the stage in the overworld (an exile to the Nether is undone here too).
        player.teleport(player.getServer().getOverworld(), base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180f, 15f);
        player.setVelocity(0, 0, 0); player.fallDistance = 0;
        player.setFireTicks(0);
    }
    private static void clean(ServerWorld world) {
        if (base == null) return;
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, new Box(base).expand(96), m -> true)) mob.discard();
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(base).expand(32), i -> true)) item.discard();
        for (BlockPos pos : BlockPos.iterate(base.add(-8, -3, -8), base.add(8, 3, 8)))
            if (world.getBlockState(pos).isOf(Blocks.FIRE)) world.setBlockState(pos, Blocks.AIR.getDefaultState());
    }

    // ---- Report. ----

    private static void safely(Runnable action) {
        try { action.run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("самотест", failure); }
    }
    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        String stamped = LocalTime.now().withNano(0) + " " + line;
        report.add(stamped);
        DivineBlockMod.LOG.info("[самотест] {}", line);
    }
    private static synchronized void write() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "selftest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("самотест: отчёт не записан", failure); }
    }
    private static void finish(MinecraftClient client) {
        phase = 3;
        write();
        client.execute(client::scheduleStop);
    }
}
