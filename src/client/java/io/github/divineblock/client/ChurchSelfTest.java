package io.github.divineblock.client;

import com.mojang.datafixers.util.Pair;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.game.Scheduler;
import io.github.divineblock.world.CemeteryPiece;
import io.github.divineblock.world.ChurchBuilder;
import io.github.divineblock.world.ChurchPiece;
import io.github.divineblock.world.ChurchStructure;
import io.github.divineblock.world.ChurchSites;
import io.github.divineblock.world.ChurchWorldgen;
import io.github.divineblock.world.SlavicChurch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructurePiece;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.level.LevelInfo;

/** The church self-test, on with -Ddivineblock.churchtest=true: makes a fresh world with structures, finds churches with
 * /locate (one of each kind if it can), flies around them taking screenshots by day and by night, inside by the altar and over
 * the churchyard, checks the altar and the chest stand where they should, the new altar recipe in a crafting table, and that
 * switching churches off generates none. Report: %LOCALAPPDATA%\DivineBlock\selftest\church-report.txt; screenshots
 * divineblock-church-*.png in the game's screenshots folder. */
public final class ChurchSelfTest {
    private static final List<String> report = new ArrayList<>();
    private static int phase, ticks, failures;
    private static volatile String screenshot;
    private static volatile boolean finished, hud = true, moody;
    private static int waited;
    private static double gamma = -1;

    private ChurchSelfTest() { }

    public static void register() {
        if (!Boolean.getBoolean("divineblock.churchtest")) return;
        log("самотест церкви включён");
        ClientTickEvents.END_CLIENT_TICK.register(ChurchSelfTest::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        client.options.hudHidden = !hud;
        // Screenshots at the default brightness (the instance may be set brighter), restored at the end.
        if (moody && gamma < 0) { gamma = client.options.getGamma().getValue(); client.options.getGamma().setValue(0.5); }
        if (!moody && gamma >= 0) { client.options.getGamma().setValue(gamma); gamma = -1; }
        // Only once the chunks around the camera have been drawn (or after ten seconds).
        if (screenshot != null && !client.worldRenderer.isTerrainRenderComplete() && waited++ < 200) return;
        if (screenshot != null) {
            if (waited > 0) log("  ждал отрисовки чанков " + waited + " тиков");
            waited = 0;
            String name = "divineblock-church-" + screenshot + ".png";
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
                String name = "divineblock-churchtest";
                LevelInfo info = new LevelInfo(name, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true, rules, DataConfiguration.SAFE_MODE);
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
                if (finished) { phase = 3; moody = false; client.execute(() -> { write(); client.scheduleStop(); }); }
                else if (ticks > 20 * 1500) { fail("самотест не уложился в 25 минут"); phase = 3; write(); client.execute(client::scheduleStop); }
            }
            default -> { }
        }
    }

    // ---- On the server thread. ----

    private record Step(int delay, Runnable action) { }

    private static void begin(MinecraftServer server, ServerPlayerEntity player) {
        if (player == null) { fail("нет игрока"); finished = true; return; }
        ServerWorld world = player.getServerWorld();
        world.setWeather(100_000, 0, false, false);
        check(ChurchWorldgen.enabled() && ChurchWorldgen.spacing() == DivineBlockMod.config().churchSpacing,
                "настройки церквей: включены=" + ChurchWorldgen.enabled() + ", редкость " + ChurchWorldgen.spacing() + " чанков");
        check(server.getRecipeManager().get(Identifier.of(DivineBlockMod.ID, "altar")).isPresent(), "рецепт алтаря загружен");
        // The real command first, as a player would type it.
        server.getCommandManager().executeWithPrefix(player.getCommandSource().withLevel(4), "locate structure divineblock:orthodox_church");
        log("команда /locate structure divineblock:orthodox_church выполнена (ответ в чате)");

        // Up to three churches, looking for both kinds.
        Map<ChurchBuilder.Variant, ChurchSites.Site> sites = new TreeMap<>();
        List<ChurchSites.Site> all = new ArrayList<>();
        // -Ddivineblock.churchtest.only=SLAVIC (or a comma list) photographs only those kinds, each forced far away.
        String onlyProperty = System.getProperty("divineblock.churchtest.only", "");
        java.util.Set<ChurchBuilder.Variant> only = java.util.EnumSet.noneOf(ChurchBuilder.Variant.class);
        for (String name : onlyProperty.split(",")) for (ChurchBuilder.Variant v : ChurchBuilder.Variant.values()) if (v.name().equalsIgnoreCase(name.trim())) only.add(v);
        BlockPos[] from = { player.getBlockPos(), player.getBlockPos().add(4000, 0, 0), player.getBlockPos().add(0, 0, 4000), player.getBlockPos().add(-4000, 0, -4000) };
        for (BlockPos start : only.isEmpty() ? from : new BlockPos[0]) {
            ChurchSites.Site site = locate(world, start);
            if (site == null) continue;
            all.add(site);
            sites.putIfAbsent(site.variant(), site);
            if (sites.size() == ChurchBuilder.Variant.values().length) break;
        }
        // The kinds not met yet are forced for churches in untouched land far away (each kind its own), so all are photographed.
        for (ChurchBuilder.Variant kind : ChurchBuilder.Variant.values()) {
            if (sites.containsKey(kind) || sites.isEmpty() && only.isEmpty() || !only.isEmpty() && !only.contains(kind)) continue;
            ChurchStructure.forceVariant = kind;
            ChurchSites.Site forced = locate(world, player.getBlockPos().add(12_000 + 5_000 * kind.ordinal(), 0, -12_000));
            ChurchStructure.forceVariant = null;
            if (forced != null) { all.add(forced); sites.putIfAbsent(forced.variant(), forced); log("  вид " + kind + " получен принудительно: " + forced.variant()); }
        }
        check(!sites.isEmpty(), "найдено церквей: " + all.size() + ", видов: " + sites.keySet());
        for (int i = 1; i < all.size(); i++) {
            double apart = Math.sqrt(all.get(i).origin().getSquaredDistance(all.get(i - 1).origin())) / 16;
            log("  расстояние между найденными: " + Math.round(apart) + " чанков");
        }

        List<Step> steps = new ArrayList<>();
        for (ChurchSites.Site site : sites.values()) tour(steps, world, player, site);
        steps.add(new Step(20, () -> recipe(world, player)));
        steps.add(new Step(60, () -> screenshot = "recipe"));
        steps.add(new Step(30, () -> { player.closeHandledScreen(); unlocked(player); }));
        steps.add(new Step(20, () -> disabled(world)));
        steps.add(new Step(20, () -> { log("ошибок всего: " + failures); finished = true; }));
        run(steps, 0);
    }

    /** Finds a church from start and makes sure its chunks exist; null if none. */
    private static ChurchSites.Site locate(ServerWorld world, BlockPos start) {
        RegistryEntry<Structure> entry = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntry(ChurchWorldgen.STRUCTURE).orElse(null);
        if (entry == null) { fail("структура не зарегистрирована"); return null; }
        long began = System.nanoTime();
        Pair<BlockPos, RegistryEntry<Structure>> hit = world.getChunkManager().getChunkGenerator().locateStructure(world, RegistryEntryList.of(entry), start, 100, false);
        if (hit == null) { log("  от " + start.toShortString() + " церквей не найдено"); return null; }
        BlockPos at = hit.getFirst();
        log("  от " + start.toShortString() + " найдена церковь у " + at.toShortString() + " за " + (System.nanoTime() - began) / 1_000_000 + " мс, "
                + Math.round(Math.sqrt(start.getSquaredDistance(at)) / 16) + " чанков");
        for (int cx = (at.getX() >> 4) - 7; cx <= (at.getX() >> 4) + 7; cx++)
            for (int cz = (at.getZ() >> 4) - 7; cz <= (at.getZ() >> 4) + 7; cz++) world.getChunk(cx, cz);
        ChurchSites.Site site = ChurchSites.at(world, at.withY(world.getTopY() - 1)).or(() -> ChurchSites.at(world, at)).orElse(null);
        if (site == null) {
            for (int y = world.getBottomY(); y < world.getTopY() && site == null; y += 8) site = ChurchSites.at(world, at.withY(y)).orElse(null);
        }
        if (site == null) { fail("у " + at.toShortString() + " нет частей церкви"); return null; }
        log("  церковь: " + site.variant() + ", пол на y=" + site.origin().getY() + ", алтарь " + site.altar().toShortString());
        check(world.getBlockState(site.altar()).isOf(DivineBlockMod.ALTAR), "алтарь стоит в алтарной части: " + world.getBlockState(site.altar()));
        check(world.getBlockEntity(site.chest()) instanceof ChestBlockEntity, "сундук с лутом на месте");
        check(world.getBlockState(site.naveCenter()).isAir() && world.getBlockState(site.naveCenter().up()).isAir(), "точка босса в центре храма свободна");
        ChurchPiece.Frame frame = new ChurchPiece.Frame(site.origin(), site.rotation());
        boolean slavic = site.variant() == ChurchBuilder.Variant.SLAVIC;
        int bells = 0, crosses = 0;
        var whole = site.church();
        for (BlockPos pos : BlockPos.iterate(whole.getMinX(), whole.getMinY(), whole.getMinZ(), whole.getMaxX(), whole.getMaxY(), whole.getMaxZ())) {
            var state = world.getBlockState(pos);
            if (state.isOf(io.github.divineblock.world.ChurchBlocks.CHURCH_BELL)) bells++;
            else if (state.isOf(io.github.divineblock.world.ChurchBlocks.DOME_CROSS)) crosses++;
        }
        int bellsWanted = slavic ? SlavicChurch.BELLS : 7;
        check(bells == bellsWanted, "колоколов на звоннице: " + bells + " (ждём " + bellsWanted + "), поворот " + site.rotation());
        // KAIZEN87's church keeps the author's own crosses on its spires; ours stand on the drawn churches.
        if (slavic) log("  восьмиконечных крестов мода на церкви: " + crosses + " (у купола свои кресты автора)");
        else check(crosses >= 6, "восьмиконечных крестов на церкви: " + crosses);
        BlockPos north = ChurchPiece.yardNorth(site.variant());
        BlockPos wallAt = slavic ? frame.world(SlavicChurch.ALTAR_X, SlavicChurch.ALTAR_Y, 0) : frame.world(30, 5, -12), yardAt = frame.world(north.getX(), 0, north.getZ()), gateWall = frame.world(ChurchBuilder.YARD_MIN_X - 1, 2, 10);
        check(ChurchSites.isChurchBlock(world, wallAt) && ChurchSites.isChurchBlock(world, gateWall) && !ChurchSites.isChurchBlock(world, yardAt),
                "isChurchBlock: стена церкви и ограда — да, трава двора — нет");
        check(ChurchSites.yard(world, yardAt).isPresent() && ChurchSites.outdoorSpot(world, yardAt).isPresent(),
                "двор " + ChurchSites.yard(world, yardAt).map(Object::toString).orElse("нет") + ", место у входа "
                        + ChurchSites.outdoorSpot(world, yardAt).map(BlockPos::toShortString).orElse("нет"));
        BlockPos spot = site.outdoorSpot();
        check(world.getBlockState(spot).isAir() && world.getBlockState(spot.up()).isAir() && !world.getBlockState(spot.down()).isAir() && !world.getBlockState(spot.down()).isReplaceable(),
                "место у входа свободно и стоит на земле: " + world.getBlockState(spot.down()));
        Vec3d fight = io.github.divineblock.game.ChurchArea.yardSpot(world, site);
        BlockPos fightAt = BlockPos.ofFloored(fight), door = frame.world(0, 0, 0);
        check(site.yard().contains(fightAt.getX(), site.yard().getMinY(), fightAt.getZ()),
                "ChurchArea.yardSpot во дворе: " + fightAt.toShortString() + ", от двери " + Math.round(Math.sqrt(door.getSquaredDistance(fightAt))) + " блоков");
        int yardBlocked = 0;
        for (int x = ChurchBuilder.YARD_MIN_X; x <= ChurchBuilder.YARD_MAX_X; x += 4)
            for (int z = ChurchBuilder.YARD_MIN_Z; z <= ChurchBuilder.YARD_MAX_Z; z += 4) {
                int x0 = slavic ? SlavicChurch.MIN_X : ChurchBuilder.MIN_X, x1 = slavic ? SlavicChurch.MAX_X : ChurchBuilder.MAX_X;
                int z0 = slavic ? SlavicChurch.MIN_Z : ChurchBuilder.MIN_Z, z1 = slavic ? SlavicChurch.MAX_Z : ChurchBuilder.MAX_Z;
                if (x >= x0 - 1 && x <= x1 + 1 && z >= z0 - 1 && z <= z1 + 1) continue;
                // The towers, the gates, the trees and the little buildings keep to a band along the wall.
                int band = io.github.divineblock.world.ChurchYard.BAND;
                if (x < ChurchBuilder.YARD_MIN_X + band || x > ChurchBuilder.YARD_MAX_X - band || Math.abs(z) > ChurchBuilder.YARD_MAX_Z - band) continue;
                BlockPos p = frame.world(x, 0, z);
                if (!world.getBlockState(p).isAir() && !world.getBlockState(p).isReplaceable()) {
                    if (yardBlocked++ < 6) log("    во дворе на (" + x + ", 0, " + z + "): " + world.getBlockState(p).getBlock());
                }
            }
        check(yardBlocked < 12, "двор открыт: занятых точек в сетке 4×4 — " + yardBlocked);
        var structureStart = world.getStructureAccessor().getStructureContaining(site.naveCenter(),
                world.getRegistryManager().get(RegistryKeys.STRUCTURE).get(ChurchWorldgen.STRUCTURE));
        long yard = structureStart.getChildren().stream().filter(p -> p instanceof PoolStructurePiece).count();
        boolean stand = structureStart.getChildren().stream().anyMatch(p -> p instanceof CemeteryPiece);
        boolean pool = world.getRegistryManager().get(RegistryKeys.TEMPLATE_POOL).getEntry(ChurchStructure.CEMETERY_POOL).isPresent();
        if (pool) check(yard > 0 && !stand, "кладбище из пула divineblock:cemetery/start пристроено: частей " + yard);
        else check(stand, "пула кладбища нет — стоит заглушка кладбища");
        var seal = net.minecraft.registry.Registries.BLOCK.getOrEmpty(Identifier.of(DivineBlockMod.ID, "boss_seal"));
        BlockPos sealLocal = ChurchPiece.seal(site.variant()), sealAt = frame.world(sealLocal.getX(), sealLocal.getY(), sealLocal.getZ());
        if (seal.isPresent()) {
            var entity = world.getBlockEntity(sealAt);
            String nbt = entity == null ? "нет" : entity.createNbt(world.getRegistryManager()).toString();
            check(world.getBlockState(sealAt).isOf(seal.get()) && nbt.contains("church"), "печать босса в полу алтаря: " + nbt);
        } else log("  блока divineblock:boss_seal нет в сборке — печать не ставится");
        long trees = structureStart.getChildren().stream().filter(p -> p instanceof PoolStructurePiece element
                && element.getPoolElement() instanceof net.minecraft.structure.pool.FeaturePoolElement).count();
        log("  кладбище: кусков " + yard + ", из них деревьев " + trees + ", бокс " + net.minecraft.structure.StructurePiece.boundingBox(
                structureStart.getChildren().stream().filter(p -> p instanceof PoolStructurePiece)));
        for (StructurePiece piece : structureStart.getChildren()) log("    часть " + piece.getClass().getSimpleName() + " " + piece.getBoundingBox());
        if (yard > 0) floatingLeaves(world, net.minecraft.structure.StructurePiece.boundingBox(structureStart.getChildren().stream().filter(p -> p instanceof PoolStructurePiece)),
                site.origin().getY());
        return site;
    }

    /** Natural (not persistent) leaves over the churchyard: a forest tree's crown that spilled over from a neighbouring chunk
     * after the clearing and hung in the air. The churchyard's own template trees have persistent leaves. */
    private static void floatingLeaves(ServerWorld world, net.minecraft.util.math.BlockBox yard, int floor) {
        int leaves = 0;
        BlockPos first = null;
        for (BlockPos pos : BlockPos.iterate(yard.getMinX(), floor - 1, yard.getMinZ(), yard.getMaxX(), floor + 34, yard.getMaxZ())) {
            var state = world.getBlockState(pos);
            if (state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) && !state.getOrEmpty(net.minecraft.block.LeavesBlock.PERSISTENT).orElse(false)
                    && leaves++ == 0) first = pos.toImmutable();
        }
        String biome = world.getBiome(yard.getCenter().withY(floor)).getKey().map(k -> k.getValue().getPath()).orElse("?");
        check(leaves == 0, "над кладбищем нет природной листвы (биом " + biome + "): " + leaves + (first == null ? "" : ", первая у " + first.toShortString()));
    }

    /** Camera positions around one church, in its own turned coordinates: day from the west front and the churchyard
     * corner, night, inside towards the iconostasis and the altar, over the churchyard. */
    private static void tour(List<Step> steps, ServerWorld world, ServerPlayerEntity player, ChurchSites.Site site) {
        String kind = site.variant().name().toLowerCase();
        ChurchPiece.Frame frame = new ChurchPiece.Frame(site.origin(), site.rotation());
        java.util.function.Function<double[], Vec3d> at = p -> {
            BlockPos block = frame.world((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2]));
            return Vec3d.ofCenter(block).add(0, p[1] - Math.floor(p[1]) - 0.5, 0);
        };
        boolean stone = site.variant() == ChurchBuilder.Variant.STONE;
        java.util.function.BiConsumer<String, double[]> shot = (name, v) -> {
            steps.add(new Step(10, () -> look(player, at.apply(new double[] { v[0], v[1], v[2] }), at.apply(new double[] { v[3], v[4], v[5] }))));
            steps.add(new Step(v.length > 6 ? (int) v[6] : 90, () -> screenshot = kind + "-" + name));
        };
        steps.add(new Step(10, () -> { player.changeGameMode(GameMode.SPECTATOR); world.setTimeOfDay(4000); hud = false; moody = true; }));
        int nave = ChurchBuilder.NAVE_X, mainCross = stone ? 48 : 55, cornerCross = stone ? 39 : 28;
        shot.accept("01-west-gate", new double[] { -30, 30, -35, 22, 16, 0, 160 });
        shot.accept("02-northwest-yard", new double[] { -26, 12, -30, 30, 18, 0, 120 });
        shot.accept("03-south", new double[] { 25, 24, 64, 25, 20, 0, 140 });
        shot.accept("04-north", new double[] { 25, 24, -64, 25, 20, 0, 140 });
        shot.accept("05-east", new double[] { 112, 24, 0, 25, 20, 0, 140 });
        shot.accept("06-southwest-air", new double[] { -40, 55, 62, 28, 10, 0, 140 });
        shot.accept("07-top", new double[] { 28, 98, 0.2, 28, 0, 0, 160 });
        shot.accept("08-cross-main", new double[] { nave - 8, mainCross + 1, 3, nave, mainCross, 0, 80 });
        shot.accept("09-cross-corner", new double[] { 34 - 7, cornerCross + 1, -8 + 3, 34, cornerCross, -8, 80 });
        shot.accept("10-cross-tower", new double[] { 6 - 8, 58, 3, 6, 57, 0, 80 });
        shot.accept("11-bell-close", new double[] { -2.5, 22.5, 0.5, 6.5, 22, 0.5, 80 });
        shot.accept("12-belfry", new double[] { -14, 26, -12, 6, 22, 0, 80 });
        shot.accept("13-holy-gate-inside", new double[] { -20, 5, 9, -33, 7, 0, 80 });
        shot.accept("14-south-gate", new double[] { 20, 6, 28, 28, 4, 39, 80 });
        shot.accept("15-wall-corner", new double[] { 70, 8, 26, 89, 6, 39, 80 });
        shot.accept("16-interior-entrance", new double[] { 14, 4.2, 0, 48, 6, 0, 80 });
        shot.accept("17-iconostasis", new double[] { 37, 4.5, 0.5, 48, 7, 0.5, 80 });
        shot.accept("18-chandelier", new double[] { 35, 6, 6, 42, 12.5, 0.5, 80 });
        shot.accept("19-interior-from-soleas", new double[] { 46, 4.5, 0.5, 20, 4, 0.5, 80 });
        shot.accept("20-altar", new double[] { 51, 3.6, 3.5, 55.5, 1.5, 0.5, 80 });
        shot.accept("21-refectory", new double[] { 27, 4, 5, 13, 3, -2, 80 });
        steps.add(new Step(10, () -> world.setTimeOfDay(18000)));
        shot.accept("22-night", new double[] { -26, 12, -30, 30, 18, 0, 90 });
        shot.accept("23-night-interior", new double[] { 33, 3.5, 0.5, 48, 6, 0.5, 90 });
        steps.add(new Step(10, () -> world.setTimeOfDay(4000)));
        // Исповедь: настоятель церкви у аналоя (Abbots), один на церковь.
        steps.add(new Step(10, () -> {
            net.minecraft.entity.passive.VillagerEntity rector = io.github.divineblock.game.Abbots.visit(world, site);
            check(rector != null && rector.getCommandTags().contains(io.github.divineblock.game.Abbots.TAG)
                    && rector.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.CLERIC && rector.hasCustomName()
                    && rector.getCustomName().getString().startsWith("отец "), "в церкви появился настоятель: " + (rector == null ? "нет" : rector.getCustomName().getString()));
            check(rector != null && rector.getBlockPos().isWithinDistance(site.analoy(), 3), "настоятель стоит у аналоя: " + (rector == null ? "—" : rector.getBlockPos().toShortString()) + ", аналой " + site.analoy().toShortString());
            check(io.github.divineblock.game.Abbots.visit(world, site) == rector, "настоятель один: второе посещение не зовёт второго");
        }));
        shot.accept("24a-rector", new double[] { 35, 4.2, -3.5, 40, 2.5, -0.5, 80 });
        shot.accept("24-cemetery", new double[] { 28, 42, 26, 28, 0, 62, 100 });
        shot.accept("25-cemetery-top", new double[] { 28, 95, 62, 28, 0, 64, 140 });
        // The churchyard: the Holy Gate from outside, the north side with the spring and the chapel, the south side.
        // Outside the wall the ground is the natural one: the camera stands two blocks over it (never inside a hill).
        steps.add(new Step(10, () -> {
            BlockPos column = frame.world(-52, 0, 6);
            world.getChunk(column);
            int ground = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column).getY();
            double eye = Math.max(6, ground - site.origin().getY() + 2.6);
            look(player, at.apply(new double[] { -52, eye, 6 }), at.apply(new double[] { -33, 10, 0 }));
        }));
        steps.add(new Step(120, () -> screenshot = kind + "-26-holy-gate-outside"));
        shot.accept("27-yard-north", new double[] { -14, 9, -18, 30, 4, -32, 120 });
        shot.accept("28-yard-south", new double[] { 6, 9, 18, 50, 3, 34, 120 });
        shot.accept("29-yard-air", new double[] { -60, 45, -60, 20, 0, 0, 140 });
        // Ring the great bell once.
        steps.add(new Step(10, () -> {
            BlockPos bell = site.variant() == ChurchBuilder.Variant.SLAVIC
                    ? frame.world(SlavicChurch.BELL_X, SlavicChurch.BEAM_Y - 2, SlavicChurch.BELL_Z) : frame.world(ChurchBuilder.BELL_X, ChurchBuilder.BELL_Y, ChurchBuilder.BELL_Z);
            boolean ok = world.getBlockState(bell).isOf(io.github.divineblock.world.ChurchBlocks.CHURCH_BELL);
            if (ok) io.github.divineblock.world.ChurchBlocks.ChurchBellBlock.ring(world, bell, 2);
            check(ok, "великий колокол на месте и звонит: " + world.getBlockState(bell));
        }));
        steps.add(new Step(10, () -> { hud = true; moody = false; }));
    }

    private static void look(ServerPlayerEntity player, Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleport(player.getServerWorld(), eye.x, eye.y - player.getStandingEyeHeight(), eye.z, yaw, pitch);
    }

    /** The new, dear recipe in a real crafting table; the old cheap one makes nothing. */
    private static void recipe(ServerWorld world, ServerPlayerEntity player) {
        player.changeGameMode(GameMode.CREATIVE);
        world.setTimeOfDay(6000);
        BlockPos table = player.getBlockPos().down(3);
        world.setBlockState(table, Blocks.CRAFTING_TABLE.getDefaultState());
        player.openHandledScreen(Blocks.CRAFTING_TABLE.getDefaultState().createScreenHandlerFactory(world, table));
        if (!(player.currentScreenHandler instanceof CraftingScreenHandler crafting)) { fail("верстак не открылся"); return; }
        Item[] old = { Items.GOLD_INGOT, Items.GOLD_INGOT, Items.GOLD_INGOT, Items.QUARTZ_BLOCK, Items.DIAMOND, Items.QUARTZ_BLOCK, Items.OBSIDIAN, Items.OBSIDIAN, Items.OBSIDIAN };
        lay(crafting, old);
        check(crafting.getSlot(0).getStack().isEmpty(), "старый дешёвый рецепт больше не даёт алтарь");
        Item[] dear = { Items.GOLD_BLOCK, Items.BOOK, Items.GOLD_BLOCK, Items.DIAMOND, Items.NETHERITE_INGOT, Items.DIAMOND, Items.CRYING_OBSIDIAN, Items.CRYING_OBSIDIAN, Items.CRYING_OBSIDIAN };
        lay(crafting, dear);
        ItemStack result = crafting.getSlot(0).getStack();
        check(result.isOf(DivineBlockMod.ALTAR_ITEM), "новый рецепт (золотые блоки, книга, алмазы, незерит, плачущий обсидиан) даёт алтарь: " + result);
    }
    private static void lay(CraftingScreenHandler crafting, Item[] grid) {
        for (int i = 0; i < 9; i++) crafting.getSlot(1 + i).setStack(new ItemStack(grid[i]));
        crafting.onContentChanged(crafting.getSlot(1).inventory);
        crafting.sendContentUpdates();
    }
    private static void unlocked(ServerPlayerEntity player) {
        boolean known = player.getRecipeBook().contains(Identifier.of(DivineBlockMod.ID, "altar"));
        check(known, "рецепт алтаря открылся в книге рецептов после посещения церкви");
    }

    /** Churches switched off: none is found in an untouched part of the world; then the setting goes back. */
    private static void disabled(ServerWorld world) {
        var config = DivineBlockMod.config();
        config.churches = false;
        ChurchWorldgen.apply(config);
        RegistryEntry<Structure> entry = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntry(ChurchWorldgen.STRUCTURE).orElseThrow();
        var hit = world.getChunkManager().getChunkGenerator().locateStructure(world, RegistryEntryList.of(entry), new BlockPos(40_000, 80, -40_000), 6, false);
        check(hit == null, "при выключенных церквях новых не находится: " + (hit == null ? "нет" : hit.getFirst().toShortString()));
        ChurchWorldgen.apply(DivineBlockMod.config());
    }

    private static void run(List<Step> steps, int index) {
        if (index >= steps.size()) return;
        Step step = steps.get(index);
        Scheduler.later(Math.max(1, step.delay()), () -> {
            try { step.action().run(); } catch (RuntimeException failure) { fail("шаг упал: " + failure); DivineBlockMod.LOG.warn("самотест церкви", failure); }
            run(steps, index + 1);
        });
    }

    private static boolean check(boolean ok, String what) { if (ok) log("ok " + what); else fail(what); return ok; }
    private static void fail(String what) { failures++; log("FAIL " + what); }
    private static synchronized void log(String line) {
        report.add(LocalTime.now().withNano(0) + " " + line);
        DivineBlockMod.LOG.info("[самотест церкви] {}", line);
    }
    private static synchronized void write() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = Path.of(local == null ? "." : local, "DivineBlock", "selftest");
        try { Files.createDirectories(dir); Files.write(dir.resolve("church-report.txt"), report, StandardCharsets.UTF_8); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("самотест церкви: отчёт не записан", failure); }
    }
    private static void finish(MinecraftClient client) { phase = 3; write(); client.execute(client::scheduleStop); }
}
