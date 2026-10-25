package io.github.mobtalk.client;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.game.CasinoContent;
import io.github.mobtalk.game.CasinoWorldgen;
import io.github.mobtalk.server.ServerConfig;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** The casinos ({@code -Only casino}). The village casino: the five buildings placed from their templates on a grass field in the sky; the casino in
 * the house pools of the five village kinds; every piece of furniture where the plan puts it; the two bouncers tough and at their ropes;
 * a visitor sitting down to play; the slot machine spinning; the table for 21 answering; a bouncer punching a player who hit a
 * villager. Screenshots outside, inside, of each piece and of the bouncer in both looks. The grand (city) casino: placed from its
 * template, its sign, chandeliers, cashiers, tables and four bouncers where the plan puts them; screenshots by day and by night.
 * No LLM, no voice. */
final class SelfTestCasino {
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private Runnable next = () -> { };
    private int chatMark, savedPerTable;
    private boolean savedHuman;

    SelfTestCasino(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 300; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private BlockPos o(int kind) { return new BlockPos(fx, fy - 1, fz + kind * 20); }
    private BlockPos at(int x, int y, int z) { return o(0).add(x, y, z); }
    private boolean heard(String... parts) {
        synchronized (chat) {
            for (String line : chat.subList(Math.min(chatMark, chat.size()), chat.size())) for (String p : parts) if (line.contains(p)) return true;
            return false;
        }
    }
    private void mark() { chatMark = chat.size(); }
    private Box area() { return new Box(o(0)).expand(24, 10, 24).offset(7, 0, 6); }

    void run(Runnable then) {
        next = then;
        MobTalkConfig config = ServerConfig.get();
        savedPerTable = config.casinoPlayersPerTable; savedHuman = test.bridge().config.humanVillagers;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("casino stages: five buildings, furniture, bouncers, a visitor, the games");
        test.command("time set 1000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode creative " + name());
        test.command("tp " + name() + " " + (fx - 6) + " " + (fy + 3) + " " + (fz + 6));
        test.await("pause: the stage chunks load", 4, () -> false, this::build);
    }

    private void build() {
        test.command("fill " + (fx - 12) + " " + (fy - 2) + " " + (fz - 8) + " " + (fx + 20) + " " + (fy - 2) + " " + (fz + 100) + " minecraft:stone");
        test.command("fill " + (fx - 12) + " " + (fy - 1) + " " + (fz - 8) + " " + (fx + 20) + " " + (fy - 1) + " " + (fz + 100) + " minecraft:grass_block");
        test.command("fill " + (fx - 12) + " " + fy + " " + (fz - 8) + " " + (fx + 20) + " " + (fy + 12) + " " + (fz + 100) + " minecraft:air");
        for (int i = 0; i < CasinoWorldgen.KINDS.size(); i++) {
            BlockPos p = o(i);
            test.command("place template mobtalk:casino/" + CasinoWorldgen.KINDS.get(i) + " " + p.getX() + " " + p.getY() + " " + p.getZ());
            // A village turns the jigsaw into its final state (the red carpet); a template placed by hand keeps it.
            test.command("setblock " + (p.getX()) + " " + (p.getY() + 1) + " " + (p.getZ() + 5) + " minecraft:red_carpet");
        }
        test.command("kill @e[type=minecraft:item]");
        test.await("the casino stands", 20, () -> onServer(() -> world().getBlockState(at(10, 1, 5)).isOf(CasinoContent.BLACKJACK)), this::checkPlan);
    }

    private void checkPlan() {
        onServer(() -> {
            ServerWorld w = world();
            test.check(w.getBlockState(at(0, 4, 6)).isOf(CasinoContent.SIGN), "the sign over the door");
            for (int x = 4; x <= 8; x++) test.check(w.getBlockState(at(x, 1, 1)).isOf(CasinoContent.SLOT) && w.getBlockState(at(x, 2, 1)).isOf(CasinoContent.SLOT), "slot machine " + x);
            test.check(w.getBlockState(at(10, 1, 2)).isOf(CasinoContent.ROULETTE), "the roulette");
            test.check(w.getBlockState(at(5, 1, 9)).isOf(CasinoContent.POKER), "the card table");
            for (int z = 8; z <= 10; z++) test.check(w.getBlockState(at(10, 1, z)).isOf(CasinoContent.BAR_COUNTER) && w.getBlockState(at(12, 1, z)).isOf(CasinoContent.BAR_SHELF), "the bar " + z);
            test.check(w.getBlockState(at(9, 1, 4)).isOf(CasinoContent.PART) && w.getBlockState(at(10, 1, 6)).isOf(CasinoContent.PART), "the table's parts");
            for (int i = 1; i < CasinoWorldgen.KINDS.size(); i++)
                test.check(w.getBlockState(o(i).add(10, 1, 5)).isOf(CasinoContent.BLACKJACK), CasinoWorldgen.KINDS.get(i) + " casino placed");
            var pools = w.getRegistryManager().get(RegistryKeys.TEMPLATE_POOL);
            for (String kind : CasinoWorldgen.KINDS) {
                StructurePool pool = pools.get(Identifier.ofVanilla("village/" + kind + "/houses"));
                long n = pool == null ? 0 : pool.getElementIndicesInRandomOrder(net.minecraft.util.math.random.Random.create(1)).stream()
                        .filter(e -> e.toString().contains("mobtalk:casino/" + kind)).count();
                test.check(n == ServerConfig.get().casinoWeight, kind + " village houses hold the casino (weight " + n + ")");
            }
            return null;
        });
        view(-9, 3.5, 6, 6, 3, 6);  // the bouncers only work near a player
        test.await("the bouncers are there", 10, () -> bouncers().size() >= 2, this::checkBouncers);
    }

    private List<VillagerEntity> bouncers() {
        return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, new Box(o(0)).expand(16, 6, 16).offset(7, 0, 6), CasinoContent::bouncer));
    }

    private void checkBouncers() {
        test.await("the bouncers toughen", 10, () -> bouncers().stream().allMatch(b -> b.getMaxHealth() >= ServerConfig.get().bouncerHealth && b.getArmor() >= 8), () -> {
            for (VillagerEntity b : bouncers()) {
                test.check(b.getMaxHealth() >= ServerConfig.get().bouncerHealth, "a bouncer is tough (" + b.getMaxHealth() + ")");
                test.check(b.getArmor() >= 8, "a bouncer wears hidden armour (" + b.getArmor() + ")");
            }
            outside();
        });
    }

    // ---- Screenshots ----

    private void view(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        BlockPos b = o(0);
        test.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f %.1f", name(), b.getX() + x, b.getY() + y, b.getZ() + z, yaw, pitch));
    }

    private void shot(String file, Runnable then) {
        test.owner.client.options.hudHidden = true;
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, () -> { test.owner.client.options.hudHidden = false; then.run(); });
        });
    }

    private void outside() {
        test.command("gamemode spectator " + name());
        view(-9, 3.5, 6, 6, 3, 6);
        shot("mobtalk-casino-01-front.png", () -> { view(-8, 9, -6, 7, 2, 6);
        shot("mobtalk-casino-02-corner.png", () -> { view(-3, 4.6, 6, 0, 4.5, 6);
        shot("mobtalk-casino-03-sign.png", () -> { view(2.5, 2.6, 5.5, 11, 1.2, 5.5);
        shot("mobtalk-casino-04-inside-door.png", () -> { view(11.5, 3.5, 10.3, 3, 1, 2);
        shot("mobtalk-casino-05-inside-back.png", () -> { view(6.5, 2.6, 4.6, 6.5, 2, 1);
        shot("mobtalk-casino-06-slots.png", () -> { view(7.2, 3.2, 5.5, 10.5, 1.8, 5.5);
        shot("mobtalk-casino-07-blackjack.png", () -> { view(7.5, 3.2, 2.5, 10.5, 1.8, 2.5);
        shot("mobtalk-casino-08-roulette.png", () -> { view(5.5, 4.2, 5.5, 5.5, 1.8, 9);
        shot("mobtalk-casino-09-poker.png", () -> { view(7.5, 2.8, 9.5, 12, 2, 9.5);
        shot("mobtalk-casino-10-bar.png", () -> { bouncerView();
        shot("mobtalk-casino-11-bouncer-human.png", this::vanillaLook);
        }); }); }); }); }); }); }); }); }); });
    }

    /** In front of a bouncer, wherever it stands, at its eye level. */
    private void bouncerView() {
        List<VillagerEntity> all = bouncers();
        if (all.isEmpty()) { view(5, 1.7, 3, 2, 2, 2.5); return; }
        // Back to its post by the rope, facing the hall, so the camera inside sees it whole.
        BlockPos o = o(0);
        test.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f -90 0", all.getFirst().getUuidAsString(), o.getX() + 2.5, o.getY() + 1.0, o.getZ() + 2.5));
        view(5.4, 1.0, 3.4, 2.5, 1.4, 2.5);  // view() aims from the feet: 1.4 here is the bouncer's chest at eye level
    }

    private void vanillaLook() {
        test.bridge().config.humanVillagers = false;
        io.github.mobtalk.client.human.HumanLooks.enabled = false;
        test.await("pause: vanilla looks", 2, () -> false, () -> { bouncerView(); shot("mobtalk-casino-12-bouncer-vanilla.png", () -> {
            test.bridge().config.humanVillagers = savedHuman;
            io.github.mobtalk.client.human.HumanLooks.enabled = savedHuman;
            others(1);
        }); });
    }

    private void others(int i) {
        if (i >= CasinoWorldgen.KINDS.size()) { grand(); return; }
        BlockPos b = o(i).subtract(o(0));
        view(b.getX() - 9, b.getY() + 3.5, b.getZ() + 6, b.getX() + 6, b.getY() + 3, b.getZ() + 6);
        shot("mobtalk-casino-13-" + CasinoWorldgen.KINDS.get(i) + ".png", () -> others(i + 1));
    }

    // ---- The grand casino (a city's) ----

    /** The grand casino's lot origin: ground surface (y = 4 in the template) on the field's grass level, well past the village ones. */
    private BlockPos g() { return new BlockPos(fx, fy - 1 - 4, fz + 120); }

    private void gview(double x, double y, double z, double tx, double ty, double tz) {
        BlockPos b = g().subtract(o(0));
        view(b.getX() + x, b.getY() + y, b.getZ() + z, b.getX() + tx, b.getY() + ty, b.getZ() + tz);
    }

    private void grand() {
        test.command("gamemode spectator " + name());
        gview(-16, 12, 10, 8, 8, 10);
        test.await("pause: the grand lot's chunks load", 4, () -> false, () -> {
            BlockPos b = g();
            test.command("fill " + (b.getX() - 24) + " " + (b.getY() + 3) + " " + (b.getZ() - 6) + " " + (b.getX() + 30) + " " + (b.getY() + 3) + " " + (b.getZ() + 26) + " minecraft:grass_block");
            test.command("fill " + (b.getX() - 24) + " " + (b.getY() + 4) + " " + (b.getZ() - 6) + " " + (b.getX() + 30) + " " + (b.getY() + 30) + " " + (b.getZ() + 26) + " minecraft:air");
            test.command("place template mobtalk:casino/grand " + b.getX() + " " + b.getY() + " " + b.getZ());
            // a city turns the connector into its final state (the red carpet); a template placed by hand keeps it
            test.command("setblock " + b.getX() + " " + (b.getY() + 5) + " " + (b.getZ() + 10) + " minecraft:red_carpet");
            test.command("kill @e[type=minecraft:item]");
            test.await("the grand casino stands", 20, () -> onServer(() -> world().getBlockState(b.add(1, 14, 10)).isOf(CasinoContent.LETTER)), this::checkGrand);
        });
    }

    private void checkGrand() {
        BlockPos b = g();
        int floor = 5, stand = 6, gallery = 11;
        onServer(() -> {
            ServerWorld w = world();
            String word = "";
            for (int z = 4; z <= 16; z++) {  // the top row of the sign: a letter starts at each part 0
                var state = w.getBlockState(b.add(1, floor + 9, z));
                if (!state.isOf(CasinoContent.LETTER)) { word += '?'; continue; }
                if (state.get(io.github.mobtalk.game.CasinoBlocks.Letter.PART) == 0)
                    word += "КАЗИНО★".charAt(state.get(io.github.mobtalk.game.CasinoBlocks.Letter.LETTER));
            }
            test.check(word.equals("КАЗ★ИНО"), "the sign reads КАЗ★ИНО (" + word + ")");
            test.check(w.getBlockState(b.add(7, floor + 16, 10)).isOf(CasinoContent.GRAND_CHANDELIER)
                    && w.getBlockState(b.add(13, floor + 16, 10)).isOf(CasinoContent.GRAND_CHANDELIER), "two crystal chandeliers over the hall");
            test.check(w.getBlockState(b.add(5, stand, 7)).isOf(CasinoContent.CASHIER), "the cashiers by the door");
            test.check(w.getBlockState(b.add(9, stand, 16)).isOf(CasinoContent.BLACKJACK), "twenty-one in the south wing");
            test.check(w.getBlockState(b.add(9, stand, 4)).isOf(CasinoContent.ROULETTE), "roulette in the north wing");
            test.check(w.getBlockState(b.add(8, gallery + 1, 3)).isOf(CasinoContent.POKER), "private card tables on the gallery");
            return null;
        });
        test.await("the grand bouncers are there", 10, () -> onServer(() -> world().getEntitiesByClass(VillagerEntity.class,
                new Box(b).expand(4).stretch(24, 26, 21), CasinoContent::bouncer).size() >= 4), () -> {
            gview(-17, 7, 10, 8, 10, 10);
            shot("mobtalk-casino-g01-front.png", () -> { gview(-12, 22, -10, 12, 8, 10);
            shot("mobtalk-casino-g02-aerial.png", () -> { gview(-9, 12.4, 10, 2, 12.4, 10);
            shot("mobtalk-casino-g03-sign.png", () -> { gview(4.5, 7.6, 10, 16, 9, 10);
            shot("mobtalk-casino-g04-hall.png", () -> { gview(21.5, 13, 10, 4, 6, 10);
            shot("mobtalk-casino-g05-from-gallery.png", () -> { gview(12.5, 7.6, 7, 12.5, 6.5, 1.5);
            shot("mobtalk-casino-g06-slots-roulette.png", () -> { gview(12.5, 8, 13, 12.5, 6, 18);
            shot("mobtalk-casino-g07-twenty-one.png", () -> { gview(10.5, 16, 12, 7, 19.5, 10);
            shot("mobtalk-casino-g08-chandelier.png", () -> { gview(17.4, 7.6, 10, 22.5, 6.5, 10);
            shot("mobtalk-casino-g09-bar.png", () -> { gview(11.5, 13, 6.5, 8, 12, 3);
            shot("mobtalk-casino-g10-gallery.png", () -> { gview(5.5, 7.6, 10, 5, 7, 6);
            shot("mobtalk-casino-g11-cashiers.png", () -> {
                test.command("time set 18000");
                test.await("pause: night falls", 2, () -> false, () -> { gview(-17, 7, 10, 8, 10, 10);
                shot("mobtalk-casino-g12-night.png", () -> { gview(-9, 12.4, 10, 2, 12.4, 10);
                shot("mobtalk-casino-g13-sign-night.png", () -> { test.command("time set 1000"); resort(); }); }); });
            }); }); }); }); }); }); }); }); }); }); });
        });
    }

    // ---- The resort «Фортуна» ----

    /** The resort's template origin: its paving (y = 2 in the template) on the field's grass level, past the grand casino. */
    private BlockPos r() { return new BlockPos(fx - 20, fy - 1 - 2, fz + 170); }

    private void rview(double x, double y, double z, double tx, double ty, double tz) {
        BlockPos b = r().subtract(o(0));
        view(b.getX() + x, b.getY() + y, b.getZ() + z, b.getX() + tx, b.getY() + ty, b.getZ() + tz);
    }

    private void resort() {
        test.command("gamemode spectator " + name());
        BlockPos b = r();
        String box = b.getX() + " " + b.getZ() + " " + (b.getX() + 79) + " " + (b.getZ() + 79);
        test.command("forceload add " + box);
        rview(40, 45, -40, 40, 15, 45);
        test.await("pause: the resort's chunks load", 6, () -> false, () -> {
            test.command("fill " + (b.getX() - 30) + " " + (b.getY() + 2) + " " + (b.getZ() - 50) + " " + (b.getX() + 110) + " " + (b.getY() + 2) + " " + (b.getZ() - 1) + " minecraft:grass_block");
            test.command("place template mobtalk:casino/resort " + b.getX() + " " + b.getY() + " " + b.getZ());
            test.command("kill @e[type=minecraft:item]");
            test.await("the resort stands", 30, () -> onServer(() -> world().getBlockState(b.add(40, 32, 42)).isOf(Blocks.GOLD_BLOCK)
                    && world().getBlockState(b.add(40, 4, 42)).isOf(Blocks.SEA_LANTERN)), this::checkResort);
        });
    }

    private void checkResort() {
        BlockPos b = r();
        onServer(() -> {
            ServerWorld w = world();
            test.check(w.getRegistryManager().get(net.minecraft.registry.RegistryKeys.STRUCTURE).containsId(Identifier.of("mobtalk", "casino_resort")),
                    "the resort is a worldgen structure");
            test.check(w.getRegistryManager().get(net.minecraft.registry.RegistryKeys.TEMPLATE_POOL).containsId(Identifier.of("mobtalk", "casino/resort")),
                    "the resort's template pool");
            test.check(readSign(w, b, 47, 12, 16, 34).equals("★КАЗИНО★"), "the portico reads ★КАЗИНО★ (" + readSign(w, b, 47, 12, 16, 34) + ")");
            test.check(w.getBlockState(b.add(74, 34, 1)).isOf(CasinoContent.LETTER), "the pylon's letters");
            test.check(w.getBlockState(b.add(40, 20, 42)).isOf(CasinoContent.GRAND_CHANDELIER), "the great chandelier under the dome");
            test.check(w.getBlockState(b.add(32, 4, 40)).isOf(CasinoContent.BLACKJACK), "twenty-one in the pit");
            test.check(w.getBlockState(b.add(35, 4, 35)).isOf(CasinoContent.ROULETTE), "roulette in the pit");
            test.check(w.getBlockState(b.add(36, 4, 50)).isOf(CasinoContent.POKER), "cards in the pit");
            test.check(w.getBlockState(b.add(8, 2, 8)).isOf(CasinoContent.FOUNTAIN), "the fountain's jets");
            test.check(w.getBlockState(b.add(40, 13, 27)).isOf(CasinoContent.COFFER), "the coffered ceiling");
            int slots = 0;
            for (BlockPos p : BlockPos.iterate(b.add(20, 4, 22), b.add(60, 4, 62))) if (w.getBlockState(p).isOf(CasinoContent.SLOT)) slots++;
            test.check(slots >= 60, "slot machines all round (" + slots + ")");
            return null;
        });
        test.await("the resort's bouncers are there", 10, () -> onServer(() -> world().getEntitiesByClass(VillagerEntity.class,
                new Box(b).stretch(80, 20, 80), CasinoContent::bouncer).size() >= 4), () -> {
            rview(40, 45, -40, 40, 15, 45);
            shot("mobtalk-casino-r01-aerial.png", () -> { rview(40, 5, -10, 40, 16, 40);
            shot("mobtalk-casino-r02-front.png", () -> { rview(58, 22, -10, 73.5, 20, 2);
            shot("mobtalk-casino-r03-pylon.png", () -> { rview(40, 11, 4, 40, 11.5, 16);
            shot("mobtalk-casino-r04-portico.png", () -> { rview(40, 7, 25, 40, 5, 50);
            shot("mobtalk-casino-r05-hall.png", () -> { rview(40, 6, 33, 40, 26, 42);
            shot("mobtalk-casino-r06-dome-inside.png", () -> { rview(31, 7, 44, 23, 7, 42);
            shot("mobtalk-casino-r07-slots.png", () -> { rview(36.5, 6.5, 37, 32, 4, 40.5);
            shot("mobtalk-casino-r08-twenty-one.png", () -> { rview(37, 6, 32, 35, 4, 35.5);
            shot("mobtalk-casino-r09-roulette.png", () -> { rview(38.5, 6.5, 47, 36, 4, 50.5);
            shot("mobtalk-casino-r10-cards.png", () -> { rview(46, 6, 47, 40, 5, 42);
            shot("mobtalk-casino-r11-bar.png", () -> { rview(26.2, 5.6, 42.5, 23.5, 5.2, 42.5);
            shot("mobtalk-casino-r12-slot-machine.png", () -> { rview(40, 40, 110, 40, 15, 40);
            shot("mobtalk-casino-r13-back.png", () -> {
                test.command("time set 18000");
                test.await("pause: night falls", 3, () -> false, () -> { rview(40, 45, -40, 40, 15, 45);
                shot("mobtalk-casino-r14-aerial-night.png", () -> { rview(40, 5, -10, 40, 16, 40);
                shot("mobtalk-casino-r15-front-night.png", () -> { rview(40, 7, 25, 40, 5, 50);
                shot("mobtalk-casino-r16-hall-night.png", () -> { rview(58, 22, -10, 73.5, 20, 2);
                shot("mobtalk-casino-r17-pylon-night.png", () -> {
                    test.command("time set 1000");
                    test.command("forceload remove " + b.getX() + " " + b.getZ() + " " + (b.getX() + 79) + " " + (b.getZ() + 79));
                    visitor();
                }); }); }); }); });
            }); }); }); }); }); }); }); }); }); }); }); }); });
        });
    }

    /** A row of two-block neon letters facing north (the text runs to -x), its top row at y: the letters whose part 0 is there. */
    private static String readSign(ServerWorld w, BlockPos b, int fromX, int y, int z, int toX) {
        StringBuilder out = new StringBuilder();
        for (int x = fromX; x >= toX; x--) {
            var state = w.getBlockState(b.add(x, y, z));
            if (!state.isOf(CasinoContent.LETTER)) { out.append('?'); continue; }
            if (state.get(io.github.mobtalk.game.CasinoBlocks.Letter.PART) == 0)
                out.append("КАЗИНО★ФРТУ".charAt(state.get(io.github.mobtalk.game.CasinoBlocks.Letter.LETTER)));
        }
        return out.toString();
    }

    // ---- Life ----

    private void visitor() {
        ServerConfig.get().casinoPlayersPerTable = 4;
        test.command("time set 6000");
        view(3, 4.5, 3, 9, 1, 7);  // visitors come to the tables near a player
        for (int i = 0; i < 4; i++) test.command("summon minecraft:villager " + (o(0).getX() + 6) + " " + (fy) + " " + (o(0).getZ() + 4 + i % 2) + " {PersistenceRequired:1b}");
        test.await("a visitor sits down to play", 90, () -> onServer(() -> !world().getEntitiesByClass(VillagerEntity.class, area(),
                v -> v.getVehicle() instanceof ArmorStandEntity && !CasinoContent.bouncer(v)).isEmpty()), () -> {
            view(3, 4.5, 3, 9, 1, 7);
            shot("mobtalk-casino-14-visitors.png", this::games);
        });
    }

    private void use(BlockPos pos, Direction side) {
        test.owner.client.execute(() -> test.owner.client.interactionManager.interactBlock(test.owner.client.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false)));
    }

    private void games() {
        test.command("gamemode creative " + name());
        view(6.5, 1, 4, 6.5, 2.5, 1);
        mark();
        test.await("pause: in front of a slot machine", 1, () -> false, () -> {
            use(at(6, 1, 1), Direction.SOUTH);
            test.await("the slot machine spins", 6, () -> heard("for fun", "на интерес"), () -> {
                test.check(heard("for fun", "на интерес"), "the slot machine plays for fun");
                mark();
                view(7.5, 1, 5.5, 10.5, 1.5, 5.5);
                test.await("pause: at the table for 21", 1, () -> false, () -> {
                    use(at(9, 1, 5), Direction.WEST);
                    test.await("the table for 21 answers", 6, () -> heard("game of 21", "игры в 21", "to deal", "сдавать", "Bring someone", "Приведи"), this::trouble);
                });
            });
        });
    }

    private void trouble() {
        test.command("gamemode survival " + name());
        test.command("effect clear " + name());
        test.command("effect give " + name() + " minecraft:regeneration 60 1 true");
        test.command("summon minecraft:villager " + (o(0).getX() + 4) + " " + fy + " " + (o(0).getZ() + 6) + " {PersistenceRequired:1b,NoAI:1b,Tags:[\"casino_victim\"]}");
        view(3, 1, 6, 4.5, 1.5, 6);
        mark();
        test.await("pause: by the villager", 2, () -> false, () -> {
            Entity victim = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains("casino_victim"))
                    .stream().findFirst().orElse(null));
            test.check(victim != null, "a villager to hit");
            if (victim == null) { finish(); return; }
            test.owner.client.execute(() -> {
                Entity mine = test.owner.client.world.getEntityById(victim.getId());
                if (mine != null) test.owner.client.interactionManager.attackEntity(test.owner.client.player, mine);
            });
            float before = test.owner.client.player.getHealth();
            test.await("a bouncer throws the player out", 15, () -> heard("Out!", "На выход") && test.owner.client.player.getHealth() < before, () -> {
                test.check(heard("Out!", "На выход"), "the bouncer warns the troublemaker");
                test.check(test.owner.client.player.getHealth() < before, "the bouncer punches the troublemaker");
                finish();
            });
        });
    }

    private void finish() {
        ServerConfig.get().casinoPlayersPerTable = savedPerTable;
        test.command("gamemode creative " + name());
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: casino stage done", 2, () -> false, next);
    }
}
