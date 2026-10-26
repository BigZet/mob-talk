package io.github.mobtalk.client;

import io.github.mobtalk.core.Officialdom;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.game.OfficialContent;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;

/** Officials, stage 1 ({@code -Only officials}): a grassy village of twenty-four in the sky puts up three desks by its bell and gets a
 * head of the administration, an inspector and a clerk. Checks the ranks, the desks, the clothes and the belly (screenshots in both
 * looks), the envelope (filling it from the other hand), a bribe to a corrupt head away from witnesses (taken, the village forgives),
 * a clerk's certificate, a bribe in front of witnesses (they remember it), an honest official (refused), the inspector's order after
 * the player builds near him and the order «lost» for half the fine, and the F7 group. No LLM, no voice. */
final class SelfTestOfficials {
    private static final String TAG = "mobtalk_off";
    private static final int FLOOR = 40;
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private Runnable next = () -> { };
    private boolean savedTowers, savedGuards, savedHuman;
    private int savedHonest, savedClientHonest, savedQueue, savedRevizor;

    SelfTestOfficials(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 400; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            if (!overlay) test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private BlockPos bell() { return new BlockPos(fx, fy, fz); }
    private long count(String part) { synchronized (chat) { return chat.stream().filter(line -> line.contains(part)).count(); } }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private Box area() { return new Box(bell()).expand(FLOOR + 4, 20, FLOOR + 4); }
    private List<VillagerEntity> officials() { return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && OfficialContent.rank(v) != null)); }
    private VillagerEntity official(Officialdom.Rank rank) { return officials().stream().filter(v -> OfficialContent.rank(v) == rank).findFirst().orElse(null); }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("officials stages: desks by the bell, three ranks, clothes and belly, the envelope, bribes, witnesses, an honest official, the inspector's order, F7");
        var config = test.bridge().config;
        savedTowers = config.villageTowers; savedGuards = config.villageGuards; savedHonest = config.officialHonestPercent; savedQueue = config.queueSeconds; savedRevizor = config.revizorSeconds; hookTooltip(); savedHuman = test.owner.config().humanVillagers;
        test.bridge().memory.familyClearForTest(player().getUuid()); // a test family left over by an earlier run
        config.villageTowers = false; config.villageGuards = false; // officials alone: no stands and towers in the way
        config.officialHonestPercent = 0; // every official corrupt until the honest stage
        savedClientHonest = test.owner.config().officialHonestPercent; test.owner.config().officialHonestPercent = 0;
        test.command("difficulty peaceful"); test.command("time set 1000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode survival " + name());
        test.command("effect give " + name() + " minecraft:slow_falling 900 1 true");
        test.command("effect give " + name() + " minecraft:resistance 900 255 true");
        test.command("effect give " + name() + " minecraft:saturation 900 1 true");
        test.command("tp " + name() + " " + at(0, 1, 0));
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    private int floorTries;
    private void build() {
        int f = FLOOR;
        test.command("fill " + at(-f, -1, -f) + " " + at(f, -1, f) + " minecraft:grass_block");
        for (String wall : List.of(at(-f, 0, -f) + " " + at(f, 3, -f), at(-f, 0, f) + " " + at(f, 3, f), at(-f, 0, -f) + " " + at(-f, 3, f), at(f, 0, -f) + " " + at(f, 3, f)))
            test.command("fill " + wall + " minecraft:barrier");
        test.command("setblock " + at(0, 0, 0) + " minecraft:bell[attachment=floor,facing=north]");
        test.await("the stage floor is built", 4, this::floorReady, () -> {
            if (!floorReady() && ++floorTries < 6) { build(); return; }
            test.check(floorReady(), "the stage floor is built");
            populate();
        });
    }
    private boolean floorReady() {
        int f = FLOOR - 1;
        return onServer(() -> List.of(bell().add(-f, -1, -f), bell().add(f, -1, -f), bell().add(-f, -1, f), bell().add(f, -1, f), bell().down())
                .stream().allMatch(p -> world().getBlockState(p).isOf(net.minecraft.block.Blocks.GRASS_BLOCK)));
    }

    private void populate() {
        String[] jobs = { "farmer", "librarian", "fisherman", "shepherd", "cleric", "mason" };
        for (int i = 0; i < 24; i++)
            test.command("summon minecraft:villager " + at(-15 + (i % 6) * 6, 0, -20 + (i / 6) * 13) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                    + jobs[i % 6] + "\",type:\"minecraft:plains\",level:2},Xp:20}");
        test.await("twenty-four villagers on the stage", 20, () -> onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG)).size() >= 24), () -> {
            GlobalPos point = GlobalPos.create(world().getRegistryKey(), bell());
            test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG))) v.getBrain().remember(MemoryModuleType.MEETING_POINT, point); });
            test.command("tp " + name() + " " + at(0, 6, 14) + " 180 30");
            test.await("three officials appointed (chat)", 150, () -> count("Назначен новый чиновник") >= 3, () -> {
                test.check(count("Назначен новый чиновник") >= 3, "a village of twenty-four puts up three desks and gets three officials (" + count("Назначен новый чиновник") + ")");
                ranks();
            });
        });
    }

    /** One of each rank, each with its desk by the bell. */
    private void ranks() {
        List<VillagerEntity> all = officials();
        Set<Officialdom.Rank> ranks = new TreeSet<>();
        for (VillagerEntity v : all) ranks.add(OfficialContent.rank(v));
        test.check(ranks.size() == 3, "head, inspector and clerk: " + ranks);
        for (VillagerEntity v : all) {
            BlockPos desk = onServer(() -> v.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null));
            boolean isDesk = desk != null && onServer(() -> world().getBlockState(desk).isOf(OfficialContent.DESK));
            test.check(isDesk && desk.getSquaredDistance(bell()) <= 10 * 10, OfficialContent.rank(v) + " has its desk by the bell (" + (desk == null ? "none" : desk.toShortString()) + ")");
        }
        VillagerEntity head = official(Officialdom.Rank.HEAD);
        if (head != null) test.check(ChildView.officialBelly(head) > 0.5f, "the corrupt head wears a belly (" + ChildView.officialBelly(head) + ")");
        looks();
    }

    /** The three side by side at the desks: people, then the vanilla look. */
    private void looks() {
        List<VillagerEntity> all = officials();
        test.server().execute(() -> { for (int i = 0; i < all.size(); i++) { VillagerEntity v = all.get(i); v.setAiDisabled(true); v.refreshPositionAndAngles(fx - 2 + i * 2 + 0.5, fy, fz + 8.5, 0f, 0f); v.setHeadYaw(0f); v.setBodyYaw(0f); } });
        test.command("tp " + name() + " " + at(0, 0, 12) + " 180 10");
        shot("mobtalk-officials-people.png", () -> {
            test.owner.config().humanVillagers = false;
            shot("mobtalk-officials-vanilla.png", () -> {
                test.owner.config().humanVillagers = savedHuman;
                // Six blocks south of the desks' middle, three up, facing it.
                double[] mid = onServer(() -> {
                    double x = 0, y = 0, z = 0; int n = 0;
                    for (VillagerEntity v : all) { BlockPos d = v.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null); if (d != null) { x += d.getX(); y += d.getY(); z += d.getZ(); n++; } }
                    return n == 0 ? new double[] {fx, fy, fz - 6} : new double[] {x / n + 0.5, y / n + 0.5, z / n + 0.5};
                });
                test.command(String.format(java.util.Locale.ROOT, "tp %s %.1f %.1f %.1f facing %.1f %.1f %.1f", name(), mid[0], mid[1] + 3, mid[2] + 7, mid[0], mid[1], mid[2]));
                shot("mobtalk-officials-desks.png", () -> {
                    test.server().execute(() -> all.forEach(v -> v.setAiDisabled(false)));
                    envelope();
                });
            });
        });
    }

    private ServerPlayerEntity player() { return test.serverPlayer(); }
    /** Puts {@code stack} in the player's main hand and {@code other} in the off hand (server side). */
    private void hands(ItemStack stack, ItemStack other) {
        onServer(() -> { player().setStackInHand(Hand.MAIN_HAND, stack); player().setStackInHand(Hand.OFF_HAND, other); return null; });
    }
    private String give(VillagerEntity official) {
        try { return test.bridge().giftFromHand(official.getUuid(), player().getUuid()).get(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) { return "error: " + e; }
    }

    /** Right-click with an envelope and emeralds in the other hand fills it. */
    private void envelope() {
        // The plain villagers stand still where they are (by the bell): the bribes below are given twenty blocks away, out of their sight.
        test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG) && OfficialContent.rank(v) == null)) v.setAiDisabled(true); });
        hands(new ItemStack(OfficialContent.ENVELOPE, 3), new ItemStack(Items.EMERALD, 30));
        onServer(() -> { player().getMainHandStack().use(world(), player(), Hand.MAIN_HAND); return null; });
        int inMain = onServer(() -> OfficialContent.emeralds(player().getMainHandStack()));
        int filled = onServer(() -> { int n = 0; for (ItemStack s : player().getInventory().main) n = Math.max(n, OfficialContent.emeralds(s)); return Math.max(n, OfficialContent.emeralds(player().getMainHandStack())); });
        test.check(filled == 30, "right-click puts the 30 emeralds from the other hand into one envelope (" + filled + ", in hand " + inMain + ")");
        test.check(onServer(() -> player().getOffHandStack().isEmpty()), "the other hand is empty after filling");
        headBribe();
    }

    /** A corrupt head, away from everyone: takes an envelope of its price and the village forgives the player. */
    private void headBribe() {
        VillagerEntity head = official(Officialdom.Rank.HEAD);
        if (head == null) { test.fail("no head"); finish(); return; }
        test.server().execute(() -> { head.setAiDisabled(true); head.refreshPositionAndAngles(fx + 30.5, fy, fz + 30.5, 0f, 0f); });
        test.command("tp " + name() + " " + (fx + 30) + " " + fy + " " + (fz + 27) + " 0 10");
        test.await("pause: away from the village", 2, () -> false, () -> {
            int relation = test.bridge().memory.relationship(head.getUuid(), player().getUuid());
            int price = Officialdom.price(Officialdom.Rank.HEAD, head.getUuid(), relation, 0, test.bridge().config.officialPricePercent, 0);
            hands(OfficialContent.envelope(price), ItemStack.EMPTY);
            String said = give(head);
            test.log("head bribe (" + price + "): " + said);
            test.check(said.contains("берёт конверт"), "the corrupt head takes an envelope of its price away from witnesses");
            test.check(onServer(() -> player().getMainHandStack().isEmpty()), "the envelope is gone from the hand");
            test.check(test.bridge().memory.relationship(head.getUuid(), player().getUuid()) > relation, "the head likes the player more");
            shot("mobtalk-officials-head-bribe.png", () -> { test.server().execute(() -> head.setAiDisabled(false)); clerk(); });
        });
    }

    /** The clerk: a bribe buys a certificate. */
    private void clerk() {
        VillagerEntity clerk = official(Officialdom.Rank.CLERK);
        if (clerk == null) { test.fail("no clerk"); finish(); return; }
        test.server().execute(() -> { clerk.setAiDisabled(true); clerk.refreshPositionAndAngles(fx - 30.5, fy, fz + 30.5, 0f, 0f); });
        test.command("tp " + name() + " " + (fx - 31) + " " + fy + " " + (fz + 27) + " 0 10");
        // Nobody about: villagers who wandered near are sent off.
        test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, new Box(fx - 30.5, fy - 4, fz + 30.5, fx - 30.5, fy + 4, fz + 30.5).expand(14), v -> OfficialContent.rank(v) == null)) v.refreshPositionAndAngles(v.getX() + 60, v.getY(), v.getZ() - 60, 0f, 0f); });
        test.await("pause: by the clerk", 2, () -> false, () -> {
            hands(OfficialContent.envelope(20), ItemStack.EMPTY);
            String said = give(clerk);
            test.log("clerk bribe: " + said);
            boolean certificate = onServer(() -> player().getInventory().containsAny(s -> s.isOf(OfficialContent.CERTIFICATE)));
            test.check(said.contains("справку") && certificate, "the clerk takes it and hands a certificate (" + certificate + ")");
            witnesses(clerk);
        });
    }

    /** In front of two villagers: a cautious official hands it back, a brazen one takes it; either way the witnesses remember. */
    private void witnesses(VillagerEntity clerk) {
        List<VillagerEntity> plain = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && OfficialContent.rank(v) == null && v.getCommandTags().contains(TAG)));
        if (plain.size() < 2) { test.fail("no villagers to witness"); honest(clerk); return; }
        List<VillagerEntity> two = plain.subList(0, 2);
        test.server().execute(() -> { for (int i = 0; i < 2; i++) { two.get(i).setAiDisabled(true); two.get(i).refreshPositionAndAngles(fx - 33.5 + i * 5, fy, fz + 29.5, 0f, 0f); } });
        test.await("pause: witnesses in place", 2, () -> false, () -> {
            hands(OfficialContent.envelope(20), ItemStack.EMPTY);
            String said = give(clerk);
            boolean brazen = Officialdom.brazen(clerk.getUuid(), 0);
            test.log("bribe before witnesses (brazen " + brazen + "): " + said);
            test.check(brazen ? said.contains("берёт") : said.contains("при людях"), "before witnesses a " + (brazen ? "brazen clerk takes it" : "cautious clerk hands it back"));
            boolean remembered = two.stream().allMatch(w -> test.bridge().memory.events(w.getUuid()).stream().anyMatch(e -> e.contains("сунул конверт")));
            test.check(remembered, "both witnesses remember the envelope by the player's name");
            test.server().execute(() -> two.forEach(w -> w.setAiDisabled(false)));
            honest(clerk);
        });
    }

    /** Every official honest: the clerk refuses and the player gets the envelope back. */
    private void honest(VillagerEntity clerk) {
        test.bridge().config.officialHonestPercent = 100;
        test.command("tp " + name() + " " + (fx - 31) + " " + fy + " " + (fz + 27) + " 0 10");
        hands(OfficialContent.envelope(20), ItemStack.EMPTY);
        String said = give(clerk);
        test.log("honest clerk: " + said);
        test.check(said.contains("взяток не берёт") || said.contains("зовёт стражу"), "an honest clerk refuses");
        test.check(onServer(() -> OfficialContent.emeralds(player().getMainHandStack())) == 20, "the envelope stays with the player");
        test.bridge().config.officialHonestPercent = 0;
        test.server().execute(() -> clerk.setAiDisabled(false));
        inspector();
    }

    /** The player builds near the inspector: he walks up and writes an order; half the fine in an envelope «loses» it. */
    private void inspector() {
        VillagerEntity inspector = official(Officialdom.Rank.INSPECTOR);
        if (inspector == null) { test.fail("no inspector"); f7(); return; }
        BlockPos near = onServer(inspector::getBlockPos);
        test.command("tp " + name() + " " + near.getX() + " " + fy + " " + (near.getZ() + 6) + " 180 15");
        test.await("pause: by the inspector", 2, () -> false, () -> {
            test.server().execute(() -> { for (int i = 0; i < 16; i++) MobGameBridge.blockPlaced(world(), player().getBlockPos().add(i % 4, 0, 2 + i / 4), player()); });
            test.await("the inspector writes an order (chat)", 30, () -> count("предписание №") >= 1, () -> {
                test.check(count("предписание №") >= 1, "the inspector writes an order to a player who builds near him");
                shot("mobtalk-officials-order.png", () -> {
                    int fine = test.bridge().memory.officialFines().stream().filter(f -> f.player().equals(player().getUuid())).mapToInt(Officialdom.Fine::amount).findFirst().orElse(0);
                    test.check(fine == test.bridge().config.inspectorFine, "the order's fine is the setting (" + fine + ")");
                    test.server().execute(() -> inspector.refreshPositionAndAngles(fx + 30.5, fy, fz - 30.5, 0f, 0f));
                    test.command("tp " + name() + " " + (fx + 30) + " " + fy + " " + (fz - 27) + " 180 10");
                    test.await("pause: away with the inspector", 2, () -> false, () -> {
                        hands(OfficialContent.envelope((fine + 1) / 2), ItemStack.EMPTY);
                        String said = give(inspector);
                        test.log("inspector bribe: " + said);
                        test.check(said.contains("потерялось"), "half the fine in an envelope «loses» the order");
                        test.check(test.bridge().memory.officialFines().stream().noneMatch(f -> f.player().equals(player().getUuid())), "no open order is left");
                        paperwork();
                    });
                });
            });
        });
    }

    // ---- Stage 2: the passport ----

    private boolean visit(VillagerEntity official) { return onServer(() -> test.bridge().officeVisitForTest(official, player())); }
    private io.github.mobtalk.core.Paperwork.Case paperCase() { return test.bridge().memory.paperCase(player().getUuid()); }
    private int passports() { return onServer(() -> { int n = 0; for (int i = 0; i < player().getInventory().size(); i++) if (player().getInventory().getStack(i).isOf(OfficialContent.PASSPORT)) n++; return n; }); }
    private VillagerEntity at(Officialdom.Rank rank) { return official(rank); }

    /** The clerk back at her desk by the bell, the player next to it, the village's villagers free to walk (they make the queue). */
    private void paperwork() {
        VillagerEntity clerk = official(Officialdom.Rank.CLERK);
        if (clerk == null) { test.fail("no clerk for the passport"); f7(); return; }
        var config = test.bridge().config;
        config.paperwork = true; config.paperLunch = true; config.paperworkSteps = 2; config.passportDays = 1; config.queueSeconds = 2; config.passportFee = 3;
        BlockPos desk = onServer(() -> clerk.getBrain().getOptionalRegisteredMemory(net.minecraft.entity.ai.brain.MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(bell()));
        test.server().execute(() -> {
            for (VillagerEntity v : officials()) { v.setAiDisabled(true); }
            clerk.refreshPositionAndAngles(desk.getX() + 1.5, desk.getY(), desk.getZ() + 0.5, 90f, 0f);
            for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG) && OfficialContent.rank(v) == null)) v.setAiDisabled(false);
            player().getInventory().clear();
            player().getInventory().insertStack(new ItemStack(Items.PAPER, 8)); player().getInventory().insertStack(new ItemStack(Items.INK_SAC, 4));
            player().getInventory().insertStack(new ItemStack(Items.EMERALD, 40));
        });
        Direction front = onServer(() -> world().getBlockState(desk).contains(OfficialContent.Desk.FACING) ? world().getBlockState(desk).get(OfficialContent.Desk.FACING) : Direction.SOUTH);
        BlockPos stand = desk.offset(front, 5).offset(front.rotateYClockwise(), 3);
        test.command(String.format(java.util.Locale.ROOT, "tp %s %d %d %d facing %d %d %d", name(), stand.getX(), stand.getY(), stand.getZ(), desk.getX(), desk.getY(), desk.getZ()));
        test.command("time set 7200");
        test.await("pause: by the clerk's window at lunch", 2, () -> false, () -> {
            long lunch = count("Обед с 13 до 14");
            visit(clerk);
            test.await("the lunch line (chat)", 3, () -> count("Обед с 13 до 14") > lunch, () -> {
                test.check(count("Обед с 13 до 14") > lunch, "at 13:30 the clerk is at lunch");
                test.check(paperCase() == null, "no case opened at lunch");
                test.command("time set 2000");
                test.await("pause: morning", 1, () -> false, () -> queue(clerk, desk));
            });
        });
    }

    /** The queue: villagers walk up and stand in line in front of the desk; the player's turn comes; the case opens with the application. */
    private void queue(VillagerEntity clerk, BlockPos desk) {
        visit(clerk);
        int ahead = onServer(() -> test.bridge().queueAheadForTest(player().getUuid()));
        test.await("villagers stand in line", 10, () -> onServer(() -> test.bridge().queueLineForTest(player().getUuid())) > 0 && lineFormed(desk), () -> {
            test.check(ahead > 0 && count("Очередь! Перед вами") >= 1, "there is a queue at the clerk's window (" + ahead + " ahead)");
            test.check(lineFormed(desk), "villagers walk up and stand in line in front of the desk (" + onServer(() -> test.bridge().queueLineForTest(player().getUuid())) + ")");
            test.owner.client.inGameHud.getChatHud().clear(false);
            shot("mobtalk-officials-queue.png", () -> test.await("the player's turn", 30, () -> paperCase() != null && paperCase().index() >= 1 && count("Ваша очередь") >= 1, () -> {
                io.github.mobtalk.core.Paperwork.Case kase = paperCase();
                test.check(kase != null && count("Ваша очередь") >= 1, "the queue moves and the player's turn comes");
                test.check(kase != null && kase.steps().size() == 4, "the case has the application, two papers and the passport (" + (kase == null ? 0 : kase.steps().size()) + ")");
                test.check(kase != null && kase.index() == 1, "the application is handed in on the spot (a sheet of paper)");
                test.bridge().config.queueSeconds = 0;
                round(0);
            }));
        });
    }
    private boolean lineFormed(BlockPos desk) {
        return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, new net.minecraft.util.math.Box(desk).expand(7, 2, 7),
                v -> OfficialContent.rank(v) == null && v.getCommandTags().contains(TAG)).size() >= 1);
    }

    /** Around the offices until the passport: each paper at its office, a day passes when a paper is «ready tomorrow». */
    private void round(int steps) {
        io.github.mobtalk.core.Paperwork.Case kase = paperCase();
        if (kase == null || steps > 14) {
            test.check(passports() == 1, "the round ends with the passport in the player's pocket (" + steps + " visits)");
            ItemStack passport = onServer(() -> { for (int i = 0; i < player().getInventory().size(); i++) if (player().getInventory().getStack(i).isOf(OfficialContent.PASSPORT)) return player().getInventory().getStack(i).copy(); return ItemStack.EMPTY; });
            test.check(OfficialContent.passportHolder(passport).equals(player().getUuidAsString()), "the passport is made out to the player");
            test.check(onServer(() -> OfficialContent.hasPassport(player())), "the game sees the player carries his passport");
            tooltip(passport);
            return;
        }
        io.github.mobtalk.core.Paperwork.Step step = kase.current();
        long today = Math.floorDiv(onServer(() -> world().getServer().getOverworld().getTimeOfDay()), 24_000L);
        if (kase.waiting() && kase.readyDay() > today) {
            test.log("paperwork: «" + step.title + "» ready by day " + kase.readyDay() + ", today " + today + " — a day passes");
            test.command("time add 24000");
            test.await("pause: next day", 1, () -> false, () -> round(steps + 1));
            return;
        }
        VillagerEntity office = at(step.office);
        if (office == null) office = official(Officialdom.Rank.CLERK);
        test.log("paperwork: «" + step.title + "» at " + step.office);
        visit(office);
        test.await("pause: after the visit", 1, () -> false, () -> round(steps + 1));
    }

    /** The passport's tooltip with the holder's face, drawn over the HUD for the screenshot. */
    private void tooltip(ItemStack passport) {
        tooltipStack = passport;
        test.owner.client.inGameHud.getChatHud().clear(false);
        shot("mobtalk-officials-passport.png", () -> { tooltipStack = ItemStack.EMPTY; papers(); });
    }
    private static volatile ItemStack tooltipStack = ItemStack.EMPTY, tooltipSecond = ItemStack.EMPTY;
    private static boolean tooltipHooked;
    static void hookTooltip() {
        if (tooltipHooked) return;
        tooltipHooked = true;
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((context, tick) -> {
            ItemStack stack = tooltipStack;
            if (stack.isEmpty()) return;
            var client = net.minecraft.client.MinecraftClient.getInstance();
            context.drawItem(stack, 24, 24);
            context.drawItemTooltip(client.textRenderer, stack, 36, 48);
            ItemStack second = tooltipSecond;
            if (!second.isEmpty()) {
                int y = context.getScaledWindowHeight() / 2 + 8;
                context.drawItem(second, 24, y - 24);
                context.drawItemTooltip(client.textRenderer, second, 36, y);
            }
        });
    }

    // ---- Papers: the windows of the clerk (ЗАГС), the head and the inspector ----

    private List<io.github.mobtalk.core.CivilRegistry.Offer> menu() { return onServer(() -> test.bridge().papersMenuForTest(player().getUuid())); }
    private int line(io.github.mobtalk.core.CivilRegistry.Paper paper) {
        List<io.github.mobtalk.core.CivilRegistry.Offer> offers = menu();
        for (int i = 0; i < offers.size(); i++) if (offers.get(i).paper() == paper) return i + 1;
        return -1;
    }
    private ItemStack paper(io.github.mobtalk.core.CivilRegistry.Paper paper) {
        return onServer(() -> {
            ItemStack found = ItemStack.EMPTY;
            for (int i = 0; i < player().getInventory().size(); i++) if (OfficialContent.paperOf(player().getInventory().getStack(i)) == paper) found = player().getInventory().getStack(i).copy();
            return found;
        });
    }
    private int papersOf(io.github.mobtalk.core.CivilRegistry.Paper paper) {
        return onServer(() -> { int n = 0; for (int i = 0; i < player().getInventory().size(); i++) if (OfficialContent.paperOf(player().getInventory().getStack(i)) == paper) n++; return n; });
    }
    private int emeralds() { return onServer(() -> player().getInventory().count(Items.EMERALD)); }
    /** Clicks the window's line for {@code paper}; true when it was there. */
    private boolean take(io.github.mobtalk.core.CivilRegistry.Paper paper) {
        int n = line(paper);
        if (n > 0) test.server().execute(() -> test.bridge().papersChooseForTest(player(), n));
        return n > 0;
    }

    /** The player with his passport, married with a son: the clerk's window lists the ЗАГС papers, a click issues each at once, a
     * repeated certificate costs the fee; the head and the inspector have their own windows; a permit keeps the inspector away. */
    private void papers() {
        VillagerEntity clerk = official(Officialdom.Rank.CLERK);
        test.bridge().config.officialPapers = true;
        VillagerEntity wife = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG) && OfficialContent.rank(v) == null && !v.isBaby()).stream().findFirst().orElse(null));
        if (clerk == null || wife == null) { test.fail("no clerk or villager for the papers"); rush(); return; }
        UUID son = UUID.randomUUID();
        test.bridge().memory.familyForTest(player().getUuid(), name(), wife.getUuid(), "Аксинья", son, "Ваня");
        test.owner.client.inGameHud.getChatHud().clear(false);
        visit(clerk);
        test.await("the clerk's window (chat)", 3, () -> count("Окно приёма") >= 1, () -> {
            List<io.github.mobtalk.core.CivilRegistry.Paper> offered = menu().stream().map(io.github.mobtalk.core.CivilRegistry.Offer::paper).toList();
            test.log("clerk's window: " + offered);
            test.check(count("Окно приёма") >= 1 && paperCase() == null, "with a passport the clerk opens the papers window, not a new passport case");
            test.check(offered.contains(io.github.mobtalk.core.CivilRegistry.Paper.MARRIAGE_CERTIFICATE) && offered.contains(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE),
                    "the book of acts has the player's marriage and his son's birth");
            test.check(offered.contains(io.github.mobtalk.core.CivilRegistry.Paper.NO_RECORD) && !offered.contains(io.github.mobtalk.core.CivilRegistry.Paper.NOT_MARRIED),
                    "his own birth is on no record, and he is married");
            test.check(papersOf(io.github.mobtalk.core.CivilRegistry.Paper.MARRIAGE_CERTIFICATE) == 1 && count("брак зарегистрирован") >= 1,
                    "the marriage certificate is handed over at the registration itself");
            shot("mobtalk-papers-window.png", () -> {
                int before = emeralds();
                take(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE);
                take(io.github.mobtalk.core.CivilRegistry.Paper.NO_RECORD);
                take(io.github.mobtalk.core.CivilRegistry.Paper.FAMILY);
                test.await("the papers are issued", 3, () -> papersOf(io.github.mobtalk.core.CivilRegistry.Paper.FAMILY) == 1, () -> {
                    test.check(papersOf(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE) == 1
                            && papersOf(io.github.mobtalk.core.CivilRegistry.Paper.NO_RECORD) == 1, "a click issues each paper at once");
                    test.check(emeralds() == before, "the first certificates and the справки are free");
                    ItemStack marriage = paper(io.github.mobtalk.core.CivilRegistry.Paper.MARRIAGE_CERTIFICATE);
                    test.check(marriage.getName().getString().equals("Свидетельство о заключении брака") && marriage.contains(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA),
                            "the certificate is named after its form and has its own look");
                    take(io.github.mobtalk.core.CivilRegistry.Paper.MARRIAGE_CERTIFICATE);
                    test.await("the repeated certificate", 3, () -> papersOf(io.github.mobtalk.core.CivilRegistry.Paper.MARRIAGE_CERTIFICATE) == 2, () -> {
                        test.check(emeralds() == before - test.bridge().config.passportFee, "a repeated certificate costs the state fee (" + (before - emeralds()) + ")");
                        tooltipStack = marriage; tooltipSecond = paper(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE);
                        chat(false); // the officials' lines would cover the papers
                        test.owner.client.inGameHud.getChatHud().clear(false);
                        shot("mobtalk-papers-zags.png", () -> {
                            tooltipStack = paper(io.github.mobtalk.core.CivilRegistry.Paper.NO_RECORD); tooltipSecond = paper(io.github.mobtalk.core.CivilRegistry.Paper.FAMILY);
                            shot("mobtalk-papers-notes.png", () -> { tooltipStack = ItemStack.EMPTY; tooltipSecond = ItemStack.EMPTY; chat(true); headPapers(); });
                        });
                    });
                });
            });
        });
    }
    private void headPapers() {
        VillagerEntity head = official(Officialdom.Rank.HEAD), inspector = official(Officialdom.Rank.INSPECTOR);
        if (head == null || inspector == null) { test.fail("no head or inspector for the papers"); rush(); return; }
        near(head);
        test.await("pause: at the head's", 1, () -> false, () -> headWindow(head, inspector));
    }
    private void chat(boolean shown) {
        test.owner.client.execute(() -> test.owner.client.options.getChatVisibility().setValue(shown ? net.minecraft.network.message.ChatVisibility.FULL : net.minecraft.network.message.ChatVisibility.HIDDEN));
    }
    /** The player steps up to the official (a click on the window needs him within reach). */
    private void near(VillagerEntity official) {
        BlockPos at = onServer(official::getBlockPos);
        test.command(String.format(java.util.Locale.ROOT, "tp %s %d %d %d", name(), at.getX() + 1, at.getY(), at.getZ() + 1));
    }
    private void headWindow(VillagerEntity head, VillagerEntity inspector) {
        visit(head);
        test.check(line(io.github.mobtalk.core.CivilRegistry.Paper.REFERENCE) > 0 && line(io.github.mobtalk.core.CivilRegistry.Paper.MARRIAGE_CERTIFICATE) < 0, "the head's window has his own papers (the character reference), not the ЗАГС ones");
        take(io.github.mobtalk.core.CivilRegistry.Paper.REFERENCE);
        take(io.github.mobtalk.core.CivilRegistry.Paper.ALIVE);
        test.await("the head's papers", 3, () -> papersOf(io.github.mobtalk.core.CivilRegistry.Paper.ALIVE) == 1, () -> {
            test.check(papersOf(io.github.mobtalk.core.CivilRegistry.Paper.REFERENCE) == 1, "the head issues a character reference");
            near(inspector);
            test.await("pause: at the inspector's", 1, () -> false, () -> inspectorWindow(inspector));
        });
    }
    private void inspectorWindow(VillagerEntity inspector) {
        {
            visit(inspector);
            int before = emeralds();
            take(io.github.mobtalk.core.CivilRegistry.Paper.BUILD_PERMIT);
            test.await("the building permit", 3, () -> papersOf(io.github.mobtalk.core.CivilRegistry.Paper.BUILD_PERMIT) == 1, () -> {
                test.check(emeralds() == before - test.bridge().config.passportFee, "the building permit costs the state fee");
                String zone = onServer(() -> test.bridge().zoneKeyForTest(inspector.getBlockPos(), world()));
                long day = Math.floorDiv(onServer(() -> world().getServer().getOverworld().getTimeOfDay()), 24_000L);
                test.check(!zone.isEmpty() && !test.bridge().memory.officialMayFine(zone, player().getUuid(), day), "with a permit the inspector writes no orders");
                tooltipStack = paper(io.github.mobtalk.core.CivilRegistry.Paper.REFERENCE); tooltipSecond = paper(io.github.mobtalk.core.CivilRegistry.Paper.BUILD_PERMIT);
                chat(false);
                test.owner.client.inGameHud.getChatHud().clear(false);
                shot("mobtalk-papers-head.png", () -> {
                    tooltipStack = ItemStack.EMPTY; tooltipSecond = ItemStack.EMPTY;
                    chat(true);
                    // Without a passport: a daughter is born, the clerk hands over her uncollected certificate, the next visit opens a case.
                    test.server().execute(() -> { for (int i = 0; i < player().getInventory().size(); i++) if (player().getInventory().getStack(i).isOf(OfficialContent.PASSPORT)) player().getInventory().setStack(i, ItemStack.EMPTY); });
                    VillagerEntity wife = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG) && OfficialContent.rank(v) == null && !v.isBaby()).stream().findFirst().orElse(null));
                    test.bridge().memory.familyForTest(player().getUuid(), name(), wife.getUuid(), "Аксинья", UUID.randomUUID(), "Маша");
                    near(official(Officialdom.Rank.CLERK));
                    test.await("pause: the passport put away, back at the clerk's", 1, () -> false, this::uncollected);
                });
            });
        }
    }

    private void uncollected() {
        VillagerEntity clerk = official(Officialdom.Rank.CLERK);
        visit(clerk);
        test.await("the window without a passport (chat)", 3, () -> count("Паспорта у вас нет") >= 1, () -> uncollectedTake(clerk));
    }
    private void uncollectedTake(VillagerEntity clerk) {
        List<io.github.mobtalk.core.CivilRegistry.Paper> offered = menu().stream().map(io.github.mobtalk.core.CivilRegistry.Offer::paper).toList();
        test.check(count("Паспорта у вас нет") >= 1 && offered.contains(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE) && offered.stream().allMatch(p -> p.certificate),
                "without a passport the clerk still gives the uncollected certificates, and only them (" + offered + ")");
        take(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE);
        test.await("the daughter's certificate", 3, () -> papersOf(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE) == 2, () -> {
            test.check(papersOf(io.github.mobtalk.core.CivilRegistry.Paper.BIRTH_CERTIFICATE) == 2, "it is issued without a passport");
            visit(clerk);
            test.check(paperCase() != null, "the next Shift+right-click opens a passport case");
            test.bridge().memory.familyClearForTest(player().getUuid());
            rush();
        });
    }

    /** A new case and an envelope to the corrupt clerk: the passport at once, no papers. */
    private void rush() {
        VillagerEntity clerk = official(Officialdom.Rank.CLERK);
        visit(clerk);
        test.check(paperCase() != null, "the clerk opens a second case");
        int relation = test.bridge().memory.relationship(clerk.getUuid(), player().getUuid());
        int price = Officialdom.price(Officialdom.Rank.CLERK, clerk.getUuid(), relation, 0, test.bridge().config.officialPricePercent, 0);
        // Nobody about: the plain villagers are sent off so the clerk takes it.
        test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, clerk.getBoundingBox().expand(12), v -> OfficialContent.rank(v) == null && v.getCommandTags().contains(TAG))) v.refreshPositionAndAngles(v.getX() + 40, v.getY(), v.getZ() + 40, 0f, 0f); });
        test.await("pause: nobody about", 1, () -> false, () -> {
            hands(OfficialContent.envelope(price), ItemStack.EMPTY);
            String said = give(clerk);
            test.log("clerk rush: " + said);
            test.check(said.contains("Паспорт готов"), "an envelope to a corrupt clerk gets the passport at once");
            test.check(paperCase() == null && passports() == 1, "the case is closed and the new passport is in the pocket");
            sweet(clerk);
        });
    }

    /** A cookie to the corrupt clerk gets the player past the queue. */
    private void sweet(VillagerEntity clerk) {
        test.bridge().config.queueSeconds = 30;
        test.server().execute(() -> { for (int i = 0; i < player().getInventory().size(); i++) if (player().getInventory().getStack(i).isOf(OfficialContent.PASSPORT)) player().getInventory().setStack(i, ItemStack.EMPTY); });
        test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, clerk.getBoundingBox().expand(60), v -> OfficialContent.rank(v) == null && v.getCommandTags().contains(TAG))) v.refreshPositionAndAngles(clerk.getX() + 3, v.getY(), clerk.getZ() + 3, 0f, 0f); });
        test.await("pause: the queue comes back", 1, () -> false, () -> {
            visit(clerk);
            int ahead = onServer(() -> test.bridge().queueAheadForTest(player().getUuid()));
            hands(new ItemStack(Items.COOKIE, 2), ItemStack.EMPTY);
            String said = give(clerk);
            test.log("cookie in the queue (" + ahead + " ahead): " + said);
            test.check(ahead <= 0 || said.contains("гостинец"), "a cookie to the corrupt clerk is taken");
            test.check(onServer(() -> test.bridge().queueAheadForTest(player().getUuid())) < 0 && paperCase() != null, "and the player is served past the queue (a case is open)");
            test.bridge().config.queueSeconds = savedQueue;
            dacha();
        });
    }

    // ---- Stages 3–4: the dacha, heaven, the black ledger, the revizor, «Охрана!» ----

    /** The plot in the stage's north-west corner, its gate to the south (toward the bell): local (x, z) → (-x, -z). */
    private BlockPos plotAt(int x, int y, int z) { return new BlockPos(fx - 26 - x, fy + y, fz - 23 - z); }
    private io.github.mobtalk.core.Graft.Estate estate(VillagerEntity head) { return test.bridge().memory.estate(head.getUuid(), null, null); }

    /** The corrupt head, three envelopes taken, gets the whole dacha at once; the black ledger lies in the cottage's safe. */
    private void dacha() {
        VillagerEntity head = official(Officialdom.Rank.HEAD);
        if (head == null) { test.fail("no head for the dacha"); f7(); return; }
        var memory = test.bridge().memory;
        while (memory.officialTaken(head.getUuid()) < io.github.mobtalk.core.Graft.LEDGER_ENVELOPES) memory.officialTook(head.getUuid());
        test.bridge().config.revizorSeconds = 12;
        int built = onServer(() -> test.bridge().dachaForTest(head, bell(), plotAt(0, 0, 0), Direction.SOUTH.getHorizontal(), 5 * test.bridge().config.dachaPoints));
        test.check(built == io.github.mobtalk.core.Graft.Stage.TOILET.ordinal(), "the corrupt head's dacha grows to the golden toilet (" + built + ")");
        test.await("the dacha's news in the chat", 5, () -> count("золотой унитаз под стеклом") >= 1, () -> {
            test.check(count("появился участок") >= 1 && count("золотой унитаз под стеклом") >= 1, "the village hears of each new piece of the dacha");
            boolean gold = onServer(() -> world().getBlockState(plotAt(10, 1, 4)).isOf(net.minecraft.block.Blocks.GOLD_BLOCK));
            test.check(gold, "the golden toilet stands under glass");
            boolean ledger = onServer(() -> world().getBlockEntity(plotAt(8, 0, 10)) instanceof net.minecraft.block.entity.ChestBlockEntity chest && chest.getStack(13).isOf(OfficialContent.LEDGER));
            test.check(ledger && estate(head).ledger(), "the black ledger lies in the cottage's safe after three envelopes");
            test.command("tp " + name() + " " + (fx - 32) + " " + (fy + 3) + " " + (fz - 17) + " 180 12");
            test.await("pause: the dacha chunks render", 3, () -> false, () -> shot("mobtalk-graft-dacha.png", () -> smite(head)));
        });
    }

    /** Heaven: a lightning into the dacha, toads in the office, the golden toilet turned to dung. */
    private void smite(VillagerEntity head) {
        test.server().execute(() -> test.bridge().smiteForTest(head, bell(), "LIGHTNING"));
        test.await("the lightning (chat)", 5, () -> count("молния ударила в дачу") >= 1, () -> {
            test.check(count("молния ударила в дачу") >= 1, "heaven strikes the dacha with a lightning");
            test.server().execute(() -> test.bridge().smiteForTest(head, bell(), "DUNG"));
            test.await("the dung (chat)", 5, () -> count("обратился в навозный") >= 1, () -> {
                boolean mud = onServer(() -> world().getBlockState(plotAt(10, 1, 4)).isOf(net.minecraft.block.Blocks.MUD_BRICKS));
                test.check(mud && estate(head).dung(), "the golden toilet turns to dung");
                shot("mobtalk-graft-dung.png", () -> {
                    test.server().execute(() -> test.bridge().smiteForTest(head, bell(), "TOADS"));
                    test.await("toads in the office", 5, () -> onServer(() -> world().getEntitiesByClass(net.minecraft.entity.passive.FrogEntity.class, area(), f -> f.getCommandTags().contains("mobtalk_toad")).size()) >= 5, () -> {
                        int toads = onServer(() -> world().getEntitiesByClass(net.minecraft.entity.passive.FrogEntity.class, area(), f -> f.getCommandTags().contains("mobtalk_toad")).size());
                        test.check(toads >= 5 && count("полон жаб") >= 1, "the head's office fills with toads (" + toads + ")");
                        BlockPos desk = onServer(() -> head.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(head.getBlockPos()));
                        test.command("tp " + name() + " " + desk.getX() + " " + (fy + 4) + " " + (desk.getZ() + 6) + " 180 35");
                        shot("mobtalk-graft-toads.png", () -> ledger(head));
                    });
                });
            });
        });
    }

    /** The ledger from the safe to an honest inspector: the revizor comes next day, panics the village and removes the head. */
    private void ledger(VillagerEntity head) {
        VillagerEntity inspector = official(Officialdom.Rank.INSPECTOR);
        if (inspector == null) { test.fail("no inspector for the ledger"); guard(); return; }
        ItemStack book = onServer(() -> world().getBlockEntity(plotAt(8, 0, 10)) instanceof net.minecraft.block.entity.ChestBlockEntity chest ? chest.removeStack(13) : ItemStack.EMPTY);
        test.check(book.isOf(OfficialContent.LEDGER), "the player takes the black ledger from the safe");
        BlockPos near = onServer(inspector::getBlockPos);
        test.command("tp " + name() + " " + near.getX() + " " + fy + " " + (near.getZ() + 3) + " 180 15");
        test.bridge().config.officialHonestPercent = 100;
        hands(book, ItemStack.EMPTY);
        String said = give(inspector);
        test.bridge().config.officialHonestPercent = 0;
        test.log("ledger to an honest inspector: " + said);
        String zone = onServer(() -> test.bridge().zoneKeyForTest(bell(), world()));
        test.check(said.contains("Передадим куда следует") && test.bridge().memory.revizorDay(zone) >= 0, "an honest official takes the ledger and calls the revizor");
        test.command("tp " + name() + " " + at(0, 7, 16) + " 180 30");
        test.command("time add 24000");
        test.await("the revizor arrives next day", 20, () -> count("едет ревизор") >= 1, () -> {
            test.check(count("едет ревизор") >= 1 && onServer(() -> test.bridge().inspectingForTest(bell(), world())), "next day the revizor from the capital arrives");
            shot("mobtalk-graft-revizor.png", () -> test.await("the verdict", 40, () -> count("снят с должности") >= 1, () -> {
                test.check(count("снят с должности") >= 1, "the revizor removes the corrupt head");
                test.check(onServer(() -> head.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.NITWIT), "the removed head is nobody now");
                test.check(estate(head).confiscated(), "the dacha is confiscated");
                boolean sign = onServer(() -> world().getBlockEntity(plotAt(8, 0, -1)) instanceof net.minecraft.block.entity.SignBlockEntity s && s.getFrontText().getMessage(0, false).getString().contains("КОНФИСКОВАНО"));
                test.check(sign, "a «КОНФИСКОВАНО» sign at the gate");
                test.command("tp " + name() + " " + (fx - 32) + " " + (fy + 3) + " " + (fz - 17) + " 180 12");
                test.await("pause: by the gate", 2, () -> false, () -> shot("mobtalk-graft-confiscated.png", this::guard));
            }));
        });
    }

    /** A player hits the clerk: «Охрана!». */
    private void guard() {
        // After the revizor the clerk may already be the new head: any official will do.
        VillagerEntity first = official(Officialdom.Rank.CLERK);
        VillagerEntity clerk = first != null ? first : officials().stream().filter(v -> OfficialContent.rank(v) != null).findFirst().orElse(null);
        if (clerk == null) { test.fail("no official to hit"); f7(); return; }
        BlockPos near = onServer(clerk::getBlockPos);
        test.command("tp " + name() + " " + near.getX() + " " + fy + " " + (near.getZ() + 2) + " 180 15");
        test.await("pause: by the clerk", 1, () -> false, () -> {
            test.server().execute(() -> clerk.damage(world().getDamageSources().playerAttack(player()), 0.5f));
            test.await("«Охрана!» (chat)", 5, () -> count("Охрана!") >= 1, () -> {
                test.check(count("Охрана!") >= 1, "a hit official shouts «Охрана!»");
                test.server().execute(() -> clerk.heal(5));
                f7();
            });
        });
    }

    /** F7 → Социум → «Чиновники»: four switches and ten sliders, each with its caption. */
    private void f7() {
        var client = test.owner.client;
        test.owner.openSettings(null);
        test.await("F7 opens", 5, () -> client.currentScreen instanceof MobTalkSettingsScreen, () -> {
            if (!(client.currentScreen instanceof MobTalkSettingsScreen screen)) { test.fail("F7 did not open"); finish(); return; }
            List<String> rows = screen.showGroup("social", "officials");
            test.log("«Чиновники» rows: " + rows);
            test.check(rows.contains("Чиновники и взятки"), "the group has its switch");
            test.check(rows.contains("Паспорт и справки") && rows.contains("Без паспорта не распишут"), "the passport switches are there");
            test.check(rows.size() == 23, "ten switches and thirteen sliders (" + rows.size() + ")");
            test.check(rows.stream().noneMatch(row -> row.isBlank() || row.startsWith("mobtalk.")), "every row has a caption, none is a raw key");
            shot("mobtalk-officials-f7.png", () -> { client.setScreen(null); finish(); });
        });
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        var config = test.bridge().config;
        config.villageTowers = savedTowers; config.villageGuards = savedGuards; config.officialHonestPercent = savedHonest; config.revizorSeconds = savedRevizor;
        test.owner.config().humanVillagers = savedHuman; test.owner.config().officialHonestPercent = savedClientHonest;
        test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG))) v.setAiDisabled(false); });
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: officials stage done", 2, () -> false, next);
    }
}
