package io.github.mobtalk.client;

import io.github.mobtalk.game.BarContent;
import io.github.mobtalk.game.BarSeatEntity;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;

/** The village bar ({@code -Only bar}): the plains bar placed on a grassy stage in the sky with every piece of furniture where the
 * template puts it; screenshots outside and inside, by day and in the evening; eight villagers who at 17:00 walk in, sit on the stools
 * and chairs and get a bartender behind the counter, the jukebox playing, a bar talk raised while the player sits among them, and
 * everyone up and the music off at closing. Then real villages placed by the game's own generator: each has at most one bar, and
 * most have one. No LLM, no voice. */
final class SelfTestBar {
    private static final String TAG = "mobtalk_bar";
    private final SelfTest test;
    private final int fx, fy, fz;
    private Runnable next = () -> { };
    private final Map<String, Object> saved = new HashMap<>();

    SelfTestBar(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX + 200; this.fy = stageY; this.fz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    /** A spot of the bar template: (0, 0, 0) is its foundation's corner, y 1 its floor, the door at (6, 2, 9). */
    private BlockPos bar(int dx, int dy, int dz) { return new BlockPos(fx + dx, fy - 2 + dy, fz + dz); }
    private String at(int dx, int dy, int dz) { BlockPos p = bar(dx, dy, dz); return p.getX() + " " + p.getY() + " " + p.getZ(); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private Box area() { return new Box(bar(6, 2, 5)).expand(30, 12, 30); }
    private List<VillagerEntity> villagers() { return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && v.getCommandTags().contains(TAG))); }
    private long seated() { return villagers().stream().filter(v -> v.getVehicle() instanceof BarSeatEntity).count(); }
    private boolean is(BlockPos pos, Block block) { return onServer(() -> world().getBlockState(pos).isOf(block)); }

    void run(Runnable then) {
        next = then;
        var config = test.bridge().config;
        saved.put("visit", config.barVisitPercent); saved.put("talk", config.barTalkSeconds); saved.put("guards", config.villageGuards); saved.put("towers", config.villageTowers);
        config.barVisitPercent = 100; config.barTalkSeconds = 10; config.villageGuards = false; config.villageTowers = false;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("bar stages: the plains bar, its evening, villages with bars");
        test.command("time set 6000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode creative " + name());
        test.command("tp " + name() + " " + at(6, 3, 18));
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    private int tries;
    private void build() {
        int f = 26;
        test.command("fill " + at(6 - f, 1, 5 - f) + " " + at(6 + f, 1, 5 + f) + " minecraft:grass_block");
        test.command("fill " + at(6 - f, 2, 5 - f) + " " + at(6 + f, 12, 5 + f) + " minecraft:air");
        test.command("place template mobtalk:bar/plains " + at(0, 0, 0));
        test.await("the bar stands", 5, () -> is(bar(1, 2, 3), BarContent.COUNTER), () -> {
            if (!is(bar(1, 2, 3), BarContent.COUNTER) && ++tries < 5) { build(); return; }
            furniture();
        });
    }

    /** Every piece where the template (tools/bar/bar.py) puts it. */
    private void furniture() {
        int counters = 0, stools = 0, chairs = 0, kegs = 0, shelves = 0;
        for (int x = 1; x <= 9; x++) if (is(bar(x, 2, 3), BarContent.COUNTER)) counters++;
        for (int x : new int[] { 2, 4, 6, 8 }) if (is(bar(x, 2, 4), BarContent.STOOL)) stools++;
        for (int x = 4; x <= 8; x++) { if (is(bar(x, 2, 1), BarContent.KEG)) kegs++; if (is(bar(x, 3, 1), BarContent.SHELF) && is(bar(x, 4, 1), BarContent.SHELF)) shelves++; }
        for (BlockPos p : List.of(bar(2, 2, 6), bar(2, 2, 8), bar(1, 2, 7), bar(3, 2, 7), bar(10, 2, 6), bar(10, 2, 8), bar(9, 2, 7), bar(11, 2, 7)))
            if (is(p, BarContent.CHAIR)) chairs++;
        test.check(counters == 9, "the counter: 9 pieces (" + counters + ")");
        test.check(stools == 4, "4 stools at the counter (" + stools + ")");
        test.check(chairs == 8, "8 chairs at two tables (" + chairs + ")");
        test.check(is(bar(2, 2, 7), BarContent.TABLE) && is(bar(10, 2, 7), BarContent.TABLE), "two tables");
        test.check(kegs == 5 && shelves == 5, "5 kegs under 5 double shelves of bottles (" + kegs + ", " + shelves + ")");
        test.check(is(bar(2, 3, 3), BarContent.BOTTLES) && is(bar(2, 3, 7), BarContent.MUGS), "bottles on the counter, mugs on a table");
        test.check(is(bar(10, 2, 1), Blocks.JUKEBOX), "a jukebox (no Alice speaker mod here)");
        test.check(is(bar(6, 2, 9), Blocks.OAK_DOOR), "the door");
        test.check(onServer(() -> world().getBlockEntity(bar(6, 4, 10)) instanceof net.minecraft.block.entity.SignBlockEntity sign
                && sign.getFrontText().getMessage(1, false).getString().equals("БАР")), "the sign «БАР» over the door");
        daylight();
    }

    private void daylight() {
        test.command("tp " + name() + " " + at(6, 3, 21) + " 180 8");
        shot("mobtalk-bar-outside.png", () -> {
            test.command("tp " + name() + " " + at(2, 3, 19) + " 200 6");
            shot("mobtalk-bar-outside-side.png", () -> {
                test.command("tp " + name() + " " + at(6, 2, 8) + " 180 12");
                shot("mobtalk-bar-inside-door.png", () -> {
                    test.command("tp " + name() + " " + at(6, 2, 2) + " 180 25");
                    shot("mobtalk-bar-back-wall.png", () -> {
                        test.command("tp " + name() + " " + at(10, 2, 2) + " 30 20");
                        shot("mobtalk-bar-from-counter.png", () -> {
                            test.command("tp " + name() + " " + at(5, 2, 6) + " 150 40");
                            shot("mobtalk-bar-stools-close.png", () -> {
                                test.command("tp " + name() + " " + at(4, 2, 5) + " 135 40");
                                shot("mobtalk-bar-table-close.png", this::populate);
                            });
                        });
                    });
                });
            });
        });
    }

    private void populate() {
        String[] jobs = { "farmer", "librarian", "fisherman", "shepherd", "nitwit", "mason", "cleric", "butcher" };
        for (int i = 0; i < jobs.length; i++)
            test.command("summon minecraft:villager " + at(-8 + (i % 4) * 7, 2, 17 + (i / 4) * 5) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                    + jobs[i] + "\",type:\"minecraft:plains\",level:2},Xp:20}");
        test.await("eight villagers by the bar", 20, () -> villagers().size() >= 8, () -> {
            GlobalPos point = GlobalPos.create(world().getRegistryKey(), bar(6, 2, 22));
            test.server().execute(() -> { for (VillagerEntity v : villagers()) v.getBrain().remember(MemoryModuleType.MEETING_POINT, point); });
            test.command("setblock " + at(6, 2, 22) + " minecraft:bell[attachment=floor,facing=north]");
            test.command("tp " + name() + " " + at(6, 3, 19) + " 180 10");
            test.command("time set 11000"); // 17:00, the bar opens.
            evening();
        });
    }

    private void evening() {
        test.await("guests sit down in the bar", 120, () -> seated() >= 5, () -> {
            test.check(seated() >= 5, "at least five guests seated (" + seated() + ")");
            BlockPos stand = onServer(() -> test.bridge().barStand(bar(6, 2, 4)));
            test.check(stand != null && stand.equals(bar(5, 2, 2)), "the bartender's place is behind the middle of the counter (" + stand + ")");
            test.await("the bartender behind the counter", 60, () -> stand != null && villagers().stream().anyMatch(v -> v.getBlockPos().getSquaredDistance(stand) <= 2), () -> {
                test.check(onServer(() -> world().getBlockEntity(bar(10, 2, 1)) instanceof JukeboxBlockEntity box && !box.getStack().isEmpty() && box.getManager().isPlaying()),
                        "the jukebox plays a disc");
                test.log("bar status: " + onServer(() -> test.bridge().barStatus(world())));
                test.command("tp " + name() + " " + at(6, 2, 8) + " 180 12");
                shot("mobtalk-bar-evening-door.png", () -> {
                    test.command("tp " + name() + " " + at(10, 2, 2) + " 40 18");
                    shot("mobtalk-bar-evening-counter.png", () -> {
                        test.command("tp " + name() + " " + at(6, 2, 6) + " 200 30");
                        shot("mobtalk-bar-evening-sitters.png", () -> {
                            test.owner.config().humanVillagers = false;
                            test.await("pause: the vanilla look", 2, () -> false, () -> shot("mobtalk-bar-evening-sitters-vanilla.png", () -> {
                                test.owner.config().humanVillagers = true;
                                talk();
                            }));
                        });
                    });
                });
            });
        });
    }

    /** The player among the guests: within the talk pause a bar talk is raised (no model here: it goes no further). */
    private void talk() {
        test.await("a bar talk is raised", 40, () -> onServer(() -> test.bridge().barStatus(world())).stream().anyMatch(s -> !s.contains("разговоров 0")), () -> {
            test.check(onServer(() -> test.bridge().barStatus(world())).stream().anyMatch(s -> !s.contains("разговоров 0")), "the guests start a talk while the player is near");
            test.command("time set 18000"); // Midnight: the bar is closed.
            test.await("everyone gets up at closing", 30, () -> seated() == 0, () -> {
                test.check(seated() == 0, "nobody sits after closing");
                test.check(onServer(() -> world().getBlockEntity(bar(10, 2, 1)) instanceof JukeboxBlockEntity box && box.getStack().isEmpty()), "the music stops at closing");
                test.await("the empty seats go away", 5, () -> onServer(() -> world().getEntitiesByClass(BarSeatEntity.class, area(), s -> true).isEmpty()), () -> {
                    test.check(onServer(() -> world().getEntitiesByClass(BarSeatEntity.class, area(), s -> true).isEmpty()), "no seat entities left");
                    villages();
                });
            });
        });
    }

    // ---- Real villages from the game's own generator ----

    private static final List<String> KINDS = List.of("plains", "desert", "savanna", "taiga", "snowy", "plains", "desert", "taiga");
    private int village;
    private final List<Integer> found = new ArrayList<>();

    private void villages() {
        test.command("time set 6000");
        test.command("gamemode spectator " + name());
        placeVillage();
    }

    private BlockPos villageSpot(int i) { return new BlockPos(fx + 600 + i * 320, 0, fz + 600); }

    private void placeVillage() {
        if (village >= KINDS.size()) { summary(); return; }
        BlockPos spot = villageSpot(village);
        test.command("tp " + name() + " " + spot.getX() + " 140 " + spot.getZ() + " 0 90");
        test.await("pause: the ground loads", 6, () -> false, () -> {
            test.command("place structure minecraft:village_" + KINDS.get(village) + " " + spot.getX() + " 70 " + spot.getZ());
            test.await("pause: the village is built", 8, () -> false, () -> {
                int bars = onServer(() -> countBars(spot));
                found.add(bars);
                test.log("village " + KINDS.get(village) + " at " + spot.toShortString() + ": bars " + bars);
                test.check(bars <= 1, "village " + KINDS.get(village) + " has at most one bar (" + bars + ")");
                if (bars == 1 && village < 5) {
                    BlockPos keg = onServer(() -> firstKeg(spot));
                    test.command("tp " + name() + " " + keg.getX() + " " + (keg.getY() + 18) + " " + (keg.getZ() + 14) + " 180 50");
                    shot("mobtalk-bar-village-" + KINDS.get(village) + ".png", () -> { village++; placeVillage(); });
                } else { village++; placeVillage(); }
            });
        });
    }

    /** The bars around a placed village: one counter of nine pieces each. */
    private int countBars(BlockPos spot) {
        int counters = 0;
        for (BlockPos pos : BlockPos.iterate(spot.add(-96, 40, -96), spot.add(96, 140, 96)))
            if (world().getBlockState(pos).isOf(BarContent.COUNTER)) counters++;
        return (counters + 4) / 9;
    }
    private BlockPos firstKeg(BlockPos spot) {
        for (BlockPos pos : BlockPos.iterate(spot.add(-96, 40, -96), spot.add(96, 140, 96)))
            if (world().getBlockState(pos).isOf(BarContent.KEG)) return pos.toImmutable();
        return spot.up(80);
    }

    private void summary() {
        long with = found.stream().filter(n -> n == 1).count();
        test.log("villages with a bar: " + with + " of " + found.size() + " " + found);
        test.check(with * 2 >= found.size(), "most villages have a bar (" + with + " of " + found.size() + ")");
        finish();
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 3, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        var config = test.bridge().config;
        config.barVisitPercent = (Integer) saved.get("visit"); config.barTalkSeconds = (Integer) saved.get("talk");
        config.villageGuards = (Boolean) saved.get("guards"); config.villageTowers = (Boolean) saved.get("towers");
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.command("gamemode survival " + name());
        test.await("pause: bar stage done", 2, () -> false, next);
    }
}
