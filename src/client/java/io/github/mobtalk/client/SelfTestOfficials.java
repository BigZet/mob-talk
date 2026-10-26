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
    private int savedHonest, savedClientHonest;

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
        savedTowers = config.villageTowers; savedGuards = config.villageGuards; savedHonest = config.officialHonestPercent; savedHuman = test.owner.config().humanVillagers;
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
                        f7();
                    });
                });
            });
        });
    }

    /** F7 → Социум → «Чиновники»: one switch and six sliders, each with its caption. */
    private void f7() {
        var client = test.owner.client;
        test.owner.openSettings(null);
        test.await("F7 opens", 5, () -> client.currentScreen instanceof MobTalkSettingsScreen, () -> {
            if (!(client.currentScreen instanceof MobTalkSettingsScreen screen)) { test.fail("F7 did not open"); finish(); return; }
            List<String> rows = screen.showGroup("social", "officials");
            test.log("«Чиновники» rows: " + rows);
            test.check(rows.contains("Чиновники и взятки"), "the group has its switch");
            test.check(rows.size() == 7, "one switch and six sliders (" + rows.size() + ")");
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
        config.villageTowers = savedTowers; config.villageGuards = savedGuards; config.officialHonestPercent = savedHonest;
        test.owner.config().humanVillagers = savedHuman; test.owner.config().officialHonestPercent = savedClientHonest;
        test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG))) v.setAiDisabled(false); });
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: officials stage done", 2, () -> false, next);
    }
}
