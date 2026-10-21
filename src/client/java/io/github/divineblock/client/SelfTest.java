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
 * takes screenshots and quits. Report: %LOCALAPPDATA%\DivineBlock\selftest\report.txt (or -Ddivineblock.selftest.report=<folder>); screenshots in the game's screenshots. */
public final class SelfTest {
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures;
    private static volatile String screenshot, clientPrayer, heardText, unheardText;
    private static volatile int voiced;
    private static volatile long spokenAt, performedAt;
    private static volatile float underVoice, skyUnderVoice;
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

        // Whisper loads its model for a minute or more in a fresh copy of the instance: warm it up now, in the background,
        // so the spoken prayer later is not judged by a cold start.
        Thread warm = new Thread(() -> {
            try { WhisperHelper.ensureReady(DivineBlockMod.config(), line -> { }); whisperReady = true; log("Whisper готов"); }
            catch (Exception failure) { whisperProblem = String.valueOf(failure.getMessage()); log("Whisper не поднялся: " + whisperProblem); }
        }, "selftest-whisper");
        warm.setDaemon(true);
        warm.start();

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
            home(player); // A mercy or a jest may have carried him off; the altar hears only those within 8 blocks.
            prayed(soul);
            clientPrayer = "Пожалуйста, великий, исцели меня";
        }));
        steps.add(new Step(-1, () -> verdictArrived(soul, "милость святому")));
        steps.add(new Step(40, () -> { screenshot = "help"; soul.lastPrayerTick = Long.MIN_VALUE / 2; Prayers.explain(player, altar); }));
        steps.add(new Step(SILENCE, () -> { }));
        steps.add(new Step(WHISPER, () -> { if (!whisperReady) fail("Whisper не готов к молитве голосом: " + whisperProblem); }));
        steps.add(new Step(100, () -> {
            home(player); // A mercy or a jest may have carried him off; the altar hears only those within 8 blocks.
            prayed(soul);
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
            verdictArrived(soul, "молитва голосом");
        }));
        steps.add(new Step(SILENCE, () -> { }));
        steps.add(new Step(100, () -> {
            // An ordinary mortal swears at the god: real wrath, at full strength, and only after the god has spoken.
            soul.righteousness.score = 0; soul.mood = 0; soul.lastPrayerTick = Long.MIN_VALUE / 2; soul.prayerTicks.clear();
            // The punishment is real: the test mortal must live through it, or every later step acts on a corpse.
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 120, 4, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20 * 120, 0, false, false));
            Prayers.spoken = line -> {
                spokenAt = System.nanoTime(); log("  бог: " + line); Scheduler.later(20, () -> screenshot = "beams");
                Scheduler.later(40, () -> {
                    underVoice = ChantPlayer.musicLevel();
                    check(String.valueOf(ChantPlayer.musicPiece()).startsWith("punishment") && GodVoicePlayer.speaking(),
                            "под голос бога звучит музыка кары: " + ChantPlayer.musicPiece() + ", громкость " + underVoice);
                    check(ChantPlayer.altarPiece() == null, "песнопение алтаря уступило музыке кары");
                    skyUnderVoice = SkyTint.amount();
                });
            };
            Prayers.performed = act -> {
                performedAt = System.nanoTime(); log("  свершилось: " + act);
                Scheduler.later(85, () -> check(ChantPlayer.musicLevel() > underVoice * 1.3f || ChantPlayer.musicPiece() == null,
                        "после слов бога музыка кары стала громче (или доиграла): " + underVoice + " → " + ChantPlayer.musicLevel()));
                Scheduler.later(140, () -> check(ChantPlayer.musicEnding(), "через 5 с после слов бога музыка кары стихает"));
                check(ChantPlayer.thunders > 0, "под музыку кары гремел гром: " + ChantPlayer.thunders + " раз");
                String group = String.valueOf(DivineState.of(player.getServer()).ledger.soul(player.getUuid()).chronicle.getLast().group);
                if (group.matches("K[2-5]")) check(skyUnderVoice > 0.5f && SkyTint.depth() >= 1, "при каре " + group + " небо покраснело: " + skyUnderVoice);
                else log("  кара " + group + ": небо не краснеет");
            };
            spokenAt = performedAt = 0;
            home(player); // A mercy or a jest may have carried him off; the altar hears only those within 8 blocks.
            prayed(soul);
            Prayers.handle(player, altar, "бля, ты бесполезный бог, дай алмазы");
        }));
        steps.add(new Step(-1, () -> {
            Verdict wrath = verdictArrived(soul, "гнев грешнику");
            if (wrath != null) {
                // In fury the god never stays silent; the act comes from a wrath group (К1–К5), each act at its own strength.
                check(wrath.fury && wrath.chosen() != Miracle.IGNORE && wrath.group.startsWith("K"),
                        "за брань — кара из группы гнева: " + wrath.group + " " + wrath.miracle + " ×" + wrath.power);
                check(wrath.reasons.stream().anyMatch(r -> r.text().contains("брань")), "брань названа причиной");
            }
        }));
        steps.add(new Step(ACTED, () -> { }));
        steps.add(new Step(60, () -> { // The god speaks for up to half a minute before the act falls.
            screenshot = "wrath";
            check(voiced >= 1, "голос бога прозвучал до конца: " + voiced + " раз");
            check(spokenAt > 0 && performedAt > spokenAt, "кара пала после слов бога: слова → дело через "
                    + (performedAt > 0 && spokenAt > 0 ? (performedAt - spokenAt) / 1_000_000 + " мс" : "—"));
        }));
        steps.add(new Step(20, () -> MinecraftClient.getInstance().execute(() -> SkyTint.verdict(MinecraftClient.getInstance(), 4))));
        steps.add(new Step(60, () -> { check(SkyTint.amount() > 0.9f, "небо конца света (К5) красное: " + SkyTint.amount()); screenshot = "sky-k5"; }));
        steps.add(new Step(30, () -> MinecraftClient.getInstance().execute(() -> SkyTint.verdict(MinecraftClient.getInstance(), 2))));
        steps.add(new Step(50, () -> { screenshot = "sky-k3"; MinecraftClient.getInstance().execute(() -> SkyTint.verdict(MinecraftClient.getInstance(), 0)); }));
        steps.add(new Step(80, () -> check(SkyTint.amount() == 0, "небо снова чистое: " + SkyTint.amount())));
        steps.add(new Step(80, () -> {
            // Idle talk at the altar is not a prayer: the god does not hear it, nothing is weighed or done.
            soul.lastPrayerTick = Long.MIN_VALUE / 2; soul.prayerTicks.clear();
            Prayers.unheard = text -> { unheardText = text; log("  не молитва, бог не услышал: " + text); };
            home(player); // A mercy or a jest may have carried him off; the altar hears only those within 8 blocks.
            prayed(soul);
            Prayers.handle(player, altar, "так, сейчас накопаю угля и поставлю факелы у входа в шахту");
        }));
        steps.add(new Step(80, () -> {
            check(unheardText != null, "болтовню у алтаря бог не счёл молитвой");
            check(soul.chronicle.size() == soulBefore, "после болтовни нового вердикта нет: " + soulBefore + " → " + soul.chronicle.size());
        }));
        steps.add(new Step(120, () -> log("--- все деяния каталога ---")));
        DivineConfig config = DivineBlockMod.config();
        config.allowWither = true; config.maxTier = 4;
        for (Miracle miracle : Miracle.values()) if (miracle != Miracle.ETERNAL_BAN) steps.add(new Step(miracle == Miracle.METEOR || miracle == Miracle.CHICKEN_RAIN ? 90 : 30, () -> perform(player, miracle, config, Stage.STOCKED)));
        // The same acts as a real mortal meets them: nothing in his hands or bags, then in the Nether under a roof of rock.
        steps.add(new Step(40, () -> log("--- деяния с пустым инвентарём ---")));
        for (Miracle miracle : EMPTY_HANDED) steps.add(new Step(30, () -> perform(player, miracle, config, Stage.EMPTY)));
        steps.add(new Step(40, () -> {
            log("--- деяния в Аду ---");
            // Into the Nether once, a step ahead: crossing between worlds is not done within the same tick.
            ServerWorld nether = player.getServer().getWorld(net.minecraft.world.World.NETHER);
            BlockPos stand = netherStage(nether);
            player.teleport(nether, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180f, 15f);
        }));
        for (Miracle miracle : IN_THE_NETHER) steps.add(new Step(miracle == Miracle.METEOR ? 90 : 40, () -> perform(player, miracle, config, Stage.NETHER)));
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
        else if (step.delay() == SILENCE) waitSilence(steps, index, 0, 0);
        else if (step.delay() == ACTED) waitActed(steps, index, 0);
        else if (step.delay() == WHISPER) waitWhisper(steps, index, 0);
        else waitVerdict(steps, index, 0);
    }
    /** Step delay meaning "once the god has fallen silent": his lines now run half a minute, and he hears no prayer meanwhile. */
    private static final int SILENCE = -2, ACTED = -3;
    /** Step delay meaning "once the punishment has fallen" (up to 90 s). */
    private static void waitActed(List<Step> steps, int index, int waited) {
        Scheduler.later(10, () -> {
            if (performedAt > 0 || waited > 1800) { safely(steps.get(index).action()); run(steps, index + 1); }
            else waitActed(steps, index, waited + 10);
        });
    }
    private static void waitSilence(List<Step> steps, int index, int waited, int quiet) {
        Scheduler.later(10, () -> {
            int still = GodVoicePlayer.speaking() ? 0 : quiet + 1;
            if (still >= 3 || waited > 1800) { safely(steps.get(index).action()); run(steps, index + 1); }
            else waitSilence(steps, index, waited + 10, still);
        });
    }
    /** Step delay meaning "once Whisper has loaded its model, or failed to" (up to five minutes). */
    private static final int WHISPER = -4;
    private static volatile boolean whisperReady;
    private static volatile String whisperProblem;
    private static void waitWhisper(List<Step> steps, int index, int waited) {
        Scheduler.later(20, () -> {
            if (whisperReady || whisperProblem != null || waited > 20 * 300) { safely(steps.get(index).action()); run(steps, index + 1); }
            else waitWhisper(steps, index, waited + 20);
        });
    }

    /** Verdicts in the world, and in this mortal's chronicle, when the last prayer was sent: a verdict "arrives" when the
     * count grows past it, so a late verdict of an earlier prayer is never taken for the next one's. */
    private static int verdicts, soulBefore;
    private static void prayed(GodLedger.Soul soul) {
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        verdicts = server == null ? 0 : DivineState.of(server).ledger.souls.values().stream().mapToInt(s -> s.chronicle.size()).sum();
        soulBefore = soul.chronicle.size();
    }
    private static void waitVerdict(List<Step> steps, int index, int waited) {
        Scheduler.later(10, () -> {
            MinecraftServer server = MinecraftClient.getInstance().getServer();
            int size = server == null ? 0 : DivineState.of(server).ledger.souls.values().stream().mapToInt(s -> s.chronicle.size()).sum();
            if (size > verdicts || waited > 20 * 90) { safely(steps.get(index).action()); run(steps, index + 1); }
            else waitVerdict(steps, index, waited + 10);
        });
    }

    private static Verdict verdictArrived(GodLedger.Soul soul, String what) {
        Verdict last = soul.lastVerdict();
        if (!check(soul.chronicle.size() > soulBefore && last != null, what + ": вердикт пришёл")) return null;
        log("  " + last.breakdown().replace("\n", "\n  "));
        log("  решил: " + last.decidedBy + ", деяние " + last.miracle + " ×" + last.power + (last.warned ? ", со знамением" : ""));
        if (what.startsWith("милость")) check(last.inclination == Miracle.Kind.HELP, what + ": склонность " + last.inclination);
        if (what.startsWith("гнев")) check(last.inclination == Miracle.Kind.WRATH, what + ": склонность " + last.inclination);
        return last;
    }

    /** Where and how an act is tried: on the stage with the things it wants, with empty hands and bags, or in the Nether. */
    private enum Stage { STOCKED, EMPTY, NETHER }
    /** Acts that read the mortal's hands and bags. */
    private static final List<Miracle> EMPTY_HANDED = List.of(Miracle.ENCHANT, Miracle.REPAIR, Miracle.TRANSMUTE, Miracle.CURSE_TOOL, Miracle.GROW,
            Miracle.SMITE_ENEMIES, Miracle.GIFT_ITEM, Miracle.SWAP_ITEMS, Miracle.PUMPKIN_HEAD, Miracle.LIGHTNING);
    /** Acts that read the sky, the weather and the ground. */
    private static final List<Miracle> IN_THE_NETHER = List.of(Miracle.WEATHER_CLEAR, Miracle.ACID_RAIN, Miracle.RANDOM_TP, Miracle.ORE_VEIN, Miracle.TIME_RUSH,
            Miracle.LIGHTNING, Miracle.METEOR, Miracle.FISH_RAIN, Miracle.SHEEP_PARTY, Miracle.FIRE_RING, Miracle.SAFE_HOME, Miracle.SWARM, Miracle.CHICKEN_RAIN,
            Miracle.END_EXILE, Miracle.EARTHQUAKE, Miracle.TNT_RAIN, Miracle.GROW, Miracle.SMITE_ENEMIES, Miracle.CREEPER_PRANK, Miracle.LEVITATE);
    /** Acts that may honestly find nothing to do here; the god then draws another act of the same group (Prayers.act). */
    private static boolean mayFail(Miracle miracle, Stage stage) {
        return stage == Stage.EMPTY && miracle == Miracle.TRANSMUTE
                || stage == Stage.NETHER && (miracle == Miracle.HELL_EXILE || miracle == Miracle.TIME_RUSH);
    }
    private static BlockPos netherBase;

    /** A 15x15 obsidian floor with air above, in the Nether near the origin. */
    private static BlockPos netherStage(ServerWorld nether) {
        if (netherBase != null) return netherBase;
        netherBase = new BlockPos(8, 64, 8);
        for (BlockPos pos : BlockPos.iterate(netherBase.add(-7, -1, -7), netherBase.add(7, 8, 7)))
            nether.setBlockState(pos, pos.getY() == netherBase.getY() - 1 ? Blocks.OBSIDIAN.getDefaultState() : Blocks.AIR.getDefaultState());
        for (BlockPos pos : BlockPos.iterate(netherBase.add(-7, -4, -7), netherBase.add(7, -2, 7)))
            if (!nether.getBlockState(pos).isSolidBlock(nether, pos)) nether.setBlockState(pos, Blocks.NETHERRACK.getDefaultState());
        return netherBase;
    }

    private static void perform(ServerPlayerEntity player, Miracle miracle, DivineConfig config, Stage stage) {
        if (!player.isAlive()) { fail(miracle.id() + ": игрок мёртв, проверять не на ком"); return; }
        BlockPos at = altar;
        if (stage == Stage.NETHER) {
            ServerWorld nether = player.getServer().getWorld(net.minecraft.world.World.NETHER);
            if (nether == null) { fail("нет мира Ада"); return; }
            BlockPos stand = netherStage(nether);
            player.teleport(nether, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180f, 15f);
            if (player.getServerWorld() != nether) { fail(miracle.id() + ": игрок не попал в Ад"); return; }
            player.setVelocity(0, 0, 0); player.fallDistance = 0; player.setFireTicks(0);
            at = stand.north(3);
            for (MobEntity mob : nether.getEntitiesByClass(MobEntity.class, new Box(stand).expand(64), m -> true)) mob.discard();
            for (ItemEntity item : nether.getEntitiesByClass(ItemEntity.class, new Box(stand).expand(32), i -> true)) item.discard();
        } else home(player);
        ServerWorld world = player.getServerWorld();
        if (stage != Stage.NETHER) clean(world);
        player.clearStatusEffects();
        boolean hurts = miracle == Miracle.LIGHTNING; // Its blow must land: no shield, and survival (creative takes no harm).
        if (!hurts) player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 4, false, false));
        net.minecraft.world.GameMode mode = player.interactionManager.getGameMode();
        if (hurts) { player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL); Scheduler.later(20, () -> player.changeGameMode(mode)); }
        player.setHealth(hurts ? player.getMaxHealth() : player.getMaxHealth() * 0.5f);
        if (stage != Stage.STOCKED) { player.getInventory().clear(); player.currentScreenHandler.sendContentUpdates(); }
        else switch (miracle) {
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
            case BABEL -> {
                // Babel brings down the works of men: a plank hut with a brick chimney for it to topple.
                BlockPos hut = base.add(5, 0, 5);
                for (BlockPos pos : BlockPos.iterate(hut, hut.add(4, 4, 4)))
                    if (pos.getX() == hut.getX() || pos.getX() == hut.getX() + 4 || pos.getZ() == hut.getZ() || pos.getZ() == hut.getZ() + 4 || pos.getY() == hut.getY() + 4)
                        world.setBlockState(pos, Blocks.OAK_PLANKS.getDefaultState());
                for (int dy = 5; dy < 9; dy++) world.setBlockState(hut.add(2, dy, 2), Blocks.BRICKS.getDefaultState());
            }
            case SMITE_ENEMIES -> { for (int i = 0; i < 2; i++) { var zombie = EntityType.ZOMBIE.create(world); zombie.refreshPositionAndAngles(base.getX() + 6, base.getY(), base.getZ() + i * 2, 0, 0); world.spawnEntity(zombie); } }
            default -> { }
        }
        int power = Math.max(miracle.minTier, Math.min(miracle.maxTier, 2));
        String where = stage == Stage.STOCKED ? "" : stage == Stage.EMPTY ? " [пусто]" : " [Ад]";
        float health = player.getHealth();
        long time = world.getTimeOfDay();
        String result;
        try { result = Miracles.perform(miracle, power, player, at, "дай алмазный меч", config); }
        catch (RuntimeException failure) { fail(miracle.id() + " ×" + power + where + ": исключение " + failure); return; }
        if (result == null && !mayFail(miracle, stage)) fail(miracle.id() + " ×" + power + where + ": не сработало");
        else log("ok " + miracle.id() + " ×" + power + where + ": " + (result == null ? "здесь невозможно — бог возьмёт другое деяние группы" : result));
        verify(player, world, miracle, stage, health, time, where);
        if (miracle == Miracle.WITHER) Scheduler.later(10, () -> { var withers = world.getEntitiesByType(EntityType.WITHER, w -> true); check(!withers.isEmpty(), "визер появился"); withers.forEach(w -> w.discard()); });
        if (miracle == Miracle.OMEN_LIGHT || miracle == Miracle.CHICKEN_RAIN || miracle == Miracle.FIRE_RING || miracle == Miracle.SMITE_ENEMIES || miracle == Miracle.GIFT_ITEM)
            Scheduler.later(miracle == Miracle.CHICKEN_RAIN ? 40 : 8, () -> screenshot = miracle.id());
        if (miracle == Miracle.METEOR) Scheduler.later(64, () -> screenshot = "meteor");
        if (miracle == Miracle.TORNADO) Scheduler.later(140, () -> {
            // Turn to face the funnel from a safe distance, then photograph it.
            var funnel = io.github.divineblock.game.Tornadoes.newest();
            if (funnel == null) { fail("торнадо не появилось"); return; }
            double dx = funnel.x - player.getX(), dz = funnel.z - player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 4, false, false));
            player.teleport(player.getServerWorld(), player.getX(), player.getY(), player.getZ(), yaw, -12f);
            check(true, "торнадо стоит в " + Math.round(Math.hypot(dx, dz)) + " блоках");
            Scheduler.later(5, () -> screenshot = "tornado");
        });
    }

    /** What the act must have done, beyond saying so. */
    private static void verify(ServerPlayerEntity player, ServerWorld world, Miracle miracle, Stage stage, float health, long time, String where) {
        String name = miracle.id() + where;
        switch (miracle) {
            case LIGHTNING -> Scheduler.later(10, () -> check(player.getHealth() < health, name + ": молния ранила грешника, здоровье " + health + " → " + player.getHealth()));
            case TIME_RUSH -> { if (stage != Stage.NETHER) Scheduler.later(100, () -> check(world.getTimeOfDay() - time >= 24_000, name + ": сутки промчались, время +" + (world.getTimeOfDay() - time))); }
            case PUMPKIN_HEAD -> check(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isOf(Items.CARVED_PUMPKIN), name + ": тыква на голове");
            case LEVITATE -> check(player.hasStatusEffect(StatusEffects.LEVITATION), name + ": смертный поднимается");
            case DIZZY -> check(player.hasStatusEffect(StatusEffects.NAUSEA), name + ": голова кругом");
            case SHEEP_PARTY -> check(world.getEntitiesByType(EntityType.SHEEP, new Box(player.getBlockPos()).expand(8), sheep -> true).size() >= 6, name + ": овцы вокруг");
            // Landed fish merge into stacks: count the fish, not the stacks.
            case FISH_RAIN -> Scheduler.later(25, () -> check(world.getEntitiesByClass(ItemEntity.class, new Box(player.getBlockPos()).expand(16),
                    item -> item.getStack().isOf(Items.COD) || item.getStack().isOf(Items.SALMON)).stream().mapToInt(item -> item.getStack().getCount()).sum() >= 5,
                    name + ": рыба падает с неба"));
            case ENCHANT -> { if (stage == Stage.EMPTY) check(holds(player, Items.ENCHANTED_BOOK), name + ": вместо вещи — зачарованная книга"); }
            case REPAIR -> { if (stage == Stage.EMPTY) check(holds(player, Items.EXPERIENCE_BOTTLE), name + ": чинить нечего — опыт"); }
            case WEATHER_CLEAR -> { if (stage == Stage.NETHER) check(player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE), name + ": в Аду — прохлада"); }
            default -> { }
        }
        if (miracle == Miracle.PUMPKIN_HEAD) player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
    }

    private static boolean holds(ServerPlayerEntity player, net.minecraft.item.Item item) {
        for (ItemStack stack : player.getInventory().main) if (stack.isOf(item)) return true;
        return false;
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
        // Parallel runs (scripts/run-selftest.ps1) give each copy its own folder through -Ddivineblock.selftest.report.
        String local = System.getenv("LOCALAPPDATA"), own = System.getProperty("divineblock.selftest.report");
        Path dir = own != null && !own.isBlank() ? Path.of(own) : Path.of(local == null ? "." : local, "DivineBlock", "selftest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("самотест: отчёт не записан", failure); }
    }
    private static void finish(MinecraftClient client) {
        phase = 3;
        write();
        client.execute(client::scheduleStop);
    }
}
